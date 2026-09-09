package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class VaultEngineTest {
    File temp,root;VaultEngine vault;
    final char[] password="senha forte do cofre 2026".toCharArray();
    @Before public void setup()throws Exception{temp=Files.createTempDirectory("amz-test-").toFile();root=new File(temp,"vault");vault=new VaultEngine(root);vault.create(password);}
    @After public void cleanup(){vault.lock();VaultEngine.removeTree(temp);}
    private VaultEngine.Entry add(String name,byte[] bytes)throws Exception{return vault.importFile(new ByteArrayInputStream(bytes),name,"application/octet-stream","",null);}
    private byte[] export(String id)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();vault.exportFile(id,out,null);return out.toByteArray();}
    @Test public void multiChunkAndEmptyRoundTripAcrossRestart()throws Exception{
        byte[] data=new byte[VaultEngine.CHUNK*2+91];new Random(7).nextBytes(data);
        VaultEngine.Entry item=add("Documento privado.bin",data),empty=add("vazio.txt",new byte[0]);
        vault.lock();vault.unlock(password);assertArrayEquals(data,export(item.id));assertArrayEquals(new byte[0],export(empty.id));
        for(File f:root.listFiles()){String raw=new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.ISO_8859_1);assertFalse(raw.contains("Documento privado"));assertFalse(raw.contains(new String(password)));}
    }
    @Test public void wrongPasswordDoesNotOpenOrModifyVault()throws Exception{
        VaultEngine.Entry e=add("a.txt","conteúdo".getBytes("UTF-8"));byte[] before=Files.readAllBytes(new File(root,"index.enc").toPath());vault.lock();
        assertThrows(Exception.class,()->vault.unlock("senha errada".toCharArray()));assertFalse(vault.isUnlocked());assertArrayEquals(before,Files.readAllBytes(new File(root,"index.enc").toPath()));vault.unlock(password);assertEquals("conteúdo",new String(export(e.id),"UTF-8"));
    }
    @Test public void modificationTruncationAndTrailingBytesAreRejected()throws Exception{
        VaultEngine.Entry e=add("x.bin",new byte[VaultEngine.CHUNK+10]);File file=new File(root,e.id+".bin");byte[] bytes=Files.readAllBytes(file.toPath());
        byte[] changed=bytes.clone();changed[40]^=1;Files.write(file.toPath(),changed);assertThrows(Exception.class,()->vault.verify(e.id));
        Files.write(file.toPath(),Arrays.copyOf(bytes,bytes.length-32));assertThrows(Exception.class,()->vault.verify(e.id));
        Files.write(file.toPath(),Arrays.copyOf(bytes,bytes.length+1));assertThrows(Exception.class,()->vault.verify(e.id));
        Files.write(file.toPath(),bytes);vault.verify(e.id);
    }
    @Test public void folderRenameMoveAndDeletePersist()throws Exception{
        VaultEngine.Entry a=vault.folder("","Pessoal"),b=vault.folder(a.id,"Documentos"),file=add("a.txt",new byte[]{4,5});
        vault.move(file.id,b.id);vault.rename(file.id,"contrato.txt");assertThrows(IOException.class,()->vault.move(a.id,b.id));assertThrows(IOException.class,()->vault.rename(file.id,"../escape"));
        vault.lock();vault.unlock(password);assertEquals(b.id,vault.get(file.id).parent);assertEquals("contrato.txt",vault.get(file.id).name);
        vault.delete(a.id);assertTrue(vault.list().isEmpty());assertFalse(new File(root,file.id+".bin").exists());
    }
    @Test public void backupRestoresOnAnotherDeviceAndRejectsWrongPassword()throws Exception{
        byte[] data=new byte[VaultEngine.CHUNK+59];new Random(2).nextBytes(data);VaultEngine.Entry e=add("foto.jpg",data);ByteArrayOutputStream bytes=new ByteArrayOutputStream();vault.backup(bytes);byte[] archive=bytes.toByteArray();vault.verifyBackup(new ByteArrayInputStream(archive));
        VaultEngine other=new VaultEngine(new File(temp,"other"));assertThrows(Exception.class,()->other.restore(new ByteArrayInputStream(archive),"errada".toCharArray()));assertFalse(other.exists());
        other.restore(new ByteArrayInputStream(archive),password);ByteArrayOutputStream actual=new ByteArrayOutputStream();other.exportFile(e.id,actual,null);assertArrayEquals(data,actual.toByteArray());assertThrows(IOException.class,()->other.restore(new ByteArrayInputStream(archive),password));other.lock();
    }
    @Test public void interruptedImportNeverCommitsMetadata()throws Exception{
        InputStream broken=new InputStream(){int calls;public int read()throws IOException{if(++calls>100)throw new IOException("falha simulada");return 3;}};
        assertThrows(IOException.class,()->vault.importFile(broken,"falha.txt","text/plain","",null));assertTrue(vault.list().isEmpty());vault.lock();vault.unlock(password);assertTrue(vault.list().isEmpty());
    }
    @Test public void passwordChangePreservesFilesAndRejectsOldPassword()throws Exception{
        VaultEngine.Entry e=add("private.txt",new byte[]{9,8,7});char[] next="Outra senha longa 2026".toCharArray();vault.changePassword(next);vault.lock();assertThrows(Exception.class,()->vault.unlock(password));vault.unlock(next);assertArrayEquals(new byte[]{9,8,7},export(e.id));
    }
    @Test public void exportFailurePreservesEncryptedOriginal()throws Exception{
        VaultEngine.Entry e=add("photo.jpg",new byte[100]);OutputStream bad=new OutputStream(){public void write(int b)throws IOException{throw new IOException("disco cheio");}};
        assertThrows(IOException.class,()->vault.exportFile(e.id,bad,null));assertNotNull(vault.get(e.id));vault.verify(e.id);
    }
    @Test public void zipTraversalAndDuplicateEntriesCannotEscapeRestore()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(out)){z.putNextEntry(new java.util.zip.ZipEntry("../escape"));z.write(7);z.closeEntry();}
        VaultEngine other=new VaultEngine(new File(temp,"other"));assertThrows(IOException.class,()->other.restore(new ByteArrayInputStream(out.toByteArray()),password));assertFalse(new File(temp,"escape").exists());
    }
    @Test public void sourceChangeIsDetectedBeforeRemoval()throws Exception{
        VaultEngine.Entry e=add("file.txt",new byte[]{1,2,3});assertTrue(vault.matches(e.id,new ByteArrayInputStream(new byte[]{1,2,3})));assertFalse(vault.matches(e.id,new ByteArrayInputStream(new byte[]{1,2,4})));assertFalse(vault.matches(e.id,new ByteArrayInputStream(new byte[]{1,2,3,4})));
    }
    @Test public void authenticatedIndexRejectsTampering()throws Exception{
        add("secret.txt",new byte[]{1});File index=new File(root,"index.enc");byte[] bytes=Files.readAllBytes(index.toPath());bytes[15]^=2;Files.write(index.toPath(),bytes);vault.lock();assertThrows(Exception.class,()->vault.unlock(password));assertFalse(vault.isUnlocked());
    }
}
