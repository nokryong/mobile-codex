package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

public class GitHubProjectSyncTest {
    private static JSONObject repo(long id, boolean privateRepo, boolean push, String branch, long size) {
        return obj("id", id, "private", privateRepo, "full_name", "owner/sync", "default_branch", branch,
            "size", size, "permissions", obj("push", push));
    }
    private static JSONObject file(String sha, String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        return obj("type", "file", "encoding", "base64", "size", bytes.length, "sha", sha,
            "content", Base64.getEncoder().encodeToString(bytes));
    }
    private static PortableProjects project(String id, String name, String device) {
        PortableProjects p = new PortableProjects();
        p.create(id, name, device, false);
        return p;
    }

    @Test public void connectRequiresPrivateWritableRepository() throws Exception {
        GitHubProjectSync.Transport publicRepo = (method, endpoint, body, token) -> repo(7, false, true, "main", 0);
        try {
            new GitHubProjectSync.Client(publicRepo).connect("owner/sync", "token");
            fail("public repository accepted");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("비공개")); }

        List<String> calls = new ArrayList<>();
        GitHubProjectSync.Transport valid = (method, endpoint, body, token) -> {
            calls.add(method + " " + endpoint);
            if (endpoint.contains("/contents/")) throw new GitHubProjectSync.HttpError(404);
            return repo(7, true, true, "main", 0);
        };
        GitHubProjectSync.Config config = new GitHubProjectSync.Client(valid).connect("owner/sync", "token");
        assertEquals("owner/sync", config.repository);
        assertEquals(7, config.repositoryId);
        assertEquals("main", config.branch);
        assertEquals(2, calls.size());
    }

    @Test public void branchReferenceIsEncodedAsOneQueryValue() throws Exception {
        String branch = "feature/fonts+large";
        List<String> reads = new ArrayList<>();
        GitHubProjectSync.Transport transport = (method, endpoint, body, token) -> {
            if (endpoint.contains("/contents/")) {
                reads.add(endpoint);
                throw new GitHubProjectSync.HttpError(404);
            }
            return repo(7, true, true, branch, 0);
        };
        GitHubProjectSync.Config config = new GitHubProjectSync.Client(transport).connect("owner/sync", "token");
        assertEquals(branch, config.branch);
        assertEquals(1, reads.size());
        assertTrue(reads.get(0).endsWith("?ref=feature%2Ffonts%2Blarge"));
    }

    @Test public void pullRejectsRepositoryIdentityChange() throws Exception {
        GitHubProjectSync.Transport transport = (method, endpoint, body, token) -> repo(8, true, true, "main", 1);
        GitHubProjectSync.Client client = new GitHubProjectSync.Client(transport);
        try {
            client.pull(new GitHubProjectSync.Config("owner/sync", 7, "main"), "token");
            fail("changed repository identity accepted");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("연결 정보")); }
    }

    @Test public void pushUnionsRemoteAndLocalEvents() throws Exception {
        String remoteRaw = project("proj_remote", "Desktop", "device_desktop").json().toString();
        String localRaw = project("proj_phone", "Phone", "device_phone").json().toString();
        String remoteSha = "a".repeat(40), commitSha = "b".repeat(40);
        final JSONObject[] written = {null};
        GitHubProjectSync.Transport transport = (method, endpoint, body, token) -> {
            if (method.equals("GET") && !endpoint.contains("/contents/")) return repo(7, true, true, "main", 2);
            if (method.equals("GET")) return file(remoteSha, remoteRaw);
            written[0] = body;
            return obj("commit", obj("sha", commitSha));
        };
        JSONObject result = new GitHubProjectSync.Client(transport)
            .push(new GitHubProjectSync.Config("owner/sync", 7, "main"), "token", localRaw);
        assertTrue(result.getBoolean("changed"));
        assertEquals(2, result.getInt("eventCount"));
        assertEquals(remoteSha, written[0].getString("sha"));
        assertEquals("main", written[0].getString("branch"));
        String uploaded = new String(Base64.getDecoder().decode(written[0].getString("content")), StandardCharsets.UTF_8);
        assertEquals(2, PortableProjects.parse(uploaded, true).eventCount());
    }

    @Test public void pushRetriesConcurrentContentsWrite() throws Exception {
        String localRaw = project("proj_phone", "Phone", "device_phone").json().toString();
        String remoteRaw = new PortableProjects().json().toString();
        String remoteSha = "c".repeat(40), commitSha = "d".repeat(40);
        final int[] puts = {0};
        GitHubProjectSync.Transport transport = (method, endpoint, body, token) -> {
            if (method.equals("GET") && !endpoint.contains("/contents/")) return repo(7, true, true, "main", 2);
            if (method.equals("GET")) return file(remoteSha, remoteRaw);
            if (++puts[0] == 1) throw new GitHubProjectSync.HttpError(409);
            return obj("commit", obj("sha", commitSha));
        };
        JSONObject result = new GitHubProjectSync.Client(transport)
            .push(new GitHubProjectSync.Config("owner/sync", 7, "main"), "token", localRaw);
        assertTrue(result.getBoolean("changed"));
        assertEquals(2, puts[0]);
    }
}
