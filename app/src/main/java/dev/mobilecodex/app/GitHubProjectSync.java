package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import dev.mobilecodex.app.core.Json;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Manual project-identity sync against a user-owned private GitHub repository.
 * Only the portable projects.json event bundle is transferred. Local paths,
 * conversations, Codex credentials and source files never enter this backend.
 */
final class GitHubProjectSync implements ProjectSyncSettings.Remote {
    private static final String FILE = "projects.json";
    private static final String PREFS = "project-sync-github";
    private static final String KEY_ALIAS = "mobile-codex-project-sync-v1";
    private static final int MAX_HTTP_BYTES = PortableProjects.MAX_BYTES * 3 + 65536;
    private final SharedPreferences prefs;
    private final Client client;
    private final Object loginState = new Object();
    private String loginFlow = "";
    // A cancellation can arrive after the credential commit but before its
    // successful state reaches the settings UI.
    private String committedLoginFlow = "";
    private Map<String, ?> committedPreferences = Map.of();
    private final GitHubDeviceAuth auth = new GitHubDeviceAuth(BuildConfig.GITHUB_OAUTH_CLIENT_ID);

    GitHubProjectSync(Context context) {
        prefs = context.getSharedPreferences(PREFS, 0);
        client = new Client(new HttpTransport());
    }

    @Override public synchronized JSONObject status() {
        Config config = null;
        try { config = loadConfig(); } catch (Exception ignored) { }
        long expiry = prefs.getLong("expiresAt", 0);
        boolean authenticated = !prefs.getString("token", "").isEmpty()
            && (expiry == 0 || System.currentTimeMillis() < expiry);
        JSONArray selected;
        try { selected = new JSONArray(prefs.getString("selectedKeys", "[]")); }
        catch (Exception ignored) { selected = new JSONArray(); }
        return Json.obj("configured", !BuildConfig.GITHUB_OAUTH_CLIENT_ID.isBlank(),
            "authenticated", authenticated, "connected", authenticated && config != null,
            "account", prefs.getString("account", ""), "repository", config == null ? "" : config.repository,
            "branch", config == null ? "" : config.branch, "lastSynced", prefs.getLong("lastSynced", 0),
            "selectedKeys", selected);
    }

    @Override public synchronized JSONObject loginStart() throws Exception {
        JSONObject flow = auth.start();
        synchronized (loginState) { loginFlow = flow.getString("flowId"); clearCommittedLogin(); }
        return flow;
    }
    @Override public synchronized JSONObject loginPoll(String flowId) throws Exception {
        synchronized (loginState) { requireLoginFlow(flowId); }
        GitHubDeviceAuth.Result result = auth.poll(flowId);
        if (result.pending != null) return result.pending;
        // Revalidate identity for every login before replacing a previous account.
        JSONObject account = new HttpTransport().request("GET", "user", null, result.accessToken);
        String login = account.optString("login", "");
        if (!login.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || account.optLong("id", 0) < 1)
            throw new IOException(t("GitHub 계정을 확인하지 못했습니다. 다시 로그인해 주세요."));
        synchronized (loginState) {
            requireLoginFlow(flowId);
            Map<String, ?> previous = snapshotPreferences(prefs);
            String encrypted = Secret.encrypt(result.accessToken);
            if (!prefs.edit().clear().putString("token", encrypted).putString("account", login)
                    .putLong("expiresAt", result.expiresAt).commit())
                throw new IOException(t("GitHub 연결 정보를 저장하지 못했습니다."));
            loginFlow = "";
            committedLoginFlow = flowId;
            committedPreferences = previous;
        }
        JSONObject snapshot = status();
        synchronized (loginState) {
            if (!committedLoginFlow.equals(flowId)) throw new IOException(t("GitHub 인증이 취소됐습니다."));
            clearCommittedLogin();
        }
        return snapshot;
    }
    private void requireLoginFlow(String flowId) throws IOException {
        if (flowId == null || flowId.isEmpty() || !loginFlow.equals(flowId))
            throw new IOException(t("GitHub 인증이 취소됐습니다."));
    }
    // This short invalidation is called before queuing cancellation behind network I/O.
    @Override public void invalidateLogin(String flowId) {
        synchronized (loginState) {
            if (flowId == null || loginFlow.equals(flowId)) loginFlow = "";
            if (flowId == null || committedLoginFlow.equals(flowId)) rollbackCommittedLogin();
        }
    }
    @Override public synchronized void loginCancel(String flowId) {
        invalidateLogin(flowId); auth.cancel(flowId);
    }

    @Override public synchronized JSONObject repositories(int page) throws Exception {
        if (page < 1 || page > 1000) throw new IllegalArgumentException("Invalid repository page");
        Object response = new HttpTransport().requestValue("GET",
            "user/repos?visibility=private&affiliation=owner,collaborator,organization_member&sort=full_name&per_page=100&page=" + page,
            null, accessToken());
        if (!(response instanceof JSONArray)) throw new IOException(t("GitHub 저장소 목록을 읽을 수 없습니다."));
        JSONArray all = (JSONArray) response, repositories = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject repo = all.getJSONObject(i), permissions = repo.optJSONObject("permissions");
            if (repo.optBoolean("private") && !repo.optBoolean("archived") && !repo.optBoolean("disabled")
                    && permissions != null && permissions.optBoolean("push"))
                repositories.put(Json.obj("fullName", Client.repository(repo.getString("full_name"))));
        }
        return Json.obj("repositories", repositories, "page", page, "hasMore", all.length() == 100);
    }

    @Override public synchronized JSONObject connect(String repository) throws Exception {
        Config config = client.connect(repository, accessToken());
        Config previous = loadConfig();
        SharedPreferences.Editor edit = prefs.edit().putInt("schemaVersion", 1).putString("repository", config.repository)
            .putLong("repositoryId", config.repositoryId).putString("branch", config.branch);
        if (previous == null || previous.repositoryId != config.repositoryId || !previous.branch.equals(config.branch))
            edit.remove("selectedKeys").remove("lastSynced");
        if (!edit.commit()) throw new IOException(t("GitHub 연결 정보를 저장하지 못했습니다."));
        return status();
    }

    @Override public synchronized void selection(JSONArray keys) throws IOException {
        if (!prefs.edit().putString("selectedKeys", keys.toString()).commit())
            throw new IOException(t("동기화할 프로젝트 선택을 저장하지 못했습니다."));
    }
    @Override public synchronized void markSynced() throws IOException {
        if (!prefs.edit().putLong("lastSynced", System.currentTimeMillis()).commit())
            throw new IOException(t("동기화는 완료했지만 마지막 동기화 시간을 저장하지 못했습니다."));
    }
    @Override public synchronized Remote previewRemote() throws Exception {
        Credentials value = credentials();
        return client.pull(value.config, value.token);
    }
    @Override public synchronized JSONObject exchange(String raw, String expectedSha) throws Exception {
        Credentials value = credentials();
        return client.exchange(value.config, value.token, raw, expectedSha);
    }

    synchronized String pull() throws Exception {
        Credentials value = credentials();
        return client.pull(value.config, value.token).bundle.json().toString();
    }

    synchronized JSONObject push(String raw) throws Exception {
        Credentials value = credentials();
        JSONObject result = client.push(value.config, value.token, raw);
        result.put("repository", value.config.repository);
        return result;
    }

    @Override public synchronized void disconnect() throws IOException {
        synchronized (loginState) { auth.cancel(loginFlow); loginFlow = ""; clearCommittedLogin(); }
        if (!prefs.edit().clear().commit()) throw new IOException(t("GitHub 연결 해제 정보를 저장하지 못했습니다."));
        Secret.delete();
    }

    private Credentials credentials() throws Exception {
        Config config = loadConfig();
        if (config == null) throw new IOException(t("GitHub 저장소를 먼저 연결해 주세요."));
        return new Credentials(config, accessToken());
    }

    private String accessToken() throws Exception {
        String encrypted = prefs.getString("token", "");
        long expiry = prefs.getLong("expiresAt", 0);
        if (encrypted.isEmpty() || expiry != 0 && System.currentTimeMillis() >= expiry)
            throw new IOException(t("GitHub에 다시 로그인해 주세요."));
        try { return Secret.decrypt(encrypted); }
        catch (Exception error) { throw new IOException(t("GitHub에 다시 로그인해 주세요.")); }
    }

    private Config loadConfig() {
        String repository = prefs.getString("repository", "");
        if (repository.isEmpty()) return null;
        return Client.validateConfig(new Config(repository, prefs.getLong("repositoryId", 0), prefs.getString("branch", "")));
    }

    private void rollbackCommittedLogin() {
        if (committedLoginFlow.isEmpty()) return;
        Map<String, ?> previous = committedPreferences;
        clearCommittedLogin();
        // If restoring the old account cannot be committed, fail closed by
        // removing the newly committed credential instead of leaving it usable.
        if (!restorePreferences(prefs, previous)) prefs.edit().clear().commit();
    }
    private void clearCommittedLogin() {
        committedLoginFlow = "";
        committedPreferences = Map.of();
    }
    @SuppressWarnings("unchecked")
    static Map<String, ?> snapshotPreferences(SharedPreferences source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = Set.copyOf((Set<String>) value);
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }
    @SuppressWarnings("unchecked")
    static boolean restorePreferences(SharedPreferences destination, Map<String, ?> source) {
        SharedPreferences.Editor edit = destination.edit().clear();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) edit.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) edit.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) edit.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) edit.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Set<?>) edit.putStringSet(entry.getKey(), (Set<String>) value);
        }
        return edit.commit();
    }

    private static final class Credentials {
        final Config config;
        final String token;
        Credentials(Config config, String token) { this.config = config; this.token = token; }
    }

    static final class Config {
        final String repository, branch;
        final long repositoryId;
        Config(String repository, long repositoryId, String branch) {
            this.repository = repository; this.repositoryId = repositoryId; this.branch = branch;
        }
    }

    static final class Remote {
        final String sha;
        final PortableProjects bundle;
        Remote(String sha, PortableProjects bundle) { this.sha = sha; this.bundle = bundle; }
    }

    interface Transport {
        JSONObject request(String method, String endpoint, JSONObject body, String token) throws Exception;
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) { super(t("GitHub 요청에 실패했습니다.") + " HTTP " + status); this.status = status; }
    }

    /** Pure protocol layer, testable without Android Keystore or SharedPreferences. */
    static final class Client {
        private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$");
        private static final Pattern SHA = Pattern.compile("^[a-f0-9]{40,64}$");
        private final Transport transport;
        Client(Transport transport) { this.transport = transport; }

        static Config validateConfig(Config config) {
            if (config == null || config.repositoryId < 1 || config.branch == null || config.branch.isBlank()
                    || config.branch.length() > 255 || config.branch.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
                throw new IllegalArgumentException(t("GitHub 동기화 연결 정보가 올바르지 않습니다."));
            repository(config.repository);
            return config;
        }

        Config connect(String repository, String token) throws Exception {
            String requested = repository(repository);
            JSONObject info = transport.request("GET", "repos/" + requested, null, token);
            JSONObject permissions = info.optJSONObject("permissions");
            if (!info.optBoolean("private") || permissions == null || !permissions.optBoolean("push"))
                throw new IOException(t("쓰기 권한이 있는 비공개 GitHub 저장소를 선택해 주세요."));
            long id = info.optLong("id", 0);
            String fullName = info.optString("full_name", ""), branch = info.optString("default_branch", "");
            Config config = validateConfig(new Config(fullName, id, branch));
            read(config, token);
            return config;
        }

        Remote pull(Config config, String token) throws Exception {
            verify(validateConfig(config), token);
            return read(config, token);
        }

        JSONObject push(Config config, String token, String raw) throws Exception {
            validateConfig(config);
            PortableProjects local = PortableProjects.parse(raw, true);
            for (int attempt = 0; attempt < 3; attempt++) {
                JSONObject info = verify(config, token);
                JSONObject permissions = info.optJSONObject("permissions");
                if (permissions == null || !permissions.optBoolean("push"))
                    throw new IOException(t("GitHub 저장소 쓰기 권한이 필요합니다."));
                Remote remote = read(config, token);
                PortableProjects combined = remote.bundle.union(local);
                String combinedRaw = combined.json().toString();
                if (remote.sha != null && combinedRaw.equals(remote.bundle.json().toString()))
                    return Json.obj("changed", false, "eventCount", combined.eventCount());

                JSONObject body = Json.obj("message", "Sync Mobile Codex project identities",
                    "content", Base64.getEncoder().encodeToString(combinedRaw.getBytes(StandardCharsets.UTF_8)));
                if (remote.sha != null) {
                    body.put("sha", remote.sha);
                    body.put("branch", config.branch);
                } else if (info.optLong("size", 0) > 0) {
                    body.put("branch", config.branch);
                }
                try {
                    JSONObject written = transport.request("PUT", "repos/" + config.repository + "/contents/" + FILE, body, token);
                    JSONObject commit = written.optJSONObject("commit");
                    String sha = commit == null ? "" : commit.optString("sha", "");
                    if (!SHA.matcher(sha).matches()) throw new IOException(t("GitHub 동기화 응답이 올바르지 않습니다."));
                    return Json.obj("changed", true, "eventCount", combined.eventCount(), "commit", sha);
                } catch (HttpError error) {
                    if ((error.status != 409 && error.status != 422) || attempt == 2) throw error;
                }
            }
            throw new IOException(t("GitHub 동기화 충돌을 해결하지 못했습니다. 다시 시도해 주세요."));
        }

        /** Commit only the remote snapshot the user reviewed. A race requires a new preview. */
        JSONObject exchange(Config config, String token, String raw, String expectedSha) throws Exception {
            PortableProjects local = PortableProjects.parse(raw, true);
            JSONObject info = verify(validateConfig(config), token);
            Remote remote = read(config, token);
            if (!java.util.Objects.equals(expectedSha, remote.sha))
                throw new IOException(t("원격 프로젝트 정보가 변경되었습니다. 다시 미리보기해 주세요."));
            PortableProjects combined = remote.bundle.union(local);
            String content = combined.json().toString();
            if (local.eventCount() == 0 || content.equals(remote.bundle.json().toString()))
                return Json.obj("changed", false, "eventCount", combined.eventCount());
            JSONObject permissions = info.optJSONObject("permissions");
            if (permissions == null || !permissions.optBoolean("push"))
                throw new IOException(t("GitHub 저장소 쓰기 권한이 필요합니다."));
            JSONObject body = Json.obj("message", "Sync Mobile Codex project identities", "content",
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
            if (remote.sha != null) body.put("sha", remote.sha);
            if (remote.sha != null || info.optLong("size", 0) > 0) body.put("branch", config.branch);
            try {
                JSONObject written = transport.request("PUT", "repos/" + config.repository + "/contents/" + FILE, body, token);
                JSONObject commit = written.optJSONObject("commit");
                if (commit == null || !SHA.matcher(commit.optString("sha", "")).matches())
                    throw new IOException(t("GitHub 동기화 결과를 확인하지 못했습니다. 다시 미리보기해 주세요."));
                return Json.obj("changed", true, "eventCount", combined.eventCount());
            } catch (HttpError error) {
                if (error.status == 409 || error.status == 422)
                    throw new IOException(t("원격 프로젝트 정보가 변경되었습니다. 다시 미리보기해 주세요."));
                throw error;
            }
        }

        private JSONObject verify(Config config, String token) throws Exception {
            JSONObject info = transport.request("GET", "repos/" + config.repository, null, token);
            if (!info.optBoolean("private"))
                throw new IOException(t("프로젝트 동기화 저장소가 공개 상태입니다. 비공개로 바꾸거나 다시 연결해 주세요."));
            if (info.optLong("id", 0) != config.repositoryId || !config.branch.equals(info.optString("default_branch", "")))
                throw new IOException(t("GitHub 저장소 연결 정보가 변경되었습니다. 다시 연결해 주세요."));
            return info;
        }

        private Remote read(Config config, String token) throws Exception {
            JSONObject file;
            try {
                file = transport.request("GET", "repos/" + config.repository + "/contents/" + FILE
                    + "?ref=" + URLEncoder.encode(config.branch, StandardCharsets.UTF_8.name()), null, token);
            } catch (HttpError error) {
                if (error.status == 404) return new Remote(null, new PortableProjects());
                throw error;
            }
            long size = file.optLong("size", -1);
            String sha = file.optString("sha", ""), encoded = file.optString("content", "").replace("\r", "").replace("\n", "");
            if (!"file".equals(file.optString("type")) || !"base64".equals(file.optString("encoding"))
                    || size < 0 || size > PortableProjects.MAX_BYTES || !SHA.matcher(sha).matches())
                throw new IOException(t("GitHub의 프로젝트 동기화 파일이 올바르지 않습니다."));
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException error) { throw new IOException(t("GitHub의 프로젝트 동기화 파일 인코딩이 올바르지 않습니다."), error); }
            if (bytes.length != size || bytes.length > PortableProjects.MAX_BYTES)
                throw new IOException(t("GitHub의 프로젝트 동기화 파일 크기가 올바르지 않습니다."));
            String raw;
            try {
                raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException error) {
                throw new IOException(t("GitHub의 프로젝트 동기화 파일은 UTF-8이어야 합니다."), error);
            }
            return new Remote(sha, PortableProjects.parse(raw, true));
        }

        private static String repository(String repository) {
            String value = repository == null ? "" : repository.trim();
            if (value.length() > 200 || !REPOSITORY.matcher(value).matches() || value.endsWith("/.") || value.endsWith("/.."))
                throw new IllegalArgumentException(t("OWNER/REPO 형식의 GitHub 저장소를 입력해 주세요."));
            return value;
        }
    }

    private static final class HttpTransport implements Transport {
        @Override public JSONObject request(String method, String endpoint, JSONObject body, String token) throws Exception {
            Object response = requestValue(method, endpoint, body, token);
            if (!(response instanceof JSONObject)) throw new IOException(t("GitHub 응답을 읽을 수 없습니다."));
            return (JSONObject) response;
        }
        Object requestValue(String method, String endpoint, JSONObject body, String token) throws Exception {
            if (endpoint == null || endpoint.startsWith("/") || endpoint.contains("://"))
                throw new IllegalArgumentException("Invalid GitHub endpoint");
            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.github.com/" + endpoint).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "Mobile-Codex");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try {
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_HTTP_BYTES) throw new IOException(t("GitHub 요청 크기 제한을 초과했습니다."));
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                byte[] response = readBounded(stream, MAX_HTTP_BYTES);
                if (status < 200 || status >= 300) throw new HttpError(status);
                String raw = decode(response);
                return raw.isBlank() ? new JSONObject() : raw.stripLeading().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            } finally {
                connection.disconnect();
            }
        }

        private static byte[] readBounded(InputStream in, int max) throws IOException {
            if (in == null) return new byte[0];
            try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = source.read(buffer)) != -1) {
                    if (out.size() + count > max) throw new IOException(t("GitHub 응답 크기 제한을 초과했습니다."));
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        }

        private static String decode(byte[] bytes) throws IOException {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException error) {
                throw new IOException(t("GitHub 응답을 읽을 수 없습니다."), error);
            }
        }
    }

    private static final class Secret {
        private static SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            java.security.Key existing = store.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
            return generator.generateKey();
        }

        static String encrypt(String plain) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            return "v1:" + Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
                + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        }

        static String decrypt(String stored) throws Exception {
            String[] parts = stored.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new GeneralSecurityException("Unsupported secret format");
            byte[] iv = Base64.getDecoder().decode(parts[1]), data = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return decodeSecret(cipher.doFinal(data));
        }

        private static String decodeSecret(byte[] data) throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        }

        static void delete() {
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            } catch (Exception ignored) { }
        }
    }
}
