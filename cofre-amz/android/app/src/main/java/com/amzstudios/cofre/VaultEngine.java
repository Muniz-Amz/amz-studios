package com.amzstudios.cofre;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned, offline vault format. No Android dependency; exercised on JVM and device. */
public final class VaultEngine {
    static final int ITERATIONS = 600000, CHUNK = 1024 * 1024;
    static final String CONFIG = "vault.key", INDEX = "index.enc", RECOVERY = "recovery.key", BACKUP_STATE = "backup.state";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File root;
    private byte[] master;
    private List<Entry> entries = new ArrayList<>();
    public interface Progress { void update(long bytes); }
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
    public boolean isUnlocked() { return master!=null; }
    public boolean hasRecovery(){return new File(root,RECOVERY).isFile();}
    public List<Entry> list() { requireUnlocked(); return new ArrayList<>(entries); }
    public Entry get(String id) throws IOException {
        requireUnlocked();
        for (Entry e:entries) if(e.id.equals(id)) return e;
        throw new IOException("Arquivo não encontrado no cofre.");
    }
    public void create(char[] password) throws Exception {
        if(exists()) throw new IOException("Já existe um cofre neste aparelho.");
        if(password.length<10) throw new IOException("Use uma senha com pelo menos 10 caracteres.");
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("Não foi possível criar o cofre.");
        master=random(32); entries=new ArrayList<>();
        try { saveIndex(entries); writeConfig(password); }
        catch(Exception e) { lock(); throw e; }
    }
    public void unlock(char[] password) throws Exception {
        lock();
        try(DataInputStream in=new DataInputStream(new FileInputStream(new File(root,CONFIG)))) {
            if(in.readInt()!=0x414D5A31 || in.readInt()!=ITERATIONS) throw new IOException("Formato de cofre não suportado.");
            byte[] salt=new byte[16], iv=new byte[12], wrapped=new byte[48];
            in.readFully(salt); in.readFully(iv); in.readFully(wrapped);
            if(in.read()!=-1) throw new IOException("Cabeçalho inválido.");
            byte[] key=derive(password,salt);
            try { master=crypt(Cipher.DECRYPT_MODE,key,iv,"AMZ/key/1",wrapped); }
            finally { Arrays.fill(key,(byte)0); }
            entries=readIndex();
            validateEntries(entries);
            for(Entry e:entries) if(!e.folder && !content(e.id).isFile()) throw new IOException("Arquivo ausente. Restaure um backup íntegro.");
            cleanupOrphans();
        } catch(Exception e) { lock(); throw e; }
    }
    public void lock() {
        if(master!=null) Arrays.fill(master,(byte)0);
        master=null; entries=new ArrayList<>();
    }
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
    private void unlockWithRecovery(String code) throws Exception {
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
    public long storedBytes(){long size=0;File[] files=root.listFiles();if(files!=null)for(File f:files)if(f.isFile())size+=f.length();return size;}
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
        requireUnlocked(); validateDestination(parent,name,null);
        String id=UUID.randomUUID().toString(); File temp=new File(root,id+".part"), target=content(id);
        byte[] key=fileKey(id), buffer=new byte[CHUNK]; MessageDigest sha=MessageDigest.getInstance("SHA-256");
        long total=0, index=0;
        try {
            try(FileOutputStream stream=new FileOutputStream(temp); DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream))) {
                out.writeInt(0x414D4631);
                while(true) {
                    int count=readChunk(source,buffer); if(count==0) break;
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
            readContent(temp,entry,null,null);
            atomicMove(temp,target);
            List<Entry> next=list(); next.add(entry); commit(next);
            return entry;
        } finally { Arrays.fill(key,(byte)0); Arrays.fill(buffer,(byte)0); if(temp.exists()) temp.delete(); }
    }
    public void exportFile(String id,OutputStream output,Progress progress) throws Exception {
        Entry entry=get(id); if(entry.folder) throw new IOException("Escolha um arquivo.");
        readContent(content(id),entry,output,progress);
    }
    public void verify(String id) throws Exception { Entry entry=get(id); readContent(content(id),entry,null,null); }
    public boolean matches(String id,InputStream input) throws Exception {
        Entry e=get(id); MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[CHUNK]; long size=0;
        try { int n; while((n=input.read(b))!=-1) { sha.update(b,0,n); size+=n; } }
        finally { Arrays.fill(b,(byte)0); }
        return size==e.size && MessageDigest.isEqual(sha.digest(),e.digest);
    }
    private void readContent(File file,Entry entry,OutputStream output,Progress progress) throws Exception {
        byte[] key=fileKey(entry.id); MessageDigest sha=MessageDigest.getInstance("SHA-256"); long total=0,index=0;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if(in.readInt()!=0x414D4631) throw new IOException("Arquivo criptografado inválido.");
            while(true) {
                int count=in.readInt(); if(count<0 || count>CHUNK || total+count>entry.size) throw new IOException("Arquivo danificado.");
                byte[] iv=new byte[12], encrypted=new byte[count+16]; in.readFully(iv); in.readFully(encrypted);
                byte[] plain=crypt(Cipher.DECRYPT_MODE,key,iv,aad(entry.id,index++,count),encrypted);
                try {
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
    public void backup(OutputStream target) throws Exception {
        requireUnlocked();
        for(Entry e:entries) if(!e.folder) verify(e.id);
        try(ZipOutputStream zip=new ZipOutputStream(target)) {
            zip.setLevel(0); // Encrypted data is incompressible.
            for(File file:backupFiles()) {
                zip.putNextEntry(new ZipEntry(file.getName()));
                try(InputStream in=new FileInputStream(file)) { copy(in,zip,null); }
                zip.closeEntry();
            }
        }
    }
    public void verifyBackup(InputStream source) throws Exception {
        requireUnlocked(); Map<String,File> expected=new HashMap<>();
        for(File f:backupFiles()) expected.put(f.getName(),f);
        try(ZipInputStream zip=new ZipInputStream(source)) {
            ZipEntry e; while((e=zip.getNextEntry())!=null) {
                File original=expected.remove(e.getName()); if(original==null) throw new IOException("Backup inválido.");
                MessageDigest actual=MessageDigest.getInstance("SHA-256"), wanted=MessageDigest.getInstance("SHA-256");
                byte[] buffer=new byte[CHUNK]; int n;
                while((n=zip.read(buffer))!=-1) actual.update(buffer,0,n);
                try(InputStream in=new FileInputStream(original)) { while((n=in.read(buffer))!=-1) wanted.update(buffer,0,n); }
                if(!MessageDigest.isEqual(actual.digest(),wanted.digest())) throw new IOException("O backup não foi gravado corretamente.");
            }
        }
        if(!expected.isEmpty()) throw new IOException("Backup incompleto.");
    }
    /** Restore into an empty vault only. The current vault can never be overwritten by this operation. */
    public void restore(InputStream source,char[] password) throws Exception {
        restoreInternal(source,password,null);
    }
    public void restoreUsingRecovery(InputStream source,String code,char[] newPassword) throws Exception {
        if(newPassword.length<10)throw new IOException("Use pelo menos 10 caracteres na nova senha.");restoreInternal(source,newPassword,code);
    }
    private void restoreInternal(InputStream source,char[] password,String recovery) throws Exception {
        if(exists()) throw new IOException("Restaure em uma instalação sem cofre. O cofre atual será preservado.");
        File staged=new File(root.getParentFile(),"restore-"+UUID.randomUUID());
        if(!staged.mkdirs()) throw new IOException("Não foi possível preparar a restauração.");
        try {
            Set<String> names=new HashSet<>(); long total=0,limit=Math.max(0,staged.getUsableSpace()-32L*1024*1024);
            try(ZipInputStream zip=new ZipInputStream(source)) {
                ZipEntry item; byte[] b=new byte[CHUNK];
                while((item=zip.getNextEntry())!=null) {
                    String n=item.getName();
                    if(item.isDirectory() || !(n.equals(CONFIG)||n.equals(INDEX)||n.equals(RECOVERY)||n.matches("[a-f0-9-]{36}\\.bin")) || !names.add(n) || names.size()>100002)
                        throw new IOException("Estrutura de backup inválida.");
                    try(FileOutputStream out=new FileOutputStream(new File(staged,n))) {
                        int count; while((count=zip.read(b))!=-1) { total+=count; if(total>limit) throw new IOException("Espaço insuficiente para restaurar."); out.write(b,0,count); }
                        out.getFD().sync();
                    }
                }
            }
            if(!names.contains(CONFIG)||!names.contains(INDEX)) throw new IOException("Backup incompleto.");
            VaultEngine check=new VaultEngine(staged);
            try { if(recovery==null)check.unlock(password);else check.unlockWithRecovery(recovery); for(Entry e:check.list()) if(!e.folder) check.verify(e.id);if(recovery!=null)check.writeConfig(password); }
            finally { check.lock(); }
            if(root.exists()) { File[] files=root.listFiles(); if(files==null||files.length>0) throw new IOException("O destino não está vazio."); if(!root.delete()) throw new IOException("Destino indisponível."); }
            atomicMove(staged,root); unlock(password);if(recovery==null)markBackupCompleted();
        } finally { removeTree(staged); }
    }
    private List<File> backupFiles() {
        List<File> files=new ArrayList<>(); files.add(new File(root,CONFIG)); files.add(new File(root,INDEX));
        if(hasRecovery())files.add(new File(root,RECOVERY));
        for(Entry e:entries) if(!e.folder) files.add(content(e.id)); return files;
    }
    private void validateDestination(String parent,String name,String except) throws IOException {
        requireUnlocked();
        if(name==null||name.trim().isEmpty()||name.length()>200||name.matches(".*[\\\\/\\p{Cntrl}].*")||name.equals(".")||name.equals("..")) throw new IOException("Use um nome válido, de até 200 caracteres.");
        if(!parent.isEmpty()&&(!get(parent).folder||get(parent).isTrashed())) throw new IOException("Pasta de destino inválida.");
        for(Entry e:entries) if(!e.isTrashed()&&e.parent.equals(parent)&&e.name.equalsIgnoreCase(name.trim())&&!e.id.equals(except)) throw new IOException("Já existe um item com esse nome nesta pasta.");
    }
    private void validateEntries(List<Entry> items) throws IOException {
        Map<String,Entry> byId=new HashMap<>(); Set<String> names=new HashSet<>();
        for(Entry e:items) {
            if(!e.id.matches("[a-f0-9-]{36}") || byId.put(e.id,e)!=null || e.size<0 || e.name.isEmpty() || e.name.length()>200 || e.name.matches(".*[\\\\/\\p{Cntrl}].*") || e.name.equals(".") || e.name.equals("..")) throw new IOException("Índice inválido.");
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
    private void commit(List<Entry> next) throws Exception { saveIndex(next); entries=next; }
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
    private void cleanupOrphans() {
        Set<String> used=new HashSet<>(); for(Entry e:entries) if(!e.folder) used.add(e.id+".bin");
        File[] files=root.listFiles(); if(files!=null) for(File f:files) if((f.getName().endsWith(".bin")&&!used.contains(f.getName()))||f.getName().endsWith(".part")) f.delete();
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
    private static void atomicBytes(File target,byte[] data) throws IOException {
        File temp=new File(target.getParentFile(),target.getName()+".part");
        try(FileOutputStream out=new FileOutputStream(temp)) { out.write(data); out.getFD().sync(); }
        atomicMove(temp,target);
    }
    private static void atomicMove(File source,File target) throws IOException {
        try { Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    }
    static void removeTree(File dir) { File[] children=dir.listFiles(); if(children!=null) for(File f:children) removeTree(f); if(dir.exists()) dir.delete(); }
    private static int readChunk(InputStream in,byte[] b) throws IOException { int filled=0; while(filled<b.length) { int n=in.read(b,filled,b.length-filled); if(n==-1) break; if(n==0) { int one=in.read(); if(one==-1) break; b[filled++]=(byte)one; } else filled+=n; } return filled; }
    static void copy(InputStream in,OutputStream out,Progress progress) throws IOException { byte[] b=new byte[CHUNK]; int n; long total=0; while((n=in.read(b))!=-1) { out.write(b,0,n); total+=n; if(progress!=null) progress.update(total); } }
}
