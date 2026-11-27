package dev.mobilecodex.app;

import android.app.Application;
import dev.mobilecodex.app.core.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineDiagnosticTest {
    private Engine engine;
    private final List<JSONObject> diagnostics = new CopyOnWriteArrayList<>();
    private final List<JSONObject> recoveries = new CopyOnWriteArrayList<>();
    @Before public void before() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        remove(new File(context.getFilesDir(), ".codex"));
        remove(new File(context.getFilesDir(), "account-profiles"));
        Files.deleteIfExists(new File(context.getFilesDir(), "sessions.json").toPath());
        engine = new Engine(context);
        engine.attach(new Engine.Ui() {
            @Override public void event(String name, JSONObject data) { if (name.equals("diagnostics.available")) diagnostics.add(data); if (name.equals("diagnostics.cleared")) recoveries.add(data); }
            @Override public void approval(Engine.Approval approval) { fail("unexpected approval"); }
        });
        handle("state");
    }
    @After public void after() throws Exception {
        engine.io.shutdownNow();
        ((ScheduledExecutorService) field("approvalTimer")).shutdownNow();
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private Object field(String name) throws Exception {
        var field = Engine.class.getDeclaredField(name); field.setAccessible(true); return field.get(engine);
    }
    private void field(String name, Object value) throws Exception {
        var field = Engine.class.getDeclaredField(name); field.setAccessible(true); field.set(engine, value);
    }
    private JSONObject handle(String action) throws Exception {
        var result = new CompletableFuture<JSONObject>();
        engine.handle(action, obj(), (value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); });
        return result.get(5, TimeUnit.SECONDS);
    }
    private void notification(String name, JSONObject value) throws Exception {
        engine.io.submit(() -> {
            try {
                Method method = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class);
                method.setAccessible(true); method.invoke(engine, name, value);
            } catch (Exception error) { throw new RuntimeException(error); }
        }).get(5, TimeUnit.SECONDS);
    }
    private JSONObject loginPreview(int index, String stage, String kind) throws Exception {
        var reports = new ErrorReports(RuntimeEnvironment.getApplication());
        reports.capture(diagnostics.get(index));
        JSONObject payload = reports.preview().getJSONObject("payload");
        assertEquals(3, payload.getInt("schema"));
        assertEquals(stage, payload.getString("stage"));
        assertEquals(kind, payload.getString("failureKind"));
        assertFalse(payload.toString().contains("private"));
        return payload;
    }
    @Test public void failedLoginNotificationAndChatErrorRecordTheirActualOperations() throws Exception {
        engine.setTestTransport((method, params) -> obj());
        handle("auth.login");
        notification("account/login/completed", obj("success", false, "error", "private login token /path"));
        assertEquals("login", diagnostics.get(0).getString("operation"));
        assertEquals("login_failed", diagnostics.get(0).getString("errorCode"));
        assertTrue(loginPreview(0, "login_result", "unknown").isNull("elapsedMs"));
        notification("error", obj("error", obj("message", "private conversation")));
        assertEquals("chat", diagnostics.get(1).getString("operation"));
        notification("error", obj("error", obj("message", "private conversation", "codexErrorInfo", "usageLimitExceeded")));
        assertEquals("usage_limit", diagnostics.get(2).getString("errorCode"));
        assertFalse(diagnostics.toString().contains("private"));
    }
    @Test public void startupFailureDoesNotSupplyMeasurementsToTheNextActionOrNotification() throws Exception {
        // Inject a frozen launch failure at the existing in-process transport seam.
        engine.setTestTransport((method, params) -> {
            StartupDiagnostics stats = new StartupDiagnostics(); stats.bytes(17);
            throw new DiagnosticFailure("test launch failure", null, field("diagnosticOperation"), stats.snapshot("start_failure", "pending", false, 1, "none"));
        });
        assertThrows(ExecutionException.class, () -> handle("auth.login"));
        assertEquals("login", diagnostics.get(0).getString("operation"));
        assertEquals(17, diagnostics.get(0).getLong("stdoutBytes"));
        assertThrows(ExecutionException.class, () -> handle("chat.invalid"));
        assertEquals("chat", diagnostics.get(1).getString("operation"));
        assertFalse(diagnostics.get(1).has("stdoutBytes"));
        notification("account/login/completed", obj("success", false));
        assertEquals("login", diagnostics.get(2).getString("operation"));
        assertFalse(diagnostics.get(2).has("stdoutBytes"));
    }
    @Test public void oldProcessDisconnectCannotReportOrBorrowReplacementMeasurements() throws Exception {
        Child old = new Child(), replacement = new Child();
        field("process", replacement); field("ready", true);
        engine.io.submit(() -> engine.codexDisconnected(old, null, null, new RuntimeFailure.Tail())).get(5, TimeUnit.SECONDS);
        assertTrue(diagnostics.isEmpty()); assertTrue(replacement.isAlive());
        engine.io.submit(() -> engine.codexDisconnected(replacement, null, null, new RuntimeFailure.Tail())).get(5, TimeUnit.SECONDS);
        assertEquals(1, diagnostics.size());
        JSONObject report = DiagnosticReport.build(diagnostics.get(0), "0.3.6", 35, "arm64", "other", "phone", "unknown", "unknown");
        assertEquals("transport_closed", report.getString("errorCode")); assertEquals("running", report.getString("stage"));
        assertEquals("start", report.getString("operation"));
        assertTrue(report.isNull("stdoutBytes")); assertTrue(report.isNull("elapsedMs"));
        assertEquals(-1, report.getLong("spawnMs")); assertTrue(report.getBoolean("processAlive"));
    }
    @Test public void disconnectDuringLoginIsNotAStartupOrChatFailure() throws Exception {
        Child child = new Child();
        field("process", child); field("ready", true); field("loginInProgress", true);
        engine.io.submit(() -> engine.codexDisconnected(child, null, null, new RuntimeFailure.Tail())).get(5, TimeUnit.SECONDS);
        assertEquals("login", diagnostics.get(0).getString("operation"));
        assertFalse(diagnostics.get(0).has("spawnMs"));
    }
    @Test public void preparationFailureReportsStageBeforeProcessCreation() throws Exception {
        assertThrows(ExecutionException.class, () -> handle("runtime.start"));
        JSONObject diagnostic = diagnostics.get(0);
        assertEquals("runtime_check", diagnostic.getString("stage"));
        assertEquals("start", diagnostic.getString("operation"));
        assertEquals(-1, diagnostic.getLong("spawnMs"));
        assertFalse(diagnostic.getBoolean("processAlive"));
    }
    @Test public void loginRpcFailureRetainsStageAndSafeExceptionCategory() throws Exception {
        engine.setTestTransport((method, params) -> {
            if (method.equals("account/login/start")) throw new java.net.SocketTimeoutException("private-token /private/path");
            return obj();
        });
        assertThrows(ExecutionException.class, () -> handle("auth.login"));
        JSONObject diagnostic = diagnostics.get(0);
        assertEquals("login_rpc", diagnostic.getString("stage"));
        assertEquals("request_timeout", diagnostic.getString("errorCode"));
        assertEquals("active", diagnostic.getString("homeKind"));
        assertFalse(diagnostic.toString().contains("private"));
        assertFalse(diagnostic.has("stdoutBytes"));
        assertTrue(loginPreview(0, "login_rpc", "timeout").getLong("elapsedMs") >= 0);
    }
    @Test public void loginPreparationFailureDoesNotRollbackOrEraseLiveLogin() throws Exception {
        field("liveRemote", "private-remote"); field("loginInProgress", true);
        assertThrows(ExecutionException.class, () -> handle("auth.login"));
        assertEquals("login_prepare", diagnostics.get(0).getString("stage"));
        assertEquals("active", loginPreview(0, "login_prepare", "busy").getString("homeKind"));
        assertEquals(true, field("loginInProgress"));
        assertEquals("private-remote", field("liveRemote"));
        assertFalse(diagnostics.toString().contains("private"));
    }

    @Test public void missingLoginFileIsDistinguishedFromANonFileAtTheSameBoundary() throws Exception {
        File auth = new File(engine.processHomeForTest(), "auth.json");
        assertFalse(auth.exists());
        assertThrows(ExecutionException.class, () -> handle("auth.add"));
        loginPreview(0, "login_home_prepare", "file_missing");
        assertTrue(auth.mkdir());
        assertThrows(ExecutionException.class, () -> handle("auth.add"));
        loginPreview(1, "login_home_prepare", "unknown");
    }
    @Test public void completionOnlyUsesTheMatchingAttemptClockAndNeverClassifiesItsText() throws Exception {
        engine.setTestTransport((method, params) -> obj("loginId", "private-attempt"));
        handle("auth.login");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(350, TimeUnit.MILLISECONDS);
        for (String id : List.of("private-other", "private-attempt", "private-attempt")) {
            notification("account/login/completed", obj("success", false, "loginId", id,
                "error", "timeout permission denied private-token@example.test /private/path"));
        }
        assertTrue(loginPreview(0, "login_result", "unknown").isNull("elapsedMs"));
        long elapsed = loginPreview(1, "login_result", "unknown").getLong("elapsedMs");
        assertTrue(elapsed >= 300);
        assertEquals(0, elapsed % 100);
        assertTrue(loginPreview(2, "login_result", "unknown").isNull("elapsedMs"));
    }
    @Test public void remoteAccountReadAndLocalCredentialApplicationHaveSeparateStages() throws Exception {
        engine.setTestTransport((method, params) -> {
            if (method.equals("account/read")) throw new java.nio.file.AccessDeniedException("/private/path");
            return obj("loginId", "private-attempt");
        });
        handle("auth.login");
        notification("account/login/completed", obj("success", true, "loginId", "private-attempt"));
        loginPreview(0, "login_account_read", "permission_denied");
        engine.setTestTransport((method, params) -> method.equals("account/read")
            ? obj("account", obj("email", "private@example.test")) : obj("loginId", "private-second"));
        handle("auth.login");
        notification("account/login/completed", obj("success", true, "loginId", "private-second"));
        loginPreview(1, "login_account_apply", "file_missing");
    }

    @Test public void backgroundChatFailureAndRecoveryUseTheirOwnLocalScope() throws Exception {
        JSONObject foreground=obj("id","local-A","codexThreadId","remote-A","messages",new org.json.JSONArray());
        JSONObject background=obj("id","local-B","codexThreadId","remote-B","messages",new org.json.JSONArray());
        ((org.json.JSONArray)field("sessions")).put(foreground).put(background);
        field("active",foreground); field("threadId","local-A");
        notification("error",obj("threadId","remote-B","error",obj("message","private")));
        assertEquals("local-B",diagnostics.get(0).optString("_scope"));
        notification("turn/started",obj("threadId","remote-A","turn",obj("id","turn-A")));
        notification("turn/started",obj("threadId","remote-B","turn",obj("id","turn-B")));
        notification("turn/completed",obj("threadId","remote-A","turn",obj("id","turn-A","status","completed")));
        assertEquals("local-A",recoveries.get(0).optString("_scope"));
        notification("turn/completed",obj("threadId","remote-B","turn",obj("id","turn-B","status","completed")));
        assertEquals("local-B",recoveries.get(1).optString("_scope"));
        notification("turn/completed",obj("threadId","unknown","turn",obj("id","turn-C","status","completed")));
        assertEquals(2,recoveries.size());
        // A newer failure removes its running turn. A delayed old success must
        // not clear that newer report merely because the conversation matches.
        notification("turn/started",obj("threadId","remote-B","turn",obj("id","new-failed-turn")));
        notification("turn/completed",obj("threadId","remote-B","turn",obj("id","new-failed-turn","status","failed")));
        notification("turn/completed",obj("threadId","remote-B","turn",obj("id","turn-B","status","completed")));
        assertEquals(2,recoveries.size());
        notification("turn/completed",obj("threadId","remote-A","turn",obj("status","completed")));
        assertEquals(2,recoveries.size());
        JSONObject publicReport=DiagnosticReport.build(diagnostics.get(0),"0.3.6",35,"arm64","other","phone","unknown","unknown");
        assertFalse(publicReport.toString().contains("local-B")); assertFalse(publicReport.has("_scope"));
    }
    private static final class Child extends Process {
        private boolean alive = true;
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return alive; }
        public void destroy() { alive = false; }
    }
}
