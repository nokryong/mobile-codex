package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class AccountProfilesTest {
    private Context context;
    private File codexHome, auth, store;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication(); codexHome = new File(context.getFilesDir(), ".codex");
        auth = new File(codexHome, "auth.json"); store = new File(context.getFilesDir(), "account-profiles");
        remove(store); Files.deleteIfExists(auth.toPath()); codexHome.mkdirs();
    }
    @After public void after() throws Exception { remove(store); Files.deleteIfExists(auth.toPath()); }

    @Test public void profilesSwitchAtomicallyAndExposeNoTokens() throws Exception {
        writeAuth("one@example.test", "subject-one", "workspace-one", "secret-one");
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        JSONObject first = profiles.saveCurrent(new JSONObject().put("email", "one@example.test").put("planType", "pro"));
        String firstKey = first.getString("key");
        writeAuth("one@example.test", "subject-one", "workspace-one", "rotated-secret");
        assertEquals(firstKey, profiles.saveCurrent(new JSONObject().put("email", "one@example.test").put("planType", "pro")).getString("key"));

        writeAuth("two@example.test", "subject-two", "workspace-two", "secret-two");
        String secondKey = profiles.saveCurrent(new JSONObject().put("email", "two@example.test").put("planType", "plus")).getString("key");
        JSONArray listed = profiles.list(); assertEquals(2, listed.length());
        assertFalse(listed.toString().contains("secret-one")); assertFalse(listed.toString().contains("secret-two"));
        profiles.switchTo(firstKey);
        assertTrue(accessToken().endsWith(".rotated-secret"));
        assertEquals(firstKey, profiles.activeKey());
        assertEquals(secondKey, profiles.delete(secondKey).getString("key"));
        assertEquals(1, profiles.list().length());
    }

    @Test public void activeProfileRestoresAfterInterruptedAddLogin() throws Exception {
        writeAuth("restore@example.test", "subject", "workspace", "secret");
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        String key = profiles.saveCurrent(new JSONObject().put("email", "restore@example.test")).getString("key");
        Files.delete(auth.toPath());
        AccountProfiles reopened = new AccountProfiles(codexHome, context.getFilesDir());
        assertEquals(key, reopened.activeKey()); assertTrue(auth.isFile());
        assertTrue(accessToken().endsWith(".secret"));
    }

    @Test public void addLoginPreparationIsolatesAuthWithoutChangingActiveProfile() throws Exception {
        writeAuth("restore@example.test", "subject", "workspace", "secret");
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        String key = profiles.saveCurrent(new JSONObject().put("email", "restore@example.test")).getString("key");
        writeAuth("restore@example.test", "subject", "workspace", "rotated-secret");
        byte[] latest = Files.readAllBytes(auth.toPath());
        profiles.prepareAddLogin(new JSONObject().put("email", "restore@example.test"));
        assertTrue(auth.isFile());
        assertEquals(key, profiles.activeKey());
        assertTrue(profiles.hasPendingAddLogin());
        assertNotNull(profiles.pendingLoginHome());
        assertFalse(new File(profiles.pendingLoginHome(), "auth.json").isFile());
        AccountProfiles reopened = new AccountProfiles(codexHome, context.getFilesDir());
        assertTrue(auth.isFile());
        assertEquals(key, reopened.activeKey());
        assertFalse(reopened.hasPendingAddLogin());
        assertArrayEquals(latest, Files.readAllBytes(auth.toPath()));
        assertArrayEquals(latest, Files.readAllBytes(new File(store, "profiles/" + key + "/auth.json").toPath()));
    }

    @Test public void missingLiveAuthUsesTheMarkedSnapshotWithoutReidentifyingIt() throws Exception {
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        // An opaque saved login has no claims to match against a later account/read result.
        Files.write(auth.toPath(), new JSONObject().put("access_token", "opaque-test-value").toString().getBytes(StandardCharsets.UTF_8));
        String active = profiles.saveCurrent(new JSONObject()).getString("key");
        writeAuth("other@example.test", "other", "other-workspace", "other-secret");
        JSONObject otherAccount = new JSONObject().put("email", "other@example.test").put("planType", "pro");
        String other = profiles.saveCurrent(otherAccount).getString("key");
        profiles.switchTo(active);
        profiles.markNeedsLogin(active);
        File saved = new File(store, "profiles/" + active + "/auth.json");
        File metadata = new File(saved.getParentFile(), "profile.json");
        File otherSaved = new File(store, "profiles/" + other + "/auth.json");
        byte[] original = Files.readAllBytes(saved.toPath()), otherOriginal = Files.readAllBytes(otherSaved.toPath());
        byte[] marked = Files.readAllBytes(metadata.toPath());
        Files.delete(auth.toPath());

        assertEquals(active, profiles.prepareAddLogin(otherAccount).getString("key"));
        assertEquals(active, profiles.activeKey());
        assertFalse(new File(profiles.pendingLoginHome(), "auth.json").exists());
        profiles.restorePreparedAddLogin();
        assertFalse(profiles.hasPendingAddLogin());
        assertArrayEquals(marked, Files.readAllBytes(metadata.toPath()));
        assertArrayEquals(original, Files.readAllBytes(auth.toPath()));
        assertArrayEquals(original, Files.readAllBytes(saved.toPath()));
        assertArrayEquals(otherOriginal, Files.readAllBytes(otherSaved.toPath()));
    }

    @Test public void unavailableActiveSnapshotNeverFallsBackToAnotherProfile() throws Exception {
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        writeAuth("other@example.test", "other", "one", "other-secret");
        String other = profiles.saveCurrent(new JSONObject()).getString("key");
        writeAuth("active@example.test", "active", "two", "active-secret");
        String active = profiles.saveCurrent(new JSONObject()).getString("key");
        File saved = new File(store, "profiles/" + active + "/auth.json");
        File otherSaved = new File(store, "profiles/" + other + "/auth.json");
        byte[] otherOriginal = Files.readAllBytes(otherSaved.toPath());
        Files.delete(auth.toPath());
        Files.createDirectory(auth.toPath());
        assertThrows(java.io.IOException.class, () -> profiles.prepareAddLogin(new JSONObject()));
        assertTrue(auth.isDirectory());
        Files.delete(auth.toPath());
        Files.delete(saved.toPath());
        assertThrows(java.io.IOException.class, () -> profiles.prepareAddLogin(new JSONObject()));
        Files.createDirectory(saved.toPath());
        assertThrows(java.io.IOException.class, () -> profiles.prepareAddLogin(new JSONObject()));
        assertFalse(auth.exists());
        assertFalse(profiles.hasPendingAddLogin());
        assertEquals(active, profiles.activeKey());
        assertArrayEquals(otherOriginal, Files.readAllBytes(otherSaved.toPath()));
    }

    @Test public void stagedSwitchDoesNotPublishUntilCommitAndRollbackRestoresLiveAuth() throws Exception {
        writeAuth("one@example.test", "subject-one", "workspace-one", "secret-one");
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        String first = profiles.saveCurrent(new JSONObject().put("email", "one@example.test")).getString("key");
        writeAuth("two@example.test", "subject-two", "workspace-two", "secret-two");
        String second = profiles.saveCurrent(new JSONObject().put("email", "two@example.test")).getString("key");
        profiles.switchTo(first);
        profiles.stageSwitch(second);
        assertEquals(first, profiles.activeKey());
        assertTrue(accessToken().endsWith(".secret-two"));
        profiles.rollbackStagedSwitch();
        assertEquals(first, profiles.activeKey());
        assertTrue(accessToken().endsWith(".secret-one"));
        profiles.stageSwitch(second); profiles.commitStagedSwitch(second);
        assertEquals(second, profiles.activeKey());
        assertTrue(accessToken().endsWith(".secret-two"));
    }

    @Test public void rejectsTraversalAndCurrentProfileDeletion() throws Exception {
        writeAuth("safe@example.test", "subject", "workspace", "secret");
        AccountProfiles profiles = new AccountProfiles(codexHome, context.getFilesDir());
        String key = profiles.saveCurrent(new JSONObject().put("email", "safe@example.test")).getString("key");
        try { profiles.switchTo("../auth"); fail("traversal must fail"); } catch (java.io.IOException expected) { }
        try { profiles.delete(key); fail("active deletion must fail"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("로그아웃")); }
    }

    private void writeAuth(String email, String subject, String accountId, String secret) throws Exception {
        JSONObject claims = new JSONObject().put("email", email).put("sub", subject)
            .put("https://api.openai.com/auth", new JSONObject().put("chatgpt_account_id", accountId).put("chatgpt_plan_type", "pro"));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject value = new JSONObject().put("tokens", new JSONObject().put("id_token", "x." + encoded + ".y")
            .put("access_token", "x." + Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "." + secret)
            .put("account_id", accountId));
        Files.write(auth.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String accessToken() throws Exception {
        JSONObject value = new JSONObject(new String(Files.readAllBytes(auth.toPath()), StandardCharsets.UTF_8));
        return value.getJSONObject("tokens").getString("access_token");
    }
    private static void remove(File value) {
        File[] children = value.listFiles(); if (children != null) for (File child : children) remove(child);
        value.delete();
    }
}
