package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.LongSupplier;

/** In-memory-only GitHub OAuth device authorization flow. */
final class GitHubDeviceAuth {
    static final String DEVICE_CODE_ENDPOINT = "https://github.com/login/device/code";
    static final String ACCESS_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String VERIFICATION_URI = "https://github.com/login/device";
    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long MAX_INTERVAL_SECONDS = 3_600L;
    private static final long MAX_DEVICE_LIFETIME_SECONDS = 86_400L;
    private static final String LOGIN_UNAVAILABLE = "이 앱의 GitHub 로그인을 사용할 수 없습니다. 앱 업데이트를 확인해 주세요.";

    interface Transport {
        JSONObject request(String endpoint, JSONObject body) throws Exception;
    }

    static final class Result {
        final JSONObject pending;
        final String accessToken;
        final long expiresAt;

        Result(JSONObject pending, String accessToken, long expiresAt) {
            this.pending = pending;
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }

        static Result pending(long interval) {
            return new Result(json("pending", true, "interval", interval), null, 0);
        }

        static Result token(String accessToken, long expiresAt) {
            return new Result(null, accessToken, expiresAt);
        }
    }

    private static final class Attempt {
        final String flowId;
        final String deviceCode;
        final long expiresAt;
        long intervalSeconds;
        long lastRequestAt;

        Attempt(String flowId, String deviceCode, long expiresAt, long intervalSeconds, long lastRequestAt) {
            this.flowId = flowId;
            this.deviceCode = deviceCode;
            this.expiresAt = expiresAt;
            this.intervalSeconds = intervalSeconds;
            this.lastRequestAt = lastRequestAt;
        }
    }

    private final String clientId;
    private final Transport transport;
    private final LongSupplier clock;
    private Attempt attempt;

    GitHubDeviceAuth(String clientId) {
        this(clientId, new HttpTransport(), System::currentTimeMillis);
    }

    GitHubDeviceAuth(String clientId, Transport transport, LongSupplier clock) {
        this.clientId = clientId == null ? "" : clientId.trim();
        if (transport == null || clock == null) throw new IllegalArgumentException("transport and clock are required");
        this.transport = transport;
        this.clock = clock;
    }

    synchronized JSONObject start() throws Exception {
        // Starting over must make an older code unusable even if the new request fails.
        attempt = null;
        requireConfiguredClientId();
        final long now = now();
        final JSONObject response;
        try {
            response = transport.request(DEVICE_CODE_ENDPOINT, body("client_id", clientId, "scope", "repo"));
        } catch (Exception ignored) {
            throw failure("GitHub 인증 요청을 시작하지 못했습니다.");
        }
        String startError = string(response, "error");
        if ("incorrect_client_credentials".equals(startError) || "invalid_client".equals(startError)) {
            throw failure(LOGIN_UNAVAILABLE);
        }
        if (startError != null) throw failure("GitHub 인증 요청을 시작하지 못했습니다.");
        String deviceCode = requiredString(response, "device_code", 1_024);
        String userCode = requiredString(response, "user_code", 128);
        String verificationUri = requiredString(response, "verification_uri", 128);
        long expiresIn = requiredPositiveLong(response, "expires_in", MAX_DEVICE_LIFETIME_SECONDS);
        long interval = optionalPositiveLong(response, "interval", 5, MAX_INTERVAL_SECONDS);
        if (!VERIFICATION_URI.equals(verificationUri)) throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        final long expiresAt;
        try {
            expiresAt = Math.addExact(now, Math.multiplyExact(expiresIn, 1_000L));
        } catch (ArithmeticException ignored) {
            throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        }
        String flowId = UUID.randomUUID().toString();
        attempt = new Attempt(flowId, deviceCode, expiresAt, interval, now);
        return json("flowId", flowId, "userCode", userCode, "verificationUri", VERIFICATION_URI,
                "interval", interval, "expiresAt", expiresAt);
    }

    synchronized Result poll(String flowId) throws Exception {
        Attempt current = matchingAttempt(flowId);
        long now = now();
        if (now >= current.expiresAt) {
            attempt = null;
            throw failure("GitHub 인증 시간이 만료됐습니다. 다시 시작해 주세요.");
        }
        if (now - current.lastRequestAt < current.intervalSeconds * 1_000L) {
            throw failure("GitHub 인증 요청 간격을 기다려 주세요.");
        }
        final JSONObject response;
        try {
            response = transport.request(ACCESS_TOKEN_ENDPOINT, body(
                    "client_id", clientId,
                    "device_code", current.deviceCode,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        } catch (Exception ignored) {
            attempt = null;
            throw failure("GitHub 인증 요청을 처리하지 못했습니다.");
        }
        current.lastRequestAt = now;
        String error = string(response, "error");
        if (error != null) return handleError(current, error);
        String token = string(response, "access_token");
        if (token == null || token.isBlank() || token.length() > 4_096 || hasControlCharacter(token) || !"bearer".equalsIgnoreCase(string(response, "token_type")) || !hasRepoScope(string(response, "scope"))) {
            attempt = null;
            throw failure("GitHub 인증 응답을 확인하지 못했습니다.");
        }
        final long expiresAt;
        try {
            expiresAt = response.has("expires_in") ? Math.addExact(now, Math.multiplyExact(requiredPositiveLong(response, "expires_in", Long.MAX_VALUE / 1_000L), 1_000L)) : 0;
        } catch (ArithmeticException | IOException ignored) {
            attempt = null;
            throw failure("GitHub 인증 응답을 확인하지 못했습니다.");
        }
        attempt = null;
        return Result.token(token, expiresAt);
    }

    synchronized void cancel(String flowId) {
        if (attempt != null && attempt.flowId.equals(flowId)) attempt = null;
    }

    private Result handleError(Attempt current, String error) throws IOException {
        if ("authorization_pending".equals(error)) return Result.pending(current.intervalSeconds);
        if ("slow_down".equals(error)) {
            try {
                current.intervalSeconds = Math.addExact(current.intervalSeconds, 5L);
            } catch (ArithmeticException ignored) {
                attempt = null;
                throw failure("GitHub 인증 요청을 처리하지 못했습니다.");
            }
            return Result.pending(current.intervalSeconds);
        }
        attempt = null;
        if ("access_denied".equals(error)) throw failure("GitHub 인증이 취소됐습니다.");
        if ("expired_token".equals(error) || "token_expired".equals(error)) throw failure("GitHub 인증 시간이 만료됐습니다. 다시 시작해 주세요.");
        if ("incorrect_client_credentials".equals(error) || "invalid_client".equals(error)) throw failure(LOGIN_UNAVAILABLE);
        throw failure("GitHub 인증 요청을 처리하지 못했습니다.");
    }

    private Attempt matchingAttempt(String flowId) throws IOException {
        if (attempt == null || flowId == null || !attempt.flowId.equals(flowId)) {
            throw failure("진행 중인 GitHub 인증 요청이 없습니다.");
        }
        return attempt;
    }

    private void requireConfiguredClientId() throws IOException {
        if (!clientId.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw failure(LOGIN_UNAVAILABLE);
        }
    }

    private long now() throws IOException {
        long value = clock.getAsLong();
        if (value < 0) throw failure("GitHub 인증 요청을 처리하지 못했습니다.");
        return value;
    }

    private static JSONObject body(String... pairs) {
        Object[] values = new Object[pairs.length];
        System.arraycopy(pairs, 0, values, 0, pairs.length);
        return json(values);
    }

    private static JSONObject json(Object... pairs) {
        try {
            JSONObject result = new JSONObject();
            for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
            return result;
        } catch (Exception ignored) {
            throw new IllegalStateException("Unable to build OAuth request");
        }
    }

    private static String requiredString(JSONObject response, String key, int maximumLength) throws IOException {
        String value = string(response, key);
        if (value == null || value.isBlank() || value.length() > maximumLength || hasControlCharacter(value)) throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        return value;
    }

    private static String string(JSONObject response, String key) {
        if (response == null) return null;
        Object value = response.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static long requiredPositiveLong(JSONObject response, String key, long maximum) throws IOException {
        return optionalPositiveLong(response, key, -1, maximum);
    }

    private static long optionalPositiveLong(JSONObject response, String key, long fallback, long maximum) throws IOException {
        if (response == null || !response.has(key)) {
            if (fallback > 0) return fallback;
            throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        }
        Object value = response.opt(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        long number = ((Number) value).longValue();
        if (number <= 0 || number > maximum) throw failure("GitHub 인증 코드 응답이 올바르지 않습니다.");
        return number;
    }

    private static boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) return true;
        }
        return false;
    }

    private static boolean hasRepoScope(String scope) {
        if (scope == null || scope.length() > 4_096) return false;
        for (String item : scope.split("[\\s,]+")) if ("repo".equals(item)) return true;
        return false;
    }

    private static IOException failure(String message) {
        return new IOException(t(message));
    }

    private static final class HttpTransport implements Transport {
        @Override public JSONObject request(String endpoint, JSONObject body) throws Exception {
            validateEndpoint(endpoint);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                byte[] encoded = form(body).getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_REQUEST_BYTES) throw new IOException("GitHub OAuth request too large");
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(15_000);
                connection.setFixedLengthStreamingMode(encoded.length);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("GitHub OAuth request failed");
                InputStream input = connection.getInputStream();
                try (InputStream response = input) { return new JSONObject(new String(readBounded(response), StandardCharsets.UTF_8)); }
            } finally {
                connection.disconnect();
            }
        }

        private static void validateEndpoint(String endpoint) throws IOException {
            if (!DEVICE_CODE_ENDPOINT.equals(endpoint) && !ACCESS_TOKEN_ENDPOINT.equals(endpoint)) throw new IOException("GitHub OAuth endpoint rejected");
            URL url = new URL(endpoint);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                    || !"github.com".equalsIgnoreCase(url.getHost()) || url.getQuery() != null || url.getRef() != null) {
                throw new IOException("GitHub OAuth endpoint rejected");
            }
        }

        private static String form(JSONObject body) throws IOException {
            StringBuilder output = new StringBuilder();
            for (Iterator<String> keys = body.keys(); keys.hasNext();) {
                String key = keys.next();
                String value = string(body, key);
                if (value == null) throw new IOException("GitHub OAuth request rejected");
                if (output.length() > 0) output.append('&');
                output.append(URLEncoder.encode(key, "UTF-8"));
                output.append('=').append(URLEncoder.encode(value, "UTF-8"));
            }
            return output.toString();
        }

        private static byte[] readBounded(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("GitHub OAuth response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
