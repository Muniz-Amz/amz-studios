package com.amzstudios.cofre;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.*;

/** Owns the unlocked engine only while a user-started, journaled transfer runs. */
public final class VaultTransferService extends Service {
    private static final String CHANNEL="vault-operations",PAUSE="com.amzstudios.cofre.PAUSE";
    static final class Session {
        final VaultProfiles profiles;
        volatile boolean running=true,paused,completed;
        volatile long bytes;
        volatile String result="";
        Session(VaultProfiles profiles){this.profiles=profiles;}
    }
    private static volatile Session current;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private Session session;
    private boolean destroyed;
    static Session active(){Session s=current;return s!=null&&s.running?s:null;}
    static synchronized Session start(Context context,VaultProfiles profiles){
        if(active()!=null)throw new IllegalStateException("Já há uma operação em andamento.");
        Session next=new Session(profiles);current=next;
        try{context.startForegroundService(new Intent(context,VaultTransferService.class));return next;}
        catch(RuntimeException failure){next.running=false;current=null;throw failure;}
    }
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel(CHANNEL,"Operações do cofre",NotificationManager.IMPORTANCE_LOW);channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);manager.createNotificationChannel(channel);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&PAUSE.equals(intent.getAction())){if(session!=null)session.paused=true;return START_NOT_STICKY;}
        if(session!=null)return START_NOT_STICKY;
        session=active();if(session==null){stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,VaultTransferService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vault).setContentTitle("Cofre AMZ").setContentText("Operação em andamento").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET).addAction(new Notification.Action.Builder(null,"Pausar",pause).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(81,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(81,notification);
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CofreAMZ:transfer");wake.setReferenceCounted(false);wake.acquire(60*60*1000L);
        worker.execute(()->{
            long[] lastWake={SystemClock.elapsedRealtime()};
            VaultEngine.Progress progress=new VaultEngine.Progress(){
                public void update(long bytes){if(session.paused||Thread.currentThread().isInterrupted())throw new VaultEngine.TransferPausedException();session.bytes=bytes;long now=SystemClock.elapsedRealtime();if(now-lastWake[0]>30*60*1000L){wake.acquire(60*60*1000L);lastWake[0]=now;}}
                public void phase(String ignored){update(0);}
            };
            try{session.result=new VaultTransfers(getApplicationContext(),session.profiles.active()).execute(progress);}
            catch(VaultEngine.TransferPausedException paused){session.result="Operação pausada. Desbloqueie o cofre para retomar. Os originais ainda não transferidos foram preservados.";}
            catch(Exception failure){session.result="A operação parou com segurança. Confira o espaço livre, as permissões e a integridade dos arquivos. Desbloqueie o cofre para retomar ou encerrar a operação pendente. Os originais ainda não transferidos foram preservados.";}
            finally{session.profiles.lock();session.completed=true;new Handler(Looper.getMainLooper()).post(()->{if(wake!=null&&wake.isHeld())wake.release();if(destroyed){session.running=false;return;}stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
        });return START_NOT_STICKY;
    }
    // Android 15+ data-sync timeout callback; no automatic restart or persisted secret.
    public void onTimeout(int startId,int foregroundServiceType){if(session!=null)session.paused=true;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){destroyed=true;if(session!=null){session.paused=true;if(session.completed)session.running=false;}if(wake!=null&&wake.isHeld())wake.release();worker.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
