package com.amzstudios.cofre;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Preparation, seeking and authenticated reads stay off the Activity thread. */
final class VaultMediaView extends LinearLayout implements AutoCloseable {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("cofre-media");private final Handler control;
    private final AtomicBoolean closed=new AtomicBoolean();private final VaultMediaSource source;
    private final CompletableFuture<Void> released=new CompletableFuture<>();
    private MediaPlayer player;private boolean prepared;private volatile boolean dragging;
    private final TextView status,elapsed,total;private final ImageButton toggle;private final SeekBar seek;private final ProgressBar loading;
    private final boolean video;private final AspectSurface surface;
    VaultMediaView(Context context,VaultEngine vault,VaultEngine.Entry entry,BooleanSupplier allowed){
        super(context);setOrientation(VERTICAL);setKeepScreenOn(true);video=entry.mime.startsWith("video/");thread.start();control=new Handler(thread.getLooper());
        source=new VaultMediaSource(vault,entry.id,()->!closed.get()&&allowed.getAsBoolean(),Long.MAX_VALUE);
        FrameLayout stage=new FrameLayout(context);stage.setBackgroundColor(Color.BLACK);LinearLayout.LayoutParams stageParams=new LinearLayout.LayoutParams(-1,0,1);stageParams.topMargin=dp(16);stageParams.bottomMargin=dp(18);addView(stage,stageParams);
        surface=new AspectSurface(context);stage.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        if(!video){surface.setVisibility(GONE);ImageView audio=new ImageView(context);audio.setImageDrawable(VaultUi.icon(context,"audio",VaultUi.ACCENT,72));stage.addView(audio,new FrameLayout.LayoutParams(dp(88),dp(88),Gravity.CENTER));}
        loading=new ProgressBar(context);loading.setIndeterminateTintList(ColorStateList.valueOf(VaultUi.ACCENT));stage.addView(loading,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));
        status=label(video?"Preparando vídeo…":"Preparando áudio…",13);status.setId(R.id.vault_media_status);status.setGravity(Gravity.CENTER);addView(status,new LinearLayout.LayoutParams(-1,-2));
        seek=new SeekBar(context);seek.setMax(1000);seek.setEnabled(false);seek.setContentDescription("Posição da reprodução");seek.setProgressTintList(ColorStateList.valueOf(VaultUi.ACCENT));seek.setThumbTintList(ColorStateList.valueOf(VaultUi.ACCENT));addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout timeline=new LinearLayout(context);elapsed=label("0:00",12);total=label("0:00",12);timeline.addView(elapsed,new LinearLayout.LayoutParams(0,-2,1));timeline.addView(total);timeline.setPadding(dp(12),0,dp(12),0);addView(timeline);
        LinearLayout buttons=new LinearLayout(context);buttons.setGravity(Gravity.CENTER);buttons.setPadding(0,dp(12),0,dp(14));
        Button back=skipButton("−10 s","Voltar 10 segundos"),forward=skipButton("+10 s","Avançar 10 segundos");toggle=new ImageButton(context);toggle.setImageDrawable(VaultUi.icon(context,"pause",VaultUi.BG,26));toggle.setContentDescription("Pausar");toggle.setBackground(VaultUi.ripple(context,VaultUi.ACCENT,30));toggle.setEnabled(false);toggle.setStateListAnimator(null);
        buttons.addView(back,new LinearLayout.LayoutParams(dp(76),dp(52)));LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(dp(60),dp(60));toggleParams.leftMargin=dp(24);toggleParams.rightMargin=dp(24);buttons.addView(toggle,toggleParams);buttons.addView(forward,new LinearLayout.LayoutParams(dp(76),dp(52)));addView(buttons);
        control.post(()->{try{
            if(closed.get())return;player=new MediaPlayer();
            player.setOnVideoSizeChangedListener((p,w,h)->ui.post(()->{if(!closed.get())surface.videoSize(w,h);}));
            player.setOnPreparedListener(p->{if(closed.get())return;prepared=true;p.start();int duration=p.getDuration();ui.post(()->{if(!closed.get()){toggle.setEnabled(true);seek.setEnabled(true);total.setText(time(duration));status.setText("Reproduzindo");if(!video)loading.setVisibility(GONE);}});control.post(tick);});
            player.setOnInfoListener((p,what,extra)->{if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)ui.post(()->{if(!closed.get())loading.setVisibility(GONE);});return false;});
            player.setOnErrorListener((p,what,extra)->{showError();return true;});
            player.setOnCompletionListener(p->ui.post(()->{if(!closed.get()){loading.setVisibility(GONE);status.setText("Concluído");setPlaying(false);}}));
            player.setDataSource(source);player.prepareAsync();
        }catch(Exception e){showError();}});
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){control.post(()->{try{if(!closed.get()&&player!=null)player.setDisplay(h);}catch(Exception e){showError();}});}public void surfaceChanged(SurfaceHolder h,int format,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){control.post(()->{try{if(!closed.get()&&player!=null)player.setDisplay(null);}catch(Exception ignored){}});}});
        toggle.setOnClickListener(v->control.post(()->{try{if(!prepared||closed.get())return;if(player.isPlaying())player.pause();else player.start();boolean playing=player.isPlaying();ui.post(()->{if(!closed.get())setPlaying(playing);});}catch(Exception e){showError();}}));
        back.setOnClickListener(v->skip(-10000));forward.setOnClickListener(v->skip(10000));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){dragging=true;}public void onStopTrackingTouch(SeekBar b){int position=b.getProgress();control.post(()->{try{if(prepared&&!closed.get())player.seekTo((long)player.getDuration()*position/1000,MediaPlayer.SEEK_CLOSEST_SYNC);}catch(Exception e){showError();}finally{dragging=false;}});}public void onProgressChanged(SeekBar b,int progress,boolean user){}});
    }
    private void skip(int ms){control.post(()->{try{if(prepared&&!closed.get())player.seekTo(Math.max(0,Math.min((long)player.getDuration(),(long)player.getCurrentPosition()+ms)),MediaPlayer.SEEK_CLOSEST_SYNC);}catch(Exception e){showError();}});}
    private void setPlaying(boolean playing){toggle.setImageDrawable(VaultUi.icon(getContext(),playing?"pause":"play",VaultUi.BG,26));toggle.setContentDescription(playing?"Pausar":"Reproduzir");}
    private final Runnable tick=new Runnable(){public void run(){if(closed.get()||!prepared)return;try{int duration=player.getDuration(),position=player.getCurrentPosition();boolean playing=player.isPlaying();ui.post(()->{if(!closed.get()){if(!dragging)seek.setProgress(duration<=0?0:(int)((long)position*1000/duration));elapsed.setText(time(position));status.setText(playing?"Reproduzindo":"Pausado");}});control.postDelayed(this,500);}catch(Exception e){showError();}}};
    private static String time(int ms){long seconds=Math.max(0,ms/1000);return seconds>=3600?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void showError(){prepared=false;control.removeCallbacks(tick);ui.post(()->{if(!closed.get()){loading.setVisibility(GONE);status.setText("Não foi possível reproduzir. Confira o formato e a integridade do arquivo.");toggle.setEnabled(false);seek.setEnabled(false);}});}
    private TextView label(String text,int sp){TextView v=new TextView(getContext());v.setText(text);v.setTextSize(sp);v.setTextColor(VaultUi.MUTED);return v;}
    private Button skipButton(String text,String description){Button v=new Button(getContext());v.setText(text);v.setTextColor(VaultUi.INK);v.setTextSize(14);v.setAllCaps(false);v.setContentDescription(description);v.setPadding(0,0,0,0);v.setMinWidth(0);v.setMinimumWidth(0);v.setBackground(VaultUi.ripple(getContext(),VaultUi.SURFACE,18));v.setStateListAnimator(null);return v;}
    private int dp(int n){return VaultUi.dp(getContext(),n);}
    void close(Runnable afterRelease){close();released.whenComplete((ignored,error)->ui.post(afterRelease));}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;setKeepScreenOn(false);control.removeCallbacksAndMessages(null);ui.removeCallbacksAndMessages(null);control.post(()->{try{if(player!=null)player.release();}catch(Exception ignored){}finally{try{source.close();}catch(Exception ignored){}thread.quitSafely();released.complete(null);}});}
    static final class AspectSurface extends SurfaceView {
        private int videoWidth=16,videoHeight=9;
        AspectSurface(Context c){super(c);}
        void videoSize(int w,int h){if(w>0&&h>0&&(w!=videoWidth||h!=videoHeight)){videoWidth=w;videoHeight=h;requestLayout();}}
        @Override protected void onMeasure(int wSpec,int hSpec){int w=MeasureSpec.getSize(wSpec),h=MeasureSpec.getSize(hSpec);if((long)w*videoHeight>(long)h*videoWidth)w=(int)((long)h*videoWidth/videoHeight);else h=(int)((long)w*videoHeight/videoWidth);setMeasuredDimension(w,h);}
    }
}
