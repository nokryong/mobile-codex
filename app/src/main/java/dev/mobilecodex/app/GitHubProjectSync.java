package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import dev.mobilecodex.app.core.Json;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.util.Base64;
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
final class GitHubProjectSync {
    private static final String FILE = "projects.json";
    private static final String PREFS = "project-sync-github";
    private static final String KEY_ALIAS = "mobile-codex-project-sync-v1";
    private static final int MAX_HTTP_BYTES = PortableProjects.MAX_BYTES * 3 + 65536;
    private final SharedPreferences prefs;
    private final Client client;

    GitHubProjectSync(Context context) {
        prefs = context.getSharedPreferences(PREFS, 0);
        client = new Client(new HttpTransport());
    }

    synchronized JSONObject status() {
        try {
            Config config = loadConfig();
            return config == null ? Json.obj("connected", false)
                : Json.obj("connected", true, "repository", config.repository, "branch", config.branch);
        } catch (Exception ignored) {
            return Json.obj("connected", false);
        }
    }

    synchronized JSONObject connect(String repository, String token) throws Exception {
        String cleanToken = token == null ? "" : token.trim();
        if (cleanToken.isEmpty() || cleanToken.length() > 1024 || cleanToken.indexOf('\0') >= 0
                || cleanToken.indexOf('\r') >= 0 || cleanToken.indexOf('\n') >= 0)
            throw new IllegalArgumentException(t("GitHub 토큰을 확인해 주세요."));
        Config config = client.connect(repository, cleanToken);
        String encrypted = Secret.encrypt(cleanToken);
        if (!prefs.edit().putInt("schemaVersion", 1).putString("repository", config.repository)
                .putLong("repositoryId", config.repositoryId).putString("branch", config.branch)
                .putString("token", encrypted).commit())
            throw new IOException(t("GitHub 연결 정보를 저장하지 못했습니다."));
        return status();
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

    synchronized void disconnect() throws IOException {
        if (!prefs.edit().clear().commit()) throw new IOException(t("GitHub 연결 해제 정보를 저장하지 못했습니다."));
        Secret.delete();
    }

    private Credentials credentials() throws Exception {
        Config config = loadConfig();
        if (config == null) throw new IOException(t("GitHub 저장소를 먼저 연결해 주세요."));
        String encrypted = prefs.getString("token", "");
        if (encrypted.isEmpty()) throw new IOException(t("GitHub 저장소를 다시 연결해 주세요."));
        try {
            return new Credentials(config, Secret.decrypt(encrypted));
        } catch (Exception error) {
            throw new IOException(t("GitHub 토큰을 열 수 없습니다. 저장소를 다시 연결해 주세요."), error);
        }
    }

    private Config loadConfig() {
        String repository = prefs.getString("repository", "");
        if (repository.isEmpty()) return null;
        return Client.validateConfig(new Config(repository, prefs.getLong("repositoryId", 0), prefs.getString("branch", "")));
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
                return raw.isBlank() ? new JSONObject() : new JSONObject(raw);
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
