package com.amzstudios.cofre;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.service.notification.StatusBarNotification;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android file descriptors, SAF documents and service lifecycle; synthetic isolated data only. */
public class VaultTransfersTest {
    private static final String AUTHORITY="com.amzstudios.cofre.test.documents";
    private static final char[] PASSWORD="Teste integridade Android 2026".toCharArray();
    private static final int MIB=1024*1024;
    private static final VaultEngine.Progress QUIET=bytes->{};
    private Context context;
    private Instrumentation instrumentation;
    private File fixture,documents;
    private String documentId;
    private VaultEngine vault;
    private MainActivity activity;
    private final List<VaultEngine> opened=new ArrayList<>();
    private final List<VaultTransferService.Session> sessions=new ArrayList<>();

    @Before public void setUp()throws Exception{
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        String unique="transfer-test-"+UUID.randomUUID();
        fixture=new File(context.getFilesDir(),unique);assertTrue(fixture.mkdirs());
        documentId="export/"+unique;documents=new File(context.getFilesDir(),"fixture-"+documentId);assertTrue(documents.mkdirs());
        vault=engine("vault");vault.create(PASSWORD);
    }
    @After public void tearDown()throws Exception{
        for(VaultTransferService.Session session:sessions)if(session.running)session.paused=true;
        for(VaultTransferService.Session session:sessions)awaitSession(session);
        wakeScreen();
        if(activity!=null&&!activity.isDestroyed()){
            instrumentation.runOnMainSync(()->activity.finish());instrumentation.waitForIdleSync();
            Field worker=MainActivity.class.getDeclaredField("worker");worker.setAccessible(true);
            ((ExecutorService)worker.get(activity)).awaitTermination(30,TimeUnit.SECONDS);
        }
        for(VaultEngine engine:opened)engine.lock();
        // Both paths were generated under this test's private fixture directories.
        VaultEngine.removeTree(fixture);VaultEngine.removeTree(documents);
    }
    private VaultEngine engine(String name){VaultEngine engine=new VaultEngine(new File(fixture,name));opened.add(engine);return engine;}
    private Uri document(String name){return DocumentsContract.buildDocumentUri(AUTHORITY,documentId+"/"+name);}
    private Uri tree(){return DocumentsContract.buildTreeDocumentUri(AUTHORITY,documentId);}
    private VaultTransfers transfers(){return new VaultTransfers(context,vault);}
    private File source(String name,int bytes)throws Exception{
        File file=new File(documents,name);byte[] block=new byte[Math.min(MIB,bytes)];
        for(int i=0;i<block.length;i++)block[i]=(byte)(i*31+19);
        try(FileOutputStream out=new FileOutputStream(file)){for(int left=bytes;left>0;){int count=Math.min(block.length,left);out.write(block,0,count);left-=count;}out.getFD().sync();}return file;
    }
    private VaultEngine.Entry imported(String name,String parent,int bytes)throws Exception{
        File source=source(name,bytes);try(InputStream in=new FileInputStream(source)){return vault.importFile(in,name,"application/octet-stream",parent,QUIET);}
    }
    private void reopen()throws Exception{File root=vault.privateDirectory();vault.lock();vault=new VaultEngine(root);opened.add(vault);vault.unlock(PASSWORD);}
    private static byte[] hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return digest.digest();}
    private static void flip(File file,long at)throws Exception{try(RandomAccessFile io=new RandomAccessFile(file,"rw")){io.seek(at);int b=io.read();assertTrue(b>=0);io.seek(at);io.write(b^0x40);io.getFD().sync();}}
    private static void paused(VaultTransfers transfers,VaultEngine.Progress progress)throws Exception{
        try{transfers.execute(progress);fail("The operation should pause");}catch(VaultEngine.TransferPausedException expected){}
        assertTrue(transfers.pending());
    }
    private static void rejected(VaultTransfers transfers)throws Exception{
        try{transfers.execute(QUIET);fail("Changed or unauthenticated data must be rejected");}catch(Exception expected){assertFalse(expected instanceof VaultEngine.TransferPausedException);}
        assertTrue(transfers.pending());
    }
    private static VaultEngine.Progress pauseAt(long count){return bytes->{if(bytes>=count)throw new VaultEngine.TransferPausedException();};}
    private File partial(){File[] files=new File(vault.privateDirectory(),"transfers").listFiles((dir,name)->name.endsWith(".part"));assertNotNull(files);assertEquals(1,files.length);return files[0];}
    private void assertMatches(VaultEngine engine,String id,File file)throws Exception{try(InputStream in=new FileInputStream(file)){assertTrue(engine.matches(id,in,QUIET));}engine.verify(id);}

    @Test public void importPauseReopensEngineAndMovesOnlyAfterVerifiedResume()throws Exception{
        File original=source("private-name-for-journal.bin",10*MIB+17);byte[] digest=hash(original);
        VaultTransfers transfer=transfers();transfer.prepareImport(Collections.singletonList(document(original.getName())),"");
        byte[] journal=Files.readAllBytes(new File(vault.privateDirectory(),"operation.state").toPath());
        assertFalse(new String(journal,StandardCharsets.ISO_8859_1).contains(original.getName()));
        paused(transfer,pauseAt(3L*MIB));assertTrue(original.exists());assertEquals(0,vault.list().size());assertTrue(partial().length()>3L*MIB);
        reopen();assertTrue(transfers().pending());assertArrayEquals(digest,hash(original));
        transfers().execute(QUIET);assertFalse(transfers().pending());assertFalse(original.exists());assertEquals(1,vault.list().size());
        VaultEngine.Entry entry=vault.list().get(0);vault.verify(entry.id);assertArrayEquals(digest,entry.digest);assertEquals(10L*MIB+17,entry.size);
    }

    @Test public void changedSourcePrefixRejectsResumeWithoutTruncatingSavedCiphertext()throws Exception{
        File original=source("changing-source.bin",5*MIB+29);VaultTransfers transfer=transfers();transfer.prepareImport(Collections.singletonList(document(original.getName())),"");
        paused(transfer,pauseAt(2L*MIB));File saved=partial();byte[] savedDigest=hash(saved);flip(original,1234);byte[] changed=hash(original);
        reopen();rejected(transfers());assertEquals(0,vault.list().size());assertArrayEquals(changed,hash(original));assertArrayEquals(savedDigest,hash(saved));
        flip(original,1234);transfers().execute(QUIET);assertFalse(original.exists());assertEquals(1,vault.list().size());vault.verify(vault.list().get(0).id);
    }

    @Test public void tamperedCiphertextCheckpointPreservesSourceAndDamagedEvidence()throws Exception{
        File original=source("tampered-partial.bin",4*MIB+7);byte[] originalDigest=hash(original);VaultTransfers transfer=transfers();transfer.prepareImport(Collections.singletonList(document(original.getName())),"");
        paused(transfer,pauseAt(2L*MIB));File saved=partial();flip(saved,88);byte[] altered=hash(saved);
        reopen();rejected(transfers());assertEquals(0,vault.list().size());assertArrayEquals(originalDigest,hash(original));assertArrayEquals(altered,hash(saved));
        transfers().discard();assertFalse(transfers().pending());assertTrue(original.exists());
    }

    @Test public void exportPartialRejectsTamperingAndResumesWhenPrefixMatches()throws Exception{
        VaultEngine.Entry entry=imported("export-original.bin","",5*MIB+11);File original=new File(documents,entry.name);File destination=new File(documents,"partial-output.bin");assertTrue(destination.createNewFile());
        VaultTransfers transfer=transfers();transfer.prepareExport(Collections.singletonList(entry.id),document(destination.getName()),false,true);
        paused(transfer,pauseAt(2L*MIB));assertEquals(2L*MIB,destination.length());assertMatches(vault,entry.id,original);
        flip(destination,321);byte[] altered=hash(destination);reopen();rejected(transfers());assertArrayEquals(altered,hash(destination));assertMatches(vault,entry.id,original);
        flip(destination,321);transfers().execute(QUIET);assertEquals(0,vault.list().size());assertArrayEquals(hash(original),hash(destination));assertFalse(transfers().pending());
    }

    @Test public void nestedExportNormalizesSelectionAndPreservesSeparatelyTrashedFiles()throws Exception{
        VaultEngine.Entry folder=vault.folder("","Album"),nested=vault.folder(folder.id,"Inside");
        VaultEngine.Entry first=imported("one.bin",folder.id,1027),second=imported("two.bin",nested.id,2049),trashed=imported("old.bin",folder.id,23);
        vault.trash(Collections.singletonList(trashed.id));
        assertTrue(new File(documents,"Album").mkdir());File untouched=new File(documents,"Album/keep.txt");Files.write(untouched.toPath(),new byte[]{9,8,7});
        VaultTransfers transfer=transfers();transfer.prepareExport(Arrays.asList(folder.id,nested.id,first.id,second.id),tree(),true,true);transfer.execute(QUIET);
        assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(untouched.toPath()));
        assertArrayEquals(hash(new File(documents,first.name)),hash(new File(documents,"Album (2)/one.bin")));
        assertArrayEquals(hash(new File(documents,second.name)),hash(new File(documents,"Album (2)/Inside/two.bin")));
        assertFalse(new File(documents,"Album (2)/old.bin").exists());assertEquals(1,vault.list().size());assertTrue(vault.get(trashed.id).isTrashed());vault.verify(trashed.id);
    }

    @Test public void changedFolderAfterPauseNeverDeletesNewOrOriginalChildren()throws Exception{
        VaultEngine.Entry folder=vault.folder("","Changing"),file=imported("before.bin",folder.id,3*MIB+7);VaultTransfers transfer=transfers();transfer.prepareExport(Collections.singletonList(folder.id),tree(),true,true);
        paused(transfer,pauseAt(MIB));VaultEngine.Entry added=imported("after.bin",folder.id,57);reopen();rejected(transfers());
        assertMatches(vault,file.id,new File(documents,file.name));assertMatches(vault,added.id,new File(documents,added.name));assertNotNull(vault.get(folder.id));
    }

    @Test public void safBackupReusesVerifiedObjectsAndLatestRestoreExcludesRemovedFiles()throws Exception{
        VaultEngine.Entry kept=imported("keep.bin","",5*MIB+3),removed=imported("remove.bin","",31);
        File backup=new File(documents,"backup");assertTrue(backup.mkdir());Uri backupTree=DocumentsContract.buildTreeDocumentUri(AUTHORITY,documentId+"/backup");DocumentStore store=new DocumentStore(context,backupTree);
        VaultTransfers transfer=transfers();transfer.prepareBackup(backupTree);transfer.execute(QUIET);assertFalse(vault.needsBackup());int firstObjects=store.list().size();
        vault.delete(removed.id);assertTrue(vault.needsBackup());
        VaultBackupSet.Result update=VaultBackupSet.backup(vault,store,QUIET);assertTrue(update.reusedBytes>5L*MIB);assertTrue(update.copiedBytes<100000);assertTrue(store.list().size()>firstObjects);
        VaultEngine restored=engine("restored");VaultBackupSet.restoreLatest(restored,new DocumentStore(context,backupTree),PASSWORD,QUIET);
        assertEquals(1,restored.list().size());assertMatches(restored,kept.id,new File(documents,kept.name));
        assertTrue("Removed external original is deliberately outside the snapshot",new File(documents,removed.name).exists());
    }

    @Test public void interruptedSafBackupKeepsPreviousSnapshotAndResumesUsingNewAdapter()throws Exception{
        VaultEngine.Entry old=imported("old-backup.bin","",100);File backup=new File(documents,"resumable-backup");assertTrue(backup.mkdir());Uri backupTree=DocumentsContract.buildTreeDocumentUri(AUTHORITY,documentId+"/resumable-backup");
        VaultBackupSet.backup(vault,new DocumentStore(context,backupTree),QUIET);int initial=backup.list().length;
        VaultEngine.Entry added=imported("new-backup.bin","",6*MIB+5);VaultTransfers transfer=transfers();transfer.prepareBackup(backupTree);
        paused(transfer,bytes->{File[] objects=backup.listFiles((dir,name)->name.endsWith(".amzo")&&new File(dir,name).length()>=MIB);if(backup.list().length>initial&&objects!=null&&objects.length>0)throw new VaultEngine.TransferPausedException();});
        VaultEngine oldRestore=engine("old-restore");VaultBackupSet.restoreLatest(oldRestore,new DocumentStore(context,backupTree),PASSWORD,QUIET);assertEquals(1,oldRestore.list().size());oldRestore.verify(old.id);
        reopen();transfers().execute(QUIET);assertFalse(transfers().pending());assertFalse(vault.needsBackup());VaultEngine restored=engine("resumed-restore");VaultBackupSet.restoreLatest(restored,new DocumentStore(context,backupTree),PASSWORD,QUIET);
        assertEquals(2,restored.list().size());assertMatches(restored,added.id,new File(documents,added.name));assertMatches(restored,old.id,new File(documents,old.name));
    }

    @Test public void corruptedAndForeignOperationJournalsCannotMoveOrDeleteSources()throws Exception{
        File original=source("journal-secret.bin",8193);byte[] originalDigest=hash(original);VaultTransfers transfer=transfers();transfer.prepareImport(Collections.singletonList(document(original.getName())),"");
        File journal=new File(vault.privateDirectory(),"operation.state");byte[] valid=Files.readAllBytes(journal.toPath());flip(journal,valid.length-1);rejected(transfers());assertArrayEquals(originalDigest,hash(original));assertEquals(0,vault.list().size());
        VaultEngine foreign=engine("foreign");foreign.create("Outra senha Android segura".toCharArray());File foreignJournal=new File(foreign.privateDirectory(),"operation.state");Files.write(foreignJournal.toPath(),valid);
        rejected(new VaultTransfers(context,foreign));assertArrayEquals(valid,Files.readAllBytes(foreignJournal.toPath()));assertTrue(foreign.list().isEmpty());assertArrayEquals(originalDigest,hash(original));
        Files.write(journal.toPath(),valid);reopen();transfers().execute(QUIET);assertFalse(original.exists());assertEquals(1,vault.list().size());
    }

    @Test public void serviceCanPauseThenImmediatelyRunAnotherSessionWithoutStalling()throws Exception{
        launchActivity();VaultProfiles profiles=new VaultProfiles(new File(fixture,"service-profiles"));profiles.create(PASSWORD);opened.add(profiles.primary);opened.add(profiles.alternate);
        File original=source("service-consecutive.bin",MIB+13);new VaultTransfers(context,profiles.active()).prepareImport(Collections.singletonList(document(original.getName())),"");
        for(int attempt=0;attempt<3;attempt++){
            VaultTransferService.Session paused=startService(profiles,true);awaitSession(paused);assertTrue(paused.result.contains("pausada"));assertTrue(original.exists());assertFalse(profiles.active().isUnlocked());profiles.unlock(PASSWORD);
        }
        VaultTransferService.Session complete=startService(profiles,false);awaitSession(complete);assertFalse(original.exists());assertTrue(complete.result.contains("concluída"));profiles.unlock(PASSWORD);
        assertEquals(1,profiles.active().list().size());profiles.active().verify(profiles.active().list().get(0).id);assertFalse(new VaultTransfers(context,profiles.active()).pending());
    }

    @Test public void serviceKeepsTransferAliveAfterActivityDestroyedAndScreenOff()throws Exception{
        launchActivity();VaultProfiles profiles=new VaultProfiles(new File(fixture,"background-profiles"));profiles.create(PASSWORD);opened.add(profiles.primary);opened.add(profiles.alternate);
        File original=source("do-not-show-private-filename.bin",64*MIB+13);byte[] expected=hash(original);new VaultTransfers(context,profiles.active()).prepareImport(Collections.singletonList(document(original.getName())),"");
        VaultTransferService.Session session=startService(profiles,false);
        boolean notificationSeen=false;long notificationDeadline=SystemClock.elapsedRealtime()+10000;
        while(session.running&&SystemClock.elapsedRealtime()<notificationDeadline){
            for(StatusBarNotification item:context.getSystemService(NotificationManager.class).getActiveNotifications())if(item.getId()==81){
                Notification notification=item.getNotification();assertEquals(Notification.VISIBILITY_SECRET,notification.visibility);
                assertEquals("Cofre AMZ",notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
                assertEquals("Operação em andamento",notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
                assertFalse(notification.extras.toString().contains(original.getName()));notificationSeen=true;
            }
            if(notificationSeen)break;Thread.sleep(20);
        }
        assertTrue("Foreground notification was never registered",notificationSeen);assertTrue("Fixture must still be running before Activity destruction",session.running);
        instrumentation.runOnMainSync(()->activity.finish());instrumentation.waitForIdleSync();shell("input keyevent KEYCODE_SLEEP");
        try{awaitSession(session);}finally{wakeScreen();}
        assertTrue(activity.isDestroyed());assertFalse(profiles.active().isUnlocked());assertFalse(original.exists());assertTrue(session.result,session.result.contains("concluída"));
        profiles.unlock(PASSWORD);assertEquals(1,profiles.active().list().size());VaultEngine.Entry entry=profiles.active().list().get(0);assertArrayEquals(expected,entry.digest);profiles.active().verify(entry.id);
    }
    private void launchActivity(){wakeScreen();activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();}
    private VaultTransferService.Session startService(VaultProfiles profiles,boolean paused)throws Exception{
        AtomicReference<VaultTransferService.Session> started=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{VaultTransferService.Session session=VaultTransferService.start(activity,profiles);session.paused=paused;Field owner=MainActivity.class.getDeclaredField("transferSession");owner.setAccessible(true);owner.set(activity,session);started.set(session);}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null)throw new AssertionError(error.get());sessions.add(started.get());return started.get();
    }
    private void awaitSession(VaultTransferService.Session session)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+180000;while(session.running&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);assertFalse("Service did not finish: "+session.result,session.running);instrumentation.waitForIdleSync();
    }
    private void wakeScreen(){if(instrumentation!=null)shell("input keyevent KEYCODE_WAKEUP");}
    private void shell(String command){try(ParcelFileDescriptor fd=instrumentation.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] discard=new byte[256];while(in.read(discard)!=-1){}}catch(IOException e){throw new AssertionError(e);}}
}
