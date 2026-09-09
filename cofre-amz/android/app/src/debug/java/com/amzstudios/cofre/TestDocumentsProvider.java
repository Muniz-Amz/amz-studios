package com.amzstudios.cofre;
import android.provider.*;
import android.database.*;
import android.os.*;
import java.io.*;
/** Synthetic SAF fixtures only: this provider is excluded from the release APK. */
public class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS};
    private File file(String id)throws FileNotFoundException{
        if(id.equals("source.txt")||id.equals("destination.txt"))return new File(getContext().getFilesDir(),"fixture-"+id);
        File root=new File(getContext().getFilesDir(),"fixture-export");
        if(id.equals("export")){root.mkdirs();return root;}
        if(!id.startsWith("export/"))throw new FileNotFoundException();
        try{File child=new File(root,id.substring(7)).getCanonicalFile();if(!child.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new FileNotFoundException();return child;}
        catch(IOException e){throw new FileNotFoundException();}
    }
    public boolean onCreate(){return true;}
    public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});}
    private void append(MatrixCursor c,String id)throws FileNotFoundException{
        File f=file(id);MatrixCursor.RowBuilder row=c.newRow();
        for(String col:c.getColumnNames()){
            if(col.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))row.add(id);
            else if(col.equals(OpenableColumns.DISPLAY_NAME))row.add(f.getName().replaceFirst("^fixture-", ""));
            else if(col.equals(OpenableColumns.SIZE))row.add(f.length());
            else if(col.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))row.add(f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain");
            else if(col.equals(DocumentsContract.Document.COLUMN_FLAGS))row.add(DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_WRITE|(f.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0));
            else row.add(null);
        }
    }
    public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(projection!=null?projection:COLUMNS);append(c,id);return c;}
    public Cursor queryChildDocuments(String parent,String[] projection,String order)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(projection!=null?projection:COLUMNS);File[] children=file(parent).listFiles();if(children!=null)for(File child:children)append(c,parent+"/"+child.getName());return c;}
    public String createDocument(String parent,String mime,String name)throws FileNotFoundException{
        if(!name.equals(new File(name).getName())||name.equals(".")||name.equals(".."))throw new FileNotFoundException();
        String id=parent+"/"+name;File target=file(id);
        try{if(target.exists()||!(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)?target.mkdir():target.createNewFile()))throw new IOException();return id;}
        catch(IOException e){throw new FileNotFoundException();}
    }
    public boolean isChildDocument(String parent,String child){return child.startsWith(parent+"/");}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
    public void deleteDocument(String id)throws FileNotFoundException{File target=file(id);VaultEngine.removeTree(target);if(target.exists())throw new FileNotFoundException();}
    public String getDocumentType(String id)throws FileNotFoundException{return file(id).isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain";}
}
