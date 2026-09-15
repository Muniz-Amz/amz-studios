package com.amzstudios.cofre;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned, offline vault format. No Android dependency; exercised on JVM and device. */
public final class VaultEngine {
    static final int ITERATIONS = 600000, CHUNK = 1024 * 1024;
    static final String CONFIG = "vault.key", INDEX = "index.enc", RECOVERY = "recovery.key", BACKUP_STATE = "backup.state";
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final int MAX_STATE_BYTES=4*1024*1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File root;
    private byte[] master;
    private List<Entry> entries = new ArrayList<>();
    public interface Progress { void update(long bytes); default void phase(String name){} }
    /** The caller may pause at any progress callback; a resumable import keeps its durable checkpoint. */
    public static final class TransferPausedException extends RuntimeException {
        public TransferPausedException(){super("Transferência pausada.");}
        public TransferPausedException(String message){super(message);}
    }
    public static final class Entry {
        public final String id, parent, name, mime, trashRoot;
        public final boolean folder;
        public final long size, modified, trashedAt;
        final byte[] digest;
        Entry(String id, String parent, String name, String mime, boolean folder, long size, long modified, byte[] digest) {
            this(id,parent,name,mime,folder,size,modified,digest,0,"");
        }
        Entry(String id,String parent,String name,String mime,boolean folder,long size,long modified,byte[] digest,long trashedAt,String trashRoot) {
            this.id=id; this.parent=parent; this.name=name; this.mime=mime; this.folder=folder;
            this.size=size; this.modified=modified; this.digest=digest.clone();this.trashedAt=trashedAt;this.trashRoot=trashRoot;
        }
        public boolean isTrashed(){return trashedAt>0;}
        Entry relocate(String parent, String name) { return new Entry(id,parent,name,mime,folder,size,modified,digest,trashedAt,trashRoot); }
        Entry inTrash(long time,String root){return new Entry(id,parent,name,mime,folder,size,modified,digest,time,root);}
    }
    public VaultEngine(File root) { this.root=root; }
    public boolean exists() { return new File(root,CONFIG).isFile(); }
    public synchronized boolean isUnlocked() { return master!=null; }
    public boolean hasRecovery(){return new File(root,RECOVERY).isFile();}
    public synchronized List<Entry> list() { requireUnlocked(); return new ArrayList<>(entries); }
    public synchronized Entry get(String id) throws IOException {
        requireUnlocked();
        for (Entry e:entries) if(e.id.equals(id)) return e;
        throw new IOException("Arquivo não encontrado no cofre.");
    }
    public void create(char[] password) throws Exception {
        requireEmptyRoot();
        if(password.length<10) throw new IOException("Use uma senha com pelo menos 10 caracteres.");
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("Não foi possível criar o cofre.");
        master=random(32); entries=new ArrayList<>();
        try { saveIndex(entries); writeConfig(password); }
        catch(Exception e) { lock(); throw e; }
    }
    public void unlock(char[] password) throws Exception {
        lock();
        try {
            master=readMaster(password);
            entries=readIndex();
            validateEntries(entries);
            for(Entry e:entries) if(!e.folder && !content(e.id).isFile()) throw new IOException("Arquivo ausente. Restaure um backup íntegro.");
        } catch(Exception e) { lock(); throw e; }
    }
    /** Checks a password envelope without unlocking or changing this vault's current session. */
    boolean acceptsPassword(char[] password)throws Exception{
        try{byte[] candidate=readMaster(password);Arrays.fill(candidate,(byte)0);return true;}
        catch(AEADBadTagException e){return false;}
    }
    private byte[] readMaster(char[] password)throws Exception{
        File config=new File(root,CONFIG);if(config.length()!=84)throw new IOException("Cabeçalho inválido.");
        return unwrapPassword(Files.readAllBytes(config.toPath()),password);
    }
    private static byte[] unwrapPassword(byte[] config,char[] password)throws Exception{
        if(config.length!=84)throw new IOException("Cabeçalho inválido.");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(config))) {
            if(in.readInt()!=0x414D5A31 || in.readInt()!=ITERATIONS) throw new IOException("Formato de cofre não suportado.");
            byte[] salt=new byte[16], iv=new byte[12], wrapped=new byte[48];
            in.readFully(salt); in.readFully(iv); in.readFully(wrapped);
            if(in.read()!=-1) throw new IOException("Cabeçalho inválido.");
            byte[] key=derive(password,salt);
            try { return crypt(Cipher.DECRYPT_MODE,key,iv,"AMZ/key/1",wrapped); }
            finally { Arrays.fill(key,(byte)0); }
        }
    }
    public synchronized void lock() {
        if(master!=null) Arrays.fill(master,(byte)0);
        master=null; entries=new ArrayList<>();
    }
    File privateDirectory(){return root;}
    /** Domain-separated, bounded private metadata; no password or master key is persisted here. */
    synchronized byte[] sealState(String purpose,byte[] plaintext)throws Exception{
        requireUnlocked();statePurpose(purpose);if(plaintext.length>stateLimit(purpose))throw new IOException("Estado muito grande.");
        byte[] iv=random(12),cipher=crypt(Cipher.ENCRYPT_MODE,master,iv,"AMZ/state/1/"+purpose,plaintext);
        byte[] result=new byte[iv.length+cipher.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(cipher,0,result,iv.length,cipher.length);return result;
    }
    synchronized byte[] openState(String purpose,byte[] ciphertext)throws Exception{requireUnlocked();return unseal(master,purpose,ciphertext);}
    static byte[] openStateWithPassword(byte[] config,char[] password,String purpose,byte[] ciphertext)throws Exception{
        byte[] key=unwrapPassword(config,password);try{return unseal(key,purpose,ciphertext);}finally{Arrays.fill(key,(byte)0);}
    }
    static byte[] openStateWithRecovery(byte[] envelope,String code,String purpose,byte[] ciphertext)throws Exception{
        if(envelope.length!=64)throw new IOException("Recuperação inválida.");byte[] recovery=decodeRecovery(code),key=null;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(envelope))){
            if(in.readInt()!=0x414D5231)throw new IOException("Recuperação inválida.");byte[] iv=new byte[12],wrapped=new byte[48];in.readFully(iv);in.readFully(wrapped);
            key=crypt(Cipher.DECRYPT_MODE,recovery,iv,"AMZ/recovery/1",wrapped);return unseal(key,purpose,ciphertext);
        }finally{Arrays.fill(recovery,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}
    }
    private static byte[] unseal(byte[] key,String purpose,byte[] ciphertext)throws Exception{
        statePurpose(purpose);if(ciphertext.length<28||ciphertext.length>stateLimit(purpose)+28)throw new IOException("Estado inválido.");
        return crypt(Cipher.DECRYPT_MODE,key,Arrays.copyOf(ciphertext,12),"AMZ/state/1/"+purpose,Arrays.copyOfRange(ciphertext,12,ciphertext.length));
    }
    private static void statePurpose(String purpose)throws IOException{if(purpose==null||!purpose.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}"))throw new IOException("Finalidade do estado inválida.");}
    private static int stateLimit(String purpose){return "backup-set-manifest".equals(purpose)?32*1024*1024:MAX_STATE_BYTES;}
    private synchronized byte[] session(){requireUnlocked();return master;}
    private synchronized void checkSession(byte[] expected){if(master==null||master!=expected)throw new IllegalStateException("Desbloqueie o cofre novamente para retomar.");}
    public void changePassword(char[] password) throws Exception {
        requireUnlocked();
        if(password.length<10) throw new IOException("Use pelo menos 10 caracteres.");
        writeConfig(password);
    }
    /** A uniformly random 256-bit key. Only its wrapped master-key envelope is persisted. */
    public String createRecoveryKey() throws Exception {
        requireUnlocked(); byte[] recovery=random(32),iv=random(12);
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x414D5231);out.write(iv);out.write(crypt(Cipher.ENCRYPT_MODE,recovery,iv,"AMZ/recovery/1",master));
            atomicBytes(new File(root,RECOVERY),bytes.toByteArray());
            StringBuilder code=new StringBuilder("AMZ1");for(int i=0;i<recovery.length;i++){if(i%2==0)code.append('-');code.append(String.format(Locale.ROOT,"%02X",recovery[i]&255));}return code.toString();
        } finally {Arrays.fill(recovery,(byte)0);}
    }
    public void recover(String code,char[] newPassword) throws Exception {
        if(newPassword.length<10)throw new IOException("Use pelo menos 10 caracteres na nova senha.");
        unlockWithRecovery(code);
        try {writeConfig(newPassword);}catch(Exception e){lock();throw e;}
    }
    void unlockWithRecovery(String code) throws Exception {
        lock();byte[] recovery=decodeRecovery(code);
        try(DataInputStream in=new DataInputStream(new FileInputStream(new File(root,RECOVERY)))) {
            if(in.readInt()!=0x414D5231)throw new IOException("Chave de recuperação inválida.");
            byte[] iv=new byte[12],wrapped=new byte[48];in.readFully(iv);in.readFully(wrapped);if(in.read()!=-1)throw new IOException("Recuperação inválida.");
            master=crypt(Cipher.DECRYPT_MODE,recovery,iv,"AMZ/recovery/1",wrapped);entries=readIndex();validateEntries(entries);
            for(Entry e:entries)if(!e.folder&&!content(e.id).isFile())throw new IOException("Arquivo ausente. Restaure um backup íntegro.");
        } catch(Exception e){lock();throw e;}finally{Arrays.fill(recovery,(byte)0);}
    }
    private static byte[] decodeRecovery(String code) throws IOException {
        String value=code.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]","");
        if(!value.matches("AMZ1[0-9A-F]{64}"))throw new IOException("Confira o código completo, começando por AMZ1.");
        byte[] key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)Integer.parseInt(value.substring(4+i*2,6+i*2),16);return key;
    }
    public long storedBytes(){long size=0;File[] files=root.listFiles();if(files!=null)for(File f:files){if(f.isFile())size+=f.length();else if(f.getName().equals("transfers")){File[] partials=f.listFiles();if(partials!=null)for(File p:partials)if(p.isFile())size+=p.length();}}return size;}
    public long availableBytes(){return root.getUsableSpace();}
    public long trashBytes(){requireUnlocked();long bytes=0;for(Entry e:entries)if(e.isTrashed()&&!e.folder)bytes+=content(e.id).length();return bytes;}
    public long lastBackupAt(){try{return backupState().time;}catch(Exception e){return 0;}}
    public boolean needsBackup(){requireUnlocked();if(entries.isEmpty()&&!hasRecovery()&&!new File(root,BACKUP_STATE).exists())return false;try{return !MessageDigest.isEqual(backupState().fingerprint,fingerprint());}catch(Exception e){return true;}}
    private static final class BackupState {long time;byte[] fingerprint;BackupState(long time,byte[] fingerprint){this.time=time;this.fingerprint=fingerprint;}}
    private BackupState backupState() throws Exception {
        requireUnlocked();File file=new File(root,BACKUP_STATE);if(file.length()!=68)throw new IOException("Sem backup confirmado.");byte[] data=Files.readAllBytes(file.toPath());
        byte[] plain=crypt(Cipher.DECRYPT_MODE,master,Arrays.copyOf(data,12),"AMZ/backup-state/1",Arrays.copyOfRange(data,12,data.length));
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))){long time=in.readLong();byte[] hash=new byte[32];in.readFully(hash);return new BackupState(time,hash);}finally{Arrays.fill(plain,(byte)0);}
    }
    private byte[] fingerprint() throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String name:new String[]{CONFIG,INDEX,RECOVERY}){File file=new File(root,name);digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update((byte)(file.exists()?1:0));if(file.exists())try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}}
        return digest.digest();
    }
    /** Call only after the destination backup has been closed, reopened and verified. */
    public void markBackupCompleted() throws Exception {
        requireUnlocked();ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeLong(System.currentTimeMillis());out.write(fingerprint());
        byte[] iv=random(12);ByteArrayOutputStream result=new ByteArrayOutputStream();result.write(iv);result.write(crypt(Cipher.ENCRYPT_MODE,master,iv,"AMZ/backup-state/1",bytes.toByteArray()));atomicBytes(new File(root,BACKUP_STATE),result.toByteArray());
    }
    private void writeConfig(char[] password) throws Exception {
        byte[] salt=random(16), iv=random(12), key=derive(password,salt), wrapped;
        try { wrapped=crypt(Cipher.ENCRYPT_MODE,key,iv,"AMZ/key/1",master); }
        finally { Arrays.fill(key,(byte)0); }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x414D5A31); out.writeInt(ITERATIONS); out.write(salt); out.write(iv); out.write(wrapped);
        atomicBytes(new File(root,CONFIG),bytes.toByteArray());
    }
    public Entry folder(String parent,String name) throws Exception {
        requireUnlocked(); validateDestination(parent,name,null);
        Entry e=new Entry(UUID.randomUUID().toString(),parent,name.trim(),"",true,0,System.currentTimeMillis(),new byte[32]);
        List<Entry> next=list(); next.add(e); commit(next); return e;
    }
    public void rename(String id,String name) throws Exception {
        Entry e=get(id); relocate(id,e.parent,name);
    }
    public void move(String id,String destination) throws Exception { Entry e=get(id); relocate(id,destination,e.name); }
    private void relocate(String id,String parent,String name) throws Exception {
        Entry e=get(id);if(e.isTrashed())throw new IOException("Restaure o item antes de movê-lo."); validateDestination(parent,name,id);
        String cursor=parent;
        while(!cursor.isEmpty()) { if(cursor.equals(id)) throw new IOException("Uma pasta não pode ficar dentro dela mesma."); cursor=get(cursor).parent; }
        List<Entry> next=list(); next.set(next.indexOf(e),e.relocate(parent,name.trim())); commit(next);
    }
    public List<Entry> selectionRoots(Collection<String> ids) throws IOException {
        requireUnlocked();Set<String> selected=new LinkedHashSet<>(ids);List<Entry> roots=new ArrayList<>();
        for(String id:selected){Entry entry=get(id);boolean nested=false;String p=entry.parent;while(!p.isEmpty()){if(selected.contains(p)){nested=true;break;}p=get(p).parent;}if(!nested)roots.add(entry);}return roots;
    }
    public List<Entry> activeSubtree(String id) throws IOException {
        Entry rootEntry=get(id);if(rootEntry.isTrashed())throw new IOException("Restaure o item primeiro.");
        Set<String> ids=new HashSet<>();ids.add(id);boolean changed;do{changed=false;for(Entry e:entries)if(!e.isTrashed()&&ids.contains(e.parent))changed|=ids.add(e.id);}while(changed);
        List<Entry> result=new ArrayList<>();for(Entry e:entries)if(ids.contains(e.id))result.add(e);return result;
    }
    public void moveAll(Collection<String> ids,String destination) throws Exception {
        if(!destination.isEmpty()){Entry target=get(destination);if(!target.folder||target.isTrashed())throw new IOException("Pasta de destino inválida.");}
        List<Entry> roots=selectionRoots(ids),next=list();
        for(Entry e:roots){if(e.isTrashed())throw new IOException("Restaure o item primeiro.");String p=destination;while(!p.isEmpty()){if(p.equals(e.id))throw new IOException("Uma pasta não pode ficar dentro dela mesma.");p=get(p).parent;}next.set(next.indexOf(e),e.relocate(destination,e.name));}
        validateEntries(next);if(!roots.isEmpty())commit(next);
    }
    public void trash(Collection<String> ids) throws Exception {
        List<Entry> roots=selectionRoots(ids),next=list();long now=Math.max(1,System.currentTimeMillis());
        for(Entry rootEntry:roots){if(rootEntry.isTrashed())throw new IOException("Este item já está na lixeira.");for(Entry e:activeSubtree(rootEntry.id))next.set(next.indexOf(e),e.inTrash(now,rootEntry.id));}
        validateEntries(next);if(!roots.isEmpty())commit(next);
    }
    public void restoreTrash(Collection<String> roots) throws Exception {
        Set<String> groups=new LinkedHashSet<>(roots),restore=new HashSet<>();
        for(String id:groups){Entry e=get(id);if(!e.isTrashed()||!e.trashRoot.equals(id))throw new IOException("Selecione um item da lixeira.");}
        for(Entry e:entries)if(groups.contains(e.trashRoot))restore.add(e.id);
        List<Entry> next=list();for(int i=0;i<next.size();i++){Entry e=next.get(i);if(restore.contains(e.id)){String p=e.parent;if(!p.isEmpty()&&!restore.contains(p)&&get(p).isTrashed())p="";next.set(i,e.relocate(p,e.name).inTrash(0,""));}}
        Set<String> names=new HashSet<>();for(Entry e:next)if(!e.isTrashed()&&!restore.contains(e.id))names.add(e.parent+"/"+e.name.toLowerCase(Locale.ROOT));
        for(int i=0;i<next.size();i++){Entry e=next.get(i);if(restore.contains(e.id)){String candidate=e.name;int n=1;while(!names.add(e.parent+"/"+candidate.toLowerCase(Locale.ROOT))){String suffix=n++==1?" (restaurado)":" (restaurado "+(n-1)+")";int dot=e.name.lastIndexOf('.');String ext=!e.folder&&dot>0?e.name.substring(dot):"",base=ext.isEmpty()?e.name:e.name.substring(0,dot);if(ext.length()>40)ext="";candidate=base.substring(0,Math.min(base.length(),200-suffix.length()-ext.length()))+suffix+ext;}next.set(i,e.relocate(e.parent,candidate));}}
        validateEntries(next);if(!restore.isEmpty())commit(next);
    }
    public void purgeTrash(Collection<String> roots) throws Exception {
        Set<String> groups=new HashSet<>(roots),removed=new HashSet<>();
        for(String id:groups){Entry e=get(id);if(!e.isTrashed()||!e.trashRoot.equals(id))throw new IOException("A exclusão definitiva só está disponível na lixeira.");}
        for(Entry e:entries)if(groups.contains(e.trashRoot))removed.add(e.id);removeIds(removed);
    }
    private void removeIds(Set<String> removed) throws Exception {
        if(removed.isEmpty())return;List<Entry> next=new ArrayList<>();
        for(Entry e:entries)if(!removed.contains(e.id))next.add(removed.contains(e.parent)?e.relocate("",e.name):e);
        validateEntries(next);commit(next);for(String id:removed)content(id).delete();
    }
    public Entry importFile(InputStream source,String name,String mime,String parent,Progress progress) throws Exception {
        byte[] active=session(); validateDestination(parent,name,null);validateMime(mime);
        String id=UUID.randomUUID().toString(); File temp=new File(root,id+".part"), target=content(id);
        byte[] key=fileKey(id), buffer=new byte[CHUNK]; MessageDigest sha=MessageDigest.getInstance("SHA-256");
        long total=0, index=0,nextSpaceCheck=0;
        try {
            try(FileOutputStream stream=new FileOutputStream(temp); DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream))) {
                out.writeInt(0x414D4631);
                while(true) {
                    checkSession(active);
                    int count=readChunk(source,buffer); if(count==0) break;
                    if(total>=nextSpaceCheck){if(root.getUsableSpace()<34L*1024*1024)throw new IOException("Espaço insuficiente; original preservado.");nextSpaceCheck=total+64L*1024*1024;}
                    byte[] plain=Arrays.copyOf(buffer,count), iv=random(12);
                    byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,key,iv,aad(id,index++,count),plain);
                    sha.update(plain); Arrays.fill(plain,(byte)0);
                    out.writeInt(count); out.write(iv); out.write(encrypted); total+=count;
                    if(progress!=null) progress.update(total);
                }
                byte[] iv=random(12);
                out.writeInt(0); out.write(iv); out.write(crypt(Cipher.ENCRYPT_MODE,key,iv,aad(id,index,0),new byte[0]));
                out.flush(); stream.getFD().sync();
            }
            Entry entry=new Entry(id,parent,name.trim(),mime==null?"application/octet-stream":mime,false,total,System.currentTimeMillis(),sha.digest());
            // Read back and authenticate every chunk before committing metadata or allowing source removal.
            if(progress!=null)progress.phase("Verificando arquivo protegido · "+entry.name);readContent(temp,entry,null,progress);
            checkSession(active);promoteNew(temp,target);
            synchronized(this){checkSession(active);List<Entry> next=list(); next.add(entry); commit(next);}
            return entry;
        } finally { Arrays.fill(key,(byte)0); Arrays.fill(buffer,(byte)0); if(temp.exists()) temp.delete(); }
    }
    private static final class ImportState {
        String id,parent,name,mime;long size,chunks,modified;boolean complete;byte[] digest;
        long offset(){return Math.addExact(4,Math.addExact(size,Math.multiplyExact(32,chunks+(complete?1:0))));}
        Entry entry(){return new Entry(id,parent,name,mime,false,size,modified,digest);}
    }
    private File transferDirectory()throws IOException{
        File dir=new File(root,"transfers");if(!dir.isDirectory()&&!dir.mkdir())throw new IOException("Não foi possível preparar a retomada.");return dir;
    }
    private static void validateTransferId(String id)throws IOException{if(id==null||!id.matches(UUID_PATTERN))throw new IOException("Identificador de transferência inválido.");}
    private void saveImport(File checkpoint,ImportState state)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(1);out.writeUTF(state.id);out.writeUTF(state.parent);out.writeUTF(state.name);out.writeUTF(state.mime);
        out.writeLong(state.size);out.writeLong(state.chunks);out.writeLong(state.modified);out.writeBoolean(state.complete);
        if(state.complete)out.write(state.digest);
        byte[] plain=bytes.toByteArray();try{writeAtomicState(checkpoint,sealState("import-resume-v1/"+state.id,plain));}finally{Arrays.fill(plain,(byte)0);}
    }
    private ImportState readImport(File checkpoint,String id)throws Exception{
        if(checkpoint.length()>4096)throw new IOException("Registro de retomada inválido.");
        byte[] plain=openState("import-resume-v1/"+id,Files.readAllBytes(checkpoint.toPath()));
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))){
            if(in.readInt()!=1)throw new IOException("Registro de retomada incompatível.");ImportState s=new ImportState();
            s.id=in.readUTF();s.parent=in.readUTF();s.name=in.readUTF();s.mime=in.readUTF();s.size=in.readLong();s.chunks=in.readLong();s.modified=in.readLong();s.complete=in.readBoolean();
            if(s.complete){s.digest=new byte[32];in.readFully(s.digest);}
            if(in.read()!=-1||!id.equals(s.id)||s.size<0||s.modified<0||s.chunks!=s.size/CHUNK+(s.size%CHUNK==0?0:1))throw new IOException("Registro de retomada inválido.");
            s.offset();return s;
        }finally{Arrays.fill(plain,(byte)0);}
    }
    /** Reopen source at byte zero. Only authenticated complete chunks are resumed; the original is never removed here. */
    public Entry importFileResumable(InputStream source,String name,String mime,String parent,String transferId,Progress progress)throws Exception{
        validateTransferId(transferId);byte[] active=session();validateMime(mime);
        String normalizedMime=mime==null?"application/octet-stream":mime;
        Entry committed=null;for(Entry e:list())if(e.id.equals(transferId)){committed=e;break;}
        if(committed!=null){
            if(committed.folder||committed.isTrashed()||!committed.parent.equals(parent)||!committed.name.equals(name==null?null:name.trim())||!committed.mime.equals(normalizedMime))throw new IOException("A transferência não corresponde ao arquivo existente.");
            verify(committed.id,progress);if(!matches(committed.id,source,progress))throw new IOException("O original mudou; nenhuma cópia foi removida.");
            checkSession(active);discardPendingImport(transferId);return committed;
        }
        validateDestination(parent,name,null);
        File directory=transferDirectory(),checkpoint=new File(directory,transferId+".state"),partial=new File(directory,transferId+".part"),target=content(transferId);
        ImportState state;
        if(checkpoint.exists()){
            state=readImport(checkpoint,transferId);
            if(!state.parent.equals(parent)||!state.name.equals(name.trim())||!state.mime.equals(normalizedMime))throw new IOException("A origem ou o destino da transferência mudou.");
        }else{
            if(partial.exists()||target.exists())throw new IOException("Há dados de uma transferência anterior. Eles foram preservados.");
            state=new ImportState();state.id=transferId;state.parent=parent;state.name=name.trim();state.mime=normalizedMime;state.modified=System.currentTimeMillis();saveImport(checkpoint,state);
        }
        if(target.exists()&&(!state.complete||partial.exists()))throw new IOException("Destino de retomada inconsistente; dados preservados.");
        File data=target.exists()?target:partial;
        if(!data.exists()){
            if(state.size!=0||state.complete)throw new IOException("Parte da transferência está ausente; original preservado.");
            try(RandomAccessFile out=new RandomAccessFile(data,"rw")){out.writeInt(0x414D4631);out.getFD().sync();}syncDirectory(directory);
        }
        byte[] key=fileKey(transferId),buffer=new byte[CHUNK];MessageDigest digest=MessageDigest.getInstance("SHA-256");
        RandomAccessFile out=null;boolean validated=false;
        try{
            out=new RandomAccessFile(data,"rw");
            if(out.length()<state.offset()||out.readInt()!=0x414D4631)throw new IOException("Transferência parcial danificada; dados preservados.");
            if(progress!=null)progress.phase("Conferindo transferência para retomar");
            long checked=0;
            for(long block=0;block<state.chunks;block++){
                checkSession(active);int count=(int)Math.min(CHUNK,state.size-checked);
                if(out.readInt()!=count)throw new IOException("Bloco parcial inválido.");byte[] iv=new byte[12],encrypted=new byte[count+16];out.readFully(iv);out.readFully(encrypted);
                byte[] plain=crypt(Cipher.DECRYPT_MODE,key,iv,aad(transferId,block,count),encrypted);
                try{int got=0;while(got<count){int n=source.read(buffer,got,count-got);if(n<0)break;if(n==0){int one=source.read();if(one<0)break;buffer[got++]=(byte)one;}else got+=n;}
                    if(got!=count||!MessageDigest.isEqual(plain,Arrays.copyOf(buffer,count)))throw new IOException("O original mudou desde a pausa. Nenhum arquivo foi removido.");
                    digest.update(plain);checked+=count;if(progress!=null)progress.update(checked);
                }finally{Arrays.fill(plain,(byte)0);Arrays.fill(buffer,(byte)0);}
            }
            if(state.complete){
                if(source.read()!=-1||!MessageDigest.isEqual(digest.digest(),state.digest))throw new IOException("O original mudou desde a pausa.");
                out.close();out=null;readContent(data,state.entry(),null,progress);
            }else{
                // Do not truncate anything until both the encrypted checkpoint and all saved source bytes authenticate.
                if(state.size%CHUNK!=0&&source.read()!=-1)throw new IOException("O tamanho do original mudou desde a pausa.");
                validated=true;out.setLength(state.offset());out.seek(state.offset());long nextCheckpoint=state.size+8L*CHUNK;
                while(state.size%CHUNK==0){
                    checkSession(active);int count=readChunk(source,buffer);if(count==0)break;
                    if(root.getUsableSpace()<34L*CHUNK)throw new IOException("Espaço insuficiente; original preservado.");
                    byte[] plain=Arrays.copyOf(buffer,count),iv=random(12),encrypted;
                    try{encrypted=crypt(Cipher.ENCRYPT_MODE,key,iv,aad(transferId,state.chunks,count),plain);}finally{Arrays.fill(plain,(byte)0);}
                    out.writeInt(count);out.write(iv);out.write(encrypted);digest.update(buffer,0,count);state.size+=count;state.chunks++;
                    if(state.size>=nextCheckpoint){out.getFD().sync();saveImport(checkpoint,state);nextCheckpoint=state.size+8L*CHUNK;}
                    if(progress!=null)progress.update(state.size);
                }
                byte[] iv=random(12);out.writeInt(0);out.write(iv);out.write(crypt(Cipher.ENCRYPT_MODE,key,iv,aad(transferId,state.chunks,0),new byte[0]));out.getFD().sync();
                state.digest=digest.digest();state.complete=true;saveImport(checkpoint,state);validated=false;out.close();out=null;
                if(progress!=null)progress.phase("Verificando arquivo protegido");readContent(data,state.entry(),null,progress);
            }
            checkSession(active);if(!data.equals(target))promoteNew(data,target);
            synchronized(this){checkSession(active);validateDestination(parent,name,null);List<Entry> next=list();next.add(state.entry());commit(next);}
            discardPendingImport(transferId);return state.entry();
        }catch(Exception failure){
            // A failed checkpoint leaves the older checkpoint intact. Any uncommitted tail is verified/truncated on the next attempt.
            if(validated&&out!=null)try{checkSession(active);out.getFD().sync();saveImport(checkpoint,state);}catch(Exception checkpointFailure){failure.addSuppressed(checkpointFailure);}
            throw failure;
        }finally{if(out!=null)out.close();Arrays.fill(key,(byte)0);Arrays.fill(buffer,(byte)0);}
    }
    /** Explicit cancellation removes only this authenticated transfer's staging; never an indexed or orphan .bin file. */
    public void discardPendingImport(String transferId)throws Exception{
        requireUnlocked();validateTransferId(transferId);File directory=new File(root,"transfers"),checkpoint=new File(directory,transferId+".state"),partial=new File(directory,transferId+".part");
        if(checkpoint.exists()){readImport(checkpoint,transferId);Files.deleteIfExists(partial.toPath());Files.deleteIfExists(checkpoint.toPath());syncDirectory(directory);}
    }
    public void exportFile(String id,OutputStream output,Progress progress) throws Exception {
        Entry entry=get(id); if(entry.folder) throw new IOException("Escolha um arquivo.");
        readContent(content(id),entry,output,progress);
    }
    /** Independent, bounded reader; every requested block is authenticated before release. */
    public synchronized RandomReader openRandomAccess(String id,java.util.function.BooleanSupplier allowed)throws Exception{
        Entry entry=get(id);if(entry.folder)throw new IOException("Escolha um arquivo.");byte[] active=master;
        return new RandomReader(content(id),entry,fileKey(id),()->sameSession(active)&&allowed.getAsBoolean());
    }
    private synchronized boolean sameSession(byte[] expected){return master!=null&&master==expected;}
    public static final class RandomReader implements Closeable {
        private final RandomAccessFile file;private final Entry entry;private final byte[] key;
        private final java.util.function.BooleanSupplier allowed;
        private byte[] cached;private long cachedIndex=-1;private boolean closed;
        RandomReader(File source,Entry entry,byte[] key,java.util.function.BooleanSupplier allowed)throws Exception{
            this.entry=entry;this.key=key;this.allowed=allowed;
            RandomAccessFile opened=null;
            try{
                opened=new RandomAccessFile(source,"r");file=opened;
                long chunks=entry.size/CHUNK+(entry.size%CHUNK==0?0:1);
                long expected=Math.addExact(4,Math.addExact(entry.size,Math.multiplyExact(32,chunks+1)));
                if(file.length()!=expected||file.readInt()!=0x414D4631)throw new IOException("Arquivo criptografado inválido.");
                file.seek(expected-32);if(file.readInt()!=0)throw new IOException("Final do arquivo inválido.");
                byte[] iv=new byte[12],tag=new byte[16];file.readFully(iv);file.readFully(tag);
                crypt(Cipher.DECRYPT_MODE,key,iv,aad(entry.id,chunks,0),tag);
            }catch(Exception e){Arrays.fill(key,(byte)0);if(opened!=null)opened.close();throw e;}
        }
        public long size(){return entry.size;}
        public synchronized int readAt(long position,byte[] buffer,int offset,int length)throws IOException{
            check();if(position<0||offset<0||length<0||offset>buffer.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;if(position>=entry.size)return -1;
            int wanted=(int)Math.min((long)length,entry.size-position),copied=0;
            try{
                while(copied<wanted){
                    check();long block=position/CHUNK;int inside=(int)(position%CHUNK);
                    if(cachedIndex!=block){
                        wipe();file.seek(Math.addExact(4,Math.multiplyExact(block,(long)CHUNK+32)));
                        int count=(int)Math.min((long)CHUNK,entry.size-block*CHUNK);
                        if(file.readInt()!=count)throw new IOException("Bloco do arquivo inválido.");
                        byte[] iv=new byte[12],encrypted=new byte[count+16];file.readFully(iv);file.readFully(encrypted);
                        cached=crypt(Cipher.DECRYPT_MODE,key,iv,aad(entry.id,block,count),encrypted);cachedIndex=block;
                    }
                    check();int count=Math.min(wanted-copied,cached.length-inside);
                    System.arraycopy(cached,inside,buffer,offset+copied,count);copied+=count;position+=count;
                }
                return copied;
            }catch(Exception e){Arrays.fill(buffer,offset,offset+copied,(byte)0);wipe();if(e instanceof IOException)throw (IOException)e;throw new IOException("Não foi possível autenticar o trecho do arquivo.",e);}
        }
        private void check()throws IOException{if(closed||!allowed.getAsBoolean())throw new IOException("Leitura encerrada.");}
        private void wipe(){if(cached!=null)Arrays.fill(cached,(byte)0);cached=null;cachedIndex=-1;}
        public InputStream stream(){return new InputStream(){long position;public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int off,int len)throws IOException{int n=readAt(position,b,off,len);if(n>0)position+=n;return n;}public long skip(long n){long skip=Math.max(0,Math.min(n,entry.size-position));position+=skip;return skip;}public void close()throws IOException{RandomReader.this.close();}};}
        @Override public synchronized void close()throws IOException{if(closed)return;closed=true;wipe();Arrays.fill(key,(byte)0);file.close();}
    }
    public void verify(String id) throws Exception { verify(id,null); }
    public void verify(String id,Progress progress) throws Exception { Entry entry=get(id);if(entry.folder)throw new IOException("Escolha um arquivo.");readContent(content(id),entry,null,progress); }
    public boolean matches(String id,InputStream input)throws Exception{return matches(id,input,null);}
    public boolean matches(String id,InputStream input,Progress progress) throws Exception {
        byte[] active=session();Entry e=get(id);if(progress!=null)progress.phase("Conferindo cópia · "+e.name); MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[CHUNK]; long size=0;
        try { int n;checkSession(active);while((n=input.read(b))!=-1) {checkSession(active);sha.update(b,0,n); size+=n;if(progress!=null)progress.update(size);checkSession(active);}checkSession(active); }
        finally { Arrays.fill(b,(byte)0); }
        return size==e.size && MessageDigest.isEqual(sha.digest(),e.digest);
    }
    private void readContent(File file,Entry entry,OutputStream output,Progress progress) throws Exception {
        byte[] active=session(),key=fileKey(entry.id); MessageDigest sha=MessageDigest.getInstance("SHA-256"); long total=0,index=0;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if(file.length()!=encryptedSize(entry.size)||in.readInt()!=0x414D4631) throw new IOException("Arquivo criptografado inválido.");
            while(true) {
                checkSession(active);
                int count=in.readInt(); if(count!=(int)Math.min(CHUNK,entry.size-total)) throw new IOException("Arquivo danificado.");
                byte[] iv=new byte[12], encrypted=new byte[count+16]; in.readFully(iv); in.readFully(encrypted);
                byte[] plain=crypt(Cipher.DECRYPT_MODE,key,iv,aad(entry.id,index++,count),encrypted);
                try {
                    checkSession(active);
                    if(count==0) { if(total!=entry.size || in.read()!=-1 || !MessageDigest.isEqual(sha.digest(),entry.digest)) throw new IOException("Verificação de integridade falhou."); break; }
                    sha.update(plain); if(output!=null) output.write(plain); total+=count;
                    if(progress!=null) progress.update(total);
                } finally { Arrays.fill(plain,(byte)0); }
            }
        } finally { Arrays.fill(key,(byte)0); }
    }
    public void delete(String id) throws Exception {
        Set<String> removed=new HashSet<>();for(Entry e:activeSubtree(id))removed.add(e.id);removeIds(removed);
    }
    public static long encryptedSize(long bytes){if(bytes<0)throw new IllegalArgumentException();long chunks=bytes/CHUNK+(bytes%CHUNK==0?0:1);return Math.addExact(4,Math.addExact(bytes,Math.multiplyExact(32,chunks+1)));}
    public void backup(OutputStream target)throws Exception{backup(target,null);}
    public void backup(OutputStream target,Progress progress) throws Exception {
        requireUnlocked();
        for(Entry e:entries) if(!e.folder){if(progress!=null)progress.phase("Verificando para backup · "+e.name);readContent(content(e.id),e,null,progress);}
        try(ZipOutputStream zip=new ZipOutputStream(target)) {
            zip.setLevel(0); // Encrypted data is incompressible.
            if(progress!=null)progress.phase("Gravando backup criptografado");long copied=0;
            for(File file:backupFiles()) {
                zip.putNextEntry(new ZipEntry(file.getName()));
                long base=copied;try(InputStream in=new FileInputStream(file)) { copy(in,zip,progress==null?null:n->progress.update(base+n)); }copied+=file.length();
                zip.closeEntry();
            }
        }
    }
    public void verifyBackup(InputStream source)throws Exception{verifyBackup(source,null);}
    public void verifyBackup(InputStream source,Progress progress) throws Exception {
        requireUnlocked();if(progress!=null)progress.phase("Conferindo backup e cofre");long processed=0; Map<String,File> expected=new HashMap<>();
        for(File f:backupFiles()) expected.put(f.getName(),f);
        CompleteZipSource complete=new CompleteZipSource(source);
        try(ZipInputStream zip=new ZipInputStream(complete)) {
            ZipEntry e; while((e=zip.getNextEntry())!=null) {
                File original=expected.remove(e.getName()); if(original==null) throw new IOException("Backup inválido.");
                MessageDigest actual=MessageDigest.getInstance("SHA-256"), wanted=MessageDigest.getInstance("SHA-256");
                byte[] buffer=new byte[CHUNK]; int n;
                while((n=zip.read(buffer))!=-1){actual.update(buffer,0,n);processed+=n;if(progress!=null)progress.update(processed);}
                try(InputStream in=new FileInputStream(original)) { while((n=in.read(buffer))!=-1){wanted.update(buffer,0,n);processed+=n;if(progress!=null)progress.update(processed);} }
                if(!MessageDigest.isEqual(actual.digest(),wanted.digest())) throw new IOException("O backup não foi gravado corretamente.");
            }
            complete.finish();
        }
        if(!expected.isEmpty()) throw new IOException("Backup incompleto.");
    }
    /** Restore into an empty vault only. The current vault can never be overwritten by this operation. */
    public void restore(InputStream source,char[] password) throws Exception {
        restoreInternal(source,password,null,null);
    }
    public void restoreUsingRecovery(InputStream source,String code,char[] newPassword) throws Exception {
        if(newPassword.length<10)throw new IOException("Use pelo menos 10 caracteres na nova senha.");restoreInternal(source,newPassword,code,null);
    }
    public void restore(InputStream source,char[] password,Progress progress)throws Exception{restoreInternal(source,password,null,progress);}
    public void restoreUsingRecovery(InputStream source,String code,char[] newPassword,Progress progress)throws Exception{if(newPassword.length<10)throw new IOException("Use pelo menos 10 caracteres na nova senha.");restoreInternal(source,newPassword,code,progress);}
    private void restoreInternal(InputStream source,char[] password,String recovery,Progress progress) throws Exception {
        requireEmptyRoot();
        File staged=new File(root.getParentFile(),"restore-"+UUID.randomUUID());
        if(!staged.mkdirs()) throw new IOException("Não foi possível preparar a restauração.");
        try {
            if(progress!=null)progress.phase("Lendo backup para restauração");Set<String> names=new HashSet<>(); long total=0,limit=Math.max(0,staged.getUsableSpace()-32L*1024*1024);
            CompleteZipSource complete=new CompleteZipSource(source);
            try(ZipInputStream zip=new ZipInputStream(complete)) {
                ZipEntry item; byte[] b=new byte[CHUNK];
                while((item=zip.getNextEntry())!=null) {
                    String n=item.getName();
                    if(item.isDirectory() || !(n.equals(CONFIG)||n.equals(INDEX)||n.equals(RECOVERY)||n.matches(UUID_PATTERN+"\\.bin")) || !names.add(n) || names.size()>100002)
                        throw new IOException("Estrutura de backup inválida.");
                    try(FileOutputStream out=new FileOutputStream(new File(staged,n))) {
                        int count; while((count=zip.read(b))!=-1) { total+=count; if(total>limit) throw new IOException("Espaço insuficiente para restaurar."); out.write(b,0,count);if(progress!=null)progress.update(total); }
                        out.getFD().sync();
                    }
                }
                complete.finish();
            }
            if(!names.contains(CONFIG)||!names.contains(INDEX)) throw new IOException("Backup incompleto.");
            VaultEngine check=new VaultEngine(staged);
            try { if(recovery==null)check.unlock(password);else check.unlockWithRecovery(recovery);Set<String> referenced=new HashSet<>();for(File f:check.currentBackupFiles())referenced.add(f.getName());if(!referenced.equals(names))throw new IOException("O backup contém arquivos fora do índice autenticado.");for(Entry e:check.list()) if(!e.folder){if(progress!=null)progress.phase("Verificando restauração · "+e.name);check.readContent(check.content(e.id),e,null,progress);}if(recovery!=null)check.writeConfig(password); }
            finally { check.lock(); }
            if(progress!=null)progress.update(total);
            if(root.exists()) { File[] files=root.listFiles(); if(files==null||files.length>0) throw new IOException("O destino não está vazio."); if(!root.delete()) throw new IOException("Destino indisponível."); }
            promoteNew(staged,root); unlock(password);if(recovery==null)markBackupCompleted();
        } finally { removeTree(staged); }
    }
    /** Only current index references; never scan the gallery, export destinations or orphaned blobs. Trash is still inside the vault. */
    private List<File> backupFiles() {return currentBackupFiles();}
    synchronized List<File> currentBackupFiles() {
        requireUnlocked();
        List<File> files=new ArrayList<>(); files.add(new File(root,CONFIG)); files.add(new File(root,INDEX));
        if(hasRecovery())files.add(new File(root,RECOVERY));
        for(Entry e:entries) if(!e.folder) files.add(content(e.id)); return files;
    }
    private void validateDestination(String parent,String name,String except) throws IOException {
        requireUnlocked();
        if(parent==null||name==null||name.trim().isEmpty()||name.length()>200||name.matches(".*[\\\\/\\p{Cntrl}].*")||name.trim().equals(".")||name.trim().equals("..")) throw new IOException("Use um nome válido, de até 200 caracteres.");
        if(!parent.isEmpty()&&(!get(parent).folder||get(parent).isTrashed())) throw new IOException("Pasta de destino inválida.");
        for(Entry e:entries) if(!e.isTrashed()&&e.parent.equals(parent)&&e.name.equalsIgnoreCase(name.trim())&&!e.id.equals(except)) throw new IOException("Já existe um item com esse nome nesta pasta.");
    }
    private void validateEntries(List<Entry> items) throws IOException {
        if(items.size()>99998)throw new IOException("Limite de itens do cofre atingido.");
        Map<String,Entry> byId=new HashMap<>(); Set<String> names=new HashSet<>();
        for(Entry e:items) {
            if(!e.id.matches(UUID_PATTERN) || byId.put(e.id,e)!=null || e.size<0 || e.modified<0 || e.name.isEmpty() || e.name.length()>200 || e.name.matches(".*[\\\\/\\p{Cntrl}].*") || e.name.equals(".") || e.name.equals("..")) throw new IOException("Índice inválido.");
            validateMime(e.mime);try{encryptedSize(e.size);}catch(ArithmeticException error){throw new IOException("Tamanho inválido no índice.",error);}
            if(e.folder&&e.size!=0)throw new IOException("Pasta com tamanho inválido no índice.");
            if(e.trashedAt<0||e.isTrashed()!=!e.trashRoot.isEmpty())throw new IOException("Lixeira inválida.");
            if(!names.add(e.trashRoot+"/"+e.parent+"/"+e.name.toLowerCase(Locale.ROOT))) throw new IOException("Já existe um item com esse nome na pasta de destino.");
        }
        for(Entry e:items) {
            if(e.isTrashed()){Entry group=byId.get(e.trashRoot);if(group==null||!group.isTrashed()||!group.trashRoot.equals(group.id))throw new IOException("Lixeira inválida.");}
            if(!e.isTrashed()&&!e.parent.isEmpty()&&byId.containsKey(e.parent)&&byId.get(e.parent).isTrashed())throw new IOException("Pasta de destino na lixeira.");
            Set<String> ancestors=new HashSet<>(); ancestors.add(e.id); String cursor=e.parent;
            while(!cursor.isEmpty()) { Entry p=byId.get(cursor); if(p==null||!p.folder||!ancestors.add(cursor)) throw new IOException("Pastas inválidas."); cursor=p.parent; }
        }
    }
    private void commit(List<Entry> next) throws Exception {
        validateEntries(next);try{saveIndex(next);entries=next;}catch(Exception failure){lock();throw failure;}
    }
    private void saveIndex(List<Entry> data) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(2); out.writeInt(data.size());
        for(Entry e:data) { out.writeUTF(e.id); out.writeUTF(e.parent); out.writeUTF(e.name); out.writeUTF(e.mime); out.writeBoolean(e.folder); out.writeLong(e.size); out.writeLong(e.modified); out.write(e.digest);out.writeLong(e.trashedAt);out.writeUTF(e.trashRoot); }
        byte[] iv=random(12), plain=bytes.toByteArray();
        try { byte[] ciphertext=crypt(Cipher.ENCRYPT_MODE,master,iv,"AMZ/index/1",plain); ByteArrayOutputStream result=new ByteArrayOutputStream(); result.write(iv); result.write(ciphertext); atomicBytes(new File(root,INDEX),result.toByteArray()); }
        finally { Arrays.fill(plain,(byte)0); }
    }
    private List<Entry> readIndex() throws Exception {
        File f=new File(root,INDEX); if(f.length()<28||f.length()>64L*1024*1024) throw new IOException("Índice inválido.");
        byte[] all=Files.readAllBytes(f.toPath()), plain=crypt(Cipher.DECRYPT_MODE,master,Arrays.copyOf(all,12),"AMZ/index/1",Arrays.copyOfRange(all,12,all.length));
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))) {
            int version=in.readInt();if(version!=1&&version!=2) throw new IOException("Atualize o aplicativo para abrir este cofre.");
            int count=in.readInt(); if(count<0||count>99998) throw new IOException("Índice inválido.");
            List<Entry> result=new ArrayList<>();
            for(int i=0;i<count;i++) { String id=in.readUTF(),parent=in.readUTF(),name=in.readUTF(),mime=in.readUTF(); boolean folder=in.readBoolean(); long size=in.readLong(),modified=in.readLong(); byte[] digest=new byte[32]; in.readFully(digest);long trashedAt=version>=2?in.readLong():0;String trashRoot=version>=2?in.readUTF():"";result.add(new Entry(id,parent,name,mime,folder,size,modified,digest,trashedAt,trashRoot)); }
            if(in.read()!=-1) throw new IOException("Índice inválido."); return result;
        } finally { Arrays.fill(plain,(byte)0); }
    }
    private void requireEmptyRoot() throws IOException {
        if(root.exists()){File[] files=root.listFiles();if(!root.isDirectory()||files==null||files.length!=0)throw new IOException("O destino contém dados. Use uma pasta vazia para preservar o cofre existente.");}
    }
    private static void validateMime(String mime)throws IOException {
        if(mime!=null&&(mime.length()>255||mime.matches(".*[\\p{Cntrl}].*")))throw new IOException("Tipo de arquivo inválido.");
    }
    private byte[] fileKey(String id) throws Exception { requireUnlocked(); Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(master,"HmacSHA256")); return mac.doFinal(("AMZ/file/1/"+id).getBytes(StandardCharsets.UTF_8)); }
    private File content(String id) { return new File(root,id+".bin"); }
    private void requireUnlocked() { if(master==null) throw new IllegalStateException("Desbloqueie o cofre para continuar."); }
    static byte[] random(int n) { byte[] b=new byte[n]; RANDOM.nextBytes(b); return b; }
    private static String aad(String id,long index,int length) { return "AMZ/chunk/1/"+id+"/"+index+"/"+length; }
    private static byte[] derive(char[] password,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] crypt(int mode,byte[] key,byte[] iv,String aad,byte[] data) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(data);
    }
    static void writeAtomicState(File target,byte[] data)throws IOException{atomicBytes(target,data);}
    private static void atomicBytes(File target,byte[] data) throws IOException {
        File temp=new File(target.getParentFile(),target.getName()+"."+UUID.randomUUID()+".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(data); out.getFD().sync(); }
            if(!MessageDigest.isEqual(data,Files.readAllBytes(temp.toPath())))throw new IOException("Falha na conferência da gravação; versão anterior preservada.");
            atomicMove(temp,target);
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    private static void atomicMove(File source,File target) throws IOException {
        // A filesystem without atomic replacement must fail before risking the last valid index/key.
        Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParentFile());
    }
    private static void promoteNew(File source,File target)throws IOException{
        if(target.exists())throw new IOException("O destino já contém dados e foi preservado.");
        Files.move(source.toPath(),target.toPath());syncDirectory(target.getParentFile());
    }
    private static void syncDirectory(File directory)throws IOException{
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}
        catch(AccessDeniedException e){if(!System.getProperty("os.name","").startsWith("Windows"))throw e;}
        catch(UnsupportedOperationException e){/* Provider has no directory sync API; file data is already fsynced. */}
    }
    /** ZipInputStream stops before the central directory. Drain the producer and require the ZIP end record before committing. */
    private static final class CompleteZipSource extends FilterInputStream {
        private final byte[] tail=new byte[65557];private long total;
        CompleteZipSource(InputStream input){super(input);}
        private void remember(byte[] b,int off,int len){int keep=Math.min(len,tail.length),start=(int)((total+len-keep)%tail.length),first=Math.min(keep,tail.length-start);System.arraycopy(b,off+len-keep,tail,start,first);System.arraycopy(b,off+len-keep+first,tail,0,keep-first);total+=len;}
        @Override public int read()throws IOException{int value=in.read();if(value>=0){tail[(int)(total%tail.length)]=(byte)value;total++;}return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int count=in.read(b,off,len);if(count>0)remember(b,off,count);return count;}
        void finish()throws IOException{
            byte[] buffer=new byte[8192];int n;while((n=read(buffer))!=-1){if(n==0&&read()==-1)break;}
            int length=(int)Math.min(total,tail.length);byte[] end=new byte[length];for(int i=0;i<length;i++)end[i]=tail[(int)((total-length+i)%tail.length)];
            for(int i=length-22;i>=0;i--)if(u32(end,i)==0x06054b50L&&i+22+u16(end,i+20)==length){
                if(u16(end,i+4)!=0||u16(end,i+6)!=0||u16(end,i+8)!=u16(end,i+10))break;
                long size=u32(end,i+12),offset=u32(end,i+16),position=total-length+i;
                if(size!=0xffffffffL&&offset!=0xffffffffL&&offset+size!=position&&!(u16(end,i+10)==65535&&i>=76&&u32(end,i-76)==0x06064b50L&&u32(end,i-20)==0x07064b50L&&offset+size+76==position))break;
                return;
            }
            throw new IOException("Backup incompleto ou final do arquivo inválido.");
        }
        private static int u16(byte[] b,int i){return (b[i]&255)|((b[i+1]&255)<<8);}
        private static long u32(byte[] b,int i){return ((long)u16(b,i))|((long)u16(b,i+2)<<16);}
    }
    static void removeTree(File dir) { File[] children=dir.listFiles(); if(children!=null) for(File f:children) removeTree(f); if(dir.exists()) dir.delete(); }
    private static int readChunk(InputStream in,byte[] b) throws IOException { int filled=0; while(filled<b.length) { int n=in.read(b,filled,b.length-filled); if(n==-1) break; if(n==0) { int one=in.read(); if(one==-1) break; b[filled++]=(byte)one; } else filled+=n; } return filled; }
    static void copy(InputStream in,OutputStream out,Progress progress) throws IOException { byte[] b=new byte[CHUNK]; int n; long total=0; while((n=in.read(b))!=-1) { out.write(b,0,n); total+=n; if(progress!=null) progress.update(total); } }
}
