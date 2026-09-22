package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;

/** Local file previews with compare-before-restore and durable recovery copies. Never changes Git's index. */
public final class ChangeReview {
    public interface Runner { byte[] run(File directory, List<String> args) throws Exception; }
    private static final int LIMIT = 8 * 1024 * 1024;
    private final File backups;
    private final Runner runner;
    private final LinkedHashMap<String, Preview> previews = new LinkedHashMap<>();
    private record Preview(File root, String path, byte[] expected, byte[] replacement, String head, String label, long time) {}
    public ChangeReview(File backups, Runner runner) { this.backups = backups; this.runner = runner; }
    private byte[] git(File root, String... args) throws Exception { return runner.run(root, Arrays.asList(args)); }
    private String head(File root) throws Exception {
        git(root, "rev-parse", "--is-inside-work-tree");
        try { return new String(git(root, "rev-parse", "--verify", "HEAD"), StandardCharsets.UTF_8).trim(); }
        catch (Exception unborn) { return ""; }
    }
    public JSONObject list(File root) throws Exception {
        String status = new String(git(root, "-c", "status.relativePaths=true", "status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames", "--", "."), StandardCharsets.UTF_8);
        JSONArray entries = new JSONArray();
        for (String item : status.split("\u0000")) {
            if (item.length() < 4) continue;
            String path = item.substring(3);
            entries.put(obj("path", path, "status", item.substring(0, 2)));
        }
        return obj("entries", entries, "note", t("Git HEAD와 현재 작업 파일을 비교합니다. 직접 수정한 내용도 포함합니다. 복원은 파일 내용만 바꾸며 스테이징은 유지합니다."));
    }
    public JSONObject preview(File root, String path) throws Exception {
        Path target = safe(root, path); byte[] current = read(target);
        String head = head(root); byte[] original = null;
        if (!head.isEmpty()) {
            String tree = new String(git(root, "ls-tree", "-z", head, "--", path), StandardCharsets.UTF_8);
            if (!tree.isEmpty()) {
                String[] fields = tree.substring(0, tree.indexOf('\t')).split(" ");
                if (!fields[0].equals("100644") && !fields[0].equals("100755")) throw new IOException(t("일반 파일만 복원할 수 있습니다. 링크·하위 저장소는 터미널에서 확인해 주세요."));
                original = git(root, "cat-file", "blob", fields[2]);
            }
        }
        return remember(root, path, current, original, head, t("HEAD 내용으로 복원"));
    }
    private JSONObject remember(File root, String path, byte[] current, byte[] replacement, String head, String label) throws Exception {
        if ((current != null && current.length > LIMIT) || (replacement != null && replacement.length > LIMIT)) throw new IOException(t("8 MiB보다 큰 파일은 터미널에서 검토해 주세요."));
        String token = UUID.randomUUID().toString();
        while (previews.size() >= 8) previews.remove(previews.keySet().iterator().next());
        previews.put(token, new Preview(root.getCanonicalFile(), path, current, replacement, head, label, System.currentTimeMillis()));
        String before = text(replacement), after = text(current);
        boolean limited = before.startsWith(t("[바이너리 파일")) || after.startsWith(t("[바이너리 파일")) || before.startsWith(t("[큰 파일")) || after.startsWith(t("[큰 파일"));
        return obj("token", token, "path", path, "before", before, "after", after, "beforeExists", replacement != null,
            "afterExists", current != null, "actionLabel", label, "canRestore", !Arrays.equals(current, replacement),
            "previewLimited", limited, "beforeSha256", hash(replacement), "afterSha256", hash(current),
            "note", t("현재 파일은 복원 전에 별도로 보관합니다. 다른 곳에서 파일이 바뀌면 복원을 중단합니다. Git 스테이징은 변경하지 않습니다."));
    }
    public JSONObject restore(File root, String token) throws Exception {
        Preview p = previews.get(token);
        if (p == null || !p.root.equals(root.getCanonicalFile()) || System.currentTimeMillis() - p.time > 300000) throw new IOException(t("미리보기가 만료되었거나 프로젝트가 바뀌었습니다. 다시 열어 주세요."));
        Path target = safe(root, p.path);
        if (p.head != null && !p.head.equals(head(root))) throw new IOException(t("Git HEAD가 변경되었습니다. 변경 사항을 다시 확인해 주세요."));
        if (!Arrays.equals(read(target), p.expected)) throw new IOException(t("미리보기 이후 파일이 변경되었습니다. 덮어쓰지 않았습니다."));
        if (Arrays.equals(p.expected, p.replacement)) throw new IOException(t("복원할 변경 사항이 없습니다."));
        Files.createDirectories(backups.toPath()); String id = UUID.randomUUID().toString();
        if (p.expected != null) Files.write(new File(backups, id + ".bin").toPath(), p.expected, StandardOpenOption.CREATE_NEW);
        JSONObject meta = obj("id", id, "root", p.root.getPath(), "path", p.path, "timestamp", System.currentTimeMillis(),
            "existed", p.expected != null, "sha256", hash(p.expected), "expectedAfter", hash(p.replacement), "completed", false);
        File metadata = new File(backups, id + ".json"); save(metadata, meta);
        // Recheck after backup I/O as another application can edit this file independently.
        if (!Arrays.equals(read(safe(root, p.path)), p.expected)) throw new IOException(t("백업 중 파일이 변경되었습니다. 덮어쓰지 않았습니다."));
        if (p.replacement == null) Files.delete(target);
        else {
            if (!Files.isDirectory(target.getParent())) throw new IOException(t("상위 폴더가 없습니다. 먼저 폴더를 복원해 주세요."));
            Path stage = Files.createTempFile(target.getParent(), ".mobile-codex-restore-", ".tmp");
            try {
                Files.write(stage, p.replacement);
                if (Files.exists(target)) {
                    try { Files.setPosixFilePermissions(stage, Files.getPosixFilePermissions(target)); } catch (UnsupportedOperationException ignored) {}
                }
                if (!Arrays.equals(read(safe(root, p.path)), p.expected)) throw new IOException(t("파일이 변경되었습니다. 다시 확인해 주세요."));
                Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(stage); }
        }
        if (!Arrays.equals(read(target), p.replacement)) throw new IOException(t("복원 내용을 검증하지 못했습니다. 복원 사본을 확인해 주세요."));
        meta.put("completed", true); save(metadata, meta); previews.remove(token);
        return obj("ok", true, "path", p.path, "backupId", id);
    }
    public JSONArray history(File root) throws Exception {
        JSONArray entries = new JSONArray(); File[] files = backups.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return entries;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            JSONObject item = parse(Utf8Files.read(file.toPath()));
            if (root.getCanonicalPath().equals(item.optString("root"))) { item.remove("root"); entries.put(item); }
        }
        return entries;
    }
    public JSONObject previewBackup(File root, String id) throws Exception {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException(t("잘못된 복원 기록입니다."));
        JSONObject item = parse(Utf8Files.read(new File(backups, id + ".json").toPath()));
        if (!root.getCanonicalPath().equals(item.optString("root"))) throw new IOException(t("다른 프로젝트의 복원 기록입니다."));
        String path = item.getString("path"); byte[] current = read(safe(root, path));
        if (!item.optBoolean("completed")) throw new IOException(t("완료되지 않은 복원입니다. 보관된 사본은 터미널에서 확인해 주세요: ") + new File(backups, id + ".bin"));
        if (!hash(current).equals(item.getString("expectedAfter"))) throw new IOException(t("복원 이후 파일이 다시 변경되었습니다. 자동으로 덮어쓰지 않습니다."));
        byte[] saved = item.getBoolean("existed") ? Files.readAllBytes(new File(backups, id + ".bin").toPath()) : null;
        if (!hash(saved).equals(item.getString("sha256"))) throw new IOException(t("복원 사본의 검증에 실패했습니다."));
        return remember(root, path, current, saved, null, t("복원 전 내용으로 되돌리기"));
    }
    private static void save(File file, JSONObject value) throws Exception { Utf8Files.write(file.toPath(), value.toString()); }
    private static String hash(byte[] data) { return data == null ? "missing" : WorkspacePath.hash(data); }
    private static String text(byte[] bytes) {
        if (bytes == null) return "";
        if (bytes.length > 1024 * 1024) return t("[큰 파일 · ") + bytes.length + " bytes]";
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.indexOf('\0') < 0 ? text : t("[바이너리 파일 · ") + bytes.length + " bytes]";
        } catch (CharacterCodingException e) { return t("[바이너리 파일 · ") + bytes.length + " bytes]"; }
    }
    private static byte[] read(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT) throw new IOException(t("일반 파일(8 MiB 이하)만 미리보기에서 복원할 수 있습니다."));
        try (InputStream input = Files.newInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n = input.read(block)) != -1) { if (out.size() + n > LIMIT) throw new IOException(t("파일 크기가 변경되었습니다.")); out.write(block, 0, n); }
            return out.toByteArray();
        }
    }
    private static Path safe(File root, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\0') >= 0) throw new IOException(t("잘못된 파일 경로입니다."));
        Path base = root.getCanonicalFile().toPath(), current = base;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) throw new IOException(t("작업 폴더 밖이나 Git 내부 파일은 복원할 수 없습니다."));
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException(t("링크를 따라 복원하지 않습니다."));
        }
        return current;
    }
}
