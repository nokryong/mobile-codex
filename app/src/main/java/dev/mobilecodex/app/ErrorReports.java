package dev.mobilecodex.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import dev.mobilecodex.app.core.DiagnosticReport;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static dev.mobilecodex.app.core.Json.*;

/** User-initiated, content-free reporting on a queue independent of the engine. */
final class ErrorReports {
    interface Reply { void complete(JSONObject value); }
    interface Transport { JSONObject post(String route, JSONObject body) throws Exception; void cancel(); }
    private final ExecutorService worker;
    private final Transport transport;
    private final Context context;
    private JSONObject latest, preview;
    // Correlation never enters the public preview or network payload.
    private String latestScope = "", previewScope = "";
    private boolean busy;
    private volatile int generation;

    ErrorReports(Context context) { this(context, Executors.newSingleThreadExecutor(), new HttpTransport()); }
    ErrorReports(Context context, ExecutorService worker, Transport transport) { this.context = context; this.worker = worker; this.transport = transport; }
    static JSONObject genericDiagnostic() { return obj("errorCode", "operation_failed", "stage", "unknown", "homeKind", "unknown"); }
    static JSONObject withOperation(JSONObject value, String action) {
        JSONObject result = parse(value.toString());
        String operation = Set.of("auth.login", "auth.add", "auth.relogin").contains(action) || action.startsWith("auth.") ? "login" : action.equals("runtime.start") ? "start" : action.startsWith("chat.") ? "chat" : "other";
        try { result.put("operation", operation); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return result;
    }
    synchronized void capture(JSONObject diagnostic) {
        // Device model, serial, account IDs, Android IDs and paths are never read.
        String ram = "unknown", page = "unknown";
        try { ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo(); context.getSystemService(ActivityManager.class).getMemoryInfo(info); ram = info.totalMem <= 4L * 1024 * 1024 * 1024 ? "le4gb" : info.totalMem <= 8L * 1024 * 1024 * 1024 ? "le8gb" : "gt8gb"; } catch (Exception ignored) { }
        try { long size = Os.sysconf(OsConstants._SC_PAGESIZE); page = size == 4096 ? "4k" : size == 16384 ? "16k" : "other"; } catch (Exception ignored) { }
        latest = DiagnosticReport.build(diagnostic, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT,
            java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") ? "arm64" : "other",
            Build.MANUFACTURER.toLowerCase(Locale.ROOT), context.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? "tablet" : "phone", ram, page);
        latestScope = diagnostic.optString("_scope");
    }
    synchronized JSONObject status() { return obj("available", latest != null); }

    synchronized boolean clear(String operation) { return clear(operation, ""); }
    synchronized boolean clear(String operation, String scope) {
        // Unknown chat scope cannot prove recovery. Concurrent conversations
        // must not consume each other's report or consent.
        if (matchesRecovery(latest, latestScope, operation, scope)) { latest = null; latestScope = ""; }
        boolean previewInvalidated = matchesRecovery(preview, previewScope, operation, scope);
        if (previewInvalidated) cancel();
        return previewInvalidated;
    }

    private static boolean matchesRecovery(JSONObject report, String reportScope, String operation, String scope) {
        return report != null && operation.equals(report.optString("operation"))
            && (!"chat".equals(operation) || (!scope.isBlank() && scope.equals(reportScope)));
    }

    synchronized JSONObject preview() {
        if (busy) return obj("status", "busy");
        if (latest == null) return obj("status", "unavailable");
        // The native snapshot is immutable for this consent, even if a newer
        // error arrives while the preview is open. Retrying keeps its report ID.
        preview = parse(latest.toString()); previewScope = latestScope;
        return obj("status", "preview", "reportId", preview.optString("reportId"), "payload", parse(preview.toString()));
    }
    synchronized void send(String reportId, boolean consent, Reply reply) {
        if (!consent || preview == null || !preview.optString("reportId").equals(reportId)) { reply.complete(obj("status", "failed", "code", "preview_required")); return; }
        if (busy) { reply.complete(obj("status", "busy")); return; }
        busy = true; int attempt = ++generation; JSONObject payload = parse(preview.toString());
        worker.execute(() -> {
            JSONObject result;
            try {
                if (attempt != generation) throw new IOException("cancelled_or_expired");
                JSONObject challenge = transport.post("challenge", obj("reportId", reportId));
                String challengeId = challenge.optString("challengeId");
                if (!challengeId.matches("[a-f0-9]{48}") || challenge.optInt("difficulty") != 16) throw new IOException("invalid_response");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long deadline = System.nanoTime() + 15000000000L; int proof = -1;
                for (int candidate = 0; candidate <= 100000000; candidate++) {
                    if ((candidate & 1023) == 0 && (attempt != generation || System.nanoTime() > deadline)) throw new IOException("cancelled_or_expired");
                    byte[] hashed = digest.digest((challengeId + ":" + candidate).getBytes(StandardCharsets.US_ASCII));
                    if (hashed[0] == 0 && hashed[1] == 0) { proof = candidate; break; }
                }
                if (proof < 0 || attempt != generation) throw new IOException("cancelled_or_expired");
                payload.put("challengeId", challengeId); payload.put("proof", proof);
                JSONObject accepted = transport.post("reports", payload);
                String url = accepted.optString("url");
                if (!url.matches("https://github\\.com/nokryong/mobile-codex/issues/[1-9][0-9]*")) throw new IOException("invalid_response");
                result = obj("status", "sent", "url", url, "grouped", accepted.optBoolean("grouped"));
            } catch (Exception failure) {
                // Fixed codes only; never expose HTTP bodies, exception text,
                // URLs, headers or request contents through failure handling.
                result = obj("status", attempt != generation ? "cancelled" : "failed", "code", "send_unconfirmed");
            }
            synchronized (this) {
                busy = false;
                if (attempt != generation) result = obj("status", "cancelled", "code", "send_unconfirmed");
                else if ("sent".equals(result.optString("status"))) {
                    // A successful submission consumes only this consent and
                    // this report, never a newer failure captured meanwhile.
                    if (latest != null && reportId.equals(latest.optString("reportId"))) { latest = null; latestScope = ""; }
                    if (preview != null && reportId.equals(preview.optString("reportId"))) { preview = null; previewScope = ""; }
                }
            }
            reply.complete(result);
        });
    }
    synchronized JSONObject cancel() { ++generation; preview = null; previewScope = ""; transport.cancel(); return obj("status", "cancelled"); }

    static void configureReportConnection(HttpURLConnection connection) throws IOException {
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json");
        // Android's default HTTP agent contains the device model/build. Keep
        // transport metadata as limited as the consented JSON payload.
        connection.setRequestProperty("User-Agent", "MobileCodex-Diagnostics/1");
        connection.setUseCaches(false); connection.setDoOutput(true);
    }

    private static final class HttpTransport implements Transport {
        private volatile HttpURLConnection active;
        @Override public JSONObject post(String route, JSONObject body) throws Exception {
            // The endpoint is public; there is deliberately no APK secret.
            String base = BuildConfig.REPORT_RELAY_URL;
            if (!base.matches("https://[a-z0-9-]+\\.vercel\\.app")) throw new IOException("not_configured");
            HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/" + route).openConnection(); active = connection;
            try {
                configureReportConnection(connection);
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                if (encoded.length > 4096) throw new IOException("invalid_payload");
                connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
                if (connection.getResponseCode() != 200) throw new IOException("send_unconfirmed");
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (received.size() + count > 4096) throw new IOException("invalid_response");
                        received.write(chunk, 0, count);
                    }
                    return parse(new String(received.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally { connection.disconnect(); if (active == connection) active = null; }
        }
        @Override public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    }
}
