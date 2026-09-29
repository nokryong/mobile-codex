package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SharedProjectTest {
    private ProjectRegistry legacy() {
        return ProjectRegistry.fromJson("{\"selectedKey\":\"a\",\"projects\":[{\"key\":\"a\",\"name\":\"Same\",\"uri\":\"content://one\"},{\"key\":\"b\",\"name\":\"Same\",\"uri\":\"content://two\"}],\"removed\":[]}");
    }
    @Test public void migrationKeepsKeysAndSameNamesSeparateAndPersistsRandomIdentities() throws Exception {
        ProjectRegistry registry = legacy();
        String a = registry.projectId("a"), b = registry.projectId("b");
        assertNotEquals(a, b);
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        assertEquals(a, restored.projectId("a")); assertEquals(b, restored.projectId("b"));
        assertEquals("a", restored.selectedKey()); assertEquals("content://one", restored.get("a").uri);
        assertEquals(2, restored.entries(p -> true).length());
        assertEquals(Json.parse(registry.toJson()).getJSONObject("identities").getString("deviceId"),
            Json.parse(restored.toJson()).getJSONObject("identities").getString("deviceId"));
    }
    @Test public void mergePreservesBindingsAndActiveKeyButGroupsBothHistories() throws Exception {
        ProjectRegistry registry = legacy(); registry.rename("b", "Target");
        String id = registry.merge("a", "b");
        assertEquals("a", registry.selectedKey());
        assertEquals("content://one", registry.get("a").uri); assertEquals("content://two", registry.get("b").uri);
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        JSONArray groups = restored.entries(p -> true); assertEquals(1, groups.length());
        JSONObject group = groups.getJSONObject(0);
        assertEquals(id, group.getString("projectId")); assertEquals("b", group.getString("key"));
        assertEquals("Target", group.getString("name")); assertTrue(group.getBoolean("selected"));
        assertEquals(2, group.getJSONArray("workspaceKeys").length());
        assertEquals(2, group.getJSONArray("bindings").length());
        assertEquals(id, restored.projectId("a")); assertEquals(id, restored.projectId("b"));
        restored.prefer("a");
        assertEquals("a", ProjectRegistry.fromJson(restored.toJson()).entries(p -> true).getJSONObject(0).getString("key"));
    }
    @Test public void repeatedAndTransitiveMergesAreIdempotentAndStable() throws Exception {
        ProjectRegistry registry = legacy(); registry.put("content://three", "Third", "c");
        String expected = java.util.stream.Stream.of(registry.projectId("a"), registry.projectId("b"), registry.projectId("c")).sorted().findFirst().get();
        registry.merge("a", "b"); registry.merge("b", "c");
        String before = registry.toJson(); registry.merge("c", "a"); assertEquals(before, registry.toJson());
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        assertEquals(expected, restored.projectId("a")); assertEquals(expected, restored.projectId("c"));
        assertEquals(1, restored.entries(p -> true).length());
    }
    @Test public void unboundProjectSurvivesRestartAndCanBeBoundWithoutChangingId() throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        String key = registry.createUnbound("Remote work").key, id = registry.projectId(key);
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        JSONObject group = restored.entries(p -> !p.uri.isBlank()).getJSONObject(0);
        assertFalse(group.getBoolean("available")); assertFalse(group.getBoolean("hasLocalFolder"));
        restored.put("content://folder", "Remote work", key);
        assertEquals(id, restored.projectId(key)); assertEquals(key, restored.selectedKey());
        assertTrue(restored.entries(p -> true).getJSONObject(0).getBoolean("hasLocalFolder"));
    }
    @Test public void detachAndRebindRetainSharedIdentityWithoutResurrectingOtherBindings() throws Exception {
        ProjectRegistry registry = legacy(); registry.merge("a", "b"); String id = registry.projectId("b");
        registry.remove("a");
        ProjectRegistry restored = ProjectRegistry.fromJson(registry.toJson());
        assertNull(restored.get("a")); assertEquals(id, restored.projectId("a"));
        assertEquals(1, restored.entries(p -> true).getJSONObject(0).getJSONArray("bindings").length());
        restored.put("content://replacement", "Same", "a");
        assertEquals(id, restored.projectId("a"));
        assertEquals(1, restored.entries(p -> true).length());
        restored.remove("a"); restored.remove("b"); assertEquals(0, restored.entries(p -> true).length());
    }
    @Test public void renameUpdatesSharedLabelWhileLocalFolderLabelsStayUseful() {
        ProjectRegistry registry = legacy(); registry.merge("a", "b"); registry.rename("a", "Shared title");
        assertEquals("Shared title", registry.displayName("b")); assertEquals("Same", registry.get("b").name);
    }
    @Test public void sameUriCannotSilentlyMoveBetweenProjects() {
        ProjectRegistry registry = legacy(); String before = registry.toJson();
        assertThrows(IllegalArgumentException.class, () -> registry.put("content://one", "B", "b"));
        assertEquals(before, registry.toJson());
    }
    @Test public void unknownOrCorruptIdentitySchemaFailsClosed() throws Exception {
        JSONObject raw = Json.parse(legacy().toJson());
        JSONObject identities = raw.getJSONObject("identities"); identities.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> ProjectRegistry.fromJson(raw.toString()));
        identities.put("schemaVersion", 1); identities.put("bindings", new JSONArray());
        assertThrows(IllegalArgumentException.class, () -> ProjectRegistry.fromJson(raw.toString()));
    }
}
