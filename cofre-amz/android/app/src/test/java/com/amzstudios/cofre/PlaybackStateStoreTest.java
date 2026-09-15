package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional playback metadata must never weaken file authentication or leak across sessions. */
public class PlaybackStateStoreTest {
    private File temporary,state;
    private VaultEngine vault;
    private VaultEngine.Entry first,second;
    private final char[] password="Senha do teste de reprodução 2026".toCharArray();
    @Before public void setup()throws Exception{
        temporary=Files.createTempDirectory("amz-playback-").toFile();vault=new VaultEngine(new File(temporary,"vault"));vault.create(password);
        first=vault.importFile(new ByteArrayInputStream(new byte[]{1,2,3}),"Filme particular.mp4","video/mp4","",null);
        second=vault.importFile(new ByteArrayInputStream(new byte[]{4,5,6}),"Audio particular.wav","audio/wav","",null);
        state=new File(vault.privateDirectory(),PlaybackStateStore.FILE);
    }
    @After public void cleanup(){vault.lock();VaultEngine.removeTree(temporary);}
    private byte[] bytes()throws IOException{return Files.readAllBytes(state.toPath());}
    private void writePlain(byte[] plain)throws Exception{VaultEngine.writeAtomicState(state,vault.sealState(PlaybackStateStore.PURPOSE,plain));}

    @Test public void encryptedStateSurvivesRestartAndUsesFreshNonce()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,12000,60000,false);}
        byte[] saved=bytes();String raw=new String(saved,"ISO-8859-1");assertFalse(raw.contains(first.name));assertFalse(raw.contains(new String(password)));
        vault.lock();vault.unlock(password);
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){
            assertEquals(12000,store.resume(first,60000));store.checkpoint(first,12000,60000,false);
        }
        assertFalse(Arrays.equals(Arrays.copyOf(saved,12),Arrays.copyOf(bytes(),12)));vault.verify(first.id);
        assertFalse(vault.currentBackupFiles().contains(state));
    }

    @Test public void completedNearEndAndBeginningNeverResume()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){
            store.checkpoint(first,12000,60000,false);assertEquals(12000,store.resume(first,60000));
            store.checkpoint(first,12000,60000,true);assertEquals(0,store.resume(first,60000));
            store.checkpoint(first,59000,60000,false);assertEquals(0,store.resume(first,60000));
            store.checkpoint(first,999,60000,false);assertEquals(0,store.resume(first,60000));
        }
        assertFalse(PlaybackStateStore.resumable(Long.MAX_VALUE,Long.MAX_VALUE));
        assertFalse(PlaybackStateStore.resumable(-1,60000));
        assertFalse(PlaybackStateStore.resumable(1000,-1));
    }

    @Test public void digestSizeDurationAndLiveIndexBindThePosition()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){
            store.checkpoint(first,12000,60000,false);
            VaultEngine.Entry different=new VaultEngine.Entry(first.id,"",first.name,first.mime,false,first.size,first.modified,new byte[32]);
            assertEquals(0,store.resume(different,60000));assertEquals(0,store.resume(first,120000));
            VaultEngine.Entry size=new VaultEngine.Entry(first.id,"",first.name,first.mime,false,first.size+1,first.modified,first.digest);
            assertEquals(0,store.resume(size,60000));byte[] before=bytes();
            vault.trash(Collections.singleton(first.id));store.checkpoint(first,24000,60000,false);assertEquals(0,store.resume(first,60000));assertArrayEquals(before,bytes());
            vault.purgeTrash(Collections.singleton(first.id));assertEquals(0,store.resume(first,60000));
            assertThrows(IOException.class,()->store.checkpoint(first,24000,60000,false));assertArrayEquals(before,bytes());
        }
    }

    @Test public void everyCiphertextByteAndTruncatedStateFailSoftWithoutTouchingFiles()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,12000,60000,false);}
        byte[] valid=bytes(),file=Files.readAllBytes(new File(vault.privateDirectory(),first.id+".bin").toPath());
        for(int i=0;i<valid.length;i++){
            byte[] bad=valid.clone();bad[i]^=1;Files.write(state.toPath(),bad);
            try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertEquals("Offset "+i,0,store.resume(first,60000));assertTrue(store.wasDamaged());}
            assertArrayEquals(bad,bytes());
        }
        for(int length:new int[]{0,1,12,27,28,valid.length-1,valid.length+1,PlaybackStateStore.MAX_BYTES+1}){
            Files.write(state.toPath(),Arrays.copyOf(valid,length));
            try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertEquals(0,store.resume(first,60000));assertTrue(store.wasDamaged());}
        }
        assertArrayEquals(file,Files.readAllBytes(new File(vault.privateDirectory(),first.id+".bin").toPath()));vault.verify(first.id);
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,20000,60000,false);assertFalse(store.wasDamaged());assertEquals(20000,store.resume(first,60000));}
    }

    @Test public void authenticatedMalformedPayloadsAreBoundedAndRejected()throws Exception{
        byte[] header=ByteBuffer.allocate(8).putInt(0x414D5031).putInt(0).array();
        for(int count:new int[]{-1,1,PlaybackStateStore.MAX_RECORDS+1,Integer.MAX_VALUE}){
            byte[] invalid=header.clone();ByteBuffer.wrap(invalid).putInt(4,count);writePlain(invalid);
            try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertEquals(0,store.resume(first,60000));assertTrue(store.wasDamaged());}
        }
        writePlain(new byte[0]);try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertTrue(store.wasDamaged());}
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,12000,60000,false);}
        byte[] valid=vault.openState(PlaybackStateStore.PURPOSE,bytes());
        byte[] duplicate=Arrays.copyOf(valid,valid.length+80);System.arraycopy(valid,8,duplicate,valid.length,80);ByteBuffer.wrap(duplicate).putInt(4,2);writePlain(duplicate);
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertTrue(store.wasDamaged());}
        ByteBuffer.wrap(valid).putLong(8+16+32+8,Long.MAX_VALUE);writePlain(valid);
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertTrue(store.wasDamaged());}
    }

    @Test public void anotherVaultOrStatePurposeCannotUsePositions()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,12000,60000,false);}
        VaultEngine other=new VaultEngine(new File(temporary,"other"));other.create("Outra senha de teste 2026".toCharArray());
        try{
            Files.copy(state.toPath(),new File(other.privateDirectory(),PlaybackStateStore.FILE).toPath());
            try(PlaybackStateStore store=new PlaybackStateStore(other)){assertEquals(0,store.resume(first,60000));assertTrue(store.wasDamaged());}
        }finally{other.lock();}
        byte[] plain=vault.openState(PlaybackStateStore.PURPOSE,bytes());VaultEngine.writeAtomicState(state,vault.sealState("transfer-journal-v1",plain));
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){assertEquals(0,store.resume(first,60000));assertTrue(store.wasDamaged());}
    }

    @Test public void separateOwnersMergeInsteadOfOverwritingEachOther()throws Exception{
        try(PlaybackStateStore one=new PlaybackStateStore(vault);PlaybackStateStore two=new PlaybackStateStore(vault)){
            one.checkpoint(first,10000,60000,false);assertEquals(10000,two.resume(first,60000));
            two.checkpoint(second,20000,60000,false);one.checkpoint(first,15000,60000,false);
            assertEquals(20000,one.resume(second,60000));assertEquals(15000,two.resume(first,60000));
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                Future<?> a=workers.submit(()->{try{for(int i=0;i<10;i++)one.checkpoint(first,20000+i,60000,false);}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> b=workers.submit(()->{try{for(int i=0;i<10;i++)two.checkpoint(second,30000+i,60000,false);}catch(Exception e){throw new RuntimeException(e);}});
                a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
            }finally{workers.shutdownNow();}
            assertEquals(20009,two.resume(first,60000));assertEquals(30009,one.resume(second,60000));
        }
    }

    @Test public void lockedSessionCannotReadOrOverwriteCachedState()throws Exception{
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){
            store.checkpoint(first,12000,60000,false);byte[] before=bytes();vault.lock();
            assertEquals(0,store.resume(first,60000));assertThrows(IllegalStateException.class,()->store.checkpoint(first,24000,60000,false));assertArrayEquals(before,bytes());
            vault.unlock(password);assertEquals(12000,store.resume(first,60000));
        }
    }

    @Test public void recordLimitEvictsOldestWithoutGrowingWithCatalog()throws Exception{
        ByteBuffer plain=ByteBuffer.allocate(8+PlaybackStateStore.MAX_RECORDS*80);plain.putInt(0x414D5031).putInt(PlaybackStateStore.MAX_RECORDS);
        UUID oldest=new UUID(0,1);
        for(int i=0;i<PlaybackStateStore.MAX_RECORDS;i++)plain.putLong(0).putLong(i+1).put(new byte[32]).putLong(3).putLong(10000).putLong(60000).putLong(i);
        writePlain(plain.array());
        try(PlaybackStateStore store=new PlaybackStateStore(vault)){store.checkpoint(first,15000,60000,false);assertEquals(15000,store.resume(first,60000));}
        byte[] decoded=vault.openState(PlaybackStateStore.PURPOSE,bytes());ByteBuffer records=ByteBuffer.wrap(decoded);assertEquals(0x414D5031,records.getInt());assertEquals(PlaybackStateStore.MAX_RECORDS,records.getInt());assertNotEquals(oldest,new UUID(records.getLong(),records.getLong()));assertTrue(state.length()<PlaybackStateStore.MAX_BYTES);
    }
}
