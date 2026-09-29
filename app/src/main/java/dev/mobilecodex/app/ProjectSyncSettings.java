package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Json.obj;
import static dev.mobilecodex.app.core.Texts.t;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Settings-only sync workflow. Called on Engine.io; no tokens cross the UI bridge. */
final class ProjectSyncSettings {
    interface Remote {
        JSONObject status();
        JSONObject loginStart() throws Exception;
        JSONObject loginPoll(String flowId) throws Exception;
        void loginCancel(String flowId);
        default void invalidateLogin(String flowId) { }
        JSONObject repositories(int page) throws Exception;
        JSONObject connect(String repository) throws Exception;
        void selection(JSONArray keys) throws Exception;
        void markSynced() throws Exception;
        GitHubProjectSync.Remote previewRemote() throws Exception;
        JSONObject exchange(String raw, String expectedSha) throws Exception;
        void disconnect() throws Exception;
    }
    private final Remote remote;
    private final DocumentStore documents;
    private Plan plan;
    private static final class Plan {
        String token, localToken, incoming, outgoing, remoteSha, connection;
        int addedEvents;
    }
    ProjectSyncSettings(Remote remote, DocumentStore documents) {
        this.remote = remote; this.documents = documents;
    }
    void invalidateLogin(String flowId) { remote.invalidateLogin(flowId); }
    JSONObject status() throws Exception {
        JSONObject status = remote.status();
        JSONArray stored = status.optJSONArray("selectedKeys");
        JSONArray selected = normalizeKeys(stored == null ? new JSONArray() : stored, false);
        if (stored != null && !stored.toString().equals(selected.toString())) remote.selection(selected);
        status.put("selectedKeys", selected);
        status.put("projects", documents.projects());
        return status;
    }
    JSONObject handle(String action, JSONObject args) throws Exception {
        switch (action) {
            case "sync.status": return status();
            case "sync.login.start": plan = null; return remote.loginStart();
            case "sync.login.poll": {
                JSONObject result = remote.loginPoll(args.getString("flowId"));
                return result.optBoolean("pending") ? result : status();
            }
            case "sync.login.cancel": remote.loginCancel(args.getString("flowId")); return status();
            case "sync.repositories": return remote.repositories(args.optInt("page", 1));
            case "sync.connect": plan = null; remote.connect(args.getString("repository")); return status();
            case "sync.selection": {
                plan = null;
                JSONArray keys = validateKeys(args.getJSONArray("keys"));
                remote.selection(keys); return status();
            }
            case "sync.preview": return preview(args.getJSONArray("keys"));
            case "sync.apply": return apply(args.getString("token"));
            case "sync.cancel": plan = null; return status();
            case "sync.disconnect": plan = null; remote.disconnect(); return status();
            default: throw new IllegalArgumentException("Unknown sync action");
        }
    }
    private JSONArray validateKeys(JSONArray keys) throws Exception {
        return normalizeKeys(keys, true);
    }
    /** Canonicalize persisted aliases and silently prune projects that no longer exist. */
    private JSONArray normalizeKeys(JSONArray keys, boolean rejectUnknown) throws Exception {
        Map<String, String> canonical = new HashMap<>();
        Set<String> selected = new LinkedHashSet<>();
        JSONArray projects = documents.projects();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject project = projects.getJSONObject(i);
            String key = project.getString("key");
            canonical.put(key, key);
            JSONArray aliases = project.optJSONArray("workspaceKeys");
            if (aliases != null) for (int alias = 0; alias < aliases.length(); alias++) {
                Object value = aliases.opt(alias);
                if (value instanceof String && !((String) value).isBlank()) canonical.put((String) value, key);
            }
        }
        for (int i = 0; i < keys.length(); i++) {
            Object key = keys.get(i);
            String mapped = key instanceof String ? canonical.get(key) : null;
            if (mapped == null) {
                if (!rejectUnknown) continue;
                throw new IllegalArgumentException(t("프로젝트 목록이 변경되었습니다. 다시 선택해 주세요."));
            }
            if (!selected.add(mapped) && rejectUnknown)
                throw new IllegalArgumentException(t("프로젝트 목록이 변경되었습니다. 다시 선택해 주세요."));
        }
        return new JSONArray(selected);
    }
    private JSONObject preview(JSONArray requested) throws Exception {
        plan = null;
        JSONArray keys = validateKeys(requested);
        PortableProjects exported = new PortableProjects();
        Set<String> uploaded = new HashSet<>();
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.getString(i);
            exported = exported.union(PortableProjects.read(documents.exportProject(key), true));
            uploaded.add(documents.projectId(key));
        }
        GitHubProjectSync.Remote incoming = remote.previewRemote();
        // Validate both the remote output and the complete local import before any write.
        incoming.bundle.union(exported);
        String raw = incoming.bundle.json().toString();
        JSONObject preview = documents.previewProjects(raw), result = preview.getJSONObject("result");
        remote.selection(keys);
        Plan next = new Plan();
        next.token = UUID.randomUUID().toString();
        next.localToken = preview.getString("token");
        next.incoming = raw;
        next.outgoing = exported.json().toString();
        next.remoteSha = incoming.sha;
        next.connection = remote.status().toString();
        next.addedEvents = result.getInt("addedEvents");
        plan = next;
        return obj("token", next.token, "summary", result.getJSONObject("summary"),
            "addedEvents", next.addedEvents, "uploadCount", uploaded.size());
    }
    private JSONObject apply(String token) throws Exception {
        Plan pending = plan;
        plan = null; // Single-use even on failure. Retry always starts with a fresh preview.
        if (pending == null || !pending.token.equals(token)
                || !pending.connection.equals(remote.status().toString())
                || !pending.localToken.equals(documents.previewProjects(pending.incoming).getString("token")))
            throw new IOException(t("동기화 정보가 변경되었습니다. 다시 미리보기해 주세요."));
        JSONObject exchange = remote.exchange(pending.outgoing, pending.remoteSha);
        JSONObject imported;
        try { imported = documents.importProjects(pending.incoming, pending.localToken); }
        catch (Exception error) {
            throw new IOException(t("GitHub 확인은 끝났지만 이 기기에 반영하지 못했습니다. 다시 미리보기해 주세요."), error);
        }
        remote.markSynced();
        JSONObject result = status();
        result.put("workspace", imported.getJSONObject("workspace"));
        result.put("addedEvents", pending.addedEvents);
        result.put("changed", exchange.optBoolean("changed"));
        return result;
    }
}
