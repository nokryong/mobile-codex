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
        assertTrue(Files.readString(auth.toPath()).contains("one@example.test"));
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
        assertTrue(Files.readString(auth.toPath()).contains("restore@example.test"));
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
        Files.writeString(auth.toPath(), value.toString(), StandardCharsets.UTF_8);
    }
    private static void remove(File value) {
        File[] children = value.listFiles(); if (children != null) for (File child : children) remove(child);
        value.delete();
    }
}
