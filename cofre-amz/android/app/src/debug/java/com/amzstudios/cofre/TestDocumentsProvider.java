package com.amzstudios.cofre;
import android.provider.*;
import android.database.*;
import android.os.*;
import java.io.*;
/** Test fixture only: excluded from the release APK. */
public class TestDocumentsProvider extends DocumentsProvider {
    private File file(String id)throws FileNotFoundException{if(!id.equals("source.txt")&&!id.equals("destination.txt"))throw new FileNotFoundException();return new File(getContext().getFilesDir(),"fixture-"+id);}
    public boolean onCreate(){return true;}
    public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});}
    public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{String[] cols=projection!=null?projection:new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS};MatrixCursor c=new MatrixCursor(cols);MatrixCursor.RowBuilder row=c.newRow();for(String col:cols){if(col.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))row.add(id);else if(col.equals(OpenableColumns.DISPLAY_NAME))row.add(id);else if(col.equals(OpenableColumns.SIZE))row.add(file(id).length());else if(col.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))row.add("text/plain");else if(col.equals(DocumentsContract.Document.COLUMN_FLAGS))row.add(DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_WRITE);else row.add(null);}return c;}
    public Cursor queryChildDocuments(String parent,String[] projection,String order){return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID});}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
    public void deleteDocument(String id)throws FileNotFoundException{if(!file(id).delete())throw new FileNotFoundException();}
    public String getDocumentType(String id){return "text/plain";}
}
