package com.amzstudios.cofre;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.text.method.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PICK_FILES=10, SAVE_FILE=11, SAVE_BACKUP=12, RESTORE=13;
    private static final int BG=0xff0d1118, SURFACE=0xff171e29, BORDER=0xff2a3545, INK=0xfff0f4fb, MUTED=0xffa4b0c2, ACCENT=0xffadceff;
    private static final long LOCK_DELAY=120000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private VaultEngine vault;
    private LinearLayout page;
    private EditText password, confirmation, search;
    private TextView status, counter;
    private ListView list;
    private FileAdapter adapter;
    private List<VaultEngine.Entry> all=new ArrayList<>(), visible=new ArrayList<>();
    private String folder="", pendingId;
    private char[] restorePassword;
    private boolean unlocked, busy, external, lockAfter, stopped;
    private ProgressDialog progress;
    private Dialog preview;
    private Runnable previewCleanup;
    private final List<Dialog> dialogs=new ArrayList<>();
    private final Runnable timeout=()->requestLock();
    private long lastProgress;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        vault=new VaultEngine(new File(getFilesDir(),"vault-v1"));
        clearPreviews(); showLocked();
    }
    @Override protected void onResume() {
        super.onResume(); stopped=false;
        // A grant to another viewer is temporary, and its cleartext cache is removed when returning.
        if(!unlocked) clearPreviews();
        if(unlocked) armLock();
    }
    @Override protected void onStop() {
        super.onStop(); stopped=true;
        if(!external) requestLock();
    }
    @Override public void onUserInteraction() { super.onUserInteraction(); if(unlocked&&!busy) armLock(); }
    @Override protected void onDestroy() {
        ui.removeCallbacks(timeout); clearRestorePassword();
        if(!busy) vault.lock(); else lockAfter=true;
        worker.shutdown(); super.onDestroy();
    }
    private void armLock() { ui.removeCallbacks(timeout); ui.postDelayed(timeout,LOCK_DELAY); }
    private void requestLock() {
        ui.removeCallbacks(timeout); unlocked=false; all.clear(); visible.clear();
        closeDialogs(); closePreview();
        if(busy) { lockAfter=true; return; }
        vault.lock(); folder=""; showLocked();
    }
    private void frame() {
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(14),dp(20),dp(12)); page.setBackgroundColor(BG);
        setContentView(page);
    }
    private void showLocked() {
        if(isFinishing()||isDestroyed()) return;
        frame();
        ScrollView scroll=new ScrollView(this); LinearLayout content=column(); content.setPadding(dp(4),dp(30),dp(4),dp(24)); scroll.addView(content); page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        ImageView mark=new ImageView(this); mark.setImageResource(R.drawable.ic_vault); content.addView(mark,new LinearLayout.LayoutParams(dp(76),dp(76)));
        TextView brand=text("AMZ STUDIOS  /  OFFLINE",12,MUTED); brand.setLetterSpacing(.12f); add(content,brand,14);
        add(content,text("Cofre AMZ",36,INK),12);
        add(content,text(vault.exists()?"Seus arquivos, guardados.\nDesbloqueie para acessar suas pastas.":"Um lugar privado para seus arquivos.\nCrie sua senha para começar.",16,MUTED),10);
        LinearLayout form=column(); form.setPadding(dp(18),dp(18),dp(18),dp(20)); form.setBackground(box(SURFACE,20)); add(content,form,28);
        add(form,text(vault.exists()?"Desbloquear cofre":"Criar cofre",20,INK),0);
        password=input("Senha",true); password.setId(R.id.vault_password); add(form,password,14);
        if(!vault.exists()) { confirmation=input("Repita a senha",true); confirmation.setId(R.id.vault_confirmation); add(form,confirmation,8); }
        CheckBox show=new CheckBox(this); show.setText("Mostrar senha"); show.setTextColor(MUTED); show.setTextSize(14);
        show.setOnCheckedChangeListener((b,on)->{ password.setTransformationMethod(on?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance()); if(!vault.exists()&&confirmation!=null) confirmation.setTransformationMethod(on?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance()); }); add(form,show,3);
        Button enter=button(vault.exists()?"Desbloquear":"Criar meu cofre",true); enter.setId(R.id.vault_unlock); add(form,enter,10);
        status=text("",14,0xffffb4ab); add(form,status,8);
        enter.setOnClickListener(v->authenticate());
        password.setOnEditorActionListener((v,a,event)->{ if(vault.exists()){authenticate();return true;} return false; });
        if(!vault.exists()) {
            add(content,text("Use pelo menos 10 caracteres. Guarde sua senha: não existe recuperação pela internet. Faça um backup do cofre antes de trocar de celular ou desinstalar o app.",14,MUTED),18);
            Button restore=button("Restaurar backup .amzcofre",false); add(content,restore,14); restore.setOnClickListener(v->askRestore());
        } else {
            add(content,text("Sem conta. Sem conexão. Os arquivos ficam neste aparelho.",14,MUTED),20);
            Button help=button("Sobre minha senha e meus arquivos",false); add(content,help,12); help.setOnClickListener(v->help());
        }
        add(content,text("Android 8 ou superior  ·  v1.0.0",12,MUTED),28);
    }
    private void authenticate() {
        if(busy) return;
        char[] pass=password.getText().toString().toCharArray(); boolean create=!vault.exists();
        if(create&&(pass.length<10||!password.getText().toString().equals(confirmation.getText().toString()))) {
            Arrays.fill(pass,'\0'); status.setText("Use 10 ou mais caracteres e repita a mesma senha."); return;
        }
        password.setText(""); if(confirmation!=null) confirmation.setText("");
        hideKeyboard();
        run(create?"Criando seu cofre…":"Desbloqueando…",()->{
            try { if(create) vault.create(pass); else vault.unlock(pass); }
            finally { Arrays.fill(pass,'\0'); }
        },()->{ unlocked=true; folder=""; showExplorer(); armLock(); },e->notice("Não foi possível desbloquear",create?friendly(e):"Senha incorreta ou cofre danificado. Confira sua senha e tente novamente."));
    }
    private void showExplorer() {
        if(!vault.isUnlocked()||isFinishing()||isDestroyed()) return;
        unlocked=true; all=vault.list(); frame();
        LinearLayout header=row(); TextView title=text("Cofre AMZ",26,INK); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button more=button("•••",false); more.setContentDescription("Opções do cofre"); header.addView(more,new LinearLayout.LayoutParams(dp(54),dp(46))); more.setOnClickListener(v->options(more));
        Button lock=button("Bloquear",false); header.addView(lock,new LinearLayout.LayoutParams(dp(104),dp(46))); lock.setOnClickListener(v->requestLock()); page.addView(header);
        counter=text("",14,MUTED); add(page,counter,8);
        search=input("Buscar arquivos e pastas",false); search.setSingleLine(true); search.setId(R.id.vault_search); add(page,search,18);
        search.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){refresh();} public void afterTextChanged(Editable e){} });
        HorizontalScrollView crumbs=new HorizontalScrollView(this); crumbs.setHorizontalScrollBarEnabled(false); LinearLayout trail=row(); crumbs.addView(trail);
        Button home=button("Meus arquivos",false); trail.addView(home); home.setOnClickListener(v->{folder="";showExplorer();});
        List<VaultEngine.Entry> parents=new ArrayList<>(); String current=folder;
        try { while(!current.isEmpty()){VaultEngine.Entry e=vault.get(current);parents.add(0,e);current=e.parent;} }
        catch(Exception e){folder="";}
        for(VaultEngine.Entry e:parents){trail.addView(text(" / ",16,MUTED));Button b=button(e.name,false);trail.addView(b);b.setOnClickListener(v->{folder=e.id;showExplorer();});}
        add(page,crumbs,12);
        LinearLayout actions=row(); Button move=button("+ Mover arquivos",true); move.setId(R.id.vault_import); actions.addView(move,new LinearLayout.LayoutParams(0,dp(52),1)); move.setOnClickListener(v->chooseFiles());
        Button newFolder=button("+ Pasta",false); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(dp(100),dp(52));np.leftMargin=dp(8);actions.addView(newFolder,np);newFolder.setOnClickListener(v->nameDialog(null));add(page,actions,10);
        list=new ListView(this); list.setDividerHeight(0); list.setBackgroundColor(BG); list.setPadding(0,dp(8),0,0); list.setClipToPadding(false); adapter=new FileAdapter(); list.setAdapter(adapter); page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,position,id)->{VaultEngine.Entry e=visible.get(position);if(e.folder){folder=e.id;showExplorer();}else fileMenu(e);});
        list.setOnItemLongClickListener((p,v,position,id)->{fileMenu(visible.get(position));return true;});
        TextView footer=text("Só neste aparelho  ·  Bloqueio automático em 2 min",12,MUTED); add(page,footer,8);
        refresh();
    }
    private void refresh() {
        if(!unlocked||adapter==null) return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT); visible=new ArrayList<>();
        for(VaultEngine.Entry e:all) if(query.isEmpty()?e.parent.equals(folder):e.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(e);
        Collections.sort(visible,(a,b)->a.folder!=b.folder?(a.folder?-1:1):a.name.compareToIgnoreCase(b.name));
        long bytes=0;int count=0;for(VaultEngine.Entry e:all)if(!e.folder){bytes+=e.size;count++;}
        counter.setText(count+" arquivo"+(count==1?"":"s")+" protegido"+(count==1?"":"s")+"  ·  "+size(bytes));
        adapter.notifyDataSetChanged();
    }
    private class FileAdapter extends BaseAdapter {
        public int getCount(){return visible.isEmpty()?1:visible.size();}
        public Object getItem(int p){return visible.isEmpty()?null:visible.get(p);}
        public long getItemId(int p){return p;}
        @Override public boolean isEnabled(int p){return !visible.isEmpty();}
        public View getView(int p,View old,android.view.ViewGroup parent){
            if(visible.isEmpty()){
                LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(58),dp(20),dp(50));
                TextView icon=text("□",56,ACCENT);empty.addView(icon);
                TextView heading=text(search.getText().length()>0?"Nenhum resultado":"Esta pasta está vazia",21,INK);heading.setGravity(Gravity.CENTER);add(empty,heading,14);
                TextView hint=text(search.getText().length()>0?"Tente outro nome.":"Toque em Mover arquivos para\nguardar seus primeiros arquivos.",15,MUTED);hint.setGravity(Gravity.CENTER);add(empty,hint,8);return empty;
            }
            VaultEngine.Entry e=visible.get(p);LinearLayout outer=column();outer.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);outer.setPadding(0,dp(4),0,dp(4));LinearLayout cell=row();cell.setPadding(dp(14),dp(16),dp(10),dp(16));cell.setBackground(box(SURFACE,14));outer.addView(cell);
            TextView badge=text(e.folder?"▰":type(e),e.folder?28:12,ACCENT);badge.setTypeface(null,Typeface.BOLD);badge.setGravity(Gravity.CENTER);badge.setBackground(box(0xff23324a,10));cell.addView(badge,new LinearLayout.LayoutParams(dp(45),dp(45)));
            LinearLayout names=column();LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(0,-2,1);nameParams.leftMargin=dp(14);cell.addView(names,nameParams);
            TextView name=text(e.name,16,INK);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name);
            String detail=e.folder?"Pasta · "+children(e.id)+" itens":size(e.size)+" · "+DateFormat.getDateInstance(DateFormat.SHORT,new Locale("pt","BR")).format(new Date(e.modified));
            add(names,text(detail,12,MUTED),5);
            Button menu=button("⋮",false);menu.setContentDescription("Opções de "+e.name);cell.addView(menu,new LinearLayout.LayoutParams(dp(44),dp(46)));menu.setOnClickListener(v->fileMenu(e));return outer;
        }
    }
    private int children(String id){int n=0;for(VaultEngine.Entry e:all)if(e.parent.equals(id))n++;return n;}
    private String type(VaultEngine.Entry e){if(e.mime.startsWith("image/"))return "IMG";if(e.mime.startsWith("video/"))return "VID";if(e.mime.startsWith("audio/"))return "ÁUD";int dot=e.name.lastIndexOf('.');return dot>=0?e.name.substring(dot+1).toUpperCase(Locale.ROOT).substring(0,Math.min(4,e.name.length()-dot-1)):"ARQ";}
    private void options(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);String[] labels={"Salvar backup criptografado","Alterar senha","Ajuda e informações"};for(String label:labels)menu.getMenu().add(label);
        menu.setOnMenuItemClickListener(item->{if(item.getTitle().equals(labels[0]))saveBackup();else if(item.getTitle().equals(labels[1]))changePassword();else help();return true;});menu.show();
    }
    private void fileMenu(VaultEngine.Entry e){
        if(!unlocked||busy)return;
        String[] labels=e.folder?new String[]{"Abrir pasta","Renomear","Mover para outra pasta","Excluir pasta"}:new String[]{"Abrir arquivo","Mover para fora do cofre","Salvar uma cópia fora","Renomear","Mover para outra pasta","Excluir do cofre"};
        track(new AlertDialog.Builder(this).setTitle(e.name).setItems(labels,(d,which)->{
            if(e.folder){if(which==0){folder=e.id;showExplorer();}else if(which==1)nameDialog(e);else if(which==2)chooseFolder(e);else confirmDelete(e);}
            else{if(which==0)openFile(e);else if(which==1||which==2)exportFile(e,which==1);else if(which==3)nameDialog(e);else if(which==4)chooseFolder(e);else confirmDelete(e);}
        }).setNegativeButton("Fechar",null).show());
    }
    private void nameDialog(VaultEngine.Entry entry){
        EditText name=input(entry==null?"Nome da pasta":"Nome",false); if(entry!=null)name.setText(entry.name);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(entry==null?"Nova pasta":"Renomear").setView(padded(name)).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String value=name.getText().toString();d.dismiss();run("Salvando…",()->{if(entry==null)vault.folder(folder,value);else vault.rename(entry.id,value);},()->showExplorer(),this::error);}));track(d);d.show();
    }
    private void chooseFolder(VaultEngine.Entry entry){
        List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("");names.add("Meus arquivos");
        for(VaultEngine.Entry e:all)if(e.folder&&!e.id.equals(entry.id)){ids.add(e.id);names.add(path(e));}
        track(new AlertDialog.Builder(this).setTitle("Mover para a pasta").setItems(names.toArray(new String[0]),(d,n)->run("Movendo…",()->vault.move(entry.id,ids.get(n)),()->showExplorer(),this::error)).setNegativeButton("Cancelar",null).show());
    }
    private String path(VaultEngine.Entry e){String s=e.name,p=e.parent;try{while(!p.isEmpty()){VaultEngine.Entry parent=vault.get(p);s=parent.name+" / "+s;p=parent.parent;}}catch(Exception ignored){}return s;}
    private void confirmDelete(VaultEngine.Entry e){
        track(new AlertDialog.Builder(this).setTitle("Excluir "+(e.folder?"pasta":"arquivo")+"?").setMessage(e.name+"\n\n"+(e.folder?"Os arquivos e subpastas também serão excluídos. ":"")+"Esta ação não pode ser desfeita. Uma cópia já retirada do cofre não será apagada.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->run("Excluindo…",()->vault.delete(e.id),()->showExplorer(),this::error)).show());
    }
    private void chooseFiles(){
        track(new AlertDialog.Builder(this).setTitle("Mover para o cofre").setMessage("Escolha os arquivos no aparelho. O app criptografa, confere a gravação e só então pede a remoção do original.\n\nSe o local de origem não permitir apagar, o app avisará que o original continua lá.").setNegativeButton("Cancelar",null).setPositiveButton("Escolher arquivos",(d,w)->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);launch(i,PICK_FILES);
        }).show());
    }
    private void launch(Intent intent,int code){
        external=true;
        try{startActivityForResult(intent,code);}catch(ActivityNotFoundException e){external=false;notice("Seletor indisponível","Ative o aplicativo Arquivos do Android para escolher o destino.");}
    }
    private boolean removeOnExport;
    private void exportFile(VaultEngine.Entry e,boolean move){
        pendingId=e.id;removeOnExport=move;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(e.mime.isEmpty()?"application/octet-stream":e.mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,e.name).putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        launch(i,SAVE_FILE);
    }
    private void saveBackup(){
        track(new AlertDialog.Builder(this).setTitle("Backup criptografado").setMessage("O backup inclui todos os arquivos e pastas, protegidos pela senha atual. Guarde-o fora do aplicativo para poder restaurar em outro celular.\n\nSe trocar a senha depois, este backup continuará usando a senha antiga.").setNegativeButton("Cancelar",null).setPositiveButton("Escolher destino",(d,w)->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Cofre-AMZ-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.ROOT).format(new Date())+".amzcofre");launch(i,SAVE_BACKUP);
        }).show());
    }
    private void askRestore(){
        EditText pass=input("Senha usada no backup",true);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Restaurar cofre").setMessage("Escolha o backup .amzcofre e use a senha que ele tinha quando foi salvo.").setView(padded(pass)).setNegativeButton("Cancelar",null).setPositiveButton("Escolher backup",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(pass.length()==0){pass.setError("Digite a senha do backup");return;}clearRestorePassword();restorePassword=pass.getText().toString().toCharArray();pass.setText("");d.dismiss();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);launch(i,RESTORE);}));track(d);d.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);external=false;stopped=false;
        if(result!=RESULT_OK||data==null){if(request==RESTORE)clearRestorePassword();if(unlocked)armLock();return;}
        if(request==RESTORE){
            Uri uri=data.getData();if(uri==null||restorePassword==null){clearRestorePassword();return;}char[] pass=restorePassword;restorePassword=null;
            run("Restaurando e verificando arquivos…",()->{try(InputStream in=read(uri)){vault.restore(in,pass);}finally{Arrays.fill(pass,'\0');}},()->{unlocked=true;showExplorer();armLock();notice("Cofre restaurado","Todos os arquivos foram verificados. Guarde seu backup em um local seguro.");},e->notice("Não foi possível restaurar","Confira a senha, a integridade do backup e o espaço disponível. "+friendly(e)));return;
        }
        if(!unlocked){notice("Cofre bloqueado","Desbloqueie e selecione os arquivos novamente. Nenhum original foi removido.");return;}
        if(request==PICK_FILES){
            List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int n=0;n<data.getClipData().getItemCount();n++)uris.add(data.getClipData().getItemAt(n).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            importFiles(uris);return;
        }
        Uri uri=data.getData();if(uri==null)return;
        if(request==SAVE_FILE){
            String id=pendingId;boolean move=removeOnExport;
            run("Salvando e conferindo o arquivo…",()->{
                try(OutputStream out=write(uri)){vault.exportFile(id,out,this::progressBytes);}
                try(InputStream in=read(uri)){if(!vault.matches(id,in))throw new IOException("A cópia salva não passou na verificação. O original permanece no cofre.");}
                if(move)vault.delete(id);
            },()->{showExplorer();notice(move?"Arquivo retirado":"Cópia salva",move?"O arquivo está no destino escolhido e foi removido do cofre.":"A cópia normal está no destino escolhido. A versão do cofre foi mantida.");},e->{error(e);});
        }else if(request==SAVE_BACKUP){
            run("Salvando backup criptografado…",()->{try(OutputStream out=write(uri)){vault.backup(out);}try(InputStream in=read(uri)){vault.verifyBackup(in);}},()->notice("Backup salvo e verificado","Você pode transferir este arquivo .amzcofre para outro celular e restaurar com a senha atual."),this::error);
        }
    }
    private void importFiles(List<Uri> uris){
        String parent=folder;StringBuilder report=new StringBuilder();int[] moved={0},retained={0},failed={0};
        run("Movendo arquivos para o cofre…",()->{
            for(Uri uri:uris){
                String name=displayName(uri);VaultEngine.Entry entry;
                try{
                    String mime=getContentResolver().getType(uri);String unique=uniqueName(parent,name);
                    try(InputStream input=read(uri)){entry=vault.importFile(input,unique,mime,parent,this::progressBytes);}
                }catch(Exception e){failed[0]++;report.append("\n• ").append(name).append(": não importado; original preservado. ").append(friendly(e));continue;}
                try{
                    // Detect a source changed since encryption; never remove the newly changed source.
                    // Flush the directory entry too, before removing an original outside the app.
                    FileDescriptor directory=android.system.Os.open(new File(getFilesDir(),"vault-v1").getPath(),android.system.OsConstants.O_RDONLY,0);
                    try{android.system.Os.fsync(directory);}finally{android.system.Os.close(directory);}
                    try(InputStream input=read(uri)){if(!vault.matches(entry.id,input))throw new IOException("O original mudou durante a transferência.");}
                    if(!DocumentsContract.isDocumentUri(this,uri)||!DocumentsContract.deleteDocument(getContentResolver(),uri))throw new IOException("O local de origem não permitiu apagar.");
                    moved[0]++;
                }catch(Exception e){retained[0]++;report.append("\n• ").append(name).append(": protegido no cofre; original ainda está na origem.");}
            }
        },()->{showExplorer();String summary=moved[0]+" movido(s). "+retained[0]+" protegido(s), com original mantido. "+failed[0]+" não importado(s).";notice(retained[0]>0?"Confira os originais":"Transferência concluída",summary+report.toString()+(retained[0]>0?"\n\nVocê pode apagar os originais pelo aplicativo Arquivos. A cópia criptografada já foi verificada.":""));},this::error);
    }
    private String uniqueName(String parent,String requested){
        String clean=requested.replaceAll("[\\\\/\\p{Cntrl}]","_");if(clean.length()>180)clean=clean.substring(0,180);if(clean.isEmpty()||clean.equals(".")||clean.equals(".."))clean="Arquivo";
        String candidate=clean;int n=2;Set<String> used=new HashSet<>();for(VaultEngine.Entry e:vault.list())if(e.parent.equals(parent))used.add(e.name.toLowerCase(Locale.ROOT));
        int dot=clean.lastIndexOf('.');String base=dot>0?clean.substring(0,dot):clean,ext=dot>0?clean.substring(dot):"";
        while(used.contains(candidate.toLowerCase(Locale.ROOT)))candidate=base+" ("+(n++)+")"+ext;return candidate;
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return "Arquivo";
    }
    private InputStream read(Uri uri)throws IOException{InputStream in=getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Não foi possível ler o arquivo.");return in;}
    private OutputStream write(Uri uri)throws IOException{OutputStream out=getContentResolver().openOutputStream(uri,"wt");if(out==null)throw new IOException("Não foi possível salvar o arquivo.");return out;}
    private void changePassword(){
        LinearLayout fields=column();EditText pass=input("Nova senha (10 ou mais caracteres)",true),again=input("Repita a nova senha",true);fields.addView(pass);fields.addView(again);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Alterar senha").setMessage("Backups anteriores continuam usando a senha antiga.").setView(padded(fields)).setNegativeButton("Cancelar",null).setPositiveButton("Salvar senha",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(pass.length()<10||!pass.getText().toString().equals(again.getText().toString())){pass.setError("Use pelo menos 10 caracteres e repita a mesma senha.");return;}char[] chars=pass.getText().toString().toCharArray();pass.setText("");again.setText("");d.dismiss();run("Atualizando senha…",()->{try{vault.changePassword(chars);}finally{Arrays.fill(chars,'\0');}},()->notice("Senha alterada","Use a nova senha para desbloquear este cofre. Salve um novo backup."),this::error);}));track(d);d.show();
    }
    private void openFile(VaultEngine.Entry e){
        if(e.size>new File(getCacheDir().getPath()).getUsableSpace()-16L*1024*1024){notice("Pouco espaço","Libere espaço para visualizar este arquivo.");return;}
        File dir=new File(getCacheDir(),"preview");dir.mkdirs();File file=new File(dir,e.name);
        run("Abrindo arquivo…",()->{try(FileOutputStream out=new FileOutputStream(file)){vault.exportFile(e.id,out,this::progressBytes);}},()->{
            try{
                if(e.mime.startsWith("image/"))showImage(e,file);
                else if(e.mime.equals("application/pdf")||e.name.toLowerCase(Locale.ROOT).endsWith(".pdf"))showPdf(e,file);
                else if(e.mime.startsWith("text/")||e.name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|csv|json|log)$"))showText(e,file);
                else if(e.mime.startsWith("video/")||e.mime.startsWith("audio/"))showMedia(e,file);
                else externalPreview(e,file);
            }catch(Exception err){clearPreviews();error(err);}
        },err->{clearPreviews();error(err);});
    }
    private LinearLayout previewFrame(String title){
        closePreview();Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content=column();content.setPadding(dp(16),dp(16),dp(16),dp(16));content.setBackgroundColor(BG);LinearLayout bar=row();TextView label=text(title,19,INK);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button close=button("Fechar",false);bar.addView(close);close.setOnClickListener(v->closePreview());content.addView(bar);dialog.setContentView(content);
        dialog.setOnDismissListener(d->{if(previewCleanup!=null){previewCleanup.run();previewCleanup=null;}clearPreviews();preview=null;});preview=dialog;return content;
    }
    private void showImage(VaultEngine.Entry entry,File file)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);if(bounds.outWidth<1)throw new IOException("Formato de imagem não reconhecido.");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/options.inSampleSize>2000||bounds.outHeight/options.inSampleSize>2000)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("Não foi possível abrir a imagem.");LinearLayout content=previewFrame(entry.name);ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(bitmap);content.addView(image,new LinearLayout.LayoutParams(-1,0,1));previewCleanup=()->{image.setImageDrawable(null);bitmap.recycle();};preview.show();
    }
    private void showText(VaultEngine.Entry entry,File file)throws Exception{
        if(file.length()>2*1024*1024){externalPreview(entry,file);return;}
        byte[] data=java.nio.file.Files.readAllBytes(file.toPath());LinearLayout content=previewFrame(entry.name);ScrollView scroll=new ScrollView(this);TextView text=text(new String(data,java.nio.charset.StandardCharsets.UTF_8),16,INK);text.setTypeface(Typeface.MONOSPACE);text.setPadding(dp(8),dp(18),dp(8),dp(18));text.setTextIsSelectable(true);scroll.addView(text);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Arrays.fill(data,(byte)0);preview.show();
    }
    private void showPdf(VaultEngine.Entry entry,File file)throws Exception{
        ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer;
        try{renderer=new PdfRenderer(fd);}catch(Exception e){fd.close();throw e;}
        LinearLayout content=previewFrame(entry.name);ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);content.addView(image,new LinearLayout.LayoutParams(-1,0,1));LinearLayout nav=row();Button previous=button("Anterior",false),next=button("Próxima",false);TextView number=text("",14,MUTED);number.setGravity(Gravity.CENTER);nav.addView(previous);nav.addView(number,new LinearLayout.LayoutParams(0,-2,1));nav.addView(next);content.addView(nav);int[] index={0};Bitmap[] current={null};
        Runnable render=()->{try(PdfRenderer.Page p=renderer.openPage(index[0])){int width=Math.min(1600,getResources().getDisplayMetrics().widthPixels);int height=Math.max(1,Math.min(2400,(int)((long)p.getHeight()*width/p.getWidth())));Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);image.setImageBitmap(bitmap);if(current[0]!=null)current[0].recycle();current[0]=bitmap;number.setText((index[0]+1)+" / "+renderer.getPageCount());previous.setEnabled(index[0]>0);next.setEnabled(index[0]+1<renderer.getPageCount());}catch(Exception e){error(e);}};
        previous.setOnClickListener(v->{index[0]--;render.run();});next.setOnClickListener(v->{index[0]++;render.run();});previewCleanup=()->{renderer.close();try{fd.close();}catch(Exception ignored){}image.setImageDrawable(null);if(current[0]!=null)current[0].recycle();};render.run();preview.show();
    }
    private void showMedia(VaultEngine.Entry entry,File file){
        LinearLayout content=previewFrame(entry.name);VideoView video=new VideoView(this);content.addView(video,new LinearLayout.LayoutParams(-1,0,1));MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);video.setVideoPath(file.getAbsolutePath());video.setOnPreparedListener(p->{video.start();controls.show(0);});video.setOnErrorListener((p,w,e)->{notice("Formato não suportado","Este aparelho não conseguiu reproduzir o arquivo. Você pode salvar uma cópia e abri-la em outro aplicativo.");return true;});previewCleanup=()->{controls.hide();video.stopPlayback();};preview.show();
    }
    private void externalPreview(VaultEngine.Entry entry,File file){
        track(new AlertDialog.Builder(this).setTitle("Abrir em outro aplicativo?").setMessage("O aplicativo escolhido terá acesso a uma cópia descriptografada e poderá salvá-la. O acesso temporário será encerrado quando você voltar ao Cofre AMZ.").setNegativeButton("Cancelar",(d,w)->clearPreviews()).setPositiveButton("Abrir",(d,w)->{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".preview",file);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,entry.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Arquivo",uri));
            try{external=true;vault.lock();unlocked=false;all.clear();showLocked();startActivityForResult(Intent.createChooser(i,"Abrir arquivo"),20);}catch(Exception err){external=false;clearPreviews();notice("Nenhum aplicativo disponível","Instale um aplicativo compatível com este formato ou retire o arquivo para uma pasta.");}
        }).setOnCancelListener(d->clearPreviews()).show());
    }
    private void closePreview(){if(preview!=null){Dialog old=preview;preview=null;old.dismiss();}}
    private void clearPreviews(){
        File dir=new File(getCacheDir(),"preview");File[] files=dir.listFiles();if(files!=null)for(File f:files){try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".preview",f);revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}f.delete();}
    }
    private void help(){notice("Como funciona o Cofre AMZ","• Funciona offline e não possui permissão de internet.\n\n• Senha, nomes de arquivos, pastas e conteúdo são protegidos com criptografia. A senha não é armazenada.\n\n• Mover arquivos remove o original só depois de verificar a gravação. Alguns locais do Android não permitem apagar; nesses casos, o app avisa. Cópias em outros locais ou na nuvem não são removidas.\n\n• Use Mover para fora do cofre para devolver um arquivo a uma pasta do celular.\n\n• Salve um backup .amzcofre pelo menu. Desinstalar o app ou limpar seus dados apaga o cofre deste aparelho. Sem senha e backup, não há recuperação.\n\n• O bloqueio ocorre ao sair do app e após 2 minutos sem interação. Seletores de arquivos podem permanecer abertos por até 2 minutos.\n\nCofre AMZ 1.0.0 · Android 8+\nAES-256-GCM · PBKDF2-SHA256 (600.000 rodadas)");}
    private interface Job{void execute()throws Exception;}
    private interface Failure{void accept(Exception e);}
    private void run(String label,Job job,Runnable done,Failure failed){
        if(busy)return;busy=true;lockAfter=false;ui.removeCallbacks(timeout);lastProgress=0;
        progress=new ProgressDialog(this);progress.setTitle(label);progress.setMessage("Aguarde. Seus arquivos estão sendo verificados.");progress.setIndeterminate(true);progress.setCancelable(false);progress.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);progress.show();
        worker.execute(()->{Exception failure=null;try{job.execute();}catch(Exception e){failure=e;}Exception outcome=failure;
            ui.post(()->{busy=false;if(progress!=null){progress.dismiss();progress=null;}if(isFinishing()||isDestroyed()){vault.lock();return;}if(lockAfter||stopped){vault.lock();unlocked=false;lockAfter=false;clearPreviews();showLocked();return;}if(outcome==null)done.run();else failed.accept(outcome);if(unlocked)armLock();});});
    }
    private void progressBytes(long bytes){long now=SystemClock.elapsedRealtime();if(now-lastProgress<300)return;lastProgress=now;ui.post(()->{if(progress!=null)progress.setMessage(size(bytes)+" processados. Aguarde a verificação.");});}
    private void error(Exception e){notice("Não foi possível concluir",friendly(e)+"\n\nSe a retirada falhou, a versão do cofre foi preservada. Confira qualquer arquivo parcial no destino antes de apagá-lo.");}
    private String friendly(Exception e){if(e instanceof javax.crypto.AEADBadTagException)return "Senha incorreta ou arquivo danificado.";String m=e.getMessage();return m==null?"Verifique o espaço disponível e a permissão para acessar os arquivos.":m;}
    private void notice(String title,String body){if(!isFinishing()&&!isDestroyed())track(new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Entendi",null).show());}
    private void track(Dialog d){dialogs.removeIf(x->!x.isShowing());dialogs.add(d);if(d.getWindow()!=null)d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private void closeDialogs(){for(Dialog d:new ArrayList<>(dialogs))if(d.isShowing())d.dismiss();dialogs.clear();}
    private void clearRestorePassword(){if(restorePassword!=null)Arrays.fill(restorePassword,'\0');restorePassword=null;}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(),0);}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(3),1);if(size>=20)v.setTypeface(null,Typeface.BOLD);return v;}
    private EditText input(String hint,boolean secret){EditText v=new EditText(this);v.setTextColor(INK);v.setHintTextColor(MUTED);v.setHint(hint);v.setTextSize(16);v.setSingleLine(true);v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setBackground(box(BG,10));v.setMinHeight(dp(52));v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);v.setInputType(secret?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT);return v;}
    private Button button(String label,boolean primary){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextSize(14);v.setTypeface(null,Typeface.BOLD);v.setTextColor(primary?BG:ACCENT);v.setPadding(dp(12),dp(6),dp(12),dp(6));v.setMinHeight(dp(46));v.setMinimumHeight(dp(46));v.setMinWidth(0);v.setMinimumWidth(0);v.setBackground(box(primary?ACCENT:SURFACE,12));return v;}
    private GradientDrawable box(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(color==BG||color==SURFACE)d.setStroke(dp(1),BORDER);return d;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private View padded(View child){LinearLayout p=column();p.setPadding(dp(22),dp(8),dp(22),dp(8));p.addView(child);return p;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(child,p);}
    private static String size(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(new Locale("pt","BR"),"%.1f KB",bytes/1024.0);if(bytes<1024L*1024*1024)return String.format(new Locale("pt","BR"),"%.1f MB",bytes/(1024.0*1024));return String.format(new Locale("pt","BR"),"%.2f GB",bytes/(1024.0*1024*1024));}
    @Override public void onBackPressed(){if(busy)return;if(unlocked&&!folder.isEmpty()){try{folder=vault.get(folder).parent;}catch(Exception e){folder="";}showExplorer();}else if(unlocked)requestLock();else super.onBackPressed();}
}
