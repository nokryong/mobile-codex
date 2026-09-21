package dev.mobilecodex.app;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

public class DevToolsTest {
    @Test public void processEnvironmentKeepsUserPackagesOutsideVersionedRuntimeAndPreservesCredentials() throws Exception {
        Map<String, String> env = new HashMap<>(); env.put("EXAMPLE_USER_TOKEN", "fixture");
        File prefix = new File("/app/toolchains/version/usr"), lib = new File("/apk/native"), home = new File("/app");
        JSONObject manifest = obj("commands", obj("node", obj("native", "libnode.so"), "python3", obj("native", "libpython3.so")));
        DevTools.environment(env, prefix, lib, home, new File("/cache"), new File(home, ".codex"), new File(home, "runtime-bin"), manifest);
        assertEquals(prefix.getAbsolutePath(), env.get("PYTHONHOME"));
        assertEquals(new File(home, "python-packages").getAbsolutePath(), env.get("PYTHONUSERBASE"));
        assertEquals(new File(home, "node-packages").getAbsolutePath(), env.get("npm_config_prefix"));
        assertEquals(new File(lib, "libmc_exec.so").getAbsolutePath(), env.get("LD_PRELOAD"));
        assertEquals(new File(lib, "libnode.so").getAbsolutePath(), env.get("MC_NODE"));
        assertTrue(env.get("PATH").contains("/node-packages/bin:"));
        assertTrue(env.get("GIT_EXEC_PATH").endsWith("/libexec/git-core"));
        assertEquals("/system/bin/sh", env.get("npm_config_script_shell"));
        assertEquals("fixture", env.get("EXAMPLE_USER_TOKEN"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertFalse(env.containsKey("GIT_SSL_NO_VERIFY"));
        assertFalse(env.containsKey("NODE_TLS_REJECT_UNAUTHORIZED"));
    }
    @Test public void failedToolLaunchIsReportedAsFailure() {
        JSONObject check = DevTools.probe("Missing tool", new ProcessBuilder("/definitely-missing/mobile-codex-tool"));
        assertFalse(check.optBoolean("ok")); assertEquals("Missing tool", check.optString("name"));
        assertFalse(check.optString("output").isBlank());
    }
}
