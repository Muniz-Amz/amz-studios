package com.amzstudios.cofre;

import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

/** Real-byte pause/restart probe, including long offsets beyond 2 GiB. Run with -Xmx96m. */
public final class ResumableVolumeProbe {
    private static final byte[] PATTERN=new byte[65536];
    static {new Random(20260915).nextBytes(PATTERN);}
    private static final class PatternSource extends InputStream {
        final long size;final MessageDigest digest;long position;
        PatternSource(long size,MessageDigest digest){this.size=size;this.digest=digest;}
        public int read(){if(position>=size)return -1;int value=PATTERN[(int)(position++%PATTERN.length)]&255;if(digest!=null)digest.update((byte)value);return value;}
        public int read(byte[] bytes,int offset,int length){
            if(length==0)return 0;if(position>=size)return -1;int wanted=(int)Math.min(length,size-position),copied=0;
            while(copied<wanted){int at=(int)(position%PATTERN.length),count=Math.min(wanted-copied,PATTERN.length-at);System.arraycopy(PATTERN,at,bytes,offset+copied,count);position+=count;copied+=count;}
            if(digest!=null)digest.update(bytes,offset,copied);return copied;
        }
    }
    public static void main(String[] args)throws Exception{
        long bytes=args.length==0?3L*1024*1024*1024+17:Long.parseLong(args[0]);
        long pauseAt=bytes>2L*1024*1024*1024+VaultEngine.CHUNK?2L*1024*1024*1024+VaultEngine.CHUNK:Math.max(1,bytes/2);
        File root=Files.createTempDirectory("cofre-resume-volume-").toFile();VaultEngine vault=new VaultEngine(root);
        char[] password="Senha sintetica do teste de retomada".toCharArray();String transfer=UUID.randomUUID().toString();long start=System.nanoTime();
        try{
            vault.create(password);
            try{vault.importFileResumable(new PatternSource(bytes,null),"volume.bin","application/octet-stream","",transfer,n->{if(n>=pauseAt)throw new VaultEngine.TransferPausedException();});throw new AssertionError("Pause was not honored");}
            catch(VaultEngine.TransferPausedException expected){System.out.println("pausedAtOrBeyond="+pauseAt);}
            if(!vault.list().isEmpty())throw new AssertionError("Partial import was committed");
            vault.lock();vault=new VaultEngine(root);vault.unlock(password);
            MessageDigest original=MessageDigest.getInstance("SHA-256");
            VaultEngine.Entry item=vault.importFileResumable(new PatternSource(bytes,original),"volume.bin","application/octet-stream","",transfer,null);
            byte[] wanted=original.digest();MessageDigest exported=MessageDigest.getInstance("SHA-256");
            try(DigestOutputStream out=new DigestOutputStream(OutputStream.nullOutputStream(),exported)){vault.exportFile(item.id,out,null);}
            if(!MessageDigest.isEqual(wanted,exported.digest()))throw new AssertionError("Plaintext digest mismatch");
            try(VaultEngine.RandomReader reader=vault.openRandomAccess(item.id,()->true)){
                for(long position:new long[]{0,2147483600L,2147483664L,bytes-70}){
                    if(position<0||position>=bytes)continue;byte[] block=new byte[(int)Math.min(64,bytes-position)];
                    if(reader.readAt(position,block,0,block.length)!=block.length)throw new AssertionError("Range short read");
                    for(int i=0;i<block.length;i++)if(block[i]!=PATTERN[(int)((position+i)%PATTERN.length)])throw new AssertionError("Range mismatch");
                }
            }
            if(new File(root,"transfers/"+transfer+".state").exists())throw new AssertionError("Checkpoint not retired");
            System.out.println("PASS bytes="+bytes+" maxHeap="+Runtime.getRuntime().maxMemory()+" totalSeconds="+(System.nanoTime()-start)/1e9);
        }finally{vault.lock();Arrays.fill(password,'\0');VaultEngine.removeTree(root);}
    }
}
