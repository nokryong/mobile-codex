package dev.mobilecodex.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, detached settings-only evidence. Times are supplied from a monotonic clock. */
final class ChatModelTrace {
    private JSONObject operation = new JSONObject();
    private JSONObject snapshot = new JSONObject();
    private JSONObject failure = new JSONObject();
    private JSONArray events = new JSONArray();
    private String stage = "idle";
    private String previousSignature = "";

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); } catch (Exception ignored) { return new JSONObject(); }
    }
    void restoreFailure(String saved) {
        try { failure = new JSONObject(saved); } catch (Exception ignored) { failure = new JSONObject(); }
    }
    void begin(long id, long epoch, String target, JSONObject environment) {
        operation = copy(environment); snapshot = new JSONObject(); events = new JSONArray();
        stage = "locate-trigger"; previousSignature = "";
        try {
            operation.put("operationId", id); operation.put("startSessionEpoch", epoch);
            operation.put("target", target); operation.put("startStage", stage);
        } catch (Exception ignored) {}
    }
    void record(String nextStage, long epoch, long elapsed, JSONObject state, JSONObject input) {
        stage = nextStage;
        if (state != null) snapshot = copy(state);
        try {
            JSONObject entry = new JSONObject();
            entry.put("stage", stage); entry.put("sessionEpoch", epoch);
            entry.put("operationId", operation.optLong("operationId"));
            if (state != null) entry.put("snapshot", copy(state));
            if (input != null) entry.put("input", copy(input));
            String signature = entry.toString();
            if (signature.equals(previousSignature)) return;
            previousSignature = signature;
            entry.put("elapsedMs", elapsed);
            events.put(entry);
            if (events.length() > 48) events.remove(0);
        } catch (Exception ignored) {}
    }
    JSONObject finish(long epoch, long elapsed, String error, String timeoutSource) {
        JSONObject result = copy(operation);
        try {
            result.put("endSessionEpoch", epoch); result.put("elapsedMs", elapsed);
            result.put("failure", error); result.put("failedAtStage", error.isEmpty() ? "" : stage);
            result.put("timeoutSource", timeoutSource);
            result.put("lastSnapshotBeforeFailure", error.isEmpty() ? JSONObject.NULL : copy(snapshot));
            result.put("finalSnapshot", copy(snapshot)); result.put("events", new JSONArray(events.toString()));
        } catch (Exception ignored) {}
        if (!error.isEmpty() && !operation.optString("target").isEmpty()) failure = copy(result);
        return result;
    }
    JSONObject lastFailure() { return copy(failure); }
    JSONArray events() {
        try { return new JSONArray(events.toString()); } catch (Exception ignored) { return new JSONArray(); }
    }
}
