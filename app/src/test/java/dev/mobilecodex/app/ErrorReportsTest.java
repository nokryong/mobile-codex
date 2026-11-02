package dev.mobilecodex.app;
import android.app.Application;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class)
public class ErrorReportsTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @After public void stop() { worker.shutdownNow(); }
    private static final class Transport implements ErrorReports.Transport {
        final List<JSONObject> reports = new ArrayList<>();
        boolean offline, held, cancelled; CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public JSONObject post(String route, JSONObject body) throws Exception {
            if (route.equals("challenge")) {
                if (held) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
                if (offline) throw new IOException("private token/path/account");
                return obj("challengeId","a".repeat(48),"difficulty",16);
            }
            reports.add(new JSONObject(body.toString())); return obj("url","https://github.com/nokryong/mobile-codex/issues/99","grouped",true);
        }
        @Override public void cancel() { cancelled=true; release.countDown(); }
    }
    private ErrorReports make(Transport transport) { return new ErrorReports(RuntimeEnvironment.getApplication(), worker, transport); }
    private JSONObject send(ErrorReports service, String id, boolean consent) throws Exception { CompletableFuture<JSONObject> result=new CompletableFuture<>(); service.send(id,consent,result::complete); return result.get(20,TimeUnit.SECONDS); }
    @Test public void noUploadUntilPreviewAndExplicitConsent() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        assertEquals("unavailable",service.preview().optString("status")); service.capture(ErrorReports.genericDiagnostic());
        assertEquals("failed",send(service,"made-up",true).optString("status"));
        String id=service.preview().optString("reportId"); assertEquals("failed",send(service,id,false).optString("status")); assertTrue(transport.reports.isEmpty());
        assertEquals("sent",send(service,id,true).optString("status")); assertEquals(1,transport.reports.size());
    }
    @Test public void reportUiIsPackagedWithoutOpeningAccessToOtherFiles() {
        var assets=RuntimeEnvironment.getApplication().getAssets();
        assertEquals(200,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/reports.js")).getStatusCode());
        assertEquals(403,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/services/diagnostic-relay/lib/github.mjs")).getStatusCode());
    }
    @Test public void httpMetadataDoesNotExposeAndroidsDefaultDeviceFingerprint() throws Exception {
        java.net.HttpURLConnection connection = new java.net.HttpURLConnection(new java.net.URL("https://report.example")) {
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
        connection.setRequestProperty("User-Agent","PRIVATE_DEVICE_MODEL PRIVATE_BUILD");
        ErrorReports.configureReportConnection(connection);
        assertEquals("MobileCodex-Diagnostics/1",connection.getRequestProperty("User-Agent"));
        assertNull(connection.getRequestProperty("Authorization")); assertNull(connection.getRequestProperty("Cookie"));
        assertFalse(connection.getInstanceFollowRedirects()); assertEquals(15000,connection.getReadTimeout());
    }
    @Test public void previewFrozenAcrossNewErrorsAndRetriesReuseItsId() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(obj("errorCode","initialize_timeout","operation","login")); JSONObject preview=service.preview(); String id=preview.optString("reportId");
        service.capture(obj("errorCode","transport_closed")); transport.offline=true;
        JSONObject failed=send(service,id,true); assertEquals("failed",failed.optString("status")); assertFalse(failed.toString().contains("private"));
        transport.offline=false; assertEquals("sent",send(service,id,true).optString("status"));
        assertEquals(id,transport.reports.get(0).optString("reportId")); assertEquals("initialize_timeout",transport.reports.get(0).optString("errorCode"));
    }
    @Test public void doubleClickIsBusyAndCancellationDoesNotSubmit() throws Exception {
        Transport transport=new Transport(); transport.held=true; ErrorReports service=make(transport); service.capture(ErrorReports.genericDiagnostic());
        String id=service.preview().optString("reportId"); CompletableFuture<JSONObject> first=new CompletableFuture<>();service.send(id,true,first::complete);
        assertTrue(transport.entered.await(5,TimeUnit.SECONDS)); assertEquals("busy",send(service,id,true).optString("status"));
        service.cancel(); assertEquals("cancelled",first.get(20,TimeUnit.SECONDS).optString("status")); assertTrue(transport.cancelled);assertTrue(transport.reports.isEmpty());
    }
}
