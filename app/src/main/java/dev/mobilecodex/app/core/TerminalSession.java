package dev.mobilecodex.app.core;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.*;
import static dev.mobilecodex.app.core.Texts.t;

/** One interactive process, retained independently of the Activity and dialogs. */
public final class TerminalSession {
    public interface Events { void event(String name, JSONObject data); }
    private final Events events;
    private static final int MAX_PENDING_BYTES = 65536;
    private final ArrayDeque<byte[]> input = new ArrayDeque<>();
    private int pendingBytes;
    private Process process;
    private String id = "";
    private long revision;
    private boolean stopping;
    private Integer exitCode;
    private final StringBuilder output = new StringBuilder();

    public TerminalSession(Events events) { this.events = events; }
    public synchronized boolean busy() { return process != null; }
    public synchronized JSONObject snapshot() {
        return obj("id", id, "revision", revision, "running", busy(), "stopping", stopping,
            "output", output.toString(), "exitCode", exitCode == null ? JSONObject.NULL : exitCode);
    }
    public synchronized JSONObject start(ProcessBuilder builder) throws IOException {
        if (busy()) throw new IOException(t("터미널 명령이 실행 중입니다."));
        Process launched = builder.redirectErrorStream(true).start();
        process = launched; id = UUID.randomUUID().toString(); stopping = false; exitCode = null;
        input.clear(); pendingBytes = 0;
        output.setLength(0); revision++;
        events.event("terminal.state", snapshot());
        Thread reader = new Thread(() -> read(launched), "mobile-terminal");
        reader.setDaemon(true); reader.start();
        Thread writer = new Thread(() -> write(launched), "mobile-terminal-input");
        writer.setDaemon(true); writer.start();
        return snapshot();
    }
    public synchronized JSONObject input(String expectedId, String text) throws IOException {
        if (process == null || !process.isAlive() || stopping || !id.equals(expectedId))
            throw new IOException(t("입력을 받을 터미널이 종료되거나 변경되었습니다."));
        byte[] bytes = (text.replaceAll("[\\r\\n]+$", "") + "\n").getBytes(StandardCharsets.UTF_8);
        if (text.length() > 16384 || text.getBytes(StandardCharsets.UTF_8).length > 16384 || text.indexOf('\0') >= 0)
            throw new IOException(t("터미널 입력이 너무 길거나 올바르지 않습니다."));
        // Includes the in-flight write. Acceptance means queued, not consumed by the CLI.
        if (pendingBytes + bytes.length > MAX_PENDING_BYTES)
            return obj("ok", false, "reason", "input_queue_full", "id", id);
        input.addLast(bytes); pendingBytes += bytes.length; notifyAll();
        return obj("ok", true, "queued", true, "id", id);
    }
    private void write(Process target) {
        try {
            while (true) {
                byte[] bytes;
                synchronized (this) {
                    while (process == target && !stopping && input.isEmpty()) wait();
                    if (process != target || stopping) return;
                    bytes = input.removeFirst();
                }
                target.getOutputStream().write(bytes);
                target.getOutputStream().flush();
                synchronized (this) { if (process == target) pendingBytes -= bytes.length; }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException failure) {
            synchronized (this) {
                if (process == target && !stopping)
                    events.event("terminal.inputError", obj("id", id, "reason", "input_closed"));
            }
        }
    }
    public void stop(String expectedId) throws IOException {
        Process target;
        synchronized (this) {
            if (process == null) return;
            if (expectedId != null && !id.equals(expectedId))
                throw new IOException(t("입력을 받을 터미널이 종료되거나 변경되었습니다."));
            if (stopping) return;
            target = process; stopping = true; revision++;
            input.clear(); notifyAll();
            events.event("terminal.state", snapshot());
        }
        // The PTY supervisor forwards SIGTERM and reaps the entire process group.
        // Neither pipe closure nor process waiting may occupy the controller queue.
        Thread term = new Thread(target::destroy, "mobile-terminal-term");
        term.setDaemon(true); term.start();
        Thread deadline = new Thread(() -> {
            try { if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "mobile-terminal-deadline");
        deadline.setDaemon(true); deadline.start();
    }
    private void read(Process target) {
        int code = -1;
        try (Reader reader = new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8)) {
            char[] block = new char[2048]; int count;
            while ((count = reader.read(block)) != -1) synchronized (this) {
                if (process != target) return;
                String text = new String(block, 0, count);
                output.append(text);
                if (output.length() > 1_000_000) output.delete(0, output.length() - 1_000_000);
                events.event("terminal.output", obj("id", id, "revision", ++revision, "text", text));
            }
            code = target.waitFor();
        } catch (IOException failure) {
            synchronized (this) { if (process == target) output.append('\n').append(t("터미널 연결이 종료되었습니다.")); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally {
            if (target.isAlive()) target.destroyForcibly();
            try { target.getOutputStream().close(); } catch (IOException ignored) { }
            try { target.getErrorStream().close(); } catch (IOException ignored) { }
            synchronized (this) {
                if (process == target) {
                    process = null; stopping = false; exitCode = code; revision++;
                    input.clear(); pendingBytes = 0; notifyAll();
                    events.event("terminal.state", snapshot());
                }
            }
        }
    }
}
