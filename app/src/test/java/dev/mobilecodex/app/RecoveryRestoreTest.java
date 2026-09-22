package dev.mobilecodex.app;
import android.app.Application;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.Files;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class RecoveryRestoreTest {
    private static String readText(java.nio.file.Path path)throws IOException{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}
    private static void writeText(java.nio.file.Path path,String text)throws IOException{Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private DocumentStore store; private File file;
    public static class Provider extends ContentProvider {
        File file;
        public boolean onCreate(){return true;}
        public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
            MatrixCursor cursor=new MatrixCursor(projection);boolean children=uri.getPath().endsWith("/children");String id=DocumentsContract.getDocumentId(uri);boolean root=!children&&id.equals("root");
            if(root || file.exists())cursor.addRow(new Object[]{root?"root":"file",root?"Project":"a.txt",root?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain",root?0:file.length(),root?0:file.lastModified(),DocumentsContract.Document.FLAG_SUPPORTS_WRITE});return cursor;
        }
        public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));}
        public String getType(Uri u){return "text/plain";}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }
    @Before public void setup()throws Exception{
        Context context=RuntimeEnvironment.getApplication();file=new File(context.getCacheDir(),"a.txt");writeText(file.toPath(),"before");
        Provider provider=new Provider();provider.file=file;android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="test.documents";provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal("test.documents",provider);
        store=new DocumentStore(context);java.lang.reflect.Field field=DocumentStore.class.getDeclaredField("tree");field.setAccessible(true);field.set(store,Uri.parse("content://test.documents/tree/root"));
    }
    private String edit()throws Exception{JSONObject read=store.read("a.txt");return store.commit(store.prepare("mobile_write",obj("path","a.txt","content","after","expectedSha256",read.getString("sha256")))).getString("recoveryId");}
    @Test public void providerWriteCanBePreviewedRestoredAndBackedUpAgain()throws Exception{
        String id=edit();JSONObject preview=store.previewRecovery(id);assertTrue(preview.getBoolean("canRestore"));assertEquals("before",preview.getString("before"));assertEquals("after",preview.getString("after"));
        JSONObject restore=store.recoveryMutation(id);JSONObject result=store.commit(store.prepare(restore.getString("operation"),restore.getJSONObject("arguments")));
        assertEquals("before",readText(file.toPath()));assertFalse(result.getString("recoveryId").isBlank());
    }
    @Test public void laterProviderEditsPreventRestore()throws Exception{
        String id=edit();writeText(file.toPath(),"external edit");assertFalse(store.previewRecovery(id).getBoolean("canRestore"));assertThrows(IOException.class,()->store.recoveryMutation(id));assertEquals("external edit",readText(file.toPath()));
    }
}
