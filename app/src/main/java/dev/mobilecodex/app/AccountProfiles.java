package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import static dev.mobilecodex.app.core.Json.obj;
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
    private static final String ADD_LOGIN_MARKER = ".add-login.json";
    private static final String SWITCH_MARKER = ".switch.json";
    private static final String SWITCH_BACKUP = ".switch-auth-backup";
    private final File liveAuth, root, profilesRoot, activeFile;

    AccountProfiles(File codexHome, File filesDir) throws IOException {
        liveAuth = child(codexHome, AUTH);
        root = child(filesDir, "account-profiles");
        profilesRoot = child(root, "profiles");
        activeFile = child(root, "active");
        ensureDirectory(profilesRoot);
        recoverInterruptedSwitch();
        recoverInterruptedAddLogin();
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
            result.put(obj("key", directory.getName(),
                "email", metadata.optString("email"),
                "planType", metadata.optString("planType"),
                "needsLogin", metadata.optBoolean("needsLogin", false),
                "label", label(metadata, directory.getName()),
                "active", directory.getName().equals(active)));
        }
        return result;
    }

    JSONObject saveCurrent(JSONObject account) throws IOException { return saveCurrent(account, true); }

    /** Snapshot the live credentials without changing the active-profile marker. */
    JSONObject saveCurrentSnapshot(JSONObject account) throws IOException { return saveCurrent(account, false); }

    /** Snapshot before add-login while preserving a prior re-login-needed flag. */
    JSONObject saveCurrentForAddLogin(JSONObject account) throws IOException {
        String key = activeKey();
        boolean needsLogin = validKey(key) && readMetadata(profileDirectory(key)).optBoolean("needsLogin", false);
        JSONObject profile = saveCurrent(account, true);
        if (needsLogin) markNeedsLogin(profile.optString("key"));
        return profile;
    }

    private JSONObject saveCurrent(JSONObject account, boolean activate) throws IOException {
        File source = authSource();
        if (!source.isFile()) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        JSONObject auth = readJson(source);
        JSONObject effectiveAccount;
        try { effectiveAccount = account == null ? new JSONObject() : new JSONObject(account.toString()); }
        catch (Exception error) { throw new IOException(t("계정 정보를 준비하지 못했습니다."), error); }
        String active = activeKey();
        if (validKey(active)) {
            JSONObject previous = readMetadata(profileDirectory(active));
            try {
                if (effectiveAccount.optString("email").isBlank() && !previous.optString("email").isBlank()) effectiveAccount.put("email", previous.optString("email"));
                if (effectiveAccount.optString("planType").isBlank() && !previous.optString("planType").isBlank()) effectiveAccount.put("planType", previous.optString("planType"));
                if (effectiveAccount.optString("accountId").isBlank() && !previous.optString("accountId").isBlank()) effectiveAccount.put("accountId", previous.optString("accountId"));
            } catch (Exception error) { throw new IOException(t("계정 정보를 준비하지 못했습니다."), error); }
        }
        Identity identity = identity(auth, effectiveAccount);
        if (!identity.hasAuth) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        String matching = matchingKey(identity);
        String key = matching.isEmpty() ? uniqueKey(identity) : matching;
        File directory = profileDirectory(key);
        ensureDirectory(directory);
        copyAtomic(source, new File(directory, AUTH));
        JSONObject metadata = obj("email", identity.email,
            "planType", identity.planType,
            "accountId", identity.accountId,
            "subject", identity.subject,
            "needsLogin", false,
            "updatedAt", System.currentTimeMillis());
        writeAtomic(new File(directory, "profile.json"), metadata.toString());
        if (activate) writeAtomic(activeFile, key + "\n");
        return publicProfile(key, metadata, activate && key.equals(activeKey()));
    }

    /**
     * Start an isolated login transaction. The existing auth remains in the
     * primary CODEX_HOME; Engine launches the login app-server with the unique
     * home returned by pendingLoginHome(), so account/login cannot revoke or
     * overwrite another profile's credential.
     */
    JSONObject prepareAddLogin(JSONObject account) throws IOException {
        return prepareAddLogin(account, false);
    }

    /** Prepare after the caller has already snapshotted live auth. */
    JSONObject prepareAddLogin(JSONObject account, boolean alreadySnapshotted) throws IOException {
        if (!liveAuth.isFile()) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        String previousKey = activeKey();
        boolean previouslyNeedsLogin = validKey(previousKey) && readMetadata(profileDirectory(previousKey)).optBoolean("needsLogin", false);
        JSONObject profile = alreadySnapshotted
            ? publicProfile(previousKey, readMetadata(profileDirectory(previousKey)), true)
            : saveCurrent(account, true);
        String key = profile.optString("key");
        if (key.isBlank()) throw new IOException(t("현재 로그인 정보를 찾지 못했습니다."));
        if (previouslyNeedsLogin) markNeedsLogin(key);
        String homeName = ".pending-login-" + UUID.randomUUID();
        File home = child(root, homeName);
        ensureDirectory(home);
        try { writeAtomic(child(root, ADD_LOGIN_MARKER), obj("phase", "prepared", "restoreKey", key, "home", homeName).toString()); }
        catch (Exception error) {
            removeTree(home);
            throw error instanceof IOException value ? value : new IOException(t("새 로그인 준비에 실패했습니다."), error);
        }
        return profile;
    }

    /** Unique CODEX_HOME for the in-progress login, or null when none exists. */
    File pendingLoginHome() throws IOException {
        File marker = child(root, ADD_LOGIN_MARKER);
        if (!marker.isFile()) return null;
        String name = readJson(marker).optString("home");
        if (name.isBlank()) return null;
        File home = child(root, name);
        return home.isDirectory() ? home : null;
    }

    /** Mark a successful device login before its new credentials are saved. */
    void markAddLoginCompleting() throws IOException {
        File marker = child(root, ADD_LOGIN_MARKER);
        if (!marker.isFile()) return;
        JSONObject state = readJson(marker);
        try { state.put("phase", "completing"); }
        catch (Exception error) { throw new IOException(t("로그인 상태를 저장하지 못했습니다."), error); }
        writeAtomic(marker, state.toString());
    }

    /** Save the newly authenticated account and remove the old-login recovery state. */
    JSONObject finishAddLogin(JSONObject account) throws IOException {
        File pendingAuth = pendingAuth();
        if (!pendingAuth.isFile()) throw new IOException(t("새 로그인 정보를 찾지 못했습니다."));
        copyAtomic(pendingAuth, liveAuth);
        JSONObject profile = saveCurrent(account, true);
        removeAddLoginFiles();
        return profile;
    }

    boolean hasPendingAddLogin() { return new File(root, ADD_LOGIN_MARKER).isFile(); }

    boolean isAddLoginCompleting() {
        try { return hasPendingAddLogin() && "completing".equals(readJson(child(root, ADD_LOGIN_MARKER)).optString("phase")); }
        catch (Exception ignored) { return false; }
    }

    /** Restore the previous account after cancellation or a failed login. */
    void restorePreparedAddLogin() throws IOException {
        restorePreparedAddLogin(false);
    }

    /**
     * Restore after a failed login in the current process. A forced restore is
     * required for a completing marker because the new login was unusable.
     */
    void restorePreparedAddLogin(boolean force) throws IOException {
        File marker = child(root, ADD_LOGIN_MARKER);
        if (!marker.isFile()) return;
        JSONObject state = readJson(marker);
        if (!force && "completing".equals(state.optString("phase"))) return;
        String key = state.optString("restoreKey");
        if (validKey(key)) writeAtomic(activeFile, key + "\n");
        removeAddLoginFiles();
    }

    /** Stage a target auth file without publishing the target as active. */
    void stageSwitch(String key) throws IOException {
        key = requireKey(key);
        String previous = activeKey();
        if (key.equals(previous)) return;
        File source = new File(profileDirectory(key), AUTH);
        if (!source.isFile()) throw new IOException(t("저장된 계정을 찾지 못했습니다."));
        File marker = child(root, SWITCH_MARKER), backup = child(root, SWITCH_BACKUP);
        if (marker.isFile()) throw new IOException(t("다른 계정 전환이 진행 중입니다."));
        if (liveAuth.isFile()) copyAtomic(liveAuth, backup); else Files.deleteIfExists(backup.toPath());
        writeAtomic(marker, obj("previousKey", previous, "targetKey", key).toString());
        try { copyAtomic(source, liveAuth); }
        catch (Exception error) {
            if (backup.isFile()) copyAtomic(backup, liveAuth); else Files.deleteIfExists(liveAuth.toPath());
            Files.deleteIfExists(marker.toPath()); Files.deleteIfExists(backup.toPath());
            throw error instanceof IOException value ? value : new IOException(t("계정을 전환하지 못했습니다."), error);
        }
    }

    void commitStagedSwitch(String key) throws IOException {
        File marker = child(root, SWITCH_MARKER);
        if (!marker.isFile()) throw new IOException(t("계정 전환 준비가 없습니다."));
        JSONObject state = readJson(marker);
        if (!key.equals(state.optString("targetKey"))) throw new IOException(t("계정 전환 대상이 바뀌었습니다."));
        try { state.put("phase", "committing"); }
        catch (Exception error) { throw new IOException(t("계정 전환 상태를 저장하지 못했습니다."), error); }
        writeAtomic(marker, state.toString());
        writeAtomic(activeFile, key + "\n");
        Files.deleteIfExists(child(root, SWITCH_BACKUP).toPath());
        Files.deleteIfExists(marker.toPath());
    }

    void rollbackStagedSwitch() throws IOException {
        File marker = child(root, SWITCH_MARKER);
        if (!marker.isFile()) return;
        File backup = child(root, SWITCH_BACKUP);
        if (backup.isFile()) copyAtomic(backup, liveAuth); else Files.deleteIfExists(liveAuth.toPath());
        Files.deleteIfExists(backup.toPath()); Files.deleteIfExists(marker.toPath());
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

    void markNeedsLogin(String key) throws IOException {
        key = requireKey(key);
        File directory = profileDirectory(key), metadataFile = new File(directory, "profile.json");
        if (!directory.isDirectory()) return;
        JSONObject metadata = readMetadata(directory);
        try { metadata.put("needsLogin", true); }
        catch (Exception error) { throw new IOException(t("계정 상태를 저장하지 못했습니다."), error); }
        writeAtomic(metadataFile, metadata.toString());
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

    private void recoverInterruptedSwitch() throws IOException {
        File marker = child(root, SWITCH_MARKER);
        if (!marker.isFile()) return;
        JSONObject state = readJson(marker);
        File backup = child(root, SWITCH_BACKUP);
        if ("committing".equals(state.optString("phase")) && state.optString("targetKey").equals(activeKey())) {
            Files.deleteIfExists(backup.toPath()); Files.deleteIfExists(marker.toPath());
            return;
        }
        if (backup.isFile()) copyAtomic(backup, liveAuth);
        Files.deleteIfExists(backup.toPath()); Files.deleteIfExists(marker.toPath());
    }

    private void recoverInterruptedAddLogin() throws IOException {
        File marker = child(root, ADD_LOGIN_MARKER);
        if (!marker.isFile()) return;
        JSONObject state = readJson(marker);
        File pending = pendingHome(state);
        File pendingAuth = pending == null ? null : new File(pending, AUTH);
        // The completing phase is the durable success boundary. Promote a
        // successful isolated login after a crash; an uncompleted flow is
        // discarded while the primary auth remains untouched.
        if ("completing".equals(state.optString("phase")) && pendingAuth != null && pendingAuth.isFile())
            copyAtomic(pendingAuth, liveAuth);
        removeAddLoginFiles();
    }

    private void removeAddLoginFiles() throws IOException {
        File pending = pendingLoginHome();
        Files.deleteIfExists(child(root, ADD_LOGIN_MARKER).toPath());
        if (pending != null) removeTree(pending);
    }

    private File authSource() throws IOException {
        File home = pendingLoginHome();
        File pending = home == null ? null : new File(home, AUTH);
        return pending != null && pending.isFile() ? pending : liveAuth;
    }

    private File pendingAuth() throws IOException {
        File home = pendingLoginHome();
        return home == null ? new File(root, ".missing-pending-auth") : new File(home, AUTH);
    }

    private File pendingHome(JSONObject state) throws IOException {
        String name = state.optString("home");
        if (name.isBlank()) return null;
        return child(root, name);
    }

    private JSONObject publicProfile(String key, JSONObject metadata, boolean active) {
        return obj("key", key, "email", metadata.optString("email"),
            "planType", metadata.optString("planType"), "label", label(metadata, key), "active", active);
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
