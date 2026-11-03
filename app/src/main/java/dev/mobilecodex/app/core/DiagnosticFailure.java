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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DiagnosticFailure measured && measured.operation == operation)
                return parse(measured.snapshot.toString());
        }
        return obj("errorCode", "operation_failed", "stage", "unknown", "homeKind", "unknown");
    }
}
