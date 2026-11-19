package dev.mobilecodex.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class LinuxCodexRuntimeTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void onlyAValidatedSelectedVersionCanRun() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(true);
        LinuxCodexRuntime runtime = new LinuxCodexRuntime(files.getRoot(), files.newFolder("native"), enabled::get);
        File store = files.newFolder("codex-linux-releases"), release = new File(store, "0.170.0");
        File executable = new File(release, "package/vendor/aarch64-unknown-linux-musl/bin/codex");
        Files.createDirectories(executable.getParentFile().toPath());
        Files.createFile(executable.toPath());
        assertNull(runtime.selected());
        File pointer = new File(store, "current");
        Files.write(pointer.toPath(), "../outside".getBytes("UTF-8"));
        assertNull(runtime.selected());
        Files.write(pointer.toPath(), release.getName().getBytes("UTF-8"));
        assertEquals(release, runtime.selected());
        enabled.set(false);
        assertNull(runtime.selected());
        enabled.set(true);
        runtime.reject(release);
        assertNull(runtime.selected());
    }

    @Test public void usesTheExistingAccountAndProjectAtTheirOriginalPaths() throws Exception {
        LinuxCodexRuntime runtime = new LinuxCodexRuntime(files.getRoot(), files.newFolder("native"), () -> true);
        File home = files.newFolder("existing-login"), work = files.newFolder("project with spaces");
        File release = files.newFolder("version"), asset = files.newFolder("attachments");
        ProcessBuilder builder = runtime.server(release, home, work, Arrays.asList("app-server","--listen","stdio://"), asset);
        List<String> command = builder.command();
        assertEquals(home.getAbsolutePath(), builder.environment().get("CODEX_HOME"));
        assertFalse(builder.environment().containsKey("LD_PRELOAD"));
        for (File path : Arrays.asList(home, work, asset))
            assertTrue(command.contains(path + ":" + path));
        assertEquals(Arrays.asList("app-server", "--listen", "stdio://"), command.subList(command.size()-3,command.size()));
        assertEquals(work, builder.directory());
    }
}
