package dev.mobilecodex.app;
import android.app.Application;
import android.content.*;
import android.content.pm.*;
import androidx.core.content.FileProvider;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static dev.mobilecodex.app.core.Json.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class AppUpdatesTest {
    private Context context;private AppUpdates updates;private File apk;private JSONObject candidate;
    private SigningInfo signing(String certificate){SigningInfo info=Shadow.newInstanceOf(SigningInfo.class);Shadows.shadowOf(info).setSignatures(new Signature[]{new Signature(certificate)});return info;}
    private PackageInfo archive(String certificate){PackageInfo info=new PackageInfo();info.packageName=context.getPackageName();info.versionName="0.1.12-alpha";info.setLongVersionCode(13);info.applicationInfo=new ApplicationInfo();info.applicationInfo.minSdkVersion=29;info.signingInfo=signing(certificate);return info;}
    private void set(String name,Object value)throws Exception{var f=AppUpdates.class.getDeclaredField(name);f.setAccessible(true);f.set(updates,value);}
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();PackageInfo current=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES);current.versionName="0.1.11-alpha";current.setLongVersionCode(12);current.signingInfo=signing("010203");Shadows.shadowOf(context.getPackageManager()).installPackage(current);
        byte[] bytes={1,2,3,4};StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
        File dir=new File(context.getCacheDir(),"app-updates");dir.mkdirs();apk=new File(dir,hash+".apk");Files.write(apk.toPath(),bytes);
        candidate=obj("versionName","0.1.12-alpha","size",bytes.length,"sha256",hash.toString(),"repository","nokryong/mobile-codex");
        updates=new AppUpdates(context,s->{});set("candidate",candidate);set("ready",true);Shadows.shadowOf(context.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(),archive("010203"));
    }
    @After public void cleanup(){updates.shutdown();}
    private File prepare()throws Exception{CompletableFuture<File> result=new CompletableFuture<>();updates.prepareInstall(candidate.getString("sha256"),(file,error)->{if(error==null)result.complete(file);else result.completeExceptionally(error);});return result.get(5,TimeUnit.SECONDS);}
    @Test public void rechecksApkAndLocksChangesUntilInstallerReturns()throws Exception{
        assertEquals(apk,prepare());assertEquals("installing",updates.snapshot().getString("status"));assertThrows(IOException.class,()->updates.configure("other/repo",true));assertThrows(IOException.class,updates::clear);
        updates.installEnded();assertFalse(updates.snapshot().getBoolean("busy"));assertTrue(updates.snapshot().getBoolean("ready"));
    }
    @Test public void changedApkAndWrongSignerNeverReachInstaller()throws Exception{
        Files.write(apk.toPath(),new byte[]{4,3,2,1});assertThrows(ExecutionException.class,this::prepare);assertFalse(updates.snapshot().getBoolean("ready"));
        Files.write(apk.toPath(),new byte[]{1,2,3,4});set("ready",true);Shadows.shadowOf(context.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(),archive("040506"));assertThrows(ExecutionException.class,this::prepare);assertTrue(updates.snapshot().getString("message").contains("서명키"));
    }
    @Test public void providerSharesOnlyDedicatedUpdateDirectory()throws Exception{
        assertEquals("content",FileProvider.getUriForFile(context,context.getPackageName()+".updates",apk).getScheme());
        assertThrows(IllegalArgumentException.class,()->FileProvider.getUriForFile(context,context.getPackageName()+".updates",new File(context.getFilesDir(),"sessions.json")));
    }
    @Test public void publicHttpsDownloadHostsOnly()throws Exception{
        AppUpdates.validateHost(new URL("https://release-assets.githubusercontent.com/path?sig=test"));
        for(String url:new String[]{"http://github.com/file","https://user@github.com/file","https://github.com.evil.test/file","https://127.0.0.1/file","https://github.com:444/file"})assertThrows(IOException.class,()->AppUpdates.validateHost(new URL(url)));
    }
    @Test public void cachedDownloadCanBeRecoveredButSourceChangesInvalidateIt()throws Exception{
        context.getSharedPreferences("app-updates",0).edit().putString("downloaded",candidate.toString()).commit();
        AppUpdates restored=new AppUpdates(context,s->{});try{assertTrue(restored.snapshot().getBoolean("ready"));restored.configure("different/repo",false);assertFalse(restored.snapshot().getBoolean("ready"));assertFalse(restored.snapshot().getBoolean("prereleases"));}finally{restored.shutdown();}
    }
    @Test public void staleUiCannotDownloadOrInstallAChangedCandidate()throws Exception{
        assertThrows(IOException.class,()->updates.download("00".repeat(32)));
        assertThrows(IOException.class,()->updates.prepareInstall("00".repeat(32),(file,error)->fail("must not install")));
        assertFalse(updates.snapshot().getBoolean("busy"));
    }
}
