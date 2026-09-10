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
    private static final int BG=VaultUi.BG, SURFACE=VaultUi.SURFACE, BORDER=VaultUi.BORDER, INK=VaultUi.INK, MUTED=VaultUi.MUTED, ACCENT=VaultUi.ACCENT;
    private static final long LOCK_DELAY=120000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private VaultEngine vault;
    private LinearLayout page;
    private EditText password, confirmation, search;
    private TextView status, counter, freeSpace, selectionTitle;
    private LinearLayout trail;
    private View normalActions, selectionActions;
    private Button selectButton;
    private boolean scrolling;
    private int filterGeneration;
    private GridView list;
    private ThumbnailLoader thumbnails;
    private FileAdapter adapter;
    private List<VaultEngine.Entry> all=new ArrayList<>(), visible=new ArrayList<>();
    private String folder="", pendingId;
    private char[] restorePassword;
    private String restoreRecovery, pendingRecovery;
    private List<String> pendingExports=new ArrayList<>();
    private final Set<String> selected=new LinkedHashSet<>();
    private boolean selectionMode,gridMode,trashView,mediaActive;
    private volatile boolean unlocked,busy;
    private boolean external, lockAfter, stopped;
    private ProgressDialog progress;
    private Dialog preview;
    private Runnable previewCleanup;
    private MediaPager mediaPager;
    private final List<Dialog> dialogs=new ArrayList<>();
    private final Runnable timeout=()->requestLock();
    private long lastProgress;
    private volatile boolean cancelRequested;
    private volatile String progressPhase="Processando";
    private volatile Summary summary;
    private final Runnable searchRefresh=()->refresh();
    private final Runnable resumeThumbnails=()->bindVisibleThumbnails();
    private static final class Summary {
        long stored,free,trash,lastBackup;int activeFiles,trashFiles;boolean needsBackup;final Map<String,Integer> children=new HashMap<>();
    }
    private void loadSummary(){
        if(!vault.isUnlocked()){summary=null;return;}Summary next=new Summary();
        next.stored=vault.storedBytes();next.free=vault.availableBytes();next.trash=vault.trashBytes();next.lastBackup=vault.lastBackupAt();next.needsBackup=vault.needsBackup();
        for(VaultEngine.Entry e:vault.list()){
            if(!e.folder){if(e.isTrashed())next.trashFiles++;else next.activeFiles++;}
            if(!e.isTrashed())next.children.put(e.parent,next.children.getOrDefault(e.parent,0)+1);
        }summary=next;
    }
    private final VaultEngine.Progress operationProgress=new VaultEngine.Progress(){
        public void update(long bytes){progressBytes(bytes);}
        public void phase(String phase){progressPhase=phase;ui.post(()->{if(progress!=null&&!cancelRequested)progress.setMessage(phase);});lastProgress=0;}
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        vault=new VaultEngine(new File(getFilesDir(),"vault-v1"));
        gridMode=getPreferences(MODE_PRIVATE).getBoolean("grid",true);
        thumbnails=new ThumbnailLoader(vault,new File(getCacheDir(),"thumb-work"),worker,ui,()->unlocked&&!busy&&!mediaActive&&!scrolling);
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
        unlocked=false;closeProgress();closeDialogs();closePreview();ui.removeCallbacksAndMessages(null);filterGeneration++;clearRestorePassword();
        thumbnails.close();pendingRecovery=null;summary=null;
        if(!worker.isShutdown())worker.execute(vault::lock);
        worker.shutdown(); super.onDestroy();
    }
    private void armLock() { ui.removeCallbacks(timeout); if(!mediaActive)ui.postDelayed(timeout,LOCK_DELAY); }
    private void requestLock() {
        ui.removeCallbacks(timeout);ui.removeCallbacks(searchRefresh);ui.removeCallbacks(resumeThumbnails);filterGeneration++; unlocked=false; all.clear(); visible.clear();selected.clear();selectionMode=false;trashView=false;pendingRecovery=null;pendingExports.clear();summary=null;thumbnails.clear();
        closeDialogs(); closePreview();
        if(busy) { lockAfter=true; return; }
        if(!worker.isShutdown())worker.execute(vault::lock); folder=""; showLocked();
    }
    private void frame() {
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(12),dp(20),dp(12)); page.setBackgroundColor(BG);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);setContentView(page);
    }
    private void showLocked() {
        if(isFinishing()||isDestroyed())return;
        frame();boolean exists=vault.exists();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);LinearLayout content=column();content.setPadding(dp(4),dp(24),dp(4),dp(20));scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout intro=row();ImageView mark=new ImageView(this);mark.setImageDrawable(VaultUi.icon(this,"lock",ACCENT,40));mark.setBackground(box(0xff203734,24));mark.setPadding(dp(21),dp(21),dp(21),dp(21));intro.addView(mark,new LinearLayout.LayoutParams(dp(84),dp(84)));
        TextView offline=text("OFFLINE\nSEMPRE SEU",10,MUTED);offline.setLetterSpacing(.13f);offline.setGravity(Gravity.RIGHT);intro.addView(offline,new LinearLayout.LayoutParams(0,-2,1));content.addView(intro);
        TextView brand=text("AMZ STUDIOS",11,ACCENT);brand.setLetterSpacing(.18f);add(content,brand,28);
        add(content,text("Seu mundo.\nEm segurança.",34,INK),8);
        add(content,text(exists?"Desbloqueie o Cofre AMZ para acessar seus arquivos.":"Bem-vindo ao Cofre AMZ. Um espaço privado para tudo o que é seu.",15,MUTED),12);
        LinearLayout form=column();form.setPadding(dp(20),dp(22),dp(20),dp(18));form.setBackground(box(SURFACE,24));add(content,form,26);
        add(form,text(exists?"Acesse seu cofre":"Crie seu cofre",20,INK),0);
        password=input("Sua senha",true);password.setId(R.id.vault_password);password.setBackground(box(BG,12));password.setImeOptions(exists?android.view.inputmethod.EditorInfo.IME_ACTION_GO:android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);add(form,password,16);
        confirmation=null;if(!exists){confirmation=input("Repita sua senha",true);confirmation.setId(R.id.vault_confirmation);confirmation.setBackground(box(BG,12));add(form,confirmation,10);}
        CheckBox show=new CheckBox(this);show.setText("Mostrar senha");show.setTextColor(MUTED);show.setTextSize(13);show.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        show.setOnCheckedChangeListener((b,on)->{password.setTransformationMethod(on?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());if(confirmation!=null)confirmation.setTransformationMethod(on?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());});add(form,show,6);
        Button enter=button(exists?"Desbloquear cofre":"Criar meu cofre",true);enter.setId(R.id.vault_unlock);enter.setCompoundDrawables(null,null,VaultUi.icon(this,"arrow",BG,20),null);add(form,enter,10);enter.setOnClickListener(v->authenticate());
        status=text("",13,0xffffb4ab);add(form,status,2);password.setOnEditorActionListener((v,a,event)->{if(exists){authenticate();return true;}return false;});
        if(!exists){add(content,text("Use pelo menos 10 caracteres. Guarde sua senha e faça um backup antes de trocar de celular ou desinstalar o app.",13,MUTED),18);Button restore=tabButton("Restaurar backup .amzcofre",false);add(content,restore,8);restore.setOnClickListener(v->askRestore());}
        else{if(vault.hasRecovery()){Button recover=tabButton("Esqueci a senha · Recuperar acesso",false);recover.setId(R.id.vault_recover);add(content,recover,12);recover.setOnClickListener(v->recoverAccess());}Button help=tabButton("Sobre minha senha e meus arquivos",false);add(content,help,8);help.setOnClickListener(v->help());}
        TextView privacy=text("Sem conta. Sem conexão. Só você e seus arquivos.",12,MUTED);privacy.setGravity(Gravity.CENTER);add(content,privacy,22);TextView version=text("Cofre AMZ · v1.1.3",11,MUTED);version.setGravity(Gravity.CENTER);add(content,version,8);
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
        ui.removeCallbacks(searchRefresh);ui.removeCallbacks(resumeThumbnails);filterGeneration++;
        scrolling=false;thumbnails.pause();unlocked=true;all=vault.list();frame();
        LinearLayout header=row(),brand=column();TextView eyebrow=text("SEU ESPAÇO PRIVADO",10,ACCENT);eyebrow.setLetterSpacing(.16f);brand.addView(eyebrow);add(brand,text("Cofre AMZ",27,INK),3);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button lock=iconButton("lock","Bloquear cofre");header.addView(lock,new LinearLayout.LayoutParams(dp(48),dp(48)));lock.setOnClickListener(v->requestLock());
        Button more=iconButton("more","Opções do cofre");LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(48),dp(48));mp.leftMargin=dp(6);header.addView(more,mp);more.setOnClickListener(v->options(more));page.addView(header);

        LinearLayout storage=row();storage.setPadding(dp(16),dp(13),dp(16),dp(13));storage.setBackground(VaultUi.ripple(this,SURFACE,18));storage.setOnClickListener(v->storageInfo());storage.setContentDescription("Informações de armazenamento");
        ImageView disk=new ImageView(this);disk.setImageDrawable(VaultUi.icon(this,"drive",ACCENT,26));storage.addView(disk,new LinearLayout.LayoutParams(dp(28),dp(28)));
        LinearLayout metrics=column();LinearLayout.LayoutParams metricsParams=new LinearLayout.LayoutParams(0,-2,1);metricsParams.leftMargin=dp(13);storage.addView(metrics,metricsParams);counter=text("",18,INK);counter.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));metrics.addView(counter);freeSpace=text("",12,MUTED);add(metrics,freeSpace,3);
        if(summary!=null&&summary.needsBackup){
            Button backup=button("Salvar\nbackup",false);backup.setId(R.id.vault_backup_reminder);backup.setContentDescription("Há alterações sem backup. Salvar backup criptografado");backup.setTextSize(12);backup.setPadding(dp(8),dp(4),dp(8),dp(4));backup.setBackground(VaultUi.ripple(this,0xff25413e,12));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(72),dp(48));bp.leftMargin=dp(8);storage.addView(backup,bp);backup.setOnClickListener(v->saveBackup());
        }else{ImageView storageArrow=new ImageView(this);storageArrow.setImageDrawable(VaultUi.icon(this,"arrow",MUTED,20));storage.addView(storageArrow,new LinearLayout.LayoutParams(dp(20),dp(20)));}add(page,storage,14);
        search=input(trashView?"Buscar na lixeira":"Buscar arquivos e pastas",false);search.setId(R.id.vault_search);search.setCompoundDrawables(VaultUi.icon(this,"search",MUTED,20),null,null,null);search.setCompoundDrawablePadding(dp(10));add(page,search,14);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){ui.removeCallbacks(searchRefresh);filterGeneration++;ui.postDelayed(searchRefresh,180);}public void afterTextChanged(Editable e){}});
        LinearLayout tabs=row();int trashCount=0;for(VaultEngine.Entry e:all)if(e.isTrashed()&&e.id.equals(e.trashRoot))trashCount++;
        Button files=tabButton("Arquivos",!trashView),trash=tabButton("Lixeira"+(trashCount>0?" · "+trashCount:""),trashView),view=iconButton(gridMode?"list":"grid",gridMode?"Visualização em lista":"Visualização em grade");
        files.setId(R.id.vault_files_tab);trash.setId(R.id.vault_trash_tab);view.setId(R.id.vault_view_toggle);
        tabs.addView(files,new LinearLayout.LayoutParams(0,dp(46),1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(46),1);tp.leftMargin=dp(4);tabs.addView(trash,tp);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(48),dp(48));vp.leftMargin=dp(12);tabs.addView(view,vp);
        files.setOnClickListener(v->switchTrash(false));trash.setOnClickListener(v->switchTrash(true));view.setOnClickListener(v->{gridMode=!gridMode;getPreferences(MODE_PRIVATE).edit().putBoolean("grid",gridMode).apply();view.setCompoundDrawables(null,VaultUi.icon(this,gridMode?"list":"grid",ACCENT,22),null,null);view.setContentDescription(gridMode?"Visualização em lista":"Visualização em grade");thumbnails.pause();refresh();});add(page,tabs,10);

        LinearLayout location=row();FrameLayout locationText=new FrameLayout(this);location.addView(locationText,new LinearLayout.LayoutParams(0,dp(48),1));
        HorizontalScrollView crumbs=new HorizontalScrollView(this);crumbs.setHorizontalScrollBarEnabled(false);trail=row();crumbs.addView(trail);locationText.addView(crumbs,new FrameLayout.LayoutParams(-1,-1));
        selectionTitle=text("",16,ACCENT);selectionTitle.setGravity(Gravity.CENTER_VERTICAL);locationText.addView(selectionTitle,new FrameLayout.LayoutParams(-1,-1));
        if(trashView){trail.addView(text("Lixeira protegida",16,INK));}
        else{
            Button home=tabButton("Meus arquivos",false);home.setTextColor(INK);trail.addView(home);home.setOnClickListener(v->{folder="";showExplorer();});List<VaultEngine.Entry> parents=new ArrayList<>();String current=folder;
            try{while(!current.isEmpty()){VaultEngine.Entry e=vault.get(current);parents.add(0,e);current=e.parent;}}catch(Exception e){folder="";}
            for(VaultEngine.Entry e:parents){trail.addView(text(" / ",15,MUTED));Button b=tabButton(e.name,false);b.setTextColor(INK);trail.addView(b);b.setOnClickListener(v->{folder=e.id;showExplorer();});}
            crumbs.post(()->crumbs.fullScroll(View.FOCUS_RIGHT));
        }
        selectButton=tabButton(selectionMode?"Cancelar":"Selecionar",false);selectButton.setId(R.id.vault_select);location.addView(selectButton,new LinearLayout.LayoutParams(dp(98),dp(48)));selectButton.setOnClickListener(v->{selectionMode=!selectionMode;selected.clear();updateSelection();});add(page,location,4);

        list=new GridView(this);list.setId(R.id.vault_items);list.setVerticalSpacing(dp(10));list.setHorizontalSpacing(dp(12));list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list.setPadding(0,dp(4),0,dp(12));list.setClipToPadding(false);list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));list.setDrawSelectorOnTop(false);adapter=new FileAdapter();list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,position,id)->{if(position>=visible.size()||busy)return;VaultEngine.Entry e=visible.get(position);if(selectionMode)toggleSelection(e);else if(e.isTrashed())fileMenu(e);else if(e.folder){folder=e.id;showExplorer();}else openFile(e);});
        list.setOnItemLongClickListener((p,v,position,id)->{if(position>=visible.size())return false;selectionMode=true;toggleSelection(visible.get(position));return true;});
        list.setOnScrollListener(new AbsListView.OnScrollListener(){
            public void onScrollStateChanged(AbsListView v,int state){scrolling=state!=SCROLL_STATE_IDLE;ui.removeCallbacks(resumeThumbnails);if(scrolling)thumbnails.pause();else ui.post(resumeThumbnails);}
            public void onScroll(AbsListView v,int first,int count,int total){}
        });
        LinearLayout actions=row();normalActions=actions;
        if(!trashView){Button move=button("Mover arquivos",true);move.setCompoundDrawables(VaultUi.icon(this,"plus",BG,20),null,null,null);move.setCompoundDrawablePadding(dp(8));move.setId(R.id.vault_import);actions.addView(move,new LinearLayout.LayoutParams(0,dp(54),1));move.setOnClickListener(v->chooseFiles());Button newFolder=button("Pasta",false);newFolder.setContentDescription("Nova pasta");newFolder.setCompoundDrawables(VaultUi.icon(this,"folder",ACCENT,20),null,null,null);newFolder.setCompoundDrawablePadding(dp(8));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(dp(110),dp(54));np.leftMargin=dp(10);actions.addView(newFolder,np);newFolder.setOnClickListener(v->nameDialog(null));}
        else{TextView hint=text("Os itens permanecem aqui até você excluí-los de vez.",12,MUTED);hint.setPadding(dp(2),dp(10),dp(2),dp(10));actions.addView(hint);}
        add(page,actions,6);
        HorizontalScrollView selectionScroll=new HorizontalScrollView(this);selectionScroll.setHorizontalScrollBarEnabled(false);selectionActions=selectionScroll;LinearLayout selectedActions=row();selectionScroll.addView(selectedActions);action(selectedActions,"Todos",()->{for(VaultEngine.Entry e:visible)selected.add(e.id);updateSelection();});
        if(trashView){action(selectedActions,"Restaurar",()->restoreSelected(new ArrayList<>(selected)));action(selectedActions,"Excluir de vez",()->purgeSelected(new ArrayList<>(selected)));}
        else{action(selectedActions,"Mover",()->chooseFolders(new ArrayList<>(selected)));action(selectedActions,"Retirar",()->exportSelection(new ArrayList<>(selected)));action(selectedActions,"Lixeira",()->trashSelected(new ArrayList<>(selected)));}add(page,selectionScroll,6);
        updateSelection();refresh();
    }
    private void switchTrash(boolean value){trashView=value;selectionMode=false;selected.clear();folder="";showExplorer();}
    private void action(LinearLayout row,String label,Runnable action){Button b=button(label,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(54));lp.rightMargin=dp(8);row.addView(b,lp);b.setOnClickListener(v->action.run());}
    private void toggleSelection(VaultEngine.Entry e){if(!selected.add(e.id))selected.remove(e.id);updateSelection();}
    private void updateSelection(){
        selectButton.setText(selectionMode?"Cancelar":"Selecionar");selectionTitle.setText(selected.size()+" selecionado"+(selected.size()==1?"":"s"));selectionTitle.setVisibility(selectionMode?View.VISIBLE:View.GONE);trail.setVisibility(selectionMode?View.INVISIBLE:View.VISIBLE);
        normalActions.setVisibility(selectionMode?View.GONE:View.VISIBLE);selectionActions.setVisibility(selectionMode?View.VISIBLE:View.GONE);if(adapter!=null)adapter.notifyDataSetChanged();
    }
    private void refresh(){
        if(!unlocked||adapter==null||list==null)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT),currentFolder=folder;boolean trash=trashView;int epoch=++filterGeneration;List<VaultEngine.Entry> snapshot=new ArrayList<>(all);
        Runnable filter=()->{
            List<VaultEngine.Entry> result=new ArrayList<>();int count=0;
            for(VaultEngine.Entry e:snapshot){if(!e.folder&&!e.isTrashed())count++;boolean eligible=trash?e.isTrashed()&&e.id.equals(e.trashRoot):!e.isTrashed();if(eligible&&(query.isEmpty()?(trash||e.parent.equals(currentFolder)):e.name.toLowerCase(Locale.ROOT).contains(query)))result.add(e);}
            Collections.sort(result,(a,b)->a.folder!=b.folder?(a.folder?-1:1):a.name.compareToIgnoreCase(b.name));int fileCount=count;
            Runnable apply=()->{if(!unlocked||epoch!=filterGeneration||isDestroyed())return;visible=result;counter.setText(size(summary==null?0:summary.stored)+" no cofre");freeSpace.setText(fileCount+" arquivo"+(fileCount==1?"":"s")+" · "+size(summary==null?0:summary.free)+" livres");list.setNumColumns(visible.isEmpty()?1:gridMode?Math.max(2,getResources().getConfiguration().screenWidthDp/180):1);adapter.notifyDataSetChanged();};
            if(Looper.myLooper()==Looper.getMainLooper())apply.run();else ui.post(apply);
        };
        if(snapshot.size()>500&&!worker.isShutdown())worker.execute(filter);else filter.run();
    }
    private void bindVisibleThumbnails(){
        if(!unlocked||busy||mediaActive||scrolling||list==null)return;
        for(int i=0;i<list.getChildCount();i++){Object tag=list.getChildAt(i).getTag();if(tag instanceof Cell){Cell c=(Cell)tag;if(c.entry!=null&&c.media)thumbnails.bind(c.entry,c.image);}}
    }
    private final class Cell {
        final boolean grid;final LinearLayout root;final FrameLayout art;final ImageView glyph,image,play;final TextView name,meta;final Button control;
        VaultEngine.Entry entry;boolean media;
        Cell(boolean grid){
            this.grid=grid;root=grid?column():row();root.setTag(this);root.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);root.setPadding(dp(grid?10:12),dp(10),dp(grid?10:8),dp(10));
            art=new FrameLayout(MainActivity.this);art.setBackground(box(0xff21313d,12));art.setClipToOutline(true);
            glyph=new ImageView(MainActivity.this);glyph.setScaleType(ImageView.ScaleType.CENTER);art.addView(glyph,new FrameLayout.LayoutParams(-1,-1));
            image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(image,new FrameLayout.LayoutParams(-1,-1));
            play=new ImageView(MainActivity.this);play.setPadding(dp(6),dp(6),dp(6),dp(6));play.setBackground(box(0xc00d141b,20));play.setImageDrawable(VaultUi.icon(MainActivity.this,"play",INK,18));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.BOTTOM|Gravity.END);pp.setMargins(0,0,dp(8),dp(8));art.addView(play,pp);
            name=text("",14,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);meta=text("",12,MUTED);meta.setSingleLine();meta.setEllipsize(TextUtils.TruncateAt.END);
            control=iconButton("more","Opções do arquivo");control.setPadding(dp(8),dp(12),dp(8),dp(12));control.setBackground(VaultUi.ripple(MainActivity.this,Color.TRANSPARENT,12));control.setOnClickListener(v->{if(entry==null)return;if(selectionMode)toggleSelection(entry);else fileMenu(entry);});
            LinearLayout labels=column();labels.addView(name);add(labels,meta,4);
            if(grid){root.addView(art,new LinearLayout.LayoutParams(-1,dp(96)));LinearLayout bottom=row();bottom.addView(labels,new LinearLayout.LayoutParams(0,-2,1));bottom.addView(control,new LinearLayout.LayoutParams(dp(40),dp(48)));add(root,bottom,6);}
            else{root.addView(art,new LinearLayout.LayoutParams(dp(52),dp(52)));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=dp(12);root.addView(labels,np);root.addView(control,new LinearLayout.LayoutParams(dp(44),dp(48)));}
        }
        void bind(VaultEngine.Entry e){
            boolean changed=entry==null||!entry.id.equals(e.id);entry=e;media=!e.folder&&(e.mime.startsWith("image/")||e.mime.startsWith("video/"));
            name.setText(e.name);meta.setText(detail(e));root.setBackground(VaultUi.ripple(MainActivity.this,selected.contains(e.id)?0xff263f42:SURFACE,16));
            if(changed){image.setTag(null);image.setImageDrawable(null);glyph.setImageDrawable(VaultUi.icon(MainActivity.this,e.folder?"folder":e.mime.startsWith("video/")?"video":e.mime.startsWith("image/")?"image":e.mime.startsWith("audio/")?"audio":"file",e.folder?0xffebc78c:ACCENT,grid?36:26));}
            play.setVisibility(grid&&e.mime.startsWith("video/")?View.VISIBLE:View.GONE);image.setVisibility(media?View.VISIBLE:View.GONE);
            String icon=selectionMode?(selected.contains(e.id)?"check":"circle"):"more";control.setCompoundDrawables(null,VaultUi.icon(MainActivity.this,icon,selected.contains(e.id)?ACCENT:MUTED,22),null,null);control.setContentDescription((selectionMode?"Selecionar ":"Opções de ")+e.name);control.setSelected(selected.contains(e.id));
            if(media&&!scrolling&&!busy&&!mediaActive)thumbnails.bind(e,image);
        }
    }
    private class FileAdapter extends BaseAdapter{
        public int getCount(){return visible.isEmpty()?1:visible.size();}public Object getItem(int p){return visible.isEmpty()?null:visible.get(p);}public long getItemId(int p){return p;}@Override public boolean isEnabled(int p){return !visible.isEmpty();}
        @Override public int getViewTypeCount(){return 3;}@Override public int getItemViewType(int p){return visible.isEmpty()?2:gridMode?1:0;}
        public View getView(int p,View old,android.view.ViewGroup parent){
            if(visible.isEmpty()){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(32),dp(16),dp(32));ImageView mark=new ImageView(MainActivity.this);mark.setImageDrawable(VaultUi.icon(MainActivity.this,search.length()>0?"search":trashView?"trash":"folder",ACCENT,38));mark.setBackground(box(SURFACE,24));mark.setPadding(dp(20),dp(20),dp(20),dp(20));empty.addView(mark,new LinearLayout.LayoutParams(dp(80),dp(80)));TextView heading=text(search.length()>0?"Nenhum resultado":trashView?"Tudo em ordem":"Seu espaço, do seu jeito",19,INK);heading.setGravity(Gravity.CENTER);add(empty,heading,18);TextView hint=text(search.length()>0?"Tente buscar por outro nome.":trashView?"A lixeira está vazia.":"Mova arquivos ou crie uma pasta para começar.",13,MUTED);hint.setGravity(Gravity.CENTER);add(empty,hint,8);return empty;}
            Cell cell=old!=null&&old.getTag() instanceof Cell&&((Cell)old.getTag()).grid==gridMode?(Cell)old.getTag():new Cell(gridMode);cell.bind(visible.get(p));return cell.root;
        }
    }
    private String detail(VaultEngine.Entry e){if(trashView)return "Excluído em "+DateFormat.getDateInstance(DateFormat.SHORT,new Locale("pt","BR")).format(new Date(e.trashedAt));return e.folder?"Pasta · "+children(e.id)+" itens":size(e.size);}
    private int children(String id){return summary==null?0:summary.children.getOrDefault(id,0);}
    private String type(VaultEngine.Entry e){if(e.mime.startsWith("image/"))return "IMG";if(e.mime.startsWith("video/"))return "VID";if(e.mime.startsWith("audio/"))return "ÁUD";int dot=e.name.lastIndexOf('.');return dot>=0?e.name.substring(dot+1).toUpperCase(Locale.ROOT).substring(0,Math.min(4,e.name.length()-dot-1)):"ARQ";}

    private void options(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);String[] labels={"Salvar backup criptografado","Chave de recuperação","Armazenamento","Alterar senha","Ajuda e informações"};for(String label:labels)menu.getMenu().add(label);
        menu.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();if(label.equals(labels[0]))saveBackup();else if(label.equals(labels[1]))createRecovery();else if(label.equals(labels[2]))storageInfo();else if(label.equals(labels[3]))changePassword();else help();return true;});menu.show();
    }
    private void fileMenu(VaultEngine.Entry e){
        if(!unlocked||busy)return;
        if(e.isTrashed()){
            track(new AlertDialog.Builder(this).setTitle(e.name).setItems(new String[]{"Restaurar","Excluir definitivamente","Selecionar"},(d,which)->{if(which==0)restoreSelected(Collections.singletonList(e.id));else if(which==1)purgeSelected(Collections.singletonList(e.id));else{selectionMode=true;selected.add(e.id);updateSelection();}}).setNegativeButton("Fechar",null).show());return;
        }
        String[] labels=e.folder?new String[]{"Abrir pasta","Renomear","Mover para outra pasta","Excluir pasta"}:new String[]{"Abrir arquivo","Mover para fora do cofre","Copiar para fora (manter no cofre)","Renomear","Mover para outra pasta","Excluir do cofre"};
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
                if(cancelRequested)break;
                try{exportBranch(root,target);if(cancelRequested)throw new CancellationException();vault.delete(root.id);success[0]++;}
                catch(Exception e){failed[0]++;details.append("\n• ").append(root.name).append(": preservado no cofre. ").append(friendly(e));}
            }
        },()->{selectionDone();notice(cancelRequested?"Retirada interrompida":"Retirada concluída",success[0]+" item(ns) retirado(s). "+failed[0]+" preservado(s)."+details+(failed[0]>0?"\n\nOs destinos com falha podem conter cópias parciais.":"")+"\n\nItens retirados não entram nos novos backups. Itens preservados continuam no cofre. Backups antigos permanecem como foram salvos.");},this::error);
    }
    private void exportBranch(VaultEngine.Entry entry,Uri parent)throws Exception{
        if(cancelRequested)throw new CancellationException();operationProgress.phase("Retirando · "+entry.name);
        Uri target=createDestination(parent,entry.folder?DocumentsContract.Document.MIME_TYPE_DIR:entry.mime,entry.name);
        if(entry.folder){List<VaultEngine.Entry> children=new ArrayList<>();for(VaultEngine.Entry e:vault.list())if(!e.isTrashed()&&e.parent.equals(entry.id))children.add(e);for(VaultEngine.Entry child:children)exportBranch(child,target);}
        else{try(OutputStream out=write(target)){vault.exportFile(entry.id,out,operationProgress);}try(InputStream in=read(target)){if(!vault.matches(entry.id,in,operationProgress))throw new IOException("A verificação do destino falhou.");}}
    }
    private Uri createDestination(Uri parent,String mime,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));Set<String> names=new HashSet<>();
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c==null)throw new IOException("Não foi possível conferir a pasta de destino.");while(c.moveToNext())names.add(c.getString(0).toLowerCase(Locale.ROOT));}
        String candidate=name;int suffix=2,dot=name.lastIndexOf('.');boolean folderType=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);String base=dot>0&&!folderType?name.substring(0,dot):name,ext=dot>0&&!folderType?name.substring(dot):"";
        while(names.contains(candidate.toLowerCase(Locale.ROOT)))candidate=base+" ("+(suffix++)+")"+ext;
        Uri created=DocumentsContract.createDocument(getContentResolver(),parent,mime.isEmpty()?"application/octet-stream":mime,candidate);if(created==null)throw new IOException("Não foi possível criar o arquivo de destino.");return created;
    }
    private void storageInfo(){
        if(!unlocked)return;
        run("Consultando armazenamento…",()->{},()->{Summary info=summary;if(info==null)return;notice("Armazenamento do cofre","Cofre: "+size(info.stored)+"\nNa lixeira: "+size(info.trash)+"\nLivre no aparelho: "+size(info.free)+"\n\nÚltimo backup confirmado: "+(info.lastBackup==0?"ainda não há":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("pt","BR")).format(new Date(info.lastBackup)))+"\n"+(info.needsBackup?"Há alterações ainda sem backup.":"Nenhuma alteração pendente de backup.")+"\n\nPara mover um arquivo, é necessário espaço livre para uma cópia criptografada dele antes de remover o original. Backups precisam de espaço para o cofre inteiro no destino.");},this::error);
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
        if(!unlocked||summary==null)return;
        track(new AlertDialog.Builder(this).setTitle("O que entra neste backup").setMessage(backupContents()+"\n\nSomente o conteúdo atual do cofre. Fotos e vídeos da galeria, arquivos retirados e itens excluídos definitivamente não entram. A lixeira continua dentro do cofre.\n\nCopiar para fora mantém o arquivo no cofre e no backup. Para retirá-lo, use Mover para fora do cofre.\n\nReserve espaço para todo o cofre e mantenha o app aberto até concluir. Backups antigos não são alterados; conservam os arquivos, a senha e a chave da data em que foram salvos.").setNegativeButton("Cancelar",null).setPositiveButton("Escolher destino",(d,w)->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Cofre-AMZ-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.ROOT).format(new Date())+".amzcofre");launch(i,SAVE_BACKUP);
        }).show());
    }
    private String backupContents(){
        Summary info=summary;if(info==null)return "";
        return "Meus arquivos: "+backupFileCount(info.activeFiles)+"\nLixeira: "+backupFileCount(info.trashFiles)+"\n"+(info.activeFiles+info.trashFiles==0?"Nenhum arquivo para incluir. Apenas a estrutura do cofre e os dados de acesso serão salvos.":"Inclui a estrutura de pastas e os dados de acesso do cofre.");
    }
    private String backupFileCount(int count){return count+" arquivo"+(count==1?"":"s");}
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
            run("Restaurando e verificando arquivos…",()->{try(InputStream in=read(uri)){if(code==null)vault.restore(in,pass,operationProgress);else vault.restoreUsingRecovery(in,code,pass,operationProgress);}finally{Arrays.fill(pass,'\0');}},()->{unlocked=true;showExplorer();armLock();notice("Cofre restaurado","Todos os arquivos foram verificados. Guarde seu backup em um local seguro.");},e->notice("Não foi possível restaurar","Confira a senha ou chave de recuperação, a integridade do backup e o espaço disponível. "+friendly(e)));return;
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
                try(OutputStream out=write(uri)){vault.exportFile(id,out,operationProgress);}
                try(InputStream in=read(uri)){if(!vault.matches(id,in,operationProgress))throw new IOException("A cópia salva não passou na verificação. O original permanece no cofre.");}
                if(cancelRequested)throw new CancellationException();if(move)vault.delete(id);
            },()->{showExplorer();notice(move?"Arquivo retirado":"Cópia salva",move?"O arquivo está no destino escolhido e foi removido do cofre. Não entrará nos novos backups. Backups antigos permanecem como foram salvos.":"A cópia normal está no destino escolhido. O arquivo continua no cofre e será incluído nos backups. Para removê-lo do cofre, use Mover para fora do cofre.");},e->{error(e);});
        }else if(request==SAVE_BACKUP){
            run("Salvando backup criptografado…",()->{try(OutputStream out=write(uri)){vault.backup(out,operationProgress);}try(InputStream in=read(uri)){vault.verifyBackup(in,operationProgress);}if(cancelRequested)throw new CancellationException();vault.markBackupCompleted();},()->{showExplorer();notice("Backup salvo e verificado",backupContents()+"\n\nSomente o que está no cofre foi salvo. Arquivos retirados não foram incluídos. Guarde o backup fora do app; use a senha ou chave da data em que foi salvo para restaurar.");},this::error);
        }
    }
    private void importFiles(List<Uri> uris){
        String parent=folder;StringBuilder report=new StringBuilder();int[] moved={0},retained={0},failed={0};
        run("Movendo arquivos para o cofre…",()->{
            for(Uri uri:uris){
                if(cancelRequested)break;
                String name=displayName(uri);VaultEngine.Entry entry;operationProgress.phase("Criptografando · "+name);
                try{
                    String mime=getContentResolver().getType(uri);String unique=uniqueName(parent,name);
                    long sourceSize=documentSize(uri);
                    if(sourceSize>=0&&VaultEngine.encryptedSize(sourceSize)+32L*1024*1024>vault.availableBytes())throw new IOException("Espaço livre insuficiente para proteger este arquivo. É necessário espaço temporário para uma cópia dele.");
                    try(InputStream input=read(uri)){entry=vault.importFile(input,unique,mime,parent,operationProgress);}
                }catch(Exception e){failed[0]++;report.append("\n• ").append(name).append(": não importado; original preservado. ").append(friendly(e));continue;}
                try{
                    // Detect a source changed since encryption; never remove the newly changed source.
                    // Flush the directory entry too, before removing an original outside the app.
                    FileDescriptor directory=android.system.Os.open(new File(getFilesDir(),"vault-v1").getPath(),android.system.OsConstants.O_RDONLY,0);
                    try{android.system.Os.fsync(directory);}finally{android.system.Os.close(directory);}
                    try(InputStream input=read(uri)){if(!vault.matches(entry.id,input,operationProgress))throw new IOException("O original mudou durante a transferência.");}
                    if(cancelRequested)throw new CancellationException();
                    if(!DocumentsContract.isDocumentUri(this,uri)||!DocumentsContract.deleteDocument(getContentResolver(),uri))throw new IOException("O local de origem não permitiu apagar.");
                    moved[0]++;
                }catch(Exception e){retained[0]++;report.append("\n• ").append(name).append(": protegido no cofre; original ainda está na origem.");}
            }
        },()->{showExplorer();String summary=moved[0]+" movido(s). "+retained[0]+" protegido(s), com original mantido. "+failed[0]+" não importado(s).";notice(cancelRequested?"Transferência interrompida":retained[0]>0?"Confira os originais":"Transferência concluída",summary+(cancelRequested?" Os itens restantes continuam na origem.":"")+report.toString()+(retained[0]>0?"\n\nVocê pode apagar os originais pelo aplicativo Arquivos. A cópia criptografada já foi verificada.":""));},this::error);
    }
    private String uniqueName(String parent,String requested){
        String clean=requested.replaceAll("[\\\\/\\p{Cntrl}]","_");if(clean.length()>180)clean=clean.substring(0,180);if(clean.isEmpty()||clean.equals(".")||clean.equals(".."))clean="Arquivo";
        String candidate=clean;int n=2;Set<String> used=new HashSet<>();for(VaultEngine.Entry e:vault.list())if(!e.isTrashed()&&e.parent.equals(parent))used.add(e.name.toLowerCase(Locale.ROOT));
        int dot=clean.lastIndexOf('.');String base=dot>0?clean.substring(0,dot):clean,ext=dot>0?clean.substring(dot):"";
        while(used.contains(candidate.toLowerCase(Locale.ROOT)))candidate=base+" ("+(n++)+")"+ext;return candidate;
    }
    private long documentSize(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}catch(Exception ignored){}return -1;}
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
        if(!unlocked||busy)return;
        if(e.mime.startsWith("image/")){
            thumbnails.pause();LinearLayout content=previewFrame(e.name);VaultImageView image=new VaultImageView(this,vault,e,()->unlocked);
            content.addView(image,new LinearLayout.LayoutParams(-1,0,1));previewCleanup=()->{image.close();if(unlocked)ui.post(resumeThumbnails);};preview.show();return;
        }
        if(e.mime.startsWith("video/")||e.mime.startsWith("audio/")){
            thumbnails.pause();LinearLayout content=previewFrame(e.name);mediaActive=true;ui.removeCallbacks(timeout);MediaPager pager=new MediaPager(content,e);mediaPager=pager;
            previewCleanup=()->{pager.close();if(mediaPager==pager)mediaPager=null;mediaActive=false;if(unlocked){armLock();ui.post(resumeThumbnails);}};preview.show();return;
        }
        if(e.size>new File(getCacheDir().getPath()).getUsableSpace()-16L*1024*1024){notice("Pouco espaço","Libere espaço para visualizar este arquivo.");return;}
        File dir=new File(getCacheDir(),"preview");dir.mkdirs();File file=new File(dir,e.name);
        run("Abrindo arquivo…",()->{try(FileOutputStream out=new FileOutputStream(file)){vault.exportFile(e.id,out,operationProgress);}},()->{
            try{
                if(e.mime.equals("application/pdf")||e.name.toLowerCase(Locale.ROOT).endsWith(".pdf"))showPdf(e,file);
                else if(e.mime.startsWith("text/")||e.name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|csv|json|log)$"))showText(e,file);
                else externalPreview(e,file);
            }catch(Exception err){clearPreviews();error(err);}
        },err->{clearPreviews();error(err);});
    }
    /** Keeps one dialog and one decoder; rapid taps coalesce while the old player releases. */
    private final class MediaPager implements AutoCloseable {
        private final List<VaultEngine.Entry> entries=new ArrayList<>();
        private final FrameLayout host;private final TextView title,position;private final Button previous,next;
        private VaultMediaView playing;private int index;private volatile boolean closed;private boolean releasing;
        MediaPager(LinearLayout content,VaultEngine.Entry opened){
            String kind=opened.mime.startsWith("video/")?"video/":"audio/";int found=-1;
            for(VaultEngine.Entry entry:visible)if(!entry.folder&&!entry.isTrashed()&&entry.mime.startsWith(kind)){if(entry.id.equals(opened.id))found=entries.size();entries.add(entry);}
            if(found<0){entries.clear();entries.add(opened);index=0;}else index=found;
            title=preview.findViewById(R.id.vault_preview_title);host=new FrameLayout(MainActivity.this);content.addView(host,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout navigation=row();previous=button("Anterior",false);next=button("Próximo",false);previous.setId(R.id.vault_media_previous);next.setId(R.id.vault_media_next);
            previous.setContentDescription(kind.equals("video/")?"Vídeo anterior":"Áudio anterior");next.setContentDescription(kind.equals("video/")?"Próximo vídeo":"Próximo áudio");previous.setCompoundDrawables(VaultUi.icon(MainActivity.this,"back",ACCENT,20),null,null,null);next.setCompoundDrawables(null,null,VaultUi.icon(MainActivity.this,"arrow",ACCENT,20),null);previous.setCompoundDrawablePadding(dp(6));next.setCompoundDrawablePadding(dp(6));
            position=text("",12,MUTED);position.setId(R.id.vault_media_position);position.setGravity(Gravity.CENTER);navigation.addView(previous,new LinearLayout.LayoutParams(-2,dp(48)));navigation.addView(position,new LinearLayout.LayoutParams(0,-2,1));navigation.addView(next,new LinearLayout.LayoutParams(-2,dp(48)));add(content,navigation,6);
            previous.setOnClickListener(v->step(-1));next.setOnClickListener(v->step(1));showTarget();
        }
        private void step(int offset){if(closed||!unlocked)return;int target=index+offset;if(target<0||target>=entries.size())return;index=target;showTarget();}
        private void showTarget(){
            if(closed||!unlocked)return;title.setText(entries.get(index).name);position.setText((index+1)+" de "+entries.size());previous.setEnabled(index>0);next.setEnabled(index+1<entries.size());previous.setAlpha(previous.isEnabled()?1f:.35f);next.setAlpha(next.isEnabled()?1f:.35f);
            if(releasing)return;
            if(playing!=null){VaultMediaView old=playing;playing=null;releasing=true;host.removeAllViews();TextView preparing=text("Preparando mídia…",14,MUTED);preparing.setGravity(Gravity.CENTER);host.addView(preparing,new FrameLayout.LayoutParams(-1,-1));old.close(()->{releasing=false;if(!closed&&unlocked)showTarget();});return;}
            host.removeAllViews();playing=new VaultMediaView(MainActivity.this,vault,entries.get(index),()->!closed&&unlocked);host.addView(playing,new FrameLayout.LayoutParams(-1,-1));
        }
        @Override public void close(){if(closed)return;closed=true;if(playing!=null){playing.close();playing=null;}host.removeAllViews();entries.clear();}
    }
    private LinearLayout previewFrame(String title){
        closePreview();Dialog dialog=new Dialog(this,R.style.AppTheme);dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content=column();content.setPadding(dp(16),dp(16),dp(16),dp(16));content.setBackgroundColor(BG);LinearLayout bar=row();TextView label=text(title,19,INK);label.setId(R.id.vault_preview_title);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button close=iconButton("close","Fechar visualização");bar.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->closePreview());content.addView(bar);dialog.setContentView(content);
        dialog.setOnDismissListener(d->{if(preview!=dialog)return;Runnable cleanup=previewCleanup;previewCleanup=null;preview=null;if(cleanup!=null)cleanup.run();clearPreviews();});preview=dialog;return content;
    }
    private void showText(VaultEngine.Entry entry,File file)throws Exception{
        if(file.length()>2*1024*1024){externalPreview(entry,file);return;}
        byte[] data=java.nio.file.Files.readAllBytes(file.toPath());LinearLayout content=previewFrame(entry.name);ScrollView scroll=new ScrollView(this);TextView text=text(new String(data,java.nio.charset.StandardCharsets.UTF_8),16,INK);text.setTypeface(Typeface.MONOSPACE);text.setPadding(dp(8),dp(18),dp(8),dp(18));text.setTextIsSelectable(true);scroll.addView(text);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Arrays.fill(data,(byte)0);preview.show();
    }
    private void showPdf(VaultEngine.Entry entry,File file){
        LinearLayout content=previewFrame(entry.name);VaultPdfView pdf=new VaultPdfView(this,file);content.addView(pdf,new LinearLayout.LayoutParams(-1,0,1));previewCleanup=pdf::close;preview.show();
    }
    private void externalPreview(VaultEngine.Entry entry,File file){
        track(new AlertDialog.Builder(this).setTitle("Abrir em outro aplicativo?").setMessage("O aplicativo escolhido terá acesso a uma cópia descriptografada e poderá salvá-la. O acesso temporário será encerrado quando você voltar ao Cofre AMZ.").setNegativeButton("Cancelar",(d,w)->clearPreviews()).setPositiveButton("Abrir",(d,w)->{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".preview",file);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,entry.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Arquivo",uri));
            try{external=true;unlocked=false;thumbnails.clear();worker.execute(vault::lock);all.clear();showLocked();startActivityForResult(Intent.createChooser(i,"Abrir arquivo"),20);}catch(Exception err){external=false;clearPreviews();notice("Nenhum aplicativo disponível","Instale um aplicativo compatível com este formato ou retire o arquivo para uma pasta.");}
        }).setOnCancelListener(d->clearPreviews()).show());
    }
    private void closePreview(){if(preview!=null){Dialog old=preview;Runnable cleanup=previewCleanup;preview=null;previewCleanup=null;old.setOnDismissListener(null);old.dismiss();if(cleanup!=null)cleanup.run();clearPreviews();}}
    private void clearPreviews(){
        File dir=new File(getCacheDir(),"preview");File[] files=dir.listFiles();if(files!=null)for(File f:files){try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".preview",f);revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}f.delete();}
    }
    private void help(){notice("Cofre AMZ 1.1.3","• Funciona offline, sem permissão de internet.\n\n• Use Selecionar ou segure um item para mover, retirar ou enviar vários arquivos à lixeira.\n\n• Grade mostra miniaturas de fotos e vídeos compatíveis. As miniaturas não são salvas em texto legível.\n\n• A lixeira continua criptografada. Restaure os itens ou exclua definitivamente para liberar espaço. Não há exclusão automática.\n\n• Gere sua chave de recuperação no menu e guarde o código fora do cofre. Quem tiver o código e os dados do cofre pode recuperar o acesso. Sem senha ou código previamente gerado, não é possível recuperar.\n\n• Salve um backup sempre que aparecer o aviso. Ele inclui os arquivos, a lixeira e a recuperação ativada. Backups antigos mantêm a senha e a chave que tinham ao ser salvos.\n\n• Desinstalar o app ou limpar seus dados apaga o cofre. Instale atualizações por cima para manter seus arquivos.\n\n• Ao importar, o original só é removido após a verificação. Se o Android não permitir apagar na origem, o app avisa.\n\n• Bloqueio ao sair do app e após 2 minutos sem interação.\n\nAES-256-GCM · Android 8+");}
    private interface Job{void execute()throws Exception;}
    private interface Failure{void accept(Exception e);}
    private void run(String label,Job job,Runnable done,Failure failed){
        if(busy)return;filterGeneration++;busy=true;cancelRequested=false;thumbnails.pause();lockAfter=false;ui.removeCallbacks(timeout);lastProgress=0;progressPhase=label;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress=new ProgressDialog(this);progress.setTitle(label);progress.setMessage("Aguarde. Seus arquivos estão sendo verificados.");progress.setIndeterminate(true);progress.setCancelable(false);progress.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);boolean cancellable=label.startsWith("Movendo arquivos")||label.startsWith("Retirando")||label.startsWith("Salvando backup")||label.startsWith("Salvando e conferindo")||label.startsWith("Abrindo arquivo")||label.startsWith("Restaurando e verificando");if(cancellable)progress.setButton(DialogInterface.BUTTON_NEGATIVE,"Interromper",(d,w)->{});progress.show();
        if(cancellable)progress.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v->{cancelRequested=true;progress.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);progress.setMessage("Interrompendo com segurança. Aguarde…");});
        worker.execute(()->{Exception failure=null;try{job.execute();}catch(Exception e){failure=e;}loadSummary();Exception outcome=failure;
            ui.post(()->{busy=false;if(isFinishing()||isDestroyed()){vault.lock();return;}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);closeProgress();if(lockAfter||stopped){vault.lock();unlocked=false;lockAfter=false;clearPreviews();showLocked();return;}if(outcome==null)done.run();else failed.accept(outcome);if(unlocked)armLock();});});
    }
    private void closeProgress(){ProgressDialog old=progress;progress=null;if(old!=null&&old.isShowing()&&old.getWindow()!=null&&old.getWindow().getDecorView().isAttachedToWindow())old.dismiss();}
    private void progressBytes(long bytes){if(cancelRequested)throw new CancellationException("Operação interrompida. Os arquivos ainda não transferidos permanecem na origem.");long now=SystemClock.elapsedRealtime();if(now-lastProgress<300)return;lastProgress=now;ui.post(()->{if(progress!=null&&!cancelRequested)progress.setMessage(progressPhase+"\n"+size(bytes)+" processados nesta etapa.\nMantenha o app aberto até concluir.");});}
    private void error(Exception e){notice("Não foi possível concluir",friendly(e)+"\n\nSe a retirada falhou, a versão do cofre foi preservada. Confira qualquer arquivo parcial no destino antes de apagá-lo.");}
    private String friendly(Exception e){if(e instanceof CancellationException)return "Operação interrompida. Os arquivos ainda não transferidos foram preservados.";if(e instanceof javax.crypto.AEADBadTagException)return "Senha incorreta ou arquivo danificado.";String m=e.getMessage();return m==null?"Verifique o espaço disponível e a permissão para acessar os arquivos.":m;}
    private void notice(String title,String body){if(!isFinishing()&&!isDestroyed())track(new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Entendi",null).show());}
    private void track(Dialog d){dialogs.removeIf(x->!x.isShowing());dialogs.add(d);if(d.getWindow()!=null)d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private void closeDialogs(){for(Dialog d:new ArrayList<>(dialogs))if(d.isShowing())d.dismiss();dialogs.clear();}
    private void clearRestorePassword(){if(restorePassword!=null)Arrays.fill(restorePassword,'\0');restorePassword=null;restoreRecovery=null;}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(),0);}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");v.setLineSpacing(dp(2),1);v.setTypeface(Typeface.create(size>=18?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;}
    private EditText input(String hint,boolean secret){EditText v=new EditText(this);v.setTextColor(INK);v.setHintTextColor(MUTED);v.setHint(hint);v.setTextSize(15);v.setSingleLine(true);v.setPadding(dp(15),dp(13),dp(15),dp(13));v.setBackground(box(SURFACE,14));v.setMinHeight(dp(52));v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);v.setInputType(secret?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT);return v;}
    private Button button(String label,boolean primary){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextSize(14);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));v.setTextColor(primary?BG:ACCENT);v.setPadding(dp(14),dp(8),dp(14),dp(8));v.setMinHeight(dp(48));v.setMinimumHeight(dp(48));v.setMinWidth(0);v.setMinimumWidth(0);v.setStateListAnimator(null);v.setElevation(0);v.setBackground(VaultUi.ripple(this,primary?ACCENT:SURFACE,14));return v;}
    private Button iconButton(String icon,String description){Button b=button("",false);b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setCompoundDrawables(null,VaultUi.icon(this,icon,ACCENT,22),null,null);b.setContentDescription(description);return b;}
    private Button tabButton(String label,boolean active){Button b=button(label,false);b.setPadding(dp(8),dp(6),dp(8),dp(6));b.setTextColor(active?ACCENT:MUTED);b.setBackground(VaultUi.ripple(this,active?0xff213735:Color.TRANSPARENT,12));return b;}
    private GradientDrawable box(int color,int radius){return VaultUi.shape(this,color,radius);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private View padded(View child){LinearLayout p=column();p.setPadding(dp(22),dp(8),dp(22),dp(8));p.addView(child);return p;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(child,p);}
    private static String size(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(new Locale("pt","BR"),"%.1f KB",bytes/1024.0);if(bytes<1024L*1024*1024)return String.format(new Locale("pt","BR"),"%.1f MB",bytes/(1024.0*1024));return String.format(new Locale("pt","BR"),"%.2f GB",bytes/(1024.0*1024*1024));}
    @Override public void onBackPressed(){if(busy)return;if(unlocked&&selectionMode){selectionMode=false;selected.clear();updateSelection();return;}if(unlocked&&trashView){switchTrash(false);return;}if(unlocked&&!folder.isEmpty()){try{folder=vault.get(folder).parent;}catch(Exception e){folder="";}showExplorer();}else if(unlocked)requestLock();else super.onBackPressed();}
}
