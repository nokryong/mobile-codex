package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectRegistryTest {
    @Test public void projectKeySurvivesFolderRebindAndSelectionRoundTrip() throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        registry.put("content://provider/tree/old", "Novel", "project-1");
        registry.put("content://provider/tree/new", "Novel", "project-1");
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        assertEquals("project-1", restored.selectedKey());
        assertEquals("content://provider/tree/new", restored.get("project-1").uri);
        JSONArray entries = restored.entries(project -> project.uri.contains("new"));
        assertTrue(entries.getJSONObject(0).getBoolean("selected"));
        assertTrue(entries.getJSONObject(0).getBoolean("available"));
    }

    @Test public void missingLegacyKeyIsKeptDistinctFromGeneralChat() {
        ProjectRegistry registry = new ProjectRegistry();
        registry.put("content://provider/tree/a", "A", "project-a");
        assertEquals("project-a", registry.restoreLegacyKey("thread-a", "", "A"));
        assertEquals("legacy:thread-lost", registry.restoreLegacyKey("thread-lost", "", "Forgotten"));
        assertEquals("old-key", registry.restoreLegacyKey("thread", "old-key", "A"));
    }
    @Test public void removedProjectStaysTombstonedAcrossRestartAndIsNotRestoredByHistory() {
        ProjectRegistry registry = new ProjectRegistry();
        registry.put("content://provider/tree/a", "A", "project-a");
        registry.remove("project-a");
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        assertTrue(restored.removed("project-a"));
        assertNull(restored.get("project-a"));
        assertEquals("project-a", restored.restoreLegacyKey("thread-a", "project-a", "A"));
        assertNull(restored.get("project-a"));
        restored.put("content://provider/tree/reconnected", "A", "project-a");
        assertFalse(restored.removed("project-a"));
        assertEquals("content://provider/tree/reconnected", restored.get("project-a").uri);
    }
}
