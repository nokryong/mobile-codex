package dev.mobilecodex.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-private authenticated transport; Engine remains the grant/lifecycle authority. */
final class ProConsultMcp implements AutoCloseable {
    static final int MAX_FRAME_BYTES = 1536 * 1024;
    static final int MAX_PROMPT_CHARS = 50_000;
    static final int MAX_RESULT_CHARS = 200_000;
    static final long MAX_TIMEOUT_MS = 650_000;
    private static final int MAX_CLIENTS = 2;
    // Codex's MCP launcher clears the parent environment. Forward only the
    // prepared Python/Android bootstrap keys, never account/config credentials.
    static final List<String> RUNTIME_ENV_KEYS = List.of(
        "PYTHONHOME", "PYTHONUSERBASE", "PYTHONUTF8", "LD_LIBRARY_PATH", "LD_PRELOAD",
        "MC_PREFIX", "MC_NATIVE_DIR", "MC_PYTHON", "TMPDIR", "TMP", "TEMP",
        "HOME", "PATH", "SHELL", "LANG");

    interface Completion {
        void success(String text);
        void error(String message);
    }
    interface Handler {
        /** Must enqueue work on Engine.io; never perform synchronous UI/network work here. */
        void consult(String requestToken, String prompt, Completion reply);
        default void cancelled(String requestToken) { }
    }

    private final Handler handler;
    private final File script;
    private final long timeoutMillis;
    private final String secret;
    private final ServerSocket listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore slots = new Semaphore(MAX_CLIENTS);
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService network = Executors.newFixedThreadPool(MAX_CLIENTS * 2, runnable -> {
        Thread thread = new Thread(runnable, "pro-consult-network"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pro-consult-timeout"); thread.setDaemon(true); return thread;
    });

    ProConsultMcp(Context context, Handler handler) throws IOException {
        this(handler, installScript(context), MAX_TIMEOUT_MS);
    }

    /** Shorter timeout injection keeps transport regression tests fast. */
    ProConsultMcp(Handler handler, File script, long timeoutMillis) throws IOException {
        if (handler == null || script == null || !script.isAbsolute() || !script.isFile()
                || timeoutMillis <= 0 || timeoutMillis > MAX_TIMEOUT_MS)
            throw new IllegalArgumentException("Invalid Pro consultation bridge configuration.");
        this.handler = handler; this.script = script; this.timeoutMillis = timeoutMillis;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(64);
        for (byte part : random) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        secret = value.toString();
        listener = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread accept = new Thread(this::acceptClients, "pro-consult-listener");
        accept.setDaemon(true); accept.start();
    }

    private static File installScript(Context context) throws IOException {
        File folder = new File(context.getFilesDir(), "pro-consult");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not prepare the Pro consultation adapter.");
        File destination = new File(folder, "pro-consult-mcp.py");
        File temporary = File.createTempFile("adapter-", ".tmp", folder);
        try {
            try (InputStream input = context.getAssets().open("pro-consult-mcp.py")) {
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return destination;
    }

    /** CLI-only override: never changes CODEX_HOME/config.toml. Do not log this list. */
    List<String> configOverrides(String pythonAbsolutePath) {
        if (closed.get() || pythonAbsolutePath == null || !new File(pythonAbsolutePath).isAbsolute())
            throw new IllegalArgumentException("The Pro consultation adapter requires an absolute Python path.");
        JSONArray arguments = new JSONArray().put(script.getAbsolutePath()).put("--port").put(String.valueOf(listener.getLocalPort()));
        String config = "mcp_servers.mobile_codex_pro={command=" + quote(pythonAbsolutePath)
            + ",args=" + arguments.toString().replace("\\/", "/")
            + ",env_vars=" + new JSONArray(RUNTIME_ENV_KEYS)
            + ",env={MC_PRO_CONSULT_SECRET=" + quote(secret) + "},tool_timeout_sec=660}";
        return List.of("-c", config);
    }

    private static String quote(String value) { return JSONObject.quote(value).replace("\\/", "/"); }

    private void acceptClients() {
        while (!closed.get()) {
            Socket socket = null;
            boolean acquired = false;
            try {
                socket = listener.accept();
                if (closed.get() || !socket.getInetAddress().isLoopbackAddress() || !(acquired = slots.tryAcquire())) {
                    socket.close(); continue;
                }
                Client client = new Client(socket); clients.add(client);
                try { network.execute(() -> serve(client)); }
                catch (RuntimeException error) { client.close(); clients.remove(client); slots.release(); }
            } catch (IOException error) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                if (acquired) slots.release();
                if (!closed.get()) close();
            }
        }
    }

    private void serve(Client client) {
        java.util.concurrent.ScheduledFuture<?> deadline = null;
        try {
            JSONObject request = object(readLine(client.socket, client.input, 10_000));
            String id = request.optString("callId");
            if (id.matches("[a-f0-9]{32}")) client.callId = id;
            if (!authenticated(request)) { client.write(false, "Local consultation authentication failed."); return; }
            Object rawToken = request.opt("requestToken"), rawPrompt = request.opt("prompt"), rawId = request.opt("callId");
            if (!"consult".equals(request.optString("method")) || !(rawId instanceof String)
                    || !((String) rawId).matches("[a-f0-9]{32}") || !(rawToken instanceof String)
                    || ((String) rawToken).isEmpty() || ((String) rawToken).length() > 512
                    || !(rawPrompt instanceof String) || ((String) rawPrompt).isEmpty()
                    || ((String) rawPrompt).codePointCount(0, ((String) rawPrompt).length()) > MAX_PROMPT_CHARS) {
                client.write(false, "Invalid local consultation request."); return;
            }
            client.callId = (String) rawId; client.token = (String) rawToken;
            deadline = timer.schedule(() -> {
                client.cancel("Pro consultation timed out. It was not resent.");
                // Closing also releases a blocked writer/reader even if the peer stops reading.
                client.close();
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            synchronized (client.dispatchLock) {
                if (!client.terminal.get() && !closed.get()) {
                    client.dispatched = true;
                    try { handler.consult(client.token, (String) rawPrompt, client); }
                    catch (RuntimeException error) { client.error("The app could not start Pro consultation."); }
                } else client.cancel("The app closed the Pro consultation bridge.");
            }
            network.execute(() -> monitorCancellation(client));
            Reply result = client.reply.get(timeoutMillis, TimeUnit.MILLISECONDS);
            client.write(result.success, result.text);
        } catch (Exception error) {
            // Do not log raw exceptions: JSON/transport errors can contain secrets or prompts.
            client.cancel("The local Pro consultation connection ended.");
        } finally {
            if (deadline != null) deadline.cancel(false);
            client.close(); clients.remove(client); slots.release();
        }
    }

    private void monitorCancellation(Client client) {
        try {
            JSONObject message = object(readLine(client.socket, client.input, timeoutMillis));
            if (authenticated(message) && "cancel".equals(message.optString("method"))
                    && client.callId.equals(message.optString("callId")) && client.token.equals(message.optString("requestToken")))
                client.cancel("Pro consultation was cancelled. It was not resent.");
            else client.cancel("Invalid local consultation control message.");
        } catch (Exception error) {
            client.cancel("The local Pro consultation connection ended.");
        }
    }

    private boolean authenticated(JSONObject request) {
        Object value = request.opt("secret");
        return value instanceof String && MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),
            ((String) value).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject object(String text) throws Exception {
        JSONTokener parser = new JSONTokener(text);
        Object value = parser.nextValue();
        if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("Expected one JSON object.");
        return (JSONObject) value;
    }

    private static String readLine(Socket socket, InputStream input, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Local bridge read timed out.");
            socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining))));
            int next = input.read();
            if (next == -1) throw new IOException("Local bridge peer disconnected.");
            if (next == '\n') break;
            if (line.size() >= MAX_FRAME_BYTES) throw new IOException("Local bridge message is too large.");
            line.write(next);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();
        } catch (CharacterCodingException error) { throw new IOException("Invalid UTF-8 local bridge message."); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.close(); } catch (IOException ignored) { }
        for (Client client : new ArrayList<>(clients)) {
            client.cancel("The app closed the Pro consultation bridge."); client.close();
        }
        timer.shutdownNow(); network.shutdownNow();
    }

    private static final class Reply {
        final boolean success; final String text;
        Reply(boolean success, String text) { this.success = success; this.text = text; }
    }
    private final class Client implements Completion {
        final Socket socket; final InputStream input;
        final AtomicBoolean terminal = new AtomicBoolean();
        final CompletableFuture<Reply> reply = new CompletableFuture<>();
        final Object dispatchLock = new Object();
        boolean dispatched;
        volatile String callId = "", token = "";
        Client(Socket socket) throws IOException { this.socket = socket; input = new BufferedInputStream(socket.getInputStream()); }
        @Override public void success(String text) { finish(true, text); }
        @Override public void error(String message) { finish(false, message); }
        private void finish(boolean success, String text) {
            if (text == null || text.codePointCount(0, text.length()) > MAX_RESULT_CHARS) {
                success = false; text = "Pro consultation returned an invalid or oversized response.";
            }
            if (terminal.compareAndSet(false, true)) reply.complete(new Reply(success, text));
        }
        void cancel(String message) {
            synchronized (dispatchLock) {
                if (!terminal.compareAndSet(false, true)) return;
                reply.complete(new Reply(false, message));
                // A shutdown racing dispatch must never create a new operation after cancellation.
                if (dispatched) try { handler.cancelled(token); } catch (RuntimeException ignored) { }
            }
        }
        void write(boolean success, String text) throws Exception {
            byte[] bytes = new JSONObject().put("callId", callId).put("success", success).put("text", text)
                .toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FRAME_BYTES) throw new IOException("Local bridge response is too large.");
            socket.getOutputStream().write(bytes); socket.getOutputStream().write('\n'); socket.getOutputStream().flush();
        }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
