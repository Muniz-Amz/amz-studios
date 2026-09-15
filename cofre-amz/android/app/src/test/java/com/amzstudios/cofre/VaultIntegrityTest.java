package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Deterministic adversarial tests of the storage protocol, not a claim of breaking AES. */
public class VaultIntegrityTest {
    private File temp,root;private VaultEngine vault;
    private final char[] password="Senha de integridade 2026".toCharArray();
    @Before public void setup()throws Exception{temp=Files.createTempDirectory("amz-integrity-").toFile();root=new File(temp,"vault");vault=new VaultEngine(root);vault.create(password);}
    @After public void cleanup(){vault.lock();VaultEngine.removeTree(temp);}
    private byte[] data(int length){byte[] b=new byte[length];new Random(7421+length).nextBytes(b);return b;}
    private String id(){return UUID.randomUUID().toString();}
    private File state(String id){return new File(root,"transfers/"+id+".state");}
    private File part(String id){return new File(root,"transfers/"+id+".part");}
    private VaultEngine.Entry resume(String id,byte[] bytes,VaultEngine.Progress progress)throws Exception{return vault.importFileResumable(new ByteArrayInputStream(bytes),"private.bin","application/octet-stream","",id,progress);}
    private void restart()throws Exception{vault.lock();vault=new VaultEngine(root);vault.unlock(password);}
    private byte[] exported(String id)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();vault.exportFile(id,out,null);return out.toByteArray();}
    private void pause(String id,byte[] bytes,long after)throws Exception{assertThrows(VaultEngine.TransferPausedException.class,()->resume(id,bytes,n->{if(n>=after)throw new VaultEngine.TransferPausedException();}));assertTrue(state(id).isFile());assertTrue(vault.list().isEmpty());}
    private byte[] backup()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();vault.backup(out);return out.toByteArray();}

    @Test public void resumableBoundarySizesAndCommittedRetryAreExact()throws Exception{
        for(int size:new int[]{0,1,VaultEngine.CHUNK-1,VaultEngine.CHUNK,VaultEngine.CHUNK+1}){
            byte[] b=data(size);String id=id();VaultEngine.Entry entry=resume(id,b,null);assertEquals(id,entry.id);assertArrayEquals(b,exported(id));
            assertEquals(id,resume(id,b,null).id);assertEquals(1,vault.list().size());assertFalse(state(id).exists());vault.delete(id);
        }
    }
    @Test public void pauseCheckpointSurvivesRestartWithoutPlaintextMetadata()throws Exception{
        byte[] b=data(9*VaultEngine.CHUNK+37);String id=id();pause(id,b,3L*VaultEngine.CHUNK);
        byte[] checkpoint=Files.readAllBytes(state(id).toPath());assertFalse(new String(checkpoint,"ISO-8859-1").contains("private.bin"));
        restart();resume(id,b,null);assertArrayEquals(b,exported(id));assertFalse(part(id).exists());
    }
    @Test public void pauseAtShortFinalChunkAndAtReadbackBothResume()throws Exception{
        byte[] b=data(VaultEngine.CHUNK+19);String id=id();pause(id,b,b.length);restart();
        assertThrows(VaultEngine.TransferPausedException.class,()->resume(id,b,new VaultEngine.Progress(){boolean verify;public void phase(String p){if(p.startsWith("Verificando arquivo"))verify=true;}public void update(long n){if(verify)throw new VaultEngine.TransferPausedException();}}));
        assertTrue(state(id).exists());restart();resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void processInterruptionDropsOnlyUncheckpointedTailAfterValidation()throws Exception{
        byte[] b=data(10*VaultEngine.CHUNK+7);String id=id();
        assertThrows(AssertionError.class,()->resume(id,b,n->{if(n>=9L*VaultEngine.CHUNK)throw new AssertionError("simulated process death");}));
        try(FileOutputStream out=new FileOutputStream(part(id),true)){out.write(new byte[]{1,2,3});}
        restart();resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void sourceFailurePreservesLastFullChunkAndResumes()throws Exception{
        byte[] b=data(3*VaultEngine.CHUNK+13);String id=id();final int failAt=VaultEngine.CHUNK+100;
        InputStream broken=new ByteArrayInputStream(b){int position;public int read(byte[] bytes,int off,int len){if(position>=failAt)throw new UncheckedIOException(new IOException("provider disconnected"));int n=super.read(bytes,off,Math.min(len,failAt-position));if(n>0)position+=n;return n;}};
        assertThrows(UncheckedIOException.class,()->vault.importFileResumable(broken,"private.bin","application/octet-stream","",id,null));
        assertTrue(part(id).isFile());restart();resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void changedPrefixCannotTruncateOrReplaceSavedData()throws Exception{
        byte[] b=data(2*VaultEngine.CHUNK+1);String id=id();pause(id,b,VaultEngine.CHUNK);
        byte[] saved=Files.readAllBytes(part(id).toPath()),checkpoint=Files.readAllBytes(state(id).toPath());byte[] changed=b.clone();changed[32]^=1;
        assertThrows(IOException.class,()->resume(id,changed,null));assertArrayEquals(saved,Files.readAllBytes(part(id).toPath()));assertArrayEquals(checkpoint,Files.readAllBytes(state(id).toPath()));
        assertThrows(IOException.class,()->resume(id,Arrays.copyOf(b,100),null));resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void changedSizeAfterShortCheckpointIsRejected()throws Exception{
        byte[] b=data(99);String id=id();pause(id,b,99);assertThrows(IOException.class,()->resume(id,Arrays.copyOf(b,100),null));resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void everyCheckpointByteIsAuthenticatedAndCancellationCannotDeleteCommittedData()throws Exception{
        byte[] b=data(71);String id=id();pause(id,b,1);byte[] original=Files.readAllBytes(state(id).toPath()),partial=Files.readAllBytes(part(id).toPath());
        for(int i=0;i<original.length;i++){byte[] altered=original.clone();altered[i]^=1;Files.write(state(id).toPath(),altered);assertThrows(Exception.class,()->resume(id,b,null));assertArrayEquals(partial,Files.readAllBytes(part(id).toPath()));}
        Files.write(state(id).toPath(),original);resume(id,b,null);vault.discardPendingImport(id);assertArrayEquals(b,exported(id));
        byte[] changed=b.clone();changed[0]^=1;assertThrows(IOException.class,()->resume(id,changed,null));assertArrayEquals(b,exported(id));
    }
    @Test public void truncatedOrTamperedPartialNeverBecomesAnIndexedFile()throws Exception{
        byte[] b=data(2*VaultEngine.CHUNK+1);String id=id();pause(id,b,VaultEngine.CHUNK);byte[] original=Files.readAllBytes(part(id).toPath());
        for(int cut:new int[]{0,3,4,17,original.length-1}){Files.write(part(id).toPath(),Arrays.copyOf(original,cut));assertThrows(Exception.class,()->resume(id,b,null));assertTrue(vault.list().isEmpty());}
        byte[] corrupt=original.clone();corrupt[28]^=1;Files.write(part(id).toPath(),corrupt);assertThrows(Exception.class,()->resume(id,b,null));Files.write(part(id).toPath(),original);resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void simulatedOutOfSpaceKeepsExistingEntriesAndResumableCopy()throws Exception{
        class SpaceFile extends File{boolean full;SpaceFile(String path){super(path);}public long getUsableSpace(){return full?0:super.getUsableSpace();}}
        vault.lock();SpaceFile low=new SpaceFile(root.getAbsolutePath());vault=new VaultEngine(low);vault.unlock(password);byte[] b=data(3*VaultEngine.CHUNK+9);String id=id();
        assertThrows(IOException.class,()->resume(id,b,n->{if(n>=VaultEngine.CHUNK)low.full=true;}));assertTrue(state(id).isFile());assertTrue(vault.list().isEmpty());low.full=false;resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void failedIndexPromotionPreservesCompleteCiphertextForRetry()throws Exception{
        byte[] b=data(100),oldIndex=Files.readAllBytes(new File(root,"index.enc").toPath());String id=id();
        Files.delete(new File(root,"index.enc").toPath());assertTrue(new File(root,"index.enc").mkdir());Files.write(new File(root,"index.enc/block").toPath(),new byte[]{7});
        assertThrows(IOException.class,()->resume(id,b,null));assertTrue(new File(root,id+".bin").isFile());assertTrue(state(id).isFile());
        assertFalse(vault.isUnlocked());
        Files.delete(new File(root,"index.enc/block").toPath());Files.delete(new File(root,"index.enc").toPath());Files.write(new File(root,"index.enc").toPath(),oldIndex);restart();resume(id,b,null);assertArrayEquals(b,exported(id));
    }
    @Test public void unlockingNeverDeletesOrphansOrInterruptedStaging()throws Exception{
        File orphan=new File(root,id()+".bin"),oldPart=new File(root,id()+".part");Files.write(orphan.toPath(),data(57));Files.write(oldPart.toPath(),data(81));byte[] a=Files.readAllBytes(orphan.toPath());restart();assertArrayEquals(a,Files.readAllBytes(orphan.toPath()));assertTrue(oldPart.exists());
        for(File f:vault.currentBackupFiles())assertFalse(f.equals(orphan)||f.equals(oldPart));
    }
    @Test public void damagedExistingRootCannotBeCreatedOrRestoredOver()throws Exception{
        byte[] archive=backup(),index=Files.readAllBytes(new File(root,"index.enc").toPath());vault.lock();Files.delete(new File(root,"vault.key").toPath());
        assertThrows(IOException.class,()->vault.create(password));assertThrows(IOException.class,()->vault.restore(new ByteArrayInputStream(archive),password));assertArrayEquals(index,Files.readAllBytes(new File(root,"index.enc").toPath()));
    }
    @Test public void everySmallCiphertextByteAndEveryTruncationAreRejected()throws Exception{
        byte[] b=data(53);VaultEngine.Entry entry=resume(id(),b,null);File file=new File(root,entry.id+".bin");byte[] original=Files.readAllBytes(file.toPath());
        for(int i=0;i<original.length;i++){byte[] altered=original.clone();altered[i]^=1;Files.write(file.toPath(),altered);assertThrows(Exception.class,()->vault.verify(entry.id));Files.write(file.toPath(),Arrays.copyOf(original,i));assertThrows(Exception.class,()->vault.verify(entry.id));}
        Files.write(file.toPath(),Arrays.copyOf(original,original.length+1));assertThrows(Exception.class,()->vault.verify(entry.id));Files.write(file.toPath(),original);assertArrayEquals(b,exported(entry.id));
    }
    @Test public void blockReorderingAndCrossFileSplicingFailAuthentication()throws Exception{
        byte[] b=data(2*VaultEngine.CHUNK+9);String first=id();VaultEngine.Entry a=resume(first,b,null);VaultEngine.Entry second=vault.importFile(new ByteArrayInputStream(b),"second.bin","application/octet-stream","",null);
        File file=new File(root,a.id+".bin");byte[] saved=Files.readAllBytes(file.toPath()),other=Files.readAllBytes(new File(root,second.id+".bin").toPath());int block=VaultEngine.CHUNK+32;
        byte[] reordered=saved.clone();System.arraycopy(saved,4+block,reordered,4,block);System.arraycopy(saved,4,reordered,4+block,block);Files.write(file.toPath(),reordered);assertThrows(Exception.class,()->vault.verify(a.id));
        Files.write(file.toPath(),other);assertThrows(Exception.class,()->vault.verify(a.id));Files.write(file.toPath(),saved);vault.verify(a.id);
    }
    @Test public void corruptedLaterRandomReadBlockWipesAlreadyCopiedPrefix()throws Exception{
        byte[] b=data(VaultEngine.CHUNK+17);VaultEngine.Entry e=resume(id(),b,null);File file=new File(root,e.id+".bin");byte[] saved=Files.readAllBytes(file.toPath());saved[4+VaultEngine.CHUNK+32+20]^=1;Files.write(file.toPath(),saved);
        try(VaultEngine.RandomReader reader=vault.openRandomAccess(e.id,()->true)){byte[] result=new byte[40];Arrays.fill(result,(byte)99);assertThrows(IOException.class,()->reader.readAt(VaultEngine.CHUNK-20,result,0,37));for(int i=0;i<20;i++)assertEquals(0,result[i]);for(int i=20;i<40;i++)assertEquals(99,result[i]);}
    }
    @Test public void auxiliaryStateIsBoundToPurposeAndVault()throws Exception{
        byte[] original=vault.sealState("transfers-v1",data(211));assertThrows(Exception.class,()->vault.openState("playback-resume-v1",original));
        VaultEngine other=new VaultEngine(new File(temp,"other"));other.create(password);try{assertThrows(Exception.class,()->other.openState("transfers-v1",original));}finally{other.lock();}
        for(int i=0;i<original.length;i++){byte[] altered=original.clone();altered[i]^=2;assertThrows(Exception.class,()->vault.openState("transfers-v1",altered));}
    }
    @Test public void incompleteZipEndAndProducerFailureCannotCommitRestore()throws Exception{
        resume(id(),data(41),null);byte[] archive=backup();
        for(int cut=1;cut<=23;cut++){byte[] shortened=Arrays.copyOf(archive,archive.length-cut);VaultEngine target=new VaultEngine(new File(temp,"restore-"+cut));assertThrows(IOException.class,()->target.restore(new ByteArrayInputStream(shortened),password));assertFalse(target.exists());assertThrows(IOException.class,()->vault.verifyBackup(new ByteArrayInputStream(shortened)));}
        InputStream producer=new ByteArrayInputStream(archive){@Override public synchronized int read(byte[] b,int off,int len){if(pos==count)throw new UncheckedIOException(new IOException("producer authentication failure"));return super.read(b,off,len);}};
        VaultEngine target=new VaultEngine(new File(temp,"producer"));assertThrows(Exception.class,()->target.restore(producer,password));assertFalse(target.exists());
    }
    @Test public void extraUnreferencedBackupObjectIsRejectedAndExistingVaultPreserved()throws Exception{
        resume(id(),data(9),null);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(File f:vault.currentBackupFiles()){zip.putNextEntry(new ZipEntry(f.getName()));Files.copy(f.toPath(),zip);zip.closeEntry();}zip.putNextEntry(new ZipEntry(id()+".bin"));zip.write(0);zip.closeEntry();}
        VaultEngine target=new VaultEngine(new File(temp,"extra"));assertThrows(IOException.class,()->target.restore(new ByteArrayInputStream(bytes.toByteArray()),password));assertFalse(target.exists());vault.verify(vault.list().get(0).id);
    }
    @Test public void removingActiveParentPreservesIndependentlyTrashedDescendants()throws Exception{
        VaultEngine.Entry folder=vault.folder("","Folder"),child=vault.importFile(new ByteArrayInputStream(data(27)),"child.bin","application/octet-stream",folder.id,null);
        vault.trash(Collections.singleton(child.id));vault.delete(folder.id);assertTrue(vault.get(child.id).isTrashed());assertEquals("",vault.get(child.id).parent);restart();vault.restoreTrash(Collections.singleton(child.id));assertArrayEquals(data(27),exported(child.id));
    }
    @Test public void purgingParentTrashPreservesSeparatelyTrashedChild()throws Exception{
        VaultEngine.Entry parent=vault.folder("","Parent"),child=vault.importFile(new ByteArrayInputStream(data(17)),"child.bin","application/octet-stream",parent.id,null);
        vault.trash(Collections.singleton(child.id));vault.trash(Collections.singleton(parent.id));vault.purgeTrash(Collections.singleton(parent.id));assertTrue(vault.get(child.id).isTrashed());assertEquals("",vault.get(child.id).parent);vault.verify(child.id);
    }
    @Test public void identifiersAndMetadataRejectTraversalAndAmbiguousFormats()throws Exception{
        for(String bad:new String[]{"../index.enc","../../vault.key","------------------------------------",UUID.randomUUID().toString().toUpperCase(Locale.ROOT)})assertThrows(IOException.class,()->resume(bad,data(1),null));
        assertThrows(IOException.class,()->vault.importFileResumable(new ByteArrayInputStream(data(1)),"a.bin","x\nunsafe","",id(),null));assertTrue(vault.list().isEmpty());
    }
    @Test public void revokingSessionStopsSequentialExportBeforeAnotherPlaintextBlock()throws Exception{
        byte[] bytes=data(2*VaultEngine.CHUNK+71);VaultEngine.Entry item=resume(id(),bytes,null);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        assertThrows(IllegalStateException.class,()->vault.exportFile(item.id,output,n->vault.lock()));
        assertEquals(VaultEngine.CHUNK,output.size());assertFalse(vault.isUnlocked());
        vault.unlock(password);assertArrayEquals(bytes,exported(item.id));
    }
    @Test public void revokedAndReauthenticatedSessionCannotAuthorizeSourceRemoval()throws Exception{
        byte[] bytes=data(137);VaultEngine.Entry item=resume(id(),bytes,null);String recovery=vault.createRecoveryKey();
        assertThrows(IllegalStateException.class,()->vault.matches(item.id,new ByteArrayInputStream(bytes),n->{try{vault.lock();vault.unlockWithRecovery(recovery);}catch(Exception e){throw new RuntimeException(e);}}));
        assertTrue(vault.isUnlocked());assertArrayEquals(bytes,exported(item.id));
    }
    @Test public void revokingSessionDuringImportPreservesCheckpointAndSourceForResume()throws Exception{
        byte[] bytes=data(2*VaultEngine.CHUNK+37);String transfer=id();
        assertThrows(IllegalStateException.class,()->resume(transfer,bytes,n->vault.lock()));
        assertTrue(state(transfer).isFile());assertTrue(part(transfer).isFile());
        vault.unlock(password);assertTrue(vault.list().isEmpty());resume(transfer,bytes,null);assertArrayEquals(bytes,exported(transfer));
    }
    @Test public void everyRecoveryEnvelopeByteAndTruncationRejectWithoutRewritingPassword()throws Exception{
        String recovery=vault.createRecoveryKey();File envelope=new File(root,VaultEngine.RECOVERY);
        byte[] saved=Files.readAllBytes(envelope.toPath()),config=Files.readAllBytes(new File(root,VaultEngine.CONFIG).toPath());
        for(int i=0;i<saved.length;i++){
            byte[] bad=saved.clone();bad[i]^=1;Files.write(envelope.toPath(),bad);
            assertThrows(Exception.class,()->vault.recover(recovery,password));assertFalse(vault.isUnlocked());
            Files.write(envelope.toPath(),Arrays.copyOf(saved,i));assertThrows(Exception.class,()->vault.recover(recovery,password));
            assertArrayEquals(config,Files.readAllBytes(new File(root,VaultEngine.CONFIG).toPath()));
        }
        Files.write(envelope.toPath(),saved);vault.unlockWithRecovery(recovery);
    }
    @Test public void everyIndexByteAndTruncationRejectAndLeaveCiphertextUntouched()throws Exception{
        VaultEngine.Entry item=resume(id(),data(103),null);String recovery=vault.createRecoveryKey();File index=new File(root,VaultEngine.INDEX);
        byte[] saved=Files.readAllBytes(index.toPath()),cipher=Files.readAllBytes(new File(root,item.id+".bin").toPath());
        for(int i=0;i<saved.length;i++){
            byte[] bad=saved.clone();bad[i]^=1;Files.write(index.toPath(),bad);assertThrows(Exception.class,()->vault.unlockWithRecovery(recovery));assertFalse(vault.isUnlocked());
            Files.write(index.toPath(),Arrays.copyOf(saved,i));assertThrows(Exception.class,()->vault.unlockWithRecovery(recovery));
        }
        assertArrayEquals(cipher,Files.readAllBytes(new File(root,item.id+".bin").toPath()));Files.write(index.toPath(),saved);vault.unlockWithRecovery(recovery);vault.verify(item.id);
    }
    @Test public void passwordEnvelopeFieldTamperingCannotOpenOrChangeVault()throws Exception{
        VaultEngine.Entry item=resume(id(),data(89),null);File config=new File(root,VaultEngine.CONFIG);byte[] saved=Files.readAllBytes(config.toPath());
        for(int at:new int[]{0,4,8,23,24,35,36,51,68,83}){
            byte[] bad=saved.clone();bad[at]^=1;Files.write(config.toPath(),bad);assertThrows(Exception.class,()->vault.unlock(password));assertFalse(vault.isUnlocked());assertArrayEquals(bad,Files.readAllBytes(config.toPath()));
        }
        for(int size:new int[]{0,4,8,24,36,83,85}){Files.write(config.toPath(),Arrays.copyOf(saved,size));assertThrows(Exception.class,()->vault.unlock(password));}
        Files.write(config.toPath(),saved);vault.unlock(password);assertArrayEquals(data(89),exported(item.id));
    }
    @Test public void seededMultiBlockFaultCampaignRejectsEveryMutationAndRecoversOriginal()throws Exception{
        VaultEngine.Entry item=resume(id(),data(2*VaultEngine.CHUNK+117),null);File encrypted=new File(root,item.id+".bin");
        byte[] saved=Files.readAllBytes(encrypted.toPath());Random random=new Random(20260915);
        try(RandomAccessFile file=new RandomAccessFile(encrypted,"rw")){
            for(int trial=0;trial<128;trial++){
                int position=random.nextInt(saved.length),mask=1<<random.nextInt(8);file.seek(position);file.write(saved[position]^mask);
                assertThrows(Exception.class,()->vault.verify(item.id));file.seek(position);file.write(saved[position]);
            }
        }
        assertArrayEquals(saved,Files.readAllBytes(encrypted.toPath()));vault.verify(item.id);
    }
}
