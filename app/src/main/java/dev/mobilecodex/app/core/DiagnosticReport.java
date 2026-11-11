package dev.mobilecodex.app.core;

import org.json.JSONObject;
import java.util.Set;
import java.util.UUID;
import static dev.mobilecodex.app.core.Json.obj;

/** Explicit allow-list; never copy source JSON, exceptions or runtime output. */
public final class DiagnosticReport {
    private DiagnosticReport() {}
    public static String choice(String value, String fallback, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return item;
        return fallback;
    }
    // Detailed local stages are deliberately reduced to the deployed relay's
    // existing vocabulary. Never require a server rollout to submit a report.
    private static String publicStage(String stage) {
        return switch (stage) {
            case "runtime_check", "config_prepare", "environment_prepare", "service_start" -> "before_spawn";
            case "runtime_prepare", "login_prepare" -> "unknown";
            case "account_read", "limits_read", "conversation_restore" -> "initialized";
            case "login_rpc" -> "ready";
            default -> choice(stage, "unknown", "before_spawn", "spawned", "initialize_sent", "initialized", "ready", "running");
        };
    }
    private static Object count(JSONObject source, String key, long max, boolean rounded) {
        Object raw = source.opt(key);
        if (!(raw instanceof Number)) return JSONObject.NULL;
        long value = Math.max(0, Math.min(max, ((Number) raw).longValue()));
        return rounded ? value / 100 * 100 : value;
    }
    private static long time(JSONObject source, String key) { return Math.max(-1, Math.min(600000, source.optLong(key, -1))) / 100 * 100; }
    public static JSONObject build(JSONObject source, String version, int api, String abi, String manufacturer, String deviceClass, String ramClass, String pageSize) {
        JSONObject value = obj("schema", 2, "reportId", UUID.randomUUID().toString(),
            "appVersion", version != null && version.matches("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})") ? version : "unknown", "runtime", "codex_01621",
            "errorCode", choice(source.optString("errorCode"), "operation_failed", "initialize_timeout", "start_failure", "transport_closed",
                "request_timeout", "permission_denied", "invalid_response", "storage_failure", "io_failure", "login_failed",
                "rpc_invalid_request", "rpc_method_missing", "rpc_internal", "rpc_rejected", "turn_failed", "context_limit", "usage_limit",
                "upstream_connection", "upstream_auth", "upstream_bad_request", "upstream_internal", "sandbox_failure"),
            "operation", choice(source.optString("operation"), "other", "login", "start", "chat"),
            "stage", publicStage(source.optString("stage")),
            "homeKind", choice(source.optString("homeKind"), "unknown", "active", "pending"),
            "elapsedMs", count(source, "elapsedMs", 600000, true),
            "processAlive", source.opt("processAlive") instanceof Boolean ? source.opt("processAlive") : JSONObject.NULL,
            "exitCode", Math.max(-1, Math.min(255, source.optInt("exitCode", -1))),
            "stderrCategory", choice(source.optString("stderrCategory"), "none", "native_link", "panic", "memory", "config"),
            "androidApi", Math.max(29, Math.min(100, api)), "abi", choice(abi, "other", "arm64"),
            "manufacturer", choice(manufacturer, "other", "samsung", "google", "xiaomi", "oppo", "vivo", "oneplus", "motorola", "sony", "asus"),
            "deviceClass", choice(deviceClass, "phone", "tablet"), "ramClass", choice(ramClass, "unknown", "le4gb", "le8gb", "gt8gb"),
            "pageSize", choice(pageSize, "unknown", "4k", "16k", "other"));
        for (String key : Set.of("spawnMs", "writeMs", "firstByteMs", "responseMs")) {
            long raw = source.optLong(key, -1);
            try { value.put(key, raw < 0 ? -1 : time(source, key)); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        for (String key : Set.of("stdoutBytes", "stderrBytes", "stdoutLines", "invalidJson", "unmatchedIds")) {
            try { value.put(key, count(source, key, key.endsWith("Bytes") ? 100000000 : 1000000, false)); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        return value;
    }
}
