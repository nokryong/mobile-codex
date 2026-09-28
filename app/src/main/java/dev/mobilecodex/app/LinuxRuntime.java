package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static dev.mobilecodex.app.core.Json.*;

/** Optional, on-demand Arch Linux ARM runtime. It never downloads until install() is called. */
public final class LinuxRuntime implements AutoCloseable {
    private static final String PREFS = "settings", ENABLED = "linuxRuntimeEnabled";
    private static final String ROOTFS = "rootfs", READY = ".mobile-codex-ready";
    private final Context context;
    private final DevTools devTools;
    private final Runnable onChanged;
    private final File home, rootfs, script, manifestFile, wrapperState;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private JSONObject manifest;
    private volatile Process toolProcess;
    private volatile HttpURLConnection downloadConnection;
    private volatile InputStream downloadInput;
    private boolean busy, enabled, committing, assetsCopied;
    private String state = "not_installed", error = "";
    private long downloaded, total;

    public LinuxRuntime(Context context, DevTools devTools, Runnable onChanged) {
        this.context = context.getApplicationContext(); this.devTools = devTools; this.onChanged = onChanged;
        home = new File(this.context.getFilesDir(), "linux"); rootfs = new File(home, ROOTFS);
        script = new File(home, "runtime.py"); manifestFile = new File(home, "manifest.json"); wrapperState = new File(home, "state.json");
        try {
            manifest = new JSONObject(readAsset("linux/manifest.json"));
            validateManifest(manifest);
        } catch (Exception e) { manifest = null; error = safeError(e); state = "error"; }
        enabled = this.context.getSharedPreferences(PREFS, 0).getBoolean(ENABLED, false);
        if (enabled && !installed()) {
            enabled = false;
            this.context.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, false).apply();
        }
        if (installed()) state = "ready";
        try { persistWrapperState(); } catch (IOException e) { state = "error"; error = safeError(e); }
    }

    public synchronized JSONObject status() {
        boolean installed = installed();
        long available = availableBytes();
        long archive = manifest == null ? 0 : manifest.optLong("compressedBytes");
        long required = manifest == null ? 0 : archive + manifest.optLong("maximumUncompressedBytes") + manifest.optLong("installationMarginBytes");
        return obj("supported", supported(), "installed", installed, "hasFiles", hasFiles(), "enabled", enabled && installed,
            "busy", busy, "state", installed && !busy && !state.equals("error") ? "ready" : state,
            "downloadedBytes", downloaded, "totalBytes", total == 0 ? archive : total,
            "downloadBytes", archive, "requiredFreeBytes", required, "availableBytes", available,
            "label", manifest == null ? "Linux" : manifest.optString("distribution", "Linux"), "error", error);
    }

    /** Starts a fresh staged installation. This method is intentionally the only download entrypoint. */
    public synchronized void install(File codexHome, File aliases) {
        if (busy) throw new IllegalStateException(t("Linux 설치가 이미 진행 중입니다."));
        if (!supported()) throw new IllegalStateException(t("이 기기에서는 Linux 런타임을 지원하지 않습니다."));
        if (installed()) { state = "ready"; changed(); return; }
        if (hasFiles()) throw new IllegalStateException(t("이전 Linux 설치 파일이 완전하지 않습니다. 삭제한 뒤 다시 설치해 주세요."));
        if (availableBytes() < requiredFreeBytes()) throw new IllegalStateException(t("Linux 런타임 설치에 필요한 저장 공간이 부족합니다."));
        busy = true; cancelled.set(false); error = ""; downloaded = 0; total = manifest.optLong("compressedBytes"); state = "downloading";
        changed();
        worker.execute(() -> installWorker(codexHome, aliases));
    }

    public void cancel() {
        Process current; HttpURLConnection connection; InputStream input;
        synchronized (this) {
            if (!busy || committing) return;
            cancelled.set(true); state = "cancelled";
            current = toolProcess; connection = downloadConnection; input = downloadInput;
        }
        try { if (input != null) input.close(); } catch (IOException ignored) {}
        if (connection != null) connection.disconnect();
        if (current != null) current.destroy();
        changed();
    }

    public synchronized void setEnabled(boolean value) {
        if (busy) throw new IllegalStateException(t("Linux 설치가 끝난 뒤에 변경해 주세요."));
        if (value && !installed()) throw new IllegalStateException(t("먼저 Linux 런타임을 설치해 주세요."));
        boolean previous = enabled;
        enabled = value;
        try { persistWrapperState(); }
        catch (IOException e) { enabled = previous; throw new IllegalStateException(safeError(e), e); }
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, value).apply();
        if (installed()) state = "ready";
        changed();
    }

    /** Explicit UI removal only. It removes the isolated rootfs, never a workspace or Codex home. */
    public synchronized void remove() {
        if (busy) throw new IllegalStateException(t("Linux 설치가 진행 중일 때는 삭제할 수 없습니다."));
        try {
            deleteTree(rootfs.toPath()); cleanupStaging();
            enabled = false; state = "not_installed"; error = ""; downloaded = total = 0;
            context.getSharedPreferences(PREFS, 0).edit().putBoolean(ENABLED, false).apply();
            persistWrapperState();
        } catch (IOException e) { state = "error"; error = safeError(e); }
        changed();
    }

    /** Creates the host process that invokes PRoot; it never falls back to host tools. */
    public ProcessBuilder command(String command, File cwd, File codexHome, File aliases) {
        synchronized (this) {
            if (!installed() || !enabled) throw new IllegalStateException(t("Linux 런타임이 설치 또는 활성화되지 않았습니다."));
        }
        try {
            copyRuntimeAssets();
            File selected = cwd == null ? null : cwd.getCanonicalFile();
            if (selected == null || !selected.isDirectory()) throw new IOException(t("선택한 작업 폴더를 찾을 수 없습니다."));
            File proot = nativeFile("libproot.so"), loader = nativeFile("libproot_loader.so");
            File python = new File(devTools.prepare(), "bin/python3");
            List<String> argv = new ArrayList<>(List.of(python.getAbsolutePath(), script.getAbsolutePath(), "run",
                "--root", rootfs.getAbsolutePath(), "--workspace", selected.getAbsolutePath(),
                "--proot", proot.getAbsolutePath(), "--loader", loader.getAbsolutePath(), "--runtime-home", home.getAbsolutePath(), "--",
                "/bin/sh", "-lc", command == null ? "" : command));
            ProcessBuilder builder = new ProcessBuilder(argv).directory(selected).redirectErrorStream(true);
            devTools.configure(builder, codexHome, aliases);
            configureEnvironment(builder.environment());
            return builder;
        } catch (Exception e) { throw new IllegalStateException(safeError(e), e); }
    }

    /** Adds only wrapper-discovery variables to an existing host environment. */
    public synchronized void configureEnvironment(Map<String, String> environment) {
        try { copyRuntimeAssets(); }
        catch (IOException e) { throw new IllegalStateException(safeError(e), e); }
        environment.put("MC_LINUX_HOME", home.getAbsolutePath());
        environment.put("MC_LINUX_SCRIPT", script.getAbsolutePath());
        environment.put("MC_LINUX_PROOT", new File(nativeDir(), "libproot.so").getAbsolutePath());
        environment.put("MC_LINUX_LOADER", new File(nativeDir(), "libproot_loader.so").getAbsolutePath());
    }

    /** Lifecycle stop is cancellation only; removal remains an explicit user action. */
    public void stop() { cancel(); }
    @Override public void close() { cancel(); worker.shutdownNow(); }

    private void installWorker(File codexHome, File aliases) {
        File stage = null;
        try {
            cleanupStaging();
            copyRuntimeAssets();
            Files.createDirectories(home.toPath());
            stage = new File(home, "staging-" + UUID.randomUUID());
            if (!stage.mkdir()) throw new IOException("cannot create installation staging directory");
            File archive = new File(stage, "rootfs.tar.xz");
            download(archive);
            checkCancelled(); setState("verifying"); verifyArchive(archive);
            checkCancelled(); setState("extracting");
            File python = new File(devTools.prepare(), "bin/python3");
            runTool(List.of(python.getAbsolutePath(), script.getAbsolutePath(), "extract", "--archive", archive.getAbsolutePath(),
                "--destination", new File(stage, ROOTFS).getAbsolutePath(), "--manifest", manifestFile.getAbsolutePath(),
                "--cancel-file", new File(stage, "cancelled").getAbsolutePath()), codexHome, aliases, TimeUnit.MINUTES.toMillis(15));
            checkCancelled(); setState("checking");
            File smokeWorkspace = new File(stage, "workspace"); Files.createDirectories(smokeWorkspace.toPath());
            File proot = nativeFile("libproot.so"), loader = nativeFile("libproot_loader.so");
            runTool(List.of(python.getAbsolutePath(), script.getAbsolutePath(), "smoke", "--root", new File(stage, ROOTFS).getAbsolutePath(),
                "--workspace", smokeWorkspace.getAbsolutePath(), "--proot", proot.getAbsolutePath(), "--loader", loader.getAbsolutePath(), "--runtime-home", stage.getAbsolutePath()), codexHome, aliases, TimeUnit.SECONDS.toMillis(45));
            checkCancelled();
            Files.write(new File(stage, ROOTFS + "/" + READY).toPath(), manifest.optString("id").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
            synchronized (this) {
                checkCancelled(); committing = true;
                if (Files.exists(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Linux rootfs changed during installation");
                Files.move(new File(stage, ROOTFS).toPath(), rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE);
                state = "ready"; error = ""; downloaded = total;
            }
        } catch (Exception e) {
            synchronized (this) { state = cancelled.get() || "cancelled".equals(e.getMessage()) ? "cancelled" : "error"; error = state.equals("cancelled") ? "" : safeError(e); }
        } finally {
            toolProcess = null;
            if (stage != null) try { deleteTree(stage.toPath()); } catch (IOException ignored) {}
            synchronized (this) { busy = false; committing = false; }
            changed();
        }
    }

    private void download(File archive) throws Exception {
        URL url = new URL(manifest.getString("url"));
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; redirects <= 3; redirects++) {
                checkCancelled();
                if (!"https".equals(url.getProtocol())) throw new IOException("Linux runtime redirect was not HTTPS");
                connection = (HttpURLConnection) url.openConnection();
                downloadConnection = connection;
                connection.setConnectTimeout(20_000); connection.setReadTimeout(30_000); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location"); connection.disconnect(); connection = null;
                    if (location == null) throw new IOException("Linux runtime redirect was invalid");
                    url = new URL(url, location); continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("Linux runtime download was rejected");
                break;
            }
            if (connection == null || connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Linux runtime redirect limit exceeded");
            long length = connection.getContentLengthLong(), expected = manifest.getLong("compressedBytes");
            if (length != expected) throw new IOException("Linux runtime download size metadata did not match");
            synchronized (this) { total = length; }
            checkCancelled();
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(archive)) {
                downloadInput = input; long lastProgress = 0;
                byte[] block = new byte[128 * 1024]; int count;
                while ((count = input.read(block)) != -1) {
                    checkCancelled();
                    synchronized (this) {
                        if (downloaded + count > expected) throw new IOException("Linux runtime download exceeded its pinned size");
                        downloaded += count;
                    }
                    output.write(block, 0, count);
                    long now = System.nanoTime();
                    if (now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(200)) { lastProgress = now; changed(); }
                }
            }
            if (archive.length() != expected) throw new IOException("Linux runtime download was incomplete");
        } finally { downloadInput = null; downloadConnection = null; if (connection != null) connection.disconnect(); }
    }

    private void verifyArchive(File archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(new FileInputStream(archive), digest)) {
            byte[] block = new byte[128 * 1024];
            while (input.read(block) != -1) checkCancelled();
        }
        String actual = hex(digest.digest());
        if (!actual.equals(manifest.getString("sha256"))) throw new IOException("Linux runtime download integrity check failed");
    }

    private void runTool(List<String> argv, File codexHome, File aliases, long timeoutMillis) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(context.getCacheDir()).redirectErrorStream(true);
        devTools.configure(builder, codexHome, aliases); configureEnvironment(builder.environment());
        Process process = builder.start(); toolProcess = process;
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    synchronized (output) { if (output.length() < 4096) output.append(line).append('\n'); }
                    if (line.contains("\"event\":\"extracting\"")) changed();
                }
            } catch (IOException ignored) {}
        }, "linux-runtime-output");
        reader.setDaemon(true); reader.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() && !cancelled.get() && System.nanoTime() < deadline) {
            process.waitFor(200, TimeUnit.MILLISECONDS);
        }
        if (process.isAlive()) {
            process.destroy(); process.waitFor(3, TimeUnit.SECONDS);
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
        reader.join(1000);
        if (cancelled.get()) throw new IOException("cancelled");
        if (System.nanoTime() >= deadline) throw new IOException("Linux setup command timed out");
        String result; synchronized (output) { result = output.toString().strip(); }
        if (process.exitValue() != 0) throw new IOException(result.isEmpty() ? "Linux setup command failed" : result);
    }

    private synchronized void setState(String next) { state = next; changed(); }
    private void checkCancelled() throws IOException { if (cancelled.get()) throw new IOException("cancelled"); }
    private synchronized boolean installed() {
        Path marker = new File(rootfs, READY).toPath();
        try { return Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) <= 128 && manifest != null &&
            manifest.optString("id").equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)); }
        catch (IOException e) { return false; }
    }
    private boolean hasFiles() { return Files.exists(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS); }
    private boolean supported() {
        if (manifest == null || Build.VERSION.SDK_INT < 29) return false;
        boolean arm64 = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
        return arm64 && nativeFile("libproot.so").isFile() && nativeFile("libproot_loader.so").isFile();
    }
    private long requiredFreeBytes() { return manifest == null ? Long.MAX_VALUE : manifest.optLong("compressedBytes") + manifest.optLong("maximumUncompressedBytes") + manifest.optLong("installationMarginBytes"); }
    private long availableBytes() { try { return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes(); } catch (Exception e) { return 0; } }
    private File nativeDir() {
        String path = context.getApplicationInfo().nativeLibraryDir;
        return new File(path == null ? "/missing-native" : path);
    }
    private File nativeFile(String name) { return new File(nativeDir(), name); }
    private void changed() { if (onChanged != null) onChanged.run(); }

    private void persistWrapperState() throws IOException {
        Files.createDirectories(home.toPath());
        File temporary = new File(home, "state.json.new");
        String value = obj("enabled", enabled && installed(), "rootfs", rootfs.getAbsolutePath()).toString();
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temporary.toPath(), wrapperState.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private synchronized void copyRuntimeAssets() throws IOException {
        if (assetsCopied) return;
        Files.createDirectories(home.toPath()); copyAsset("linux/runtime.py", script); copyAsset("linux/manifest.json", manifestFile);
        assetsCopied = true;
    }
    private void copyAsset(String name, File target) throws IOException {
        File temporary = new File(home, target.getName() + "." + UUID.randomUUID() + ".new");
        try (InputStream input = context.getAssets().open(name); OutputStream output = new FileOutputStream(temporary)) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private String readAsset(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void cleanupStaging() throws IOException {
        if (!home.isDirectory()) return;
        File[] children = home.listFiles(); if (children == null) return;
        for (File child : children) if (child.getName().startsWith("staging-")) deleteTree(child.toPath());
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> entries = Files.walk(path)) {
            List<Path> all = entries.collect(Collectors.toList());
            // A valid Linux rootfs contains read-only directories. Restore owner
            // write permission before deleting children; Files.walk never follows
            // its guest symlinks.
            for (Path entry : all) if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                entry.toFile().setWritable(true, true); entry.toFile().setExecutable(true, true);
            }
            for (Path entry : all.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(entry);
        }
    }
    private static void validateManifest(JSONObject value) throws IOException {
        if (value.optInt("schema") != 1 || !value.optString("id").matches("[A-Za-z0-9._-]+") ||
            !value.optString("url").startsWith("https://") || !value.optString("sha256").matches("[0-9a-f]{64}") ||
            value.optLong("compressedBytes") <= 0 || !value.optString("archiveTopLevel").matches("[A-Za-z0-9._-]+") ||
            value.optInt("maximumEntries") < 1 || value.optLong("maximumUncompressedBytes") < 1) throw new IOException("invalid Linux runtime manifest");
    }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b)); return value.toString(); }
    private static String safeError(Exception e) {
        String value = e.getMessage(); if (value == null || value.isBlank()) value = e.getClass().getSimpleName();
        value = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("https?://\\S+", "download source");
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
