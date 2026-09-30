package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EngineFileContextTest {
    private Context context;
    private Engine engine;
    private File source;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
        new File(context.getFilesDir(), "sessions.json").delete();
        new File(context.getFilesDir(), "sessions.json.tmp").delete();
        engine = new Engine(context);
        source = new File(new File(context.getFilesDir(), "workspace"), "file-context-test.txt");
        Files.write(source.toPath(), "original".getBytes(StandardCharsets.UTF_8));
    }
    @After public void after() throws Exception {
        engine.io.shutdownNow();
        java.lang.reflect.Field field = Engine.class.getDeclaredField("approvalTimer"); field.setAccessible(true);
        ((ScheduledExecutorService) field.get(engine)).shutdownNow();
        Files.deleteIfExists(source.toPath());
        new File(context.getFilesDir(), "sessions.json").delete();
        new File(context.getFilesDir(), "sessions.json.tmp").delete();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
    }
    private CompletableFuture<JSONObject> request(String action, JSONObject args) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action, args, (value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); });
        return result;
    }
    private JSONObject handle(String action, JSONObject args) throws Exception { return request(action, args).get(5, TimeUnit.SECONDS); }
    private void assertStale(CompletableFuture<JSONObject> result) throws Exception {
        try { result.get(5, TimeUnit.SECONDS); fail("stale file operations must fail"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("프로젝트가 바뀌었습니다")); }
    }
    @Test public void scopedFileRequestsRejectAnotherWorkspaceBeforeReadingOrMutating() throws Exception {
        for (String action : new String[]{"files.list", "files.search", "files.read", "files.mention", "images.read"}) {
            assertStale(request(action, obj("workspaceKey", "another-project", "path", source.getName(), "query", "file-context")));
        }
        JSONObject original = handle("files.read", obj("workspaceKey", "", "path", source.getName()));
        assertStale(request("files.mutate", obj("workspaceKey", "another-project", "operation", "mobile_write", "arguments",
            obj("path", source.getName(), "content", "wrong-project-edit", "expectedSha256", original.getString("sha256")))));
        assertEquals("original", new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        assertEquals("original", handle("files.read", obj("path", source.getName())).getString("content"));
    }
    @Test public void approvingAnOldFileMutationAfterProjectSwitchDoesNotModifyTheOriginalFile() throws Exception {
        CompletableFuture<Engine.Approval> shown = new CompletableFuture<>();
        engine.attach(new Engine.Ui() {
            public void event(String name, JSONObject data) {}
            public void approval(Engine.Approval approval) { shown.complete(approval); }
        });
        JSONObject original = handle("files.read", obj("workspaceKey", "", "path", source.getName()));
        CompletableFuture<JSONObject> mutation = request("files.mutate", obj("workspaceKey", "", "operation", "mobile_write", "arguments",
            obj("path", source.getName(), "content", "pending edit", "expectedSha256", original.getString("sha256"))));
        Engine.Approval approval = shown.get(5, TimeUnit.SECONDS);
        handle("projects.create", obj("name", "Another project"));
        assertFalse(handle("state", obj()).getJSONObject("workspace").getString("key").isEmpty());
        approval.decision.complete(true);
        assertStale(mutation);
        assertEquals("original", new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
    }
}
