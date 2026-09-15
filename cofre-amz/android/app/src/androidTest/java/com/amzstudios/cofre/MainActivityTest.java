package com.amzstudios.cofre;

import androidx.test.platform.app.InstrumentationRegistry;
import static org.junit.Assert.*;
import org.junit.*;
import android.widget.*;
import android.app.*;
import android.content.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;

/** Runs against the real Activity, private storage, and a real Android DocumentsProvider. */
public class MainActivityTest {
    private MainActivity activity;
    @Before public void clearAlternateFixture(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();VaultEngine.removeTree(new File(context.getFilesDir(),"vault-alternate-v1"));if(android.os.Build.VERSION.SDK_INT>=33)getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);}
    private android.app.Instrumentation getInstrumentation(){return InstrumentationRegistry.getInstrumentation();}
    private MainActivity getActivity(){activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return activity;}
    @After public void finish()throws Exception{if(activity!=null){getInstrumentation().runOnMainSync(()->activity.finish());getInstrumentation().waitForIdleSync();((java.util.concurrent.ExecutorService)field(activity,"worker")).awaitTermination(60,java.util.concurrent.TimeUnit.SECONDS);}}
    private Object field(MainActivity a,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private void waitForIdleJob(MainActivity a)throws Exception{for(int i=0;i<400;i++){getInstrumentation().waitForIdleSync();if(!(Boolean)field(a,"busy"))return;Thread.sleep(100);}fail("Operation timed out");}
    private void dismiss(MainActivity a)throws Exception{getInstrumentation().runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("closeDialogs");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});}
    private void login(MainActivity a,String password)throws Exception{
        assertNotNull("Transfers must return to the locked screen",a.findViewById(R.id.vault_password));
        getInstrumentation().runOnMainSync(()->{((EditText)a.findViewById(R.id.vault_password)).setText(password);a.findViewById(R.id.vault_unlock).performClick();});
        waitForIdleJob(a);assertTrue((Boolean)field(a,"unlocked"));
    }
    private void capture(MainActivity a,String name){getInstrumentation().runOnMainSync(()->{View view=a.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}});}
    @Test public void testCreateBrowseImportMoveOutAndLock()throws Exception{
        Context context=getInstrumentation().getTargetContext();VaultEngine.removeTree(new File(context.getFilesDir(),"vault-v1"));
        MainActivity a=getActivity();getInstrumentation().waitForIdleSync();
        capture(a,"test-welcome.png");
        assertNotNull(a.findViewById(R.id.vault_password));
        assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
        getInstrumentation().runOnMainSync(()->{((EditText)a.findViewById(R.id.vault_password)).setText("Teste seguro Android 2026");((EditText)a.findViewById(R.id.vault_confirmation)).setText("Teste seguro Android 2026");a.findViewById(R.id.vault_unlock).performClick();});
        waitForIdleJob(a);assertNotNull(a.findViewById(R.id.vault_import));assertTrue((Boolean)field(a,"unlocked"));
        // Exercise the actual transfer handler with a file under the debug-only test provider.
        android.net.Uri source=android.provider.DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","source.txt");
        File sourceFile=new File(context.getFilesDir(),"fixture-source.txt");
        try(FileOutputStream out=new FileOutputStream(sourceFile)){out.write("arquivo para mover ao cofre".getBytes("UTF-8"));}
        getInstrumentation().runOnMainSync(()->a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(source)));
        waitForIdleJob(a);dismiss(a);login(a,"Teste seguro Android 2026");
        VaultEngine vault=(VaultEngine)field(a,"vault");assertEquals(1,vault.list().size());assertFalse("Original should be removed after verification",sourceFile.exists());VaultEngine.Entry entry=vault.list().get(0);vault.verify(entry.id);
        capture(a,"test-explorer.png");
        new File(context.getFilesDir(),"fixture-destination.txt").delete();
        getInstrumentation().runOnMainSync(()->{try{Field id=MainActivity.class.getDeclaredField("pendingId");id.setAccessible(true);id.set(a,entry.id);Field move=MainActivity.class.getDeclaredField("removeOnExport");move.setAccessible(true);move.set(a,true);a.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(android.provider.DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","destination.txt")));}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleJob(a);dismiss(a);assertFalse(vault.isUnlocked());login(a,"Teste seguro Android 2026");assertEquals(0,vault.list().size());File destination=new File(context.getFilesDir(),"fixture-destination.txt");assertTrue(destination.isFile());assertEquals("arquivo para mover ao cofre",new String(java.nio.file.Files.readAllBytes(destination.toPath()),"UTF-8"));
        getInstrumentation().runOnMainSync(()->a.onBackPressed());assertNotNull(a.findViewById(R.id.vault_password));((java.util.concurrent.ExecutorService)field(a,"worker")).submit(()->{}).get(20,java.util.concurrent.TimeUnit.SECONDS);assertFalse(vault.isUnlocked());
        destination.delete();
    }

    @Test public void interruptImportKeepsOriginalAndInterfaceResponsive()throws Exception{
        Context context=getInstrumentation().getTargetContext();VaultEngine.removeTree(new File(context.getFilesDir(),"vault-v1"));
        MainActivity a=getActivity();getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->{((EditText)a.findViewById(R.id.vault_password)).setText("Teste cancelamento 2026");((EditText)a.findViewById(R.id.vault_confirmation)).setText("Teste cancelamento 2026");a.findViewById(R.id.vault_unlock).performClick();});waitForIdleJob(a);
        File source=new File(context.getFilesDir(),"fixture-source.txt");try(RandomAccessFile f=new RandomAccessFile(source,"rw")){f.setLength(32L*1024*1024);}
        android.net.Uri uri=android.provider.DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","source.txt");
        getInstrumentation().runOnMainSync(()->a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(uri)));
        boolean[] paused={false};for(int i=0;i<200&&!paused[0];i++){getInstrumentation().runOnMainSync(()->{View pause=a.findViewById(R.id.vault_transfer_pause);if(pause!=null){pause.performClick();paused[0]=true;}});if(!paused[0])Thread.sleep(25);}
        assertTrue("Foreground transfer must expose its pause control",paused[0]);waitForIdleJob(a);
        assertTrue("Original must survive interruption",source.exists());VaultEngine vault=(VaultEngine)field(a,"vault");assertFalse(vault.isUnlocked());assertTrue("An interrupted transfer must retain an authenticated resume journal",new VaultTransfers(a,vault).pending());
        dismiss(a);login(a,"Teste cancelamento 2026");assertNotNull(a.findViewById(R.id.vault_import));for(VaultEngine.Entry entry:vault.list())if(!entry.folder)vault.verify(entry.id);dismiss(a);
        ((java.util.concurrent.ExecutorService)field(a,"worker")).submit(()->{try{new VaultTransfers(a,vault).discard();}catch(Exception e){throw new RuntimeException(e);}}).get(20,java.util.concurrent.TimeUnit.SECONDS);
        assertTrue("Discarding paused work must retain the source",source.exists());assertFalse(new VaultTransfers(a,vault).pending());source.delete();
    }

    @Test public void finishDuringQueuedOperationDoesNotDismissDetachedWindow()throws Exception{
        Context context=getInstrumentation().getTargetContext();VaultEngine.removeTree(new File(context.getFilesDir(),"vault-v1"));MainActivity a=getActivity();getInstrumentation().waitForIdleSync();
        java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(a,"worker");worker.execute(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{getInstrumentation().runOnMainSync(()->{((EditText)a.findViewById(R.id.vault_password)).setText("Teste fechamento seguro 2026");((EditText)a.findViewById(R.id.vault_confirmation)).setText("Teste fechamento seguro 2026");a.findViewById(R.id.vault_unlock).performClick();a.finish();});for(int i=0;i<100&&!a.isDestroyed();i++){getInstrumentation().waitForIdleSync();Thread.sleep(50);}assertTrue("Activity did not finish",a.isDestroyed());assertNull(field(a,"progress"));}finally{gate.countDown();}
        assertTrue(worker.awaitTermination(60,java.util.concurrent.TimeUnit.SECONDS));getInstrumentation().waitForIdleSync();assertFalse(((VaultEngine)field(a,"vault")).isUnlocked());assertNull(field(a,"progress"));
    }
}
