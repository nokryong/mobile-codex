package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineLoginConversationTest {
    private Context context;
    private Engine engine;
    private File home, sessionsFile;
    private AccountProfiles profiles;
    private byte[] originalAuth;
    private String originalKey;
    private boolean failLogin, failStartup, failResume;
    private final List<String> threadCalls = new ArrayList<>();
    private final List<JSONObject> completions = new ArrayList<>();
    private final JSONObject oldAccount = obj("type", "chatgpt", "email", "old@example.test");
    private final JSONObject newAccount = obj("type", "chatgpt", "email", "new@example.test");

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        home = new File(context.getFilesDir(), ".codex");
        sessionsFile = new File(context.getFilesDir(), "sessions.json");
        remove(home); remove(new File(context.getFilesDir(), "account-profiles"));
        Files.deleteIfExists(sessionsFile.toPath());
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        home.mkdirs();
        dev.mobilecodex.app.core.Utf8Files.write(new File(home, "auth.json").toPath(), obj("tokens", obj("access_token", "old-test-secret")).toString());
        originalAuth = Files.readAllBytes(new File(home, "auth.json").toPath());
        profiles = new AccountProfiles(home, context.getFilesDir());
        originalKey = profiles.saveCurrent(oldAccount).getString("key");
    }
    @After public void after() {
        if (engine != null) engine.io.shutdownNow();
        remove(home); remove(new File(context.getFilesDir(), "account-profiles")); remove(sessionsFile);
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private void create(boolean selected) throws Exception {
        if (selected) dev.mobilecodex.app.core.Utf8Files.write(sessionsFile.toPath(), array(
            obj("id", "local-saved", "sessionVersion", 2, "codexThreadId", "remote-saved", "title", "Saved",
                "workspace", "", "workspaceKey", "", "imageHistoryVersion", 1, "phoneToolsVersion", 1,
                "messages", array(obj("id", "message-saved", "role", "user", "text", "Keep this conversation"))),
            obj("id", "pending-delete", "deletionPending", true)).toString());
        engine = new Engine(context);
        engine.setTestAccountValidation(true);
        engine.setTestTransport((method, args) -> {
            File currentHome = engine.processHomeForTest();
            File auth = new File(currentHome, "auth.json");
            boolean pending = !home.equals(currentHome);
            if (method.startsWith("thread/")) {
                assertFalse("authentication HOME must never access conversations", pending);
                threadCalls.add(method);
                if (method.equals("thread/resume")) {
                    assertEquals("remote-saved", args.getString("threadId"));
                    if (failResume) throw new IOException("rollout not found");
                }
                return obj();
            }
            if (method.equals("account/read")) {
                if (pending && failStartup) throw new IOException("startup unavailable");
                return obj("account", !auth.isFile() ? JSONObject.NULL : dev.mobilecodex.app.core.Utf8Files.read(auth.toPath()).contains("new-test-secret") ? newAccount : oldAccount);
            }
            if (method.equals("account/rateLimits/read")) return obj();
            if (method.equals("model/list")) return obj("data", new JSONArray());
            if (method.equals("account/login/start")) {
                assertTrue("login must start before any thread operation", threadCalls.isEmpty());
                if (failLogin) throw new IOException("device login unavailable");
                return obj("loginId", "login-test");
            }
            if (method.equals("account/login/cancel")) return obj();
            throw new AssertionError(method);
        });
        engine.attach(new Engine.Ui() {
            @Override public void event(String name, JSONObject data) { if (name.equals("login.completed")) completions.add(data); }
            @Override public void approval(Engine.Approval approval) { fail("login must not request tool approval"); }
        });
        if (selected) handle("chat.resume", obj("id", "local-saved"));
        else handle("state", obj());
    }
    private JSONObject handle(String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> {
            if (error == null) result.complete(value); else result.completeExceptionally(error);
        });
        return result.get(5, TimeUnit.SECONDS);
    }
    private void completed(boolean success) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        engine.io.execute(() -> {
            try {
                Method method = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class);
                method.setAccessible(true); method.invoke(engine, "account/login/completed", obj("success", success));
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(5, TimeUnit.SECONDS);
    }
    private void assertConversationPreserved() throws Exception {
        JSONObject state = handle("state", obj());
        assertEquals("local-saved", state.getString("threadId"));
        assertEquals("Keep this conversation", state.getJSONArray("messages").getJSONObject(0).getString("text"));
        JSONObject stored = new JSONArray(dev.mobilecodex.app.core.Utf8Files.read(sessionsFile.toPath())).getJSONObject(0);
        assertEquals("remote-saved", stored.getString("codexThreadId"));
    }
    private void assertOriginalAccount() throws Exception {
        assertEquals(home, engine.processHomeForTest());
        assertEquals(originalKey, profiles.activeKey());
        assertArrayEquals(originalAuth, Files.readAllBytes(new File(home, "auth.json").toPath()));
        assertFalse(profiles.hasPendingAddLogin());
        assertConversationPreserved();
    }
    @Test public void addingFromSelectedChatDefersAllThreadWorkUntilCancellationReturnsHome() throws Exception {
        create(true);
        assertEquals("login-test", handle("auth.add", obj()).getString("loginId"));
        File pending = engine.processHomeForTest(); assertNotEquals(home, pending);
        assertTrue(threadCalls.isEmpty()); assertConversationPreserved();
        // Even a manual start while the login is open must not resume in its HOME.
        handle("runtime.start", obj()); assertTrue(threadCalls.isEmpty());
        handle("auth.cancel", obj());
        assertOriginalAccount(); assertFalse(pending.exists());
        assertEquals(List.of("thread/delete", "thread/resume"), threadCalls);
    }
    @Test public void addingFromNewChatStillStartsDeviceLogin() throws Exception {
        create(false); assertEquals("login-test", handle("auth.add", obj()).getString("loginId"));
        assertTrue(threadCalls.isEmpty());
        handle("auth.cancel", obj()); assertEquals(home, engine.processHomeForTest());
    }
    @Test public void revokedAccountReloginAlsoSkipsConversationRestore() throws Exception {
        profiles.markNeedsLogin(originalKey); create(true);
        assertEquals("login-test", handle("auth.login", obj()).getString("loginId"));
        assertNotEquals(home, engine.processHomeForTest()); assertTrue(threadCalls.isEmpty());
        handle("auth.cancel", obj()); assertOriginalAccount();
    }
    @Test public void ordinaryLoginWithSelectedChatDoesNotResumeBeforeDeviceCode() throws Exception {
        create(true); assertEquals("login-test", handle("auth.login", obj()).getString("loginId"));
        assertEquals(home, engine.processHomeForTest()); assertTrue(threadCalls.isEmpty());
        handle("auth.cancel", obj()); assertConversationPreserved();
    }
    @Test public void startRequestFailureRollsBackTheIsolatedHome() throws Exception {
        create(true); failLogin = true;
        try { handle("auth.add", obj()); fail("failure must reach the caller"); }
        catch (ExecutionException expected) { assertEquals("device login unavailable", expected.getCause().getMessage()); }
        assertOriginalAccount(); assertEquals(List.of("thread/delete", "thread/resume"), threadCalls);
    }
    @Test public void isolatedEngineStartupFailureAlsoRollsBack() throws Exception {
        create(true); failStartup = true;
        try { handle("auth.add", obj()); fail("startup failure must reach the caller"); }
        catch (ExecutionException expected) { assertEquals("startup unavailable", expected.getCause().getMessage()); }
        assertOriginalAccount();
    }
    @Test public void unsuccessfulCompletionRestoresOldHomeAndConversation() throws Exception {
        create(true); handle("auth.add", obj()); completed(false);
        assertOriginalAccount(); assertFalse(completions.get(0).getBoolean("success"));
    }
    @Test public void successfulLoginPromotesAuthBeforeResumingExactlyOnce() throws Exception {
        assertSuccessfulLogin(false);
        assertEquals(List.of("thread/delete", "thread/resume"), threadCalls);
    }
    @Test public void missingRolloutAfterPromotionDoesNotInvalidateSuccessfulAuthentication() throws Exception {
        assertSuccessfulLogin(true);
    }
    private void assertSuccessfulLogin(boolean missingRollout) throws Exception {
        create(true); handle("auth.add", obj());
        File pending = engine.processHomeForTest();
        dev.mobilecodex.app.core.Utf8Files.write(new File(pending, "auth.json").toPath(), obj("tokens", obj("access_token", "new-test-secret")).toString());
        failResume = missingRollout; completed(true);
        assertEquals(home, engine.processHomeForTest()); assertFalse(pending.exists());
        assertTrue(dev.mobilecodex.app.core.Utf8Files.read(new File(home, "auth.json").toPath()).contains("new-test-secret"));
        assertNotEquals(originalKey, profiles.activeKey()); assertFalse(profiles.hasPendingAddLogin());
        assertEquals("new@example.test", handle("state", obj()).getJSONObject("account").getString("email"));
        assertTrue(completions.get(0).getBoolean("success")); assertConversationPreserved();
    }
    @Test public void normalEngineStartStillRestoresSavedChatAndRetriesTombstones() throws Exception {
        create(true); handle("runtime.start", obj());
        assertEquals(List.of("thread/delete", "thread/resume"), threadCalls);
        assertConversationPreserved();
    }
}
