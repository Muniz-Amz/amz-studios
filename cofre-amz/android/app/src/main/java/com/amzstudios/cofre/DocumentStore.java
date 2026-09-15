package com.amzstudios.cofre;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** SAF adapter. An existing backup object is never opened for writing. */
final class DocumentStore implements VaultBackupSet.Store {
    private final ContentResolver resolver;
    private final Uri directory;
    private final Map<String,Uri> objects=new HashMap<>();
    private final Set<Uri> objectUris=new HashSet<>();
    private boolean loaded;
    DocumentStore(Context context,Uri tree){resolver=context.getApplicationContext().getContentResolver();directory=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
    @Override public List<String> list()throws IOException{
        objects.clear();objectUris.clear();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(directory,DocumentsContract.getDocumentId(directory));
        try(Cursor cursor=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor==null)throw new IOException("Não foi possível ler a pasta do backup.");
            while(cursor.moveToNext()){String name=cursor.getString(1);Uri uri=DocumentsContract.buildDocumentUriUsingTree(directory,cursor.getString(0));if(objects.put(name,uri)!=null||!objectUris.add(uri))throw new IOException("A pasta contém objetos duplicados.");}
        }catch(RuntimeException e){throw new IOException("Não foi possível acessar a pasta do backup.",e);}
        loaded=true;return new ArrayList<>(objects.keySet());
    }
    @Override public InputStream read(String name)throws IOException{
        Uri uri=objects.get(name);if(uri==null){list();uri=objects.get(name);}if(uri==null)throw new FileNotFoundException(name);
        InputStream in=resolver.openInputStream(uri);if(in==null)throw new IOException("Não foi possível ler o backup.");return in;
    }
    @Override public OutputStream create(String name)throws IOException{
        if(!name.matches("[A-Za-z0-9._-]{1,180}"))throw new IOException("Nome de objeto inválido.");
        if(!loaded)list();if(objects.containsKey(name))throw new IOException("O arquivo do backup já existe.");
        Uri uri=DocumentsContract.createDocument(resolver,directory,"application/octet-stream",name);if(uri==null)throw new IOException("Não foi possível criar o objeto do backup.");
        if(objectUris.contains(uri))throw new IOException("O destino retornou um arquivo já existente. A gravação foi recusada.");
        try(Cursor c=resolver.query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c==null||!c.moveToFirst()||!name.equals(c.getString(0)))throw new IOException("O destino alterou o nome do objeto. Escolha outra pasta.");}
        objects.put(name,uri);objectUris.add(uri);ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"rw");if(fd==null)throw new IOException("Não foi possível gravar o backup.");
        if(fd.getStatSize()>0){fd.close();throw new IOException("O novo destino já contém dados. A gravação foi recusada.");}
        return new ParcelFileDescriptor.AutoCloseOutputStream(fd){
            boolean closed;
            @Override public void close()throws IOException{if(closed)return;closed=true;try{flush();getFD().sync();}finally{super.close();}}
        };
    }
}
