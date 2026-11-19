package dev.mobilecodex.app;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Installs the official Linux ARM64 payload separately from the APK. Only the
 * app-server launch gets account/workspace mounts. Update probes use an empty home. */
public final class LinuxCodexRuntime {
    private static final String REGISTRY = "https://registry.npmjs.org/@openai/codex/";
    private static final String TARGET = "package/vendor/aarch64-unknown-linux-musl/";
    private static final String VERSION = "[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}";
    private static final int MIB = 1024 * 1024;
    private final File store, root, nativeDir;
    private final BooleanSupplier enabled;
    private static final Set<String> cleaned = new HashSet<>();
    private boolean checking;
    private long nextCheck;
    private volatile String rejected = "";

    public LinuxCodexRuntime(File files, File nativeDir, BooleanSupplier enabled) {
        this.store = new File(files, "codex-linux-releases");
        this.root = new File(files, "linux/rootfs");
        this.nativeDir = nativeDir;
        this.enabled = enabled;
    }

    public synchronized void check() {
        long now = System.nanoTime();
        if (checking || (nextCheck != 0 && now - nextCheck < 0) || !enabled.getAsBoolean()) return;
        checking = true;
        Thread worker = new Thread(() -> {
            boolean success = false;
            try { update(); success = true; }
            catch (Exception e) { android.util.Log.w("CodexUpdate", "Linux CLI update deferred: " + e.getClass().getSimpleName()); }
            finally {
                synchronized (this) {
                    nextCheck = System.nanoTime() + (success ? TimeUnit.HOURS.toNanos(6) : TimeUnit.MINUTES.toNanos(5));
                    checking = false;
                }
            }
        }, "codex-linux-update");
        worker.setDaemon(true);
        worker.start();
    }

    private File binary(File release) { return new File(release, TARGET + "bin/codex"); }

    public File selected() {
        if (!enabled.getAsBoolean()) return null;
        File marker = new File(store, "current");
        try {
            if (!marker.isFile() || marker.length() > 32) return null;
            String version = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII);
            if (!version.matches(VERSION) || version.equals(rejected)) return null;
            File release = new File(store, version);
            return binary(release).isFile() ? release : null;
        } catch (IOException e) { return null; }
    }

    public void reject(File release) { if (release != null) rejected = release.getName(); }

    private static InputStream open(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        try {
            if (c.getResponseCode() != 200 || c.getContentLengthLong() > limit)
                throw new IOException("Invalid package response");
            return new FilterInputStream(c.getInputStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { c.disconnect(); } }
            };
        } catch (IOException e) { c.disconnect(); throw e; }
    }

    private static void copy(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] block = new byte[65536];
        int total = 0, n;
        while ((n = in.read(block)) != -1) {
            if (n > limit - total) throw new IOException("Package response too large");
            out.write(block, 0, n); total += n;
        }
    }

    private static JSONObject metadata(String suffix) throws Exception {
        try (InputStream in = open(REGISTRY + suffix, MIB); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, MIB);
            return new JSONObject(out.toString("UTF-8"));
        }
    }

    private void update() throws Exception {
        Files.createDirectories(store.toPath());
        cleanColdStore();
        JSONObject manifest = metadata("latest");
        String version = manifest.getString("version");
        if (!version.matches(VERSION) || !("npm:@openai/codex@" + version + "-linux-arm64").equals(
                manifest.getJSONObject("optionalDependencies").getString("@openai/codex-linux-arm64")))
            throw new IOException("Unknown Codex distribution");
        File release = new File(store, version);
        if (!binary(release).isFile()) stage(release, version);
        if (!enabled.getAsBoolean()) return;
        File pointer = File.createTempFile(".current-", ".tmp", store);
        try {
            Files.write(pointer.toPath(), version.getBytes(StandardCharsets.US_ASCII));
            Files.move(pointer.toPath(), new File(store, "current").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pointer.toPath()); }
    }

    private void stage(File release, String version) throws Exception {
        String platformVersion = version + "-linux-arm64";
        JSONObject pkg = metadata(platformVersion);
        JSONObject dist = pkg.getJSONObject("dist");
        String url = REGISTRY + "-/codex-" + platformVersion + ".tgz";
        String integrity = dist.getString("integrity");
        if (!platformVersion.equals(pkg.getString("version")) || !url.equals(dist.getString("tarball")) ||
                !integrity.startsWith("sha512-") || dist.getLong("unpackedSize") > 1024L * MIB)
            throw new IOException("Invalid Codex package metadata");
        File tmp = Files.createTempDirectory(store.toPath(), ".staging-").toFile();
        try {
            File archive = new File(tmp, "codex.tgz");
            MessageDigest hash = MessageDigest.getInstance("SHA-512");
            try (InputStream in = open(url, 300 * MIB); OutputStream out = new java.security.DigestOutputStream(new FileOutputStream(archive), hash)) {
                copy(in, out, 300 * MIB);
            }
            if (!MessageDigest.isEqual(Base64.getDecoder().decode(integrity.substring(7)), hash.digest()))
                throw new IOException("Codex integrity mismatch");
            runProbe(guest(tmp, Collections.singletonList(tmp), Arrays.asList("/usr/bin/tar", "-xzf", archive.getAbsolutePath(), "--no-same-owner")), null);
            Files.delete(archive.toPath());
            String executable = binary(tmp).getAbsolutePath();
            runProbe(guest(tmp, Collections.singletonList(tmp), Arrays.asList(executable, "--version")), "codex-cli " + version);
            // Validate the actual RPC handshake using a disposable account home.
            ProcessBuilder smoke = guest(tmp, Collections.singletonList(tmp), Arrays.asList(executable, "app-server", "--listen", "stdio://"));
            File probeHome = new File(tmp, ".probe");
            Files.createDirectories(probeHome.toPath());
            smoke.environment().put("CODEX_HOME", probeHome.toString());
            probeServer(smoke);
            delete(probeHome.toPath());
            Files.move(tmp.toPath(), release.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally { delete(tmp.toPath()); }
    }

    /** Old app-server processes are gone on a cold start. Never unlink their
     * helpers or replace a running version during a live upgrade. */
    private void cleanColdStore() throws IOException {
        synchronized (cleaned) {
            if (!cleaned.add(store.getAbsolutePath())) return;
        }
        File current = selected();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(store.toPath())) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".staging-") || name.startsWith(".current-") ||
                        (current != null && name.matches(VERSION) && !current.equals(entry.toFile()))) delete(entry);
            }
        }
    }

    private static void probeServer(ProcessBuilder builder) throws Exception {
        Process child = builder.redirectError(new File("/dev/null")).start();
        java.util.concurrent.FutureTask<Boolean> response = new java.util.concurrent.FutureTask<>(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                for (int n = 0; n < MIB; n++) {
                    int ch = reader.read();
                    if (ch < 0) return false;
                    if (ch != '\n') { line.append((char)ch); continue; }
                    JSONObject message = new JSONObject(line.toString());
                    if (message.optInt("id", -1) == 1) return message.optJSONObject("result") != null;
                    line.setLength(0);
                }
                return false;
            }
        });
        Thread reader = new Thread(response, "codex-version-probe");
        reader.setDaemon(true); reader.start();
        try (OutputStream input = child.getOutputStream()) {
            input.write("{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"mobile_probe\",\"version\":\"1\"}}}\n".getBytes(StandardCharsets.UTF_8));
            input.flush();
            if (!response.get(45, TimeUnit.SECONDS)) throw new IOException("Codex initialization failed");
        } finally {
            child.destroy();
            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
            response.cancel(true);
        }
    }

    private static void runProbe(ProcessBuilder builder, String expected) throws Exception {
        File output = File.createTempFile(".probe-", ".log", builder.directory());
        Process child = null;
        try {
            child = builder.redirectOutput(output).redirectError(new File("/dev/null")).start();
            child.getOutputStream().close();
            if (!child.waitFor(90, TimeUnit.SECONDS) || child.exitValue() != 0) throw new IOException("Codex guest probe failed");
            if (expected != null && (output.length() > MIB || !new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8).contains(expected)))
                throw new IOException("Unexpected Codex probe response");
        } finally { if (child != null && child.isAlive()) child.destroyForcibly(); Files.deleteIfExists(output.toPath()); }
    }

    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) { for (Path child : entries) delete(child); }
        }
        Files.deleteIfExists(path);
    }

    private ProcessBuilder guest(File cwd, Collection<File> mounts, List<String> command) throws IOException {
        File tmp = new File(store, ".proot");
        Files.createDirectories(tmp.toPath());
        List<String> argv = new ArrayList<>(Arrays.asList(new File(nativeDir, "libproot.so").toString(),
                "--kill-on-exit", "-0", "-r", root.toString(), "-b", "/dev", "-b", "/proc"));
        for (File mount : new LinkedHashSet<>(mounts)) {
            String path = mount.getAbsolutePath();
            if (path.contains(":")) throw new IOException("Unsupported Linux mount path");
            Collections.addAll(argv, "-b", path + ":" + path);
        }
        Collections.addAll(argv, "-w", cwd.toString());
        argv.addAll(command);
        ProcessBuilder b = new ProcessBuilder(argv).directory(cwd);
        Map<String,String> env = b.environment();
        env.clear();
        env.put("HOME", "/tmp"); env.put("TMPDIR", "/tmp"); env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin"); env.put("SHELL", "/bin/sh");
        env.put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt");
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").toString());
        env.put("PROOT_TMP_DIR", tmp.toString()); env.put("PROOT_DONT_POLLUTE_ROOTFS", "1");
        return b;
    }

    public ProcessBuilder server(File release, File home, File cwd, List<String> args, File... assets) throws IOException {
        List<File> mounts = new ArrayList<>(Arrays.asList(release, home, cwd));
        for (File asset : assets) if (asset.isDirectory()) mounts.add(asset);
        String executable = binary(release).getAbsolutePath();
        List<String> cmd = new ArrayList<>(); cmd.add(executable); cmd.addAll(args);
        ProcessBuilder b = guest(cwd, mounts, cmd);
        b.environment().put("HOME", home.toString()); b.environment().put("CODEX_HOME", home.toString());
        b.environment().put("CODEX_SELF_EXE", executable);
        b.environment().put("PATH", binary(release).getParent() + ":" + b.environment().get("PATH"));
        return b;
    }
}
