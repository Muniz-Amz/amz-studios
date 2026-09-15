package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real encrypted files and a failing removable-storage provider; no mock cryptography. */
public class VaultBackupSetTest {
    private File temp, root, backup;
    private VaultEngine vault;
    private VaultBackupSet.FolderStore store;
    private final char[] password = "senha de teste backup 2026".toCharArray();
    @Before public void setup() throws Exception {
        temp=Files.createTempDirectory("amz-backup-set-").toFile();
        root=new File(temp,"vault"); backup=new File(temp,"backup");
        vault=new VaultEngine(root); vault.create(password); store=new VaultBackupSet.FolderStore(backup);
    }
    @After public void cleanup() { vault.lock(); VaultEngine.removeTree(temp); }
    private byte[] data(int length) { byte[] b=new byte[length]; new Random(529+length).nextBytes(b); return b; }
    private VaultEngine.Entry add(String name,byte[] bytes) throws Exception {
        return vault.importFile(new ByteArrayInputStream(bytes),name,"application/octet-stream","",null);
    }
    private VaultBackupSet.Result save() throws Exception { return VaultBackupSet.backup(vault,store,null); }
    private VaultEngine target() { return new VaultEngine(new File(temp,"restored-"+UUID.randomUUID())); }
    private byte[] export(VaultEngine engine,String id) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); engine.exportFile(id,bytes,null); return bytes.toByteArray();
    }
    private void checkRestore(String snapshot,String id,byte[] bytes) throws Exception {
        VaultEngine restored=target();
        try { VaultBackupSet.restoreSnapshot(restored,store,snapshot,password,null); assertArrayEquals(bytes,export(restored,id)); }
        finally { restored.lock(); }
    }
    private String object() throws Exception {
        for(String name:store.list()) if(name.startsWith("o-")) return name;
        throw new AssertionError("No objects");
    }
    private String largestObject() throws Exception {
        String best=null; long size=-1;
        for(String name:store.list()) if(name.startsWith("o-")&&new File(backup,name).length()>size) { best=name;size=new File(backup,name).length(); }
        return best;
    }
    private String snapshot(String commit) { return "s-"+commit.substring(2,commit.length()-5)+".amzs"; }
    private abstract static class DelegatingStore implements VaultBackupSet.Store {
        final VaultBackupSet.Store base;
        DelegatingStore(VaultBackupSet.Store base) { this.base=base; }
        public List<String> list() throws IOException { return base.list(); }
        public InputStream read(String name) throws IOException { return base.read(name); }
        public OutputStream create(String name) throws IOException { return base.create(name); }
    }
    private void rejectLatest(VaultBackupSet.Store source) throws Exception {
        VaultEngine restored=target();
        assertThrows(Exception.class,()->VaultBackupSet.restoreLatest(restored,source,password,null));
        assertFalse(restored.exists()); assertFalse(restored.isUnlocked());
    }

    @Test public void unchangedBackupReusesAllChunksAndRoundTripsMultipleChunksAndEmptyFile() throws Exception {
        byte[] bytes=data(VaultBackupSet.OBJECT_BYTES*2+79);
        VaultEngine.Entry file=add("video reservado.mp4",bytes),empty=add("vazio.txt",new byte[0]);
        VaultBackupSet.Result first=save(),second=save();
        assertTrue(first.copiedBytes>bytes.length); assertEquals(0,first.reusedBytes);
        assertEquals(0,second.copiedBytes); assertEquals(first.copiedBytes,second.reusedBytes);
        assertEquals(2,VaultBackupSet.snapshots(store).size());
        checkRestore(second.snapshotName,file.id,bytes); checkRestore(second.snapshotName,empty.id,new byte[0]);
        for(String name:store.list()) if(name.startsWith("o-")) assertTrue(new File(backup,name).length()<=VaultBackupSet.OBJECT_BYTES);
    }

    @Test public void latestSnapshotExcludesRemovedFilesButExplicitOlderSnapshotRemainsRestorable() throws Exception {
        byte[] original=data(1037); VaultEngine.Entry removed=add("retirado.jpg",original);
        String first=save().snapshotName; vault.delete(removed.id);
        VaultEngine.Entry current=add("dentro.txt",new byte[]{8,9});
        save(); VaultEngine restored=target();
        try { VaultBackupSet.restoreLatest(restored,store,password,null); assertEquals(1,restored.list().size());
            assertEquals(current.id,restored.list().get(0).id); assertThrows(IOException.class,()->restored.get(removed.id)); }
        finally { restored.lock(); }
        checkRestore(first,removed.id,original);
    }

    @Test public void snapshotKeepsEncryptedTrashAndExcludesOrphansAndAuxiliaryState() throws Exception {
        VaultEngine.Entry item=add("foto.jpg",new byte[]{2,3}); vault.trash(Collections.singleton(item.id));
        String orphan=UUID.randomUUID()+".bin";
        Files.write(new File(root,orphan).toPath(),new byte[]{7});
        Files.write(new File(root,"playback.state").toPath(),new byte[]{8});
        Files.write(new File(root,"transfer.state").toPath(),new byte[]{9});
        save(); VaultEngine restored=target();
        try { VaultBackupSet.restoreLatest(restored,store,password,null); assertTrue(restored.get(item.id).isTrashed());
            assertFalse(new File(restored.privateDirectory(),orphan).exists());
            assertFalse(new File(restored.privateDirectory(),"playback.state").exists());
            assertFalse(new File(restored.privateDirectory(),"transfer.state").exists()); }
        finally { restored.lock(); }
    }

    @Test public void foreignVaultCannotAppendToOrChangeBackupSet() throws Exception {
        save(); Map<String,byte[]> before=contents(); VaultEngine foreign=new VaultEngine(new File(temp,"foreign"));
        try { foreign.create(password); assertThrows(IOException.class,()->VaultBackupSet.backup(foreign,store,null)); assertContents(before); }
        finally { foreign.lock(); }
    }

    @Test public void passwordChangeKeepsObjectsReusableAndEachSnapshotRequiresItsOwnPassword() throws Exception {
        VaultEngine.Entry item=add("video.mp4",data(1999)); String old=save().snapshotName;
        char[] next="nova senha backup 2026".toCharArray(); vault.changePassword(next);
        VaultBackupSet.Result latest=save(); assertTrue(latest.reusedBytes>1999); assertEquals(84,latest.copiedBytes);
        checkRestore(old,item.id,data(1999)); rejectLatest(store);
        VaultEngine restored=target();
        try { VaultBackupSet.restoreLatest(restored,store,next,null); assertArrayEquals(data(1999),export(restored,item.id)); }
        finally { restored.lock(); }
    }

    @Test public void offlineRecoveryRestoresWithoutOriginalPasswordAndRejectsWrongCode() throws Exception {
        String code=vault.createRecoveryKey(); VaultEngine.Entry item=add("a.txt",new byte[]{6}); save();
        VaultEngine wrong=target();
        String bad=code.substring(0,code.length()-1)+(code.endsWith("A")?"B":"A");
        char[] next="recuperada nova senha".toCharArray();
        assertThrows(Exception.class,()->VaultBackupSet.restoreLatestUsingRecovery(wrong,store,bad,next,null)); assertFalse(wrong.exists());
        VaultEngine restored=target();
        try { VaultBackupSet.restoreLatestUsingRecovery(restored,store,code,next,null); assertArrayEquals(new byte[]{6},export(restored,item.id));
            restored.lock(); assertThrows(Exception.class,()->restored.unlock(password)); restored.unlock(next); }
        finally { restored.lock(); }
    }

    @Test public void ciphertextAndMetadataAreNotWrittenAsPlaintext() throws Exception {
        String secret="Segredo unicamente dentro do cofre 2026";
        add("Nome privado inconfundivel.doc",secret.getBytes("UTF-8")); save();
        for(String name:store.list()) {
            String raw=new String(Files.readAllBytes(new File(backup,name).toPath()),"ISO-8859-1");
            assertFalse(raw.contains(secret)); assertFalse(raw.contains("Nome privado")); assertFalse(raw.contains(new String(password)));
        }
    }

    @Test public void truncatedAppendedFlippedAndMissingObjectAreRejectedWithoutCreatingVault() throws Exception {
        add("arquivo.bin",data(1500)); save(); File object=new File(backup,largestObject()); byte[] good=Files.readAllBytes(object.toPath());
        Files.write(object.toPath(),Arrays.copyOf(good,good.length-1)); rejectLatest(store);
        Files.write(object.toPath(),Arrays.copyOf(good,good.length+1)); rejectLatest(store);
        byte[] bad=good.clone(); bad[bad.length/2]^=1; Files.write(object.toPath(),bad); rejectLatest(store);
        Files.delete(object.toPath()); rejectLatest(store);
    }

    @Test public void changedObjectDuringSecondRestoreReadCannotBePromoted() throws Exception {
        add("arquivo.bin",data(2100)); save(); String object=largestObject();
        VaultBackupSet.Store changing=new DelegatingStore(store) {
            int reads;
            @Override public InputStream read(String name) throws IOException {
                if(name.equals(object)&&++reads>=2) { byte[] bytes=Files.readAllBytes(new File(backup,name).toPath()); bytes[bytes.length-1]^=8; return new ByteArrayInputStream(bytes); }
                return super.read(name);
            }
        };
        rejectLatest(changing);
    }

    @Test public void swappedCiphertextObjectsFailEvenWhenNamesAndSizesArePlausible() throws Exception {
        add("one.bin",data(3001)); add("two.bin",data(3002)); save();
        List<File> files=new ArrayList<>();
        for(String name:store.list()) if(name.startsWith("o-")&&new File(backup,name).length()>3000) files.add(new File(backup,name));
        assertEquals(2,files.size()); byte[] a=Files.readAllBytes(files.get(0).toPath()),b=Files.readAllBytes(files.get(1).toPath());
        Files.write(files.get(0).toPath(),b); Files.write(files.get(1).toPath(),a); rejectLatest(store);
    }

    @Test public void incompleteNewestCommitNeverSilentlyRestoresAnOlderSnapshot() throws Exception {
        VaultEngine.Entry item=add("old.txt",new byte[]{1}); String first=save().snapshotName; vault.rename(item.id,"new.txt"); String latest=save().snapshotName;
        File commit=new File(backup,latest); byte[] good=Files.readAllBytes(commit.toPath());
        Files.write(commit.toPath(),Arrays.copyOf(good,good.length-1));
        assertFalse(VaultBackupSet.snapshots(store).get(0).complete); rejectLatest(store); checkRestore(first,item.id,new byte[]{1});
    }

    @Test public void corruptManifestWithRecomputedPublicCommitHashStillFailsAuthentication() throws Exception {
        add("secret.txt",data(233)); String latest=save().snapshotName;
        File snapshot=new File(backup,snapshot(latest)); byte[] bytes=Files.readAllBytes(snapshot.toPath()); bytes[bytes.length-1]^=1;
        Files.write(snapshot.toPath(),bytes); rewriteCommit(latest,bytes); rejectLatest(store);
    }

    @Test public void snapshotSubstitutionBetweenVaultsCannotAuthenticateUnderOriginalPassword() throws Exception {
        add("one.txt",new byte[]{4}); String latest=save().snapshotName;
        VaultEngine other=new VaultEngine(new File(temp,"foreign")); File foreignDir=new File(temp,"foreign-backup");
        try {
            other.create(password); VaultBackupSet.FolderStore foreignStore=new VaultBackupSet.FolderStore(foreignDir);
            String foreign=VaultBackupSet.backup(other,foreignStore,null).snapshotName;
            byte[] bytes=Files.readAllBytes(new File(foreignDir,snapshot(foreign)).toPath());
            Files.write(new File(backup,snapshot(latest)).toPath(),bytes); rewriteCommit(latest,bytes); rejectLatest(store);
        } finally { other.lock(); }
    }

    @Test public void existingAndDamagedNonemptyDestinationArePreserved() throws Exception {
        add("source.txt",data(20)); save();
        VaultEngine existing=target(); existing.create(password); byte[] prior=Files.readAllBytes(new File(existing.privateDirectory(),VaultEngine.INDEX).toPath());
        try { assertThrows(IOException.class,()->VaultBackupSet.restoreLatest(existing,store,password,null));
            assertArrayEquals(prior,Files.readAllBytes(new File(existing.privateDirectory(),VaultEngine.INDEX).toPath())); }
        finally { existing.lock(); }
        VaultEngine damaged=target(); assertTrue(damaged.privateDirectory().mkdirs());
        File evidence=new File(damaged.privateDirectory(),"only-copy.bin"); Files.write(evidence.toPath(),new byte[]{4,8});
        assertThrows(IOException.class,()->VaultBackupSet.restoreLatest(damaged,store,password,null)); assertArrayEquals(new byte[]{4,8},Files.readAllBytes(evidence.toPath()));
    }

    @Test public void corruptVaultSourceCannotPublishSnapshot() throws Exception {
        VaultEngine.Entry item=add("video.bin",data(310)); String first=save().snapshotName;
        File source=new File(root,item.id+".bin"); byte[] bytes=Files.readAllBytes(source.toPath()); bytes[44]^=4; Files.write(source.toPath(),bytes);
        assertThrows(Exception.class,()->save()); assertEquals(1,VaultBackupSet.snapshots(store).size()); checkRestore(first,item.id,data(310));
    }

    @Test public void sourceIndexChangingBeforeCommitIsRejected() throws Exception {
        VaultEngine.Entry item=add("before.txt",new byte[]{2});
        VaultEngine.Progress mutate=new VaultEngine.Progress() {
            public void update(long ignored) {}
            public void phase(String name) {
                if(name.equals("Confirmando nova versão do backup")) try { vault.rename(item.id,"after.txt"); } catch(Exception e) { throw new RuntimeException(e); }
            }
        };
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,store,mutate)); assertTrue(VaultBackupSet.snapshots(store).isEmpty());
    }

    @Test public void partialObjectFromFullDiskIsNeverReusedAndRetryPreservesCompletedObjects() throws Exception {
        byte[] bytes=data(VaultBackupSet.OBJECT_BYTES+11); VaultEngine.Entry item=add("video.mp4",bytes);
        final int[] writes={0};
        VaultBackupSet.Store fullDisk=new DelegatingStore(store) {
            @Override public OutputStream create(String name) throws IOException {
                OutputStream output=super.create(name);
                if(!name.startsWith("o-")) return output;
                return new FilterOutputStream(output) {
                    @Override public void write(byte[] b,int off,int len) throws IOException {
                        if(len>VaultEngine.CHUNK) throw new AssertionError("Unbounded write");
                        if(++writes[0]==4) { out.write(b,off,len/2); throw new IOException("simulated full disk"); }
                        out.write(b,off,len);
                    }
                };
            }
        };
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,fullDisk,null)); assertTrue(VaultBackupSet.snapshots(store).isEmpty());
        Map<String,byte[]> partial=contents(); VaultBackupSet.Result retry=save(); assertTrue(retry.reusedBytes>=84);
        for(Map.Entry<String,byte[]> old:partial.entrySet()) assertArrayEquals(old.getValue(),Files.readAllBytes(new File(backup,old.getKey()).toPath()));
        checkRestore(retry.snapshotName,item.id,bytes);
    }

    @Test public void pauseAndProcessRestartReuseDurableChunksWithoutSavingPassword() throws Exception {
        byte[] bytes=data(VaultBackupSet.OBJECT_BYTES+1024); VaultEngine.Entry item=add("video.mp4",bytes);
        final boolean[] objectCreated={false};
        VaultBackupSet.Store tracking=new DelegatingStore(store) {
            @Override public OutputStream create(String name) throws IOException {
                OutputStream out=super.create(name); if(name.startsWith("o-")&&name.length()>80) objectCreated[0]=true; return out;
            }
        };
        VaultEngine.Progress pause=count->{if(objectCreated[0]&&count>1024*1024) throw new VaultEngine.TransferPausedException();};
        assertThrows(VaultEngine.TransferPausedException.class,()->VaultBackupSet.backup(vault,tracking,pause));
        vault.lock(); vault=new VaultEngine(root); vault.unlock(password);
        VaultBackupSet.Result resumed=save(); assertTrue(resumed.reusedBytes>=84); checkRestore(resumed.snapshotName,item.id,bytes);
    }

    @Test public void providerReadbackCorruptionPreventsCommitAndRetryUsesNewImmutableObject() throws Exception {
        VaultEngine.Entry item=add("document.bin",data(1079));
        VaultBackupSet.Store corrupting=new DelegatingStore(store) {
            @Override public OutputStream create(String name) throws IOException {
                OutputStream output=super.create(name); if(!name.startsWith("o-")) return output;
                return new FilterOutputStream(output) {
                    @Override public void close() throws IOException { super.close(); File f=new File(backup,name); byte[] b=Files.readAllBytes(f.toPath()); b[b.length-1]^=1; Files.write(f.toPath(),b); }
                };
            }
        };
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,corrupting,null)); assertTrue(VaultBackupSet.snapshots(store).isEmpty());
        Map<String,byte[]> damaged=contents(); String good=save().snapshotName;
        for(Map.Entry<String,byte[]> old:damaged.entrySet()) assertArrayEquals(old.getValue(),Files.readAllBytes(new File(backup,old.getKey()).toPath()));
        checkRestore(good,item.id,data(1079));
    }

    @Test public void interruptedManifestPreservesPreviousCommitAndSuccessfulRetryReusesObjects() throws Exception {
        VaultEngine.Entry item=add("keep.txt",data(343)); String first=save().snapshotName; vault.rename(item.id,"updated.txt");
        VaultBackupSet.Store failing=failingOutput("s-");
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,failing,null));
        assertEquals(1,VaultBackupSet.snapshots(store).size()); assertEquals(1,VaultBackupSet.incompleteSnapshots(store)); checkRestore(first,item.id,data(343));
        VaultBackupSet.Result retry=save(); assertEquals(0,retry.copiedBytes); assertTrue(retry.reusedBytes>0); checkRestore(retry.snapshotName,item.id,data(343));
    }

    @Test public void interruptedCommitRequiresExplicitOlderRestoreAndRetryPublishesNewerVersion() throws Exception {
        VaultEngine.Entry item=add("keep.txt",new byte[]{5}); String first=save().snapshotName; vault.rename(item.id,"changed.txt");
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,failingOutput("c-"),null)); rejectLatest(store); checkRestore(first,item.id,new byte[]{5});
        String retry=save().snapshotName; assertTrue(VaultBackupSet.snapshots(store).get(0).complete); checkRestore(retry,item.id,new byte[]{5});
    }

    @Test public void closeFlushFailureIsNotReportedAsSuccessfulBackup() throws Exception {
        add("source.txt",data(378));
        VaultBackupSet.Store failure=new DelegatingStore(store) {
            @Override public OutputStream create(String name) throws IOException {
                OutputStream output=super.create(name); if(!name.startsWith("o-")) return output;
                return new FilterOutputStream(output) { @Override public void close() throws IOException { super.close(); throw new IOException("flush failed"); } };
            }
        };
        assertThrows(IOException.class,()->VaultBackupSet.backup(vault,failure,null)); assertTrue(VaultBackupSet.snapshots(store).isEmpty());
        assertTrue(save().reusedBytes>0);
    }

    @Test public void folderStoreNeverOverwritesOrEscapesDirectory() throws Exception {
        try(OutputStream out=store.create("reserved.amzo")) { out.write(5); }
        assertThrows(IOException.class,()->store.create("reserved.amzo"));
        for(String bad:Arrays.asList("..",".","../escape","sub/file","sub\\file","C:escape")) assertThrows(IOException.class,()->store.create(bad));
        assertArrayEquals(new byte[]{5},Files.readAllBytes(new File(backup,"reserved.amzo").toPath()));
    }

    @Test public void authenticatedMalformedManifestFieldsRejectBeforeDestinationCreation() throws Exception {
        add("small.txt",new byte[]{9}); String latest=save().snapshotName;
        byte[] snapshot=Files.readAllBytes(new File(backup,snapshot(latest)).toPath());
        byte[] plain=manifestPlain(snapshot);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain)); in.readInt(); in.readUTF();
        int countOffset=plain.length-in.available(); in.readInt(); in.readUTF();
        int firstSizeOffset=plain.length-in.available(); in.readLong(); in.skipBytes(32);
        int firstChunksOffset=plain.length-in.available();
        List<byte[]> mutations=new ArrayList<>();
        byte[] bad=plain.clone(); putInt(bad,countOffset,100003); mutations.add(bad);
        bad=plain.clone(); putInt(bad,countOffset,-1); mutations.add(bad);
        bad=plain.clone(); putInt(bad,firstChunksOffset,Integer.MAX_VALUE); mutations.add(bad);
        bad=plain.clone(); Arrays.fill(bad,firstSizeOffset,firstSizeOffset+8,(byte)255); mutations.add(bad);
        mutations.add(Arrays.copyOf(plain,plain.length-1)); mutations.add(Arrays.copyOf(plain,plain.length+1));
        for(byte[] malformed:mutations) { replaceManifest(latest,snapshot,malformed); rejectLatest(store); }
    }

    @Test public void targetedSnapshotBitFlipsCannotBypassAuthenticatedManifestWithRecomputedChecksums() throws Exception {
        vault.createRecoveryKey(); add("classified.txt",data(278)); String latest=save().snapshotName;
        byte[] good=Files.readAllBytes(new File(backup,snapshot(latest)).toPath());
        int[] positions={0,4,8,28,40,87,88,89,112,153,154,good.length-17,good.length-1};
        for(int position:positions) {
            byte[] bad=good.clone(); bad[position]^=1;
            Files.write(new File(backup,snapshot(latest)).toPath(),bad); rewriteCommit(latest,bad); rejectLatest(store);
        }
    }

    private byte[] manifestPlain(byte[] snapshot) throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(snapshot)); in.skipBytes(4+84);
        if(in.readBoolean()) in.skipBytes(64);
        int size=in.readInt(); byte[] sealed=new byte[size]; in.readFully(sealed);
        return vault.openState("backup-set-manifest",sealed);
    }
    private void replaceManifest(String name,byte[] snapshot,byte[] plain) throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(snapshot)); in.skipBytes(4+84);
        if(in.readBoolean()) in.skipBytes(64);
        int prefix=snapshot.length-in.available(); byte[] sealed=vault.sealState("backup-set-manifest",plain);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); bytes.write(snapshot,0,prefix);
        DataOutputStream out=new DataOutputStream(bytes); out.writeInt(sealed.length); out.write(sealed); out.flush();
        byte[] changed=bytes.toByteArray(); Files.write(new File(backup,snapshot(name)).toPath(),changed); rewriteCommit(name,changed);
    }
    private void putInt(byte[] bytes,int offset,int value) {
        for(int i=0;i<4;i++) bytes[offset+i]=(byte)(value>>>(24-8*i));
    }

    private VaultBackupSet.Store failingOutput(String prefix) {
        return new DelegatingStore(store) {
            @Override public OutputStream create(String name) throws IOException {
                OutputStream output=super.create(name); if(!name.startsWith(prefix)) return output;
                return new FilterOutputStream(output) {
                    @Override public void write(byte[] b,int off,int len) throws IOException { out.write(b,off,Math.max(1,len/2)); throw new IOException("simulated interruption"); }
                };
            }
        };
    }
    private Map<String,byte[]> contents() throws Exception {
        Map<String,byte[]> files=new TreeMap<>(); for(String name:store.list()) files.put(name,Files.readAllBytes(new File(backup,name).toPath())); return files;
    }
    private void assertContents(Map<String,byte[]> expected) throws Exception {
        assertEquals(expected.keySet(),new TreeSet<>(store.list()));
        for(Map.Entry<String,byte[]> file:expected.entrySet()) assertArrayEquals(file.getValue(),Files.readAllBytes(new File(backup,file.getKey()).toPath()));
    }
    private void rewriteCommit(String name,byte[] snapshot) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x41424331); out.writeUTF(name.substring(2,name.length()-5)); out.writeInt(snapshot.length);
        out.write(MessageDigest.getInstance("SHA-256").digest(snapshot)); out.flush();
        out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())); out.flush();
        Files.write(new File(backup,name).toPath(),bytes.toByteArray());
    }
}
