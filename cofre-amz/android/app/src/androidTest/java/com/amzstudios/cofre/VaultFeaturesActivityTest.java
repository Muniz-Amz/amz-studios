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
    private void click(String label){main(()->{View v=null;try{View actions=(View)field(activity,"selectionActions");if(actions!=null&&actions.isShown())v=byText(actions,label);}catch(Exception ignored){}if(v==null)v=byText(activity.getWindow().getDecorView(),label);assertNotNull("Missing control: "+label,v);v.performClick();});}
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
        // Playback also reads encrypted ranges; the UI must remain responsive and cache stays empty.
        long videoStart=android.os.SystemClock.elapsedRealtime();selectItem(video.id);
        Dialog media=(Dialog)field(activity,"preview");assertNotNull(media);main(()->activity.onUserInteraction());assertFalse(((android.os.Handler)field(activity,"ui")).hasCallbacks((Runnable)field(activity,"timeout")));
        String[] state={""};for(int i=0;i<100;i++){main(()->state[0]=((TextView)media.findViewById(R.id.vault_media_status)).getText().toString());if(!state[0].startsWith("Preparando"))break;Thread.sleep(100);}
        assertTrue(state[0],state[0].startsWith("Reproduzindo")||state[0].startsWith("Pausado")||state[0].equals("Concluído"));
        android.util.Log.i("CofrePerformance","Small MP4 prepared after direct tap in "+(android.os.SystemClock.elapsedRealtime()-videoStart)+" ms");
        long heartbeat=android.os.SystemClock.elapsedRealtime();for(int i=0;i<20;i++)main(()->{});assertTrue("UI blocked during playback",android.os.SystemClock.elapsedRealtime()-heartbeat<3000);
        File[] plaintext=new File(context.getCacheDir(),"preview").listFiles();assertTrue(plaintext==null||plaintext.length==0);invoke("closePreview");
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
        // A new backup after withdrawing the whole tree must not bring those files back.
        invoke("saveBackup");assertTrue(((TextView)dialog().findViewById(android.R.id.message)).getText().toString().contains("Nenhum arquivo para incluir"));dismiss();
        main(()->activity.onActivityResult(12,Activity.RESULT_OK,new Intent().setData(document)));waitJob();dismiss();
        File emptyRoot=new File(context.getCacheDir(),"test-empty-backup-"+UUID.randomUUID());VaultEngine empty=new VaultEngine(emptyRoot);
        try(InputStream in=new FileInputStream(backup)){empty.restore(in,"Nova senha offline 2026".toCharArray());assertTrue(empty.list().isEmpty());assertTrue(empty.hasRecovery());}finally{empty.lock();VaultEngine.removeTree(emptyRoot);}
        assertEquals("conteudo um",new String(Files.readAllBytes(new File(destination,"Documentos/um.txt").toPath()),"UTF-8"));
        // A rejected tree must preserve every original in the cofre.
        VaultEngine.Entry retained=vault().importFile(new ByteArrayInputStream(new byte[]{1,2,3}),"preservado.bin","application/octet-stream","",null);invoke("showExplorer");set("pendingExports",new ArrayList<>(Collections.singletonList(retained.id)));
        Uri invalid=DocumentsContract.buildTreeDocumentUri("com.amzstudios.cofre.test.documents","invalid");main(()->activity.onActivityResult(14,Activity.RESULT_OK,new Intent().setData(invalid)));waitJob();dismiss();vault().verify(retained.id);assertEquals(1,vault().list().size());
        invoke("requestLock");waitJob();main(()->{((EditText)activity.findViewById(R.id.vault_password)).setText("Nova senha offline 2026");activity.findViewById(R.id.vault_unlock).performClick();});waitJob();assertTrue(vault().isUnlocked());
    }

    @Test public void backupAfterIndividualMoveKeepsOnlyCurrentVaultFiles()throws Exception{
        VaultEngine.Entry moved=add("","Retirada.txt","foto retirada"),copied=add("","Copiada.txt","foto copiada"),trash=add("","Lixeira.txt","foto na lixeira");seed.trash(Collections.singleton(trash.id));open();
        File outside=new File(context.getFilesDir(),"fixture-export");assertTrue(outside.mkdirs());
        Uri movedUri=DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","export/retirada.txt"),copiedUri=DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","export/copiada.txt");
        set("pendingId",copied.id);set("removeOnExport",false);main(()->activity.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(copiedUri)));waitJob();
        assertTrue(((TextView)dialog().findViewById(android.R.id.message)).getText().toString().contains("continua no cofre"));dismiss();vault().verify(copied.id);
        set("pendingId",moved.id);set("removeOnExport",true);main(()->activity.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(movedUri)));waitJob();
        assertTrue(((TextView)dialog().findViewById(android.R.id.message)).getText().toString().contains("Não entrará nos novos backups"));dismiss();assertThrows(IOException.class,()->vault().get(moved.id));
        invoke("saveBackup");AlertDialog confirmation=dialog();String message=((TextView)confirmation.findViewById(android.R.id.message)).getText().toString();assertTrue(message,message.contains("Meus arquivos: 1 arquivo"));assertTrue(message,message.contains("Lixeira: 1 arquivo"));
        main(()->{View decor=confirmation.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"test-v114-backup.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new RuntimeException(e);}finally{bitmap.recycle();}});dismiss();
        Uri backupUri=DocumentsContract.buildDocumentUri("com.amzstudios.cofre.test.documents","destination.txt");main(()->activity.onActivityResult(12,Activity.RESULT_OK,new Intent().setData(backupUri)));waitJob();
        String saved=((TextView)dialog().findViewById(android.R.id.message)).getText().toString();assertTrue(saved,saved.contains("Meus arquivos: 1 arquivo"));dismiss();assertFalse(vault().needsBackup());
        File restoredRoot=new File(context.getCacheDir(),"test-current-backup-"+UUID.randomUUID());VaultEngine restored=new VaultEngine(restoredRoot);
        try(InputStream in=new FileInputStream(new File(context.getFilesDir(),"fixture-destination.txt"))){restored.restore(in,password.toCharArray());assertEquals(2,restored.list().size());assertThrows(IOException.class,()->restored.get(moved.id));assertFalse(restored.get(copied.id).isTrashed());assertTrue(restored.get(trash.id).isTrashed());restored.verify(copied.id);restored.verify(trash.id);}finally{restored.lock();VaultEngine.removeTree(restoredRoot);}
        assertEquals("foto retirada",new String(Files.readAllBytes(new File(outside,"retirada.txt").toPath()),"UTF-8"));assertEquals("foto copiada",new String(Files.readAllBytes(new File(outside,"copiada.txt").toPath()),"UTF-8"));
    }

    @Test public void lightweightLayoutRecyclesCellsAndKeepsSelectionScroll()throws Exception{
        seed.folder("","Documentos");seed.folder("","Pessoal");
        Bitmap art=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(art);Paint paint=new Paint();
        paint.setShader(new LinearGradient(0,0,0,400,0xff79b4bb,0xffefc493,Shader.TileMode.CLAMP));canvas.drawRect(0,0,640,400,paint);paint.setShader(null);paint.setColor(0xffffe5b7);canvas.drawCircle(470,115,44,paint);paint.setColor(0xff3f6b76);Path mountain=new Path();mountain.moveTo(0,340);mountain.lineTo(180,140);mountain.lineTo(370,360);mountain.lineTo(530,230);mountain.lineTo(640,330);mountain.lineTo(640,400);mountain.lineTo(0,400);mountain.close();canvas.drawPath(mountain,paint);
        ByteArrayOutputStream jpg=new ByteArrayOutputStream();art.compress(Bitmap.CompressFormat.JPEG,90,jpg);art.recycle();
        VaultEngine.Entry photo=seed.importFile(new ByteArrayInputStream(jpg.toByteArray()),"Montanhas ao amanhecer.jpg","image/jpeg","",null);
        seed.importFile(new ByteArrayInputStream(jpg.toByteArray()),"Minha próxima viagem.jpg","image/jpeg","",null);
        open();if(!(Boolean)field(activity,"gridMode"))clickId(R.id.vault_view_toggle);main(()->((GridView)activity.findViewById(R.id.vault_items)).setSelection(2));
        for(int i=0;i<80&&loadedImages(activity.findViewById(R.id.vault_items))<2;i++){Thread.sleep(100);inst.waitForIdleSync();}capture("test-v112-grid.png");
        // Rebinding the same cell must not show the previous file's decrypted thumbnail.
        main(()->{GridView grid=activity.findViewById(R.id.vault_items);android.widget.Adapter adapter=grid.getAdapter();int photoIndex=-1,folderIndex=-1;for(int i=0;i<adapter.getCount();i++){VaultEngine.Entry e=(VaultEngine.Entry)adapter.getItem(i);if(e.id.equals(photo.id))photoIndex=i;if(e.folder)folderIndex=i;}View first=adapter.getView(photoIndex,null,grid);assertEquals(1,loadedImages(first));View reused=adapter.getView(folderIndex,first,grid);assertSame(first,reused);assertEquals(0,loadedImages(reused));});
        selectItem(photo.id);Dialog preview=(Dialog)field(activity,"preview");assertNotNull(preview);
        for(int i=0;i<80&&loadedImages(preview.getWindow().getDecorView())<1;i++){Thread.sleep(100);inst.waitForIdleSync();}assertEquals(1,loadedImages(preview.getWindow().getDecorView()));File[] plaintext=new File(context.getCacheDir(),"preview").listFiles();assertTrue(plaintext==null||plaintext.length==0);invoke("closePreview");
        clickId(R.id.vault_view_toggle);capture("test-v112-list.png");
        // Metadata-only UI fixture: this measures navigation, not a 100 GB storage test.
        List<VaultEngine.Entry> catalog=new ArrayList<>();for(int i=0;i<5000;i++)catalog.add(new VaultEngine.Entry("ui-"+i,"",String.format(Locale.ROOT,"Arquivo %05d.txt",i),"text/plain",false,3000,0,new byte[32]));set("all",catalog);invoke("refresh");waitJob();GridView original=activity.findViewById(R.id.vault_items);assertEquals(5000,original.getAdapter().getCount());
        main(()->original.setSelection(120));int position=original.getFirstVisiblePosition();assertTrue(position>0);clickId(R.id.vault_select);selectItem("ui-120");assertSame(original,activity.findViewById(R.id.vault_items));assertEquals(position,original.getFirstVisiblePosition());
        main(()->((EditText)activity.findViewById(R.id.vault_search)).setText("Arquivo 004"));Thread.sleep(250);waitJob();assertEquals(100,original.getAdapter().getCount());selectItem("ui-400");assertEquals("Arquivo 004",((EditText)activity.findViewById(R.id.vault_search)).getText().toString());assertSame(original,activity.findViewById(R.id.vault_items));
        long start=android.os.SystemClock.elapsedRealtime();for(int i=0;i<20;i++){final int target=i*4;main(()->original.setSelection(target));}android.util.Log.i("CofrePerformance","5000-item metadata fixture, 20 navigation/idle rounds: "+(android.os.SystemClock.elapsedRealtime()-start)+" ms");assertTrue("UI navigation stalled",android.os.SystemClock.elapsedRealtime()-start<5000);
    }

    @Test public void videoSurfacePreservesLandscapeAndPortraitAspect()throws Exception{
        main(()->{VaultMediaView.AspectSurface view=new VaultMediaView.AspectSurface(context);view.videoSize(1920,1080);view.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));assertEquals(1000,view.getMeasuredWidth());assertEquals(562,view.getMeasuredHeight());view.videoSize(1080,1920);view.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));assertEquals(562,view.getMeasuredWidth());assertEquals(1000,view.getMeasuredHeight());});
    }

    @Test public void pdfPagesDecodeAndCloseWithoutBlockingUi()throws Exception{
        android.graphics.pdf.PdfDocument document=new android.graphics.pdf.PdfDocument();for(int i=0;i<2;i++){android.graphics.pdf.PdfDocument.Page p=document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(600,800,i+1).create());Paint paint=new Paint();paint.setColor(Color.BLACK);paint.setTextSize(36);p.getCanvas().drawText("Pagina privada "+(i+1),40,80,paint);document.finishPage(p);}ByteArrayOutputStream data=new ByteArrayOutputStream();document.writeTo(data);document.close();VaultEngine.Entry pdf=seed.importFile(new ByteArrayInputStream(data.toByteArray()),"Documento.pdf","application/pdf","",null);open();selectItem(pdf.id);waitJob();Dialog preview=(Dialog)field(activity,"preview");assertNotNull(preview);for(int i=0;i<100&&byText(preview.getWindow().getDecorView(),"1 / 2")==null;i++){Thread.sleep(100);inst.waitForIdleSync();}assertNotNull(byText(preview.getWindow().getDecorView(),"1 / 2"));assertEquals(1,loadedImages(preview.getWindow().getDecorView()));main(()->byText(preview.getWindow().getDecorView(),"Próxima").performClick());for(int i=0;i<100&&byText(preview.getWindow().getDecorView(),"2 / 2")==null;i++){Thread.sleep(100);inst.waitForIdleSync();}assertNotNull(byText(preview.getWindow().getDecorView(),"2 / 2"));invoke("closePreview");assertEquals(0,new File(context.getCacheDir(),"preview").list().length);vault().verify(pdf.id);
    }

    private VaultEngine.Entry video(String name,String parent)throws Exception{try(InputStream in=inst.getContext().getAssets().open("blue-fixture.mp4")){return seed.importFile(in,name,"video/mp4",parent,null);}}
    private void awaitMedia(Dialog dialog)throws Exception{
        String[] state={""};for(int i=0;i<150;i++){main(()->{TextView label=dialog.findViewById(R.id.vault_media_status);state[0]=label==null?"":label.getText().toString();});if(state[0].startsWith("Reproduzindo")||state[0].startsWith("Pausado")||state[0].equals("Concluído"))return;Thread.sleep(100);}fail("Playback unavailable: "+state[0]);
    }
    @Test public void videoNavigationUsesVisibleOrderAndReleasesPreviousPlayer()throws Exception{
        VaultEngine.Entry first=video("A primeiro.mp4",""),middle=video("C segundo.mp4",""),last=video("E terceiro.mp4","");VaultEngine.Entry folder=seed.folder("","Outra pasta");video("B oculto.mp4",folder.id);VaultEngine.Entry deleted=video("D excluido.mp4","");seed.trash(Collections.singletonList(deleted.id));add("","B documento.txt","Não é um vídeo.");open();selectItem(middle.id);Dialog dialog=(Dialog)field(activity,"preview");Object pager=field(activity,"mediaPager");awaitMedia(dialog);Object old=field(pager,"playing");
        assertEquals("2 de 3",((TextView)dialog.findViewById(R.id.vault_media_position)).getText().toString());
        // Burst navigation while release is pending keeps the same dialog and plays only the final target.
        main(()->{dialog.findViewById(R.id.vault_media_previous).performClick();dialog.findViewById(R.id.vault_media_next).performClick();dialog.findViewById(R.id.vault_media_next).performClick();});awaitMedia(dialog);
        assertSame(dialog,field(activity,"preview"));assertNotSame(old,field(pager,"playing"));((CompletableFuture<?>)field(old,"released")).get(5,TimeUnit.SECONDS);assertTrue((Boolean)field(field(old,"source"),"closed"));assertEquals(last.name,((TextView)dialog.findViewById(R.id.vault_preview_title)).getText().toString());assertEquals("3 de 3",((TextView)dialog.findViewById(R.id.vault_media_position)).getText().toString());assertFalse(dialog.findViewById(R.id.vault_media_next).isEnabled());
        main(()->{dialog.findViewById(R.id.vault_media_previous).performClick();dialog.findViewById(R.id.vault_media_previous).performClick();});awaitMedia(dialog);assertEquals(first.name,((TextView)dialog.findViewById(R.id.vault_preview_title)).getText().toString());assertFalse(dialog.findViewById(R.id.vault_media_previous).isEnabled());assertTrue(dialog.findViewById(R.id.vault_media_next).isEnabled());
        main(()->{View v=dialog.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"test-v113-player.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new RuntimeException(e);}finally{b.recycle();}});
        File[] cleartext=new File(context.getCacheDir(),"preview").listFiles();assertTrue(cleartext==null||cleartext.length==0);GridView grid=activity.findViewById(R.id.vault_items);invoke("closePreview");assertSame(grid,activity.findViewById(R.id.vault_items));assertNull(field(activity,"mediaPager"));
        // Search determines the queue; an excluded video cannot be reached with the arrows.
        main(()->((EditText)activity.findViewById(R.id.vault_search)).setText("C segundo"));Thread.sleep(250);waitJob();selectItem(middle.id);Dialog filtered=(Dialog)field(activity,"preview");awaitMedia(filtered);assertEquals("1 de 1",((TextView)filtered.findViewById(R.id.vault_media_position)).getText().toString());assertFalse(filtered.findViewById(R.id.vault_media_previous).isEnabled());assertFalse(filtered.findViewById(R.id.vault_media_next).isEnabled());
        Object active=field(field(activity,"mediaPager"),"playing");invoke("requestLock");waitJob();((CompletableFuture<?>)field(active,"released")).get(5,TimeUnit.SECONDS);assertFalse(vault().isUnlocked());assertNull(field(activity,"preview"));assertNull(field(activity,"mediaPager"));
    }
}
