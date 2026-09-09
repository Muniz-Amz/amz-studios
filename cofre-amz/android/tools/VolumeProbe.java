package com.amzstudios.cofre;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Reproducible real-byte stress probe. Run with -Xmx96m; defaults to 3 GiB + 17 bytes. */
public final class VolumeProbe {
    public static void main(String[] args)throws Exception{
        long bytes=args.length==0?3L*1024*1024*1024+17:Long.parseLong(args[0]);
        File root=Files.createTempDirectory("cofre-volume-probe-").toFile();VaultEngine vault=new VaultEngine(root);
        byte[] pattern=new byte[64*1024];new Random(123).nextBytes(pattern);MessageDigest expected=MessageDigest.getInstance("SHA-256");
        InputStream generated=new InputStream(){long position;public int read(){if(position>=bytes)return -1;int value=pattern[(int)(position++%pattern.length)]&255;expected.update((byte)value);return value;}public int read(byte[] b,int off,int len){if(position>=bytes)return -1;int wanted=(int)Math.min(len,bytes-position),copied=0;while(copied<wanted){int at=(int)(position%pattern.length),n=Math.min(wanted-copied,pattern.length-at);System.arraycopy(pattern,at,b,off+copied,n);position+=n;copied+=n;}expected.update(b,off,copied);return copied;}};
        long start=System.nanoTime();
        try{
            vault.create("Senha sintetica do teste de volume".toCharArray());
            VaultEngine.Entry entry=vault.importFile(generated,"volume.bin","application/octet-stream","",n->{if(n%(512L*1024*1024)==0)System.out.println("processed="+n);});
            long imported=System.nanoTime();byte[] wanted=expected.digest();
            MessageDigest actual=MessageDigest.getInstance("SHA-256");try(DigestOutputStream out=new DigestOutputStream(OutputStream.nullOutputStream(),actual)){vault.exportFile(entry.id,out,null);}
            if(!MessageDigest.isEqual(wanted,actual.digest()))throw new AssertionError("Digest mismatch");
            try(VaultEngine.RandomReader reader=vault.openRandomAccess(entry.id,()->true)){
                for(long position:new long[]{0,2147483600L,bytes-70}){if(position>=bytes)continue;byte[] part=new byte[(int)Math.min(64,bytes-position)];if(reader.readAt(position,part,0,part.length)!=part.length)throw new AssertionError();for(int i=0;i<part.length;i++)if(part[i]!=pattern[(int)((position+i)%pattern.length)])throw new AssertionError("Range mismatch");}
            }
            long finished=System.nanoTime();System.out.println("PASS bytes="+bytes+" maxHeap="+Runtime.getRuntime().maxMemory()+" encryptedBytes="+vault.storedBytes()+" importAndVerifySeconds="+(imported-start)/1e9+" totalSeconds="+(finished-start)/1e9);
        }finally{vault.lock();VaultEngine.removeTree(root);}
    }
}
