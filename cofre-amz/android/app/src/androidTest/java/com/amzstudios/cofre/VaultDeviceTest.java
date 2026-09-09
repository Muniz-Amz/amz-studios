package com.amzstudios.cofre;

import androidx.test.platform.app.InstrumentationRegistry;
import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.util.*;

/** Real Android crypto provider, filesystem, persistence, backup, and failure-path checks. */
public class VaultDeviceTest {
    @Test public void testAndroidCryptoFileAndBackupRoundTrip()throws Exception {
        File temp=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"vault-device-test");VaultEngine.removeTree(temp);temp.mkdirs();
        char[] password="Senha longa Android 2026".toCharArray();VaultEngine first=new VaultEngine(new File(temp,"one")),second=new VaultEngine(new File(temp,"two"));
        try {
            first.create(password);byte[] content=new byte[2*VaultEngine.CHUNK+9];new Random(41).nextBytes(content);
            VaultEngine.Entry folder=first.folder("","Pessoal"),entry=first.importFile(new ByteArrayInputStream(content),"prova.bin","application/octet-stream",folder.id,null);
            first.verify(entry.id);first.lock();first.unlock(password);ByteArrayOutputStream actual=new ByteArrayOutputStream();first.exportFile(entry.id,actual,null);assertTrue(Arrays.equals(content,actual.toByteArray()));
            ByteArrayOutputStream backup=new ByteArrayOutputStream();first.backup(backup);first.verifyBackup(new ByteArrayInputStream(backup.toByteArray()));second.restore(new ByteArrayInputStream(backup.toByteArray()),password);second.verify(entry.id);assertEquals(folder.id,second.get(entry.id).parent);
            second.lock();try{second.unlock("errada".toCharArray());fail("Wrong password accepted");}catch(javax.crypto.AEADBadTagException expected){}assertFalse(second.isUnlocked());
            first.delete(folder.id);assertTrue(first.list().isEmpty());
        } finally {first.lock();second.lock();VaultEngine.removeTree(temp);}
    }
}
