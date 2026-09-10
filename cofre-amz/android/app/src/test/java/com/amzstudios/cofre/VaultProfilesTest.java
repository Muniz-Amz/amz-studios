package com.amzstudios.cofre;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class VaultProfilesTest {
    File root;VaultProfiles profiles;
    final char[] real="Senha principal teste 2026".toCharArray(),fake="Senha alternativa teste 2026".toCharArray();
    @Before public void setup()throws Exception{root=Files.createTempDirectory("cofre-profiles-").toFile();profiles=new VaultProfiles(root);profiles.create(real);}
    @After public void cleanup(){profiles.lock();VaultEngine.removeTree(root);}
    private VaultEngine.Entry add(String name)throws Exception{return profiles.active().importFile(new ByteArrayInputStream(name.getBytes("UTF-8")),name,"text/plain","",null);}
    private byte[] backup()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();profiles.active().backup(out);profiles.active().verifyBackup(new ByteArrayInputStream(out.toByteArray()));return out.toByteArray();}
    @Test public void passwordsSelectIndependentKeysFilesAndTrashAfterRestart()throws Exception{
        VaultEngine.Entry secret=add("privado.txt");byte[] cipher=Files.readAllBytes(new File(root,"vault-v1/"+secret.id+".bin").toPath());
        profiles.createAlternate(fake);assertTrue(profiles.isPrimary());assertFalse(profiles.alternate.isUnlocked());profiles.unlock(fake);
        assertSame(profiles.alternate,profiles.active());assertFalse(profiles.primary.isUnlocked());assertTrue(profiles.active().list().isEmpty());assertThrows(IOException.class,()->profiles.active().get(secret.id));
        VaultEngine.Entry cover=add("cotidiano.txt");profiles.active().trash(Collections.singleton(cover.id));profiles.lock();profiles=new VaultProfiles(root);profiles.unlock(real);
        assertEquals(1,profiles.active().list().size());assertFalse(profiles.active().get(secret.id).isTrashed());profiles.active().verify(secret.id);assertFalse(profiles.alternate.isUnlocked());
        assertArrayEquals(cipher,Files.readAllBytes(new File(root,"vault-v1/"+secret.id+".bin").toPath()));profiles.unlock(fake);assertTrue(profiles.active().get(cover.id).isTrashed());profiles.active().restoreTrash(Collections.singleton(cover.id));profiles.active().verify(cover.id);
    }
    @Test public void cannotCreateWithSamePasswordOrConfigureFromAlternate()throws Exception{
        assertThrows(IOException.class,()->profiles.createAlternate(real));assertFalse(profiles.alternate.exists());assertTrue(profiles.isPrimary());profiles.createAlternate(fake);profiles.unlock(fake);
        assertThrows(IOException.class,()->profiles.createAlternate("Outra senha teste 2026".toCharArray()));assertFalse(profiles.primary.isUnlocked());
    }
    @Test public void wrongPasswordLocksBothAndNeverReturnsAnEmptyFallback()throws Exception{
        add("privado.txt");profiles.createAlternate(fake);profiles.unlock(fake);add("cotidiano.txt");
        assertThrows(IOException.class,()->profiles.unlock("Senha errada teste 2026".toCharArray()));assertFalse(profiles.primary.isUnlocked());assertFalse(profiles.alternate.isUnlocked());assertThrows(IllegalStateException.class,()->profiles.active().list());
        profiles.unlock(real);assertEquals("privado.txt",profiles.active().list().get(0).name);
    }
    @Test public void passwordChangesRejectCollisionsAndKeepOtherVaultUnchanged()throws Exception{
        profiles.createAlternate(fake);byte[] config=Files.readAllBytes(new File(root,"vault-v1/vault.key").toPath());profiles.unlock(fake);
        assertThrows(IOException.class,()->profiles.changePassword(real));assertTrue(profiles.active().isUnlocked());char[] next="Senha alternativa nova 2026".toCharArray();profiles.changePassword(next);
        assertArrayEquals(config,Files.readAllBytes(new File(root,"vault-v1/vault.key").toPath()));assertThrows(IOException.class,()->profiles.unlock(fake));profiles.unlock(next);assertSame(profiles.alternate,profiles.active());profiles.unlock(real);assertThrows(IOException.class,()->profiles.changePassword(next));
    }
    @Test public void recoveryCodeOnlyChangesItsOwnVaultAndRejectsCollision()throws Exception{
        VaultEngine.Entry secret=add("privado.txt");String realCode=profiles.active().createRecoveryKey();profiles.createAlternate(fake);profiles.unlock(fake);VaultEngine.Entry cover=add("cotidiano.txt");String fakeCode=profiles.active().createRecoveryKey();
        byte[] config=Files.readAllBytes(new File(root,"vault-alternate-v1/vault.key").toPath());assertThrows(IOException.class,()->profiles.recover(fakeCode,real));assertArrayEquals(config,Files.readAllBytes(new File(root,"vault-alternate-v1/vault.key").toPath()));
        profiles.recover(fakeCode,"Senha falsa recuperada 2026".toCharArray());assertSame(profiles.alternate,profiles.active());profiles.active().verify(cover.id);assertFalse(profiles.primary.isUnlocked());
        profiles.recover(realCode,"Senha real recuperada 2026".toCharArray());assertTrue(profiles.isPrimary());profiles.active().verify(secret.id);assertFalse(profiles.alternate.isUnlocked());
    }
    @Test public void backupAndReminderContainOnlyOpenVault()throws Exception{
        VaultEngine.Entry secret=add("privado.txt");byte[] mainBackup=backup();profiles.active().markBackupCompleted();profiles.createAlternate(fake);assertFalse(profiles.primary.needsBackup());profiles.unlock(fake);VaultEngine.Entry cover=add("cotidiano.txt");String code=profiles.active().createRecoveryKey();byte[] coverBackup=backup();profiles.active().markBackupCompleted();assertFalse(profiles.active().needsBackup());
        VaultEngine restored=new VaultEngine(new File(root,"restore-check"));try{restored.restoreUsingRecovery(new ByteArrayInputStream(coverBackup),code,fake);assertEquals(1,restored.list().size());restored.verify(cover.id);assertThrows(IOException.class,()->restored.get(secret.id));}finally{restored.lock();}
        profiles.unlock(real);assertFalse(profiles.active().needsBackup());profiles.active().verifyBackup(new ByteArrayInputStream(mainBackup));assertThrows(Exception.class,()->profiles.active().verifyBackup(new ByteArrayInputStream(coverBackup)));
    }
    @Test public void restoreSecondBackupAlongsideFirstAndPreserveBothOnFailure()throws Exception{
        VaultEngine.Entry secret=add("privado.txt");byte[] mainBackup=backup();profiles.createAlternate(fake);profiles.unlock(fake);VaultEngine.Entry cover=add("cotidiano.txt");byte[] coverBackup=backup();
        VaultProfiles restored=new VaultProfiles(new File(root,"restored"));
        try{restored.restore(new ByteArrayInputStream(mainBackup),real,null,false,null);assertTrue(restored.isPrimary());assertThrows(Exception.class,()->restored.restore(new ByteArrayInputStream(coverBackup),real,null,true,null));assertFalse(restored.alternate.exists());restored.primary.verify(secret.id);
            restored.restore(new ByteArrayInputStream(coverBackup),fake,null,true,null);restored.active().verify(cover.id);assertFalse(restored.primary.isUnlocked());assertThrows(IOException.class,()->restored.restore(new ByteArrayInputStream(mainBackup),real,null,false,null));restored.unlock(real);restored.active().verify(secret.id);
        }finally{restored.lock();}
    }
    @Test public void interruptedAlternateRestoreKeepsPrimaryAndLeavesTargetLocked()throws Exception{
        profiles.createAlternate(fake);profiles.unlock(fake);add("cotidiano.txt");byte[] copy=backup();VaultProfiles target=new VaultProfiles(new File(root,"cancelled-restore"));
        try{target.create(real);VaultEngine.Entry secret=target.active().importFile(new ByteArrayInputStream(new byte[]{1,2,3}),"privado.txt","text/plain","",null);
            assertThrows(java.util.concurrent.CancellationException.class,()->target.restore(new ByteArrayInputStream(copy),fake,null,true,bytes->{throw new java.util.concurrent.CancellationException();}));
            assertTrue(target.isPrimary());target.active().verify(secret.id);assertFalse(target.alternate.exists());assertFalse(target.alternate.isUnlocked());
        }finally{target.lock();}
    }
    @Test public void oldSingleVaultAndReadOnlyPasswordCheckKeepCompatibility()throws Exception{
        VaultEngine.Entry secret=add("legado.txt");assertTrue(profiles.primary.acceptsPassword(real));assertFalse(profiles.primary.acceptsPassword(fake));profiles.primary.verify(secret.id);profiles.lock();
        assertTrue(profiles.primary.acceptsPassword(real));assertFalse(profiles.primary.isUnlocked());profiles.unlock(real);profiles.active().verify(secret.id);assertFalse(profiles.alternate.exists());
    }
}
