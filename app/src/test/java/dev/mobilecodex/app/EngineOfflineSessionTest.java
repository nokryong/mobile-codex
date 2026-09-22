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
    }
    @After public void after() {
        new File(context.getFilesDir(), "sessions.json").delete();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
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
