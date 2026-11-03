package dev.mobilecodex.app.core;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.json.JSONObject;
import org.junit.Test;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

public class DiagnosticFailureTest {
    @Test public void onlyTheSameFailedOperationCanUseItsStartupMeasurements() {
        Object login = new Object();
        JSONObject measured = obj("errorCode", "initialize_timeout", "stage", "initialize_sent", "stdoutBytes", 12);
        Throwable failure = new DiagnosticFailure("private error /path token", new IOException("private cause"), login, measured);
        measured.put("stdoutBytes", 999);
        assertEquals(12, DiagnosticFailure.snapshot(new CompletionException(failure), login).getLong("stdoutBytes"));
        JSONObject laterChat = DiagnosticFailure.snapshot(failure, new Object());
        assertEquals("operation_failed", laterChat.getString("errorCode")); assertFalse(laterChat.has("stdoutBytes"));
        assertFalse(laterChat.toString().contains("private"));
        assertFalse(DiagnosticFailure.snapshot(new IOException("later failure"), login).has("stdoutBytes"));
    }
    @Test public void replacementProcessAndRollbackCannotOverwriteTheOriginalFailure() {
        Object operation = new Object();
        StartupDiagnostics first = new StartupDiagnostics(); first.bytes(12);
        Throwable original = new DiagnosticFailure("first execution", null, operation, first.snapshot("start_failure", "pending", false, 1, "none"));
        StartupDiagnostics replacement = new StartupDiagnostics(); replacement.bytes(300);
        Throwable rollback = new DiagnosticFailure("replacement execution", null, operation, replacement.snapshot("start_failure", "active", false, 2, "none"));
        original.addSuppressed(rollback);
        JSONObject snapshot = DiagnosticFailure.snapshot(original, operation);
        assertEquals(12, snapshot.getLong("stdoutBytes")); assertEquals("pending", snapshot.getString("homeKind"));
        assertEquals(300, DiagnosticFailure.snapshot(rollback, operation).getLong("stdoutBytes"));
        snapshot.put("stdoutBytes", 900);
        assertEquals(12, DiagnosticFailure.snapshot(original, operation).getLong("stdoutBytes"));
        assertFalse(DiagnosticFailure.snapshot(null, new Object()).has("stdoutBytes"));
    }
}
