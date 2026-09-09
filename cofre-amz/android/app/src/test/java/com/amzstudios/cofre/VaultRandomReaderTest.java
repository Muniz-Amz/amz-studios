package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VaultRandomReaderTest {
    private File temp,root;private VaultEngine vault;private byte[] data;private VaultEngine.Entry entry;
    @Before public void setup()throws Exception{temp=Files.createTempDirectory("cofre-range-").toFile();root=new File(temp,"vault");vault=new VaultEngine(root);vault.create("Senha de teste aleatoria".toCharArray());data=new byte[VaultEngine.CHUNK*2+87];new Random(42).nextBytes(data);entry=vault.importFile(new ByteArrayInputStream(data),"video.mp4","video/mp4","",null);}
    @After public void cleanup(){vault.lock();VaultEngine.removeTree(temp);}
    @Test public void authenticatesUnorderedAndCrossBlockReads()throws Exception{
        try(VaultEngine.RandomReader reader=vault.openRandomAccess(entry.id,()->true)){
            for(int offset:new int[]{data.length-70,0,VaultEngine.CHUNK-17,VaultEngine.CHUNK*2-12,301}){int n=Math.min(64,data.length-offset);byte[] actual=new byte[n+7];assertEquals(n,reader.readAt(offset,actual,7,n));assertArrayEquals(Arrays.copyOfRange(data,offset,offset+n),Arrays.copyOfRange(actual,7,7+n));}
            assertEquals(data.length,reader.size());assertEquals(-1,reader.readAt(data.length,new byte[1],0,1));assertEquals(0,reader.readAt(data.length,new byte[0],0,0));
        }
    }
    @Test public void corruptionIsRejectedBeforeReturningBlock()throws Exception{
        try(RandomAccessFile file=new RandomAccessFile(new File(root,entry.id+".bin"),"rw")){file.seek(VaultEngine.CHUNK+32L+40);file.writeByte(5);}
        try(VaultEngine.RandomReader reader=vault.openRandomAccess(entry.id,()->true)){assertThrows(IOException.class,()->reader.readAt(VaultEngine.CHUNK,new byte[40],0,40));}
    }
    @Test public void finalAuthenticationAndExactLengthAreRequired()throws Exception{
        File path=new File(root,entry.id+".bin");try(RandomAccessFile file=new RandomAccessFile(path,"rw")){long at=file.length()-1;file.seek(at);int last=file.read();file.seek(at);file.writeByte(last^5);}
        assertThrows(Exception.class,()->vault.openRandomAccess(entry.id,()->true));
        try(RandomAccessFile file=new RandomAccessFile(path,"rw")){file.setLength(file.length()+1);}
        assertThrows(Exception.class,()->vault.openRandomAccess(entry.id,()->true));
    }
    @Test public void closeAndCancellationRevokeCachedReads()throws Exception{
        AtomicBoolean allowed=new AtomicBoolean(true);VaultEngine.RandomReader reader=vault.openRandomAccess(entry.id,allowed::get);reader.readAt(0,new byte[30],0,30);allowed.set(false);assertThrows(IOException.class,()->reader.readAt(1,new byte[1],0,1));reader.close();reader.close();allowed.set(true);assertThrows(IOException.class,()->reader.readAt(1,new byte[1],0,1));
    }
    @Test public void streamCanDecodeFromStartTwiceWithoutTemporaryCopy()throws Exception{
        for(int i=0;i<2;i++)try(InputStream in=vault.openRandomAccess(entry.id,()->true).stream()){assertEquals(VaultEngine.CHUNK-10,in.skip(VaultEngine.CHUNK-10));byte[] actual=new byte[70];assertEquals(70,in.read(actual));assertArrayEquals(Arrays.copyOfRange(data,VaultEngine.CHUNK-10,VaultEngine.CHUNK+60),actual);}
    }
    @Test public void cancellationDuringVerificationNeverCommitsImport()throws Exception{
        VaultEngine.Progress cancel=new VaultEngine.Progress(){boolean verification;public void phase(String s){verification=true;}public void update(long n){if(verification)throw new java.util.concurrent.CancellationException();}};
        assertThrows(java.util.concurrent.CancellationException.class,()->vault.importFile(new ByteArrayInputStream(data),"cancelado.bin","application/octet-stream","",cancel));assertEquals(1,vault.list().size());vault.verify(entry.id);
    }
    @Test public void calculationsSupportVolumesBeyondFourGigabytes(){long bytes=100L*1024*1024*1024;assertEquals(bytes+100L*1024*32+36,VaultEngine.encryptedSize(bytes));assertThrows(ArithmeticException.class,()->VaultEngine.encryptedSize(Long.MAX_VALUE));}

    @Test public void cancelledRestoreNeverPromotesPartialVault()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();vault.backup(bytes);File target=new File(temp,"restore");VaultEngine restored=new VaultEngine(target);
        assertThrows(java.util.concurrent.CancellationException.class,()->restored.restore(new ByteArrayInputStream(bytes.toByteArray()),"Senha de teste aleatoria".toCharArray(),n->{throw new java.util.concurrent.CancellationException();}));
        assertFalse(restored.exists());assertFalse(target.exists());assertEquals(1,temp.listFiles().length);vault.verify(entry.id);
    }
}
