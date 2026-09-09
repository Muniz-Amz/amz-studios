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
    private static final int PICK_FILES=10, SAVE_FILE=11, SAVE_BACKUP=12, RESTORE=13, EXPORT_MANY=14, SAVE_RECOVERY=15;
    private static final int BG=0xff0d1118, SURFACE=0xff171e29, BORDER=0xff2a3545, INK=0xfff0f4fb, MUTED=0xffa4b0c2, ACCENT=0xffadceff;
    private static final long LOCK_DELAY=120000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private VaultEngine vault;
    private LinearLayout page;
    private EditText password, confirmation, search;
    private TextView status, counter;
    private GridView list;
    private ThumbnailLoader thumbnails;
    private FileAdapter adapter;
    private List<VaultEngine.Entry> all=new ArrayList<>(), visible=new ArrayList<>();
    private String folder="", pendingId;
    private char[] restorePassword;
    private String restoreRecovery, pendingRecovery;
    private List<String> pendingExports=new ArrayList<>();
    private final Set<String> selected=new LinkedHashSet<>();
    private boolean selectionMode,gridMode,trashView;
    private volatile boolean unlocked,busy;
    private boolean external, lockAfter, stopped;
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
        gridMode=getPreferences(MODE_PRIVATE).getBoolean("grid",false);
        thumbnails=new ThumbnailLoader(vault,new File(getCacheDir(),"thumb-work"),worker,ui,()->unlocked&&!busy);
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
        unlocked=false;thumbnails.clear();pendingRecovery=null;
        if(!worker.isShutdown())worker.execute(vault::lock);
        worker.shutdown(); super.onDestroy();
    }
    private void armLock() { ui.removeCallbacks(timeout); ui.postDelayed(timeout,LOCK_DELAY); }
    private void requestLock() {
        ui.removeCallbacks(timeout); unlocked=false; all.clear(); visible.clear();selected.clear();selectionMode=false;trashView=false;pendingRecovery=null;thumbnails.clear();
        closeDialogs(); closePreview();
        if(busy) { lockAfter=true; return; }
        if(!worker.isShutdown())worker.execute(vault::lock); folder=""; showLocked();
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
            if(vault.hasRecovery()){Button recover=button("Esqueci a senha · Recuperar acesso",false);recover.setId(R.id.vault_recover);add(content,recover,12);recover.setOnClickListener(v->recoverAccess());}
            Button help=button("Sobre minha senha e meus arquivos",false); add(content,help,12); help.setOnClickListener(v->help());
        }
        add(content,text("Android 8 ou superior  ·  v1.1.0",12,MUTED),28);
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
        if(!vault.isUnlocked()||isFinishing()||isDestroyed())return;
        unlocked=true;all=vault.list();frame();
        LinearLayout header=row();TextView title=text("Cofre AMZ",25,INK);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button more=button("•••",false);more.setContentDescription("Opções do cofre");header.addView(more,new LinearLayout.LayoutParams(dp(48),dp(46)));more.setOnClickListener(v->options(more));
        Button lock=button("Bloquear",false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(100),dp(46));lp.leftMargin=dp(6);header.addView(lock,lp);lock.setOnClickListener(v->requestLock());page.addView(header);
        counter=text("",14,MUTED);counter.setOnClickListener(v->storageInfo());add(page,counter,8);
        if(vault.needsBackup()){
            Button backup=button("Há alterações sem backup · Salvar backup",false);backup.setId(R.id.vault_backup_reminder);backup.setTextSize(13);add(page,backup,8);backup.setOnClickListener(v->saveBackup());
        }
        search=input(trashView?"Buscar na lixeira":"Buscar arquivos e pastas",false);search.setId(R.id.vault_search);add(page,search,12);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){refresh();}public void afterTextChanged(Editable e){}});
        LinearLayout tabs=row();int trashCount=0;for(VaultEngine.Entry e:all)if(e.isTrashed()&&e.id.equals(e.trashRoot))trashCount++;
        Button files=button("Arquivos",!trashView),trash=button("Lixeira ("+trashCount+")",trashView),view=button(gridMode?"Lista":"Grade",false);
        files.setId(R.id.vault_files_tab);trash.setId(R.id.vault_trash_tab);view.setId(R.id.vault_view_toggle);
        tabs.addView(files,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.leftMargin=dp(6);tabs.addView(trash,tp);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(76),dp(44));vp.leftMargin=dp(6);tabs.addView(view,vp);
        files.setOnClickListener(v->switchTrash(false));trash.setOnClickListener(v->switchTrash(true));view.setOnClickListener(v->{gridMode=!gridMode;getPreferences(MODE_PRIVATE).edit().putBoolean("grid",gridMode).apply();view.setText(gridMode?"Lista":"Grade");refresh();});add(page,tabs,8);
        LinearLayout location=row();HorizontalScrollView crumbs=new HorizontalScrollView(this);crumbs.setHorizontalScrollBarEnabled(false);LinearLayout trail=row();crumbs.addView(trail);location.addView(crumbs,new LinearLayout.LayoutParams(0,dp(48),1));
        if(selectionMode){TextView count=text(selected.size()+" selecionado(s)",15,INK);trail.addView(count);}else if(trashView){trail.addView(text("Lixeira criptografada",16,INK));}
        else{
            Button home=button("Meus arquivos",false);trail.addView(home);home.setOnClickListener(v->{folder="";showExplorer();});List<VaultEngine.Entry> parents=new ArrayList<>();String current=folder;
            try{while(!current.isEmpty()){VaultEngine.Entry e=vault.get(current);parents.add(0,e);current=e.parent;}}catch(Exception e){folder="";}
            for(VaultEngine.Entry e:parents){trail.addView(text(" / ",15,MUTED));Button b=button(e.name,false);trail.addView(b);b.setOnClickListener(v->{folder=e.id;showExplorer();});}
        }
        Button select=button(selectionMode?"Cancelar":"Selecionar",false);select.setId(R.id.vault_select);location.addView(select,new LinearLayout.LayoutParams(dp(100),dp(44)));select.setOnClickListener(v->{selectionMode=!selectionMode;selected.clear();showExplorer();});add(page,location,6);
        if(selectionMode){
            HorizontalScrollView scroll=new HorizontalScrollView(this);LinearLayout actions=row();scroll.addView(actions);action(actions,"Todos",()->{for(VaultEngine.Entry e:visible)selected.add(e.id);showExplorer();});
            if(trashView){action(actions,"Restaurar",()->restoreSelected(new ArrayList<>(selected)));action(actions,"Excluir de vez",()->purgeSelected(new ArrayList<>(selected)));}
            else{action(actions,"Mover",()->chooseFolders(new ArrayList<>(selected)));action(actions,"Retirar",()->exportSelection(new ArrayList<>(selected)));action(actions,"Lixeira",()->trashSelected(new ArrayList<>(selected)));}add(page,scroll,6);
        }else if(!trashView){
            LinearLayout actions=row();Button move=button("+ Mover arquivos",true);move.setId(R.id.vault_import);actions.addView(move,new LinearLayout.LayoutParams(0,dp(50),1));move.setOnClickListener(v->chooseFiles());Button newFolder=button("+ Pasta",false);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(dp(100),dp(50));np.leftMargin=dp(8);actions.addView(newFolder,np);newFolder.setOnClickListener(v->nameDialog(null));add(page,actions,6);
        }
        list=new GridView(this);list.setId(R.id.vault_items);list.setVerticalSpacing(dp(8));list.setHorizontalSpacing(dp(10));list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list.setPadding(0,dp(12),0,dp(8));list.setClipToPadding(false);adapter=new FileAdapter();list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,position,id)->{if(visible.isEmpty())return;VaultEngine.Entry e=visible.get(position);if(selectionMode)toggleSelection(e);else if(e.folder&&!trashView){folder=e.id;showExplorer();}else fileMenu(e);});
        list.setOnItemLongClickListener((p,v,position,id)->{if(visible.isEmpty())return false;selectionMode=true;toggleSelection(visible.get(position));return true;});
        add(page,text(trashView?"A lixeira ocupa espaço até a exclusão definitiva.":"Segure um item para selecionar · Tudo offline",12,MUTED),4);refresh();
    }
    private void switchTrash(boolean value){trashView=value;selectionMode=false;selected.clear();folder="";showExplorer();}
    private void action(LinearLayout row,String label,Runnable action){Button b=button(label,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(44));lp.rightMargin=dp(6);row.addView(b,lp);b.setOnClickListener(v->action.run());}
    private void toggleSelection(VaultEngine.Entry e){if(!selected.add(e.id))selected.remove(e.id);String query=search.getText().toString();showExplorer();search.setText(query);}
    private void refresh(){
        if(!unlocked||adapter==null||list==null)return;String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible=new ArrayList<>();
        for(VaultEngine.Entry e:all){boolean eligible=trashView?e.isTrashed()&&e.id.equals(e.trashRoot):!e.isTrashed();if(eligible&&(query.isEmpty()?(trashView||e.parent.equals(folder)):e.name.toLowerCase(Locale.ROOT).contains(query)))visible.add(e);}
        Collections.sort(visible,(a,b)->a.folder!=b.folder?(a.folder?-1:1):a.name.compareToIgnoreCase(b.name));
        int count=0;for(VaultEngine.Entry e:all)if(!e.folder&&!e.isTrashed())count++;
        counter.setText(count+" arquivo(s) · "+size(vault.storedBytes())+" no cofre\n"+size(vault.availableBytes())+" livres no aparelho");
        list.setNumColumns(visible.isEmpty()?1:gridMode?(getResources().getConfiguration().screenWidthDp>=600?3:2):1);adapter.notifyDataSetChanged();
    }
    private class FileAdapter extends BaseAdapter{
        public int getCount(){return visible.isEmpty()?1:visible.size();}public Object getItem(int p){return visible.isEmpty()?null:visible.get(p);}public long getItemId(int p){return p;}@Override public boolean isEnabled(int p){return !visible.isEmpty();}
        public View getView(int p,View old,android.view.ViewGroup parent){
            if(visible.isEmpty()){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(28),dp(12),dp(28));TextView heading=text(search.length()>0?"Nenhum resultado":trashView?"Lixeira vazia":"Esta pasta está vazia",20,INK);heading.setGravity(Gravity.CENTER);empty.addView(heading);TextView hint=text(search.length()>0?"Tente outro nome.":trashView?"Os itens excluídos ficam protegidos aqui.":"Toque em Mover arquivos para começar.",14,MUTED);hint.setGravity(Gravity.CENTER);add(empty,hint,10);return empty;}
            VaultEngine.Entry e=visible.get(p);LinearLayout cell=gridMode?column():row();cell.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);cell.setPadding(dp(12),dp(12),dp(12),dp(12));cell.setBackground(box(selected.contains(e.id)?0xff263d5b:SURFACE,14));
            FrameLayout icon=new FrameLayout(MainActivity.this);icon.setBackground(box(0xff23324a,10));TextView badge=text(e.folder?"▰":type(e),e.folder?30:14,ACCENT);badge.setTypeface(null,Typeface.BOLD);badge.setGravity(Gravity.CENTER);icon.addView(badge,new FrameLayout.LayoutParams(-1,-1));
            if(!e.folder&&(e.mime.startsWith("image/")||e.mime.startsWith("video/"))){ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);icon.addView(image,new FrameLayout.LayoutParams(-1,-1));thumbnails.bind(e,image);}
            if(gridMode){cell.addView(icon,new LinearLayout.LayoutParams(-1,dp(112)));LinearLayout names=row();TextView name=text(e.name,15,INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name,new LinearLayout.LayoutParams(0,-2,1));names.addView(itemControl(e),new LinearLayout.LayoutParams(dp(42),dp(44)));add(cell,names,6);}
            else{cell.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout names=column();LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=dp(12);cell.addView(names,np);TextView name=text(e.name,16,INK);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name);add(names,text(detail(e),12,MUTED),4);cell.addView(itemControl(e),new LinearLayout.LayoutParams(dp(44),dp(46)));}
            if(gridMode)add(cell,text(detail(e),12,MUTED),2);return cell;
        }
    }
    private View itemControl(VaultEngine.Entry e){
        if(selectionMode){CheckBox box=new CheckBox(this);box.setChecked(selected.contains(e.id));box.setContentDescription("Selecionar "+e.name);box.setOnClickListener(v->toggleSelection(e));return box;}
        Button menu=button("⋮",false);menu.setContentDescription("Opções de "+e.name);menu.setOnClickListener(v->fileMenu(e));return menu;
    }
    private String detail(VaultEngine.Entry e){if(trashView)return "Excluído em "+DateFormat.getDateInstance(DateFormat.SHORT,new Locale("pt","BR")).format(new Date(e.trashedAt));return e.folder?"Pasta · "+children(e.id)+" itens":size(e.size);}
    private int children(String id){int n=0;for(VaultEngine.Entry e:all)if(!e.isTrashed()&&e.parent.equals(id))n++;return n;}
    private String type(VaultEngine.Entry e){if(e.mime.startsWith("image/"))return "IMG";if(e.mime.startsWith("video/"))return "VID";if(e.mime.startsWith("audio/"))return "ÁUD";int dot=e.name.lastIndexOf('.');return dot>=0?e.name.substring(dot+1).toUpperCase(Locale.ROOT).substring(0,Math.min(4,e.name.length()-dot-1)):"ARQ";}

    private void options(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);String[] labels={"Salvar backup criptografado","Chave de recuperação","Armazenamento","Alterar senha","Ajuda e informações"};for(String label:labels)menu.getMenu().add(label);
        menu.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();if(label.equals(labels[0]))saveBackup();else if(label.equals(labels[1]))createRecovery();else if(label.equals(labels[2]))storageInfo();else if(label.equals(labels[3]))changePassword();else help();return true;});menu.show();
    }
    private void fileMenu(VaultEngine.Entry e){
        if(!unlocked||busy)return;
        if(e.isTrashed()){
            track(new AlertDialog.Builder(this).setTitle(e.name).setItems(new String[]{"Restaurar","Excluir definitivamente","Selecionar"},(d,which)->{if(which==0)restoreSelected(Collections.singletonList(e.id));else if(which==1)purgeSelected(Collections.singletonList(e.id));else{selectionMode=true;selected.add(e.id);showExplorer();}}).setNegativeButton("Fechar",null).show());return;
        }
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
        chooseFolders(Collections.singletonList(entry.id));
    }
    private boolean requireSelection(Collection<String> ids){if(ids.isEmpty()){notice("Selecione os itens","Toque nos arquivos ou use Todos antes de escolher uma ação.");return false;}return true;}
    private void selectionDone(){selected.clear();selectionMode=false;thumbnails.clear();showExplorer();}
    private void chooseFolders(List<String> chosen){
        if(!requireSelection(chosen))return;
        List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("");names.add("Meus arquivos");
        Set<String> excluded=new HashSet<>();try{for(VaultEngine.Entry root:vault.selectionRoots(chosen))for(VaultEngine.Entry e:vault.activeSubtree(root.id))excluded.add(e.id);}catch(Exception e){error(e);return;}
        for(VaultEngine.Entry e:all)if(e.folder&&!e.isTrashed()&&!excluded.contains(e.id)){ids.add(e.id);names.add(path(e));}
        track(new AlertDialog.Builder(this).setTitle("Mover para a pasta").setItems(names.toArray(new String[0]),(d,n)->run("Movendo…",()->vault.moveAll(chosen,ids.get(n)),this::selectionDone,this::error)).setNegativeButton("Cancelar",null).show());
    }
    private String path(VaultEngine.Entry e){String s=e.name,p=e.parent;try{while(!p.isEmpty()){VaultEngine.Entry parent=vault.get(p);s=parent.name+" / "+s;p=parent.parent;}}catch(Exception ignored){}return s;}
    private void confirmDelete(VaultEngine.Entry e){
        trashSelected(Collections.singletonList(e.id));
    }
    private void trashSelected(List<String> ids){
        if(!requireSelection(ids))return;
        track(new AlertDialog.Builder(this).setTitle("Mover para a lixeira?").setMessage(ids.size()+" item(ns) selecionado(s). Pastas incluem seus arquivos.\n\nTudo continua criptografado e pode ser restaurado. O espaço só é liberado ao excluir definitivamente.").setNegativeButton("Cancelar",null).setPositiveButton("Mover para lixeira",(d,w)->run("Movendo para a lixeira…",()->vault.trash(ids),this::selectionDone,this::error)).show());
    }
    private void restoreSelected(List<String> ids){if(!requireSelection(ids))return;run("Restaurando itens…",()->vault.restoreTrash(ids),()->{selectionDone();notice("Itens restaurados","Os itens voltaram às pastas originais. Se a pasta não estiver disponível, eles ficam em Meus arquivos. Nomes repetidos recebem a indicação restaurado.");},this::error);}
    private void purgeSelected(List<String> ids){
        if(!requireSelection(ids))return;
        track(new AlertDialog.Builder(this).setTitle("Excluir definitivamente?").setMessage(ids.size()+" item(ns) e seu conteúdo serão apagados do cofre. Esta ação não pode ser desfeita. Backups externos já existentes continuam intactos.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir de vez",(d,w)->run("Excluindo definitivamente…",()->vault.purgeTrash(ids),this::selectionDone,this::error)).show());
    }
    private void exportSelection(List<String> ids){
        if(!requireSelection(ids))return;pendingExports=new ArrayList<>(ids);
        track(new AlertDialog.Builder(this).setTitle("Retirar itens do cofre").setMessage("Escolha uma pasta de destino. Você pode criar uma subpasta pelo seletor do Android.\n\nCada arquivo será conferido antes da remoção do cofre. Pastas são retiradas com seus arquivos, mantendo a organização.").setNegativeButton("Cancelar",null).setPositiveButton("Escolher pasta",(d,w)->launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION),EXPORT_MANY)).show());
    }
    private void exportMany(Uri tree,List<String> ids){
        int[] success={0},failed={0};StringBuilder details=new StringBuilder();
        run("Retirando os itens selecionados…",()->{
            Uri target=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            for(VaultEngine.Entry root:vault.selectionRoots(ids)){
                try{exportBranch(root,target);vault.delete(root.id);success[0]++;}
                catch(Exception e){failed[0]++;details.append("\n• ").append(root.name).append(": preservado no cofre. ").append(friendly(e));}
            }
        },()->{selectionDone();notice("Retirada concluída",success[0]+" item(ns) retirado(s). "+failed[0]+" preservado(s)."+details+(failed[0]>0?"\n\nOs destinos com falha podem conter cópias parciais.":""));},this::error);
    }
    private void exportBranch(VaultEngine.Entry entry,Uri parent)throws Exception{
        Uri target=createDestination(parent,entry.folder?DocumentsContract.Document.MIME_TYPE_DIR:entry.mime,entry.name);
        if(entry.folder){List<VaultEngine.Entry> children=new ArrayList<>();for(VaultEngine.Entry e:vault.list())if(!e.isTrashed()&&e.parent.equals(entry.id))children.add(e);for(VaultEngine.Entry child:children)exportBranch(child,target);}
        else{try(OutputStream out=write(target)){vault.exportFile(entry.id,out,this::progressBytes);}try(InputStream in=read(target)){if(!vault.matches(entry.id,in))throw new IOException("A verificação do destino falhou.");}}
    }
    private Uri createDestination(Uri parent,String mime,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));Set<String> names=new HashSet<>();
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c==null)throw new IOException("Não foi possível conferir a pasta de destino.");while(c.moveToNext())names.add(c.getString(0).toLowerCase(Locale.ROOT));}
        String candidate=name;int suffix=2,dot=name.lastIndexOf('.');boolean folderType=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);String base=dot>0&&!folderType?name.substring(0,dot):name,ext=dot>0&&!folderType?name.substring(dot):"";
        while(names.contains(candidate.toLowerCase(Locale.ROOT)))candidate=base+" ("+(suffix++)+")"+ext;
        Uri created=DocumentsContract.createDocument(getContentResolver(),parent,mime.isEmpty()?"application/octet-stream":mime,candidate);if(created==null)throw new IOException("Não foi possível criar o arquivo de destino.");return created;
    }
    private void storageInfo(){
        if(!unlocked)return;long last=vault.lastBackupAt();notice("Armazenamento do cofre","Cofre: "+size(vault.storedBytes())+"\nNa lixeira: "+size(vault.trashBytes())+"\nLivre no aparelho: "+size(vault.availableBytes())+"\n\nÚltimo backup confirmado: "+(last==0?"ainda não há":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("pt","BR")).format(new Date(last)))+"\n"+(vault.needsBackup()?"Há alterações ainda sem backup.":"Nenhuma alteração pendente de backup.")+"\n\nOs arquivos da lixeira continuam ocupando espaço. A exclusão definitiva libera esse espaço.");
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
        LinearLayout fields=column();CheckBox useCode=new CheckBox(this);useCode.setText("Usar chave de recuperação");useCode.setTextColor(INK);fields.addView(useCode);EditText code=input("Código AMZ1-…",true),pass=input("Senha usada no backup",true),again=input("Repita a nova senha",true);fields.addView(code);fields.addView(pass);fields.addView(again);code.setVisibility(View.GONE);again.setVisibility(View.GONE);
        useCode.setOnCheckedChangeListener((b,on)->{code.setVisibility(on?View.VISIBLE:View.GONE);again.setVisibility(on?View.VISIBLE:View.GONE);pass.setHint(on?"Nova senha (10 ou mais caracteres)":"Senha usada no backup");});
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Restaurar cofre").setMessage("Use a senha ou a chave de recuperação que o backup tinha quando foi salvo.").setView(padded(fields)).setNegativeButton("Cancelar",null).setPositiveButton("Escolher backup",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{boolean recovery=useCode.isChecked();if(pass.length()==0||(recovery&&(pass.length()<10||!pass.getText().toString().equals(again.getText().toString())||code.length()==0))){pass.setError(recovery?"Informe o código e repita uma nova senha de 10+ caracteres.":"Digite a senha do backup");return;}clearRestorePassword();restorePassword=pass.getText().toString().toCharArray();restoreRecovery=recovery?code.getText().toString():null;pass.setText("");again.setText("");code.setText("");d.dismiss();launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),RESTORE);}));track(d);d.show();
    }
    private void createRecovery(){
        boolean exists=vault.hasRecovery();track(new AlertDialog.Builder(this).setTitle(exists?"Gerar outra chave de recuperação?":"Criar chave de recuperação").setMessage((exists?"A chave anterior deixará de funcionar neste cofre. Backups antigos continuam com a chave antiga.\n\n":"")+"Guarde o código fora do cofre. Quem tiver esse código e os dados do cofre poderá recuperar o acesso. O app não guarda o código em texto legível.").setNegativeButton("Cancelar",null).setPositiveButton(exists?"Gerar nova chave":"Gerar código",(d,w)->{
            String[] code={null};run("Gerando chave de recuperação…",()->code[0]=vault.createRecoveryKey(),()->{showExplorer();pendingRecovery=code[0];showRecoveryCode();},this::error);
        }).show());
    }
    private void showRecoveryCode(){
        LinearLayout content=column();TextView explanation=text("Anote ou salve fora do cofre. Este código não será mostrado novamente. Depois, salve um novo backup para incluir a recuperação.",15,MUTED);content.addView(explanation);
        TextView code=text(pendingRecovery,17,INK);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setId(R.id.vault_recovery_code);add(content,code,18);
        track(new AlertDialog.Builder(this).setTitle("Sua chave de recuperação").setView(padded(content)).setPositiveButton("Já guardei",(d,w)->pendingRecovery=null).setNegativeButton("Salvar código",(d,w)->launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Cofre-AMZ-chave-de-recuperacao.txt"),SAVE_RECOVERY)).setOnCancelListener(d->pendingRecovery=null).show());
    }
    private void recoverAccess(){
        LinearLayout fields=column();EditText code=input("Chave de recuperação AMZ1-…",true),pass=input("Nova senha (10 ou mais caracteres)",true),again=input("Repita a nova senha",true);code.setId(R.id.vault_recovery_input);pass.setId(R.id.vault_new_password);again.setId(R.id.vault_new_confirmation);fields.addView(code);add(fields,pass,8);add(fields,again,8);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Recuperar acesso offline").setMessage("Use a chave gerada anteriormente neste cofre. Seus arquivos serão mantidos e a senha será substituída.").setView(padded(fields)).setNegativeButton("Cancelar",null).setPositiveButton("Recuperar",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(code.length()==0||pass.length()<10||!pass.getText().toString().equals(again.getText().toString())){pass.setError("Informe o código e repita uma senha de 10+ caracteres.");return;}String recovery=code.getText().toString();char[] chars=pass.getText().toString().toCharArray();code.setText("");pass.setText("");again.setText("");d.dismiss();run("Recuperando o cofre…",()->{try{vault.recover(recovery,chars);}finally{Arrays.fill(chars,'\0');}},()->{unlocked=true;folder="";trashView=false;showExplorer();armLock();notice("Acesso recuperado","A nova senha já está valendo neste cofre. Salve um novo backup.");},e->notice("Não foi possível recuperar","Confira o código de recuperação deste cofre. Seus arquivos foram preservados."));}));track(d);d.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);external=false;stopped=false;
        if(result!=RESULT_OK||data==null){if(request==RESTORE)clearRestorePassword();if(request==SAVE_RECOVERY)pendingRecovery=null;if(unlocked)armLock();return;}
        if(request==RESTORE){
            Uri uri=data.getData();if(uri==null||restorePassword==null){clearRestorePassword();return;}char[] pass=restorePassword;String code=restoreRecovery;restorePassword=null;restoreRecovery=null;
            run("Restaurando e verificando arquivos…",()->{try(InputStream in=read(uri)){if(code==null)vault.restore(in,pass);else vault.restoreUsingRecovery(in,code,pass);}finally{Arrays.fill(pass,'\0');}},()->{unlocked=true;showExplorer();armLock();notice("Cofre restaurado","Todos os arquivos foram verificados. Guarde seu backup em um local seguro.");},e->notice("Não foi possível restaurar","Confira a senha ou chave de recuperação, a integridade do backup e o espaço disponível. "+friendly(e)));return;
        }
        if(!unlocked){notice("Cofre bloqueado","Desbloqueie e selecione os arquivos novamente. Nenhum original foi removido.");return;}
        if(request==PICK_FILES){
            List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int n=0;n<data.getClipData().getItemCount();n++)uris.add(data.getClipData().getItemAt(n).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            importFiles(uris);return;
        }
        Uri uri=data.getData();if(uri==null)return;
        if(request==EXPORT_MANY){List<String> ids=new ArrayList<>(pendingExports);pendingExports.clear();exportMany(uri,ids);return;}
        if(request==SAVE_RECOVERY){
            String code=pendingRecovery;pendingRecovery=null;if(code==null){notice("Código indisponível","Desbloqueie e gere uma nova chave no menu do cofre.");return;}
            run("Salvando o código de recuperação…",()->{byte[] bytes=("Cofre AMZ — chave de recuperação offline\n\n"+code+"\n\nGuarde este código fora do cofre e separado do backup. Quem tiver ambos pode recuperar o acesso.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);try(OutputStream out=write(uri)){out.write(bytes);}try(InputStream in=read(uri)){ByteArrayOutputStream actual=new ByteArrayOutputStream();byte[] block=new byte[1024];int n;while((n=in.read(block))!=-1){actual.write(block,0,n);if(actual.size()>bytes.length)throw new IOException("Verificação do código salvo falhou.");}if(!Arrays.equals(bytes,actual.toByteArray()))throw new IOException("Verificação do código salvo falhou.");}finally{Arrays.fill(bytes,(byte)0);}},()->notice("Código salvo","Guarde esse arquivo separado do backup. Agora salve um novo backup do cofre."),this::error);return;
        }
        if(request==SAVE_FILE){
            String id=pendingId;boolean move=removeOnExport;
            run("Salvando e conferindo o arquivo…",()->{
                try(OutputStream out=write(uri)){vault.exportFile(id,out,this::progressBytes);}
                try(InputStream in=read(uri)){if(!vault.matches(id,in))throw new IOException("A cópia salva não passou na verificação. O original permanece no cofre.");}
                if(move)vault.delete(id);
            },()->{showExplorer();notice(move?"Arquivo retirado":"Cópia salva",move?"O arquivo está no destino escolhido e foi removido do cofre.":"A cópia normal está no destino escolhido. A versão do cofre foi mantida.");},e->{error(e);});
        }else if(request==SAVE_BACKUP){
            run("Salvando backup criptografado…",()->{try(OutputStream out=write(uri)){vault.backup(out);}try(InputStream in=read(uri)){vault.verifyBackup(in);}vault.markBackupCompleted();},()->{showExplorer();notice("Backup salvo e verificado","O backup inclui os arquivos, a lixeira e a recuperação, quando ativada. Você pode restaurar com a senha ou chave que ele tinha ao ser salvo.");},this::error);
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
        String candidate=clean;int n=2;Set<String> used=new HashSet<>();for(VaultEngine.Entry e:vault.list())if(!e.isTrashed()&&e.parent.equals(parent))used.add(e.name.toLowerCase(Locale.ROOT));
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
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(pass.length()<10||!pass.getText().toString().equals(again.getText().toString())){pass.setError("Use pelo menos 10 caracteres e repita a mesma senha.");return;}char[] chars=pass.getText().toString().toCharArray();pass.setText("");again.setText("");d.dismiss();run("Atualizando senha…",()->{try{vault.changePassword(chars);}finally{Arrays.fill(chars,'\0');}},()->{showExplorer();notice("Senha alterada","Use a nova senha para desbloquear este cofre. Salve um novo backup.");},this::error);}));track(d);d.show();
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
            try{external=true;unlocked=false;thumbnails.clear();worker.execute(vault::lock);all.clear();showLocked();startActivityForResult(Intent.createChooser(i,"Abrir arquivo"),20);}catch(Exception err){external=false;clearPreviews();notice("Nenhum aplicativo disponível","Instale um aplicativo compatível com este formato ou retire o arquivo para uma pasta.");}
        }).setOnCancelListener(d->clearPreviews()).show());
    }
    private void closePreview(){if(preview!=null){Dialog old=preview;preview=null;old.dismiss();}}
    private void clearPreviews(){
        File dir=new File(getCacheDir(),"preview");File[] files=dir.listFiles();if(files!=null)for(File f:files){try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".preview",f);revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}f.delete();}
    }
    private void help(){notice("Cofre AMZ 1.1.0","• Funciona offline, sem permissão de internet.\n\n• Use Selecionar ou segure um item para mover, retirar ou enviar vários arquivos à lixeira.\n\n• Grade mostra miniaturas de fotos e vídeos compatíveis. As miniaturas não são salvas em texto legível.\n\n• A lixeira continua criptografada. Restaure os itens ou exclua definitivamente para liberar espaço. Não há exclusão automática.\n\n• Gere sua chave de recuperação no menu e guarde o código fora do cofre. Quem tiver o código e os dados do cofre pode recuperar o acesso. Sem senha ou código previamente gerado, não é possível recuperar.\n\n• Salve um backup sempre que aparecer o aviso. Ele inclui os arquivos, a lixeira e a recuperação ativada. Backups antigos mantêm a senha e a chave que tinham ao ser salvos.\n\n• Desinstalar o app ou limpar seus dados apaga o cofre. Instale atualizações por cima para manter seus arquivos.\n\n• Ao importar, o original só é removido após a verificação. Se o Android não permitir apagar na origem, o app avisa.\n\n• Bloqueio ao sair do app e após 2 minutos sem interação.\n\nAES-256-GCM · Android 8+");}
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
    private void clearRestorePassword(){if(restorePassword!=null)Arrays.fill(restorePassword,'\0');restorePassword=null;restoreRecovery=null;}
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
    @Override public void onBackPressed(){if(busy)return;if(unlocked&&selectionMode){selectionMode=false;selected.clear();showExplorer();return;}if(unlocked&&trashView){switchTrash(false);return;}if(unlocked&&!folder.isEmpty()){try{folder=vault.get(folder).parent;}catch(Exception e){folder="";}showExplorer();}else if(unlocked)requestLock();else super.onBackPressed();}
}
