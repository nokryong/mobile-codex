package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class DocumentStoreGeneralWorkspaceTest {
    private Context context;
    private File fixture;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        fixture = new File(context.getFilesDir(), "workspace/general-workspace-test");
        delete(fixture); assertTrue(fixture.mkdirs());
    }
    @After public void cleanup() { delete(fixture); }

    @Test public void generalWorkspaceUsesExistingPrivateFolderAndSupportsVerifiedFileOperations() throws Exception {
        File existing = new File(fixture, "existing.txt");
        Files.write(existing.toPath(), "kept".getBytes(StandardCharsets.UTF_8));
        DocumentStore store = new DocumentStore(context);
        JSONObject workspace = store.workspace();
        assertFalse(workspace.getBoolean("selected")); assertEquals("", workspace.getString("key"));
        assertEquals("", workspace.getString("projectId")); assertEquals("Codex", workspace.getString("name"));
        assertTrue(workspace.getBoolean("available")); assertTrue(workspace.getBoolean("hasLocalFolder"));
        assertTrue(workspace.getBoolean("defaultWorkspace"));
        assertEquals(new File(context.getFilesDir(), "workspace").getCanonicalFile(), store.directDirectory());
        assertEquals("kept", store.read("general-workspace-test/existing.txt").getString("content"));

        DocumentStore.Mutation create = store.prepare("mobile_create",
            obj("path", "general-workspace-test/new.txt", "content", "new"));
        store.commit(create);
        assertEquals("new", store.read("general-workspace-test/new.txt").getString("content"));

        String sha = store.read("general-workspace-test/existing.txt").getString("sha256");
        DocumentStore.Mutation stale = store.prepare("mobile_write",
            obj("path", "general-workspace-test/existing.txt", "content", "updated", "expectedSha256", sha));
        Files.write(existing.toPath(), "changed elsewhere".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> store.commit(stale));
        assertEquals("changed elsewhere", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> store.read("../outside.txt"));
    }

    @Test public void ancestorMutationsCannotDeleteMoveOrRenameGitMetadata() throws Exception {
        File repository = new File(fixture, "repo"), git = new File(repository, ".git");
        assertTrue(git.mkdirs()); Files.write(new File(git, "config").toPath(), "private".getBytes(StandardCharsets.UTF_8));
        File destination = new File(fixture, "destination"); assertTrue(destination.mkdir());
        assertThrows(java.io.IOException.class, () -> store().prepare("mobile_delete", obj("path", "general-workspace-test/repo")));
        assertThrows(java.io.IOException.class, () -> store().prepare("mobile_rename", obj("path", "general-workspace-test/repo", "name", "renamed")));
        assertThrows(java.io.IOException.class, () -> store().prepare("mobile_move", obj("path", "general-workspace-test/repo", "destination", "general-workspace-test/destination")));
        assertTrue(new File(git, "config").isFile());
    }

    private DocumentStore store() { return new DocumentStore(context); }
    private static void delete(File value) {
        if (!value.exists()) return;
        File[] children = value.listFiles(); if (children != null) for (File child : children) delete(child);
        value.delete();
    }
}
