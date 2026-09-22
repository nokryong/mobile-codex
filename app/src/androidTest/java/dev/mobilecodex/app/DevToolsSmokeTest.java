package dev.mobilecodex.app;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Optional ARM64-device tests. Local JVM/CI compilation does not execute these. */
@RunWith(AndroidJUnit4.class)
public class DevToolsSmokeTest {
    @Test public void runtimesAndNativePythonModulesRunWithoutLoginOrNetwork() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevTools tools = new DevTools(context);
        File isolatedHome = new File(context.getCacheDir(), "tool-smoke-" + System.nanoTime());
        assertTrue(isolatedHome.mkdirs());
        JSONObject result = tools.check(isolatedHome, isolatedHome);
        assertTrue(result.toString(), result.getBoolean("ok"));
    }
    @Test public void gitCommitAndNpmScriptUseTheSamePrivateProject() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevTools tools = new DevTools(context); File prefix = tools.prepare();
        File project = new File(context.getCacheDir(), "tool-project-" + System.nanoTime()); assertTrue(project.mkdirs());
        Files.write(new File(project, "package.json").toPath(), "{\"name\":\"offline-smoke\",\"version\":\"1.0.0\",\"scripts\":{\"test\":\"node test.js\"}}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(project, "test.js").toPath(), "require('node:fs').writeFileSync('result.txt','ok');".getBytes(StandardCharsets.UTF_8));
        for (String[] command : new String[][]{
                {"npm", "run", "test", "--offline"}, {"git", "init"}, {"git", "add", "."},
                {"git", "-c", "user.name=Runtime Test", "-c", "user.email=test@example.invalid", "commit", "-m", "offline smoke"},
                {"git", "log", "-1", "--oneline"},
                {"git", "grep", "-P", "^o(?=k)", "--", "result.txt"}}) {
            command[0] = new File(prefix, "bin/" + command[0]).getAbsolutePath();
            ProcessBuilder builder = new ProcessBuilder(command).directory(project).redirectErrorStream(true);
            tools.configure(builder, project, project);
            builder.environment().put("HOME", project.getAbsolutePath());
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            JSONObject result = DevTools.probe("offline project", builder);
            assertTrue(result.toString(), result.getBoolean("ok"));
        }
        assertEquals("ok", new String(Files.readAllBytes(new File(project, "result.txt").toPath()), StandardCharsets.UTF_8));
    }
}
