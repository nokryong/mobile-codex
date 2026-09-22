package dev.mobilecodex.app.core;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static dev.mobilecodex.app.core.Json.*;
public class UpdateCatalogTest {
    private JSONObject release(String version,boolean pre){String name="mobile-codex-"+version+"-arm64.apk";return obj("tag_name","v"+version,"prerelease",pre,"body","<script>untrusted</script>","assets",array(obj("name",name,"state","uploaded","size",100,"digest","sha256:"+"ab".repeat(32),"browser_download_url","https://github.com/owner/repo/releases/download/v"+version+"/"+name)));}
    @Test public void numericAndPrereleaseVersionsSortCorrectly()throws Exception{
        assertTrue(UpdateCatalog.compare("0.1.11-alpha","0.1.9")>0);assertTrue(UpdateCatalog.compare("1.0.0","1.0.0-rc.2")>0);
        assertTrue(UpdateCatalog.compare("1.0.0-beta.2","1.0.0-alpha.9")>0);assertTrue(UpdateCatalog.compare("1.0.0-rc.10","1.0.0-rc.2")>0);
        assertThrows(IOException.class,()->UpdateCatalog.compare("latest","1.0.0"));
    }
    @Test public void choosesHighestEligibleArm64AssetWithDigest()throws Exception{
        JSONArray releases=array(release("0.1.9",false),release("0.1.12-alpha",true),release("0.1.10",false));
        assertEquals("0.1.12-alpha",UpdateCatalog.latest(releases,"owner/repo",true).getString("versionName"));
        assertEquals("0.1.10",UpdateCatalog.latest(releases,"Owner/Repo",false).getString("versionName"));
        JSONObject bad=release("9.0.0",false);bad.getJSONArray("assets").getJSONObject(0).put("digest","missing");releases.put(bad);
        assertEquals("0.1.12-alpha",UpdateCatalog.latest(releases,"owner/repo",true).getString("versionName"));
    }
    @Test public void rejectsWrongRepositoryUrlsDraftsAndMalformedSources()throws Exception{
        JSONObject bad=release("9.0.0",false);bad.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://github.com/other/repo/releases/download/v9.0.0/mobile-codex-9.0.0-arm64.apk");
        assertNull(UpdateCatalog.latest(array(bad,release("8.0.0",false).put("draft",true)),"owner/repo",true));
        for(String repo:List.of("../repo","owner/repo/extra","owner/..","https://github.com/a/b","a/b?token=x"))assertThrows(IOException.class,()->UpdateCatalog.repository(repo));
    }
    @Test public void requiresUpgradePackageNameAndCompatibleForwardSignatures()throws Exception{
        UpdateCatalog.identity("app",11,"app",12,"0.1.11","0.1.11",29,35,true);
        assertThrows(IOException.class,()->UpdateCatalog.identity("app",11,"other",12,"0.1.11","0.1.11",29,35,true));
        assertThrows(IOException.class,()->UpdateCatalog.identity("app",11,"app",11,"0.1.11","0.1.11",29,35,true));
        assertThrows(IOException.class,()->UpdateCatalog.identity("app",11,"app",12,"0.1.11","0.1.10",29,35,true));
        assertThrows(IOException.class,()->UpdateCatalog.identity("app",11,"app",12,"0.1.11","0.1.11",36,35,true));
        assertThrows(IOException.class,()->UpdateCatalog.identity("app",11,"app",12,"0.1.11","0.1.11",29,35,false));
        assertTrue(UpdateCatalog.compatibleSigners(Set.of("old"),Set.of("new"),Set.of("old","new")));
        assertFalse(UpdateCatalog.compatibleSigners(Set.of("new"),Set.of("old"),Set.of("old")));
        assertFalse(UpdateCatalog.compatibleSigners(Set.of("a","b"),Set.of("a"),Set.of("a","b")));
        assertFalse(UpdateCatalog.compatibleSigners(Set.of(),Set.of(),Set.of()));
    }
    private byte[] data="verified APK bytes".getBytes(StandardCharsets.UTF_8);
    private String hash()throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format("%02x",b&255));return s.toString();}
    @Test public void verifiesEveryByteAndRejectsTruncationExcessAndWrongHash()throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertEquals(data.length,VerifiedDownload.copy(new ByteArrayInputStream(data),output,data.length,hash(),()->false,n->{}));assertArrayEquals(data,output.toByteArray());
        assertThrows(IOException.class,()->VerifiedDownload.copy(new ByteArrayInputStream(data),output,data.length+1,hash(),()->false,n->{}));
        assertThrows(IOException.class,()->VerifiedDownload.copy(new ByteArrayInputStream(data),output,data.length-1,hash(),()->false,n->{}));
        assertThrows(IOException.class,()->VerifiedDownload.copy(new ByteArrayInputStream(data),output,data.length,"00".repeat(32),()->false,n->{}));
    }
    @Test public void cancellationStopsBeforeReadingOrCommittingBytes()throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertThrows(IOException.class,()->VerifiedDownload.copy(new ByteArrayInputStream(data),output,data.length,hash(),()->true,n->{}));assertEquals(0,output.size());
    }
}
