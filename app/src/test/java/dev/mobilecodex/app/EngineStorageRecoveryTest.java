package dev.mobilecodex.app;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineStorageRecoveryTest {
    private JSONObject call(Engine engine, String method, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        engine.handle(method, args, (result, error) -> { if (error != null) future.completeExceptionally(error); else future.complete(result); });
        return future.get(5, TimeUnit.SECONDS);
    }
    @Test public void runtimeStopAndNewMessagePreserveExactCorruptOriginal() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "sessions.json");
        byte[] corrupt = new byte[]{'[', '{', 'x', 0, (byte)0xff, '\n'};
        Files.write(file.toPath(), corrupt);
        Engine engine = new Engine(RuntimeEnvironment.getApplication());
        try {
            assertTrue(call(engine, "sessions.storage", obj()).getBoolean("recoveryRequired"));
            call(engine, "runtime.stop", obj());
            assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
            assertThrows(ExecutionException.class, () -> call(engine, "chat.send", obj("text", "must not replace")));
            assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
            assertTrue(call(engine, "state", obj()).getJSONObject("sessionStorage").getBoolean("recoveryRequired"));
        } finally { engine.io.shutdownNow(); Files.delete(file.toPath()); }
    }
}
