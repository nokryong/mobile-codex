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
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EngineOfflineSessionTest {
    private Context context;
    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(), "sessions.json").delete();
        new File(context.getFilesDir(), "sessions.json.tmp").delete();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
        deleteTree(new File(context.getFilesDir(), "account-profiles"));
    }
    @After public void after() {
        new File(context.getFilesDir(), "sessions.json").delete();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
        deleteTree(new File(context.getFilesDir(), "account-profiles"));
    }
    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    @Test public void approvalReviewModesStaySeparateFromFileAccess() throws Exception {
        Engine engine = new Engine(context);
        Method method = Engine.class.getDeclaredMethod("threadStartParams", String.class); method.setAccessible(true);
        JSONObject automatic = (JSONObject) method.invoke(engine, "");
        assertEquals("workspace-write", automatic.getString("sandbox"));
        assertEquals("on-request", automatic.getString("approvalPolicy"));
        assertEquals("auto_review", automatic.getString("approvalsReviewer"));
        handle(engine, "approvals.set", obj("mode", "ask"));
        JSONObject ask = (JSONObject) method.invoke(engine, "");
        assertEquals("on-request", ask.getString("approvalPolicy")); assertEquals("user", ask.getString("approvalsReviewer"));
        handle(engine, "permissions.set", obj("mode", "read-only"));
        handle(engine, "approvals.set", obj("mode", "allow-all"));
        JSONObject allow = (JSONObject) method.invoke(engine, "");
        assertEquals("read-only", allow.getString("sandbox"));
        assertEquals("never", allow.getString("approvalPolicy")); assertEquals("user", allow.getString("approvalsReviewer"));
        engine.io.shutdownNow();
    }
    @Test public void accountReadDoesNotForceRotationAndRateLimitRevocationIsNotSilenced() throws Exception {
        File auth = new File(new File(context.getFilesDir(), ".codex"), "auth.json"); auth.getParentFile().mkdirs();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "opaque-test-token")).toString().getBytes(StandardCharsets.UTF_8));
        Engine engine = new Engine(context); java.util.ArrayList<JSONObject> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("account/read")) return obj("account", obj("type", "chatgpt", "email", "test@example.test", "planType", "plus"));
            if (method.equals("account/rateLimits/read")) throw new java.io.IOException("401 token_revoked");
            throw new AssertionError(method);
        });
        Method readAccount = Engine.class.getDeclaredMethod("readAccount"); readAccount.setAccessible(true); readAccount.invoke(engine);
        assertFalse(calls.get(0).getJSONObject("params").getBoolean("refreshToken"));
        Method readRateLimits = Engine.class.getDeclaredMethod("readRateLimits"); readRateLimits.setAccessible(true);
        try { readRateLimits.invoke(engine); fail("revoked credentials must not be treated as a usable account"); }
        catch (java.lang.reflect.InvocationTargetException expected) { assertTrue(expected.getCause().getMessage().contains("다시 로그인")); }
        engine.io.shutdownNow(); deleteTree(new File(context.getFilesDir(), "account-profiles")); Files.deleteIfExists(auth.toPath());
    }
    @Test public void revokedAccountSwitchRestoresThePreviousActiveProfile() throws Exception {
        File auth = new File(new File(context.getFilesDir(), ".codex"), "auth.json"); auth.getParentFile().mkdirs();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "opaque-one")).toString().getBytes(StandardCharsets.UTF_8));
        Engine engine = new Engine(context);
        engine.setTestTransport((method, params) -> obj("account", obj("type", "chatgpt", "email", "one@example.test", "planType", "plus")));
        Method readAccount = Engine.class.getDeclaredMethod("readAccount"); readAccount.setAccessible(true); readAccount.invoke(engine);
        AccountProfiles profiles = (AccountProfiles) field(engine, "accountProfiles"); String previous = profiles.activeKey();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "opaque-two")).toString().getBytes(StandardCharsets.UTF_8));
        engine.setTestTransport((method, params) -> obj("account", obj("type", "chatgpt", "email", "two@example.test", "planType", "pro")));
        readAccount.invoke(engine); String target = profiles.activeKey(); assertNotEquals(previous, target);
        profiles.switchTo(previous);
        engine.setTestTransport((method, params) -> {
            if (method.equals("account/read")) {
                // The active marker remains on the previous profile until
                // validation commits. Identify the staged account from the
                // live credential that stageSwitch copied instead.
                String raw = new String(Files.readAllBytes(auth.toPath()), StandardCharsets.UTF_8);
                return obj("account", raw.contains("opaque-two")
                    ? obj("type", "chatgpt", "email", "two@example.test", "planType", "pro")
                    : obj("type", "chatgpt", "email", "one@example.test", "planType", "plus"));
            }
            if (method.equals("account/rateLimits/read")) {
                // The staged target is intentionally not active yet. Validate
                // the credential that was copied into live auth.json rather
                // than observing the old active marker.
                String raw = new String(Files.readAllBytes(auth.toPath()), StandardCharsets.UTF_8);
                if (raw.contains("opaque-two")) throw new java.io.IOException("401 token_revoked");
                return obj("rateLimits", obj("primary", obj("usedPercent", 1)));
            }
            throw new AssertionError(method);
        });
        engine.setTestAccountValidation(true); setField(engine, "account", obj("type", "chatgpt", "email", "one@example.test"));
        Method switchAccount = Engine.class.getDeclaredMethod("switchAccount", String.class); switchAccount.setAccessible(true);
        try { switchAccount.invoke(engine, target); fail("a failed target startup must not report a successful switch"); }
        catch (java.lang.reflect.InvocationTargetException expected) { assertTrue(expected.getCause().getMessage().contains("다시 로그인")); }
        assertEquals(previous, profiles.activeKey());
        engine.io.shutdownNow(); deleteTree(new File(context.getFilesDir(), "account-profiles")); Files.deleteIfExists(auth.toPath());
    }

    @Test public void revokedActiveAccountCanReachAddLoginWithoutLogoutAndCancelRestoresAuth() throws Exception {
        File auth = new File(new File(context.getFilesDir(), ".codex"), "auth.json"); auth.getParentFile().mkdirs();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "revoked-token")).toString().getBytes(StandardCharsets.UTF_8));
        byte[] previousAuth = Files.readAllBytes(auth.toPath());
        Engine engine = new Engine(context);
        AccountProfiles profiles = (AccountProfiles) field(engine, "accountProfiles");
        String previous = profiles.saveCurrent(obj("email", "revoked@example.test", "planType", "plus")).getString("key");
        profiles.markNeedsLogin(previous);
        java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(method);
            if (method.equals("account/login/start")) {
                assertTrue("active auth must remain isolated from device login", auth.isFile());
                File pending = profiles.pendingLoginHome();
                assertNotNull("device login must use a pending CODEX_HOME", pending);
                assertEquals(pending.getAbsolutePath(), engine.processHomeForTest().getAbsolutePath());
                assertFalse("pending home must start without active credentials", new File(pending, "auth.json").isFile());
                return obj("loginId", "login-test", "userCode", "ABC", "verificationUrl", "https://auth.openai.com/codex/device");
            }
            throw new AssertionError(method);
        });
        Method beginLogin = Engine.class.getDeclaredMethod("beginLogin", boolean.class); beginLogin.setAccessible(true);
        beginLogin.invoke(engine, true);
        assertEquals(java.util.List.of("account/login/start"), calls);
        assertEquals(previous, profiles.activeKey());
        Method restore = Engine.class.getDeclaredMethod("restoreAccountAfterCancelledLogin"); restore.setAccessible(true);
        restore.invoke(engine);
        assertTrue(auth.isFile());
        assertEquals(previous, profiles.activeKey());
        assertArrayEquals(previousAuth, Files.readAllBytes(auth.toPath()));
        engine.io.shutdownNow(); deleteTree(new File(context.getFilesDir(), "account-profiles")); Files.deleteIfExists(auth.toPath());
    }

    @Test public void threeSequentialAddLoginsStayUsableAfterRotatingAndReturningToOlderProfiles() throws Exception {
        File auth = new File(new File(context.getFilesDir(), ".codex"), "auth.json"); auth.getParentFile().mkdirs();
        writeNamedAuth(auth, "a@example.test", "token-a-0");
        Engine engine = new Engine(context); AccountProfiles profiles = (AccountProfiles) field(engine, "accountProfiles");
        Method readAccount = Engine.class.getDeclaredMethod("readAccount"); readAccount.setAccessible(true);
        Method beginLogin = Engine.class.getDeclaredMethod("beginLogin", boolean.class); beginLogin.setAccessible(true);
        Method notification = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class); notification.setAccessible(true);
        final String[] login = {"a"};
        engine.setTestTransport((method, params) -> {
            if (method.equals("account/read")) {
                File currentHome = engine.processHomeForTest();
                String raw = new String(Files.readAllBytes(new File(currentHome, "auth.json").toPath()), StandardCharsets.UTF_8);
                String name = raw.contains("token-a") ? "a" : raw.contains("token-b") ? "b" : "c";
                return obj("account", accountFor(name));
            }
            if (method.equals("account/rateLimits/read")) {
                File currentHome = engine.processHomeForTest();
                File currentAuth = new File(currentHome, "auth.json");
                if (currentAuth.isFile()) {
                    String raw = new String(Files.readAllBytes(currentAuth.toPath()), StandardCharsets.UTF_8);
                    String name = raw.contains("token-a") ? "a" : raw.contains("token-b") ? "b" : "c";
                    writeNamedAuth(currentAuth, name + "@example.test", "token-" + name + "-rotated");
                }
                return obj("rateLimits", obj("primary", obj("usedPercent", 1)));
            }
            if (method.equals("account/login/start")) return obj("loginId", "login-" + login[0], "userCode", "ABC", "verificationUrl", "https://auth.openai.com/codex/device");
            if (method.equals("model/list")) return obj("data", new JSONArray());
            throw new AssertionError(method);
        });
        readAccount.invoke(engine);
        String a = profiles.activeKey();
        assertFalse("initial login must create a profile", a.isBlank());
        login[0] = "b"; beginLogin.invoke(engine, true);
        File pendingB = engine.processHomeForTest(); assertNotEquals(auth.getParentFile().getAbsolutePath(), pendingB.getAbsolutePath());
        writeNamedAuth(new File(pendingB, "auth.json"), "b@example.test", "token-b-0");
        notification.invoke(engine, "account/login/completed", obj("success", true));
        String b = profiles.activeKey(); assertNotEquals(a, b);
        assertFalse("second login must create a profile", b.isBlank());
        login[0] = "c"; beginLogin.invoke(engine, true);
        File pendingC = engine.processHomeForTest();
        writeNamedAuth(new File(pendingC, "auth.json"), "c@example.test", "token-c-0");
        notification.invoke(engine, "account/login/completed", obj("success", true));
        String c = profiles.activeKey(); assertNotEquals(b, c);
        assertFalse("third login must create a profile", c.isBlank());

        engine.setTestAccountValidation(true);
        for (String key : new String[]{a, b, c, a, b}) {
            Method switchAccount = Engine.class.getDeclaredMethod("switchAccount", String.class); switchAccount.setAccessible(true);
            switchAccount.invoke(engine, key);
            assertEquals(key, profiles.activeKey());
            assertTrue(auth.isFile());
        }
        engine.io.shutdownNow(); deleteTree(new File(context.getFilesDir(), "account-profiles")); Files.deleteIfExists(auth.toPath());
    }
    @Test public void sessionsAreSharedAcrossProfilesAndAccountSwitchKeepsTheOpenConversation() throws Exception {
        File auth = new File(new File(context.getFilesDir(), ".codex"), "auth.json"); auth.getParentFile().mkdirs();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "opaque-one")).toString().getBytes(StandardCharsets.UTF_8));
        Engine engine = new Engine(context);
        Method readAccount = Engine.class.getDeclaredMethod("readAccount"); readAccount.setAccessible(true);
        engine.setTestTransport((method, params) -> obj("account", obj("type", "chatgpt", "email", "one@example.test", "planType", "plus")));
        readAccount.invoke(engine);
        AccountProfiles profiles = (AccountProfiles) field(engine, "accountProfiles"); String first = profiles.activeKey();
        Files.write(auth.toPath(), obj("tokens", obj("access_token", "opaque-two")).toString().getBytes(StandardCharsets.UTF_8));
        engine.setTestTransport((method, params) -> obj("account", obj("type", "chatgpt", "email", "two@example.test", "planType", "pro")));
        readAccount.invoke(engine); String second = profiles.activeKey(); assertNotEquals(first, second);
        profiles.switchTo(first);

        setField(engine, "sessions", array(obj("id", "shared-thread", "title", "Shared", "workspace", "", "workspaceKey", "",
            "accountProfileKey", first, "messages", array(obj("id", "m1", "role", "user", "text", "keep me")),
            "imageHistoryVersion", 1, "phoneToolsVersion", 1)));
        JSONObject before = handle(engine, "chat.resume", obj("id", "shared-thread"));
        assertEquals("shared-thread", before.getString("threadId"));
        assertEquals(1, before.getJSONArray("sessions").length());

        java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(method);
            if (method.equals("account/read")) return obj("account", obj("type", "chatgpt", "email", "two@example.test", "planType", "pro"));
            if (method.equals("account/rateLimits/read")) return obj("rateLimits", new JSONObject());
            if (method.equals("thread/resume")) return new JSONObject();
            throw new AssertionError(method);
        });
        engine.setTestAccountValidation(true); setField(engine, "account", obj("type", "chatgpt", "email", "one@example.test"));
        Method switchAccount = Engine.class.getDeclaredMethod("switchAccount", String.class); switchAccount.setAccessible(true);
        switchAccount.invoke(engine, second);

        JSONObject after = handle(engine, "state", new JSONObject());
        assertEquals(second, profiles.activeKey());
        assertEquals("shared-thread", after.getString("threadId"));
        assertEquals("keep me", after.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertEquals(1, after.getJSONArray("sessions").length());
        assertTrue(calls.contains("thread/resume"));
        engine.io.shutdownNow(); deleteTree(new File(context.getFilesDir(), "account-profiles")); Files.deleteIfExists(auth.toPath());
    }
    @Test public void legacySessionOwnershipIsRemovedAndNeverHidesConversations() throws Exception {
        JSONArray initial = array(
            obj("id", "one", "title", "One", "workspace", "", "workspaceKey", "", "accountProfileKey", "account-old", "messages", new JSONArray()),
            obj("id", "two", "title", "Two", "workspace", "", "workspaceKey", "", "accountProfileKey", "account-other", "messages", new JSONArray()));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        File profileRoot = new File(context.getFilesDir(), "account-profiles"); profileRoot.mkdirs();
        Files.write(new File(profileRoot, "active").toPath(), "account-current\n".getBytes(StandardCharsets.UTF_8));

        Engine engine = new Engine(context);
        JSONObject state = handle(engine, "state", new JSONObject());
        assertEquals(2, state.getJSONArray("sessions").length());
        assertEquals("one", handle(engine, "chat.resume", obj("id", "one")).getString("threadId"));
        JSONObject renamed = handle(engine, "chat.rename", obj("id", "one", "title", "Renamed"));
        assertEquals("Renamed", session(renamed.getJSONArray("sessions"), "one").getString("title"));
        JSONArray stored = new JSONArray(dev.mobilecodex.app.core.Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        assertFalse(stored.getJSONObject(0).has("accountProfileKey"));
        assertFalse(stored.getJSONObject(1).has("accountProfileKey"));
        engine.io.shutdownNow();
    }
    private static Object field(Engine engine, String name) throws Exception { java.lang.reflect.Field value = Engine.class.getDeclaredField(name); value.setAccessible(true); return value.get(engine); }
    private static void setField(Engine engine, String name, Object value) throws Exception { java.lang.reflect.Field field = Engine.class.getDeclaredField(name); field.setAccessible(true); field.set(engine, value); }
    private static JSONObject accountFor(String name) { return obj("type", "chatgpt", "email", name + "@example.test", "planType", "plus"); }
    private static void writeNamedAuth(File file, String email, String token) throws Exception {
        File parent = file.getParentFile(); if (parent != null) parent.mkdirs();
        JSONObject claims = obj("email", email, "sub", "subject-" + email,
            "https://api.openai.com/auth", obj("chatgpt_account_id", "account-" + email,
                "chatgpt_plan_type", "plus"));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject auth = obj("tokens", obj("id_token", "x." + payload + ".id-signature",
            "access_token", "x." + payload + "." + token, "account_id", "account-" + email));
        Files.write(file.toPath(), auth.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject handle(Engine engine, String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> { if (error != null) done.completeExceptionally(error); else done.complete(value); });
        return done.get(5, TimeUnit.SECONDS);
    }
    private void loggedIn(Engine engine) throws Exception {
        java.lang.reflect.Field account = Engine.class.getDeclaredField("account"); account.setAccessible(true); account.set(engine, obj("type", "chatgpt"));
    }
    private byte[] png() {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff00aaee); ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); return output.toByteArray();
    }
    @Test public void storedSessionsSwitchBetweenGeneralAndUnavailableProjectWithoutStartingCodex() throws Exception {
        context.getSharedPreferences("projects", 0).edit().putString("registry", obj("selectedKey", "project-a", "projects", array(
            obj("key", "project-a", "name", "A", "uri", "content://provider/tree/a"))).toString()).commit();
        JSONArray sessions = array(
            obj("id", "project-thread", "title", "Project", "workspace", "A", "workspaceKey", "project-a", "messages", array(obj("id", "p1", "role", "user", "text", "project draft"))),
            obj("id", "general-thread", "title", "General", "workspace", "", "workspaceKey", "", "messages", array(obj("id", "g1", "role", "user", "text", "general draft"))));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), sessions.toString());
        Engine engine = new Engine(context);
        JSONObject general = handle(engine, "chat.resume", obj("id", "general-thread"));
        assertFalse(general.getJSONObject("workspace").getBoolean("selected"));
        assertEquals("general draft", general.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertFalse(general.getBoolean("ready"));
        JSONObject project = handle(engine, "chat.resume", obj("id", "project-thread"));
        assertEquals("project-a", project.getJSONObject("workspace").getString("key"));
        assertFalse(project.getJSONObject("workspace").getBoolean("available"));
        assertEquals("project draft", project.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertFalse(project.getBoolean("ready"));
        engine.io.shutdownNow();
    }
    @Test public void removingAnInactiveProjectKeepsTheCurrentConversationAndWorkspace() throws Exception {
        context.getSharedPreferences("projects", 0).edit().putString("registry", obj("selectedKey", "project-a", "projects", array(
            obj("key", "project-a", "name", "A", "uri", "content://provider/tree/a"),
            obj("key", "project-b", "name", "B", "uri", "content://provider/tree/b"))).toString()).commit();
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(
            obj("id", "a-thread", "title", "A", "workspace", "A", "workspaceKey", "project-a",
                "messages", array(obj("id", "m1", "role", "user", "text", "keep this")))).toString());
        Engine engine = new Engine(context);
        handle(engine, "chat.resume", obj("id", "a-thread"));
        handle(engine, "projects.remove", obj("key", "project-b"));
        JSONObject state = handle(engine, "state", new JSONObject());
        assertEquals("a-thread", state.getString("threadId"));
        assertEquals("project-a", state.getJSONObject("workspace").getString("key"));
        assertNotNull(session(state.getJSONArray("sessions"), "a-thread"));
        assertEquals(1, state.getJSONArray("projects").length());
        assertEquals("project-a", state.getJSONArray("projects").getJSONObject(0).getString("key"));
        engine.io.shutdownNow();
    }
    @Test public void removedProjectHistoryResumesDetachedAndCannotSendUntilReconnected() throws Exception {
        context.getSharedPreferences("projects", 0).edit().putString("registry", obj("selectedKey", "", "projects", new JSONArray(),
            "removed", array(obj("key", "project-a", "name", "A"))).toString()).commit();
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(
            obj("id", "old-thread", "title", "Old", "workspace", "A", "workspaceKey", "project-a", "messages", new JSONArray())).toString());
        Engine engine = new Engine(context);
        JSONObject resumed = handle(engine, "chat.resume", obj("id", "old-thread"));
        JSONObject workspace = resumed.getJSONObject("workspace");
        assertEquals("project-a", workspace.getString("key"));
        assertTrue(workspace.getBoolean("detached"));
        assertFalse(workspace.getBoolean("available"));
        try { handle(engine, "chat.send", obj("text", "must not use another folder")); fail("detached history must not send"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("연결 해제")); }
        engine.io.shutdownNow();
    }
    @Test public void inputUsesVerifiedUserInputShapesForSkillMentionAndAttachment() throws Exception {
        Engine engine = new Engine(context);
        JSONObject attachment = engine.attachments.store(new java.io.ByteArrayInputStream("code".getBytes(StandardCharsets.UTF_8)), "note.txt", "text/plain");
        Method method = Engine.class.getDeclaredMethod("input", String.class, JSONArray.class, JSONArray.class, JSONArray.class);
        method.setAccessible(true);
        JSONArray input = (JSONArray) method.invoke(engine, "inspect this", array(attachment.getString("id")),
            array(obj("name", "review", "path", "/skills/review/SKILL.md")), array(obj("name", "Calendar", "path", "app://calendar")));
        assertEquals("text", input.getJSONObject(0).getString("type"));
        assertEquals(0, input.getJSONObject(0).getJSONArray("text_elements").length());
        assertEquals("skill", input.getJSONObject(1).getString("type"));
        assertEquals("mention", input.getJSONObject(2).getString("type"));
        assertEquals("text", input.getJSONObject(3).getString("type"));
        assertTrue(input.getJSONObject(3).getString("text").contains("\"path\":"));
        engine.io.shutdownNow();
    }
    @Test public void attachmentOnlyTurnUsesProtocolInputsAndFailedTurnDoesNotCreateLocalThread() throws Exception {
        Engine engine = new Engine(context); loggedIn(engine);
        JSONObject image = engine.attachments.store(new java.io.ByteArrayInputStream(png()), "picture.png", "image/png");
        JSONObject text = engine.attachments.store(new java.io.ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)), "notes.txt", "text/plain");
        java.util.ArrayList<JSONObject> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("thread/start")) return obj("thread", obj("id", "accepted-thread"));
            if (method.equals("turn/start")) return obj("turn", obj("id", "turn-1"));
            throw new AssertionError(method);
        });
        handle(engine, "chat.send", obj("text", "", "attachments", array(image.getString("id"), text.getString("id")),
            "skills", array(obj("name", "review", "path", "/skills/review/SKILL.md")), "mentions", array(obj("name", "Calendar", "path", "app://calendar"))));
        JSONObject turn = calls.stream().filter(c -> c.optString("method").equals("turn/start")).findFirst().get().getJSONObject("params");
        assertEquals(context.getFilesDir().toPath().resolve("workspace").toString(), turn.getString("cwd"));
        JSONArray input = turn.getJSONArray("input");
        assertTrue(types(input).contains("localImage")); assertTrue(types(input).contains("skill")); assertTrue(types(input).contains("mention"));
        assertTrue(input.toString().contains(text.getString("filename")));
        JSONObject accepted = handle(engine, "state", new JSONObject());
        assertEquals("accepted-thread", accepted.getString("threadId"));
        assertEquals(2, accepted.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments").length());
        assertEquals("review", accepted.getJSONArray("messages").getJSONObject(0).getJSONArray("skills").getJSONObject(0).getString("name"));
        assertEquals("Calendar", accepted.getJSONArray("messages").getJSONObject(0).getJSONArray("mentions").getJSONObject(0).getString("name"));
        engine.io.shutdownNow();

        Engine reopened = new Engine(context); loggedIn(reopened);
        java.util.ArrayList<JSONObject> resumedCalls = new java.util.ArrayList<>();
        reopened.setTestTransport((method, params) -> {
            resumedCalls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("thread/resume")) return new JSONObject();
            if (method.equals("turn/start")) return obj("turn", obj("id", "turn-2"));
            throw new AssertionError(method);
        });
        handle(reopened, "chat.resume", obj("id", "accepted-thread"));
        handle(reopened, "chat.send", obj("text", "continued"));
        JSONObject resume = resumedCalls.stream().filter(c -> c.optString("method").equals("thread/resume")).findFirst().get().getJSONObject("params");
        assertEquals(context.getFilesDir().toPath().resolve("workspace").toString(), resume.getString("cwd"));
        reopened.io.shutdownNow();

        Engine failed = new Engine(context); loggedIn(failed);
        failed.setTestTransport((method, params) -> {
            if (method.equals("thread/start")) return obj("thread", obj("id", "orphaned-server-thread"));
            if (method.equals("turn/start")) throw new java.io.IOException("network failed");
            throw new AssertionError(method);
        });
        try { handle(failed, "chat.new", obj("workspaceKey", "")); handle(failed, "chat.send", obj("text", "will fail")); fail("expected failure"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("network failed")); }
        JSONObject afterFailure = handle(failed, "state", new JSONObject());
        assertEquals("", afterFailure.getString("threadId"));
        assertEquals(1, afterFailure.getJSONArray("sessions").length());
        failed.io.shutdownNow();
    }
    @Test public void exactRequestedModelIsVisibleInThreadInstructionsAndUpdatesOnChange() throws Exception {
        Engine engine = new Engine(context); loggedIn(engine);
        java.lang.reflect.Field models = Engine.class.getDeclaredField("models"); models.setAccessible(true);
        models.set(engine, array(obj("model", "gpt-5.6-sol", "isDefault", true), obj("model", "gpt-6-astra")));
        java.util.ArrayList<JSONObject> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("thread/start")) return obj("thread", obj("id", "model-thread"));
            if (method.equals("thread/resume")) return new JSONObject();
            if (method.equals("turn/start")) return obj("turn", obj("id", "turn-" + calls.size()));
            throw new AssertionError(method);
        });
        handle(engine, "chat.send", obj("text", "which model", "model", "gpt-5.6-sol"));
        JSONObject started = calls.stream().filter(c -> c.optString("method").equals("thread/start")).findFirst().orElseThrow().getJSONObject("params");
        assertEquals("gpt-5.6-sol", started.getString("model"));
        assertTrue(started.getString("developerInstructions").contains("exact model requested for this thread is gpt-5.6-sol"));
        java.lang.reflect.Field busy = Engine.class.getDeclaredField("busy"); busy.setAccessible(true); busy.setBoolean(engine, false);
        handle(engine, "chat.send", obj("text", "switch model", "model", "gpt-6-astra"));
        JSONObject resumed = calls.stream().filter(c -> c.optString("method").equals("thread/resume")).reduce((a,b) -> b).orElseThrow().getJSONObject("params");
        assertTrue(resumed.getString("developerInstructions").contains("exact model requested for this thread is gpt-6-astra"));
        JSONObject turn = calls.stream().filter(c -> c.optString("method").equals("turn/start")).reduce((a,b) -> b).orElseThrow().getJSONObject("params");
        assertEquals("gpt-6-astra", turn.getString("model"));
        engine.io.shutdownNow();
    }
    private java.util.Set<String> types(JSONArray input) throws Exception {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (int i = 0; i < input.length(); i++) out.add(input.getJSONObject(i).getString("type"));
        return out;
    }
    @Test public void savedGeneralSelectionDoesNotReimportLegacyFolderAndUnknownLegacySessionGetsRebindablePlaceholder() throws Exception {
        context.getSharedPreferences("projects", 0).edit().putString("registry", obj("selectedKey", "", "projects", new JSONArray()).toString()).commit();
        context.getSharedPreferences("workspace", 0).edit().putString("uri", "content://provider/tree/old").commit();
        DocumentStore store = new DocumentStore(context);
        assertFalse(store.workspace().getBoolean("selected")); assertEquals(0, store.projects().length());
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(
            obj("id", "lost", "title", "Lost", "workspace", "Old project", "workspaceKey", "missing-project", "messages", new JSONArray())).toString());
        Engine engine = new Engine(context);
        JSONObject state = handle(engine, "state", new JSONObject());
        assertEquals("missing-project", state.getJSONArray("projects").getJSONObject(0).getString("key"));
        JSONObject resumed = handle(engine, "chat.resume", obj("id", "lost"));
        assertTrue(resumed.getJSONObject("workspace").getBoolean("selected"));
        assertFalse(resumed.getJSONObject("workspace").getBoolean("available"));
        engine.io.shutdownNow();
    }
    @Test public void oldImageHistoryStaysOfflineUntilResumeThenRestoresOnce() throws Exception {
        Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(obj("id", "old-images", "title", "Old", "workspace", "", "workspaceKey", "",
            "imageHistoryVersion", 0, "messages", new JSONArray())).toString().getBytes(StandardCharsets.UTF_8));
        Engine engine = new Engine(context);
        handle(engine, "chat.resume", obj("id", "old-images"));
        JSONObject offline = handle(engine, "state", new JSONObject());
        assertEquals(0, offline.getJSONArray("messages").length());
        java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        String data = android.util.Base64.encodeToString(png(), android.util.Base64.NO_WRAP);
        engine.setTestTransport((method, params) -> {
            calls.add(method);
            if (method.equals("thread/resume")) return new JSONObject();
            if (method.equals("thread/read")) return obj("thread", obj("turns", array(obj("id", "old-turn", "items", array(
                obj("id", "old-image", "type", "imageGeneration", "status", "completed", "result", data))))));
            throw new AssertionError(method);
        });
        JSONObject online = handle(engine, "runtime.start", new JSONObject());
        assertTrue(calls.contains("thread/resume")); assertTrue(calls.contains("thread/read"));
        assertEquals(1, online.getJSONArray("messages").length());
        assertEquals(1, online.getJSONArray("messages").getJSONObject(0).getJSONArray("images").length());
        int reads = java.util.Collections.frequency(calls, "thread/read");
        handle(engine, "runtime.start", new JSONObject());
        assertEquals(reads, java.util.Collections.frequency(calls, "thread/read"));
        engine.io.shutdownNow();
    }
    @Test public void sessionsCanBeRenamedAndDeleted() throws Exception {
        JSONArray initial = array(
            obj("id", "s1", "title", "대화 1", "workspace", "", "workspaceKey", "", "messages", new JSONArray()),
            obj("id", "s2", "title", "대화 2", "workspace", "", "workspaceKey", "", "messages", new JSONArray()));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        Engine engine = new Engine(context);
        JSONObject renamed = handle(engine, "chat.rename", obj("id", "s1", "title", "수정된 대화"));
        assertEquals("수정된 대화", session(renamed.getJSONArray("sessions"), "s1").getString("title"));
        handle(engine, "chat.resume", obj("id", "s1"));
        assertEquals("s1", handle(engine, "state", new JSONObject()).getString("threadId"));
        JSONObject deleted = handle(engine, "chat.delete", obj("id", "s1"));
        assertTrue(deleted.getBoolean("deletionPending"));
        assertEquals(1, deleted.getJSONArray("sessions").length());
        assertEquals("s2", deleted.getJSONArray("sessions").getJSONObject(0).getString("id"));
        assertEquals("", deleted.getString("threadId"));
        engine.io.shutdownNow();
    }
    @Test public void renameAndDeleteDoNotReportSuccessWhenTheirDurableWriteFails() throws Exception {
        JSONArray initial = array(obj("id", "s1", "title", "Original", "workspace", "", "workspaceKey", "", "messages", array(obj("id", "m1", "role", "user", "text", "draft"))));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        File parentFile = new File(context.getFilesDir(), "not-a-directory");
        dev.mobilecodex.app.core.Utf8Files.write(parentFile.toPath(), "blocked");

        Engine renamed = new Engine(context); handle(renamed, "chat.resume", obj("id", "s1")); renamed.setStateFileForTest(new File(parentFile, "sessions.json"));
        try { handle(renamed, "chat.rename", obj("id", "s1", "title", "Changed")); fail("expected durable write failure"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("저장")); }
        assertEquals("Original", session(handle(renamed, "state", new JSONObject()).getJSONArray("sessions"), "s1").getString("title"));
        assertEquals("s1", handle(renamed, "state", new JSONObject()).getString("threadId"));
        assertEquals("draft", handle(renamed, "state", new JSONObject()).getJSONArray("messages").getJSONObject(0).getString("text"));
        renamed.io.shutdownNow();

        Engine deleted = new Engine(context); handle(deleted, "chat.resume", obj("id", "s1")); deleted.setStateFileForTest(new File(parentFile, "sessions.json"));
        try { handle(deleted, "chat.delete", obj("id", "s1")); fail("expected durable write failure"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("저장")); }
        JSONObject state = handle(deleted, "state", new JSONObject());
        assertNotNull(session(state.getJSONArray("sessions"), "s1"));
        assertEquals(0, state.getInt("pendingDeletionCount"));
        assertEquals("s1", state.getString("threadId"));
        assertEquals("draft", state.getJSONArray("messages").getJSONObject(0).getString("text"));
        deleted.io.shutdownNow(); parentFile.delete();
    }
    @Test public void offlineDeletionIsHiddenAndRetriedBeforeNormalSessionResume() throws Exception {
        JSONArray initial = array(obj("id", "s1", "title", "Delete me", "workspace", "", "workspaceKey", "", "messages", new JSONArray()));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        Engine offline = new Engine(context);
        JSONObject queued = handle(offline, "chat.delete", obj("id", "s1"));
        assertTrue(queued.getBoolean("deletionPending"));
        assertEquals(0, queued.getJSONArray("sessions").length());
        assertEquals(1, queued.getInt("pendingDeletionCount"));
        offline.io.shutdownNow();

        Engine failedRetry = new Engine(context);
        try { handle(failedRetry, "chat.resume", obj("id", "s1")); fail("pending deletion must not be resumable"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("찾을 수 없습니다")); }
        failedRetry.setTestTransport((method, params) -> { if (method.equals("thread/delete")) throw new java.io.IOException("network failed"); throw new AssertionError(method); });
        JSONObject stillPending = handle(failedRetry, "runtime.start", new JSONObject());
        assertEquals(1, stillPending.getInt("pendingDeletionCount"));
        failedRetry.io.shutdownNow();

        Engine restarted = new Engine(context);
        java.util.ArrayList<JSONObject> calls = new java.util.ArrayList<>();
        restarted.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("thread/delete")) return new JSONObject();
            throw new AssertionError(method);
        });
        JSONObject afterRetry = handle(restarted, "runtime.start", new JSONObject());
        JSONObject deletion = calls.stream().filter(c -> c.optString("method").equals("thread/delete")).findFirst().orElseThrow();
        assertEquals("s1", deletion.getJSONObject("params").getString("threadId"));
        assertEquals(0, afterRetry.getJSONArray("sessions").length());
        assertEquals(0, afterRetry.getInt("pendingDeletionCount"));
        restarted.io.shutdownNow();
    }
    @Test public void connectedDeletionCallsThreadDeleteAndDoesNotRemainPending() throws Exception {
        JSONArray initial = array(obj("id", "s1", "title", "Delete me", "workspace", "", "workspaceKey", "", "messages", new JSONArray()));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        Engine engine = new Engine(context);
        java.util.ArrayList<JSONObject> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", new JSONObject(params.toString())));
            if (method.equals("thread/delete")) return new JSONObject();
            throw new AssertionError(method);
        });
        handle(engine, "runtime.start", new JSONObject());
        JSONObject deleted = handle(engine, "chat.delete", obj("id", "s1"));
        assertFalse(deleted.getBoolean("deletionPending"));
        JSONObject remote = calls.stream().filter(c -> c.optString("method").equals("thread/delete")).findFirst().orElseThrow();
        assertEquals("s1", remote.getJSONObject("params").getString("threadId"));
        assertEquals(0, deleted.getJSONArray("sessions").length());
        engine.io.shutdownNow();
    }
    @Test public void alreadyDeletedThreadIsReconciledOnlyForExactMissingThreadError() throws Exception {
        JSONArray initial = array(obj("id", "s1", "deletionPending", true));
        dev.mobilecodex.app.core.Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), initial.toString());
        Engine engine = new Engine(context);
        engine.setTestTransport((method, params) -> { throw new java.io.IOException("cannot verify thread not found: s1"); });
        assertEquals(1, handle(engine, "runtime.start", new JSONObject()).getInt("pendingDeletionCount"));
        engine.setTestTransport((method, params) -> { throw new java.io.IOException("thread not found: s1"); });
        assertEquals(0, handle(engine, "runtime.start", new JSONObject()).getInt("pendingDeletionCount"));
        engine.io.shutdownNow();
        Engine restarted = new Engine(context);
        assertEquals(0, handle(restarted, "state", new JSONObject()).getInt("pendingDeletionCount"));
        restarted.io.shutdownNow();
    }
    private JSONObject session(JSONArray sessions, String id) {
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject value = sessions.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return value;
        }
        return null;
    }
}
