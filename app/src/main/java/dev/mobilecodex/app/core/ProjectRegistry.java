package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Predicate;

/** Durable project identities.  A project key survives a replacement SAF URI. */
public final class ProjectRegistry {
    public static final class Project {
        public final String key, name, uri;
        Project(String key, String name, String uri) { this.key = key; this.name = name; this.uri = uri; }
        JSONObject json(boolean selected, boolean available) {
            return Json.obj("key", key, "name", name, "selected", selected, "available", available);
        }
    }

    private final LinkedHashMap<String, Project> projects = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> removed = new LinkedHashMap<>();
    private String selectedKey = "";

    public static ProjectRegistry fromJson(String raw) {
        ProjectRegistry result = new ProjectRegistry();
        if (raw == null || raw.isBlank()) return result;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray values = root.optJSONArray("projects");
            if (values != null) for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                String key = value.optString("key"), uri = value.optString("uri"), name = value.optString("name");
                if (!key.isBlank() && !name.isBlank()) result.projects.put(key, new Project(key, name, uri));
            }
            String selected = root.optString("selectedKey");
            if (result.projects.containsKey(selected)) result.selectedKey = selected;
            JSONArray tombstones = root.optJSONArray("removed");
            if (tombstones != null) for (int i = 0; i < tombstones.length(); i++) {
                JSONObject value = tombstones.optJSONObject(i); if (value == null) continue;
                String key = value.optString("key"); if (!key.isBlank() && !result.projects.containsKey(key)) result.removed.put(key, value.optString("name", "이전 프로젝트"));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public String selectedKey() { return selectedKey; }
    public Project selected() { return projects.get(selectedKey); }
    public Project get(String key) { return projects.get(key == null ? "" : key); }

    public Project put(String uri, String name, String keyHint) {
        if (uri == null || uri.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("프로젝트 정보가 올바르지 않습니다.");
        String key = keyHint == null || keyHint.isBlank() ? WorkspacePath.hash(uri) : keyHint;
        Project value = new Project(key, name, uri);
        projects.put(key, value);
        removed.remove(key);
        selectedKey = key;
        return value;
    }
    public void ensurePlaceholder(String key, String name) {
        if (key == null || key.isBlank() || projects.containsKey(key) || removed.containsKey(key)) return;
        projects.put(key, new Project(key, name == null || name.isBlank() ? "이전 프로젝트" : name, ""));
    }
    public boolean removed(String key) { return removed.containsKey(key == null ? "" : key); }
    public Project remove(String key) {
        Project value = projects.remove(key == null ? "" : key);
        if (value == null) throw new IllegalArgumentException("등록된 프로젝트를 찾을 수 없습니다.");
        removed.put(value.key, value.name);
        if (value.key.equals(selectedKey)) selectedKey = "";
        return value;
    }

    public void select(String key) {
        key = key == null ? "" : key;
        if (!key.isEmpty() && !projects.containsKey(key)) throw new IllegalArgumentException("등록된 프로젝트를 찾을 수 없습니다.");
        selectedKey = key;
    }

    /** A legacy workspace hash is never silently rewritten to the current project. */
    public String restoreLegacyKey(String sessionId, String oldKey, String oldName) {
        if (oldKey != null && !oldKey.isBlank()) { ensurePlaceholder(oldKey, oldName); return oldKey; }
        for (Project project : projects.values()) if (project.name.equals(oldName)) return project.key;
        String key = "legacy:" + (sessionId == null || sessionId.isBlank() ? UUID.randomUUID() : sessionId);
        ensurePlaceholder(key, oldName); return key;
    }

    public JSONArray entries(Predicate<Project> available) {
        JSONArray out = new JSONArray();
        for (Project value : projects.values()) out.put(value.json(value.key.equals(selectedKey), available.test(value)));
        return out;
    }

    public String toJson() {
        JSONArray values = new JSONArray();
        for (Project project : projects.values()) values.put(Json.obj("key", project.key, "name", project.name, "uri", project.uri));
        JSONArray tombstones = new JSONArray();
        for (Map.Entry<String, String> value : removed.entrySet()) tombstones.put(Json.obj("key", value.getKey(), "name", value.getValue()));
        return Json.obj("selectedKey", selectedKey, "projects", values, "removed", tombstones).toString();
    }
}
