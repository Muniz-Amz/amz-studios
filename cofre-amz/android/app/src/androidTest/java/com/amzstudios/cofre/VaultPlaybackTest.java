package com.amzstudios.cofre;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Android decoding without touching the user's vault or depending on Activity lifecycle. */
public class VaultPlaybackTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final AtomicBoolean allowed=new AtomicBoolean(true);
    private final char[] password="Senha de teste da mídia 2026".toCharArray();
    private File root;
    private VaultEngine vault;
    private VaultEngine.Entry entry;
    private VaultMediaView view;

    @Before public void setup()throws Exception{
        root=new File(context.getCacheDir(),"playback-test-"+UUID.randomUUID());vault=new VaultEngine(root);vault.create(password);
        int samples=8000*20;ByteBuffer wav=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36+samples*2).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(samples*2);
        entry=vault.importFile(new ByteArrayInputStream(wav.array()),"Mídia sintética.wav","audio/wav","",null);
    }
    @After public void cleanup()throws Exception{if(view!=null)closeView();vault.lock();VaultEngine.removeTree(root);}
    private Object field(String name)throws Exception{Field field=VaultMediaView.class.getDeclaredField(name);field.setAccessible(true);return field.get(view);}
    private <T>T control(Callable<T> action)throws Exception{
        FutureTask<T> task=new FutureTask<>(action);assertTrue(((Handler)field("control")).post(task));return task.get(15,TimeUnit.SECONDS);
    }
    private void openView()throws Exception{
        inst.runOnMainSync(()->view=new VaultMediaView(new ContextThemeWrapper(context,R.style.AppTheme),vault,entry,allowed::get));
        long until=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<until){if(control(()->(Boolean)field("prepared")&&!(Boolean)field("resuming")))return;Thread.sleep(50);}
        fail("Media preparation did not finish");
    }
    private void closeView()throws Exception{
        VaultMediaView closing=view;inst.runOnMainSync(closing::close);closing.releaseFuture().get(20,TimeUnit.SECONDS);view=null;
    }
    private void pauseAt(long position)throws Exception{
        control(()->{MediaPlayer player=(MediaPlayer)field("player");player.pause();player.seekTo(position,MediaPlayer.SEEK_CLOSEST);return null;});
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until){if(control(()->Math.abs(((MediaPlayer)field("player")).getCurrentPosition()-position)<1500))return;Thread.sleep(50);}
        fail("Media seek did not finish");
    }
    private void chooseSpeed(float speed,String label)throws Exception{
        Method method=VaultMediaView.class.getDeclaredMethod("changeSpeed",float.class,String.class);method.setAccessible(true);
        inst.runOnMainSync(()->{try{method.invoke(view,speed,label);}catch(Exception error){throw new RuntimeException(error);}});
        control(()->null);
    }

    @Test public void closeCheckpointSurvivesLockAndReopeningAtSavedPosition()throws Exception{
        openView();pauseAt(10000);closeView();
        byte[] saved=Files.readAllBytes(new File(root,PlaybackStateStore.FILE).toPath());vault.lock();Thread.sleep(100);assertArrayEquals(saved,Files.readAllBytes(new File(root,PlaybackStateStore.FILE).toPath()));
        vault.unlock(password);try(PlaybackStateStore state=new PlaybackStateStore(vault)){assertTrue(state.resume(entry,20000)>=8500);}
        openView();assertTrue(control(()->((MediaPlayer)field("player")).getCurrentPosition())>=8500);vault.verify(entry.id);
    }

    @Test public void selectingEachSpeedKeepsPauseThenAppliesOnPlay()throws Exception{
        openView();pauseAt(5000);float[] values={.5f,1f,1.25f,1.5f,2f};String[] labels={"0,5×","1×","1,25×","1,5×","2×"};
        Method start=VaultMediaView.class.getDeclaredMethod("startPlayback");start.setAccessible(true);
        for(int i=0;i<values.length;i++){
            chooseSpeed(values[i],labels[i]);assertFalse(control(()->((MediaPlayer)field("player")).isPlaying()));assertEquals(values[i],control(()->(Float)field("playbackSpeed")),0f);
            float actual=control(()->{start.invoke(view);MediaPlayer player=(MediaPlayer)field("player");float value=player.getPlaybackParams().getSpeed();player.pause();return value;});assertEquals(values[i],actual,.01f);
        }
    }

    @Test public void damagedOptionalStateDoesNotPreventAuthenticatedPlayback()throws Exception{
        try(PlaybackStateStore state=new PlaybackStateStore(vault)){state.checkpoint(entry,10000,20000,false);}
        File state=new File(root,PlaybackStateStore.FILE);byte[] before=Files.readAllBytes(state.toPath());before[before.length-1]^=1;Files.write(state.toPath(),before);
        openView();assertTrue(control(()->((MediaPlayer)field("player")).getCurrentPosition())<5000);assertTrue(control(()->((MediaPlayer)field("player")).isPlaying()));vault.verify(entry.id);
    }

    @Test public void immediateCloseIsIdempotentAndCompletesReleaseBarrier()throws Exception{
        for(int i=0;i<12;i++){
            inst.runOnMainSync(()->{view=new VaultMediaView(new ContextThemeWrapper(context,R.style.AppTheme),vault,entry,allowed::get);view.close();view.close();});
            view.releaseFuture().get(20,TimeUnit.SECONDS);assertNull(field("player"));view=null;
        }
        vault.lock();assertFalse(vault.isUnlocked());
    }
}
