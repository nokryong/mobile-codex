package dev.mobilecodex.app.core;

import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicLong;
import static dev.mobilecodex.app.core.Json.obj;

/** Content-free counters only. No request IDs, methods, buffers or paths. */
public final class StartupDiagnostics {
    private final long began = System.nanoTime();
    private volatile String stage = "before_spawn";
    private volatile long spawned = -1, write = -1, firstByte = -1, response = -1;
    private final AtomicLong bytes = new AtomicLong(), lines = new AtomicLong(), invalid = new AtomicLong(), unmatched = new AtomicLong(), stderr = new AtomicLong();
    public long elapsed() { return Math.max(0, Math.min(600000, (System.nanoTime() - began) / 1000000)); }
    public void spawned() { spawned = elapsed(); stage = "spawned"; }
    public void initializing() { stage = "initialize_sent"; }
    public void initialized() { response = elapsed(); stage = "initialized"; }
    public void ready() { stage = "ready"; }
    public String stage() { return stage; }
    public void written() { if (write < 0) write = elapsed(); }
    public synchronized void bytes(int count) { if (count > 0) { if (firstByte < 0) firstByte = elapsed(); add(bytes, count, 100000000); } }
    public void line() { add(lines, 1, 1000000); }
    public void invalid() { add(invalid, 1, 1000000); }
    public void unmatched() { add(unmatched, 1, 1000000); }
    public void stderr(int count) { add(stderr, count, 100000000); }
    private static void add(AtomicLong counter, long count, long cap) { if (count > 0) counter.updateAndGet(value -> Math.min(cap, value + count)); }
    private static long rounded(long value) { return value < 0 ? -1 : value / 100 * 100; }
    public JSONObject snapshot(String code, String homeKind, boolean alive, int exitCode, String category) {
        return obj("errorCode", code, "stage", stage, "homeKind", homeKind,
            "elapsedMs", rounded(elapsed()), "spawnMs", rounded(spawned), "writeMs", rounded(write),
            "firstByteMs", rounded(firstByte), "responseMs", rounded(response), "processAlive", alive,
            "exitCode", Math.max(-1, Math.min(255, exitCode)), "stdoutBytes", bytes.get(), "stdoutLines", lines.get(),
            "invalidJson", invalid.get(), "unmatchedIds", unmatched.get(), "stderrBytes", stderr.get(), "stderrCategory", category);
    }
}
