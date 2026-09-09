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
    static final String CONFIG = "vault.key", INDEX = "index.enc";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File root;
    private byte[] master;
    private List<Entry> entries = new ArrayList<>();
    public interface Progress { void update(long bytes); }
    public static final class Entry {
        public final String id, parent, name, mime;
        public final boolean folder;
        public final long size, modified;
        final byte[] digest;
        Entry(String id, String parent, String name, String mime, boolean folder, long size, long modified, byte[] digest) {
            this.id=id; this.parent=parent; this.name=name; this.mime=mime; this.folder=folder;
            this.size=size; this.modified=modified; this.digest=digest.clone();
        }
        Entry relocate(String parent, String name) { return new Entry(id,parent,name,mime,folder,size,modified,digest); }
    }
    public VaultEngine(File root) { this.root=root; }
    public boolean exists() { return new File(root,CONFIG).isFile(); }
    public boolean isUnlocked() { return master!=null; }
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
        Entry e=get(id); validateDestination(parent,name,id);
        String cursor=parent;
        while(!cursor.isEmpty()) { if(cursor.equals(id)) throw new IOException("Uma pasta não pode ficar dentro dela mesma."); cursor=get(cursor).parent; }
        List<Entry> next=list(); next.set(next.indexOf(e),e.relocate(parent,name.trim())); commit(next);
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
        get(id); Set<String> removed=new HashSet<>(); removed.add(id);
        boolean changed; do { changed=false; for(Entry e:entries) if(removed.contains(e.parent)) changed|=removed.add(e.id); } while(changed);
        List<Entry> next=list(); next.removeIf(e->removed.contains(e.id)); commit(next);
        for(String item:removed) content(item).delete();
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
        if(exists()) throw new IOException("Restaure em uma instalação sem cofre. O cofre atual será preservado.");
        File staged=new File(root.getParentFile(),"restore-"+UUID.randomUUID());
        if(!staged.mkdirs()) throw new IOException("Não foi possível preparar a restauração.");
        try {
            Set<String> names=new HashSet<>(); long total=0,limit=Math.max(0,staged.getUsableSpace()-32L*1024*1024);
            try(ZipInputStream zip=new ZipInputStream(source)) {
                ZipEntry item; byte[] b=new byte[CHUNK];
                while((item=zip.getNextEntry())!=null) {
                    String n=item.getName();
                    if(item.isDirectory() || !(n.equals(CONFIG)||n.equals(INDEX)||n.matches("[a-f0-9-]{36}\\.bin")) || !names.add(n) || names.size()>100000)
                        throw new IOException("Estrutura de backup inválida.");
                    try(FileOutputStream out=new FileOutputStream(new File(staged,n))) {
                        int count; while((count=zip.read(b))!=-1) { total+=count; if(total>limit) throw new IOException("Espaço insuficiente para restaurar."); out.write(b,0,count); }
                        out.getFD().sync();
                    }
                }
            }
            if(!names.contains(CONFIG)||!names.contains(INDEX)) throw new IOException("Backup incompleto.");
            VaultEngine check=new VaultEngine(staged);
            try { check.unlock(password); for(Entry e:check.list()) if(!e.folder) check.verify(e.id); }
            finally { check.lock(); }
            if(root.exists()) { File[] files=root.listFiles(); if(files==null||files.length>0) throw new IOException("O destino não está vazio."); if(!root.delete()) throw new IOException("Destino indisponível."); }
            atomicMove(staged,root); unlock(password);
        } finally { removeTree(staged); }
    }
    private List<File> backupFiles() {
        List<File> files=new ArrayList<>(); files.add(new File(root,CONFIG)); files.add(new File(root,INDEX));
        for(Entry e:entries) if(!e.folder) files.add(content(e.id)); return files;
    }
    private void validateDestination(String parent,String name,String except) throws IOException {
        requireUnlocked();
        if(name==null||name.trim().isEmpty()||name.length()>200||name.matches(".*[\\\\/\\p{Cntrl}].*")||name.equals(".")||name.equals("..")) throw new IOException("Use um nome válido, de até 200 caracteres.");
        if(!parent.isEmpty()&&!get(parent).folder) throw new IOException("Pasta de destino inválida.");
        for(Entry e:entries) if(e.parent.equals(parent)&&e.name.equalsIgnoreCase(name.trim())&&!e.id.equals(except)) throw new IOException("Já existe um item com esse nome nesta pasta.");
    }
    private void validateEntries(List<Entry> items) throws IOException {
        Map<String,Entry> byId=new HashMap<>(); Set<String> names=new HashSet<>();
        for(Entry e:items) {
            if(!e.id.matches("[a-f0-9-]{36}") || byId.put(e.id,e)!=null || e.size<0 || e.name.isEmpty() || e.name.length()>200 || e.name.matches(".*[\\\\/\\p{Cntrl}].*") || e.name.equals(".") || e.name.equals("..")) throw new IOException("Índice inválido.");
            if(!names.add(e.parent+"/"+e.name.toLowerCase(Locale.ROOT))) throw new IOException("Nomes duplicados no índice.");
        }
        for(Entry e:items) {
            Set<String> ancestors=new HashSet<>(); ancestors.add(e.id); String cursor=e.parent;
            while(!cursor.isEmpty()) { Entry p=byId.get(cursor); if(p==null||!p.folder||!ancestors.add(cursor)) throw new IOException("Pastas inválidas."); cursor=p.parent; }
        }
    }
    private void commit(List<Entry> next) throws Exception { saveIndex(next); entries=next; }
    private void saveIndex(List<Entry> data) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(1); out.writeInt(data.size());
        for(Entry e:data) { out.writeUTF(e.id); out.writeUTF(e.parent); out.writeUTF(e.name); out.writeUTF(e.mime); out.writeBoolean(e.folder); out.writeLong(e.size); out.writeLong(e.modified); out.write(e.digest); }
        byte[] iv=random(12), plain=bytes.toByteArray();
        try { byte[] ciphertext=crypt(Cipher.ENCRYPT_MODE,master,iv,"AMZ/index/1",plain); ByteArrayOutputStream result=new ByteArrayOutputStream(); result.write(iv); result.write(ciphertext); atomicBytes(new File(root,INDEX),result.toByteArray()); }
        finally { Arrays.fill(plain,(byte)0); }
    }
    private List<Entry> readIndex() throws Exception {
        File f=new File(root,INDEX); if(f.length()<28||f.length()>64L*1024*1024) throw new IOException("Índice inválido.");
        byte[] all=Files.readAllBytes(f.toPath()), plain=crypt(Cipher.DECRYPT_MODE,master,Arrays.copyOf(all,12),"AMZ/index/1",Arrays.copyOfRange(all,12,all.length));
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))) {
            if(in.readInt()!=1) throw new IOException("Atualize o aplicativo para abrir este cofre.");
            int count=in.readInt(); if(count<0||count>99998) throw new IOException("Índice inválido.");
            List<Entry> result=new ArrayList<>();
            for(int i=0;i<count;i++) { String id=in.readUTF(),parent=in.readUTF(),name=in.readUTF(),mime=in.readUTF(); boolean folder=in.readBoolean(); long size=in.readLong(),modified=in.readLong(); byte[] digest=new byte[32]; in.readFully(digest); result.add(new Entry(id,parent,name,mime,folder,size,modified,digest)); }
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
