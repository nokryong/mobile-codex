package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** App-private ChatGPT credential profiles. Secret values are never returned to the UI. */
final class AccountProfiles {
    private static final String AUTH = "auth.json";
    private final File liveAuth, root, profilesRoot, activeFile;

    AccountProfiles(File codexHome, File filesDir) throws IOException {
        liveAuth = child(codexHome, AUTH);
        root = child(filesDir, "account-profiles");
        profilesRoot = child(root, "profiles");
        activeFile = child(root, "active");
        ensureDirectory(profilesRoot);
        restoreActiveIfMissing();
    }

    String activeKey() {
        try {
            String key = new String(Files.readAllBytes(activeFile.toPath()), StandardCharsets.UTF_8).trim();
            return validKey(key) ? key : "";
        } catch (Exception ignored) { return ""; }
    }

    JSONArray list() {
        JSONArray result = new JSONArray();
        String active = activeKey();
        File[] entries = profilesRoot.listFiles(File::isDirectory);
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File directory : entries) {
            if (!validKey(directory.getName()) || !new File(directory, AUTH).isFile()) continue;
            JSONObject metadata = readMetadata(directory);
            result.put(new JSONObject()
                .put("key", directory.getName())
                .put("email", metadata.optString("email"))
                .put("planType", metadata.optString("planType"))
                .put("label", label(metadata, directory.getName()))
                .put("active", directory.getName().equals(active)));
        }
        return result;
    }

    JSONObject saveCurrent(JSONObject account) throws IOException {
        if (!liveAuth.isFile()) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        JSONObject auth = readJson(liveAuth);
        Identity identity = identity(auth, account);
        if (!identity.hasAuth) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        String matching = matchingKey(identity);
        String key = matching.isEmpty() ? uniqueKey(identity) : matching;
        File directory = profileDirectory(key);
        ensureDirectory(directory);
        copyAtomic(liveAuth, new File(directory, AUTH));
        JSONObject metadata = new JSONObject()
            .put("email", identity.email)
            .put("planType", identity.planType)
            .put("accountId", identity.accountId)
            .put("subject", identity.subject)
            .put("updatedAt", System.currentTimeMillis());
        writeAtomic(new File(directory, "profile.json"), metadata.toString());
        writeAtomic(activeFile, key + "\n");
        return publicProfile(key, metadata, true);
    }

    void switchTo(String key) throws IOException {
        File source = new File(profileDirectory(requireKey(key)), AUTH);
        if (!source.isFile()) throw new IOException(t("저장된 계정을 찾지 못했습니다."));
        File backup = child(root, ".live-auth-backup");
        if (liveAuth.isFile()) copyAtomic(liveAuth, backup); else Files.deleteIfExists(backup.toPath());
        try {
            copyAtomic(source, liveAuth);
            writeAtomic(activeFile, key + "\n");
            Files.deleteIfExists(backup.toPath());
        } catch (Exception error) {
            if (backup.isFile()) copyAtomic(backup, liveAuth);
            throw error instanceof IOException value ? value : new IOException(t("계정을 전환하지 못했습니다."), error);
        } finally { Files.deleteIfExists(backup.toPath()); }
    }

    JSONObject delete(String key) throws IOException {
        key = requireKey(key);
        if (key.equals(activeKey())) throw new IOException(t("현재 사용 중인 계정은 먼저 로그아웃해 주세요."));
        File directory = profileDirectory(key);
        if (!directory.isDirectory()) throw new IOException(t("저장된 계정을 찾지 못했습니다."));
        JSONObject metadata = readMetadata(directory);
        removeTree(directory);
        return publicProfile(key, metadata, false);
    }

    void removeActiveProfile() throws IOException {
        String key = activeKey();
        Files.deleteIfExists(activeFile.toPath());
        if (!key.isEmpty()) removeTree(profileDirectory(key));
    }

    private void restoreActiveIfMissing() throws IOException {
        String key = activeKey();
        if (liveAuth.isFile() || key.isEmpty()) return;
        File source = new File(profileDirectory(key), AUTH);
        if (source.isFile()) copyAtomic(source, liveAuth);
    }

    private JSONObject publicProfile(String key, JSONObject metadata, boolean active) {
        return new JSONObject().put("key", key).put("email", metadata.optString("email"))
            .put("planType", metadata.optString("planType")).put("label", label(metadata, key)).put("active", active);
    }

    private String matchingKey(Identity target) {
        File[] entries = profilesRoot.listFiles(File::isDirectory);
        if (entries == null) return "";
        for (File directory : entries) {
            JSONObject metadata = readMetadata(directory);
            Identity stored = new Identity(true, metadata.optString("email"), metadata.optString("planType"),
                metadata.optString("accountId"), metadata.optString("subject"));
            if (sameIdentity(target, stored)) return directory.getName();
        }
        return "";
    }

    private static boolean sameIdentity(Identity a, Identity b) {
        if (!a.accountId.isEmpty() && !b.accountId.isEmpty()) {
            if (!a.accountId.equals(b.accountId)) return false;
            if (!a.subject.isEmpty() && !b.subject.isEmpty()) return a.subject.equals(b.subject);
            return !a.email.isEmpty() && a.email.equalsIgnoreCase(b.email);
        }
        if (!a.subject.isEmpty() && !b.subject.isEmpty()) return a.subject.equals(b.subject);
        return !a.email.isEmpty() && a.email.equalsIgnoreCase(b.email) && a.planType.equals(b.planType);
    }

    private String uniqueKey(Identity identity) throws IOException {
        String seed = !identity.subject.isEmpty() || !identity.accountId.isEmpty()
            ? identity.accountId + "|" + identity.subject + "|" + identity.email
            : identity.email + "|" + identity.planType;
        if (seed.replace("|", "").isEmpty()) seed = UUID.randomUUID().toString();
        String base = "account-" + sha256(seed).substring(0, 16), key = base;
        int suffix = 2;
        while (profileDirectory(key).exists()) key = base + "-" + suffix++;
        return key;
    }

    private Identity identity(JSONObject auth, JSONObject account) {
        JSONObject tokens = auth.optJSONObject("tokens");
        String access = tokens == null ? auth.optString("access_token") : tokens.optString("access_token", auth.optString("access_token"));
        JSONObject idClaims = jwt(tokens == null ? auth.optString("id_token") : tokens.optString("id_token"));
        JSONObject accessClaims = jwt(access);
        JSONObject authClaims = idClaims.optJSONObject("https://api.openai.com/auth");
        if (authClaims == null) authClaims = accessClaims.optJSONObject("https://api.openai.com/auth");
        if (authClaims == null) authClaims = new JSONObject();
        String email = first(idClaims.optString("email"), accessClaims.optString("email"), account.optString("email"));
        String plan = first(authClaims.optString("chatgpt_plan_type"), auth.optString("plan_type"), account.optString("planType"));
        String accountId = first(tokens == null ? "" : tokens.optString("account_id"), auth.optString("account_id"),
            authClaims.optString("chatgpt_account_id"), accessClaims.optString("chatgpt_account_id"));
        String subject = first(idClaims.optString("sub"), accessClaims.optString("sub"));
        return new Identity(!access.isEmpty(), email, plan, accountId, subject);
    }

    private static JSONObject jwt(String token) {
        if (token == null || token.chars().filter(ch -> ch == '.').count() < 2) return new JSONObject();
        try {
            String part = token.split("\\.", -1)[1];
            byte[] decoded = Base64.getUrlDecoder().decode(part);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String label(JSONObject metadata, String fallback) {
        String email = metadata.optString("email"), plan = metadata.optString("planType");
        String base = email.isBlank() ? fallback : email;
        return plan.isBlank() ? base : base + " (" + plan + ")";
    }
    private static JSONObject readJson(File file) throws IOException {
        try { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException(t("로그인 정보를 읽지 못했습니다."), e); }
    }
    private static JSONObject readMetadata(File directory) {
        try { return new JSONObject(new String(Files.readAllBytes(new File(directory, "profile.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    private File profileDirectory(String key) throws IOException { return child(profilesRoot, requireKey(key)); }
    private static String requireKey(String key) throws IOException {
        if (!validKey(key)) throw new IOException(t("계정 식별자가 올바르지 않습니다."));
        return key;
    }
    private static boolean validKey(String key) { return key != null && key.matches("[a-z0-9][a-z0-9._-]{0,79}"); }
    private static File child(File parent, String name) throws IOException {
        File root = parent.getCanonicalFile(), value = new File(root, name).getCanonicalFile();
        if (!root.equals(value.getParentFile())) throw new IOException(t("계정 저장 경로가 올바르지 않습니다."));
        return value;
    }
    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(t("계정 저장 폴더를 만들지 못했습니다."));
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false);
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true);
    }
    private static void copyAtomic(File source, File destination) throws IOException {
        ensureDirectory(destination.getParentFile());
        File pending = new File(destination.getParentFile(), "." + destination.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(source.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pending.setReadable(false, false); pending.setWritable(false, false); pending.setReadable(true, true); pending.setWritable(true, true);
            moveAtomic(pending, destination);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    private static void writeAtomic(File destination, String value) throws IOException {
        ensureDirectory(destination.getParentFile());
        File pending = new File(destination.getParentFile(), "." + destination.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(pending.toPath(), value.getBytes(StandardCharsets.UTF_8));
            pending.setReadable(false, false); pending.setWritable(false, false); pending.setReadable(true, true); pending.setWritable(true, true);
            moveAtomic(pending, destination);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    private static void moveAtomic(File source, File destination) throws IOException {
        try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b)); return result.toString();
        } catch (Exception e) { throw new IOException(e); }
    }
    private static void removeTree(File value) throws IOException {
        if (!value.exists()) return;
        if (Files.isSymbolicLink(value.toPath())) { Files.deleteIfExists(value.toPath()); return; }
        File[] children = value.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        Files.deleteIfExists(value.toPath());
    }
    private record Identity(boolean hasAuth, String email, String planType, String accountId, String subject) {}
}
