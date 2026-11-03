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
    @Before public void before() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        remove(new File(context.getFilesDir(), ".codex"));
        remove(new File(context.getFilesDir(), "account-profiles"));
        Files.deleteIfExists(new File(context.getFilesDir(), "sessions.json").toPath());
        engine = new Engine(context);
        engine.attach(new Engine.Ui() {
            @Override public void event(String name, JSONObject data) { if (name.equals("diagnostics.available")) diagnostics.add(data); }
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
    @Test public void failedLoginNotificationAndChatErrorRecordTheirActualOperations() throws Exception {
        engine.setTestTransport((method, params) -> obj());
        handle("auth.login");
        notification("account/login/completed", obj("success", false, "error", "private login token /path"));
        assertEquals("login", diagnostics.get(0).getString("operation"));
        notification("error", obj("error", obj("message", "private conversation")));
        assertEquals("chat", diagnostics.get(1).getString("operation"));
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
        assertEquals("other", report.getString("operation"));
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
