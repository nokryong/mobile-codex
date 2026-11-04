package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static dev.mobilecodex.app.core.Json.obj;

/** Only displayable quota data; never credentials, prompts, or account addresses. */
public record UsageWidgetData(String status, long updatedAt, List<Limit> limits) {
    public record Limit(String label, double remaining, long resetsAt) {}
    public UsageWidgetData { limits = List.copyOf(limits); }
    public static UsageWidgetData empty(String status) { return new UsageWidgetData(status, 0, List.of()); }
    public List<Limit> current(long now) {
        return limits.stream().filter(row -> row.resetsAt() == 0 || row.resetsAt() > now)
            .sorted(Comparator.comparingDouble(Limit::remaining)).toList();
    }
    public static UsageWidgetData codex(JSONObject response, long now) {
        List<Limit> rows = new ArrayList<>();
        JSONObject buckets = response == null ? null : response.optJSONObject("rateLimitsByLimitId");
        if (buckets != null && buckets.length() > 0) {
            for (var keys = buckets.keys(); keys.hasNext();) {
                String id = keys.next();
                JSONObject limits = buckets.optJSONObject(id);
                if (limits != null) codexWindows(rows, limits, limitName(limits, id));
                if (rows.size() >= 8) break;
            }
        } else if (response != null && response.optJSONObject("rateLimits") != null) codexWindows(rows, response.optJSONObject("rateLimits"), "");
        return new UsageWidgetData(rows.isEmpty() ? "unknown" : "ready", now, rows);
    }
    private static String limitName(JSONObject limits, String fallback) {
        // Android's optString renders an explicit JSON null as the text "null".
        for (String key : List.of("limitName", "normalModelSlug"))
            if (!limits.isNull(key) && !limits.optString(key).isBlank()) return limits.optString(key);
        return fallback;
    }
    private static void codexWindows(List<Limit> rows, JSONObject limits, String prefix) {
        for (String key : List.of("primary", "secondary")) {
            if (rows.size() >= 8) break;
            JSONObject window = limits.optJSONObject(key);
            if (window == null) continue;
            double used = number(window, "usedPercent");
            if (!Double.isFinite(used)) continue;
            int minutes = window.optInt("windowDurationMins");
            String label = minutes == 10080 ? "week" : minutes == 300 ? "session"
                : minutes > 0 ? minutes + "m" : key;
            if (!prefix.isBlank()) label = prefix + " · " + label;
            rows.add(new Limit(label, clamp(100 - used), epoch(window.opt("resetsAt"))));
        }
    }
    public static UsageWidgetData provider(JSONObject provider) {
        if ("signed_out".equals(provider.optString("auth"))) return empty("signed_out");
        List<Limit> rows = new ArrayList<>();
        JSONArray usage = provider.optJSONArray("usage");
        if (usage != null) for (int i = 0; i < Math.min(usage.length(), 8); i++) {
            JSONObject row = usage.optJSONObject(i);
            if (row == null) continue;
            double remaining = number(row, "remainingPercent");
            if (!Double.isFinite(remaining)) continue;
            String limit = row.optString("limit");
            String label = List.of("session", "week").contains(limit) ? limit : row.optString("label");
            if (!limit.isBlank() && !List.of("session", "week").contains(limit)) label += " · " + limit;
            rows.add(new Limit(label.substring(0, Math.min(label.length(), 100)), clamp(remaining), epoch(row.opt("resetsAt"))));
        }
        return new UsageWidgetData(rows.isEmpty() ? "unknown" : "ready", provider.optLong("usageUpdatedAt"), rows);
    }
    private static double number(JSONObject value, String key) {
        Object number = value.opt(key);
        return number instanceof Number ? ((Number) number).doubleValue() : Double.NaN;
    }
    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
    private static long epoch(Object value) {
        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            return number > 0 && number < 10_000_000_000L ? number * 1000 : Math.max(0, number);
        }
        if (value instanceof String) try { return Instant.parse((String) value).toEpochMilli(); } catch (Exception ignored) { }
        return 0;
    }
    public JSONObject json() {
        JSONArray rows = new JSONArray();
        for (Limit row : limits) rows.put(obj("label", row.label(), "remaining", row.remaining(), "resetsAt", row.resetsAt()));
        return obj("status", status, "updatedAt", updatedAt, "limits", rows);
    }
    public static UsageWidgetData read(String text) {
        try {
            JSONObject value = new JSONObject(text);
            JSONArray list = value.optJSONArray("limits");
            List<Limit> rows = new ArrayList<>();
            if (list != null) for (int i = 0; i < Math.min(8, list.length()); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row == null || !Double.isFinite(number(row, "remaining"))) continue;
                rows.add(new Limit(row.optString("label"), clamp(number(row, "remaining")), row.optLong("resetsAt")));
            }
            return new UsageWidgetData(value.optString("status", "unknown"), value.optLong("updatedAt"), rows);
        } catch (Exception ignored) { return empty("unknown"); }
    }
}
