package dev.mobilecodex.app;

import android.content.Context;
import android.system.Os;
import dev.mobilecodex.app.core.RuntimePayload;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.*;

/** APK-installed native tools and versioned, offline standard-library installation. */
final class DevTools {
    private final Context context;
    private final File nativeDir;
    private JSONObject manifest;
    private File prefix;
    private String loadError = "";
    private boolean prepared;

    DevTools(Context context) {
        this.context = context;
        String libraryPath = context.getApplicationInfo().nativeLibraryDir;
        nativeDir = new File(libraryPath == null ? "/missing-native" : libraryPath);
        try (InputStream in = context.getAssets().open("devtools/manifest.json")) {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) contents.write(block, 0, n);
            byte[] bytes = contents.toByteArray();
            manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (manifest.getInt("schema") != 1) throw new IOException("지원하지 않는 개발 도구 형식입니다.");
            prefix = new File(context.getFilesDir(), "toolchains/" + RuntimePayload.sha256(bytes).substring(0, 24) + "/usr");
        } catch (Exception e) { loadError = "개발 도구가 포함되지 않았습니다. 전체 APK를 설치해 주세요."; }
    }
    synchronized JSONObject status() {
        JSONArray tools = new JSONArray();
        if (manifest != null) {
            JSONObject versions = manifest.optJSONObject("versions");
            if (versions != null) for (String[] names : new String[][]{{"python", "Python"}, {"node", "Node.js"}, {"git", "Git"}, {"npm", "npm"}, {"pip", "pip"}})
                if (versions.has(names[0])) tools.put(obj("name", names[1], "version", versions.optString(names[0])));
        }
        return obj("bundled", manifest != null && loadError.isEmpty(), "prepared", prepared, "tools", tools, "error", loadError);
    }
    synchronized File prepare() throws Exception {
        if (!loadError.isEmpty() || manifest == null) throw new IOException(loadError);
        if (prepared) return prefix;
        JSONObject nativeFiles = manifest.getJSONObject("nativeFiles");
        for (Iterator<String> it = nativeFiles.keys(); it.hasNext();) requireNative(it.next());
        JSONArray required = manifest.optJSONArray("requiredNative");
        if (required != null) for (int i = 0; i < required.length(); i++) requireNative(required.getString(i));
        Path home = prefix.getParentFile().toPath();
        Files.createDirectories(home);
        Path complete = home.resolve("complete");
        if (!Files.isRegularFile(complete)) {
            // A failed staging directory is never reused; user-installed packages live elsewhere.
            Path stage = home.resolve("staging-" + UUID.randomUUID());
            JSONObject payload = manifest.getJSONObject("payload");
            if (!payload.getString("file").equals("payload.zip")) throw new IOException("잘못된 개발 도구 파일입니다.");
            try (InputStream in = context.getAssets().open("devtools/payload.zip")) {
                RuntimePayload.extract(in, stage, payload.getString("sha256"));
                if (Files.exists(prefix.toPath())) removeStaging(prefix.toPath());
                Files.move(stage, prefix.toPath());
                installLinks();
                Files.write(complete, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            } catch (Exception e) {
                removeStaging(stage);
                throw new IOException("개발 도구 준비에 실패했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요: " + e.getMessage(), e);
            }
        } else {
            // APK updates can change nativeLibraryDir even when runtime data has not changed.
            installLinks();
        }
        Files.createDirectories(new File(context.getFilesDir(), "python-packages").toPath());
        Files.createDirectories(new File(context.getFilesDir(), "node-packages/bin").toPath());
        prepared = true;
        return prefix;
    }
    private File requireNative(String name) throws IOException {
        if (!name.matches("lib[A-Za-z0-9_.+-]+\\.so")) throw new IOException("잘못된 실행 파일 이름입니다.");
        File file = new File(nativeDir, name);
        if (!file.isFile() || !file.canExecute()) throw new IOException("개발 도구 실행 파일을 찾지 못했습니다: " + name);
        return file;
    }
    private void installLinks() throws Exception {
        JSONObject links = manifest.getJSONObject("links");
        for (Iterator<String> it = links.keys(); it.hasNext();) {
            String name = it.next(); JSONObject link = links.getJSONObject(name);
            Path destination = RuntimePayload.resolve(prefix.toPath(), name);
            String target;
            if (link.has("native")) target = requireNative(link.getString("native")).getAbsolutePath();
            else if (link.has("system") && link.getString("system").equals("/system/bin/sh")) target = "/system/bin/sh";
            else target = RuntimePayload.resolve(prefix.toPath(), link.getString("path")).toString();
            // Never follow a user-created directory symlink while repairing installed links.
            for (Path p = destination.getParent(); !p.equals(prefix.toPath()); p = p.getParent())
                if (Files.isSymbolicLink(p)) throw new IOException("개발 도구 경로가 변경되었습니다: " + name);
            Files.createDirectories(destination.getParent());
            try { if (Os.readlink(destination.toString()).equals(target)) continue; } catch (android.system.ErrnoException ignored) {}
            Files.deleteIfExists(destination);
            Os.symlink(target, destination.toString());
        }
    }
    private static void removeStaging(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // Files.walk does not follow symlinks; this is only used for our incomplete extraction.
        try (java.util.stream.Stream<Path> files = Files.walk(path)) {
            for (Path child : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(child);
        }
    }
    void configure(ProcessBuilder builder, File codexHome, File aliases) throws Exception {
        File root = prepare();
        environment(builder.environment(), root, nativeDir, context.getFilesDir(), context.getCacheDir(), codexHome, aliases, manifest);
    }
    static void environment(Map<String, String> env, File prefix, File nativeDir, File home, File cache, File codexHome, File aliases, JSONObject manifest) throws Exception {
        String usr = prefix.getAbsolutePath(), lib = nativeDir.getAbsolutePath();
        env.put("HOME", home.getAbsolutePath()); env.put("CODEX_HOME", codexHome.getAbsolutePath());
        env.put("MC_PREFIX", usr); env.put("MC_NATIVE_DIR", lib);
        env.put("MC_NODE", new File(nativeDir, manifest.getJSONObject("commands").getJSONObject("node").getString("native")).getAbsolutePath());
        env.put("MC_PYTHON", new File(nativeDir, manifest.getJSONObject("commands").getJSONObject("python3").getString("native")).getAbsolutePath());
        env.put("LD_LIBRARY_PATH", lib); env.put("LD_PRELOAD", new File(nativeDir, "libmc_exec.so").getAbsolutePath());
        env.put("PATH", aliases.getAbsolutePath() + ":" + usr + "/bin:" + home.getAbsolutePath() + "/node-packages/bin:" + home.getAbsolutePath() + "/python-packages/bin:" + lib + ":/system/bin:/system/xbin");
        env.put("TMPDIR", cache.getAbsolutePath()); env.put("TMP", cache.getAbsolutePath()); env.put("TEMP", cache.getAbsolutePath());
        env.put("SHELL", "/system/bin/sh"); env.put("LANG", "C.UTF-8");
        env.put("PYTHONHOME", usr); env.put("PYTHONUSERBASE", new File(home, "python-packages").getAbsolutePath());
        env.put("PYTHONUTF8", "1"); env.put("PIP_USER", "1"); env.put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        env.put("npm_config_prefix", new File(home, "node-packages").getAbsolutePath());
        env.put("npm_config_cache", new File(cache, "npm").getAbsolutePath());
        env.put("npm_config_script_shell", "/system/bin/sh");
        env.put("NODE_PATH", usr + "/lib/node_modules:" + new File(home, "node-packages/lib/node_modules").getAbsolutePath());
        env.put("GIT_EXEC_PATH", usr + "/libexec/git-core"); env.put("GIT_TEMPLATE_DIR", usr + "/share/git-core/templates");
        env.put("GIT_CONFIG_NOSYSTEM", "1"); env.put("GIT_TERMINAL_PROMPT", "0"); env.put("GIT_PAGER", "cat");
        env.put("SSL_CERT_FILE", usr + "/etc/tls/cert.pem"); env.put("GIT_SSL_CAINFO", usr + "/etc/tls/cert.pem");
        env.put("CURL_CA_BUNDLE", usr + "/etc/tls/cert.pem"); env.put("NODE_EXTRA_CA_CERTS", usr + "/etc/tls/cert.pem");
        env.put("PIP_CERT", usr + "/etc/tls/cert.pem");
        env.put("OPENSSL_CONF", usr + "/etc/tls/openssl.cnf");
        env.put("OPENSSL_MODULES", usr + "/lib/ossl-modules");
        env.put("OPENSSL_ENGINES", usr + "/lib/engines-3");
        env.put("TERMINFO", usr + "/share/terminfo");
    }
    JSONObject check(File codexHome, File aliases) throws Exception {
        File root = prepare(); JSONArray checks = new JSONArray(); boolean ok = true;
        String[][] commands = {
            {"Python", "python3", "-c", "import sys,ssl,sqlite3,ctypes,zlib,bz2,lzma; assert sqlite3.connect(':memory:').execute('select 1').fetchone()[0]==1; print(sys.version.split()[0]); print(ssl.OPENSSL_VERSION)"},
            {"Node.js", "node", "-e", "const c=require('node:child_process');console.log(process.version);console.log(c.execFileSync(process.execPath,['-e','process.stdout.write(\"child process OK\")']).toString())"},
            {"Git", "git", "--version"}, {"npm", "npm", "--version"}, {"pip", "pip", "--version"}
        };
        for (String[] command : commands) {
            List<String> argv = new ArrayList<>(Arrays.asList(command).subList(1, command.length));
            argv.set(0, new File(root, "bin/" + command[1]).getAbsolutePath());
            ProcessBuilder builder = new ProcessBuilder(argv).directory(context.getCacheDir()).redirectErrorStream(true);
            configure(builder, codexHome, aliases);
            JSONObject result = probe(command[0], builder); checks.put(result); ok &= result.optBoolean("ok");
        }
        return obj("ok", ok, "checks", checks);
    }
    static JSONObject probe(String name, ProcessBuilder builder) {
        Process process = null;
        try {
            process = builder.start(); Process child = process; ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = child.getInputStream()) {
                    byte[] block = new byte[2048]; int n;
                    while ((n = in.read(block)) != -1) synchronized (bytes) { if (bytes.size() < 16384) bytes.write(block, 0, Math.min(n, 16384 - bytes.size())); }
                } catch (IOException ignored) {}
            }, "devtools-check"); reader.setDaemon(true); reader.start();
            boolean ended = process.waitFor(15, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            reader.join(1000);
            String output; synchronized (bytes) { output = new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
            return obj("name", name, "ok", ended && process.exitValue() == 0, "output", ended ? output.strip() : "실행 확인 시간이 초과되었습니다.\n" + output);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return obj("name", name, "ok", false, "output", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
