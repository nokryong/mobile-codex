package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import dev.mobilecodex.app.core.ProjectRegistry;
import dev.mobilecodex.app.core.Utf8Files;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class ProjectIdentityIntegrationTest {
    private Context context;
    private Engine engine;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        Files.deleteIfExists(new File(context.getFilesDir(), "sessions.json").toPath());
        File[] leftovers = context.getFilesDir().listFiles((directory, name) -> name.startsWith("sessions-before-") || name.equals("blocked-sessions-target"));
        if (leftovers != null) for (File value : leftovers) delete(value);
    }
    @After public void cleanup() { if (engine != null) engine.io.shutdownNow(); }
    private JSONObject call(String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); });
        return result.get(10, TimeUnit.SECONDS);
    }
    private String seed() {
        String legacy = obj("selectedKey", "a", "projects", array(
            obj("key", "a", "name", "A", "uri", "content://provider/tree/a"),
            obj("key", "b", "name", "B", "uri", "content://provider/tree/b")), "removed", array()).toString();
        context.getSharedPreferences("projects", 0).edit().putString("registry", legacy).commit(); return legacy;
    }
    @Test public void migrationBacksUpOriginalAndMergeKeepsSessionsAndFolderBytes() throws Exception {
        String legacy = seed();
        File source = new File(context.getFilesDir(), "project-file.txt"); Utf8Files.write(source.toPath(), "unchanged source");
        String sessions = array(obj("id", "thread-a", "title", "A chat", "workspaceKey", "a", "messages", array()),
            obj("id", "thread-b", "title", "B chat", "workspaceKey", "b", "messages", array())).toString();
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), sessions);
        engine = new Engine(context);
        String migratedSessions = Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath());
        JSONArray migrated = new JSONArray(migratedSessions);
        assertEquals(2, migrated.length());
        for (int i = 0; i < migrated.length(); i++) {
            JSONObject value = migrated.getJSONObject(i);
            String suffix = i == 0 ? "a" : "b";
            assertEquals("thread-" + suffix, value.getString("id"));
            assertEquals(i == 0 ? "A chat" : "B chat", value.getString("title"));
            assertEquals(suffix, value.getString("workspaceKey"));
            assertEquals(0, value.getJSONArray("messages").length());
            assertEquals("thread-" + suffix, value.getString("codexThreadId"));
            assertEquals(2, value.getInt("sessionVersion"));
        }
        engine.setTestTransport((method, params) -> { throw new AssertionError("Unexpected Codex RPC: " + method); });
        assertEquals(legacy, context.getSharedPreferences("projects", 0).getString("registry-before-identities-v1", ""));
        call("chat.resume", obj("id", "thread-a"));
        call("projects.merge", obj("sourceKey", "a", "targetKey", "b"));
        JSONObject snapshot = call("state", obj());
        assertEquals("thread-a", snapshot.getString("threadId"));
        assertEquals("a", snapshot.getJSONObject("workspace").getString("key"));
        assertEquals("B", snapshot.getJSONObject("workspace").getString("name"));
        assertEquals(1, snapshot.getJSONArray("projects").length());
        assertEquals(migratedSessions, Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        assertEquals("unchanged source", Utf8Files.read(source.toPath()));
        String projectId = snapshot.getJSONObject("workspace").getString("projectId");
        engine.io.shutdownNow(); engine = new Engine(context);
        snapshot = call("chat.resume", obj("id", "thread-b"));
        assertEquals("b", snapshot.getJSONObject("workspace").getString("key"));
        assertEquals(projectId, snapshot.getJSONObject("workspace").getString("projectId"));
        assertEquals(2, snapshot.getJSONArray("sessions").length());
        assertEquals(migratedSessions, Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        assertEquals(legacy, context.getSharedPreferences("projects", 0).getString("registry-before-identities-v1", ""));
    }
    @Test public void unboundProjectIsPersistentAndCannotStartCodex() throws Exception {
        engine = new Engine(context);
        engine.setTestTransport((method, params) -> { throw new AssertionError("Unbound project must not call Codex"); });
        JSONObject created = call("projects.create", obj("name", "Only metadata"));
        assertFalse(created.getBoolean("available")); assertFalse(created.getBoolean("hasLocalFolder"));
        ExecutionException error = assertThrows(ExecutionException.class, () -> call("chat.send", obj("text", "run", "workspaceKey", created.getString("key"))));
        assertTrue(error.getCause().getMessage().contains("폴더"));
        engine.io.shutdownNow(); engine = new Engine(context);
        assertEquals(created.getString("projectId"), call("state", obj()).getJSONObject("workspace").getString("projectId"));
    }
    @Test public void failedCommitDoesNotPublishAnInMemoryMergeOrDefaultChange() throws Exception {
        seed(); new DocumentStore(context); // Complete the migration before injecting failure.
        SharedPreferences real = context.getSharedPreferences("projects", 0);
        String before = real.getString("registry", "");
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
            if (!method.getName().equals("edit")) return method.invoke(real, args);
            SharedPreferences.Editor editor = real.edit();
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (p, m, a) -> {
                if (m.getName().equals("commit")) return false;
                if (m.getReturnType() == SharedPreferences.Editor.class) return p;
                return m.invoke(editor, a);
            });
        });
        Context wrapper = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return name.equals("projects") ? failing : super.getSharedPreferences(name, mode); }
        };
        DocumentStore store = new DocumentStore(wrapper);
        assertThrows(java.io.IOException.class, () -> store.mergeProjects("a", "b"));
        assertThrows(java.io.IOException.class, () -> store.preferProject("b"));
        assertThrows(java.io.IOException.class, () -> store.createProject("Uncommitted"));
        assertThrows(java.io.IOException.class, () -> store.selectProject("b"));
        assertEquals("", store.restoreProjectKey("old-thread", "unknown", "Old"));
        assertEquals(2, store.projects().length());
        assertEquals("a", store.workspace().getString("key"));
        assertNotEquals(store.projectId("a"), store.projectId("b"));
        assertEquals(before, real.getString("registry", ""));
    }
    @Test public void backgroundTurnBlocksIdentityMutationsWithoutChangingRegistry() throws Exception {
        seed(); engine = new Engine(context);
        String before = context.getSharedPreferences("projects", 0).getString("registry", "");
        java.lang.reflect.Field field = Engine.class.getDeclaredField("runningTurns"); field.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<String, String> turns = (java.util.Map<String, String>) field.get(engine);
        turns.put("background-thread", "turn-running");
        assertThrows(ExecutionException.class, () -> call("projects.merge", obj("sourceKey", "a", "targetKey", "b")));
        assertThrows(ExecutionException.class, () -> call("projects.create", obj("name", "Pending")));
        assertThrows(ExecutionException.class, () -> call("projects.prefer", obj("key", "b")));
        assertEquals(before, context.getSharedPreferences("projects", 0).getString("registry", ""));
    }
    @Test public void inactiveProOperationBlocksIdentityMutationsAndDelayedFolderBinding() throws Exception {
        seed(); engine = new Engine(context);
        String before = context.getSharedPreferences("projects", 0).getString("registry", "");
        java.lang.reflect.Field field = Engine.class.getDeclaredField("sessions"); field.setAccessible(true);
        JSONArray sessions = (JSONArray) field.get(engine);
        sessions.put(obj("id", "inactive-pro", "workspaceKey", "b", "messages", array(),
            "proOperation", obj("id", "op", "messageId", "pending")));
        assertThrows(ExecutionException.class, () -> call("projects.merge", obj("sourceKey", "a", "targetKey", "b")));
        assertThrows(ExecutionException.class, () -> call("projects.remove", obj("key", "b")));
        assertThrows(ExecutionException.class, () -> call("projects.create", obj("name", "Pending")));
        assertThrows(ExecutionException.class, () -> call("projects.prefer", obj("key", "b")));
        java.io.IOException picker = assertThrows(java.io.IOException.class,
            () -> engine.selectProjectFolder(android.net.Uri.parse("content://provider/tree/rebind"), "b"));
        assertTrue(picker.getMessage().contains("진행 중인 작업"));
        assertEquals(before, context.getSharedPreferences("projects", 0).getString("registry", ""));
    }
    @Test public void removingOneMergedBindingMovesOnlyItsChatsAndPreservesEveryOtherField() throws Exception {
        seed();
        String raw = array(
            obj("id", "a-chat", "title", "A", "workspace", "A", "workspaceKey", "a", "model", "gpt-6-astra",
                "createdAt", 7, "chatConversationId", "remote-a", "messages", array(obj("id", "ma", "text", "keep-a"))),
            obj("id", "b-chat", "title", "B", "workspace", "B", "workspaceKey", "b", "fastMode", true,
                "chatProjectPath", "/g/g-p-project", "messages", array(obj("id", "mb", "text", "keep-b")))).toString();
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), raw);
        engine = new Engine(context);
        call("projects.merge", obj("sourceKey", "a", "targetKey", "b"));
        JSONObject removed = call("projects.remove", obj("key", "a"));
        assertEquals(1, removed.getInt("movedChats"));
        JSONArray stored = new JSONArray(Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        JSONObject a = stored.getJSONObject(0), b = stored.getJSONObject(1);
        assertEquals("", a.getString("workspaceKey")); assertEquals("", a.getString("workspace"));
        assertEquals("gpt-6-astra", a.getString("model")); assertEquals(7, a.getInt("createdAt"));
        assertEquals("remote-a", a.getString("chatConversationId")); assertEquals("keep-a", a.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertEquals("b", b.getString("workspaceKey")); assertEquals("B", b.getString("workspace"));
        assertTrue(b.getBoolean("fastMode")); assertEquals("/g/g-p-project", b.getString("chatProjectPath"));
        assertEquals("keep-b", b.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertEquals(1, call("state", obj()).getJSONArray("projects").length());
    }
    @Test public void removalWriteFailureUsesTombstoneAsRecoveryJournalAndRestartFinishesMigration() throws Exception {
        seed();
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(
            obj("id", "a-chat", "title", "A", "workspace", "A", "workspaceKey", "a", "model", "gpt-6-astra",
                "createdAt", 9, "chatConversationId", "remote-a", "messages", array(obj("id", "m", "text", "preserved"))),
            obj("id", "b-chat", "title", "B", "workspace", "B", "workspaceKey", "b", "messages", array())).toString());
        engine = new Engine(context);
        String durableBefore = Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath());
        File blocked = new File(context.getFilesDir(), "blocked-sessions-target"); assertTrue(blocked.mkdir());
        Files.write(new File(blocked, "keep").toPath(), new byte[]{1});
        engine.setStateFileForTest(blocked);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> call("projects.remove", obj("key", "a")));
        assertTrue(failure.getCause().getMessage().contains("프로젝트 연결은 해제됐지만"));
        assertEquals(durableBefore, Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        assertEquals("", call("state", obj()).getJSONArray("sessions").getJSONObject(1).getString("workspaceKey"));
        File[] backups = context.getFilesDir().listFiles((directory, name) -> name.startsWith("sessions-before-project-remove-") && name.endsWith(".json"));
        assertNotNull(backups); assertEquals(1, backups.length); assertEquals(durableBefore, Utf8Files.read(backups[0].toPath()));

        engine.io.shutdownNow(); engine = new Engine(context);
        JSONArray recovered = new JSONArray(Utf8Files.read(new File(context.getFilesDir(), "sessions.json").toPath()));
        JSONObject moved = recovered.getJSONObject(0), untouched = recovered.getJSONObject(1);
        assertEquals("", moved.getString("workspaceKey")); assertEquals("", moved.getString("workspace"));
        assertEquals("gpt-6-astra", moved.getString("model")); assertEquals("remote-a", moved.getString("chatConversationId"));
        assertEquals("preserved", moved.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertEquals("b", untouched.getString("workspaceKey"));
    }
    @Test public void temporarilyUnavailableSingleFolderMigrationCanRetry() {
        String uri = "content://unavailable.provider/tree/old-folder";
        context.getSharedPreferences("workspace", 0).edit().putString("uri", uri).commit();
        new DocumentStore(context);
        assertEquals("", context.getSharedPreferences("projects", 0).getString("registry", ""));
        assertEquals(uri, context.getSharedPreferences("workspace", 0).getString("uri", ""));
    }
    @Test public void importPreviewIsReadOnlyAndStaleConfirmationCannotWrite() throws Exception {
        seed(); engine = new Engine(context);
        engine.setTestTransport((method, params) -> { throw new AssertionError("Project import must not call Codex"); });
        ProjectRegistry other = new ProjectRegistry(); other.createUnbound("From desktop");
        String raw = other.exportProject(other.selectedKey()).toString();
        String before = context.getSharedPreferences("projects",0).getString("registry", "");
        JSONObject preview = call("projects.import.preview", obj("content",raw));
        assertEquals(before,context.getSharedPreferences("projects",0).getString("registry", ""));
        call("projects.rename",obj("key","a","name","New local name"));
        String changed = context.getSharedPreferences("projects",0).getString("registry", "");
        assertThrows(ExecutionException.class, () -> call("projects.import.apply",obj("content",raw,"token",preview.getString("token"))));
        assertEquals(changed,context.getSharedPreferences("projects",0).getString("registry", ""));
        JSONObject fresh = call("projects.import.preview",obj("content",raw));
        JSONObject applied = call("projects.import.apply",obj("content",raw,"token",fresh.getString("token")));
        assertEquals(3,applied.getJSONArray("projects").length()); assertEquals("a",applied.getJSONObject("workspace").getString("key"));
        JSONObject imported = applied.getJSONArray("projects").getJSONObject(2);
        assertFalse(imported.getBoolean("hasLocalFolder")); assertFalse(imported.getBoolean("available"));
        assertEquals(0,call("projects.import.preview",obj("content",raw)).getJSONObject("result").getInt("addedEvents"));
        DocumentStore restarted = new DocumentStore(context); assertEquals(3,restarted.projects().length());
    }
    private static void delete(File value) throws Exception {
        if (!value.exists()) return;
        File[] children = value.listFiles(); if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(value.toPath());
    }
}
