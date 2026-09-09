package com.amzstudios.cofre;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** Thumbnails exist in memory only; temporary plaintext is private and removed in finally. */
final class ThumbnailLoader {
    private final VaultEngine vault;private final File directory;private final ExecutorService worker;
    private final Handler ui;private final BooleanSupplier allowed;private volatile int generation;
    private final Set<String> pending=new HashSet<>(),unsupported=new HashSet<>();
    private final Map<String,List<ImageView>> waiting=new HashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    ThumbnailLoader(VaultEngine vault,File directory,ExecutorService worker,Handler ui,BooleanSupplier allowed){this.vault=vault;this.directory=directory;this.worker=worker;this.ui=ui;this.allowed=allowed;VaultEngine.removeTree(directory);directory.mkdirs();}
    void clear(){generation++;waiting.clear();pending.clear();unsupported.clear();cache.evictAll();}
    void bind(VaultEngine.Entry entry,ImageView image){
        String tag=generation+":"+entry.id;image.setTag(tag);Bitmap cached=cache.get(entry.id);
        if(cached!=null){image.setImageBitmap(cached);return;}if(unsupported.contains(entry.id))return;
        waiting.computeIfAbsent(entry.id,k->new ArrayList<>()).add(image);if(!pending.add(entry.id))return;
        int epoch=generation;
        worker.execute(()->{
            Bitmap result=null;boolean failed=false;File file=new File(directory,entry.id+".tmp");
            try{
                if(epoch!=generation||!allowed.getAsBoolean())return;
                if(entry.size>directory.getUsableSpace()-16L*1024*1024)throw new IOException("Sem espaço temporário");
                try(FileOutputStream out=new FileOutputStream(file)){vault.exportFile(entry.id,out,bytes->{if(epoch!=generation||!allowed.getAsBoolean())throw new java.util.concurrent.CancellationException();});}
                if(epoch!=generation||!allowed.getAsBoolean())return;
                if(entry.mime.startsWith("image/")){
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
                    if(bounds.outWidth<=0)throw new IOException("Imagem não suportada");BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                    while(bounds.outWidth/options.inSampleSize>640||bounds.outHeight/options.inSampleSize>640)options.inSampleSize*=2;
                    result=BitmapFactory.decodeFile(file.getPath(),options);
                }else{
                    MediaMetadataRetriever media=new MediaMetadataRetriever();try{media.setDataSource(file.getPath());
                        if(Build.VERSION.SDK_INT>=27)result=media.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,400,400);
                        else{Bitmap frame=media.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){float scale=Math.min(1f,400f/Math.max(frame.getWidth(),frame.getHeight()));result=Bitmap.createScaledBitmap(frame,Math.max(1,(int)(frame.getWidth()*scale)),Math.max(1,(int)(frame.getHeight()*scale)),true);if(result!=frame)frame.recycle();}}
                    }finally{media.release();}
                }
                failed=result==null;
            }catch(java.util.concurrent.CancellationException ignored){}catch(Exception|OutOfMemoryError e){failed=true;}
            finally{
                file.delete();Bitmap bitmap=result;boolean unsupportedFormat=failed;
                ui.post(()->{if(epoch!=generation){if(bitmap!=null)bitmap.recycle();return;}pending.remove(entry.id);List<ImageView> views=waiting.remove(entry.id);if(bitmap==null){if(unsupportedFormat)unsupported.add(entry.id);return;}if(!allowed.getAsBoolean()){bitmap.recycle();return;}cache.put(entry.id,bitmap);if(views!=null)for(ImageView view:views)if(tag.equals(view.getTag()))view.setImageBitmap(bitmap);});
            }
        });
    }
}
