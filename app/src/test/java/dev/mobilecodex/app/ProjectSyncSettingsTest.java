package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.io.IOException;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class ProjectSyncSettingsTest {
    private DocumentStore documents;
    private Context context;
    private FakeRemote remote;
    private ProjectSyncSettings sync;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        documents = new DocumentStore(context);
        remote = new FakeRemote();
        sync = new ProjectSyncSettings(remote, documents);
    }
    private String project(String name) throws Exception {
        documents.createProject(name);
        JSONArray projects = documents.projects();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject project = projects.getJSONObject(i);
            if (name.equals(project.getString("name"))) return project.getString("key");
        }
        throw new AssertionError("Missing project");
    }
    private JSONObject preview(String... keys) throws Exception {
        return sync.handle("sync.preview", obj("keys", new JSONArray(java.util.Arrays.asList(keys))));
    }
    private JSONObject apply(JSONObject preview) throws Exception {
        return sync.handle("sync.apply", obj("token", preview.getString("token")));
    }
    @Test public void previewDoesNotImportOrUploadAndApplyExchangesOnlySelectedProjects() throws Exception {
        String selected = project("Selected"), privateProject = project("Local only");
        String before = documents.projects().toString();
        JSONObject preview = preview(selected);
        assertEquals(before, documents.projects().toString());
        assertEquals(0, remote.writes);
        assertEquals(1, preview.getInt("uploadCount"));
        assertEquals(1, preview.getInt("addedEvents"));
        JSONObject result = apply(preview);
        assertEquals(1, remote.writes);
        assertEquals(3, result.getJSONArray("projects").length());
        assertTrue(remote.bundle.projectIds().contains(documents.projectId(selected)));
        assertFalse(remote.bundle.projectIds().contains(documents.projectId(privateProject)));
        assertEquals(1, result.getLong("lastSynced"));
        assertThrows(Exception.class, () -> apply(preview));
        assertEquals(1, remote.writes);
    }
    @Test public void emptySelectionPullsWithoutUploadingAndCancelDoesNothing() throws Exception {
        project("Local");
        JSONObject cancelled = preview();
        sync.handle("sync.cancel", obj());
        assertThrows(Exception.class, () -> apply(cancelled));
        assertEquals(1, documents.projects().length());
        JSONObject result = apply(preview());
        assertEquals(0, remote.writes);
        assertEquals(2, result.getJSONArray("projects").length());
    }
    @Test public void localChangesInvalidatePreviewBeforeAnyRemoteWrite() throws Exception {
        String key = project("Local");
        JSONObject preview = preview(key);
        project("Added later");
        assertThrows(Exception.class, () -> apply(preview));
        assertEquals(0, remote.exchanges);
        assertEquals(0, remote.lastSynced);
    }
    @Test public void repositoryChangesOrRemoteRacesCannotApplyUnreviewedData() throws Exception {
        String key = project("Local");
        JSONObject old = preview(key);
        sync.handle("sync.connect", obj("repository", "owner/other"));
        assertThrows(Exception.class, () -> apply(old));
        JSONObject current = preview(key);
        remote.revision++;
        assertThrows(Exception.class, () -> apply(current));
        assertEquals(1, documents.projects().length());
        assertEquals(0, remote.writes);
        assertEquals(0, remote.lastSynced);
    }
    @Test public void failedUploadDoesNotImportOrReportSuccessAndNeedsFreshPreview() throws Exception {
        String key = project("Local");
        JSONObject preview = preview(key);
        remote.fail = true;
        assertThrows(Exception.class, () -> apply(preview));
        assertEquals(1, documents.projects().length());
        assertEquals(0, remote.lastSynced);
        remote.fail = false;
        assertThrows(Exception.class, () -> apply(preview));
        assertEquals(0, remote.writes);
        apply(preview(key));
        assertEquals(1, remote.writes);
    }
    @Test public void invalidSelectionCannotAccessRemoteAndStatusContainsNoSecret() throws Exception {
        assertThrows(Exception.class, () -> preview("missing"));
        assertEquals(0, remote.reads);
        String key = project("Local");
        assertThrows(Exception.class, () -> preview(key, key));
        assertEquals(0, remote.reads);
        String status = sync.handle("sync.status", obj()).toString();
        assertFalse(status.contains("token"));
        assertFalse(status.contains("device_code"));
    }
    @Test public void selectionCanonicalizesWorkspaceKeyAliasesAndDeduplicatesThem() throws Exception {
        String alias = project("Alias"), canonical = project("Canonical");
        documents.mergeProjects(alias, canonical);
        remote.selection = new JSONArray(java.util.List.of(alias, canonical, "removed-project"));
        JSONObject status = sync.status();
        assertEquals(1, status.getJSONArray("selectedKeys").length());
        assertEquals(canonical, status.getJSONArray("selectedKeys").getString(0));
        assertEquals(1, remote.selection.length());
        assertEquals(canonical, remote.selection.getString(0));

        sync.handle("sync.selection", obj("keys", new JSONArray(java.util.List.of(alias))));
        assertEquals(canonical, remote.selection.getString(0));
        assertThrows(Exception.class, () -> sync.handle("sync.selection", obj("keys", new JSONArray(java.util.List.of(alias, canonical)))));
    }
    @Test public void statusPrunesSelectionForRemovedProjectsBeforePreview() throws Exception {
        String removed = project("Removed");
        remote.selection = new JSONArray(java.util.List.of(removed));
        documents.removeProject(removed);
        assertEquals(0, sync.status().getJSONArray("selectedKeys").length());
        assertEquals(0, remote.selection.length());
        assertThrows(Exception.class, () -> preview(removed));
        assertEquals(0, remote.reads);
    }
    @Test public void matchedCommittedLoginCancellationRestoresPreviousPreferences() throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("project-sync-github", 0);
        prefs.edit().clear().putString("token", "before").putString("account", "old-account").putLong("expiresAt", 11).commit();
        Map<String, ?> before = GitHubProjectSync.snapshotPreferences(prefs);
        prefs.edit().clear().putString("token", "after").putString("account", "new-account").putLong("expiresAt", 22).commit();
        GitHubProjectSync backend = new GitHubProjectSync(context);
        Field flow = GitHubProjectSync.class.getDeclaredField("committedLoginFlow"), snapshot = GitHubProjectSync.class.getDeclaredField("committedPreferences");
        flow.setAccessible(true); snapshot.setAccessible(true); flow.set(backend, "committed"); snapshot.set(backend, before);
        backend.invalidateLogin("committed");
        assertEquals("before", prefs.getString("token", "")); assertEquals("old-account", prefs.getString("account", "")); assertEquals(11, prefs.getLong("expiresAt", 0));
        assertEquals("", flow.get(backend));
        assertTrue(((Map<?, ?>) snapshot.get(backend)).isEmpty());
    }
    static final class FakeRemote implements ProjectSyncSettings.Remote {
        PortableProjects bundle = new PortableProjects();
        int revision = 1, writes, exchanges, reads;
        long lastSynced;
        boolean fail;
        String repository = "owner/sync";
        JSONArray selection = new JSONArray();
        FakeRemote() { bundle.create("proj_remote", "From PC", "device_pc", false); }
        public JSONObject status() { return obj("configured", true, "authenticated", true, "connected", true,
            "repository", repository, "branch", "main", "lastSynced", lastSynced, "selectedKeys", selection); }
        public JSONObject loginStart() { throw new UnsupportedOperationException(); }
        public JSONObject loginPoll(String id) { throw new UnsupportedOperationException(); }
        public void loginCancel(String id) { }
        public JSONObject repositories(int page) { throw new UnsupportedOperationException(); }
        public JSONObject connect(String name) { repository = name; return status(); }
        public void selection(JSONArray keys) { selection = keys; }
        public void markSynced() { lastSynced++; }
        public GitHubProjectSync.Remote previewRemote() { reads++; return new GitHubProjectSync.Remote("" + revision, bundle); }
        public JSONObject exchange(String raw, String sha) throws Exception {
            exchanges++;
            if (!sha.equals("" + revision)) throw new IOException("Remote changed");
            if (fail) throw new IOException("Offline");
            PortableProjects outgoing = PortableProjects.parse(raw, true);
            if (outgoing.eventCount() != 0) { bundle = bundle.union(outgoing); writes++; revision++; }
            return obj("changed", outgoing.eventCount() != 0);
        }
        public void disconnect() { }
    }
}
