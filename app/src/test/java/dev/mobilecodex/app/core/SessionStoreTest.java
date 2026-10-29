package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

public class SessionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File target() { return new File(temporary.getRoot(), "sessions.json"); }
    private JSONArray record() { return array(obj("id", "kept", "workspaceKey", "project", "deletionPending", true,
        "draftId", "draft", "messages", array(obj("id", "message", "text", "hello")))); }
    @Test public void missingOnlyIsAnEmptyWritableFirstRun() throws Exception {
        SessionStore store = new SessionStore(target());
        assertEquals("missing", store.status().getString("state"));
        store.save(record());
        assertEquals(record().toString(), new SessionStore(target()).sessions().toString());
        assertEquals("loaded", new SessionStore(target()).status().getString("state"));
    }
    @Test public void corruptCurrentCannotBeReplacedByAnEmptySaveOrDamageBackup() throws Exception {
        SessionStore store = new SessionStore(target()); store.save(record());
        Path backup = target().toPath().resolveSibling("sessions.json.last-good");
        byte[] good = Files.readAllBytes(backup), corrupt = "[broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target().toPath(), corrupt);
        SessionStore reopened = new SessionStore(target());
        assertEquals("parse_failed", reopened.status().getString("state"));
        assertThrows(IOException.class, () -> reopened.save(new JSONArray()));
        assertArrayEquals(corrupt, Files.readAllBytes(target().toPath()));
        assertArrayEquals(good, Files.readAllBytes(backup));
    }
    @Test public void readFailureIsDifferentFromMissingAndBlocksSaving() throws Exception {
        assertTrue(target().mkdir());
        SessionStore store = new SessionStore(target());
        assertEquals("read_failed", store.status().getString("state"));
        assertThrows(IOException.class, () -> store.save(record()));
        assertTrue(target().isDirectory());
    }
    @Test public void corruptBackupIsNeverOfferedOrApplied() throws Exception {
        Utf8Files.write(target().toPath(), "bad current");
        Utf8Files.write(target().toPath().resolveSibling("sessions.json.last-good"), "{bad backup}");
        SessionStore store = new SessionStore(target());
        assertFalse(store.status().has("candidateSha256"));
        assertThrows(IOException.class, () -> store.recover("0".repeat(64)));
        assertEquals("bad current", Utf8Files.read(target().toPath()));
    }
    @Test public void explicitVerifiedRecoveryPreservesOriginalAndResumesSaving() throws Exception {
        SessionStore store = new SessionStore(target()); store.save(record());
        Utf8Files.write(target().toPath(), "bad current");
        store = new SessionStore(target());
        String hash = store.status().getString("candidateSha256");
        assertEquals(record().toString(), store.recover(hash).toString());
        assertFalse(store.recoveryRequired());
        assertEquals(1, temporary.getRoot().listFiles((d, n) -> n.startsWith("sessions.json.recovery-")).length);
        // Deletion after restore is durable. A valid empty current never resurrects the older candidate.
        store.save(new JSONArray());
        assertEquals(0, new SessionStore(target()).sessions().length());
    }
    @Test public void failedWritesAndReplacementsLeaveAValidCurrentOrRecoveryCandidate() throws Exception {
        new SessionStore(target()).save(record());
        byte[] original = Files.readAllBytes(target().toPath());
        for (int failAt = 1; failAt <= 6; failAt++) {
            final int stage = failAt;
            SessionStore broken = new SessionStore(target()) {
                int calls;
                @Override protected void writeSynced(Path p, byte[] data) throws IOException {
                    if (++calls == stage) throw new IOException("ENOSPC"); super.writeSynced(p, data);
                }
                @Override protected void replace(Path from, Path to) throws IOException {
                    if (++calls == stage) throw new IOException("rename failed"); super.replace(from, to);
                }
            };
            try { broken.save(array(obj("id", "new"))); } catch (IOException expected) { }
            SessionStore reopened = new SessionStore(target());
            assertFalse(reopened.recoveryRequired());
            assertTrue(reopened.status().has("candidateSha256"));
            assertEquals(0, temporary.getRoot().listFiles((d, n) -> n.endsWith(".tmp")).length);
            Files.write(target().toPath(), original);
        }
    }
    @Test public void externalCorruptionBetweenLoadAndSaveLocksTheStore() throws Exception {
        SessionStore store = new SessionStore(target()); store.save(record());
        Utf8Files.write(target().toPath(), "corruption");
        assertThrows(IOException.class, () -> store.save(new JSONArray()));
        assertTrue(store.recoveryRequired());
        assertEquals("corruption", Utf8Files.read(target().toPath()));
    }
}
