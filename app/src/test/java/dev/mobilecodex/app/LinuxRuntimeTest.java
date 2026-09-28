package dev.mobilecodex.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class LinuxRuntimeTest {
    @Test public void defaultStateIsDisabledAndDoesNotClaimInstalled() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("settings", 0).edit().remove("linuxRuntimeEnabled").commit();
        LinuxRuntime runtime = new LinuxRuntime(context, new DevTools(context), () -> {});
        assertFalse(runtime.status().optBoolean("enabled"));
        assertFalse(runtime.status().optBoolean("installed"));
        assertEquals("not_installed", runtime.status().optString("state"));
        runtime.close();
    }

    @Test public void enablingWithoutValidatedRootfsIsRejected() {
        Context context = RuntimeEnvironment.getApplication();
        LinuxRuntime runtime = new LinuxRuntime(context, new DevTools(context), () -> {});
        try { runtime.setEnabled(true); fail("must require an installed rootfs"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Linux")); }
        finally { runtime.close(); }
    }

    @Test public void damagedInstallationIsRemovableWithoutDeletingLinkedProject() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Path root = new File(context.getFilesDir(), "linux/rootfs").toPath();
        Files.createDirectories(root);
        Files.write(root.resolve(".mobile-codex-ready"), "wrong-version".getBytes(StandardCharsets.UTF_8));
        Path project = new File(context.getFilesDir(), "retained-project").toPath();
        Files.createDirectories(project); Files.write(project.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("workspace"), project);
        LinuxRuntime runtime = new LinuxRuntime(context, new DevTools(context), () -> {});
        try {
            assertTrue(runtime.status().getBoolean("hasFiles"));
            assertFalse(runtime.status().getBoolean("installed"));
            runtime.remove();
            assertFalse(runtime.status().getBoolean("hasFiles"));
            assertEquals("keep", new String(Files.readAllBytes(project.resolve("keep.txt")), StandardCharsets.UTF_8));
        } finally { runtime.close(); }
    }

    @Test public void togglePersistsForAlreadyRunningShellsAndRollsBackWhenWriteFails() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("settings", 0).edit().remove("linuxRuntimeEnabled").commit();
        LinuxRuntime runtime = new LinuxRuntime(context, new DevTools(context), () -> {});
        Path home = new File(context.getFilesDir(), "linux").toPath();
        Path root = home.resolve("rootfs"); Files.createDirectories(root);
        JSONObject pinned;
        try (java.io.InputStream input = context.getAssets().open("linux/manifest.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
            pinned = new JSONObject(bytes.toString("UTF-8"));
        }
        Files.write(root.resolve(".mobile-codex-ready"), pinned.getString("id").getBytes(StandardCharsets.UTF_8));
        try {
            runtime.setEnabled(true);
            assertTrue(new JSONObject(new String(Files.readAllBytes(home.resolve("state.json")), StandardCharsets.UTF_8)).getBoolean("enabled"));
            runtime.setEnabled(false);
            assertFalse(new JSONObject(new String(Files.readAllBytes(home.resolve("state.json")), StandardCharsets.UTF_8)).getBoolean("enabled"));
            Files.createDirectory(home.resolve("state.json.new"));
            try { runtime.setEnabled(true); fail("Expected atomic state write to fail"); }
            catch (IllegalStateException expected) { assertFalse(runtime.status().getBoolean("enabled")); }
            assertFalse(context.getSharedPreferences("settings", 0).getBoolean("linuxRuntimeEnabled", true));
        } finally {
            Files.deleteIfExists(home.resolve("state.json.new"));
            runtime.remove(); runtime.close();
        }
    }
}
