package dev.mobilecodex.app.core;
import org.json.JSONObject;
import org.junit.Test;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
public class DiagnosticReportTest {
    @Test public void copiesOnlyFixedFieldsAndCoarseDeviceBuckets() {
        JSONObject source = obj("errorCode", "initialize_timeout", "stage", "initialize_sent", "operation", "login", "stdoutBytes", 12,
            "token", "secret", "message", "private chat", "path", "/private/account", "stderrCategory", "private log", "spawnMs", -1);
        JSONObject report = DiagnosticReport.build(source, "0.3.6", 35, "arm64", "private device model", "tablet", "le8gb", "16k");
        String encoded = report.toString();
        assertFalse(encoded.contains("secret")); assertFalse(encoded.contains("private")); assertFalse(encoded.contains("/"));
        assertEquals(27, report.length()); assertEquals("other", report.optString("manufacturer"));
        assertEquals("none", report.optString("stderrCategory")); assertEquals(-1, report.optLong("spawnMs"));
        assertTrue(report.optString("reportId").matches("[a-f0-9-]{36}"));
        assertNotEquals(report.optString("reportId"), DiagnosticReport.build(source, "0.3.6",35,"arm64","other","phone","unknown","unknown").optString("reportId"));
    }
    @Test public void countersAreBoundedAndTimingsRounded() {
        JSONObject report = DiagnosticReport.build(obj("stdoutBytes",Long.MAX_VALUE,"invalidJson",-10,"elapsedMs",602001,"firstByteMs",155),"0.3.6",35,"arm64","google","phone","unknown","4k");
        assertEquals(100000000,report.optLong("stdoutBytes")); assertEquals(0,report.optLong("invalidJson"));
        assertEquals(600000,report.optLong("elapsedMs")); assertEquals(100,report.optLong("firstByteMs"));
    }
    @Test public void recordsTransportCountsWithoutCapturingContent() throws Exception {
        StartupDiagnostics stats = new StartupDiagnostics(); stats.spawned(); stats.initializing(); stats.written();
        stats.bytes(8); stats.line(); stats.invalid(); stats.unmatched(); stats.stderr(5);
        JSONObject snapshot = stats.snapshot("initialize_timeout","pending",true,-1,"none");
        assertEquals(8,snapshot.optLong("stdoutBytes")); assertEquals(1,snapshot.optLong("stdoutLines"));
        assertEquals(1,snapshot.optLong("invalidJson")); assertEquals(1,snapshot.optLong("unmatchedIds")); assertEquals(5,snapshot.optLong("stderrBytes"));
        assertEquals(-1,snapshot.optLong("responseMs")); assertEquals("initialize_sent",snapshot.optString("stage"));
        assertFalse(snapshot.has("message")); assertFalse(snapshot.has("stdout"));
    }
}
