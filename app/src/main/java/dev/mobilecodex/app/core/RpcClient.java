package dev.mobilecodex.app.core;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bidirectional JSONL JSON-RPC, including server-initiated approval/tool calls. */
public final class RpcClient implements AutoCloseable {
    public interface Listener {
        void notification(String method, JSONObject params);
        void request(Object id, String method, JSONObject params);
        void disconnected(Throwable error);
    }
    private final BufferedReader input;
    private final Writer output;
    private final Listener listener;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Object transportLock = new Object();
    private volatile boolean closed;
    private volatile IOException closeError = new IOException("Codex 연결이 종료되었습니다.");

    public RpcClient(InputStream input, OutputStream output, Listener listener) {
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        this.listener = listener;
    }
    public void start() {
        Thread reader = new Thread(this::readLoop, "codex-json-rpc");
        reader.setDaemon(true);
        reader.start();
    }
    public CompletableFuture<JSONObject> request(String method, JSONObject params) {
        long id = ids.getAndIncrement();
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        String key = String.valueOf(id);
        synchronized (transportLock) {
            if (closed) { result.completeExceptionally(closedException()); return result; }
            pending.put(key, result);
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = timer.schedule(() -> {
                CompletableFuture<JSONObject> value = pending.remove(key);
                if (value != null) value.completeExceptionally(new TimeoutException(method + " 응답 시간 초과"));
            }, 60, TimeUnit.SECONDS);
        } catch (RejectedExecutionException closedTimer) {
            pending.remove(key); result.completeExceptionally(closedException()); return result;
        }
        result.whenComplete((v, e) -> timeout.cancel(false));
        try { send(Json.obj("id", id, "method", method, "params", params)); }
        catch (IOException e) {
            pending.remove(key);
            result.completeExceptionally(e);
        }
        return result;
    }
    public void notify(String method, JSONObject params) throws IOException {
        send(Json.obj("method", method, "params", params));
    }
    public void respond(Object id, JSONObject result) throws IOException {
        send(Json.obj("id", id, "result", result));
    }
    public void reject(Object id, String message) throws IOException {
        send(Json.obj("id", id, "error", Json.obj("code", -32601, "message", message)));
    }
    private void send(JSONObject value) throws IOException {
        IOException failure = null;
        synchronized (transportLock) {
            if (closed) throw closedException();
            try { output.write(value.toString()); output.write('\n'); output.flush(); }
            catch (IOException e) { failure = new IOException("Codex 통신이 끊겼습니다.", e); }
        }
        if (failure != null) { disconnect(failure); throw failure; }
    }
    private void readLoop() {
        Throwable cause = new EOFException("Codex 실행이 종료되었습니다.");
        try {
            while (!closed) {
                String line = limitedLine();
                if (line == null) break;
                if (!line.isBlank()) {
                    JSONObject message;
                    // Ignore unframed output, but do not hide failures in protocol handling.
                    try { message = Json.parse(line); }
                    catch (RuntimeException invalidJson) { continue; }
                    accept(message);
                }
            }
        } catch (Exception e) { cause = e; }
        disconnect(safeDisconnectCause(cause));
    }
    private String limitedLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 8 * 1024 * 1024) throw new IOException("Codex 응답이 8 MiB 제한을 초과했습니다.");
            line.append((char) ch);
        }
        return line.length() == 0 ? null : line.toString();
    }
    public void accept(JSONObject msg) {
        String method = msg.optString("method", "");
        JSONObject params = msg.optJSONObject("params");
        if (params == null) params = new JSONObject();
        Object id = msg.opt("id");
        if (!method.isEmpty()) {
            if (id != null && id != JSONObject.NULL) listener.request(id, method, params);
            else listener.notification(method, params);
            return;
        }
        CompletableFuture<JSONObject> target = pending.remove(String.valueOf(id));
        if (target == null) return;
        JSONObject error = msg.optJSONObject("error");
        if (error != null) target.completeExceptionally(new IOException(error.optString("message", "Codex 요청 실패")));
        else {
            JSONObject result = msg.optJSONObject("result");
            target.complete(result == null ? new JSONObject() : result);
        }
    }
    public boolean isClosed() { return closed; }
    private IOException closedException() { return new IOException(closeError.getMessage(), closeError.getCause()); }
    private static IOException safeDisconnectCause(Throwable error) {
        if (error instanceof EOFException) return new IOException("Codex 실행이 종료되어 연결이 끊겼습니다.");
        String message = error == null ? "Codex 연결이 종료되었습니다." : error.getMessage();
        if (message != null && message.contains("8 MiB")) return new IOException(message);
        return new IOException("Codex 통신이 끊겼습니다.");
    }
    private void disconnect(IOException error) {
        if (!closeInternal(error)) return;
        listener.disconnected(error);
    }
    private boolean closeInternal(IOException error) {
        synchronized (transportLock) {
            if (closed) return false;
            closed = true; closeError = error;
        }
        timer.shutdownNow();
        pending.values().forEach(f -> f.completeExceptionally(closedException()));
        pending.clear();
        // Closing a BufferedReader from another thread can wait on a blocked read.
        // The owner destroys its Process, which closes the underlying pipe.
        try { output.close(); } catch (IOException ignored) {}
        return true;
    }
    @Override public void close() {
        closeInternal(new IOException("Codex 연결이 종료되었습니다."));
    }
}
