package com.amzstudios.cofre;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded independent decoding; no plaintext media file and no transfer-queue backlog. */
final class ThumbnailLoader {
    private final VaultEngine vault;private final Handler ui;private final BooleanSupplier allowed;private volatile int generation;
    private final ThreadPoolExecutor decodeWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"cofre-thumbnails"));
    private final Set<String> pending=new HashSet<>(),unsupported=new HashSet<>();
    private final Map<String,List<WeakReference<ImageView>>> waiting=new HashMap<>();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    ThumbnailLoader(VaultEngine vault,File legacyDirectory,ExecutorService unused,Handler ui,BooleanSupplier allowed){this.vault=vault;this.ui=ui;this.allowed=allowed;VaultEngine.removeTree(legacyDirectory);legacyDirectory.mkdirs();}
    void pause(){generation++;decodeWorker.getQueue().clear();waiting.clear();pending.clear();}
    void clear(){pause();unsupported.clear();cache.evictAll();}
    void close(){clear();decodeWorker.shutdownNow();}
    void bind(VaultEngine.Entry entry,ImageView image){
        String tag=generation+":"+entry.id;image.setTag(tag);Bitmap cached=cache.get(entry.id);
        if(cached!=null){image.setImageBitmap(cached);return;}image.setImageDrawable(null);if(unsupported.contains(entry.id)||!allowed.getAsBoolean())return;
        List<WeakReference<ImageView>> views=waiting.computeIfAbsent(entry.id,k->new ArrayList<>());views.removeIf(v->v.get()==null);if(views.size()>=8)views.remove(0);views.add(new WeakReference<>(image));
        if(!pending.add(entry.id))return;int epoch=generation;
        try{decodeWorker.execute(()->{
            Bitmap result=null;boolean failed=false;long deadline=SystemClock.elapsedRealtime()+8000;
            BooleanSupplier valid=()->epoch==generation&&allowed.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline;
            try{
                if(!valid.getAsBoolean())return;
                if(entry.mime.startsWith("image/")){
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    try(InputStream in=vault.openRandomAccess(entry.id,valid).stream()){BitmapFactory.decodeStream(in,null,bounds);}
                    if(bounds.outWidth<=0)throw new IOException("Imagem não suportada");BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                    while(bounds.outWidth/options.inSampleSize>640||bounds.outHeight/options.inSampleSize>640)options.inSampleSize*=2;
                    try(InputStream in=vault.openRandomAccess(entry.id,valid).stream()){result=BitmapFactory.decodeStream(in,null,options);}
                }else{
                    try(VaultMediaSource source=new VaultMediaSource(vault,entry.id,valid,64L*1024*1024)){
                        MediaMetadataRetriever media=new MediaMetadataRetriever();try{media.setDataSource(source);
                            if(Build.VERSION.SDK_INT>=27)result=media.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,400,400);
                            else{Bitmap frame=media.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){float scale=Math.min(1f,400f/Math.max(frame.getWidth(),frame.getHeight()));result=Bitmap.createScaledBitmap(frame,Math.max(1,(int)(frame.getWidth()*scale)),Math.max(1,(int)(frame.getHeight()*scale)),true);if(result!=frame)frame.recycle();}}
                        }finally{media.release();}
                    }
                }
                failed=result==null;
            }catch(Exception|OutOfMemoryError e){failed=true;}
            finally{
                Bitmap bitmap=result;boolean unsupportedFormat=failed;
                ui.post(()->{if(epoch!=generation){if(bitmap!=null)bitmap.recycle();return;}pending.remove(entry.id);List<WeakReference<ImageView>> targets=waiting.remove(entry.id);if(bitmap==null){if(unsupportedFormat)unsupported.add(entry.id);return;}if(!allowed.getAsBoolean()){bitmap.recycle();return;}cache.put(entry.id,bitmap);if(targets!=null)for(WeakReference<ImageView> ref:targets){ImageView view=ref.get();if(view!=null&&tag.equals(view.getTag()))view.setImageBitmap(bitmap);}});
            }
        });}catch(RejectedExecutionException e){pending.remove(entry.id);waiting.remove(entry.id);}
    }
}
