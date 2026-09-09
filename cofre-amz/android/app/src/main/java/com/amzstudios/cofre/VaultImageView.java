package com.amzstudios.cofre;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A bounded image preview, decoded off screen without exporting a plaintext file. */
final class VaultImageView extends FrameLayout implements AutoCloseable {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService decode=Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed=new AtomicBoolean();private final ImageView image;private Bitmap displayed;
    VaultImageView(Context c,VaultEngine vault,VaultEngine.Entry entry,BooleanSupplier allowed){
        super(c);image=new ImageView(c);image.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(image,new LayoutParams(-1,-1));
        TextView status=new TextView(c);status.setText("Abrindo imagem…");status.setTextColor(VaultUi.MUTED);status.setGravity(Gravity.CENTER);addView(status,new LayoutParams(-1,-1));
        BooleanSupplier valid=()->!closed.get()&&allowed.getAsBoolean();
        decode.execute(()->{Bitmap result=null;
            try{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=vault.openRandomAccess(entry.id,valid).stream()){BitmapFactory.decodeStream(in,null,bounds);}
                if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Formato de imagem não reconhecido.");
                BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;
                while(bounds.outWidth/opts.inSampleSize>2000||bounds.outHeight/opts.inSampleSize>2000)opts.inSampleSize*=2;
                try(InputStream in=vault.openRandomAccess(entry.id,valid).stream()){result=BitmapFactory.decodeStream(in,null,opts);}
                if(result==null)throw new IOException("Não foi possível abrir a imagem.");Bitmap bitmap=result;
                ui.post(()->{if(!valid.getAsBoolean()){bitmap.recycle();return;}displayed=bitmap;image.setImageBitmap(bitmap);status.setVisibility(GONE);});
            }catch(Exception|OutOfMemoryError e){if(result!=null)result.recycle();ui.post(()->{if(valid.getAsBoolean())status.setText("Não foi possível abrir esta imagem. Confira o formato e a integridade do arquivo.");});}
            finally{decode.shutdown();}
        });
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;image.setImageDrawable(null);if(displayed!=null){displayed.recycle();displayed=null;}decode.shutdownNow();}
}
