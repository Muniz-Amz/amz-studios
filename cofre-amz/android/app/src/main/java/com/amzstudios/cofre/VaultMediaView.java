package com.amzstudios.cofre;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Media callbacks and decryption run away from the Activity thread. */
final class VaultMediaView extends LinearLayout implements AutoCloseable {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("cofre-media");private final Handler control;
    private final AtomicBoolean closed=new AtomicBoolean();private final VaultMediaSource source;
    private MediaPlayer player;private boolean prepared;private final TextView status;private final Button toggle;private final SeekBar seek;
    VaultMediaView(Context context,VaultEngine vault,VaultEngine.Entry entry,BooleanSupplier allowed){
        super(context);setOrientation(VERTICAL);setKeepScreenOn(true);thread.start();control=new Handler(thread.getLooper());
        source=new VaultMediaSource(vault,entry.id,()->!closed.get()&&allowed.getAsBoolean(),Long.MAX_VALUE);
        SurfaceView surface=new SurfaceView(context);addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(context);status.setTextColor(Color.WHITE);status.setText("Preparando vídeo…");status.setId(R.id.vault_media_status);addView(status);
        seek=new SeekBar(context);seek.setMax(1000);addView(seek,new LinearLayout.LayoutParams(-1,-2));toggle=new Button(context);toggle.setText("Pausar");toggle.setEnabled(false);addView(toggle);
        control.post(()->{try{
            if(closed.get())return;player=new MediaPlayer();
            player.setOnPreparedListener(p->{if(closed.get())return;prepared=true;p.start();ui.post(()->{if(!closed.get()){toggle.setEnabled(true);status.setText("Reproduzindo");}});control.post(tick);});
            player.setOnErrorListener((p,what,extra)->{showError();return true;});
            player.setOnCompletionListener(p->ui.post(()->{if(!closed.get()){status.setText("Concluído");toggle.setText("Reproduzir");}}));
            player.setDataSource(source);player.prepareAsync();
        }catch(Exception e){showError();}});
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){control.post(()->{if(!closed.get()&&player!=null)player.setDisplay(h);});}public void surfaceChanged(SurfaceHolder h,int format,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){control.post(()->{if(!closed.get()&&player!=null)player.setDisplay(null);});}});
        toggle.setOnClickListener(v->control.post(()->{try{if(!prepared||closed.get())return;if(player.isPlaying()){player.pause();ui.post(()->toggle.setText("Reproduzir"));}else{player.start();ui.post(()->toggle.setText("Pausar"));}}catch(Exception e){showError();}}));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){int position=b.getProgress();control.post(()->{try{if(prepared&&!closed.get())player.seekTo((long)player.getDuration()*position/1000,MediaPlayer.SEEK_CLOSEST_SYNC);}catch(Exception e){showError();}});}public void onProgressChanged(SeekBar b,int progress,boolean user){}});
    }
    private final Runnable tick=new Runnable(){public void run(){if(closed.get()||!prepared)return;try{int duration=player.getDuration(),position=player.getCurrentPosition();boolean playing=player.isPlaying();ui.post(()->{if(!closed.get()&&!seek.isPressed()){seek.setProgress(duration<=0?0:(int)((long)position*1000/duration));status.setText((playing?"Reproduzindo · ":"Pausado · ")+time(position)+" / "+time(duration));}});control.postDelayed(this,500);}catch(Exception e){showError();}}};
    private static String time(int ms){long seconds=Math.max(0,ms/1000);return String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void showError(){prepared=false;ui.post(()->{if(!closed.get()){status.setText("Não foi possível reproduzir. Confira o formato e a integridade do arquivo.");toggle.setEnabled(false);}});}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;control.removeCallbacksAndMessages(null);ui.removeCallbacksAndMessages(null);control.post(()->{try{if(player!=null)player.release();}finally{try{source.close();}catch(Exception ignored){}thread.quitSafely();}});}
}
