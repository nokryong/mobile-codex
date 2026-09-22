package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import dev.mobilecodex.app.core.ProjectRegistry;
import dev.mobilecodex.app.core.WorkspacePath;
import static dev.mobilecodex.app.core.Json.*;

/** SAF operations, with verified local-volume mapping for the shell working directory. */
public final class DocumentStore {
    public static final int TEXT_LIMIT = 1024 * 1024;
    private static final int BACKUP_LIMIT = 32 * 1024 * 1024;
    private final Context context;
    private Uri tree;
    private String label = "";
    private String detachedKey = "", detachedName = "";
    private ProjectRegistry projects;
    private final File backups;
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    public DocumentStore(Context context) {
        this.context = context;
        backups = new File(context.getFilesDir(), "recovery");
        backups.mkdirs();
        String registry = context.getSharedPreferences("projects", 0).getString("registry", "");
        projects = ProjectRegistry.fromJson(registry);
        // Migrate the old single-folder preference into the multi-project registry once.
        String saved = context.getSharedPreferences("workspace", 0).getString("uri", "");
        if (registry.isBlank() && !saved.isEmpty()) {
            try {
                Uri uri = Uri.parse(saved); Node node = query(documentUri(uri));
                projects.put(uri.toString(), node.name, WorkspacePath.hash(uri.toString())); saveProjects();
            } catch (Exception ignored) { }
        }
        try { activate(projects.selected()); } catch (Exception ignored) { tree = null; }
    }
    public synchronized JSONObject select(Uri uri) throws Exception { return select(uri, ""); }
    /** Registers a new project or reconnects the stable project key to a replacement SAF URI. */
    public synchronized JSONObject select(Uri uri, String projectKey) throws Exception {
        if (!"content".equals(uri.getScheme()) || !DocumentsContract.isTreeUri(uri))
            throw new IllegalArgumentException(t("폴더를 선택해 주세요."));
        Node selected = query(documentUri(uri));
        if (!selected.directory()) throw new IOException(t("폴더가 아닙니다."));
        ProjectRegistry.Project existing = projectKey == null || projectKey.isBlank() ? null : projects.get(projectKey);
        projects.put(uri.toString(), selected.name, existing == null ? projectKey : existing.key);
        tree = uri;
        label = selected.name;
        detachedKey = ""; detachedName = "";
        context.getSharedPreferences("workspace", 0).edit().putString("uri", uri.toString()).apply();
        saveProjects();
        return workspace();
    }
    /** Switches among stored projects without asking Android's picker again. Empty selects general chat. */
    public synchronized JSONObject selectProject(String key) throws Exception {
        if (projects.removed(key)) { detachedKey = key; detachedName = t("연결 해제된 프로젝트"); tree = null; label = detachedName; return workspace(); }
        detachedKey = ""; detachedName = "";
        projects.select(key);
        ProjectRegistry.Project project = projects.selected();
        tree = null; label = project == null ? "" : project.name;
        try { activate(project); } catch (Exception ignored) { tree = null; }
        saveProjects();
        return workspace();
    }
    public synchronized JSONArray projects() { return projects.entries(this::available); }
    /** Removes only the registry entry. The underlying SAF folder and its files are untouched. */
    public synchronized JSONObject removeProject(String key) throws IOException {
        ProjectRegistry staged = ProjectRegistry.fromJson(projects.toJson());
        boolean current = key != null && (key.equals(projects.selectedKey()) || key.equals(detachedKey));
        ProjectRegistry.Project removed = staged.remove(key);
        if (!context.getSharedPreferences("projects", 0).edit().putString("registry", staged.toJson()).commit())
            throw new IOException(t("프로젝트 목록 변경을 저장하지 못했습니다."));
        projects = staged;
        if (current) { tree = null; label = ""; detachedKey = ""; detachedName = ""; }
        return obj("key", removed.key, "name", removed.name);
    }
    public synchronized String restoreProjectKey(String sessionId, String oldKey, String oldName) {
        String before = projects.toJson();
        String key = projects.restoreLegacyKey(sessionId, oldKey, oldName);
        if (!before.equals(projects.toJson())) saveProjects();
        return key;
    }
    private void saveProjects() {
        context.getSharedPreferences("projects", 0).edit().putString("registry", projects.toJson()).apply();
    }
    private Uri documentUri(Uri value) {
        return DocumentsContract.buildDocumentUriUsingTree(value, DocumentsContract.getTreeDocumentId(value));
    }
    private boolean available(ProjectRegistry.Project project) {
        if (project == null) return true;
        if (project.uri.isBlank()) return false;
        try {
            Uri uri = Uri.parse(project.uri);
            for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions())
                if (permission.isReadPermission() && permission.getUri().equals(uri)) return true;
        } catch (Exception ignored) { }
        return false;
    }
    private void activate(ProjectRegistry.Project project) throws Exception {
        if (project == null || !available(project)) return;
        Uri uri = Uri.parse(project.uri); Node selected = query(documentUri(uri));
        if (!selected.directory()) throw new IOException(t("폴더가 아닙니다."));
        tree = uri; label = project.name;
    }
    public synchronized JSONObject workspace() {
        ProjectRegistry.Project project = projects.selected();
        if (!detachedKey.isBlank()) return obj("selected", true, "name", detachedName, "key", detachedKey, "available", false, "detached", true);
        return obj("selected", project != null, "name", project == null ? "" : project.name,
            "key", project == null ? "" : project.key, "available", project == null || (tree != null && available(project)));
    }
    public synchronized String key() { return detachedKey.isBlank() ? projects.selectedKey() : detachedKey; }
    public synchronized void requireWorkspaceAvailable() throws IOException {
        if (!detachedKey.isBlank()) throw new IOException(t("연결 해제된 프로젝트 대화입니다. 폴더를 다시 연결하거나 일반 대화에서 새 작업을 시작해 주세요."));
        ProjectRegistry.Project project = projects.selected();
        if (project != null) {
            try {
                if (tree == null || !available(project) || !query(documentUri(tree)).directory())
                    throw new IOException(t("작업 폴더를 열 수 없습니다."));
            } catch (Exception e) {
                tree = null;
                throw new IOException(t("이 프로젝트 폴더에 접근할 수 없습니다. 프로젝트에서 폴더를 다시 연결해 주세요."), e);
            }
        }
    }
    /** Makes a project-relative mention reachable by Codex, copying SAF-only files into app storage. */
    synchronized JSONObject mention(String path, AttachmentStore attachments) throws Exception {
        Node node = resolve(path);
        if (node.directory()) throw new IOException(t("폴더는 대화에 첨부할 수 없습니다."));
        File direct = directDirectory();
        if (direct != null) {
            File local = new File(direct, path).getCanonicalFile();
            if (!local.getPath().startsWith(direct.getCanonicalPath() + File.separator) || !local.isFile())
                throw new IOException(t("파일을 읽을 수 없습니다."));
            return obj("name", node.name, "path", local.getAbsolutePath());
        }
        try (InputStream in = context.getContentResolver().openInputStream(node.uri)) {
            if (in == null) throw new IOException(t("파일을 읽을 수 없습니다."));
            JSONObject stored = attachments.store(in, node.name, node.mime);
            return obj("name", node.name, "path", stored.getString("path"));
        }
    }
    synchronized JSONObject image(String path, ImageStore images) throws Exception {
        Node n = resolve(path);
        if (n.directory()) throw new IOException(t("이미지 파일을 선택해 주세요."));
        try (InputStream in = context.getContentResolver().openInputStream(n.uri)) {
            if (in == null) throw new IOException(t("이미지 파일을 읽을 수 없습니다."));
            return images.store(in, n.name);
        }
    }
    /** Primary-storage document trees have a documented volume:relative-path ID. */
    public synchronized File directDirectory() {
        if (!workspace().optBoolean("available")) return null;
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int separator = id.indexOf(':');
            if (separator < 0) return null;
            String volume = id.substring(0, separator), relative = id.substring(separator + 1);
            WorkspacePath.segments(relative);
            File root;
            if (volume.equals("primary")) root = Environment.getExternalStorageDirectory().getCanonicalFile();
            else if (volume.equals("home")) root = new File(Environment.getExternalStorageDirectory(), "Documents").getCanonicalFile();
            else if (volume.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) root = new File("/storage", volume).getCanonicalFile();
            else return null;
            File folder = new File(root, relative).getCanonicalFile();
            if (!(folder.equals(root) || folder.getPath().startsWith(root.getPath() + File.separator))) return null;
            return folder.isDirectory() && folder.canRead() ? folder : null;
        } catch (Exception ignored) { return null; }
    }
    private void requireTree() throws IOException {
        requireWorkspaceAvailable();
        if (tree == null) throw new IOException(t("먼저 작업 폴더를 선택해 주세요."));
    }
    private Node query(Uri uri) throws IOException {
        try (Cursor c = context.getContentResolver().query(uri, COLUMNS, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new FileNotFoundException(t("파일을 찾을 수 없습니다."));
            return new Node(uri, c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5));
        }
    }
    private Node root() throws IOException {
        requireTree();
        return query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)));
    }
    private List<Node> children(Node parent) throws IOException {
        if (!parent.directory()) throw new IOException(t("폴더가 아닙니다."));
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id);
        ArrayList<Node> result = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(children, COLUMNS, null, null, null)) {
            if (c == null) throw new IOException(t("폴더를 열 수 없습니다."));
            while (c.moveToNext()) {
                if (result.size() >= 5000) throw new IOException(t("한 폴더의 항목이 5,000개를 초과합니다. 더 작은 폴더를 선택해 주세요."));
                result.add(new Node(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5)));
            }
        }
        result.sort(Comparator.comparing((Node n) -> !n.directory()).thenComparing(n -> n.name.toLowerCase(Locale.ROOT)));
        return result;
    }
    private Node resolve(String path) throws IOException {
        Node n = root();
        for (String segment : WorkspacePath.segments(path)) {
            Node found = null;
            for (Node child : children(n)) {
                if (child.name.equals(segment)) {
                    if (found != null) throw new IOException(t("이름이 같은 항목이 여러 개입니다: ") + segment);
                    found = child;
                }
            }
            if (found == null) throw new FileNotFoundException(t("파일을 찾을 수 없습니다: ") + path);
            n = found;
        }
        return n;
    }
    private boolean exists(String path) throws IOException {
        try { resolve(path); return true; } catch (FileNotFoundException e) { return false; }
    }
    public synchronized JSONObject list(String path) throws IOException {
        JSONArray entries = new JSONArray();
        for (Node n : children(resolve(path))) entries.put(n.json(path.isEmpty() ? n.name : path + "/" + n.name));
        return obj("path", path, "entries", entries);
    }
    public synchronized JSONObject search(String text) throws IOException {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(t("검색어를 입력해 주세요."));
        String needle = text.toLowerCase(Locale.ROOT);
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add("");
        HashSet<String> seen = new HashSet<>();
        JSONArray results = new JSONArray();
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000 && results.length() < 200) {
            String path = queue.remove();
            Node parent = resolve(path);
            if (!seen.add(parent.id)) continue;
            for (Node n : children(parent)) {
                if (++visited > 3000) break;
                String childPath = path.isEmpty() ? n.name : path + "/" + n.name;
                if (n.name.toLowerCase(Locale.ROOT).contains(needle)) results.put(n.json(childPath));
                if (n.directory() && WorkspacePath.segments(childPath).size() < 32) queue.add(childPath);
                if (results.length() >= 200) break;
            }
        }
        return obj("entries", results, "truncated", !queue.isEmpty() || visited >= 3000 || results.length() >= 200);
    }
    private byte[] bytes(Node n, int limit) throws IOException {
        if (n.directory()) throw new IOException(t("폴더는 텍스트로 열 수 없습니다."));
        if (n.size > limit) throw new IOException(t("파일 크기 제한을 초과했습니다 (") + (limit / 1024 / 1024) + " MiB).");
        try (InputStream in = context.getContentResolver().openInputStream(n.uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException(t("파일을 읽을 수 없습니다."));
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException(t("파일 크기 제한을 초과했습니다."));
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    private String decode(byte[] data) throws IOException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            if (value.indexOf('\0') >= 0) throw new IOException(t("텍스트 파일이 아닙니다."));
            return value;
        } catch (CharacterCodingException e) { throw new IOException(t("UTF-8 텍스트 파일만 편집할 수 있습니다.")); }
    }
    public synchronized JSONObject read(String path) throws IOException {
        byte[] data = bytes(resolve(path), TEXT_LIMIT);
        return obj("path", path, "content", decode(data), "sha256", WorkspacePath.hash(data));
    }
    public synchronized Mutation prepare(String operation, JSONObject args) throws Exception {
        requireTree();
        String path = args.getString("path");
        WorkspacePath.segments(path);
        if (path.isEmpty()) throw new IOException(t("작업 폴더 자체는 변경할 수 없습니다."));
        Node n = null;
        byte[] before = null;
        String title;
        String preview = path;
        if (operation.equals("mobile_create") || operation.equals("mobile_mkdir")) {
            if (exists(path)) throw new IOException(t("같은 이름의 파일 또는 폴더가 이미 있습니다."));
            if (!resolve(WorkspacePath.parent(path)).directory()) throw new IOException(t("상위 폴더가 없습니다."));
            title = operation.equals("mobile_create") ? t("파일 만들기") : t("폴더 만들기");
        } else {
            n = resolve(path);
            title = switch (operation) {
                case "mobile_write" -> t("파일 수정");
                case "mobile_delete" -> t("파일 삭제");
                case "mobile_rename" -> t("이름 변경");
                case "mobile_move" -> t("파일 이동");
                default -> throw new IOException(t("지원하지 않는 작업입니다."));
            };
        }
        if (operation.equals("mobile_write")) {
            before = bytes(n, TEXT_LIMIT);
            WorkspacePath.requireVersion(args.getString("expectedSha256"), before);
            preview += t("\n\n현재 내용\n") + excerpt(decode(before)) + t("\n\n수정할 내용\n") + excerpt(args.getString("content"));
        }
        if (operation.equals("mobile_create")) preview += "\n\n" + excerpt(args.getString("content"));
        if (operation.equals("mobile_create") || operation.equals("mobile_write")) {
            if (args.getString("content").getBytes(StandardCharsets.UTF_8).length > TEXT_LIMIT)
                throw new IOException(t("텍스트는 최대 1 MiB까지 저장할 수 있습니다."));
        }
        if (operation.equals("mobile_delete")) {
            if (!n.directory() && n.size <= BACKUP_LIMIT) before = bytes(n, BACKUP_LIMIT);
            preview += n.directory() ? t("\n\n폴더와 그 안의 파일을 삭제합니다. 폴더 전체의 복구 사본은 생성하지 않습니다.") :
                before != null ? t("\n\n삭제 전에 앱 안에 복구용 사본을 보관합니다.") : t("\n\n이 큰 파일은 복구 사본 없이 삭제합니다.");
        }
        if (operation.equals("mobile_rename")) {
            String name = args.getString("name"); WorkspacePath.checkName(name);
            String parent = WorkspacePath.parent(path);
            String target = parent.isEmpty() ? name : parent + "/" + name;
            if (exists(target)) throw new IOException(t("같은 이름의 항목이 이미 있습니다."));
            preview += "\n→ " + target;
        }
        if (operation.equals("mobile_move")) {
            String destination = args.getString("destination"); WorkspacePath.segments(destination);
            if (destination.equals(path) || destination.startsWith(path + "/")) throw new IOException(t("자기 안으로 이동할 수 없습니다."));
            if (!resolve(destination).directory()) throw new IOException(t("대상 폴더가 아닙니다."));
            if ((n.flags & Document.FLAG_SUPPORTS_MOVE) == 0) throw new IOException(t("이 저장소는 파일 이동을 지원하지 않습니다."));
            String target = destination.isEmpty() ? n.name : destination + "/" + n.name;
            if (exists(target)) throw new IOException(t("대상 폴더에 같은 이름의 항목이 있습니다."));
            preview += "\n→ " + target;
        }
        return new Mutation(operation, parse(args.toString()), tree.toString(), n == null ? "" : n.id,
            n == null ? 0 : n.modified, before, title, preview);
    }
    private String excerpt(String s) { return s.length() <= 6000 ? s : s.substring(0, 6000) + t("\n… (미리보기 생략)"); }
    public synchronized JSONObject commit(Mutation m) throws Exception {
        requireTree();
        if (!tree.toString().equals(m.tree)) throw new IOException(t("작업 폴더가 변경되어 요청을 취소했습니다."));
        String path = m.args.getString("path");
        // Revalidate after the user has spent time reviewing the confirmation.
        Mutation now = prepare(m.operation, m.args);
        if (!now.documentId.equals(m.documentId) || now.modified != m.modified ||
            (m.before != null && !Arrays.equals(now.before, m.before)))
            throw new IOException(t("확인하는 동안 파일이 변경되었습니다. 다시 시도해 주세요."));
        Node parent = resolve(WorkspacePath.parent(path));
        String recoveryId = "";
        if (m.before != null) recoveryId = backup(m, resolve(path).mime);
        switch (m.operation) {
            case "mobile_create", "mobile_mkdir" -> {
                String mime = m.operation.equals("mobile_mkdir") ? Document.MIME_TYPE_DIR : "text/plain";
                Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent.uri, mime, WorkspacePath.name(path));
                if (created == null) throw new IOException(t("생성하지 못했습니다."));
                if (m.operation.equals("mobile_create")) write(created, m.args.getString("content").getBytes(StandardCharsets.UTF_8));
                path = WorkspacePath.parent(path).isEmpty() ? query(created).name : WorkspacePath.parent(path) + "/" + query(created).name;
            }
            case "mobile_write" -> write(resolve(path).uri, m.args.getString("content").getBytes(StandardCharsets.UTF_8));
            case "mobile_delete" -> {
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), resolve(path).uri)) throw new IOException(t("삭제하지 못했습니다."));
            }
            case "mobile_rename" -> {
                if (DocumentsContract.renameDocument(context.getContentResolver(), resolve(path).uri, m.args.getString("name")) == null)
                    throw new IOException(t("이름을 변경하지 못했습니다."));
            }
            case "mobile_move" -> {
                if (DocumentsContract.moveDocument(context.getContentResolver(), resolve(path).uri, parent.uri,
                    resolve(m.args.getString("destination")).uri) == null) throw new IOException(t("이동하지 못했습니다."));
            }
            default -> throw new IOException(t("지원하지 않는 작업입니다."));
        }
        if (!recoveryId.isEmpty()) {
            File metadata = new File(backups, recoveryId + ".json");
            JSONObject saved = parse(dev.mobilecodex.app.core.Utf8Files.read(metadata.toPath()));
            if (m.operation.equals("mobile_write")) saved.put("afterSha256", WorkspacePath.hash(bytes(resolve(path), TEXT_LIMIT)));
            else if (m.operation.equals("mobile_delete")) saved.put("afterMissing", true);
            saved.put("completed", true); dev.mobilecodex.app.core.Utf8Files.write(metadata.toPath(), saved.toString());
        }
        return obj("ok", true, "operation", m.operation, "path", path, "recoveryId", recoveryId);
    }
    private void write(Uri uri, byte[] data) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException(t("파일을 저장할 수 없습니다."));
            out.write(data); out.flush();
        }
        if (!Arrays.equals(bytes(query(uri), TEXT_LIMIT), data)) throw new IOException(t("저장한 내용을 검증하지 못했습니다. 복구 사본을 확인해 주세요."));
    }
    private String backup(Mutation m, String mime) throws Exception {
        String id = UUID.randomUUID().toString();
        if (backups.getUsableSpace() < m.before.length + 10 * 1024 * 1024L) throw new IOException(t("복구 사본을 저장할 공간이 부족합니다."));
        Files.write(new File(backups, id + ".bin").toPath(), m.before);
        JSONObject meta = obj("id", id, "tree", m.tree, "workspace", label, "path", m.args.getString("path"),
            "mime", mime, "operation", m.operation, "timestamp", System.currentTimeMillis(), "sha256", WorkspacePath.hash(m.before));
        Files.write(new File(backups, id + ".json").toPath(), meta.toString().getBytes(StandardCharsets.UTF_8));
        return id;
    }
    public synchronized JSONArray recoveryList() throws Exception {
        JSONArray out = new JSONArray();
        File[] files = backups.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            JSONObject meta = parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (tree != null && meta.optString("tree").equals(tree.toString())) {
                meta.remove("tree"); out.put(meta);
            }
            if (out.length() >= 100) break;
        }
        return out;
    }
    private JSONObject recoveryMetadata(String id) throws Exception {
        requireTree();
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException(t("잘못된 복구 항목입니다."));
        JSONObject meta = parse(dev.mobilecodex.app.core.Utf8Files.read(new File(backups, id + ".json").toPath()));
        if (!tree.toString().equals(meta.optString("tree"))) throw new IOException(t("다른 프로젝트의 복구 항목입니다."));
        return meta;
    }
    public synchronized JSONObject previewRecovery(String id) throws Exception {
        JSONObject meta = recoveryMetadata(id); byte[] before = Files.readAllBytes(new File(backups, id + ".bin").toPath());
        WorkspacePath.requireVersion(meta.getString("sha256"), before);
        String path = meta.getString("path"), current = "", reason = "", currentHash = "";
        boolean present = exists(path), canRestore = meta.optBoolean("completed") && before.length <= TEXT_LIMIT;
        if (present) {
            byte[] after = bytes(resolve(path), TEXT_LIMIT); current = decode(after); currentHash = WorkspacePath.hash(after);
            if (!WorkspacePath.hash(after).equals(meta.optString("afterSha256"))) { canRestore = false; reason = t("파일이 이후에 변경되었습니다. 사본을 다른 위치에 저장해서 비교해 주세요."); }
        } else if (!meta.optBoolean("afterMissing")) { canRestore = false; reason = t("현재 파일이 없습니다. 사본을 다른 위치에 저장해 주세요."); }
        if (!meta.optBoolean("completed")) reason = t("이전 버전 또는 완료 상태를 확인할 수 없는 사본입니다. 다른 위치에 저장할 수 있습니다.");
        return obj("id", id, "path", path, "before", before.length <= TEXT_LIMIT ? decode(before) : t("[1 MiB보다 큰 사본 · 다른 위치에 저장해 확인하세요]"), "after", current, "currentSha256", currentHash, "beforeExists", true, "afterExists", present,
            "canRestore", canRestore, "actionLabel", t("이 사본으로 복원"), "note", reason.isEmpty() ? t("수정·삭제 직전 사본입니다. 복원 전에 현재 파일을 다시 확인합니다.") : reason);
    }
    public synchronized JSONObject recoveryMutation(String id) throws Exception {
        JSONObject preview = previewRecovery(id);
        if (!preview.getBoolean("canRestore")) throw new IOException(preview.optString("note", t("이 사본을 자동 복원할 수 없습니다.")));
        String path = preview.getString("path");
        if (preview.getBoolean("afterExists")) return obj("operation", "mobile_write", "arguments", obj("path", path, "content", preview.getString("before"), "expectedSha256", preview.getString("currentSha256")));
        return obj("operation", "mobile_create", "arguments", obj("path", path, "content", preview.getString("before")));
    }
    /** Exports a backup to a fresh user-picked destination; never overwrites the source. */
    public synchronized void exportRecovery(String id, Uri destination) throws Exception {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException(t("잘못된 복구 항목입니다."));
        byte[] data = Files.readAllBytes(new File(backups, id + ".bin").toPath());
        JSONObject meta = parse(dev.mobilecodex.app.core.Utf8Files.read(new File(backups, id + ".json").toPath()));
        WorkspacePath.requireVersion(meta.getString("sha256"), data);
        try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException(t("복구 사본을 내보낼 수 없습니다."));
            out.write(data);
        }
    }
    public static final class Mutation {
        public final String operation, tree, documentId, title, preview;
        public final JSONObject args;
        public final long modified;
        public final byte[] before;
        Mutation(String operation, JSONObject args, String tree, String documentId, long modified, byte[] before, String title, String preview) {
            this.operation = operation; this.args = args; this.tree = tree; this.documentId = documentId;
            this.modified = modified; this.before = before; this.title = title; this.preview = preview;
        }
    }
    private static final class Node {
        final Uri uri;
        final String id, name, mime;
        final long size, modified;
        final int flags;
        Node(Uri uri, String id, String name, String mime, long size, long modified, int flags) {
            this.uri = uri; this.id = id; this.name = name; this.mime = mime; this.size = size; this.modified = modified; this.flags = flags;
        }
        boolean directory() { return Document.MIME_TYPE_DIR.equals(mime); }
        JSONObject json(String path) { return obj("name", name, "path", path, "directory", directory(), "mime", mime, "size", size, "modified", modified); }
    }
}
