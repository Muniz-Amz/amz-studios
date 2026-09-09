package com.amzstudios.cofre;

import androidx.test.platform.app.InstrumentationRegistry;
import static org.junit.Assert.*;
import org.junit.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Real Android media decoding, Activity controls and document-tree transfers with synthetic data. */
public class VaultFeaturesActivityTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final String password="Senha teste recursos 2026";
    private MainActivity activity;
    private VaultEngine seed;
    private File vaultDir;
    @Before public void setup()throws Exception{
        vaultDir=new File(context.getFilesDir(),"vault-v1");VaultEngine.removeTree(vaultDir);
        VaultEngine.removeTree(new File(context.getFilesDir(),"fixture-export"));
        seed=new VaultEngine(vaultDir);seed.create(password.toCharArray());
    }
    @After public void cleanup()throws Exception{
        seed.lock();if(activity!=null){ExecutorService worker=(ExecutorService)field(activity,"worker");main(()->activity.finish());inst.waitForIdleSync();worker.awaitTermination(20,TimeUnit.SECONDS);}
    }
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private void main(Runnable action){inst.runOnMainSync(action);inst.waitForIdleSync();}
    private void invoke(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);main(()->{try{m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});}
    private void waitJob()throws Exception{for(int i=0;i<400;i++){inst.waitForIdleSync();if(!(Boolean)field(activity,"busy")){((ExecutorService)field(activity,"worker")).submit(()->{}).get(20,TimeUnit.SECONDS);inst.waitForIdleSync();return;}Thread.sleep(100);}fail("Activity job timed out");}
    private void open()throws Exception{
        seed.lock();activity=(MainActivity)inst.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));inst.waitForIdleSync();
        main(()->{((EditText)activity.findViewById(R.id.vault_password)).setText(password);activity.findViewById(R.id.vault_unlock).performClick();});waitJob();assertTrue((Boolean)field(activity,"unlocked"));
    }
    private VaultEngine vault()throws Exception{return (VaultEngine)field(activity,"vault");}
    @SuppressWarnings("unchecked") private AlertDialog dialog()throws Exception{List<Dialog> dialogs=(List<Dialog>)field(activity,"dialogs");for(int i=dialogs.size()-1;i>=0;i--)if(dialogs.get(i).isShowing())return (AlertDialog)dialogs.get(i);throw new AssertionError("No active dialog");}
    private void positive()throws Exception{AlertDialog d=dialog();main(()->d.getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitJob();}
    private void dismiss()throws Exception{invoke("closeDialogs");}
    private View byText(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=byText(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private void click(String label){main(()->{View v=byText(activity.getWindow().getDecorView(),label);assertNotNull("Missing control: "+label,v);v.performClick();});}
    private void clickId(int id){main(()->{View v=activity.findViewById(id);assertNotNull(v);v.performClick();});}
    private void selectItem(String id)throws Exception{
        main(()->{GridView grid=activity.findViewById(R.id.vault_items);for(int i=0;i<grid.getAdapter().getCount();i++){Object value=grid.getAdapter().getItem(i);if(value instanceof VaultEngine.Entry&&((VaultEngine.Entry)value).id.equals(id)){grid.performItemClick(grid.getChildAt(i-grid.getFirstVisiblePosition()),i,i);return;}}fail("Entry not visible");});
    }
    private VaultEngine.Entry add(String parent,String name,String content)throws Exception{return seed.importFile(new ByteArrayInputStream(content.getBytes("UTF-8")),name,"text/plain",parent,null);}
    private int loadedImages(View v){int n=v instanceof ImageView&&((ImageView)v).getDrawable() instanceof BitmapDrawable?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=loadedImages(((ViewGroup)v).getChildAt(i));return n;}
    private void capture(String name){main(()->{View v=activity.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new RuntimeException(e);}finally{b.recycle();}});}

    @Test public void gridSelectionTrashRestorePurgeAndMedia()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(80,180,120));ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,png);bitmap.recycle();
        VaultEngine.Entry photo=seed.importFile(new ByteArrayInputStream(png.toByteArray()),"Foto de teste.png","image/png","",null);
        VaultEngine.Entry video;try(InputStream in=inst.getContext().getAssets().open("blue-fixture.mp4")){video=seed.importFile(in,"Video de teste.mp4","video/mp4","",null);}
        VaultEngine.Entry folder=seed.folder("","Pessoal");open();
        if(!(Boolean)field(activity,"gridMode"))clickId(R.id.vault_view_toggle);
        for(int i=0;i<100&&loadedImages(activity.findViewById(R.id.vault_items))<2;i++){waitJob();Thread.sleep(100);}
        assertEquals(2,loadedImages(activity.findViewById(R.id.vault_items)));assertTrue(((GridView)activity.findViewById(R.id.vault_items)).getNumColumns()>1);capture("test-v11-grid.png");
        assertEquals(0,new File(context.getCacheDir(),"thumb-work").list().length);
        clickId(R.id.vault_select);selectItem(photo.id);selectItem(video.id);assertEquals(2,((Set<?>)field(activity,"selected")).size());capture("test-v11-selection.png");
        click("Mover");AlertDialog folders=dialog();main(()->folders.getListView().performItemClick(null,1,1));waitJob();
        assertEquals(folder.id,vault().get(photo.id).parent);assertEquals(folder.id,vault().get(video.id).parent);
        selectItem(folder.id);clickId(R.id.vault_select);click("Todos");click("Lixeira");positive();
        assertTrue(vault().get(photo.id).isTrashed());assertTrue(vault().get(video.id).isTrashed());assertTrue(new File(vaultDir,photo.id+".bin").isFile());
        clickId(R.id.vault_trash_tab);capture("test-v11-trash.png");clickId(R.id.vault_select);click("Todos");click("Restaurar");waitJob();dismiss();
        assertFalse(vault().get(photo.id).isTrashed());assertEquals(folder.id,vault().get(photo.id).parent);
        clickId(R.id.vault_files_tab);selectItem(folder.id);clickId(R.id.vault_select);click("Todos");click("Lixeira");positive();clickId(R.id.vault_trash_tab);clickId(R.id.vault_select);click("Todos");click("Excluir de vez");positive();
        assertEquals(1,vault().list().size());assertFalse(new File(vaultDir,photo.id+".bin").exists());assertFalse(new File(vaultDir,video.id+".bin").exists());
        invoke("requestLock");waitJob();assertFalse(vault().isUnlocked());assertEquals(0,((android.util.LruCache<?,?>)field(field(activity,"thumbnails"),"cache")).size());
    }

    @Test public void recoveryBackupAndVerifiedMultiExport()throws Exception{
        VaultEngine.Entry folder=seed.folder("","Documentos"),one=add(folder.id,"um.txt","conteudo um"),two=add("","dois.txt","conteudo dois");open();
        assertTrue(vault().storedBytes()>0);assertTrue(vault().availableBytes()>0);assertNotNull(activity.findViewById(R.id.vault_backup_reminder));
        invoke("createRecovery");positive();String code=((TextView)dialog().findViewById(R.id.vault_recovery_code)).getText().toString();assertTrue(code.startsWith("AMZ1-"));positive();assertTrue(vault().hasRecovery());
        Uri document=DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","destination.txt");main(()->activity.onActivityResult(12,Activity.RESULT_OK,new Intent().setData(document)));waitJob();dismiss();
        assertFalse(vault().needsBackup());assertNull(activity.findViewById(R.id.vault_backup_reminder));File backup=new File(context.getFilesDir(),"fixture-destination.txt");try(InputStream in=new FileInputStream(backup)){vault().verifyBackup(in);}
        invoke("requestLock");waitJob();clickId(R.id.vault_recover);AlertDialog recovery=dialog();main(()->{((EditText)recovery.findViewById(R.id.vault_recovery_input)).setText(code);((EditText)recovery.findViewById(R.id.vault_new_password)).setText("Nova senha offline 2026");((EditText)recovery.findViewById(R.id.vault_new_confirmation)).setText("Nova senha offline 2026");});positive();dismiss();assertTrue(vault().isUnlocked());vault().verify(one.id);assertTrue(vault().needsBackup());
        File destination=new File(context.getFilesDir(),"fixture-export");destination.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(destination,"dois.txt"))){out.write("preexistente".getBytes("UTF-8"));}
        clickId(R.id.vault_select);click("Todos");click("Retirar");dismiss();
        Uri tree=DocumentsContract.buildTreeDocumentUri("com.amzstudios.cofre.test.documents","export");main(()->activity.onActivityResult(14,Activity.RESULT_OK,new Intent().setData(tree)));waitJob();dismiss();
        assertTrue(vault().list().isEmpty());assertEquals("conteudo um",new String(Files.readAllBytes(new File(destination,"Documentos/um.txt").toPath()),"UTF-8"));assertEquals("conteudo dois",new String(Files.readAllBytes(new File(destination,"dois (2).txt").toPath()),"UTF-8"));assertEquals("preexistente",new String(Files.readAllBytes(new File(destination,"dois.txt").toPath()),"UTF-8"));
        assertTrue(vault().needsBackup());assertNotNull(activity.findViewById(R.id.vault_backup_reminder));
        // A rejected tree must preserve every original in the cofre.
        VaultEngine.Entry retained=vault().importFile(new ByteArrayInputStream(new byte[]{1,2,3}),"preservado.bin","application/octet-stream","",null);invoke("showExplorer");set("pendingExports",new ArrayList<>(Collections.singletonList(retained.id)));
        Uri invalid=DocumentsContract.buildTreeDocumentUri("com.amzstudios.cofre.test.documents","invalid");main(()->activity.onActivityResult(14,Activity.RESULT_OK,new Intent().setData(invalid)));waitJob();dismiss();vault().verify(retained.id);assertEquals(1,vault().list().size());
        invoke("requestLock");waitJob();main(()->{((EditText)activity.findViewById(R.id.vault_password)).setText("Nova senha offline 2026");activity.findViewById(R.id.vault_unlock).performClick();});waitJob();assertTrue(vault().isUnlocked());
    }
}
