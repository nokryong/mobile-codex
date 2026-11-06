package dev.mobilecodex.app.core;

import java.io.IOException;
import org.json.JSONObject;
import static dev.mobilecodex.app.core.Json.*;

/** A frozen measurement of this failure, never a cache for the next operation. */
public final class DiagnosticFailure extends IOException {
    private final Object operation;
    private final JSONObject snapshot;

    public DiagnosticFailure(String message, Throwable cause, Object operation, JSONObject snapshot) {
        super(message, cause);
        this.operation = operation;
        this.snapshot = parse(snapshot.toString());
    }

    public static JSONObject snapshot(Throwable failure, Object operation) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof DiagnosticFailure measured) {
                if (measured.operation == operation) return parse(measured.snapshot.toString());
                return generic("operation_failed");
            }
        }
        cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof RpcFailure remote) return generic(remote.category);
            if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.SocketTimeoutException) return generic("request_timeout");
            if (cause instanceof SecurityException) return generic("permission_denied");
            if (cause instanceof org.json.JSONException) return generic("invalid_response");
            if (cause instanceof java.nio.file.FileSystemException) return generic("storage_failure");
            if (cause instanceof IOException && cause.getCause() == null) return generic("io_failure");
        }
        return generic("operation_failed");
    }
    private static JSONObject generic(String code) {
        return obj("errorCode", code, "stage", "unknown", "homeKind", "unknown");
    }
    /** UI retains the upstream message; diagnostics retain only a fixed classification. */
    public static final class RpcFailure extends IOException {
        final String category;
        public RpcFailure(String message, JSONObject error) {
            super(message);
            category = remoteCategory(error);
        }
    }
    public static String remoteCategory(JSONObject error) {
        if (error == null) return "turn_failed";
        Object code = error.opt("code");
        if (code instanceof Number) {
            long numeric = ((Number) code).longValue();
            if (((Number) code).doubleValue() != numeric || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) return "rpc_rejected";
            return switch ((int) numeric) {
                case -32700, -32600, -32602 -> "rpc_invalid_request";
                case -32601 -> "rpc_method_missing";
                case -32603 -> "rpc_internal";
                default -> "rpc_rejected";
            };
        }
        // Only exact, documented engine categories are mapped; arbitrary strings are discarded.
        Object info = error.opt("codexErrorInfo");
        String name = info instanceof String ? (String) info : info instanceof JSONObject object && object.length() == 1 ? object.keys().next() : "";
        return switch (name) {
            case "contextWindowExceeded" -> "context_limit";
            case "usageLimitExceeded" -> "usage_limit";
            case "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected", "responseTooManyFailedAttempts" -> "upstream_connection";
            case "unauthorized" -> "upstream_auth";
            case "badRequest" -> "upstream_bad_request";
            case "internalServerError" -> "upstream_internal";
            case "sandboxError" -> "sandbox_failure";
            default -> "turn_failed";
        };
    }
}
