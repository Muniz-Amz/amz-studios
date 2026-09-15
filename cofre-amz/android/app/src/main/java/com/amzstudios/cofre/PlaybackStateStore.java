package com.amzstudios.cofre;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Small, optional playback metadata. Call off the UI thread. Owners share the vault's lock. */
final class PlaybackStateStore implements AutoCloseable {
    static final String PURPOSE="playback-resume-v1", FILE="playback.state";
    static final int MAX_RECORDS=2048, MAX_BYTES=256*1024;
    private static final int MAGIC=0x414D5031, RECORD_BYTES=80;
    private final VaultEngine vault;
    private final LinkedHashMap<UUID,Position> positions=new LinkedHashMap<>();
    private boolean damaged;
    private static final class Position {
        final byte[] digest; final long size,position,duration,updated;
        Position(byte[] digest,long size,long position,long duration,long updated){this.digest=digest.clone();this.size=size;this.position=position;this.duration=duration;this.updated=updated;}
        void clear(){Arrays.fill(digest,(byte)0);}
    }
    PlaybackStateStore(VaultEngine vault){this.vault=vault;}

    long resume(VaultEngine.Entry entry,long duration){
        synchronized(vault){
            load();
            try{
                if(!matchesCurrent(entry))return 0;
                Position saved=positions.get(UUID.fromString(entry.id));
                if(saved==null||saved.size!=entry.size||!MessageDigest.isEqual(saved.digest,entry.digest))return 0;
                if(duration<=0||Math.abs(saved.duration-duration)>Math.max(2000,duration/100))return 0;
                return resumable(saved.position,duration)?saved.position:0;
            }catch(Exception unavailable){return 0;}
        }
    }
    static boolean resumable(long position,long duration){
        long tail=Math.min(10000,Math.max(1000,duration/100));
        return position>=1000&&duration>2000&&position<duration-tail;
    }
    boolean wasDamaged(){synchronized(vault){load();return damaged;}}

    /** Clear finished/near-end positions. Index binding also rejects stale callbacks after removal. */
    void checkpoint(VaultEngine.Entry entry,long position,long duration,boolean completed)throws Exception{
        synchronized(vault){
            // Reload under the same lock before merging. An old player cannot overwrite the next
            // player's checkpoint, and locking the vault waits for this small atomic write.
            load();if(!matchesCurrent(entry))return;
            UUID id=UUID.fromString(entry.id);Position prior=positions.remove(id);if(prior!=null)prior.clear();
            if(!completed&&resumable(position,duration))positions.put(id,new Position(entry.digest,entry.size,position,duration,System.currentTimeMillis()));
            // Keep work bounded independently of the number of photos in the vault. Deleted IDs
            // cannot resume because every read checks the live index; old records age out here.
            while(positions.size()>MAX_RECORDS){Map.Entry<UUID,Position> oldest=positions.entrySet().iterator().next();oldest.getValue().clear();positions.remove(oldest.getKey());}
            persist();damaged=false;
        }
    }
    private boolean matchesCurrent(VaultEngine.Entry entry)throws IOException{
        VaultEngine.Entry current=vault.get(entry.id);
        return !current.folder&&!current.isTrashed()&&current.size==entry.size&&MessageDigest.isEqual(current.digest,entry.digest);
    }
    private void load(){
        clearPositions();damaged=false;byte[] plain=null;
        try{
            File file=new File(vault.privateDirectory(),FILE);if(!file.exists())return;
            if(file.length()<28||file.length()>MAX_BYTES)throw new IOException("Estado de reprodução inválido.");
            byte[] sealed=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(sealed);if(in.read()!=-1)throw new IOException("Estado de reprodução alterado.");}
            plain=vault.openState(PURPOSE,sealed);ByteBuffer in=ByteBuffer.wrap(plain);
            if(in.remaining()<8||in.getInt()!=MAGIC)throw new IOException("Estado de reprodução inválido.");
            int count=in.getInt();if(count<0||count>MAX_RECORDS||in.remaining()!=count*RECORD_BYTES)throw new IOException("Estado de reprodução inválido.");
            for(int i=0;i<count;i++){
                UUID id=new UUID(in.getLong(),in.getLong());byte[] digest=new byte[32];in.get(digest);
                long size=in.getLong(),position=in.getLong(),duration=in.getLong(),updated=in.getLong();
                try{if(size<0||!resumable(position,duration)||updated<0||positions.containsKey(id))throw new IOException("Posição de reprodução inválida.");positions.put(id,new Position(digest,size,position,duration,updated));}finally{Arrays.fill(digest,(byte)0);}
            }
        }catch(Exception invalid){clearPositions();damaged=true;}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private void persist()throws Exception{
        byte[] plain=new byte[8+positions.size()*RECORD_BYTES];
        try{
            ByteBuffer out=ByteBuffer.wrap(plain);out.putInt(MAGIC);out.putInt(positions.size());
            for(Map.Entry<UUID,Position> entry:positions.entrySet()){
                UUID id=entry.getKey();Position value=entry.getValue();out.putLong(id.getMostSignificantBits());out.putLong(id.getLeastSignificantBits());out.put(value.digest);out.putLong(value.size);out.putLong(value.position);out.putLong(value.duration);out.putLong(value.updated);
            }
            byte[] sealed=vault.sealState(PURPOSE,plain);
            VaultEngine.writeAtomicState(new File(vault.privateDirectory(),FILE),sealed);
        }finally{Arrays.fill(plain,(byte)0);}
    }
    private void clearPositions(){for(Position value:positions.values())value.clear();positions.clear();}
    @Override public void close(){synchronized(vault){clearPositions();damaged=false;}}
}
