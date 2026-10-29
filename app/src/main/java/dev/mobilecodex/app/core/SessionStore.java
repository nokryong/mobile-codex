package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import static dev.mobilecodex.app.core.Json.*;

/** Fail closed on existing unreadable data. Backups are candidates, never automatic restores. */
public class SessionStore {
    public enum State { MISSING, LOADED, READ_FAILED, PARSE_FAILED }
    private final Path file, backup;
    private State state;
    private byte[] expected;
    private JSONArray loaded = new JSONArray();
    private String candidateSha = "";
    private int candidateCount;

    public SessionStore(File file) {
        this.file = file.toPath(); backup = file.toPath().resolveSibling(file.getName() + ".last-good");
        load();
        refreshCandidate();
    }
    private void load() {
        loaded = new JSONArray(); expected = null;
        if (Files.notExists(file)) { state = State.MISSING; return; }
        try { expected = Files.readAllBytes(file); }
        catch (IOException error) { state = State.READ_FAILED; return; }
        try { loaded = parse(expected); state = State.LOADED; }
        catch (IOException error) { state = State.PARSE_FAILED; }
    }
    public synchronized JSONArray sessions() throws IOException { return parse(loaded.toString().getBytes(StandardCharsets.UTF_8)); }
    public synchronized boolean recoveryRequired() { return state == State.READ_FAILED || state == State.PARSE_FAILED; }
    public synchronized JSONObject status() {
        JSONObject result = obj("state", state.name().toLowerCase(java.util.Locale.ROOT), "recoveryRequired", recoveryRequired());
        return candidateSha.isEmpty() ? result : obj("state", state.name().toLowerCase(java.util.Locale.ROOT),
            "recoveryRequired", recoveryRequired(), "candidateSha256", candidateSha, "candidateSessions", candidateCount);
    }
    private void refreshCandidate() {
        candidateSha = ""; candidateCount = 0;
        try { byte[] bytes = candidate(); candidateSha = digest(bytes); candidateCount = parse(bytes).length(); }
        catch (IOException ignored) { }
    }
    private static JSONArray parse(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONArray) || tokener.nextClean() != 0) throw new IOException("Invalid session array");
            JSONArray sessions = (JSONArray) value;
            for (int i = 0; i < sessions.length(); i++) if (!(sessions.opt(i) instanceof JSONObject)) throw new IOException("Invalid session entry");
            return sessions;
        } catch (Exception error) { throw new IOException("Invalid session snapshot", error); }
    }
    private static String digest(byte[] bytes) throws IOException {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (Exception error) { throw new IOException(error); }
    }
    private byte[] envelope(byte[] bytes) throws IOException {
        return obj("sha256", digest(bytes), "sessions", new String(bytes, StandardCharsets.UTF_8)).toString().getBytes(StandardCharsets.UTF_8);
    }
    private byte[] candidate() throws IOException {
        try {
            JSONObject saved = new JSONObject(new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
            byte[] bytes = saved.getString("sessions").getBytes(StandardCharsets.UTF_8);
            if (!digest(bytes).equals(saved.getString("sha256"))) throw new IOException("Backup checksum mismatch");
            parse(bytes); return bytes;
        } catch (Exception error) { throw new IOException("No verified session recovery candidate", error); }
    }
    protected void writeSynced(Path path, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path.toFile())) { out.write(bytes); out.getFD().sync(); }
    }
    protected void replace(Path source, Path target) throws IOException {
        // Same-directory atomic replacement: unsupported filesystems fail without deleting the original.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void atomic(Path target, byte[] bytes) throws IOException {
        Path pending = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try { writeSynced(pending, bytes); replace(pending, target); }
        finally { Files.deleteIfExists(pending); }
    }
    private void checkUnchanged() throws IOException {
        try {
            if (expected == null ? !Files.notExists(file) : !Arrays.equals(expected, Files.readAllBytes(file))) {
                load(); state = State.READ_FAILED;
                throw new IOException("Session file changed; explicit recovery or reload required");
            }
        } catch (IOException failure) { state = State.READ_FAILED; throw failure; }
    }
    public synchronized void save(JSONArray value) throws IOException {
        if (recoveryRequired()) throw new IOException("Session recovery is required before saving");
        checkUnchanged();
        byte[] next = value.toString().getBytes(StandardCharsets.UTF_8); parse(next);
        // Before replacing current, preserve the verified current bytes. A corrupt
        // current can never reach this step or overwrite the good candidate.
        if (expected != null) atomic(backup, envelope(expected));
        atomic(file, next);
        expected = next; loaded = parse(next); state = State.LOADED;
        // A failure refreshing the candidate still leaves the committed current
        // and the previous verified candidate intact.
        try { atomic(backup, envelope(next)); } catch (IOException ignored) { }
        refreshCandidate();
    }
    public synchronized JSONArray recover(String sha256) throws IOException {
        byte[] bytes = candidate();
        if (!digest(bytes).equals(sha256)) throw new IOException("Recovery candidate changed");
        // Explicit caller consent is required because an older candidate may
        // contain conversations deleted since that snapshot. Preserve evidence.
        if (!Files.notExists(file)) {
            byte[] original = Files.readAllBytes(file);
            Path preserved = file.resolveSibling(file.getFileName() + ".recovery-" + UUID.randomUUID());
            writeSynced(preserved, original);
        }
        atomic(file, bytes); load();
        refreshCandidate();
        if (recoveryRequired()) throw new IOException("Recovered snapshot could not be loaded");
        return sessions();
    }
}
