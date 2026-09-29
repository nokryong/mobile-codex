package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.robolectric.shadows.ShadowSystemClock;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineAccountRestoreTest {
    private Context context;
    private Engine engine;
    private final JSONObject signedIn = obj("type", "chatgpt", "email", "saved@example.test", "planType", "plus");
    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        delete(new File(context.getFilesDir(), "account-profiles"));
        delete(new File(context.getFilesDir(), ".codex"));
        Files.deleteIfExists(new File(context.getFilesDir(), "sessions.json").toPath());
    }
    @After public void after() {
        if (engine != null) engine.io.shutdownNow();
        delete(new File(context.getFilesDir(), "account-profiles"));
        delete(new File(context.getFilesDir(), ".codex"));
    }
    private static void delete(File path) {
        File[] children = path.listFiles();
        if (children != null) for (File child : children) delete(child);
        path.delete();
    }
    private void savedLogin() throws Exception {
        File home = new File(context.getFilesDir(), ".codex"); home.mkdirs();
        Files.write(new File(home, "auth.json").toPath(), obj("tokens", obj("access_token", "test-secret-not-for-ui")).toString().getBytes(StandardCharsets.UTF_8));
        new AccountProfiles(home, context.getFilesDir()).saveCurrent(signedIn);
    }
    private void create(Engine.TestTransport transport) {
        engine = new Engine(context); engine.setTestTransport(transport); engine.setTestAccountValidation(true);
    }
    private JSONObject handle(String action) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, obj(), (value, error) -> {
            if (error == null) result.complete(value); else result.completeExceptionally(error);
        });
        return result.get(5, TimeUnit.SECONDS);
    }
    private JSONObject response(String method, JSONObject args) throws Exception {
        if ("account/read".equals(method)) {
            assertFalse("startup must not force token rotation", args.getBoolean("refreshToken"));
            return obj("account", signedIn);
        }
        if ("account/rateLimits/read".equals(method)) return obj("rateLimits", obj("primary", obj("usedPercent", 12)));
        throw new AssertionError("Unexpected RPC: " + method);
    }
    private List<JSONObject> observeStates() throws Exception {
        List<JSONObject> states = new CopyOnWriteArrayList<>();
        engine.attach(new Engine.Ui() {
            @Override public void event(String name, JSONObject data) { if ("state".equals(name)) states.add(data); }
            @Override public void approval(Engine.Approval approval) { fail("restore must not request approval"); }
        });
        handle("state"); return states;
    }
    @Test public void coldReopenRestoresSavedAccountWithoutLoginAndCoalescesLifecycleCallbacks() throws Exception {
        savedLogin(); AtomicInteger reads = new AtomicInteger();
        create((method, args) -> { if ("account/read".equals(method)) reads.incrementAndGet(); return response(method, args); });
        JSONObject before = handle("state");
        assertEquals("unknown", before.getString("authState"));
        assertFalse(before.getBoolean("ready")); assertEquals(0, before.getJSONObject("account").length());
        List<JSONObject> states = observeStates();
        engine.restoreAccount(); engine.restoreAccount(); engine.restoreAccount();
        JSONObject after = handle("state");
        assertEquals(1, reads.get()); assertTrue(after.getBoolean("ready"));
        assertEquals("signed_in", after.getString("authState"));
        assertEquals("saved@example.test", after.getJSONObject("account").getString("email"));
        assertFalse(after.toString().contains("test-secret-not-for-ui"));
        assertFalse(states.stream().anyMatch(s -> "signed_out".equals(s.optString("authState"))));
    }
    @Test public void accountPublishesBeforeQuotaFinishesWithoutBlockingTheUiThread() throws Exception {
        savedLogin(); CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        create((method, args) -> {
            if ("account/rateLimits/read".equals(method)) { reading.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return response(method, args);
        });
        List<JSONObject> states = observeStates();
        try {
            engine.restoreAccount(); assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(states.stream().anyMatch(s -> "signed_in".equals(s.optString("authState"))));
        } finally { release.countDown(); }
        assertEquals("signed_in", handle("state").getString("authState"));
    }
    @Test public void firstInstallShowsLoginWithoutStartingAnEngine() throws Exception {
        create((method, args) -> { throw new AssertionError(method); });
        engine.restoreAccount();
        JSONObject state = handle("state");
        assertEquals("signed_out", state.getString("authState")); assertFalse(state.getBoolean("ready"));
    }
    @Test public void temporaryFailureStaysUnknownToLoginAndRetriesOnLaterForeground() throws Exception {
        savedLogin(); AtomicInteger reads = new AtomicInteger();
        create((method, args) -> {
            if ("account/read".equals(method) && reads.incrementAndGet() == 1) throw new IOException("offline");
            return response(method, args);
        });
        List<JSONObject> states = observeStates();
        engine.restoreAccount(); JSONObject failed = handle("state");
        assertEquals("error", failed.getString("authState"));
        assertFalse(states.stream().anyMatch(s -> "signed_out".equals(s.optString("authState"))));
        engine.restoreAccount(); handle("state"); assertEquals(1, reads.get());
        ShadowSystemClock.advanceBy(Duration.ofMillis(10_001)); engine.restoreAccount();
        assertEquals("signed_in", handle("state").getString("authState")); assertEquals(2, reads.get());
    }
    @Test public void actualRevocationRequiresLoginButDoesNotStartLoginForTheUser() throws Exception {
        savedLogin();
        create((method, args) -> {
            if ("account/rateLimits/read".equals(method)) throw new IOException("401 token_revoked");
            return response(method, args);
        });
        engine.restoreAccount(); JSONObject state = handle("state");
        assertEquals("signed_out", state.getString("authState"));
        assertEquals(0, state.getJSONObject("account").length());
        assertTrue(state.getJSONArray("accounts").getJSONObject(0).getBoolean("needsLogin"));
    }
    @Test public void revokedAccountCanReachDeviceLoginWithoutDestroyingItsSavedProfile() throws Exception {
        savedLogin(); File home = new File(context.getFilesDir(), ".codex");
        AccountProfiles profiles = new AccountProfiles(home, context.getFilesDir());
        String key = profiles.activeKey(); byte[] original = Files.readAllBytes(new File(home, "auth.json").toPath());
        AtomicInteger logins = new AtomicInteger();
        create((method, args) -> {
            if ("account/login/start".equals(method)) {
                logins.incrementAndGet(); return obj("loginId", "test-login", "userCode", "test-code", "verificationUrl", "https://auth.openai.com/codex/device");
            }
            if ("account/read".equals(method) && !engine.processHomeForTest().equals(home)) return obj("account", JSONObject.NULL);
            if ("account/rateLimits/read".equals(method)) throw new IOException("401 token_revoked");
            return response(method, args);
        });
        engine.restoreAccount(); assertEquals("signed_out", handle("state").getString("authState"));
        assertEquals("test-login", handle("auth.login").getString("loginId")); assertEquals(1, logins.get());
        assertNotEquals(home, engine.processHomeForTest()); assertEquals(key, profiles.activeKey());
        assertArrayEquals(original, Files.readAllBytes(new File(home, "auth.json").toPath()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount(); handle("state");
        assertEquals(1, logins.get()); assertNotEquals(home, engine.processHomeForTest());
    }
    @Test public void transientRefreshKeepsThePreviouslyConfirmedAccount() throws Exception {
        savedLogin(); AtomicInteger reads = new AtomicInteger();
        create((method, args) -> {
            if ("account/read".equals(method) && reads.incrementAndGet() > 1) throw new IOException("offline");
            return response(method, args);
        });
        engine.restoreAccount(); handle("state");
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount();
        JSONObject state = handle("state"); assertEquals("error", state.getString("authState"));
        assertEquals("saved@example.test", state.getJSONObject("account").getString("email"));
    }
    @Test public void addedAccountIsPublishedOnlyAfterItsCredentialPromotionCommits() throws Exception {
        assertAddedAccountPublication(false);
    }
    @Test public void rejectedAddedAccountNeverAppearsAsActive() throws Exception {
        assertAddedAccountPublication(true);
    }
    private void assertAddedAccountPublication(boolean reject) throws Exception {
        savedLogin(); File home = new File(context.getFilesDir(), ".codex");
        JSONObject added = obj("type", "chatgpt", "email", "added@example.test", "planType", "pro");
        AtomicInteger validation = new AtomicInteger();
        create((method, args) -> {
            File auth = new File(engine.processHomeForTest(), "auth.json");
            boolean isAdded = auth.isFile() && new String(Files.readAllBytes(auth.toPath()), StandardCharsets.UTF_8).contains("new-test-secret");
            if ("account/read".equals(method)) return obj("account", !auth.isFile() ? JSONObject.NULL : isAdded ? added : signedIn);
            if ("account/rateLimits/read".equals(method)) {
                if (isAdded && !engine.processHomeForTest().equals(home)) {
                    validation.incrementAndGet();
                    if (reject) throw new IOException("401 token_revoked");
                }
                return obj("rateLimits", obj());
            }
            if ("account/login/start".equals(method)) return obj("loginId", "added-test-login");
            if ("model/list".equals(method)) return obj("data", new org.json.JSONArray());
            throw new AssertionError(method);
        });
        engine.restoreAccount(); handle("state"); handle("auth.add");
        File pending = engine.processHomeForTest(); assertNotEquals(home, pending);
        Files.write(new File(pending, "auth.json").toPath(), obj("tokens", obj("access_token", "new-test-secret")).toString().getBytes(StandardCharsets.UTF_8));
        List<JSONObject> states = observeStates();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        engine.io.execute(() -> {
            try {
                java.lang.reflect.Method notify = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class);
                notify.setAccessible(true); notify.invoke(engine, "account/login/completed", obj("success", true));
                completed.complete(null);
            } catch (Throwable error) { completed.completeExceptionally(error); }
        });
        completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, validation.get()); assertEquals(home, engine.processHomeForTest());
        JSONObject current = handle("state");
        assertEquals(reject ? "saved@example.test" : "added@example.test", current.getJSONObject("account").getString("email"));
        for (JSONObject state : states) {
            if (!"added@example.test".equals(state.getJSONObject("account").optString("email"))) continue;
            assertFalse("rejected identity must not be published", reject);
            boolean active = false;
            for (int i = 0; i < state.getJSONArray("accounts").length(); i++) {
                JSONObject profile = state.getJSONArray("accounts").getJSONObject(i);
                if (profile.optBoolean("active") && "added@example.test".equals(profile.optString("email"))) active = true;
            }
            assertTrue("published identity must match committed active profile", active);
        }
    }
    @Test public void explicitStopSurvivesForegroundAndManualStartResumesAutoRefresh() throws Exception {
        savedLogin(); AtomicInteger reads = new AtomicInteger();
        create((method, args) -> { if ("account/read".equals(method)) reads.incrementAndGet(); return response(method, args); });
        engine.restoreAccount(); handle("state"); handle("runtime.stop");
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount();
        assertFalse(handle("state").getBoolean("ready")); assertEquals(1, reads.get());
        handle("runtime.start"); assertEquals(2, reads.get());
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount(); handle("state"); assertEquals(3, reads.get());
        engine.stop(); handle("state"); ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount();
        assertFalse(handle("state").getBoolean("ready")); assertEquals(3, reads.get());
    }
    @Test public void logoutCannotBeUndoneByQueuedOrLaterResumeChecks() throws Exception {
        savedLogin(); AtomicInteger reads = new AtomicInteger();
        create((method, args) -> {
            if ("account/logout".equals(method)) { Files.delete(new File(engine.processHomeForTest(), "auth.json").toPath()); return obj(); }
            if ("account/read".equals(method)) reads.incrementAndGet();
            return response(method, args);
        });
        engine.restoreAccount(); handle("state"); handle("auth.logout"); int count = reads.get();
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001)); engine.restoreAccount();
        JSONObject state = handle("state");
        assertEquals(count, reads.get()); assertEquals("signed_out", state.getString("authState"));
        assertEquals(0, state.getJSONObject("account").length()); assertEquals(0, state.getJSONArray("accounts").length());
    }
}
