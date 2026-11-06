package dev.mobilecodex.app.core;
import org.json.JSONObject;
import org.junit.Test;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
public class DiagnosticReportTest {
    @Test public void reportsActualVersionsAndOnlyFixedErrorCategories() {
        for (String version : new String[]{"0.3.7", "0.3.8", "1.20.300"})
            assertEquals(version, DiagnosticReport.build(obj("errorCode", "usage_limit"),version,35,"arm64","other","phone","unknown","unknown").optString("appVersion"));
        JSONObject invalid = DiagnosticReport.build(obj("errorCode", "secret"),"private-version",35,"arm64","other","phone","unknown","unknown");
        assertEquals("unknown", invalid.optString("appVersion")); assertEquals("operation_failed", invalid.optString("errorCode"));
    }
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
    @Test public void missingMeasurementsAreNotMeasuredZeros() throws Exception {
        JSONObject absent = report(obj()), zero = report(new StartupDiagnostics().snapshot("start_failure", "active", false, 0, "none"));
        assertEquals(2, absent.getInt("schema"));
        for (String key : new String[]{"elapsedMs", "stdoutBytes", "stdoutLines", "stderrBytes", "invalidJson", "unmatchedIds", "processAlive"}) {
            assertTrue(key, absent.has(key) && absent.isNull(key));
            assertFalse(key, zero.isNull(key));
        }
        assertEquals(0, zero.getLong("stdoutBytes")); assertFalse(zero.getBoolean("processAlive"));
        assertEquals(-1, absent.getLong("spawnMs")); assertEquals(-1, absent.getInt("exitCode"));
    }
    @Test public void measuredReportMatchesSharedRelayAndPreviewFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/diagnostic-v2.json")) {
            assertNotNull(input);
            JSONObject fixture = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject actual = report(obj("operation", "login"));
            actual.put("reportId", fixture.getString("reportId"));
            assertEquals(fixture.length(), actual.length());
            for (var keys = fixture.keys(); keys.hasNext();) {
                String key = keys.next();
                Object expected = fixture.get(key), observed = actual.get(key);
                if (expected instanceof Number) {
                    assertTrue(key, observed instanceof Number);
                    assertEquals(key, expected.toString(), observed.toString());
                } else assertEquals(key, expected, observed);
            }
        }
    }
    private static JSONObject report(JSONObject source) {
        return DiagnosticReport.build(source, "0.3.6", 35, "arm64", "other", "phone", "unknown", "unknown");
    }
}
