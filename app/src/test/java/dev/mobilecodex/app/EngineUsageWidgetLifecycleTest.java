package dev.mobilecodex.app;

import android.app.Application;
import android.content.Intent;
import dev.mobilecodex.app.core.RpcClient;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineUsageWidgetLifecycleTest {
    private Application context;
    private Engine engine;
    private Child child;
    private RpcClient rpc;
    private CountDownLatch quotaStarted, releaseQuota;
    private final AtomicInteger modelReads = new AtomicInteger();
    private final JSONObject account = obj("type", "chatgpt", "email", "widget@example.test", "planType", "plus");
    private static final Engine.Ui UI = new Engine.Ui() {
        public void event(String name, JSONObject data) {}
        public void approval(Engine.Approval approval) {}
    };
    private static final class Child extends Process {
        boolean alive = true;
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return alive; }
        public void destroy() { alive = false; }
    }
    private void set(String name, Object value) throws Exception {
        var field = Engine.class.getDeclaredField(name); field.setAccessible(true); field.set(engine, value);
    }
    private Object get(String name) throws Exception {
        var field = Engine.class.getDeclaredField(name); field.setAccessible(true); return field.get(engine);
    }
    private static void delete(File path) {
        File[] children = path.listFiles();
        if (children != null) for (File child : children) delete(child);
        path.delete();
    }
    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        delete(new File(context.getFilesDir(), "account-profiles"));
        delete(new File(context.getFilesDir(), ".codex"));
        File home = new File(context.getFilesDir(), ".codex"); home.mkdirs();
        Files.writeString(new File(home, "auth.json").toPath(), obj("tokens", obj("access_token", "test-widget-token")).toString());
        new AccountProfiles(home, context.getFilesDir()).saveCurrent(account);
        engine = new Engine(context);
        engine.setTestAccountValidation(true);
        engine.setTestTransport((method, args) -> {
            if (method.equals("account/read")) return obj("account", account);
            if (method.equals("account/rateLimits/read")) {
                if (quotaStarted != null) {
                    quotaStarted.countDown();
                    assertTrue("quota test must release the engine queue", releaseQuota.await(5, TimeUnit.SECONDS));
                }
                return obj("rateLimits", obj("primary", obj("usedPercent", 27)));
            }
            if (method.equals("model/list")) {
                modelReads.incrementAndGet();
                return obj("data", new JSONArray().put(obj("model", "widget-test-model", "isDefault", true)));
            }
            throw new AssertionError("Unexpected RPC: " + method);
        });
        child = new Child();
        rpc = new RpcClient(InputStream.nullInputStream(), OutputStream.nullOutputStream(), new RpcClient.Listener() {
            public void notification(String method, JSONObject params) {}
            public void request(Object id, String method, JSONObject params) {}
            public void disconnected(Throwable error) {}
        });
        // The subprocess is a fake; all ownership, UI and refresh decisions run in the real Engine.
        set("process", child); set("rpc", rpc); set("account", account); set("authState", "signed_in");
    }
    @After public void after() {
        if (releaseQuota != null) releaseQuota.countDown();
        if (engine != null) engine.io.shutdownNow();
        if (rpc != null) rpc.close();
        delete(new File(context.getFilesDir(), "account-profiles"));
        delete(new File(context.getFilesDir(), ".codex"));
    }
    private void drain() throws Exception { engine.io.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    private CompletableFuture<JSONObject> refresh() {
        CompletableFuture<JSONObject> completed = new CompletableFuture<>();
        engine.refreshUsageWidget("codex", (result, error) -> {
            if (error == null) completed.complete(result); else completed.completeExceptionally(error);
        });
        return completed;
    }
    private JSONObject call(String action) throws Exception {
        CompletableFuture<JSONObject> completed = new CompletableFuture<>();
        engine.handle(action, obj(), (result, error) -> {
            if (error == null) completed.complete(result); else completed.completeExceptionally(error);
        });
        return completed.get(5, TimeUnit.SECONDS);
    }
    private void assertPromotedOnce() throws Exception {
        assertSame(child, get("process")); assertTrue(child.alive); assertFalse(rpc.isClosed());
        assertNull(get("widgetProcess")); assertEquals(1, modelReads.get());
        Intent started = shadowOf(context).getNextStartedService();
        assertNotNull("widget takeover must start the foreground service", started);
        assertEquals(EngineService.class.getName(), started.getComponent().getClassName());
        assertNull("normal reuse must not restart the service", shadowOf(context).getNextStartedService());
        assertEquals("widget-test-model", call("state").getJSONArray("models").getJSONObject(0).getString("model"));
    }
    @Test public void uiAttachingDuringWidgetQuotaReadPromotesTheSameProcessOnce() throws Exception {
        set("widgetProcess", child); set("ready", false);
        quotaStarted = new CountDownLatch(1); releaseQuota = new CountDownLatch(1);
        CompletableFuture<JSONObject> completed = refresh();
        assertTrue(quotaStarted.await(5, TimeUnit.SECONDS));
        engine.attach(UI); releaseQuota.countDown();
        completed.get(5, TimeUnit.SECONDS); drain();
        engine.attach(UI); call("runtime.start"); drain();
        assertPromotedOnce();
    }
    @Test public void normalStartPromotesARetainedWidgetProcessWithoutRequiringUiAttachment() throws Exception {
        set("widgetProcess", child); set("ready", true);
        call("runtime.start"); call("runtime.start");
        assertPromotedOnce();
    }
    @Test public void observerAttachingDuringWidgetQuotaReadAlsoTakesOwnership() throws Exception {
        set("widgetProcess", child); set("ready", false);
        quotaStarted = new CountDownLatch(1); releaseQuota = new CountDownLatch(1);
        CompletableFuture<JSONObject> completed = refresh();
        assertTrue(quotaStarted.await(5, TimeUnit.SECONDS));
        engine.observe(UI); releaseQuota.countDown();
        completed.get(5, TimeUnit.SECONDS); drain();
        assertPromotedOnce();
    }
    @Test public void widgetOnlyRefreshReleasesOnlyItsOwnedProcessWithoutForegroundWork() throws Exception {
        set("widgetProcess", child); set("ready", false);
        refresh().get(5, TimeUnit.SECONDS); drain();
        assertFalse(child.alive); assertTrue(rpc.isClosed());
        assertNull(get("process")); assertNull(get("widgetProcess")); assertEquals(false, get("ready"));
        assertEquals(0, modelReads.get()); assertNull(shadowOf(context).getNextStartedService());
    }
    @Test public void widgetRefreshPreservesAnAlreadyForegroundRuntimeAndItsModels() throws Exception {
        set("ready", true);
        JSONArray models = new JSONArray().put(obj("model", "existing-model")); set("models", models);
        refresh().get(5, TimeUnit.SECONDS); drain();
        engine.attach(UI); drain();
        assertSame(child, get("process")); assertTrue(child.alive); assertFalse(rpc.isClosed());
        assertSame(models, get("models")); assertEquals(0, modelReads.get());
        assertNull(shadowOf(context).getNextStartedService());
    }
    @Test public void skippedWidgetRefreshCannotReleaseAnUnownedStartingProcess() throws Exception {
        set("ready", false); set("autoRestorePaused", true);
        refresh().get(5, TimeUnit.SECONDS); drain();
        assertSame(child, get("process")); assertTrue(child.alive); assertFalse(rpc.isClosed());
        assertEquals(0, modelReads.get()); assertNull(shadowOf(context).getNextStartedService());
    }
}
