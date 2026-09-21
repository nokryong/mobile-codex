package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class CodexHomeTest {
    private Context context;
    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        remove(new File(context.getFilesDir(), ".codex")); remove(new File(context.getFilesDir(), "codex"));
        context.getSharedPreferences("codex-home-migration", 0).edit().clear().commit();
    }
    @After public void after() { remove(new File(context.getFilesDir(), ".codex")); remove(new File(context.getFilesDir(), "codex")); context.getSharedPreferences("codex-home-migration", 0).edit().clear().commit(); }
    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("test setup failed");
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void remove(File value) {
        File[] children = value.listFiles(); if (children != null) for (File child : children) remove(child);
        value.delete();
    }
    @Test public void migrationCopiesMissingLegacyStateWithoutOverwritingOrResurrectingIt() throws Exception {
        File legacy = new File(context.getFilesDir(), "codex");
        write(new File(legacy, "config.toml"), "model = \"legacy\"");
        write(new File(legacy, "auth.json"), "secret-preserved");
        write(new File(legacy, "skills/review/SKILL.md"), "# review");
        CodexHome home = CodexHome.open(context);
        assertEquals("model = \"legacy\"", read(new File(home.root(), "config.toml")));
        assertEquals("# review", read(new File(home.root(), "skills/review/SKILL.md")));
        assertEquals("secret-preserved", read(new File(legacy, "auth.json")));
        Files.delete(new File(home.root(), "auth.json").toPath());
        CodexHome.open(context);
        assertFalse(new File(home.root(), "auth.json").exists());
    }
    @Test public void conflictLeavesBothFilesIntactAndReportsNoSecretContent() throws Exception {
        File legacy = new File(context.getFilesDir(), "codex"), current = new File(context.getFilesDir(), ".codex");
        write(new File(legacy, "config.toml"), "legacy"); write(new File(current, "config.toml"), "current");
        CodexHome home = CodexHome.open(context);
        assertEquals("legacy", read(new File(legacy, "config.toml")));
        assertEquals("current", read(new File(home.root(), "config.toml")));
        assertTrue(home.migration().conflicts >= 1);
        assertFalse(home.migration().notice().contains("legacy"));
    }
}
