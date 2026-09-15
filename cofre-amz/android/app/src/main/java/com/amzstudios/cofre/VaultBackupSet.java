package com.amzstudios.cofre;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Immutable, resumable encrypted snapshots. The Store must never overwrite an existing name. */
public final class VaultBackupSet {
    public interface Store {
        List<String> list() throws IOException;
        InputStream read(String name) throws IOException;
        /** Create a new file with exactly this name, rejecting an existing name. Close must flush it. */
        OutputStream create(String name) throws IOException;
    }

    public static final int OBJECT_BYTES = 4 * 1024 * 1024;
    private static final int BUFFER_BYTES = 1024 * 1024, MAX_MANIFEST = 32 * 1024 * 1024;
    private static final int MAX_FILES = 100002, MAX_OBJECTS = 1000000;
    private static final String IDENTITY = "cofre-amz-set.amzi", PURPOSE = "backup-set-manifest";
    private static final byte[] IDENTITY_TEXT = "Cofre AMZ backup set version 1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private VaultBackupSet() {}

    /** Small filesystem implementation for desktop tooling and the same core's JVM tests. */
    public static final class FolderStore implements Store {
        private final File root;
        public FolderStore(File root) throws IOException {
            this.root = root;
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Pasta de backup indisponível.");
        }
        public List<String> list() throws IOException {
            File[] files = root.listFiles();
            if (files == null) throw new IOException("Não foi possível listar o backup.");
            List<String> names = new ArrayList<>();
            for (File f : files) if (f.isFile()) names.add(f.getName());
            return names;
        }
        public InputStream read(String name) throws IOException { return new FileInputStream(file(name)); }
        public OutputStream create(String name) throws IOException {
            File f = file(name);
            if (!f.createNewFile()) throw new IOException("O arquivo de backup já existe.");
            return new FileOutputStream(f) {
                @Override public void close() throws IOException {
                    try { flush(); getFD().sync(); } finally { super.close(); }
                }
            };
        }
        private File file(String name) throws IOException {
            if (name == null || !name.matches("[a-zA-Z0-9.-]{1,160}") || name.equals(".") || name.equals(".."))
                throw new IOException("Nome de backup inválido.");
            File file = new File(root, name);
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Atalhos não podem armazenar blocos do backup.");
            return file;
        }
    }

    public static final class Result {
        public final String snapshotName;
        public final long copiedBytes, reusedBytes;
        public final int files;
        Result(String name, long copied, long reused, int files) {
            snapshotName = name; copiedBytes = copied; reusedBytes = reused; this.files = files;
        }
    }
    public static final class SnapshotInfo {
        /** Commit name, to pass to restoreSnapshot. No private filename is exposed. */
        public final String name;
        public final long createdAt;
        /** Structural completeness only; authentication and all content are checked on restore. */
        public final boolean complete;
        SnapshotInfo(String name, boolean complete) {
            this.name = name; this.complete = complete;
            createdAt = Long.parseLong(name.substring(2, 15));
        }
    }
    private static final class ObjectRef {
        final byte[] hash, nonce;
        final int size;
        ObjectRef(byte[] hash, byte[] nonce, int size) { this.hash = hash; this.nonce = nonce; this.size = size; }
        String name() { return "o-" + hex(hash) + "-" + hex(nonce) + ".amzo"; }
    }
    private static final class FileRecord {
        String name;
        long size;
        byte[] hash;
        final List<ObjectRef> objects = new ArrayList<>();
    }
    private static final class Manifest {
        String id;
        byte[] config, recovery;
        final List<FileRecord> files = new ArrayList<>();
    }
    private static final class Commit {
        String id;
        int size;
        byte[] hash;
        String snapshotName() { return "s-" + id + ".amzs"; }
    }

    /** Caller serializes vault mutations. Partial objects are reusable only after full digest verification. */
    public static Result backup(VaultEngine vault, Store store, VaultEngine.Progress progress) throws Exception {
        List<String> names = store.list();
        ensureIdentity(vault, store, names);
        Map<String, List<String>> candidates = new HashMap<>();
        for (String name : names) if (isObjectName(name))
            candidates.computeIfAbsent(name.substring(2, 66), ignored -> new ArrayList<>()).add(name);
        List<File> sourceFiles = vault.currentBackupFiles();
        if (sourceFiles.size() < 2 || sourceFiles.size() > MAX_FILES) throw invalid();
        // Refuse an oversized snapshot before copying any content. Names are fixed ASCII
        // vault filenames, so this is the exact serialized manifest size for a 50-char id.
        long encodedSize = 60, plannedObjects = 0;
        for (File source : sourceFiles) {
            if (!source.isFile() || !validVaultName(source.getName())) throw invalid();
            long size = source.length(), chunks = size / OBJECT_BYTES + (size % OBJECT_BYTES == 0 ? 0 : 1);
            plannedObjects += chunks;
            if (plannedObjects > MAX_OBJECTS) throw new IOException("Esta versão excede o limite de blocos do backup.");
            encodedSize += 46L + source.getName().length() + chunks * 52L;
            if (encodedSize > MAX_MANIFEST) throw new IOException("O índice desta versão é grande demais para o backup.");
        }
        Manifest manifest = new Manifest();
        manifest.id = nextId(names);
        long copied = 0, reused = 0;
        int objectCount = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        for (File source : sourceFiles) {
            FileRecord record = new FileRecord();
            record.name = source.getName();
            if (!validVaultName(record.name)) throw invalid();
            record.size = source.length();
            MessageDigest whole = digest();
            phase(progress, "Conferindo e atualizando backup criptografado");
            try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
                long offset = 0;
                while (offset < record.size) {
                    if (++objectCount > MAX_OBJECTS) throw new IOException("Esta versão excede o limite de blocos do backup.");
                    int size = (int) Math.min(OBJECT_BYTES, record.size - offset);
                    MessageDigest part = digest();
                    input.seek(offset);
                    for (int remaining = size; remaining > 0;) {
                        int n = Math.min(buffer.length, remaining);
                        input.readFully(buffer, 0, n); part.update(buffer, 0, n); whole.update(buffer, 0, n);
                        remaining -= n; update(progress, offset + size - remaining);
                    }
                    byte[] wanted = part.digest();
                    ObjectRef object = null;
                    List<String> matching = candidates.get(hex(wanted));
                    if (matching != null) for (String candidate : matching) {
                        if (objectMatches(store, candidate, size, wanted, progress)) {
                            object = new ObjectRef(wanted, unhex(candidate.substring(67, 99)), size); break;
                        }
                    }
                    if (object == null) {
                        byte[] nonce = new byte[16]; RANDOM.nextBytes(nonce);
                        object = new ObjectRef(wanted, nonce, size);
                        input.seek(offset);
                        try (OutputStream output = store.create(object.name())) {
                            for (int remaining = size; remaining > 0;) {
                                int n = Math.min(buffer.length, remaining);
                                input.readFully(buffer, 0, n); output.write(buffer, 0, n);
                                remaining -= n; update(progress, copied + size - remaining);
                            }
                        }
                        if (!objectMatches(store, object.name(), size, wanted, progress))
                            throw new IOException("O destino não gravou o bloco corretamente. O cofre foi preservado.");
                        copied += size;
                        candidates.computeIfAbsent(hex(wanted), ignored -> new ArrayList<>()).add(object.name());
                    } else reused += size;
                    record.objects.add(object); offset += size;
                }
                if (input.length() != record.size) throw changed();
            }
            record.hash = whole.digest();
            // Authenticate source ciphertext as well as checking the destination's exact bytes.
            if (record.name.endsWith(".bin")) vault.verify(record.name.substring(0, 36), progress);
            if (!fileMatches(source, record, buffer, progress)) throw changed();
            manifest.files.add(record);
            if (record.name.equals(VaultEngine.CONFIG)) manifest.config = Files.readAllBytes(source.toPath());
            if (record.name.equals(VaultEngine.RECOVERY)) manifest.recovery = Files.readAllBytes(source.toPath());
        }
        assertCurrent(vault, manifest, buffer, progress);
        byte[] encoded = encodeManifest(manifest);
        byte[] sealed;
        try { sealed = vault.sealState(PURPOSE, encoded); } finally { Arrays.fill(encoded, (byte) 0); }
        byte[] snapshot = encodeSnapshot(manifest, sealed);
        Commit commit = new Commit(); commit.id = manifest.id; commit.hash = hash(snapshot); commit.size = snapshot.length;
        phase(progress, "Confirmando nova versão do backup");
        write(store, commit.snapshotName(), snapshot);
        if (!MessageDigest.isEqual(hash(readBounded(store, commit.snapshotName(), MAX_MANIFEST + 1024)), commit.hash))
            throw new IOException("A versão do backup não passou na conferência.");
        // Final publication occurs only after source authentication and all object read-backs.
        assertCurrent(vault, manifest, buffer, progress);
        String commitName = "c-" + commit.id + ".amzc";
        byte[] commitBytes = encodeCommit(commit);
        write(store, commitName, commitBytes);
        if (!MessageDigest.isEqual(commitBytes, readBounded(store, commitName, 256))) throw invalid();
        update(progress, copied);
        return new Result(commitName, copied, reused, manifest.files.size());
    }

    public static List<SnapshotInfo> snapshots(Store store) throws IOException {
        List<SnapshotInfo> result = new ArrayList<>();
        for (String name : store.list()) if (isCommitName(name)) {
            boolean complete;
            try { readCommit(store, name); complete = true; } catch (IOException e) { complete = false; }
            result.add(new SnapshotInfo(name, complete));
        }
        result.sort((a, b) -> b.name.compareTo(a.name));
        return result;
    }
    public static int incompleteSnapshots(Store store) throws IOException {
        List<String> names = store.list();
        Set<String> complete = new HashSet<>();
        for (SnapshotInfo snapshot : snapshots(store)) if (snapshot.complete)
            complete.add("s-" + snapshot.name.substring(2, snapshot.name.length() - 5) + ".amzs");
        int count = 0;
        for (String name : names) if (isSnapshotName(name) && !complete.contains(name)) count++;
        return count;
    }
    public static void restoreLatest(VaultEngine target, Store store, char[] password, VaultEngine.Progress progress) throws Exception {
        restoreSnapshot(target, store, latest(store), password, progress);
    }
    public static void restoreLatestUsingRecovery(VaultEngine target, Store store, String code, char[] newPassword, VaultEngine.Progress progress) throws Exception {
        restoreSnapshotUsingRecovery(target, store, latest(store), code, newPassword, progress);
    }
    public static void restoreSnapshot(VaultEngine target, Store store, String name, char[] password, VaultEngine.Progress progress) throws Exception {
        restoreInternal(target, store, name, password, null, progress);
    }
    public static void restoreSnapshotUsingRecovery(VaultEngine target, Store store, String name, String code, char[] newPassword, VaultEngine.Progress progress) throws Exception {
        restoreInternal(target, store, name, newPassword, code, progress);
    }
    private static String latest(Store store) throws IOException {
        List<SnapshotInfo> snapshots = snapshots(store);
        if (snapshots.isEmpty()) throw new IOException("Não há uma versão concluída neste backup.");
        if (!snapshots.get(0).complete) throw new IOException("A última versão do backup está incompleta. Escolha uma versão anterior explicitamente.");
        return snapshots.get(0).name;
    }
    private static void restoreInternal(VaultEngine target, Store store, String name, char[] password, String recovery, VaultEngine.Progress progress) throws Exception {
        if (target.exists()) throw new IOException("O cofre atual será preservado. Restaure em um destino vazio.");
        Commit commit = readCommit(store, name);
        byte[] snapshot = readBounded(store, commit.snapshotName(), MAX_MANIFEST + 1024);
        if (snapshot.length != commit.size || !MessageDigest.isEqual(hash(snapshot), commit.hash)) throw invalid();
        Manifest manifest = decodeSnapshot(snapshot, commit.id, password, recovery);
        phase(progress, "Conferindo os blocos da versão escolhida");
        // This first pass guarantees that missing/truncated objects never yield a partial ZIP.
        for (FileRecord file : manifest.files) for (ObjectRef object : file.objects)
            if (!objectMatches(store, object.name(), object.size, object.hash, progress)) throw invalid();
        try (SnapshotZipInputStream input = new SnapshotZipInputStream(store, manifest)) {
            if (recovery == null) target.restore(input, password, progress);
            else target.restoreUsingRecovery(input, recovery, password, progress);
            input.awaitSuccess();
        }
    }

    private static final class SnapshotZipInputStream extends InputStream {
        private final PipedInputStream input = new PipedInputStream(65536);
        private final Thread producer;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;
        SnapshotZipInputStream(Store store, Manifest manifest) throws IOException {
            PipedOutputStream output = new PipedOutputStream(input);
            producer = new Thread(() -> {
                ZipOutputStream zip = new ZipOutputStream(output);
                try {
                    zip.setLevel(0);
                    byte[] buffer = new byte[BUFFER_BYTES];
                    for (FileRecord file : manifest.files) {
                        if (closed) throw new IOException("Restauração interrompida.");
                        zip.putNextEntry(new ZipEntry(file.name));
                        MessageDigest whole = digest();
                        for (ObjectRef object : file.objects) {
                            MessageDigest part = digest();
                            try (InputStream source = store.read(object.name())) {
                                int remaining = object.size;
                                while (remaining > 0) {
                                    int n = source.read(buffer, 0, Math.min(buffer.length, remaining));
                                    if (n < 0) throw invalid();
                                    if (n == 0) continue;
                                    whole.update(buffer, 0, n); part.update(buffer, 0, n);
                                    zip.write(buffer, 0, n); remaining -= n;
                                }
                                if (source.read() != -1 || !MessageDigest.isEqual(part.digest(), object.hash)) throw invalid();
                            }
                        }
                        if (!MessageDigest.isEqual(whole.digest(), file.hash)) throw invalid();
                        zip.closeEntry();
                    }
                    zip.finish();
                } catch (Throwable e) { failure.set(e); }
                finally {
                    // On failure, close the pipe before ZipOutputStream.close can emit a valid
                    // descriptor/directory for data that failed our second digest check.
                    if (failure.get() != null) try { output.close(); } catch (IOException ignored) {}
                    try { zip.close(); } catch (Throwable e) { failure.compareAndSet(null, e); }
                    try { output.close(); } catch (IOException e) { failure.compareAndSet(null, e); }
                }
            }, "cofre-backup-restore");
            producer.setDaemon(true); producer.start();
        }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            checkFailure();
            int count = input.read(b, off, len);
            if (count < 0) awaitSuccess();
            else checkFailure();
            return count;
        }
        void awaitSuccess() throws IOException {
            try { producer.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Restauração interrompida."); }
            checkFailure();
        }
        private void checkFailure() throws IOException {
            Throwable e = failure.get(); if (e != null) throw new IOException("Falha ao conferir o conteúdo do backup.", e);
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true; input.close();
            if (producer.isAlive()) producer.interrupt();
        }
    }

    private static void ensureIdentity(VaultEngine vault, Store store, List<String> names) throws Exception {
        if (names.contains(IDENTITY)) {
            byte[] identity;
            try { identity = vault.openState("backup-set-identity", readBounded(store, IDENTITY, 256)); }
            catch (Exception e) { throw new IOException("Esta pasta pertence a outro cofre ou a identificação está danificada. Escolha a pasta correta.", e); }
            if (!MessageDigest.isEqual(identity, IDENTITY_TEXT)) throw invalid();
        } else {
            for (String name : names) if (isObjectName(name) || isSnapshotName(name) || isCommitName(name))
                throw new IOException("Identificação do backup ausente. Escolha outra pasta para preservar este backup.");
            byte[] sealed = vault.sealState("backup-set-identity", IDENTITY_TEXT);
            write(store, IDENTITY, sealed);
            if (!MessageDigest.isEqual(sealed, readBounded(store, IDENTITY, 256))) throw invalid();
        }
    }
    private static void assertCurrent(VaultEngine vault, Manifest manifest, byte[] buffer, VaultEngine.Progress progress) throws Exception {
        Map<String, File> current = new HashMap<>();
        for (File file : vault.currentBackupFiles()) current.put(file.getName(), file);
        for (FileRecord record : manifest.files) {
            File file = current.remove(record.name);
            if (file == null || file.length() != record.size) throw changed();
            if (!record.name.endsWith(".bin") && !fileMatches(file, record, buffer, progress)) throw changed();
        }
        if (!current.isEmpty()) throw changed();
    }
    private static boolean fileMatches(File file, FileRecord expected, byte[] buffer, VaultEngine.Progress progress) throws Exception {
        if (file.length() != expected.size) return false;
        MessageDigest sha = digest(); long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            int n; while ((n = input.read(buffer)) != -1) {
                sha.update(buffer, 0, n); total += n; update(progress, total);
                if (total > expected.size) return false;
            }
        }
        return total == expected.size && MessageDigest.isEqual(sha.digest(), expected.hash);
    }
    private static boolean objectMatches(Store store, String name, int size, byte[] expected, VaultEngine.Progress progress) throws Exception {
        MessageDigest sha = digest(); byte[] buffer = new byte[BUFFER_BYTES]; long count = 0;
        try (InputStream input = store.read(name)) {
            int n; while ((n = input.read(buffer, 0, (int) Math.min(buffer.length, size - count + 1))) != -1) {
                if (n == 0) continue;
                count += n; if (count > size) return false;
                sha.update(buffer, 0, n); update(progress, count);
            }
        } catch (IOException e) { return false; }
        return count == size && MessageDigest.isEqual(sha.digest(), expected);
    }
    private static byte[] encodeManifest(Manifest manifest) throws IOException {
        if (manifest.files.size() < 2 || manifest.files.size() > MAX_FILES) throw invalid();
        ByteArrayOutputStream bytes = new LimitedBytes(MAX_MANIFEST);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41424D31); out.writeUTF(manifest.id); out.writeInt(manifest.files.size());
            for (FileRecord file : manifest.files) {
                out.writeUTF(file.name); out.writeLong(file.size); out.write(file.hash); out.writeInt(file.objects.size());
                for (ObjectRef object : file.objects) { out.write(object.hash); out.write(object.nonce); out.writeInt(object.size); }
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] encodeSnapshot(Manifest manifest, byte[] sealed) throws IOException {
        if (manifest.config == null || manifest.config.length != 84 || (manifest.recovery != null && manifest.recovery.length != 64)) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41425331); out.write(manifest.config);
            out.writeBoolean(manifest.recovery != null); if (manifest.recovery != null) out.write(manifest.recovery);
            out.writeInt(sealed.length); out.write(sealed);
        }
        return bytes.toByteArray();
    }
    private static Manifest decodeSnapshot(byte[] snapshot, String id, char[] password, String recovery) throws Exception {
        Manifest manifest = new Manifest(); byte[] sealed;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            if (in.readInt() != 0x41425331) throw invalid();
            manifest.config = new byte[84]; in.readFully(manifest.config);
            int hasRecovery = in.readUnsignedByte(); if (hasRecovery > 1) throw invalid();
            if (hasRecovery == 1) { manifest.recovery = new byte[64]; in.readFully(manifest.recovery); }
            int size = in.readInt(); if (size < 28 || size > MAX_MANIFEST + 28 || size != in.available()) throw invalid();
            sealed = new byte[size]; in.readFully(sealed);
        }
        if (recovery != null && manifest.recovery == null) throw new IOException("Esta versão não contém uma chave de recuperação.");
        byte[] plain = recovery == null
                ? VaultEngine.openStateWithPassword(manifest.config, password, PURPOSE, sealed)
                : VaultEngine.openStateWithRecovery(manifest.recovery, recovery, PURPOSE, sealed);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            if (in.readInt() != 0x41424D31 || !id.equals(in.readUTF())) throw invalid();
            manifest.id = id;
            int count = in.readInt(); if (count < 2 || count > MAX_FILES) throw invalid();
            Set<String> names = new HashSet<>(); int objects = 0;
            for (int i = 0; i < count; i++) {
                FileRecord file = new FileRecord(); file.name = in.readUTF(); file.size = in.readLong();
                if (!validVaultName(file.name) || !names.add(file.name) || file.size < 0) throw invalid();
                file.hash = new byte[32]; in.readFully(file.hash);
                int chunks = in.readInt();
                long expected = file.size / OBJECT_BYTES + (file.size % OBJECT_BYTES == 0 ? 0 : 1);
                if (chunks < 0 || chunks != expected || (long) objects + chunks > MAX_OBJECTS) throw invalid();
                objects += chunks; long total = 0;
                for (int j = 0; j < chunks; j++) {
                    byte[] hash = new byte[32], nonce = new byte[16]; in.readFully(hash); in.readFully(nonce);
                    int size = in.readInt();
                    if (size != (int) Math.min(OBJECT_BYTES, file.size - total)) throw invalid();
                    total += size; file.objects.add(new ObjectRef(hash, nonce, size));
                }
                if (total != file.size) throw invalid();
                if (file.name.equals(VaultEngine.CONFIG) && (file.size != 84 || !MessageDigest.isEqual(file.hash, hash(manifest.config)))) throw invalid();
                if (file.name.equals(VaultEngine.RECOVERY) && (manifest.recovery == null || file.size != 64 || !MessageDigest.isEqual(file.hash, hash(manifest.recovery)))) throw invalid();
                manifest.files.add(file);
            }
            if (in.read() != -1 || !names.contains(VaultEngine.CONFIG) || !names.contains(VaultEngine.INDEX)
                    || names.contains(VaultEngine.RECOVERY) != (manifest.recovery != null)) throw invalid();
        } finally { Arrays.fill(plain, (byte) 0); }
        return manifest;
    }
    private static byte[] encodeCommit(Commit commit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x41424331); out.writeUTF(commit.id); out.writeInt(commit.size); out.write(commit.hash); out.flush();
        out.write(hash(bytes.toByteArray())); out.flush(); return bytes.toByteArray();
    }
    private static Commit readCommit(Store store, String name) throws IOException {
        if (!isCommitName(name)) throw invalid();
        byte[] bytes = readBounded(store, name, 256); if (bytes.length < 32) throw invalid();
        if (!MessageDigest.isEqual(hash(Arrays.copyOf(bytes, bytes.length - 32)), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length))) throw invalid();
        Commit commit = new Commit();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 32))) {
            if (in.readInt() != 0x41424331) throw invalid();
            commit.id = in.readUTF(); commit.size = in.readInt(); commit.hash = new byte[32]; in.readFully(commit.hash);
            if (!name.equals("c-" + commit.id + ".amzc") || commit.size < 117 || commit.size > MAX_MANIFEST + 1024 || in.read() != -1) throw invalid();
        }
        return commit;
    }
    private static String nextId(List<String> names) throws IOException {
        long time = System.currentTimeMillis();
        for (String name : names) if (isCommitName(name) || isSnapshotName(name))
            time = Math.max(time, Long.parseLong(name.substring(2, 15)) + 1);
        if (time < 0 || time > 9999999999999L) throw invalid();
        return String.format(Locale.ROOT, "%013d", time) + "-" + UUID.randomUUID();
    }
    private static boolean isCommitName(String name) { return name.matches("c-[0-9]{13}-[a-f0-9-]{36}\\.amzc"); }
    private static boolean isSnapshotName(String name) { return name.matches("s-[0-9]{13}-[a-f0-9-]{36}\\.amzs"); }
    private static boolean isObjectName(String name) { return name.matches("o-[a-f0-9]{64}-[a-f0-9]{32}\\.amzo"); }
    private static boolean validVaultName(String name) {
        return name.equals(VaultEngine.CONFIG) || name.equals(VaultEngine.INDEX) || name.equals(VaultEngine.RECOVERY)
                || name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.bin");
    }
    private static void write(Store store, String name, byte[] data) throws IOException { try (OutputStream out = store.create(name)) { out.write(data); } }
    private static byte[] readBounded(Store store, String name, int limit) throws IOException {
        try (InputStream input = store.read(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, total = 0;
            while ((n = input.read(buffer)) != -1) { total += n; if (total > limit) throw invalid(); output.write(buffer, 0, n); }
            return output.toByteArray();
        }
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static byte[] hash(byte[] data) { return digest().digest(data); }
    private static String hex(byte[] data) { StringBuilder out = new StringBuilder(data.length * 2); for (byte b : data) out.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16)); return out.toString(); }
    private static byte[] unhex(String text) { byte[] bytes = new byte[text.length() / 2]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16); return bytes; }
    private static void phase(VaultEngine.Progress progress, String name) { if (progress != null) progress.phase(name); }
    private static void update(VaultEngine.Progress progress, long count) { if (progress != null) progress.update(count); }
    private static IOException invalid() { return new IOException("Backup incompleto ou danificado. O cofre atual foi preservado."); }
    private static IOException changed() { return new IOException("O cofre mudou durante o backup. Repita para salvar uma versão consistente."); }
    private static final class LimitedBytes extends ByteArrayOutputStream {
        private final int limit;
        LimitedBytes(int limit) { this.limit = limit; }
        @Override public synchronized void write(int b) { if (count >= limit) throw new IllegalStateException("Muitos blocos no backup."); super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { if (len > limit - count) throw new IllegalStateException("Muitos blocos no backup."); super.write(b, off, len); }
    }
}
