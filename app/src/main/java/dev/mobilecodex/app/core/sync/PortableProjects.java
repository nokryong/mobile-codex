package dev.mobilecodex.app.core.sync;

import dev.mobilecodex.app.core.Json;
import dev.mobilecodex.app.core.WorkspacePath;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Project metadata only. Immutable events merge by ID; causal names preserve concurrent edits. */
public final class PortableProjects {
    public static final int MAX_BYTES = 1024 * 1024, MAX_EVENTS = 1024;
    public static final String FORMAT = "mobile-codex-projects";
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN .*PRIVATE KEY-----|(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|secret)\\s*[:=]\\s*\\S+)");
    private final TreeMap<String, JSONObject> events = new TreeMap<>();
    private final TreeMap<String, String> roots = new TreeMap<>();
    private final Map<String, Set<String>> ancestors = new HashMap<>();
    private final TreeMap<String, SortedSet<String>> names = new TreeMap<>();

    public static PortableProjects parse(String raw, boolean exchange) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Project bundle exceeds 1 MiB");
        return read(StrictJson.object(raw), exchange);
    }
    public static PortableProjects read(JSONObject data, boolean exchange) {
        try {
            if (data.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Project history exceeds 1 MiB");
            fields(data, "format", "schemaVersion", "events");
            if (!FORMAT.equals(data.get("format")) || !(data.get("schemaVersion") instanceof Number) || ((Number) data.get("schemaVersion")).doubleValue() != 1)
                throw new IllegalArgumentException("Unsupported project bundle schema");
            JSONArray input = data.getJSONArray("events");
            if (input.length() > MAX_EVENTS) throw new IllegalArgumentException("Too many project events");
            PortableProjects result = new PortableProjects();
            for (int i = 0; i < input.length(); i++) {
                JSONObject e = input.getJSONObject(i); String kind = string(e, "kind");
                if (!Set.of("created", "renamed", "linked").contains(kind)) throw new IllegalArgumentException("Unknown project event");
                fields(e, "id", "deviceId", "projectId", "kind", "parents", kind.equals("linked") ? "targetId" : "name");
                String id = id(e, "id", "evt"), project = id(e, "projectId", "proj"), device = id(e, "deviceId", "device");
                JSONArray parentArray = e.getJSONArray("parents"); TreeSet<String> parents = new TreeSet<>();
                if (parentArray.length() > MAX_EVENTS) throw new IllegalArgumentException("Too many event parents");
                for (int p = 0; p < parentArray.length(); p++) {
                    Object parent = parentArray.get(p);
                    if (!(parent instanceof String) || !((String) parent).matches("evt_[a-z0-9_-]{1,96}") || !parents.add((String) parent)) throw new IllegalArgumentException("Invalid event parents");
                }
                String value = kind.equals("linked") ? id(e, "targetId", "proj") : string(e, "name");
                if (!kind.equals("linked")) checkName(value, exchange);
                if (kind.equals("created") && !parents.isEmpty()) throw new IllegalArgumentException("Creation cannot have parents");
                JSONObject normalized = Json.obj("id", id, "deviceId", device, "projectId", project, "kind", kind, "parents", new JSONArray(parents), kind.equals("linked") ? "targetId" : "name", value);
                if (result.events.put(id, normalized) != null) throw new IllegalArgumentException("Duplicate event ID");
            }
            result.reduce(); return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid project bundle: " + e.getMessage(), e); }
    }
    public JSONObject json() { return Json.obj("format", FORMAT, "schemaVersion", 1, "events", new JSONArray(events.values())); }
    public Set<String> projectIds() { return Collections.unmodifiableSet(roots.keySet()); }
    public String canonical(String id) {
        if (!roots.containsKey(id)) throw new IllegalArgumentException("Unknown project ID");
        while (!roots.get(id).equals(id)) id = roots.get(id);
        return id;
    }
    public String name(String id) { return names.get(canonical(id)).first(); }
    public JSONArray conflicts(String id) { SortedSet<String> values = names.get(canonical(id)); return new JSONArray(values.size() > 1 ? values : Collections.emptySet()); }
    public int eventCount() { return events.size(); }
    public JSONObject summary() {
        JSONArray projects = new JSONArray(); int conflicts = 0, links = 0;
        for (String id : names.keySet()) { JSONArray values = conflicts(id); if (values.length() > 0) conflicts++; projects.put(Json.obj("projectId", id, "name", name(id), "nameConflicts", values)); }
        for (JSONObject e : events.values()) if (e.optString("kind").equals("linked")) links++;
        return Json.obj("projects", projects, "eventCount", events.size(), "linkCount", links, "conflictCount", conflicts);
    }
    public PortableProjects union(PortableProjects other) {
        TreeMap<String, JSONObject> combined = new TreeMap<>(events);
        for (Map.Entry<String, JSONObject> e : other.events.entrySet()) {
            JSONObject old = combined.get(e.getKey());
            if (old != null && !signature(old).equals(signature(e.getValue()))) throw new IllegalArgumentException("Event ID has different contents");
            combined.put(e.getKey(), e.getValue());
        }
        return read(Json.obj("format", FORMAT, "schemaVersion", 1, "events", new JSONArray(combined.values())), false);
    }
    public JSONObject export(Collection<String> selected) {
        Set<String> include = new HashSet<>(); for (String id : selected) include.add(canonical(id));
        if (include.isEmpty()) throw new IllegalArgumentException("Select at least one project");
        JSONArray out = new JSONArray();
        for (JSONObject event : events.values()) if (include.contains(canonical(event.optString("projectId")))) out.put(event);
        JSONObject data = Json.obj("format", FORMAT, "schemaVersion", 1, "events", out);
        parse(data.toString(), true); return data;
    }
    public void create(String projectId, String name, String deviceId, boolean seed) { add("created", projectId, name, deviceId, seed); }
    public void rename(String projectId, String name, String deviceId, boolean seed) { add("renamed", projectId, name, deviceId, seed); }
    public void link(String projectId, String targetId, String deviceId, boolean seed) {
        if (!canonical(projectId).equals(canonical(targetId))) add("linked", projectId, targetId, deviceId, seed);
    }
    private void add(String kind, String project, String value, String device, boolean seed) {
        TreeSet<String> parents = new TreeSet<>();
        if (!kind.equals("created")) {
            Set<String> groups = new HashSet<>(); groups.add(canonical(project)); if (kind.equals("linked")) groups.add(canonical(value));
            for (JSONObject e : events.values()) if (groups.contains(canonical(e.optString("projectId")))) parents.add(e.optString("id"));
            for (JSONObject e : events.values()) for (int i = 0; i < e.optJSONArray("parents").length(); i++) parents.remove(e.optJSONArray("parents").optString(i));
        }
        String id = "evt_" + (seed ? WorkspacePath.hash(device + "\n" + kind + "\n" + project + "\n" + String.join(",", parents) + "\n" + value) : UUID.randomUUID());
        JSONObject event = Json.obj("id", id, "deviceId", device, "projectId", project, "kind", kind, "parents", new JSONArray(parents), kind.equals("linked") ? "targetId" : "name", value);
        TreeMap<String, JSONObject> staged = new TreeMap<>(events); staged.put(id, event);
        PortableProjects checked = read(Json.obj("format", FORMAT, "schemaVersion", 1, "events", new JSONArray(staged.values())), false);
        if (checked.json().toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Project history is full");
        events.clear(); events.putAll(checked.events); reduce();
    }
    private void reduce() {
        roots.clear(); ancestors.clear(); names.clear();
        for (JSONObject e : events.values()) if (e.optString("kind").equals("created")) roots.put(e.optString("projectId"), e.optString("projectId"));
        for (JSONObject e : events.values()) {
            String project = canonical(e.optString("projectId"));
            if (e.optString("kind").equals("linked")) {
                String target = canonical(e.optString("targetId"));
                if (!project.equals(target)) roots.put(project.compareTo(target) < 0 ? target : project, project.compareTo(target) < 0 ? project : target);
            }
        }
        TreeSet<String> remaining = new TreeSet<>(events.keySet());
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (String id : new ArrayList<>(remaining)) {
                JSONObject e = events.get(id); JSONArray parents = e.optJSONArray("parents"); Set<String> seen = new HashSet<>(); boolean ready = true;
                for (int i = 0; i < parents.length(); i++) {
                    String parent = parents.optString(i);
                    if (!events.containsKey(parent)) throw new IllegalArgumentException("Missing event parent");
                    if (!canonical(events.get(parent).optString("projectId")).equals(canonical(e.optString("projectId")))) throw new IllegalArgumentException("Unrelated event parent");
                    if (!ancestors.containsKey(parent)) { ready = false; break; }
                    seen.add(parent); seen.addAll(ancestors.get(parent));
                }
                if (!ready) continue;
                if (!e.optString("kind").equals("created")) {
                    requireCreation(seen, e.optString("projectId"));
                    if (e.optString("kind").equals("linked")) requireCreation(seen, e.optString("targetId"));
                }
                ancestors.put(id, seen); remaining.remove(id); progress = true;
            }
            if (!progress) throw new IllegalArgumentException("Cyclic project history");
        }
        Set<String> superseded = new HashSet<>();
        for (JSONObject e : events.values()) if (!e.optString("kind").equals("linked")) superseded.addAll(ancestors.get(e.optString("id")));
        for (JSONObject e : events.values()) if (!e.optString("kind").equals("linked") && !superseded.contains(e.optString("id")))
            names.computeIfAbsent(canonical(e.optString("projectId")), key -> new TreeSet<>()).add(e.optString("name"));
    }
    private void requireCreation(Set<String> ancestors, String project) {
        for (String id : ancestors) { JSONObject e = events.get(id); if (e.optString("kind").equals("created") && e.optString("projectId").equals(project)) return; }
        throw new IllegalArgumentException("Event has no causal project creation");
    }
    private static String signature(JSONObject e) {
        return e.optString("deviceId") + "\n" + e.optString("projectId") + "\n" + e.optString("kind") + "\n" + e.optJSONArray("parents") + "\n" + e.optString("name", e.optString("targetId"));
    }
    private static void fields(JSONObject o, String... expected) {
        Set<String> keys = new HashSet<>(); o.keys().forEachRemaining(keys::add);
        if (!keys.equals(new HashSet<>(Arrays.asList(expected)))) throw new IllegalArgumentException("Unknown or missing project fields");
    }
    private static String string(JSONObject o, String key) throws Exception { Object value = o.get(key); if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key); return (String) value; }
    private static String id(JSONObject o, String key, String prefix) throws Exception { String value = string(o, key); if (!value.matches(prefix + "_[a-z0-9_-]{1,96}")) throw new IllegalArgumentException("Invalid " + key); return value; }
    private static void checkName(String value, boolean exchange) {
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid project name");
        for (int i = 0; i < value.length(); i++) if (Character.isSurrogate(value.charAt(i))) {
            if (!Character.isHighSurrogate(value.charAt(i)) || i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw new IllegalArgumentException("Invalid unicode name");
        }
        if (exchange && SECRET.matcher(value).find()) throw new IllegalArgumentException("Project name may contain a credential");
    }
}
