package com.amzstudios.cofre;

import android.media.MediaDataSource;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Gives Android authenticated ranges without a plaintext copy of the media file. */
final class VaultMediaSource extends MediaDataSource {
    private final VaultEngine vault;private final String id;private final BooleanSupplier allowed;
    private final long readLimit;private long readBytes;private VaultEngine.RandomReader reader;private boolean closed;
    VaultMediaSource(VaultEngine vault,String id,BooleanSupplier allowed,long readLimit){this.vault=vault;this.id=id;this.allowed=allowed;this.readLimit=readLimit;}
    private void open()throws IOException{
        if(closed||!allowed.getAsBoolean())throw new IOException("Leitura encerrada.");
        if(reader==null)try{reader=vault.openRandomAccess(id,()->!closed&&allowed.getAsBoolean());}catch(Exception e){throw new IOException("Não foi possível abrir o arquivo protegido.",e);}
    }
    @Override public synchronized long getSize()throws IOException{open();return reader.size();}
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int size)throws IOException{
        open();if(readBytes>readLimit-size)throw new IOException("Limite de leitura da miniatura.");
        int n=reader.readAt(position,buffer,offset,size);if(n>0)readBytes+=n;return n;
    }
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;if(reader!=null){reader.close();reader=null;}}
}
