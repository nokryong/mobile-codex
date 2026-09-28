package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class LinuxEngineIntegrationTest {
    private JSONObject call(Engine engine, String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> {
            if (error != null) result.completeExceptionally(error); else result.complete(value);
        });
        return result.get(5, TimeUnit.SECONDS);
    }
    @Test public void readingLinuxStatusDoesNotStartCodexOrInstallAnything() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Engine engine = new Engine(context);
        engine.setTestTransport((method, args) -> { throw new AssertionError("Unexpected Codex RPC: " + method); });
        try {
            JSONObject status = call(engine, "linux.status", obj());
            assertFalse(status.getBoolean("enabled"));
            assertFalse(status.getBoolean("busy"));
            JSONObject snapshot = call(engine, "state", obj());
            assertFalse(snapshot.getJSONObject("linux").getBoolean("installed"));
            assertFalse(snapshot.getBoolean("ready"));
        } finally { engine.io.shutdownNow(); }
    }
    @Test public void settingsCannotRemoveEnvironmentWhileAnotherChatIsRunning() throws Exception {
        Engine engine = new Engine(RuntimeEnvironment.getApplication());
        Field field = Engine.class.getDeclaredField("runningTurns"); field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> running = (Map<String, String>) field.get(engine);
        running.put("another-chat", "active-turn");
        try {
            try { call(engine, "linux.remove", obj()); fail("Running work must block removal"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("진행 중인 작업")); }
            assertEquals("active-turn", running.get("another-chat"));
        } finally { engine.io.shutdownNow(); }
    }
    @Test public void linuxInstructionsUseNormalShellApprovalsAndDoNotPretendInstallation() {
        assertTrue(Engine.linuxInstructions(obj("enabled", true, "installed", false)).contains("disabled"));
        assertFalse(Engine.linuxInstructions(obj("enabled", false, "installed", true)).contains("mc-linux --"));
        String enabled = Engine.linuxInstructions(obj("enabled", true, "installed", true));
        assertTrue(enabled.contains("mc-linux --"));
        assertTrue(enabled.contains("normal approval policy"));
        assertTrue(enabled.contains("/workspace"));
        assertTrue(enabled.contains("not automatically available"));
    }
}
