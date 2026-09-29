package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Predicate;
import dev.mobilecodex.app.core.sync.ProjectIdentities;

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
    private ProjectIdentities identities = new ProjectIdentities();

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
                String key = value.optString("key"); if (!key.isBlank() && !result.projects.containsKey(key)) result.removed.put(key, value.optString("name", t("이전 프로젝트")));
            }
        } catch (Exception ignored) { }
        JSONObject root = Json.parse(raw);
        if (root.has("identities")) {
            JSONObject identityData = root.optJSONObject("identities");
            if (identityData == null) throw new IllegalArgumentException("Invalid project identities");
            result.identities = ProjectIdentities.fromJson(identityData);
        }
        for (Project project : result.projects.values()) {
            if (root.has("identities") && result.identities.projectId(project.key).isEmpty())
                throw new IllegalArgumentException("Missing project identity");
            result.identities.ensure(project.key, project.name);
        }
        // Tombstoned local keys retain their identity when reconnected later.
        result.removed.forEach((key, name) -> {
            if (root.has("identities") && result.identities.projectId(key).isEmpty()) throw new IllegalArgumentException("Missing removed project identity");
            result.identities.ensure(key, name);
        });
        return result;
    }

    public String selectedKey() { return selectedKey; }
    public Project selected() { return projects.get(selectedKey); }
    public Project get(String key) { return projects.get(key == null ? "" : key); }
    public String projectId(String key) { return identities.projectId(key); }
    public String displayName(String key) { return identities.name(key); }
    public JSONObject exportProject(String key) {
        if (get(key) == null) throw new IllegalArgumentException("Unknown project");
        return identities.export(Collections.singleton(key));
    }
    public JSONObject importProjects(String raw) {
        JSONObject summary = identities.importBundle(raw);
        for (String id : identities.unboundProjects()) {
            String key = "local_" + UUID.randomUUID();
            identities.attach(key, id);
            projects.put(key, new Project(key, identities.sharedName(id), ""));
        }
        return summary;
    }

    public Project createUnbound(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 200) throw new IllegalArgumentException(t("프로젝트 이름은 1~200자로 입력해 주세요."));
        String key = "local_" + UUID.randomUUID();
        ensurePlaceholder(key, clean);
        selectedKey = key;
        return projects.get(key);
    }
    public String merge(String sourceKey, String targetKey) {
        if (get(sourceKey) == null || get(targetKey) == null) throw new IllegalArgumentException(t("등록된 프로젝트를 찾을 수 없습니다."));
        return identities.merge(sourceKey, targetKey);
    }
    public void prefer(String key) {
        if (get(key) == null) throw new IllegalArgumentException(t("등록된 프로젝트를 찾을 수 없습니다."));
        identities.prefer(key);
    }

    public Project put(String uri, String name, String keyHint) {
        if (uri == null || uri.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException(t("프로젝트 정보가 올바르지 않습니다."));
        String key = keyHint == null || keyHint.isBlank() ? WorkspacePath.hash(uri) : keyHint;
        for (Project existing : projects.values()) if (existing.uri.equals(uri) && !existing.key.equals(key))
            throw new IllegalArgumentException(t("이미 연결된 폴더입니다. 프로젝트 병합을 사용해 주세요."));
        Project value = new Project(key, name, uri);
        projects.put(key, value);
        identities.ensure(key, name);
        removed.remove(key);
        selectedKey = key;
        return value;
    }
    public void ensurePlaceholder(String key, String name) {
        if (key == null || key.isBlank() || projects.containsKey(key) || removed.containsKey(key)) return;
        projects.put(key, new Project(key, name == null || name.isBlank() ? t("이전 프로젝트") : name, ""));
        identities.ensure(key, projects.get(key).name);
    }
    public boolean removed(String key) { return removed.containsKey(key == null ? "" : key); }
    public Project remove(String key) {
        Project value = projects.remove(key == null ? "" : key);
        if (value == null) throw new IllegalArgumentException(t("등록된 프로젝트를 찾을 수 없습니다."));
        removed.put(value.key, value.name);
        if (value.key.equals(selectedKey)) selectedKey = "";
        return value;
    }

    /** Changes only the user-facing project label; the stable key and folder URI remain unchanged. */
    public Project rename(String key, String name) {
        String normalizedKey = key == null ? "" : key;
        String normalizedName = name == null ? "" : name.trim();
        Project value = projects.get(normalizedKey);
        if (value == null) throw new IllegalArgumentException(t("등록된 프로젝트를 찾을 수 없습니다."));
        // Imported valid names may exceed the local input limit. Selecting a conflict keeps its exact value.
        boolean conflictChoice = false;
        JSONArray conflicts = identities.conflicts(normalizedKey);
        for (int i = 0; i < conflicts.length(); i++) if (conflicts.optString(i).equals(name)) { conflictChoice = true; normalizedName = name; break; }
        if (normalizedName.isBlank() || (!conflictChoice && normalizedName.length() > 200)) throw new IllegalArgumentException(t("프로젝트 이름은 1~200자로 입력해 주세요."));
        Project renamed = new Project(value.key, normalizedName, value.uri);
        projects.put(normalizedKey, renamed);
        identities.rename(normalizedKey, normalizedName);
        return renamed;
    }

    public void select(String key) {
        key = key == null ? "" : key;
        if (!key.isEmpty() && !projects.containsKey(key)) throw new IllegalArgumentException(t("등록된 프로젝트를 찾을 수 없습니다."));
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
        LinkedHashMap<String, List<Project>> groups = new LinkedHashMap<>();
        for (Project value : projects.values()) groups.computeIfAbsent(projectId(value.key), id -> new ArrayList<>()).add(value);
        for (List<Project> group : groups.values()) {
            Project primary = group.get(0);
            String preferred = identities.preferredKey(primary.key);
            boolean selected = false;
            JSONArray keys = new JSONArray(), bindings = new JSONArray();
            for (Project value : group) {
                if (value.key.equals(preferred)) primary = value;
                if (value.key.equals(selectedKey)) selected = true;
                keys.put(value.key);
                bindings.put(Json.obj("key", value.key, "name", value.name, "uri", value.uri,
                    "bindingId", identities.bindingId(value.key), "deviceId", identities.deviceId(),
                    "hasLocalFolder", !value.uri.isBlank(), "available", available.test(value), "selected", value.key.equals(selectedKey)));
            }
            out.put(Json.obj("key", primary.key, "projectId", projectId(primary.key), "name", displayName(primary.key),
                "selected", selected, "available", available.test(primary), "hasLocalFolder", !primary.uri.isBlank(),
                "workspaceKeys", keys, "bindings", bindings, "nameConflicts", identities.conflicts(primary.key)));
        }
        return out;
    }

    public String toJson() {
        JSONArray values = new JSONArray();
        for (Project project : projects.values()) values.put(Json.obj("key", project.key, "name", project.name, "uri", project.uri));
        JSONArray tombstones = new JSONArray();
        for (Map.Entry<String, String> value : removed.entrySet()) tombstones.put(Json.obj("key", value.getKey(), "name", value.getValue()));
        return Json.obj("selectedKey", selectedKey, "projects", values, "removed", tombstones, "identities", identities.toJson()).toString();
    }
}
