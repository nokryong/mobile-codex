package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class PersonalInstructionsTest {
    private Context context;
    @Before public void before() { context = RuntimeEnvironment.getApplication(); remove(new File(context.getFilesDir(), ".codex")); remove(new File(context.getFilesDir(), "codex")); context.getSharedPreferences("codex-home-migration", 0).edit().clear().commit(); }
    @After public void after() { remove(new File(context.getFilesDir(), ".codex")); remove(new File(context.getFilesDir(), "codex")); context.getSharedPreferences("codex-home-migration", 0).edit().clear().commit(); }
    private static void remove(File value) { File[] children = value.listFiles(); if (children != null) for (File child : children) remove(child); value.delete(); }
    private JSONObject handle(Engine engine, String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); });
        return result.get(5, TimeUnit.SECONDS);
    }
    @Test public void savesReopensClearsAndEditsTheEffectiveOverrideOffline() throws Exception {
        CodexHome home = CodexHome.open(context); PersonalInstructions store = new PersonalInstructions(home);
        assertFalse(store.read().getBoolean("overridden"));
        assertEquals("Always test.", store.save("Always test.").getString("content"));
        assertEquals("Always test.", new PersonalInstructions(home).read().getString("content"));
        dev.mobilecodex.app.core.Utf8Files.write(home.child("AGENTS.override.md").toPath(), "Temporary override");
        JSONObject override = store.read(); assertTrue(override.getBoolean("overridden")); assertTrue(override.getString("path").endsWith("AGENTS.override.md"));
        assertEquals("Override updated", store.save("Override updated").getString("content"));
        assertEquals("Always test.", dev.mobilecodex.app.core.Utf8Files.read(home.child("AGENTS.md").toPath()));
        JSONObject fallback = store.save("");
        assertFalse(fallback.getBoolean("overridden"));
        assertTrue(fallback.getString("activePath").endsWith("AGENTS.md"));
        assertEquals("Always test.", fallback.getString("content"));
        assertTrue(fallback.getString("notice").contains("다시 활성화"));
    }
    @Test public void failedAtomicWriteRetainsExistingData() throws Exception {
        File blocked = new File(CodexHome.open(context).root(), "blocked");
        Files.write(blocked.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PersonalInstructions.writeAtomically(new File(blocked, "AGENTS.md"), "new"));
        assertEquals("keep", new String(Files.readAllBytes(blocked.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void engineActionsSaveOfflineAndStopTheRuntimeForReload() throws Exception {
        Engine engine = new Engine(context);
        engine.setTestTransport((method, params) -> { throw new AssertionError(method); });
        handle(engine, "runtime.start", new JSONObject());
        JSONObject saved = handle(engine, "instructions.save", obj("content", "Use Korean."));
        assertTrue(saved.getString("path").endsWith("AGENTS.md"));
        assertFalse(handle(engine, "state", new JSONObject()).getBoolean("ready"));
        Engine reopened = new Engine(context);
        assertEquals("Use Korean.", handle(reopened, "instructions.read", new JSONObject()).getString("content"));
        engine.io.shutdownNow(); reopened.io.shutdownNow();
    }
    @Test public void savingInstructionsForcesTheSameThreadToResumeInTheNextServer() throws Exception {
        File sessions = new File(context.getFilesDir(), "sessions.json");
        dev.mobilecodex.app.core.Utf8Files.write(sessions.toPath(), array(obj("id", "thread", "title", "Existing", "workspaceKey", "", "messages", array(), "imageHistoryVersion", 1)).toString());
        Engine engine = new Engine(context); java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        engine.setTestTransport((method, params) -> { calls.add(method); assertEquals("thread/resume", method); return obj(); });
        handle(engine, "chat.resume", obj("id", "thread"));
        handle(engine, "runtime.start", obj()); assertEquals(1, calls.size());
        handle(engine, "instructions.save", obj("content", "Use concise Korean."));
        handle(engine, "runtime.start", obj()); assertEquals(2, calls.size());
        engine.io.shutdownNow(); sessions.delete();
    }

}
