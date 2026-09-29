package dev.mobilecodex.app.core;

import dev.mobilecodex.app.core.sync.*;
import org.json.*;
import org.junit.Test;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class PortableProjectsTest {
    private PortableProjects created() { PortableProjects p = new PortableProjects(); p.create("proj_a", "Original", "device_phone", false); return p; }
    @Test public void concurrentNamesSurviveAndExplicitRenameResolves() {
        PortableProjects a = created(), b = PortableProjects.parse(a.json().toString(), true);
        a.rename("proj_a", "Phone", "device_phone", false); b.rename("proj_a", "Desktop", "device_desktop", false);
        PortableProjects union = a.union(b);
        assertEquals(2, union.conflicts("proj_a").length()); assertEquals("Desktop", union.name("proj_a"));
        assertEquals(union.json().toString(), b.union(a).json().toString());
        assertEquals(union.json().toString(), union.union(a).json().toString());
        union.rename("proj_a", "Chosen", "device_phone", false);
        assertEquals(0, union.conflicts("proj_a").length()); assertEquals("Chosen", union.union(b).name("proj_a"));
    }
    @Test public void exportIncludesOnlySelectedConnectedProjects() {
        PortableProjects p = created(); p.create("proj_b", "B", "device_desktop", false); p.create("proj_private", "Private", "device_phone", false);
        p.link("proj_b", "proj_a", "device_phone", false); p.rename("proj_a", "Together", "device_phone", false);
        PortableProjects out = PortableProjects.parse(p.export(List.of("proj_b")).toString(), true);
        assertEquals(Set.of("proj_a", "proj_b"), out.projectIds()); assertEquals("proj_a", out.canonical("proj_b"));
        assertEquals("Together", out.name("proj_b")); assertEquals(4, out.eventCount());
    }
    @Test public void untrustedBundleIsStrictAndCannotCarryExtraState() throws Exception {
        String raw = created().json().toString();
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), true));
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse("{'format':'mobile-codex-projects'}", true));
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(raw + " trailing", true));
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(" ".repeat(PortableProjects.MAX_BYTES + 1), true));
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse("[".repeat(100) + "0" + "]".repeat(100), true));
        JSONObject bad = new JSONObject(raw); bad.put("auth", "hidden");
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(bad.toString(), true));
        bad.remove("auth"); bad.getJSONArray("events").getJSONObject(0).put("localPath", "/secret");
        assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(bad.toString(), true));
        PortableProjects secret = new PortableProjects(); secret.create("proj_secret", "api_key=hidden", "device_phone", false);
        assertThrows(IllegalArgumentException.class, () -> secret.export(List.of("proj_secret")));
    }
    @Test public void forgedHistoryAndConflictingEventIdsAreRejected() throws Exception {
        PortableProjects a = created(); String raw = a.json().toString(); JSONObject forged = new JSONObject(raw);
        JSONObject event = forged.getJSONArray("events").getJSONObject(0); event.put("name", "different");
        PortableProjects b = PortableProjects.parse(forged.toString(), true);
        assertThrows(IllegalArgumentException.class, () -> a.union(b));
        forged.getJSONArray("events").put(event);
        final String duplicate = forged.toString(); assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(duplicate, true));
        forged = new JSONObject(raw); forged.getJSONArray("events").put(Json.obj("id","evt_fake","deviceId","device_x","projectId","proj_a","kind","renamed","parents",Json.array(),"name","Fake"));
        final String noParent = forged.toString(); assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(noParent, true));
        forged.getJSONArray("events").getJSONObject(1).put("parents",Json.array("evt_fake"));
        final String cycle = forged.toString(); assertThrows(IllegalArgumentException.class, () -> PortableProjects.parse(cycle, true));
    }
    @Test public void importedIdentityDoesNotAutoMergeNamesOrResurrectRemovedBinding() throws Exception {
        ProjectRegistry phone = new ProjectRegistry(); phone.put("content://phone/tree/code","Same name","phone");
        ProjectRegistry desktop = new ProjectRegistry(); desktop.put("C:/work","Same name","desktop");
        String bundle = desktop.exportProject("desktop").toString();
        phone.importProjects(bundle); assertEquals(2, phone.entries(p -> false).length()); assertEquals("phone", phone.selectedKey());
        String imported = phone.entries(p -> false).getJSONObject(1).getString("key");
        assertEquals("", phone.get(imported).uri);
        phone.remove(imported); phone.importProjects(bundle); assertEquals(1, phone.entries(p -> false).length());
        assertEquals("content://phone/tree/code", phone.get("phone").uri);
    }
    @Test public void phaseOneMigrationIsDeterministicAndRetainsIds() throws Exception {
        ProjectRegistry original = new ProjectRegistry(); original.put("content://x/tree/a", "A", "a"); original.put("content://x/tree/b", "B", "b"); original.merge("a","b");
        JSONObject old = new JSONObject(original.toJson()); old.getJSONObject("identities").remove("history");
        ProjectRegistry first = ProjectRegistry.fromJson(old.toString()), retry = ProjectRegistry.fromJson(old.toString());
        assertEquals(first.toJson(),retry.toJson()); assertEquals(original.projectId("a"),first.projectId("a"));
        assertEquals("B", first.displayName("a")); assertEquals(1,first.entries(p -> false).length());
        assertEquals(first.toJson(),ProjectRegistry.fromJson(first.toJson()).toJson());
    }
    @Test public void commonGoldenFixturePreservesConcurrentNames() throws Exception {
        File file = new File("../tests/fixtures/sync/projects-v1.json"); if (!file.exists()) file = new File("tests/fixtures/sync/projects-v1.json");
        PortableProjects p = PortableProjects.parse(Utf8Files.read(file.toPath()), true);
        assertEquals("Desktop name", p.name("proj_a")); assertEquals(2,p.conflicts("proj_a").length()); assertEquals(3,p.eventCount());
    }
    @Test public void importedLongConflictNameCanBeSelectedWithoutChangingItsValue() throws Exception {
        PortableProjects p = created(), other = PortableProjects.parse(p.json().toString(), true);
        String longName = " " + "N".repeat(250) + " ";
        p.rename("proj_a",longName,"device_phone",false); other.rename("proj_a","Short","device_desktop",false);
        ProjectRegistry registry = new ProjectRegistry(); registry.importProjects(p.union(other).json().toString());
        String key = registry.entries(x -> false).getJSONObject(0).getString("key");
        registry.rename(key,longName); assertEquals(longName,registry.displayName(key));
        assertEquals(0,registry.entries(x -> false).getJSONObject(0).getJSONArray("nameConflicts").length());
    }
}
