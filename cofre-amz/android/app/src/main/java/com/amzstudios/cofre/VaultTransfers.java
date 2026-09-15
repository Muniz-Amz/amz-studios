package com.amzstudios.cofre;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.*;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** One authenticated pending operation per vault; no password or plaintext file bytes in the journal. */
final class VaultTransfers {
    private static final String FILE="operation.state",PURPOSE="transfers-v1";
    private final Context context;
    private final ContentResolver resolver;
    private final VaultEngine vault;
    private JSONObject state;
    VaultTransfers(Context context,VaultEngine vault){this.context=context.getApplicationContext();resolver=this.context.getContentResolver();this.vault=vault;}
    boolean pending(){return new File(vault.privateDirectory(),FILE).exists();}
    private void save()throws Exception{byte[] plain=state.toString().getBytes(StandardCharsets.UTF_8);try{if(plain.length>16*1024*1024-28)throw new IOException("Selecione menos itens nesta operação.");VaultEngine.writeAtomicState(new File(vault.privateDirectory(),FILE),vault.sealState(PURPOSE,plain));}finally{Arrays.fill(plain,(byte)0);}}
    private void load()throws Exception{File f=new File(vault.privateDirectory(),FILE);if(f.length()<28||f.length()>16*1024*1024)throw new IOException("Registro de transferência inválido. Os arquivos foram preservados.");byte[] plain=vault.openState(PURPOSE,Files.readAllBytes(f.toPath()));try{state=new JSONObject(new String(plain,StandardCharsets.UTF_8));if(state.getInt("version")!=1)throw new IOException("Versão de transferência não suportada.");}finally{Arrays.fill(plain,(byte)0);}}
    private void begin(String kind)throws Exception{if(pending())throw new IOException("Retome ou encerre a operação pendente antes de iniciar outra.");state=new JSONObject().put("version",1).put("kind",kind).put("items",new JSONArray()).put("retained",0);}
    void prepareImport(List<Uri> sources,String parent)throws Exception{
        begin("import");Set<String> names=new HashSet<>();for(VaultEngine.Entry e:vault.list())if(!e.isTrashed()&&e.parent.equals(parent))names.add(e.name.toLowerCase(Locale.ROOT));
        for(Uri uri:sources){String name=unique(displayName(uri),names);state.getJSONArray("items").put(new JSONObject().put("id",UUID.randomUUID().toString()).put("uri",uri.toString()).put("name",name).put("mime",Objects.toString(resolver.getType(uri),"application/octet-stream")).put("parent",parent));}
        save();
    }
    void prepareBackup(Uri tree)throws Exception{begin("backup");state.put("tree",tree.toString());save();}
    void prepareExport(List<String> ids,Uri target,boolean tree,boolean move)throws Exception{
        begin("export");state.put("move",move);JSONArray roots=new JSONArray();state.put("roots",roots);
        for(VaultEngine.Entry root:vault.selectionRoots(ids)){
            roots.put(new JSONObject().put("id",root.id));List<VaultEngine.Entry> branch=orderedBranch(root);
            for(VaultEngine.Entry entry:branch){JSONObject item=new JSONObject().put("id",entry.id).put("root",root.id).put("parent",entry.parent).put("name",entry.name).put("mime",entry.mime).put("folder",entry.folder).put("size",entry.size).put("digest",android.util.Base64.encodeToString(entry.digest,android.util.Base64.NO_WRAP));
                if(entry.id.equals(root.id))item.put(tree?"destinationParent":"output",tree?DocumentsContract.buildDocumentUriUsingTree(target,DocumentsContract.getTreeDocumentId(target)).toString():target.toString());
                state.getJSONArray("items").put(item);
            }
        }save();
    }
    private List<VaultEngine.Entry> orderedBranch(VaultEngine.Entry root)throws Exception{Map<String,List<VaultEngine.Entry>> children=new HashMap<>();for(VaultEngine.Entry e:vault.activeSubtree(root.id))children.computeIfAbsent(e.parent,k->new ArrayList<>()).add(e);List<VaultEngine.Entry> ordered=new ArrayList<>();ArrayDeque<VaultEngine.Entry> queue=new ArrayDeque<>();queue.add(root);while(!queue.isEmpty()){VaultEngine.Entry e=queue.remove();ordered.add(e);queue.addAll(children.getOrDefault(e.id,Collections.emptyList()));}return ordered;}
    String execute(VaultEngine.Progress progress)throws Exception{
        load();String kind=state.getString("kind");
        if(kind.equals("import"))importAll(progress);
        else if(kind.equals("export"))exportAll(progress);
        else if(kind.equals("backup")){VaultBackupSet.backup(vault,new DocumentStore(context,Uri.parse(state.getString("tree"))),progress);vault.markBackupCompleted();}
        else throw new IOException("Operação desconhecida. Os arquivos foram preservados.");
        int retained=state.optInt("retained");finish();return kind.equals("backup")?"Backup salvo e verificado. Somente o conteúdo atual do cofre entra na restauração mais recente. Versões anteriores continuam preservadas.":"Operação concluída e verificada."+(retained>0?" "+retained+" original(is) continuam na origem porque não foi possível removê-los com segurança.":"");
    }
    void discard()throws Exception{
        if(!vault.isUnlocked())throw new IOException("Desbloqueie o cofre para encerrar a operação.");
        try{load();}catch(Exception damaged){File journal=new File(vault.privateDirectory(),FILE);Files.move(journal.toPath(),new File(vault.privateDirectory(),"operation-damaged-"+UUID.randomUUID()+".state").toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);syncDirectory();return;}
        if(state.getString("kind").equals("import")){JSONArray items=state.getJSONArray("items");for(int i=0;i<items.length();i++)vault.discardPendingImport(items.getJSONObject(i).getString("id"));}finish();
    }
    private void finish()throws IOException{Files.deleteIfExists(new File(vault.privateDirectory(),FILE).toPath());syncDirectory();}
    private void importAll(VaultEngine.Progress progress)throws Exception{
        JSONArray items=state.getJSONArray("items");
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);if(item.optBoolean("done"))continue;progress.phase("Protegendo arquivo "+(i+1)+" de "+items.length());Uri source=Uri.parse(item.getString("uri"));String id=item.getString("id");
            if(!item.optBoolean("protected")){
                try(InputStream in=read(source)){vault.importFileResumable(in,item.getString("name"),item.getString("mime"),item.getString("parent"),id,progress);}
                vault.verify(id,progress);syncDirectory();item.put("protected",true);save();
            }
            // Reverify both sides on every retry. A changed/missing external document is never removed.
            vault.verify(id,progress);boolean removed=false;
            try(InputStream in=read(source)){
                if(vault.matches(id,in,progress)){progress.update(vault.get(id).size);removed=DocumentsContract.isDocumentUri(context,source)&&DocumentsContract.deleteDocument(resolver,source);}
            }catch(VaultEngine.TransferPausedException pause){throw pause;}catch(IOException|SecurityException failure){/* Keep the verified encrypted copy and report the original as retained. */}
            if(!removed)state.put("retained",state.optInt("retained")+1);item.put("done",true);save();vault.discardPendingImport(id);
        }
    }
    private void exportAll(VaultEngine.Progress progress)throws Exception{
        JSONArray items=state.getJSONArray("items"),roots=state.getJSONArray("roots");Map<String,JSONObject> byId=new HashMap<>();for(int i=0;i<items.length();i++)byId.put(items.getJSONObject(i).getString("id"),items.getJSONObject(i));
        for(int r=0;r<roots.length();r++){
            JSONObject root=roots.getJSONObject(r);if(root.optBoolean("done"))continue;String rootId=root.getString("id");
            if(root.optBoolean("verified")&&!exists(rootId)){root.put("done",true);save();continue;}
            checkBranch(rootId,items);
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);if(!rootId.equals(item.getString("root")))continue;VaultEngine.Entry entry=boundEntry(item);progress.phase("Retirando arquivo "+(i+1)+" de "+items.length());
                if(!item.has("output")){
                    String parent=item.has("destinationParent")?item.getString("destinationParent"):byId.get(item.getString("parent")).getString("output");
                    Uri created=createDestination(Uri.parse(parent),entry.folder?DocumentsContract.Document.MIME_TYPE_DIR:entry.mime,entry.name);item.put("output",created.toString());save();
                }
                if(!entry.folder)exportFile(entry,Uri.parse(item.getString("output")),progress);
            }
            // Check every output again immediately before deleting a whole subtree.
            checkBranch(rootId,items);
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(rootId.equals(item.getString("root"))&&!item.getBoolean("folder")){boundEntry(item);try(InputStream in=read(Uri.parse(item.getString("output")))){if(!vault.matches(item.getString("id"),in,progress))throw new IOException("O destino mudou. O original permanece no cofre.");}}}
            root.put("verified",true);save();progress.update(0);if(state.getBoolean("move"))vault.delete(rootId);root.put("done",true);save();
        }
    }
    private boolean exists(String id){try{vault.get(id);return true;}catch(Exception missing){return false;}}
    private VaultEngine.Entry boundEntry(JSONObject item)throws Exception{
        VaultEngine.Entry entry=vault.get(item.getString("id"));if(entry.isTrashed()||entry.folder!=item.getBoolean("folder")||entry.size!=item.getLong("size")||!entry.parent.equals(item.getString("parent"))||!entry.name.equals(item.getString("name"))||!MessageDigest.isEqual(entry.digest,android.util.Base64.decode(item.getString("digest"),android.util.Base64.NO_WRAP)))throw new IOException("O item mudou desde a pausa. Encerre a operação pendente e selecione novamente.");return entry;
    }
    private void checkBranch(String root,JSONArray items)throws Exception{Set<String> planned=new HashSet<>();for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(root.equals(item.getString("root"))){boundEntry(item);planned.add(item.getString("id"));}}Set<String> actual=new HashSet<>();for(VaultEngine.Entry entry:vault.activeSubtree(root))actual.add(entry.id);if(!actual.equals(planned))throw new IOException("A pasta mudou desde a pausa. Todos os originais foram preservados.");}
    private void exportFile(VaultEngine.Entry entry,Uri target,VaultEngine.Progress progress)throws Exception{
        byte[] expected=new byte[1024*1024],actual=new byte[1024*1024];
        try(ParcelFileDescriptor fd=resolver.openFileDescriptor(target,"rw")){
            if(fd==null)throw new IOException("Não foi possível abrir o destino.");
            // Require a seekable local destination. Never truncate an existing partial or append blindly.
            try(FileOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd);VaultEngine.RandomReader source=vault.openRandomAccess(entry.id,vault::isUnlocked)){
                FileChannel channel=out.getChannel();long stored=channel.size();if(stored>entry.size)throw new IOException("O destino contém dados diferentes. O original foi preservado.");
                long position=0;try(InputStream existing=read(target)){while(position<stored){int count=(int)Math.min(expected.length,stored-position);int n=source.readAt(position,expected,0,count);if(n!=count)throw new EOFException();new DataInputStream(existing).readFully(actual,0,count);for(int k=0;k<count;k++)if(expected[k]!=actual[k])throw new IOException("A cópia parcial foi alterada. O original foi preservado.");position+=count;progress.update(position);}}
                channel.position(stored);long synced=stored;
                while(position<entry.size){progress.update(position);int count=source.readAt(position,expected,0,(int)Math.min(expected.length,entry.size-position));if(count<=0)throw new EOFException();ByteBuffer buffer=ByteBuffer.wrap(expected,0,count);while(buffer.hasRemaining())channel.write(buffer);position+=count;if(position-synced>=8L*1024*1024){channel.force(true);synced=position;}}
                channel.force(true);
            }
        }finally{Arrays.fill(expected,(byte)0);Arrays.fill(actual,(byte)0);}
        try(InputStream in=read(target)){if(!vault.matches(entry.id,in,progress))throw new IOException("A verificação do destino falhou. O original permanece no cofre.");}
    }
    private InputStream read(Uri uri)throws IOException{InputStream in=resolver.openInputStream(uri);if(in==null)throw new IOException("Não foi possível ler o arquivo. Confira a permissão do destino.");return in;}
    private String displayName(Uri uri){try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}return "Arquivo";}
    private static String unique(String requested,Set<String> used){String clean=requested.replaceAll("[\\\\/\\p{Cntrl}]","_");if(clean.length()>180)clean=clean.substring(0,180);if(clean.isEmpty()||clean.equals(".")||clean.equals(".."))clean="Arquivo";String candidate=clean;int dot=clean.lastIndexOf('.'),n=2;String base=dot>0?clean.substring(0,dot):clean,ext=dot>0?clean.substring(dot):"";while(!used.add(candidate.toLowerCase(Locale.ROOT)))candidate=base+" ("+(n++)+")"+ext;return candidate;}
    private Uri createDestination(Uri parent,String mime,String requested)throws IOException{
        Set<String> used=new HashSet<>(),ids=new HashSet<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));try(Cursor c=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){if(c==null)throw new IOException("Não foi possível conferir a pasta.");while(c.moveToNext()){used.add(c.getString(0).toLowerCase(Locale.ROOT));ids.add(c.getString(1));}}
        Uri created=DocumentsContract.createDocument(resolver,parent,mime.isEmpty()?"application/octet-stream":mime,unique(requested,used));if(created==null||ids.contains(DocumentsContract.getDocumentId(created)))throw new IOException("O destino não criou um arquivo novo. A gravação foi recusada.");return created;
    }
    private void syncDirectory()throws IOException{try{FileDescriptor fd=android.system.Os.open(vault.privateDirectory().getPath(),android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}}catch(android.system.ErrnoException e){throw new IOException("Não foi possível confirmar a gravação do cofre.",e);}}
}
