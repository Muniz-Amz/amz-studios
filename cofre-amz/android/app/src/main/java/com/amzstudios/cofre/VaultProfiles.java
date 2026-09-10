package com.amzstudios.cofre;

import java.io.*;

/** Two independent encrypted stores. Call mutations on the Activity's serial worker. */
final class VaultProfiles {
    final VaultEngine primary,alternate;
    private volatile VaultEngine active;
    VaultProfiles(File files){
        primary=new VaultEngine(new File(files,"vault-v1"));
        alternate=new VaultEngine(new File(files,"vault-alternate-v1"));active=primary;
    }
    VaultEngine active(){return active;}
    boolean exists(){return primary.exists();}
    boolean hasRecovery(){return primary.hasRecovery()||alternate.hasRecovery();}
    boolean isPrimary(){return active==primary&&primary.isUnlocked();}
    void lock(){primary.lock();alternate.lock();}
    void create(char[] password)throws Exception{primary.create(password);active=primary;}
    void unlock(char[] password)throws Exception{
        lock();
        for(VaultEngine candidate:new VaultEngine[]{primary,alternate}){
            if(!candidate.exists())continue;
            try{candidate.unlock(password);active=candidate;return;}catch(Exception e){candidate.lock();}
        }
        throw new IOException("Senha incorreta ou cofre danificado.");
    }
    private void requirePrimary()throws IOException{if(!isPrimary())throw new IOException("Desbloqueie o cofre para continuar.");}
    private void differentFrom(VaultEngine other,char[] password)throws Exception{
        if(password.length<10)throw new IOException("Use pelo menos 10 caracteres.");
        if(other.exists()&&other.acceptsPassword(password))throw new IOException("Esta senha não pode ser usada. Escolha outra senha.");
    }
    void createAlternate(char[] password)throws Exception{
        requirePrimary();if(alternate.exists())throw new IOException("A senha alternativa já está configurada.");
        differentFrom(primary,password);
        try{alternate.create(password);}finally{alternate.lock();}
    }
    void changePassword(char[] password)throws Exception{
        if(!active.isUnlocked())throw new IOException("Desbloqueie o cofre para continuar.");
        differentFrom(active==primary?alternate:primary,password);active.changePassword(password);
    }
    void recover(String code,char[] password)throws Exception{
        lock();VaultEngine recovered=null;
        for(VaultEngine candidate:new VaultEngine[]{primary,alternate}){
            if(!candidate.hasRecovery())continue;
            try{candidate.unlockWithRecovery(code);recovered=candidate;break;}catch(Exception e){candidate.lock();}
        }
        if(recovered==null)throw new IOException("Não foi possível recuperar o acesso.");
        try{differentFrom(recovered==primary?alternate:primary,password);recovered.changePassword(password);active=recovered;}
        catch(Exception e){lock();throw e;}
    }
    void restore(InputStream source,char[] password,String recovery,boolean toAlternate,VaultEngine.Progress progress)throws Exception{
        if(toAlternate)requirePrimary();
        VaultEngine target=toAlternate?alternate:primary,other=toAlternate?primary:alternate;
        if(target.exists())throw new IOException("O cofre existente será preservado. Restaure somente em um destino vazio.");
        differentFrom(other,password);
        try{if(recovery==null)target.restore(source,password,progress);else target.restoreUsingRecovery(source,recovery,password,progress);}
        catch(Exception e){target.lock();throw e;}
        other.lock();active=target;
    }
}
