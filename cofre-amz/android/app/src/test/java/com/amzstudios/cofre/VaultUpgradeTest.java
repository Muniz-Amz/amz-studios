package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class VaultUpgradeTest {
    File temp,root;VaultEngine v;char[] password="Senha segura nova versao".toCharArray();
    @Before public void setup()throws Exception{temp=Files.createTempDirectory("cofre-11-").toFile();root=new File(temp,"vault");v=new VaultEngine(root);v.create(password);}
    @After public void cleanup(){v.lock();VaultEngine.removeTree(temp);}
    private VaultEngine.Entry file(String parent,String name)throws Exception{return v.importFile(new ByteArrayInputStream(("Conteudo "+name).getBytes("UTF-8")),name,"text/plain",parent,null);}
    private byte[] backup()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();v.backup(out);byte[] bytes=out.toByteArray();v.verifyBackup(new ByteArrayInputStream(bytes));return bytes;}
    @Test public void restoresRealV100FixtureAndUpgradesWithoutChangingFile()throws Exception{
        VaultEngine old=new VaultEngine(new File(temp,"legacy"));try(InputStream in=getClass().getResourceAsStream("/v1-cofre.amzcofre")){assertNotNull(in);old.restore(in,"Senha compatibilidade v1".toCharArray());}
        VaultEngine.Entry original=old.list().stream().filter(e->e.name.equals("original.txt")).findFirst().get();byte[] cipher=Files.readAllBytes(new File(temp,"legacy/"+original.id+".bin").toPath());
        old.trash(Collections.singleton(original.id));old.lock();old.unlock("Senha compatibilidade v1".toCharArray());assertTrue(old.get(original.id).isTrashed());old.restoreTrash(Collections.singleton(original.id));
        assertArrayEquals(cipher,Files.readAllBytes(new File(temp,"legacy/"+original.id+".bin").toPath()));ByteArrayOutputStream restored=new ByteArrayOutputStream();old.exportFile(original.id,restored,null);assertEquals("Arquivo preservado da versao 1.0.0",new String(restored.toByteArray(),"UTF-8"));old.lock();
    }
    @Test public void trashRetainsCiphertextAndRestoreHandlesNameCollision()throws Exception{
        VaultEngine.Entry e=file("","documento.txt");byte[] cipher=Files.readAllBytes(new File(root,e.id+".bin").toPath());v.trash(Collections.singleton(e.id));assertTrue(v.get(e.id).isTrashed());assertArrayEquals(cipher,Files.readAllBytes(new File(root,e.id+".bin").toPath()));
        file("","documento.txt");v.restoreTrash(Collections.singleton(e.id));assertEquals("documento (restaurado).txt",v.get(e.id).name);assertFalse(v.get(e.id).isTrashed());v.lock();v.unlock(password);v.verify(e.id);
    }
    @Test public void separatelyTrashedChildDoesNotReturnWithParent()throws Exception{
        VaultEngine.Entry folder=v.folder("","Pasta"),child=file(folder.id,"antes.txt"),active=file(folder.id,"agora.txt");v.trash(Collections.singleton(child.id));v.trash(Collections.singleton(folder.id));v.restoreTrash(Collections.singleton(folder.id));
        assertTrue(v.get(child.id).isTrashed());assertFalse(v.get(active.id).isTrashed());v.trash(Collections.singleton(folder.id));v.purgeTrash(Collections.singleton(folder.id));assertTrue(v.get(child.id).isTrashed());v.restoreTrash(Collections.singleton(child.id));assertEquals("",v.get(child.id).parent);v.verify(child.id);
    }
    @Test public void multiSelectionNormalizesFoldersAndMovesAtomically()throws Exception{
        VaultEngine.Entry folder=v.folder("","Origem"),child=file(folder.id,"a.txt"),dest=v.folder("","Destino");assertEquals(1,v.selectionRoots(Arrays.asList(folder.id,child.id)).size());v.moveAll(Arrays.asList(folder.id,child.id),dest.id);assertEquals(dest.id,v.get(folder.id).parent);assertEquals(folder.id,v.get(child.id).parent);
        assertThrows(IOException.class,()->v.moveAll(Collections.singleton(dest.id),folder.id));
        VaultEngine.Entry one=file("","conflito.txt"),two=file("","livre.txt");file(dest.id,"conflito.txt");assertThrows(IOException.class,()->v.moveAll(Arrays.asList(two.id,one.id),dest.id));assertEquals("",v.get(two.id).parent);
    }
    @Test public void multipleTrashRestoreAndPurgePersist()throws Exception{
        VaultEngine.Entry a=file("","a.txt"),b=file("","b.txt");long before=v.storedBytes();v.trash(Arrays.asList(a.id,b.id));assertTrue(v.trashBytes()>0);v.lock();v.unlock(password);v.restoreTrash(Collections.singleton(a.id));v.purgeTrash(Collections.singleton(b.id));assertFalse(new File(root,b.id+".bin").exists());assertFalse(v.get(a.id).isTrashed());assertTrue(v.storedBytes()<before);assertThrows(IOException.class,()->v.purgeTrash(Collections.singleton(a.id)));
    }
    @Test public void recoveryChangesPasswordWithoutChangingData()throws Exception{
        VaultEngine.Entry e=file("","segredo.txt");String code=v.createRecoveryKey();byte[] before=Files.readAllBytes(new File(root,e.id+".bin").toPath());v.lock();char[] next="Senha recuperada offline".toCharArray();v.recover(code.toLowerCase(Locale.ROOT),next);v.verify(e.id);assertArrayEquals(before,Files.readAllBytes(new File(root,e.id+".bin").toPath()));v.lock();assertThrows(Exception.class,()->v.unlock(password));v.unlock(next);
        for(File f:root.listFiles())assertFalse(new String(Files.readAllBytes(f.toPath()),"ISO-8859-1").contains(code));
    }
    @Test public void wrongRecoveryAndForeignRecoveryNeverRewritePassword()throws Exception{
        file("","a.txt");String code=v.createRecoveryKey();byte[] header=Files.readAllBytes(new File(root,"vault.key").toPath());String wrong=code.substring(0,code.length()-1)+(code.endsWith("A")?"B":"A");v.lock();assertThrows(Exception.class,()->v.recover(wrong,"Nova senha forte 2026".toCharArray()));assertArrayEquals(header,Files.readAllBytes(new File(root,"vault.key").toPath()));v.unlock(password);
        VaultEngine other=new VaultEngine(new File(temp,"foreign"));other.create(password);String foreign=other.createRecoveryKey();assertThrows(Exception.class,()->v.recover(foreign,password));assertArrayEquals(header,Files.readAllBytes(new File(root,"vault.key").toPath()));other.lock();v.unlock(password);
    }
    @Test public void recoveryRotationAndRecoveryBackupRestore()throws Exception{
        VaultEngine.Entry e=file("","recuperavel.txt");String old=v.createRecoveryKey();byte[] oldBackup=backup();String current=v.createRecoveryKey();v.lock();assertThrows(Exception.class,()->v.recover(old,password));v.recover(current,password);v.trash(Collections.singleton(e.id));byte[] recent=backup();
        VaultEngine restored=new VaultEngine(new File(temp,"restored"));restored.restoreUsingRecovery(new ByteArrayInputStream(recent),current,"Senha do celular novo".toCharArray());assertTrue(restored.get(e.id).isTrashed());restored.verify(e.id);restored.lock();
        VaultEngine legacy=new VaultEngine(new File(temp,"old-backup"));legacy.restoreUsingRecovery(new ByteArrayInputStream(oldBackup),old,password);legacy.verify(e.id);legacy.lock();
    }
    @Test public void reminderOnlyClearsAfterVerifiedBackupAndReturnsOnChanges()throws Exception{
        assertFalse(v.needsBackup());VaultEngine.Entry e=file("","a.txt");assertTrue(v.needsBackup());byte[] copy=backup();assertTrue(v.needsBackup());v.markBackupCompleted();assertFalse(v.needsBackup());assertTrue(v.lastBackupAt()>0);v.lock();v.unlock(password);assertFalse(v.needsBackup());
        v.rename(e.id,"renomeado.txt");assertTrue(v.needsBackup());assertThrows(Exception.class,()->v.verifyBackup(new ByteArrayInputStream(copy)));assertTrue(v.needsBackup());backup();v.markBackupCompleted();assertFalse(v.needsBackup());v.createRecoveryKey();assertTrue(v.needsBackup());backup();v.markBackupCompleted();v.changePassword("Senha atualizada cofre".toCharArray());assertTrue(v.needsBackup());
    }
    @Test public void trashAndRecoverySurvivePasswordBackupRestore()throws Exception{
        VaultEngine.Entry a=file("","a.txt"),b=file("","b.txt");v.createRecoveryKey();v.trash(Collections.singleton(a.id));byte[] copy=backup();VaultEngine other=new VaultEngine(new File(temp,"restore"));other.restore(new ByteArrayInputStream(copy),password);assertTrue(other.hasRecovery());assertTrue(other.get(a.id).isTrashed());assertFalse(other.get(b.id).isTrashed());assertFalse(other.needsBackup());other.restoreTrash(Collections.singleton(a.id));other.verify(a.id);assertTrue(other.needsBackup());other.lock();
    }
    @Test public void corruptBackupMarkerTriggersReminderWithoutBlockingVault()throws Exception{
        file("","a.txt");backup();v.markBackupCompleted();Files.write(new File(root,"backup.state").toPath(),new byte[]{1,2});v.lock();v.unlock(password);assertTrue(v.needsBackup());assertEquals(0,v.lastBackupAt());
    }
}
