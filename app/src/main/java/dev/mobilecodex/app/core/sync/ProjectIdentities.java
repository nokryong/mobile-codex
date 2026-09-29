package dev.mobilecodex.app.core.sync;

import dev.mobilecodex.app.core.Json;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Logical identities and local binding IDs. Contains no folder paths or credentials. */
public final class ProjectIdentities {
    private final String deviceId;
    private final LinkedHashMap<String, String> names = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> parents = new LinkedHashMap<>();
    private final LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
    private PortableProjects history = new PortableProjects();

    private static final class Binding {
        final String id, projectId;
        Binding(String id, String projectId) { this.id = id; this.projectId = projectId; }
    }

    public ProjectIdentities() { this("device_" + UUID.randomUUID()); }
    private ProjectIdentities(String deviceId) { this.deviceId = deviceId; }
    public String deviceId() { return deviceId; }

    public void ensure(String localKey, String name) {
        if (bindings.containsKey(localKey)) return;
        String id = "proj_" + UUID.randomUUID();
        history.create(id, name, deviceId, false);
        refresh();
        bindings.put(localKey, new Binding("binding_" + UUID.randomUUID(), id));
    }
    public String projectId(String key) {
        Binding binding = bindings.get(key);
        return binding == null ? "" : canonical(binding.projectId);
    }
    public String bindingId(String key) {
        Binding binding = bindings.get(key);
        return binding == null ? "" : binding.id;
    }
    public String name(String key) { return names.getOrDefault(projectId(key), ""); }
    public void rename(String key, String name) { history.rename(requireProject(key), name, deviceId, false); refresh(); }
    public JSONArray conflicts(String key) { return history.conflicts(requireProject(key)); }
    public JSONObject export(Collection<String> keys) {
        List<String> ids = new ArrayList<>(); for (String key : keys) ids.add(requireProject(key));
        return history.export(ids);
    }
    public JSONObject importBundle(String raw) {
        PortableProjects incoming = PortableProjects.parse(raw, true);
        int before = history.eventCount();
        history = history.union(incoming); refresh();
        Set<String> affected = new TreeSet<>(); for (String id : incoming.projectIds()) affected.add(history.canonical(id));
        JSONArray projects = new JSONArray(); int conflicts = 0;
        for (String id : affected) {
            JSONArray values = history.conflicts(id); if (values.length() > 0) conflicts++;
            projects.put(Json.obj("projectId", id, "name", history.name(id), "nameConflicts", values));
        }
        JSONObject summary = Json.obj("projects", projects, "eventCount", incoming.eventCount(), "linkCount", incoming.summary().optInt("linkCount"), "conflictCount", conflicts);
        return Json.obj("summary", summary, "addedEvents", history.eventCount() - before);
    }
    public Set<String> unboundProjects() {
        Set<String> roots = new TreeSet<>(); for (String id : names.keySet()) roots.add(canonical(id));
        for (Binding binding : bindings.values()) roots.remove(canonical(binding.projectId));
        return roots;
    }
    public void attach(String key, String projectId) {
        if (bindings.containsKey(key)) throw new IllegalArgumentException("Existing binding");
        bindings.put(key, new Binding("binding_" + UUID.randomUUID(), canonical(projectId)));
    }
    public String sharedName(String id) { return names.get(canonical(id)); }
    private void refresh() {
        names.clear(); parents.clear();
        for (String id : history.projectIds()) {
            String root = history.canonical(id); names.put(id, history.name(id));
            if (!root.equals(id)) parents.put(id, root);
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        defaults.forEach((id, key) -> normalized.putIfAbsent(canonical(id), key));
        defaults.clear(); defaults.putAll(normalized);
    }
    public String preferredKey(String key) { return defaults.getOrDefault(requireProject(key), ""); }
    public void prefer(String key) { defaults.put(requireProject(key), key); }

    /** Union by stable ID makes repeated/transitive merges cycle-free. Local keys never change. */
    public String merge(String sourceKey, String targetKey) {
        String source = requireProject(sourceKey), target = requireProject(targetKey);
        if (source.equals(target)) return target;
        String name = names.get(target);
        history.link(source, target, deviceId, false);
        history.rename(target, name, deviceId, false);
        refresh();
        String root = canonical(source);
        defaults.remove(source); defaults.remove(target);
        defaults.put(root, targetKey);
        return root;
    }
    private String requireProject(String key) {
        String id = projectId(key);
        if (id.isEmpty()) throw new IllegalArgumentException("Unknown local project key");
        return id;
    }
    private String canonical(String id) {
        HashSet<String> seen = new HashSet<>();
        while (parents.containsKey(id)) {
            if (!seen.add(id)) throw new IllegalArgumentException("Cyclic project identities");
            id = parents.get(id);
        }
        if (!names.containsKey(id)) throw new IllegalArgumentException("Unknown shared project ID");
        return id;
    }

    public JSONObject toJson() {
        JSONArray projects = new JSONArray(), aliases = new JSONArray(), local = new JSONArray(), preferred = new JSONArray();
        names.forEach((id, name) -> projects.put(Json.obj("projectId", id, "name", name)));
        parents.forEach((id, target) -> aliases.put(Json.obj("projectId", id, "targetId", target)));
        bindings.forEach((key, value) -> local.put(Json.obj("localKey", key, "bindingId", value.id, "projectId", value.projectId)));
        defaults.forEach((id, key) -> preferred.put(Json.obj("projectId", id, "localKey", key)));
        return Json.obj("schemaVersion", 1, "deviceId", deviceId, "projects", projects, "aliases", aliases, "bindings", local, "defaults", preferred, "history", history.json());
    }

    public static ProjectIdentities fromJson(JSONObject data) {
        try {
            if (data.getInt("schemaVersion") != 1) throw new IllegalArgumentException("Unsupported project identity schema");
            ProjectIdentities result = new ProjectIdentities(required(data, "deviceId"));
            JSONArray projects = data.getJSONArray("projects"), aliases = data.getJSONArray("aliases"), local = data.getJSONArray("bindings");
            for (int i = 0; i < projects.length(); i++) {
                JSONObject p = projects.getJSONObject(i);
                if (result.names.put(required(p, "projectId"), required(p, "name")) != null) throw new IllegalArgumentException("Duplicate project ID");
            }
            for (int i = 0; i < aliases.length(); i++) {
                JSONObject alias = aliases.getJSONObject(i);
                String id = required(alias, "projectId"), target = required(alias, "targetId");
                if (!result.names.containsKey(id) || !result.names.containsKey(target) || result.parents.put(id, target) != null)
                    throw new IllegalArgumentException("Invalid project alias");
            }
            for (String id : result.names.keySet()) result.canonical(id);
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < local.length(); i++) {
                JSONObject b = local.getJSONObject(i);
                String key = required(b, "localKey"), id = required(b, "bindingId"), project = required(b, "projectId");
                result.canonical(project);
                if (!ids.add(id) || result.bindings.put(key, new Binding(id, project)) != null) throw new IllegalArgumentException("Duplicate binding");
            }
            JSONArray preferred = data.getJSONArray("defaults");
            for (int i = 0; i < preferred.length(); i++) {
                JSONObject p = preferred.getJSONObject(i);
                String id = required(p, "projectId"), key = required(p, "localKey");
                if (!id.equals(result.projectId(key)) || result.defaults.put(id, key) != null) throw new IllegalArgumentException("Invalid default binding");
            }
            if (data.has("history")) {
                result.history = PortableProjects.read(data.getJSONObject("history"), false);
                if (!result.history.projectIds().equals(result.names.keySet())) throw new IllegalArgumentException("Inconsistent project history");
                for (String id : result.names.keySet()) if (!result.canonical(id).equals(result.history.canonical(id))) throw new IllegalArgumentException("Inconsistent aliases");
            } else {
                // Stable seed events make migration retry-safe without changing project or binding IDs.
                for (String id : new TreeSet<>(result.names.keySet())) result.history.create(id, result.names.get(id), result.deviceId, true);
                for (String id : new TreeSet<>(result.parents.keySet())) result.history.link(id, result.parents.get(id), result.deviceId, true);
                Set<String> roots = new TreeSet<>(); for (String id : result.names.keySet()) roots.add(result.canonical(id));
                for (String id : roots) result.history.rename(id, result.names.get(id), result.deviceId, true);
            }
            result.refresh();
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid project identities", e); }
    }
    private static String required(JSONObject value, String key) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof String) || ((String) raw).isBlank()) throw new IllegalArgumentException("Invalid " + key);
        return (String) raw;
    }
}
