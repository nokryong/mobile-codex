package dev.mobilecodex.app;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.TimeUnit;
import dev.mobilecodex.app.core.RpcClient;
import org.json.JSONObject;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RuntimeSmokeTest {
    @Test public void bundledEngineInitializesWithoutTermuxOrCredentials() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File lib = new File(context.getApplicationInfo().nativeLibraryDir), binary = new File(lib, "libcodex.so");
        assertTrue(binary.canExecute());
        File home = new File(context.getCacheDir(), "smoke-" + System.nanoTime()); assertTrue(home.mkdirs());
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "app-server", "--listen", "stdio://").directory(home);
        builder.environment().put("HOME", home.getAbsolutePath()); builder.environment().put("CODEX_HOME", home.getAbsolutePath());
        builder.environment().put("LD_LIBRARY_PATH", lib.getAbsolutePath()); builder.environment().put("SHELL", "/system/bin/sh");
        Process process = builder.start();
        Thread drain = new Thread(() -> { try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {} }); drain.setDaemon(true); drain.start();
        try (RpcClient rpc = new RpcClient(process.getInputStream(), process.getOutputStream(), new RpcClient.Listener() {
            public void notification(String m, JSONObject p) {}
            public void request(Object id, String m, JSONObject p) {}
            public void disconnected(Throwable e) {}
        })) {
            rpc.start(); JSONObject result = rpc.request("initialize", obj("clientInfo", obj("name", "mobile_codex_test", "version", "1"), "capabilities", obj("experimentalApi", true))).get(30, TimeUnit.SECONDS);
            assertTrue(result.has("userAgent")); rpc.notify("initialized", obj());
            JSONObject account = rpc.request("account/read", obj("refreshToken", false)).get(30, TimeUnit.SECONDS);
            assertTrue(account.isNull("account"));
        } finally { process.destroyForcibly(); }
    }
}
