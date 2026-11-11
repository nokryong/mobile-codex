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
    @Test public void successfulSendConsumesReportAndConsent() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        assertFalse(service.status().optBoolean("available"));
        service.capture(ErrorReports.genericDiagnostic()); assertTrue(service.status().optBoolean("available"));
        String id=service.preview().optString("reportId");
        assertEquals("sent",send(service,id,true).optString("status"));
        assertFalse(service.status().optBoolean("available"));
        assertEquals("unavailable",service.preview().optString("status"));
        assertEquals("preview_required",send(service,id,true).optString("code"));
        assertEquals(1,transport.reports.size());
    }
    @Test public void sendingOlderPreviewPreservesNewerFailure() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(obj("operation","login","errorCode","login_failed"));
        String oldId=service.preview().optString("reportId");
        service.capture(obj("operation","chat","errorCode","turn_failed"));
        assertEquals("sent",send(service,oldId,true).optString("status"));
        assertTrue(service.status().optBoolean("available"));
        JSONObject newer=service.preview(); assertNotEquals(oldId,newer.optString("reportId"));
        assertEquals("turn_failed",newer.getJSONObject("payload").optString("errorCode"));
    }
    @Test public void recoveryOnlyClearsMatchingOperationAndConsent() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(obj("operation","login")); String loginId=service.preview().optString("reportId");
        assertFalse(service.clear("start")); assertTrue(service.status().optBoolean("available"));
        service.capture(obj("operation","chat","_scope","local-chat")); assertTrue(service.clear("login"));
        assertFalse(service.clear("login"));
        assertTrue(service.status().optBoolean("available"));
        assertEquals("preview_required",send(service,loginId,true).optString("code"));
        JSONObject chat=service.preview(); assertEquals("chat",chat.getJSONObject("payload").optString("operation"));
        assertTrue(service.clear("chat", "local-chat")); assertFalse(service.status().optBoolean("available"));
        assertEquals("preview_required",send(service,chat.optString("reportId"),true).optString("code"));
        assertTrue(transport.reports.isEmpty());
    }
    @Test public void cancellationRequiresFreshPreviewButRetainsRetryableReport() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(ErrorReports.genericDiagnostic()); String id=service.preview().optString("reportId");
        service.cancel(); assertTrue(service.status().optBoolean("available"));
        assertEquals("preview_required",send(service,id,true).optString("code"));
        assertEquals(id,service.preview().optString("reportId"));
        assertEquals("sent",send(service,id,true).optString("status"));
    }
    @Test public void recoveryCancelsPendingSendWithoutClearingUnrelatedFailure() throws Exception {
        Transport transport=new Transport(); transport.held=true; ErrorReports service=make(transport);
        service.capture(obj("operation","login")); String id=service.preview().optString("reportId");
        CompletableFuture<JSONObject> result=new CompletableFuture<>(); service.send(id,true,result::complete);
        assertTrue(transport.entered.await(5,TimeUnit.SECONDS));
        service.capture(obj("operation","chat")); service.clear("login");
        assertEquals("cancelled",result.get(20,TimeUnit.SECONDS).optString("status"));
        assertTrue(service.status().optBoolean("available")); assertTrue(transport.reports.isEmpty());
        assertEquals("chat",service.preview().getJSONObject("payload").optString("operation"));
    }

    @Test public void returnedPreviewCannotMutateConsentedPayload() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(obj("operation","login","errorCode","login_failed"));
        JSONObject preview=service.preview(); String id=preview.optString("reportId");
        preview.getJSONObject("payload").put("operation","chat").put("errorCode","turn_failed");
        assertEquals("sent",send(service,id,true).optString("status"));
        assertEquals("login",transport.reports.get(0).optString("operation"));
        assertEquals("login_failed",transport.reports.get(0).optString("errorCode"));
    }

    @Test public void recoveryUsesPrivateConversationScopeWithoutLeakingIt() throws Exception {
        Transport transport=new Transport(); ErrorReports service=make(transport);
        service.capture(obj("operation","chat","_scope","private-conversation-A"));
        String older=service.preview().optString("reportId");
        service.capture(obj("operation","chat","_scope","private-conversation-B"));
        assertTrue(service.clear("chat","private-conversation-A"));
        assertTrue(service.status().optBoolean("available"));
        assertEquals("preview_required",send(service,older,true).optString("code"));
        JSONObject preview=service.preview();
        assertFalse(service.clear("chat","private-conversation-A"));
        assertFalse(service.clear("chat"));
        assertFalse(preview.toString().contains("private-conversation"));
        assertFalse(preview.toString().contains("_scope"));
        assertFalse(service.status().toString().contains("private-conversation"));
        assertEquals("sent",send(service,preview.optString("reportId"),true).optString("status"));
        assertFalse(transport.reports.toString().contains("private-conversation"));
        assertFalse(transport.reports.toString().contains("_scope"));
    }
    @Test public void matchingBackgroundRecoveryClearsLatestAndPreview() {
        ErrorReports service=make(new Transport());
        service.capture(obj("operation","chat","_scope","background")); service.preview();
        assertTrue(service.clear("chat","background"));
        assertFalse(service.status().optBoolean("available"));
    }

}
