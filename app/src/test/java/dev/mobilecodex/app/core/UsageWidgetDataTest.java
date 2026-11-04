package dev.mobilecodex.app.core;

import org.junit.Test;
import org.json.JSONArray;
import java.util.List;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

public class UsageWidgetDataTest {
    @Test public void codexReadsNamedLimitBucketsWhenTheLegacyBucketIsAbsent() {
        var data = UsageWidgetData.codex(obj("rateLimitsByLimitId", obj("model", obj("limitName", "Model budget",
            "primary", obj("usedPercent", 47, "windowDurationMins", 10080)))), 123);
        assertEquals(53, data.limits().get(0).remaining(), 0);
        assertEquals("Model budget · week", data.limits().get(0).label());
    }
    @Test public void codexUsesTheMostRestrictiveUnexpiredWindow() {
        UsageWidgetData data = UsageWidgetData.codex(obj("rateLimits", obj(
            "primary", obj("usedPercent", 12, "windowDurationMins", 300, "resetsAt", 2000000000L),
            "secondary", obj("usedPercent", 83, "windowDurationMins", 10080, "resetsAt", 2100000000L))), 1000);
        assertEquals("week", data.current(1900000000000L).get(0).label());
        assertEquals(17, data.current(1900000000000L).get(0).remaining(), 0);
        assertEquals(2000000000000L, data.limits().get(0).resetsAt());
    }
    @Test public void elapsedResetNeedsAFreshReadingRatherThanInventingFullCapacity() {
        UsageWidgetData data = new UsageWidgetData("ready", 1000, List.of(new UsageWidgetData.Limit("session", 2, 2000)));
        assertEquals(2, data.current(1999).get(0).remaining(), 0);
        assertTrue(data.current(2000).isEmpty());
    }
    @Test public void absentOrMalformedPercentIsUnknownRatherThanFullOrEmpty() {
        var codex = UsageWidgetData.codex(obj("rateLimits", obj("primary", obj("usedPercent", "bad"))), 1000);
        var claude = UsageWidgetData.provider(obj("usage", new JSONArray().put(obj("label", "week"))));
        assertTrue(codex.limits().isEmpty()); assertTrue(claude.limits().isEmpty());
        assertEquals("unknown", UsageWidgetData.read("corrupted").status());
    }
    @Test public void providerRowsPreserveObservationTimeAndIsoResetsWithoutPrivateFields() {
        var source = obj("auth", "signed_in", "usageUpdatedAt", 1234, "access_token", "private-token", "email", "private-email",
            "usage", new JSONArray().put(obj("label", "All models", "limit", "week", "remainingPercent", 39,
                "resetsAt", "2030-01-01T00:00:00Z")));
        UsageWidgetData data = UsageWidgetData.provider(source);
        assertEquals(1234, data.updatedAt()); assertEquals(1893456000000L, data.limits().get(0).resetsAt());
        assertEquals(data, UsageWidgetData.read(data.json().toString()));
        assertFalse(data.json().toString().contains("private"));
        source.put("auth", "signed_out"); assertTrue(UsageWidgetData.provider(source).limits().isEmpty());
    }
    @Test public void outOfRangeValuesAreClampedAndModelGroupLabelsAreRetained() {
        UsageWidgetData data = UsageWidgetData.provider(obj("usage", new JSONArray()
            .put(obj("label", "Gemini Pro", "limit", "daily", "remainingPercent", -3))
            .put(obj("label", "Gemini Flash", "remainingPercent", 130))));
        assertEquals("Gemini Pro · daily", data.limits().get(0).label());
        assertEquals(0, data.limits().get(0).remaining(), 0); assertEquals(100, data.limits().get(1).remaining(), 0);
    }
}
