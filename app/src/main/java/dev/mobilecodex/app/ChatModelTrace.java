package dev.mobilecodex.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, detached settings-only evidence. Times are supplied from a monotonic clock. */
final class ChatModelTrace {
    private JSONObject operation = new JSONObject();
    private JSONObject snapshot = new JSONObject();
    private JSONObject failure = new JSONObject();
    private JSONObject firstFailure = new JSONObject();
    private boolean incidentActive;
    private JSONArray events = new JSONArray();
    private String stage = "idle";
    private String previousSignature = "";

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); } catch (Exception ignored) { return new JSONObject(); }
    }
    void restoreFailure(String saved) {
        try {
            JSONObject data = new JSONObject(saved);
            failure = data.has("lastFailure") ? data.getJSONObject("lastFailure") : data;
            firstFailure = data.has("firstFailure") ? data.getJSONObject("firstFailure") : copy(failure);
            incidentActive = data.optBoolean("incidentActive", failure.length() > 0);
        } catch (Exception ignored) { failure = new JSONObject(); firstFailure = new JSONObject(); incidentActive = false; }
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
        if (state != null) {
            state = copy(state);
            JSONObject observation = state.optJSONObject("inputObservation");
            if (observation != null && (observation.optLong("operationId", -1) != operation.optLong("operationId")
                    || observation.optLong("sessionEpoch", -1) != operation.optLong("startSessionEpoch")))
                state.remove("inputObservation");
            snapshot = copy(state);
        }
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
        if (!operation.optString("target").isEmpty()) {
            if (!error.isEmpty()) {
                failure = copy(result);
                if (!incidentActive) firstFailure = copy(result);
                incidentActive = true;
            } else incidentActive = false;
        }
        return result;
    }
    JSONObject lastFailure() { return copy(failure); }
    JSONObject firstFailure() { return copy(firstFailure); }
    String savedFailures() {
        JSONObject result = new JSONObject();
        try {
            result.put("firstFailure", copy(firstFailure)); result.put("lastFailure", copy(failure));
            result.put("incidentActive", incidentActive);
        } catch (Exception ignored) {}
        return result.toString();
    }
    JSONArray events() {
        try { return new JSONArray(events.toString()); } catch (Exception ignored) { return new JSONArray(); }
    }
}
