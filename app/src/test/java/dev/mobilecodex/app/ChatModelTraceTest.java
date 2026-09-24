package dev.mobilecodex.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatModelTraceTest {
    @Test public void freezesFailureBeforeRecoveryAndKeepsOriginalOperation() throws Exception {
        ChatModelTrace trace = new ChatModelTrace();
        trace.begin(7, 2, "Pro", new JSONObject().put("buildSha", "test-sha"));
        JSONObject snapshot = new JSONObject().put("level", "X-High");
        trace.record("wait-value-change", 2, 100, snapshot, null);
        trace.finish(3, 5000, "timeout", "operation-watchdog");
        snapshot.put("level", "High");
        trace.begin(8, 3, "High", new JSONObject());
        trace.record("verify-value", 3, 10, snapshot, null);
        trace.finish(3, 20, "", "");
        JSONObject frozen = trace.lastFailure();
        assertEquals(7, frozen.getLong("operationId"));
        assertEquals(2, frozen.getLong("startSessionEpoch"));
        assertEquals(3, frozen.getLong("endSessionEpoch"));
        assertEquals("X-High", frozen.getJSONObject("lastSnapshotBeforeFailure").getString("level"));
        assertEquals("wait-value-change", frozen.getString("failedAtStage"));
        assertEquals("operation-watchdog", frozen.getString("timeoutSource"));
        frozen.put("target", "mutated");
        assertEquals("Pro", trace.lastFailure().getString("target"));
    }
    @Test public void readFailureDoesNotEraseSelectionFailureAndCanRestore() throws Exception {
        ChatModelTrace trace = new ChatModelTrace();
        trace.begin(1, 1, "Pro", new JSONObject());
        trace.finish(1, 20, "selection failed", "");
        trace.begin(2, 1, "", new JSONObject());
        trace.finish(1, 30, "reading failed", "");
        ChatModelTrace restored = new ChatModelTrace();
        restored.restoreFailure(trace.lastFailure().toString());
        assertEquals("Pro", restored.lastFailure().getString("target"));
        assertEquals("selection failed", restored.lastFailure().getString("failure"));
    }
    @Test public void eventsDeduplicateAndResetOnNextOperation() throws Exception {
        ChatModelTrace trace = new ChatModelTrace();
        trace.begin(1, 1, "Pro", new JSONObject());
        JSONObject snapshot = new JSONObject().put("level", "X-High");
        trace.record("wait-value-change", 1, 80, snapshot, null);
        trace.record("wait-value-change", 1, 160, snapshot, null);
        assertEquals(1, trace.events().length());
        snapshot.put("level", "Pro");
        trace.record("wait-value-change", 1, 240, snapshot, null);
        assertEquals(2, trace.events().length());
        trace.begin(2, 1, "High", new JSONObject());
        assertEquals(0, trace.events().length());
    }
}
