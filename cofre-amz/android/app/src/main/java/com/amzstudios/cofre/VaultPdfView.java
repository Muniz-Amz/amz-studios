package com.amzstudios.cofre;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized native PDF decoding with one bounded displayed page. */
final class VaultPdfView extends LinearLayout implements AutoCloseable {
    private final ExecutorService decode=Executors.newSingleThreadExecutor();private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed=new AtomicBoolean();private final ImageView image;private final TextView number;private final Button previous,next;
    private PdfRenderer renderer;private ParcelFileDescriptor descriptor;private Bitmap displayed;private int page;
    VaultPdfView(Context c,File file){
        super(c);setOrientation(VERTICAL);image=new ImageView(c);image.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(image,new LayoutParams(-1,0,1));LinearLayout nav=new LinearLayout(c);nav.setGravity(Gravity.CENTER_VERTICAL);
        previous=button("Anterior");next=button("Próxima");number=new TextView(c);number.setTextColor(VaultUi.MUTED);number.setTextSize(13);number.setText("Abrindo PDF…");number.setGravity(Gravity.CENTER);nav.addView(previous);nav.addView(number,new LayoutParams(0,-2,1));nav.addView(next);addView(nav);
        previous.setOnClickListener(v->{page--;render();});next.setOnClickListener(v->{page++;render();});previous.setEnabled(false);next.setEnabled(false);
        decode.execute(()->{try{if(closed.get())return;descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);renderPage(0);}catch(Exception|OutOfMemoryError e){failure();}});
    }
    private Button button(String label){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextColor(VaultUi.ACCENT);b.setTextSize(13);b.setStateListAnimator(null);b.setBackground(VaultUi.ripple(getContext(),VaultUi.SURFACE,14));return b;}
    private void render(){previous.setEnabled(false);next.setEnabled(false);number.setText("Abrindo página…");int index=page;decode.execute(()->renderPage(index));}
    private void renderPage(int index){
        if(closed.get())return;Bitmap bitmap=null;
        try(PdfRenderer.Page pdf=renderer.openPage(index)){
            float scale=Math.min(1600f/pdf.getWidth(),2400f/pdf.getHeight());bitmap=Bitmap.createBitmap(Math.max(1,(int)(pdf.getWidth()*scale)),Math.max(1,(int)(pdf.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);pdf.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);Bitmap result=bitmap;int count=renderer.getPageCount();
            ui.post(()->{if(closed.get()){result.recycle();return;}Bitmap old=displayed;displayed=result;image.setImageBitmap(result);if(old!=null)old.recycle();number.setText((index+1)+" / "+count);previous.setEnabled(index>0);next.setEnabled(index+1<count);});
        }catch(Exception|OutOfMemoryError e){if(bitmap!=null)bitmap.recycle();failure();}
    }
    private void failure(){ui.post(()->{if(!closed.get()){number.setText("Não foi possível abrir este PDF.");previous.setEnabled(false);next.setEnabled(false);}});}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;image.setImageDrawable(null);if(displayed!=null){displayed.recycle();displayed=null;}decode.execute(()->{if(renderer!=null)renderer.close();if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}});decode.shutdown();}
}
