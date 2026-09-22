package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import dev.mobilecodex.app.core.*;
import static dev.mobilecodex.app.core.Json.*;

/** Owns one local app-server process, outside Activity/WebView lifecycle. */
public final class Engine {
    public interface Ui {
        void event(String name, JSONObject data);
        void approval(Approval approval);
    }
    public interface Reply { void complete(JSONObject result, Throwable error); }
    /** Narrow in-process seam for protocol regression tests; production always uses RpcClient. */
    interface TestTransport { JSONObject call(String method, JSONObject params) throws Exception; }
    public static final class Approval {
        public final String id = UUID.randomUUID().toString();
        public final String title, message;
        public final CompletableFuture<Boolean> decision = new CompletableFuture<>();
        Approval(String title, String message) { this.title = title; this.message = message; }
    }
    private final Context context;
    public final DocumentStore documents;
    final ImageStore images;
    final AttachmentStore attachments;
    public final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService approvalTimer = Executors.newSingleThreadScheduledExecutor();
    private volatile Ui ui;
    private final Set<Ui> observers = new CopyOnWriteArraySet<>();
    private final ChangeReview changes;
    private Process process;
    private RpcClient rpc;
    private TestTransport testTransport;
    private volatile boolean ready, busy;
    private String permissionMode, approvalMode;
    private final Map<String, PendingRequest> requests = new LinkedHashMap<>();
    private volatile Process terminalProcess;
    private String status = t("시작할 준비가 됐습니다"), threadId = "", turnId = "", serverThreadId = "";
    private JSONObject account = new JSONObject();
    private JSONObject rateLimits = new JSONObject();
    private JSONArray models = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONObject active;
    private Approval pendingApproval;
    private File stateFile;
    private final File workDir;
    private final CodexHome codexHome;
    private final AccountProfiles accountProfiles;
    private final PersonalInstructions instructions;
    private final DevTools devTools;
    private String addAccountRestoreKey = "";
    private record PendingRequest(RpcClient connection, Object id, String method, JSONObject params) {}

    public Engine(Context context) {
        this.context = context;
        permissionMode = context.getSharedPreferences("settings", 0).getString("permissions", "workspace-write");
        approvalMode = context.getSharedPreferences("settings", 0).getString("approvalMode", "auto-review");
        documents = new DocumentStore(context);
        images = new ImageStore(context);
        attachments = new AttachmentStore(context, images);
        try { codexHome = CodexHome.open(context); }
        catch (IOException e) { throw new IllegalStateException(t("Codex 홈을 준비하지 못했습니다."), e); }
        try { accountProfiles = new AccountProfiles(codexHome.root(), context.getFilesDir()); }
        catch (IOException e) { throw new IllegalStateException(t("계정 프로필을 준비하지 못했습니다."), e); }
        instructions = new PersonalInstructions(codexHome);
        devTools = new DevTools(context);
        changes = new ChangeReview(new File(context.getFilesDir(), "change-backups"), this::git);
        workDir = new File(context.getFilesDir(), "workspace"); workDir.mkdirs();
        stateFile = new File(context.getFilesDir(), "sessions.json");
        try { sessions = new JSONArray(dev.mobilecodex.app.core.Utf8Files.read(stateFile.toPath())); } catch (Exception ignored) {}
        restoreSessionProjects();
    }
    public void attach(Ui ui) {
        this.ui = ui;
        io.execute(() -> {
            publish();
            if (pendingApproval != null && !pendingApproval.decision.isDone()) ui.approval(pendingApproval);
            requests.forEach((key, request) -> event("server.request", obj("key", key, "method", request.method, "params", request.params)));
        });
    }
    public void detach(Ui ui) { if (this.ui == ui) this.ui = null; }
    public void observe(Ui observer) { observers.add(observer); io.execute(() -> { if (observers.contains(observer)) { observer.event("state", snapshot()); if (pendingApproval != null && !pendingApproval.decision.isDone()) observer.approval(pendingApproval); requests.forEach((key, request) -> observer.event("server.request", obj("key", key, "method", request.method, "params", request.params))); } }); }
    public void unobserve(Ui observer) { observers.remove(observer); }
    void setTestTransport(TestTransport value) { testTransport = value; }
    private void event(String name, JSONObject data) { Ui current = ui; if (current != null) current.event(name, data); for (Ui observer : observers) if (observer != current) observer.event(name, data); }
    private JSONObject snapshot() {
        JSONArray summaries = new JSONArray();
        String accountKey = accountProfiles.activeKey();
        for (int i = sessions.length() - 1; i >= 0; i--) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null && !s.optBoolean("deletionPending") && belongsToAccount(s, accountKey)) summaries.put(obj("id", s.optString("id"), "title", s.optString("title"),
                "workspace", s.optString("workspace"), "workspaceKey", s.optString("workspaceKey")));
        }
        return obj("ready", ready, "busy", busy, "status", t(status), "account", account,
            "accounts", accountProfiles.list(), "rateLimits", rateLimits, "models", models, "workspace", documents.workspace(), "projects", documents.projects(), "sessions", summaries,
            "threadId", threadId, "turnId", turnId, "turnDiff", active == null ? "" : active.optString("turnDiff"), "messages", active == null ? new JSONArray() : active.optJSONArray("messages"),
            "pendingDeletionCount", pendingDeletionCount(), "devtools", devTools.status(),
            "phone", PhoneUseService.status(context), "phoneToolsAvailable", active == null || active.optInt("phoneToolsVersion") >= 1,
            "permissions", permissionMode, "approvalMode", approvalMode, "allFilesAccess", (Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED),
            "directWorkspace", documents.directDirectory() != null, "cwd", projectDirectory().getAbsolutePath());
    }
    private void publish() { event("state", snapshot()); }
    private void persist() {
        try { persistSessions(sessions); }
        catch (IOException e) { event("error", obj("message", t("대화 기록을 저장하지 못했습니다."))); }
    }
    /** Writes an already-staged session state. Mutating RPCs use this directly so they cannot report a durable success on I/O failure. */
    private void persistSessions(JSONArray value) throws IOException {
        File pending = new File(stateFile.getParentFile(), "sessions.json.tmp");
        try {
            dev.mobilecodex.app.core.Utf8Files.write(pending.toPath(), value.toString());
            Files.move(pending.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            pending.delete();
            throw new IOException(t("대화 기록을 저장하지 못했습니다."), e);
        }
    }
    private int pendingDeletionCount() {
        int count = 0;
        String accountKey = accountProfiles.activeKey();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optBoolean("deletionPending") && belongsToAccount(session, accountKey)) count++;
        }
        return count;
    }
    private static boolean belongsToAccount(JSONObject session, String accountKey) {
        String sessionKey = session.optString("accountProfileKey");
        return sessionKey.isBlank() || accountKey.isBlank() || sessionKey.equals(accountKey);
    }
    /** Test-only state-store seam for durable-write failure coverage. */
    void setStateFileForTest(File file) { stateFile = file; }
    /** Old session files have no registry entry. Keep their identity distinct from general chat. */
    private void restoreSessionProjects() {
        boolean changed = false;
        for (int i = 0; i < sessions.length(); i++) {
            try {
                JSONObject session = sessions.optJSONObject(i); if (session == null || session.optBoolean("deletionPending")) continue;
                if (!session.has("workspaceKey")) {
                    session.put("workspaceKey", documents.restoreProjectKey(session.optString("id"), "", session.optString("workspace")));
                    changed = true;
                } else if (!session.optString("workspaceKey").isBlank()) {
                    String key = documents.restoreProjectKey(session.optString("id"), session.optString("workspaceKey"), session.optString("workspace"));
                    if (!key.equals(session.optString("workspaceKey"))) { session.put("workspaceKey", key); changed = true; }
                }
                if (!session.has("messages") || session.optJSONArray("messages") == null) { session.put("messages", new JSONArray()); changed = true; }
            } catch (Exception ignored) { }
        }
        if (changed) persist();
    }
    public void updatesChanged(JSONObject data) { event("updates.changed", data); }
    public boolean canInstallUpdate() { return !busy && !VoiceInput.active() && (terminalProcess == null || !terminalProcess.isAlive()); }
    public void voiceInputChanged() { event("voice.changed", obj()); }
    public void phoneStateChanged() { io.execute(this::publish); }
    public void handle(String action, JSONObject args, Reply reply) {
        // Never queue consent revocation behind a slow engine/tool operation.
        if (Set.of("phone.stop", "chat.stop", "runtime.stop", "auth.logout").contains(action)) PhoneUseService.stopControl();
        io.execute(() -> {
            try {
                switch (action) {
                    case "state" -> reply.complete(snapshot(), null);
                    case "phone.stop" -> { publish(); reply.complete(snapshot(), null); }
                    case "runtime.start" -> { start(); reply.complete(snapshot(), null); }
                    case "devtools.check" -> { JSONObject result = devTools.check(codexHome.root(), runtimeAliases()); publish(); reply.complete(result, null); }
                    case "runtime.stop" -> { stopNow(); reply.complete(snapshot(), null); }
                    case "auth.login" -> reply.complete(beginLogin(false), null);
                    case "auth.add" -> reply.complete(beginLogin(true), null);
                    case "auth.cancel" -> {
                        JSONObject result;
                        try { result = call("account/login/cancel", args); }
                        finally { restoreAccountAfterCancelledLogin(); }
                        reply.complete(result, null);
                    }
                    case "auth.switch" -> { switchAccount(args.getString("key")); reply.complete(snapshot(), null); }
                    case "auth.remove" -> { accountProfiles.delete(args.getString("key")); publish(); reply.complete(snapshot(), null); }
                    case "auth.logout" -> {
                        ensureIdle(); start(); call("account/logout", new JSONObject()); accountProfiles.removeActiveProfile();
                        account = new JSONObject(); rateLimits = new JSONObject(); clearActive(); publish(); reply.complete(obj("ok", true), null);
                    }
                    case "permissions.set" -> {
                        ensureIdle();
                        String mode = args.getString("mode");
                        if (!Set.of("read-only", "workspace-write", "danger-full-access").contains(mode)) throw new IOException(t("잘못된 권한 모드입니다."));
                        permissionMode = mode;
                        context.getSharedPreferences("settings", 0).edit().putString("permissions", mode).apply();
                        if (active != null && ready) resumeRemote(true);
                        publish(); reply.complete(obj("ok", true), null);
                    }
                    case "approvals.set" -> {
                        ensureIdle(); String mode = args.getString("mode");
                        if (!Set.of("ask", "auto-review", "allow-all").contains(mode)) throw new IOException(t("잘못된 승인 방식입니다."));
                        approvalMode = mode; context.getSharedPreferences("settings", 0).edit().putString("approvalMode", mode).apply();
                        if (active != null && ready) resumeRemote(true);
                        publish(); reply.complete(obj("ok", true), null);
                    }
                    case "config.read" -> { File config = new File(codexHome.root(), "config.toml"); reply.complete(obj("content", config.exists() ? dev.mobilecodex.app.core.Utf8Files.read(config.toPath()) : ""), null); }
                    case "config.save" -> {
                        ensureIdle(); dev.mobilecodex.app.core.Utf8Files.write(new File(codexHome.root(), "config.toml").toPath(), args.getString("content"));
                        stopNow(); reply.complete(obj("ok", true), null);
                    }
                    case "instructions.read" -> reply.complete(instructions.read(), null);
                    case "instructions.save" -> {
                        ensureIdle(); JSONObject saved = instructions.save(args.getString("content"));
                        // AGENTS files are read when Codex starts a new execution; force that boundary after a successful write.
                        stopNow(); reply.complete(saved, null);
                    }
                    case "rpc" -> { start(); reply.complete(call(args.getString("method"), args.optJSONObject("params") == null ? new JSONObject() : args.getJSONObject("params")), null); }
                    case "rpc.respond" -> {
                        PendingRequest pending = requests.remove(args.getString("key"));
                        if (pending == null) throw new IOException(t("이미 종료된 요청입니다."));
                        pending.connection.respond(pending.id, args.getJSONObject("result"));
                        reply.complete(obj("ok", true), null);
                    }
                    case "terminal.run" -> { runTerminal(args.getString("command")); reply.complete(obj("ok", true), null); }
                    case "terminal.stop" -> { if (terminalProcess != null) terminalProcess.destroyForcibly(); reply.complete(obj("ok", true), null); }
                    case "files.list" -> reply.complete(documents.list(args.optString("path", "")), null);
                    case "files.search" -> reply.complete(documents.search(args.getString("query")), null);
                    case "files.read" -> reply.complete(documents.read(args.getString("path")), null);
                    case "files.mention" -> reply.complete(documents.mention(args.getString("path"), attachments), null);
                    case "images.read" -> reply.complete(readImage(args.getString("path")), null);
                    case "files.mutate" -> mutate(args.getString("operation"), args.getJSONObject("arguments"), true, reply);
                    case "recovery.list" -> reply.complete(obj("entries", documents.recoveryList()), null);
                    case "recovery.preview" -> { requireScope(args); reply.complete(documents.previewRecovery(args.getString("id")), null); }
                    case "recovery.restore" -> { ensureIdle(); requireScope(args); JSONObject restore = documents.recoveryMutation(args.getString("id")); mutate(restore.getString("operation"), restore.getJSONObject("arguments"), true, reply); }
                    case "changes.list" -> { requireScope(args); reply.complete(changes.list(reviewDirectory()), null); }
                    case "changes.history" -> { requireScope(args); reply.complete(obj("entries", changes.history(reviewDirectory())), null); }
                    case "changes.preview" -> { requireScope(args); reply.complete(changes.preview(reviewDirectory(), args.getString("path")), null); }
                    case "changes.backupPreview" -> { requireScope(args); reply.complete(changes.previewBackup(reviewDirectory(), args.getString("id")), null); }
                    case "changes.restore" -> {
                        ensureIdle(); requireScope(args);
                        if (permissionMode.equals("read-only")) throw new IOException(t("읽기 전용 모드에서는 복원할 수 없습니다."));
                        if (terminalProcess != null && terminalProcess.isAlive()) throw new IOException(t("실행 중인 터미널 명령을 먼저 중지해 주세요."));
                        JSONObject result = changes.restore(reviewDirectory(), args.getString("token")); event("files.changed", result); reply.complete(result, null);
                    }
                    case "projects.select" -> { ensureIdle(); documents.selectProject(args.optString("key", "")); clearActive(); publish(); reply.complete(snapshot(), null); }
                    case "projects.remove" -> { ensureIdle(); String key = args.getString("key"); boolean current = key.equals(documents.key()); JSONObject removed = documents.removeProject(key); if (current) clearActive(); publish(); reply.complete(removed, null); }
                    case "projects.rename" -> { ensureIdle(); JSONObject renamed = documents.renameProject(args.getString("key"), args.getString("name")); publish(); reply.complete(renamed, null); }
                    case "documents.projects" -> reply.complete(obj("projects", documents.projects()), null);
                    case "chat.send" -> { requireChatScope(args); send(args.optString("text", ""), args.optString("model", ""), args.optString("effort", ""),
                        args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions")); reply.complete(obj("ok", true), null); }
                    case "chat.steer" -> { steer(args); reply.complete(obj("ok", true), null); }
                    case "chat.new" -> {
                        ensureIdle();
                        String key = args.has("workspaceKey") ? args.optString("workspaceKey", "") : documents.key();
                        documents.selectProject(key); clearActive(); publish(); reply.complete(snapshot(), null);
                    }
                    case "chat.resume" -> { resume(args.getString("id")); reply.complete(snapshot(), null); }
                    case "chat.rename" -> { renameSession(args.getString("id"), args.getString("title")); publish(); reply.complete(snapshot(), null); }
                    case "chat.delete" -> {
                        boolean deletionPending = deleteSession(args.getString("id"));
                        publish();
                        JSONObject result = snapshot(); result.put("deletionPending", deletionPending);
                        reply.complete(result, null);
                    }
                    case "chat.stop" -> {
                        if (!turnId.isEmpty()) call("turn/interrupt", obj("threadId", threadId, "turnId", turnId));
                        if (pendingApproval != null) pendingApproval.decision.complete(false);
                        reply.complete(obj("ok", true), null);
                    }
                    default -> throw new IOException(t("지원하지 않는 요청입니다: ") + action);
                }
            } catch (Throwable e) {
                reply.complete(null, unwrap(e));
            }
        });
    }
    private static Throwable unwrap(Throwable e) {
        while ((e instanceof ExecutionException || e instanceof CompletionException) && e.getCause() != null) e = e.getCause();
        return e;
    }
    public void workspaceChanged() {
        io.execute(() -> { clearActive(); publish(); });
    }
    private void clearActive() { active = null; threadId = ""; turnId = ""; serverThreadId = ""; }
    public boolean isBusy() { return busy; }
    private void ensureIdle() throws IOException { if (busy) throw new IOException(t("진행 중인 작업을 먼저 중지해 주세요.")); }
    private void start() throws Exception {
        if (testTransport != null) {
            ready = true;
            retryPendingDeletions();
            if (active != null && active.optString("workspaceKey").equals(documents.key()) && documents.workspace().optBoolean("available")) resumeRemote(false);
            return;
        }
        if (ready && process != null && process.isAlive() && rpc != null && !rpc.isClosed()) {
            retryPendingDeletions();
            publish();
            return;
        }
        // A stdout disconnect can race with queued UI RPCs. Never reuse its closed writer or
        // leave the old child around while replacing it.
        Process staleProcess = process; RpcClient staleRpc = rpc;
        process = null; rpc = null; ready = false;
        if (staleRpc != null) staleRpc.close();
        if (staleProcess != null && staleProcess.isAlive()) staleProcess.destroyForcibly();
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
            throw new IOException(t("이 알파 버전은 ARM64 안드로이드 기기용입니다."));
        File libraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File binary = new File(libraryDir, "libcodex.so");
        if (!binary.isFile() || !binary.canExecute()) throw new IOException(t("실행 엔진이 포함되지 않았습니다. 전체 APK를 다시 설치해 주세요."));
        status = t("Codex를 시작하고 있습니다"); publish();
        // Keep upstream Codex tools/features available; preserve user configuration.
        File config = new File(codexHome.root(), "config.toml");
        if (!config.exists()) dev.mobilecodex.app.core.Utf8Files.write(config.toPath(), "cli_auth_credentials_store = \"file\"\napproval_policy = \"on-request\"\n");
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "app-server", "--listen", "stdio://");
        builder.directory(projectDirectory());
        devTools.configure(builder, codexHome.root(), runtimeAliases());
        builder.environment().put("CODEX_SELF_EXE", binary.getAbsolutePath());
        context.startForegroundService(new Intent(context, EngineService.class));
        RuntimeFailure.Tail stderrTail = new RuntimeFailure.Tail();
        try {
            process = builder.start();
            Process launched = process;
            Thread errors = new Thread(() -> {
                // Drain stderr to avoid deadlock. Keep only a bounded private tail for allow-listed diagnostics.
                try (InputStream stderr = launched.getErrorStream()) { byte[] buffer = new byte[4096]; int count; while ((count = stderr.read(buffer)) != -1) stderrTail.append(buffer, count); }
                catch (IOException ignored) {}
            }, "codex-stderr"); errors.setDaemon(true); errors.start();
            rpc = new RpcClient(process.getInputStream(), process.getOutputStream(), new RpcClient.Listener() {
                @Override public void notification(String method, JSONObject params) { io.execute(() -> onNotification(method, params)); }
                @Override public void request(Object id, String method, JSONObject params) { io.execute(() -> onRequest(id, method, params)); }
                @Override public void disconnected(Throwable error) {
                    io.execute(() -> {
                        if (process != launched) return;
                        int exitCode = -1;
                        try { exitCode = launched.exitValue(); } catch (IllegalThreadStateException ignored) { }
                        if (launched.isAlive()) launched.destroyForcibly();
                        PhoneUseService.stopControl();
                        process = null; rpc = null;
                        requests.forEach((key, value) -> event("server.resolved", obj("key", key)));
                        requests.clear();
                        ready = false; busy = false; turnId = ""; serverThreadId = "";
                        String detail = error == null ? t("Codex 연결이 종료되었습니다.") : error.getMessage();
                        String diagnosis = stderrTail.diagnosis();
                        status = t("실행 엔진 연결이 종료되었습니다") + (exitCode >= 0 ? t(" (종료 코드 ") + exitCode + ")" : "") + ". " + detail
                            + (diagnosis.isEmpty() ? "" : t(" 진단 단서: ") + diagnosis + ".") + t(" 다시 연결해 주세요.");
                        if (pendingApproval != null) pendingApproval.decision.complete(false);
                        publish();
                        context.stopService(new Intent(context, EngineService.class));
                    });
                }
            });
            rpc.start();
            call("initialize", obj("clientInfo", obj("name", "mobile_codex", "title", "Mobile Codex", "version", "0.1.11"),
                "capabilities", obj("experimentalApi", true)));
            rpc.notify("initialized", new JSONObject());
            ready = true; status = t("연결됨");
            readAccount();
            readRateLimits();
            try { models = call("model/list", obj("limit", 100, "includeHidden", false)).optJSONArray("data"); }
            catch (Exception ignored) { models = new JSONArray(); }
            if (models == null) models = new JSONArray();
            // Tombstones must be retried before a restored active thread can be resumed.
            retryPendingDeletions();
            // A process restart needs an explicit server-side thread resume.
            if (active != null && active.optString("workspaceKey").equals(documents.key()) && documents.workspace().optBoolean("available")) resumeRemote(false);
            publish();
        } catch (Exception e) {
            Process failed = process; int exitCode = -1;
            try { if (failed != null) exitCode = failed.exitValue(); } catch (IllegalThreadStateException ignored) { }
            String diagnosis = stderrTail.diagnosis();
            String detail = t("Codex를 시작하지 못했습니다") + (exitCode >= 0 ? t(" (종료 코드 ") + exitCode + ")" : "") + ": " + unwrap(e).getMessage()
                + (diagnosis.isEmpty() ? "" : t(" (진단 단서: ") + diagnosis + ")");
            stopNow(); throw new IOException(detail, e);
        }
    }
    private JSONObject call(String method, JSONObject params) throws Exception {
        if (testTransport != null) return testTransport.call(method, params);
        if (rpc == null) throw new IOException(t("먼저 Codex를 시작해 주세요."));
        return rpc.request(method, params).get(65, TimeUnit.SECONDS);
    }
    private void readAccount() throws Exception {
        JSONObject data = call("account/read", obj("refreshToken", false));
        account = data.optJSONObject("account"); if (account == null) account = new JSONObject();
        if (account.length() > 0) {
            JSONObject profile = accountProfiles.saveCurrent(account);
            claimLegacySessions(profile.optString("key"));
        }
    }
    private void readRateLimits() {
        if (account.length() == 0) { rateLimits = new JSONObject(); return; }
        try { rateLimits = call("account/rateLimits/read", new JSONObject()); }
        catch (Exception ignored) { rateLimits = new JSONObject(); }
    }
    private JSONObject beginLogin(boolean add) throws Exception {
        ensureIdle(); start();
        if (add && account.length() > 0) {
            JSONObject profile = accountProfiles.saveCurrent(account);
            addAccountRestoreKey = profile.optString("key");
            call("account/logout", new JSONObject());
            account = new JSONObject(); rateLimits = new JSONObject(); clearActive(); publish();
        } else addAccountRestoreKey = "";
        return call("account/login/start", obj("type", "chatgptDeviceCode"));
    }
    private void restoreAccountAfterCancelledLogin() throws Exception {
        if (addAccountRestoreKey.isBlank()) return;
        String restore = addAccountRestoreKey; addAccountRestoreKey = "";
        stopNow(); accountProfiles.switchTo(restore); account = new JSONObject(); rateLimits = new JSONObject(); start();
    }
    private void switchAccount(String key) throws Exception {
        ensureIdle();
        String previous = accountProfiles.activeKey();
        if (key.equals(previous)) return;
        if (account.length() > 0) accountProfiles.saveCurrent(account);
        stopNow();
        try {
            accountProfiles.switchTo(key); account = new JSONObject(); rateLimits = new JSONObject(); clearActive(); start();
        } catch (Exception error) {
            if (!previous.isBlank()) {
                try { accountProfiles.switchTo(previous); account = new JSONObject(); rateLimits = new JSONObject(); start(); }
                catch (Exception ignored) { }
            }
            throw error;
        }
    }
    private void claimLegacySessions(String key) throws Exception {
        if (key == null || key.isBlank()) return;
        boolean changed = false;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && !session.has("accountProfileKey")) { session.put("accountProfileKey", key); changed = true; }
        }
        if (changed) persist();
    }
    private String resolvedModel(String requested) {
        if (requested != null && !requested.isBlank()) return requested;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && model.optBoolean("isDefault")) return model.optString("model", model.optString("id"));
        }
        return "";
    }
    private String workspaceInstructions(String model) {
        JSONObject workspace = documents.workspace();
        return ToolCatalog.INSTRUCTIONS + " Selected Android folder: " + workspace.optString("name", "none")
            + ". Folder available: " + workspace.optBoolean("available") + ". Direct shell access to that folder: "
            + (documents.directDirectory() != null) + ". The exact model requested for this thread is "
            + (model == null || model.isBlank() ? "not available from the runtime" : model)
            + ". When the user asks which model you are, report that exact requested model id.";
    }
    private String approvalPolicy() { return "allow-all".equals(approvalMode) ? "never" : "on-request"; }
    private String approvalsReviewer() { return "auto-review".equals(approvalMode) ? "auto_review" : "user"; }
    private JSONObject threadStartParams(String model) throws Exception {
        model = resolvedModel(model);
        JSONObject params = obj("cwd", projectDirectory().getAbsolutePath(), "sandbox", permissionMode, "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer(),
            "developerInstructions", workspaceInstructions(model), "dynamicTools", ToolCatalog.all());
        if (!model.isEmpty()) params.put("model", model);
        return params;
    }
    /** thread/resume schema accepts cwd and instructions but not dynamicTools. */
    private void resumeRemote(boolean force) throws Exception { resumeRemote(force, active == null ? "" : active.optString("model")); }
    private void resumeRemote(boolean force, String model) throws Exception {
        if (active == null || threadId.isEmpty() || (!force && threadId.equals(serverThreadId))) return;
        if (!active.optString("workspaceKey").equals(documents.key()))
            throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
        documents.requireWorkspaceAvailable();
        call("thread/resume", obj("threadId", threadId, "excludeTurns", true, "cwd", projectDirectory().getAbsolutePath(),
            "sandbox", permissionMode, "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer(), "developerInstructions", workspaceInstructions(resolvedModel(model))));
        serverThreadId = threadId;
        restoreImageHistory();
    }
    /** Old local records predate persisted image IDs; only recover them after a server resume. */
    private void restoreImageHistory() {
        if (active == null || active.optInt("imageHistoryVersion") >= 1) return;
        try {
            JSONObject thread = call("thread/read", obj("threadId", threadId, "includeTurns", true)).optJSONObject("thread");
            JSONArray turns = thread == null ? null : thread.optJSONArray("turns");
            if (turns == null) return;
            for (int t = 0; t < turns.length(); t++) {
                JSONObject turn = turns.optJSONObject(t); if (turn == null) continue;
                JSONArray items = turn.optJSONArray("items");
                if (items != null) for (int n = 0; n < items.length(); n++) {
                    JSONObject item = items.optJSONObject(n); if (item != null) recordImages(item, true, turn.optString("id"));
                }
            }
            active.put("imageHistoryVersion", 1); persist(); publish();
        } catch (Exception e) {
            event("notice", obj("message", t("이전 이미지 기록을 불러오지 못했습니다: ") + unwrap(e).getMessage()));
        }
    }
    private JSONArray input(String text, JSONArray attachmentIds, JSONArray skills, JSONArray mentions) throws Exception {
        JSONArray result = new JSONArray();
        if (!text.isBlank()) result.put(obj("type", "text", "text", text, "text_elements", new JSONArray()));
        addSkills(result, skills);
        addMentions(result, mentions);
        JSONArray attachmentsInput = attachments.inputs(attachmentIds);
        for (int i = 0; i < attachmentsInput.length(); i++) {
            JSONObject value = attachmentsInput.getJSONObject(i);
            if ("text".equals(value.optString("type")) && !value.has("text_elements")) value.put("text_elements", new JSONArray());
            result.put(value);
        }
        return result;
    }
    private void addSkills(JSONArray result, JSONArray skills) throws Exception {
        if (skills == null) return;
        if (skills.length() > 32) throw new IOException(t("한 메시지에는 최대 32개의 스킬을 추가할 수 있습니다."));
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < skills.length(); i++) {
            JSONObject value = skills.optJSONObject(i); if (value == null) throw new IOException(t("스킬 정보가 올바르지 않습니다."));
            String name = value.optString("name"), path = value.optString("path");
            if (name.isBlank() || path.isBlank() || !seen.add(path)) throw new IOException(t("스킬 정보가 올바르지 않습니다."));
            result.put(obj("type", "skill", "name", name, "path", path));
        }
    }
    private void addMentions(JSONArray result, JSONArray mentions) throws Exception {
        if (mentions == null) return;
        if (mentions.length() > 64) throw new IOException(t("한 메시지에는 최대 64개의 멘션을 추가할 수 있습니다."));
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < mentions.length(); i++) {
            JSONObject value = mentions.optJSONObject(i); if (value == null) throw new IOException(t("멘션 정보가 올바르지 않습니다."));
            String name = value.optString("name"), path = value.optString("path");
            if (name.isBlank() || path.isBlank() || !seen.add(path)) throw new IOException(t("멘션 정보가 올바르지 않습니다."));
            if (path.startsWith("app://") || path.startsWith("plugin://")) result.put(obj("type", "mention", "name", name, "path", path));
            else if (new File(path).isAbsolute()) {
                File file = new File(path).getCanonicalFile();
                if (!file.isFile() || !allowedMentionFile(file)) throw new IOException(t("멘션 파일을 읽을 수 없습니다."));
                result.put(obj("type", "mention", "name", name, "path", file.getAbsolutePath()));
                result.put(obj("type", "text", "text", "Mentioned file is available at this absolute path: " + file.getAbsolutePath(), "text_elements", new JSONArray()));
            }
            else {
                JSONObject file = documents.mention(path, attachments);
                result.put(obj("type", "mention", "name", file.getString("name"), "path", file.getString("path")));
                result.put(obj("type", "text", "text", "Mentioned file is available at this absolute path: " + file.getString("path"), "text_elements", new JSONArray()));
            }
        }
    }
    private boolean allowedMentionFile(File file) throws IOException {
        File privateAttachments = new File(context.getFilesDir(), "attachments").getCanonicalFile();
        if (file.getPath().startsWith(privateAttachments.getPath() + File.separator)) return true;
        File workspace = documents.directDirectory();
        return workspace != null && file.getPath().startsWith(workspace.getCanonicalPath() + File.separator);
    }
    private String titleFor(String text, JSONArray attachmentIds) {
        if (!text.isBlank()) return text.substring(0, Math.min(40, text.length()));
        if (attachmentIds != null && attachmentIds.length() > 0) return t("첨부 파일 ") + attachmentIds.length() + t("개");
        return t("제목 없는 대화");
    }
    private JSONObject userMessage(String text, JSONArray attachmentIds, JSONArray skills, JSONArray mentions) throws Exception {
        JSONObject message = obj("role", "user", "text", text, "id", UUID.randomUUID().toString());
        if (skills != null && skills.length() > 0) message.put("skills", new JSONArray(skills.toString()));
        if (mentions != null && mentions.length() > 0) message.put("mentions", new JSONArray(mentions.toString()));
        if (attachmentIds != null && attachmentIds.length() > 0) {
            JSONArray stored = new JSONArray();
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < attachmentIds.length(); i++) {
                String id = attachmentIds.getString(i);
                if (seen.add(id)) stored.put(attachments.get(id));
            }
            message.put("attachments", stored);
        }
        return message;
    }
    private void requireScope(JSONObject args) throws IOException {
        if (!args.has("workspaceKey") || !args.optString("workspaceKey").equals(documents.key())) throw new IOException(t("프로젝트가 바뀌었습니다. 다시 열어 주세요."));
    }
    private void requireChatScope(JSONObject args) throws IOException {
        if (args.has("expectedThreadId") && !args.optString("expectedThreadId").equals(threadId)) throw new IOException(t("대화가 바뀌었습니다. 현재 대화에서 다시 보내 주세요."));
        if (args.has("workspaceKey")) requireScope(args);
    }
    private void steer(JSONObject args) throws Exception {
        requireChatScope(args);
        String text = args.optString("text").trim();
        if (text.isEmpty() || text.length() > 50000) throw new IOException(t("추가 지시는 1~50,000자로 입력해 주세요."));
        if (!busy || turnId.isEmpty() || !turnId.equals(args.optString("expectedTurnId"))) throw new IOException(t("진행 중인 작업이 변경되거나 종료되었습니다. 새 메시지로 보내 주세요."));
        JSONArray input = input(text, args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions"));
        call("turn/steer", obj("threadId", threadId, "expectedTurnId", turnId, "input", input));
        active.getJSONArray("messages").put(userMessage(text, args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions")));
        persist(); publish();
    }
    private File reviewDirectory() throws IOException {
        documents.requireWorkspaceAvailable();
        if (documents.workspace().optBoolean("selected") && documents.directDirectory() == null) throw new IOException(t("이 폴더는 문서 제공자 전용입니다. 복구 사본에서 파일별 변경을 확인해 주세요."));
        return projectDirectory();
    }
    private byte[] git(File directory, List<String> arguments) throws Exception {
        File prefix = devTools.prepare();
        List<String> argv = new ArrayList<>(); argv.add(new File(prefix, "bin/git").getAbsolutePath()); argv.add("--no-pager"); argv.add("--literal-pathspecs"); argv.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(directory);
        devTools.configure(builder, codexHome.root(), runtimeAliases()); builder.environment().put("GIT_OPTIONAL_LOCKS", "0"); builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        return ProcessOutput.run(builder, 16 * 1024 * 1024, 15);
    }
    private void send(String text, String model, String effort, JSONArray attachmentIds, JSONArray skills, JSONArray mentions) throws Exception {
        ensureIdle();
        if (text.length() > 50000) throw new IOException(t("메시지는 최대 50,000자까지 입력할 수 있습니다."));
        documents.requireWorkspaceAvailable();
        JSONArray input = input(text, attachmentIds, skills, mentions);
        if (input.length() == 0) throw new IOException(t("메시지나 첨부 파일을 추가해 주세요."));
        start();
        if (account.length() == 0) throw new IOException(t("ChatGPT 계정으로 로그인해 주세요."));
        String actualModel = resolvedModel(model);
        JSONObject candidate = null;
        String candidateThreadId = "";
        if (active == null) {
            JSONObject params = threadStartParams(actualModel);
            JSONObject thread = call("thread/start", params).getJSONObject("thread");
            candidateThreadId = thread.getString("id");
            candidate = obj("id", candidateThreadId, "title", titleFor(text, attachmentIds), "workspace", documents.workspace().optString("name"),
                "workspaceKey", documents.key(), "accountProfileKey", accountProfiles.activeKey(), "model", actualModel,
                "messages", new JSONArray(), "imageHistoryVersion", 1, "phoneToolsVersion", 1);
        } else {
            if (!active.optString("workspaceKey").equals(documents.key())) throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
            resumeRemote(!actualModel.equals(active.optString("model")), actualModel);
        }
        String targetThread = candidate == null ? threadId : candidateThreadId;
        busy = true; status = t("작업 중"); publish();
        try {
            JSONObject params = obj("threadId", targetThread, "input", input, "cwd", projectDirectory().getAbsolutePath(), "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer());
            if (!actualModel.isEmpty()) params.put("model", actualModel);
            if (!effort.isEmpty()) params.put("effort", effort);
            JSONObject turn = call("turn/start", params).optJSONObject("turn");
            if (candidate != null) { active = candidate; threadId = candidateThreadId; serverThreadId = candidateThreadId; sessions.put(active); }
            else active.put("model", actualModel);
            active.getJSONArray("messages").put(userMessage(text, attachmentIds, skills, mentions));
            if (turn != null) turnId = turn.optString("id", "");
            persist(); publish();
        } catch (Exception e) { busy = false; status = t("요청 실패"); publish(); throw e; }
    }
    private void resume(String id) throws Exception {
        ensureIdle();
        String accountKey = accountProfiles.activeKey();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && belongsToAccount(session, accountKey) && session.optString("id").equals(id)) {
                String key = session.optString("workspaceKey");
                try { documents.selectProject(key); }
                catch (Exception ignored) { documents.selectProject(""); }
                threadId = id; serverThreadId = ""; active = session;
                publish(); return;
            }
        }
        throw new IOException(t("대화를 찾을 수 없습니다."));
    }
    private void renameSession(String id, String title) throws Exception {
        ensureIdle();
        // Titles are Mobile Codex local aliases: they remain available without starting or signing in to Codex.
        String trimmed = title.trim();
        if (trimmed.isEmpty()) throw new IOException(t("대화 제목을 입력해 주세요."));
        JSONArray replacement = new JSONArray(sessions.toString());
        String accountKey = accountProfiles.activeKey();
        boolean found = false;
        for (int i = 0; i < replacement.length(); i++) {
            JSONObject session = replacement.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && belongsToAccount(session, accountKey) && session.optString("id").equals(id)) {
                session.put("title", trimmed);
                found = true;
                break;
            }
        }
        if (!found) throw new IOException(t("대화를 찾을 수 없습니다."));
        persistSessions(replacement);
        replaceSessions(replacement);
    }
    /**
     * First persist a hidden tombstone. This makes an offline request durable before any
     * server mutation. A connected runtime then deletes the real Codex thread immediately;
     * failed server calls leave the tombstone to retry on the next start/reconnect.
     */
    private boolean deleteSession(String id) throws Exception {
        ensureIdle();
        boolean found = false;
        String accountKey = accountProfiles.activeKey();
        JSONArray marked = new JSONArray(sessions.toString());
        for (int i = 0; i < marked.length(); i++) {
            JSONObject session = marked.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && belongsToAccount(session, accountKey) && session.optString("id").equals(id)) {
                session.put("deletionPending", true);
                found = true;
                break;
            }
        }
        if (!found) throw new IOException(t("대화를 찾을 수 없습니다."));
        // Do not clear the visible/active conversation until its pending-deletion record is durable.
        persistSessions(marked);
        replaceSessions(marked);
        if (id.equals(threadId)) clearActive();
        if (!ready || !deleteRemote(id, false)) return true;

        JSONArray remaining = withoutPendingSession(id);
        try {
            persistSessions(remaining);
            replaceSessions(remaining);
            return false;
        } catch (IOException e) {
            // The already durable tombstone remains in sessions and will be reconciled later.
            event("notice", obj("message", t("Codex 대화 삭제는 완료됐지만 목록 정리를 나중에 다시 시도합니다.")));
            return true;
        }
    }
    private JSONObject session(String id) {
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optString("id").equals(id)) return session;
        }
        return null;
    }
    /** A staged JSON copy invalidates JSONObject references, including an unrelated active conversation. */
    private void replaceSessions(JSONArray replacement) {
        sessions = replacement;
        if (active != null) {
            JSONObject restored = session(threadId);
            if (restored == null) clearActive();
            else active = restored;
        }
    }
    private JSONArray withoutPendingSession(String id) {
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optBoolean("deletionPending") && session.optString("id").equals(id)) continue;
            remaining.put(sessions.opt(i));
        }
        return remaining;
    }
    private boolean deleteRemote(String id, boolean retry) {
        try {
            call("thread/delete", obj("threadId", id));
            return true;
        } catch (Exception e) {
            // app-server v0.155.1 reports a missing root thread as "thread not found: <id>".
            // For a durable deletion tombstone that is already the requested end state.
            String message = unwrap(e).getMessage();
            if (retry && ("thread not found: " + id).equals(message)) return true;
            if (retry) event("notice", obj("message", t("삭제 대기 중인 Codex 대화를 아직 지우지 못했습니다.")));
            else event("notice", obj("message", t("Codex 대화 삭제를 나중에 다시 시도합니다.")));
            return false;
        }
    }
    private void retryPendingDeletions() {
        if (!ready) return;
        String accountKey = accountProfiles.activeKey();
        ArrayList<String> completed = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optBoolean("deletionPending") && belongsToAccount(session, accountKey) && deleteRemote(session.optString("id"), true))
                completed.add(session.optString("id"));
        }
        for (String id : completed) {
            JSONArray remaining = withoutPendingSession(id);
            try {
                persistSessions(remaining);
                replaceSessions(remaining);
            } catch (IOException e) {
                event("error", obj("message", t("대화 기록을 저장하지 못했습니다.")));
                return;
            }
        }
    }
    private void onNotification(String method, JSONObject p) {
        try {
            if (method.equals("account/login/completed")) {
                if (p.optBoolean("success")) {
                    readAccount(); readRateLimits(); addAccountRestoreKey = ""; clearActive(); status = t("연결됨");
                    try { JSONArray data = call("model/list", obj("limit", 100)).optJSONArray("data"); if (data != null) models = data; } catch (Exception ignored) {}
                } else {
                    event("error", obj("message", p.optString("error", t("로그인이 취소되었습니다."))));
                    try { restoreAccountAfterCancelledLogin(); } catch (Exception restoreError) { event("error", obj("message", t("이전 계정을 복원하지 못했습니다: ") + unwrap(restoreError).getMessage())); }
                }
                event("login.completed", p); publish(); return;
            }
            if (method.equals("account/updated")) { readAccount(); readRateLimits(); publish(); return; }
            if (method.equals("account/rateLimits/updated")) { rateLimits = p; publish(); return; }
            if (method.equals("serverRequest/resolved")) {
                Object id = p.opt("requestId");
                requests.entrySet().removeIf(entry -> {
                    if (String.valueOf(entry.getValue().id).equals(String.valueOf(id))) {
                        event("server.resolved", obj("key", entry.getKey())); return true;
                    }
                    return false;
                });
                return;
            }
            if (p.has("threadId") && !p.optString("threadId").equals(threadId)) return;
            if (method.equals("item/agentMessage/delta") && active != null) {
                String id = p.optString("itemId");
                JSONObject message = message(id);
                message.put("text", message.optString("text") + p.optString("delta"));
                event("message.delta", obj("id", id, "delta", p.optString("delta")));
            } else if ((method.equals("item/completed") || method.equals("item/started")) && active != null) {
                JSONObject item = p.optJSONObject("item");
                boolean completed = method.equals("item/completed");
                if (item != null && recordImages(item, completed, p.optString("turnId", turnId))) {
                    if (completed) persist(); publish();
                } else if (completed && item != null && "agentMessage".equals(item.optString("type"))) {
                    message(item.getString("id")).put("text", item.optString("text")); persist(); publish();
                } else event("agent.event", obj("method", method, "params", p));
            } else if (method.equals("turn/diff/updated") && active != null) {
                active.put("turnDiff", p.optString("diff")); persist(); publish();
            } else if (method.equals("turn/started")) {
                JSONObject turn = p.optJSONObject("turn"); if (turn != null) turnId = turn.optString("id");
                busy = true; status = t("작업 중"); publish();
            } else if (method.equals("turn/completed")) {
                busy = false; turnId = ""; status = t("연결됨");
                JSONObject turn = p.optJSONObject("turn");
                if (turn != null && turn.optJSONObject("error") != null) event("error", obj("message", turn.getJSONObject("error").optString("message", t("작업 실패"))));
                if (pendingApproval != null) pendingApproval.decision.complete(false);
                if (active != null) {
                    JSONArray messages = active.getJSONArray("messages");
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject message = messages.getJSONObject(i);
                        if (message.optString("imageStatus").equals("generating")) message.put("imageStatus", "failed")
                            .put("imageError", t("이미지 생성이 완료되지 않았습니다. 다시 시도해 주세요."));
                    }
                }
                persist(); publish();
            } else if (method.equals("error")) {
                JSONObject error = p.optJSONObject("error");
                event("error", obj("message", error == null ? t("Codex 요청 오류") : error.optString("message", t("Codex 요청 오류"))));
            } else {
                event("agent.event", obj("method", method, "params", p));
            }
        } catch (Exception e) { event("error", obj("message", unwrap(e).getMessage())); }
    }
    private JSONObject readImage(String path) throws Exception {
        if (path.startsWith("sandbox:")) path = path.substring("sandbox:".length());
        if (path.startsWith("file://")) path = android.net.Uri.parse(path).getPath();
        if (path == null || path.isBlank()) throw new IOException(t("이미지 경로가 없습니다."));
        File file = new File(path);
        if (file.isAbsolute()) return images.importFile(file);
        if (documents.workspace().optBoolean("selected")) return documents.image(path, images);
        return images.importFile(new File(projectDirectory(), path));
    }
    private boolean recordImages(JSONObject item, boolean completed, String group) throws Exception {
        String type = item.optString("type");
        // Observation screenshots are tool context, not generated artwork for the persistent gallery.
        if (type.equals("dynamicToolCall") && (item.optString("tool").startsWith("mobile_phone_") || item.optString("name").startsWith("mobile_phone_"))) return false;
        boolean generation = type.equals("imageGeneration"), view = type.equals("imageView");
        JSONArray outputs = null;
        if (type.equals("mcpToolCall") && item.optJSONObject("result") != null) outputs = item.getJSONObject("result").optJSONArray("content");
        if (type.equals("dynamicToolCall")) outputs = item.optJSONArray("contentItems");
        JSONArray encoded = new JSONArray();
        if (outputs != null) for (int i = 0; i < outputs.length(); i++) {
            JSONObject part = outputs.optJSONObject(i); if (part == null) continue;
            if (part.optString("type").equals("image")) encoded.put(part.optString("data"));
            else if (part.optString("type").equals("inputImage") && part.optString("imageUrl").startsWith("data:image/")) encoded.put(part.getString("imageUrl"));
        }
        if (!generation && !view && encoded.length() == 0) return false;
        JSONObject message = message(item.getString("id"));
        if (!group.isEmpty()) message.put("imageGroup", group);
        if (completed && message.optJSONArray("images") != null && message.getJSONArray("images").length() > 0) return true;
        message.put("kind", "image").put("imageStatus", completed ? "completed" : "generating");
        if (!completed) return true;
        JSONArray attachments = new JSONArray();
        List<String> errors = new ArrayList<>();
        try {
            if (generation) {
                if (!item.isNull("failure") || item.optString("status").equals("failed"))
                    throw new IOException(t("이미지 생성에 실패했습니다. ") + (item.optJSONObject("failure") != null && "usageLimitExceeded".equals(item.getJSONObject("failure").optString("type")) ? t("이미지 사용 한도를 확인해 주세요.") : t("다시 시도해 주세요.")));
                String path = item.optString("savedPath", ""), result = item.optString("result", "");
                if (!path.isEmpty()) {
                    try { attachments.put(images.importFile(new File(path))); }
                    catch (Exception e) { if (result.isEmpty()) throw e; attachments.put(images.importBase64(result, "")); }
                } else if (!result.isEmpty()) attachments.put(images.importBase64(result, ""));
                else throw new IOException(t("생성 결과에 이미지 파일이 없습니다. 다시 생성해 주세요."));
            } else if (view) attachments.put(readImage(item.getString("path")));
        } catch (Exception e) { errors.add(unwrap(e).getMessage()); }
        for (int i = 0; i < encoded.length(); i++) {
            try { attachments.put(images.importBase64(encoded.getString(i), "")); }
            catch (Exception e) { errors.add((i + 1) + t("번째 이미지: ") + unwrap(e).getMessage()); }
        }
        if (errors.isEmpty()) message.remove("imageError");
        else message.put("imageStatus", "failed").put("imageError", String.join("\n", errors));
        message.put("images", attachments);
        return true;
    }
    private JSONObject message(String id) throws Exception {
        JSONArray messages = active.getJSONArray("messages");
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.getJSONObject(i); if (m.optString("id").equals(id)) return m;
        }
        JSONObject m = obj("id", id, "role", "assistant", "text", ""); messages.put(m); return m;
    }
    private void onRequest(Object id, String method, JSONObject p) {
        RpcClient connection = rpc;
        try {
            if (!method.equals("item/tool/call")) {
                String key = UUID.randomUUID().toString();
                requests.put(key, new PendingRequest(connection, id, method, p));
                event("server.request", obj("key", key, "method", method, "params", p));
                return;
            }
            if (!p.optString("threadId").equals(threadId) || active == null || !active.optString("workspaceKey").equals(documents.key()))
                throw new IOException(t("대화의 작업 폴더가 일치하지 않습니다."));
            String tool = p.getString("tool"); JSONObject args = p.getJSONObject("arguments");
            event("tool", obj("name", tool, "path", args.optString("path", args.optString("query", ""))));
            if (PhoneToolCatalog.NAMES.contains(tool)) {
                if (tool.equals("mobile_phone_action") && permissionMode.equals("read-only"))
                    throw new IOException(t("읽기 전용 모드에서는 화면 조회만 가능합니다. 조작하려면 작업 권한을 변경해 주세요."));
                connection.respond(id, PhoneUseService.execute(context, tool, args));
            } else if (ToolCatalog.WRITE.contains(tool)) {
                mutate(tool, args, false, (result, error) -> {
                    try { connection.respond(id, ToolCatalog.result(error == null, error == null ? result.toString() : error.getMessage())); }
                    catch (IOException ignored) {}
                });
            } else {
                JSONObject result = switch (tool) {
                    case "mobile_list" -> documents.list(args.optString("path", ""));
                    case "mobile_search" -> documents.search(args.getString("query"));
                    case "mobile_read" -> documents.read(args.getString("path"));
                    default -> throw new IOException(t("알 수 없는 파일 도구입니다."));
                };
                connection.respond(id, ToolCatalog.result(true, result.toString()));
            }
        } catch (Exception e) {
            try { connection.respond(id, ToolCatalog.result(false, unwrap(e).getMessage())); } catch (IOException ignored) {}
        }
    }
    private void mutate(String operation, JSONObject args, boolean interactive, Reply reply) throws Exception {
        if (!ToolCatalog.WRITE.contains(operation)) throw new IOException(t("지원하지 않는 파일 작업입니다."));
        if (permissionMode.equals("read-only")) throw new IOException(t("현재 읽기 전용 모드입니다. 권한 설정을 변경해 주세요."));
        if (pendingApproval != null && !pendingApproval.decision.isDone()) throw new IOException(t("다른 변경 사항을 확인 중입니다. 한 번에 하나씩 요청해 주세요."));
        DocumentStore.Mutation mutation = documents.prepare(operation, args);
        if (!interactive) {
            JSONObject result = documents.commit(mutation); event("files.changed", result); reply.complete(result, null); return;
        }
        Approval approval = new Approval(mutation.title, mutation.preview);
        pendingApproval = approval;
        ScheduledFuture<?> timeout = approvalTimer.schedule(() -> approval.decision.complete(false), 10, TimeUnit.MINUTES);
        approval.decision.whenCompleteAsync((approved, error) -> {
            timeout.cancel(false);
            if (pendingApproval == approval) pendingApproval = null;
            try {
                if (error != null || !Boolean.TRUE.equals(approved)) throw new IOException(t("사용자가 변경을 취소했습니다."));
                JSONObject result = documents.commit(mutation);
                event("files.changed", result); reply.complete(result, null);
            } catch (Throwable e) { reply.complete(null, unwrap(e)); }
        }, io);
        Ui current = ui;
        if (current != null) current.approval(approval);
        for (Ui observer : observers) if (observer != current) observer.approval(approval);
        if (current == null && observers.isEmpty()) approval.decision.complete(false);
    }
    public void stop() {
        PhoneUseService.stopControl();
        // Closing approval first allows a pending action to resolve without changes.
        Approval approval = pendingApproval; if (approval != null) approval.decision.complete(false);
        io.execute(this::stopNow);
    }
    private void stopNow() {
        PhoneUseService.stopControl();
        if (pendingApproval != null) pendingApproval.decision.complete(false);
        Process old = process; process = null;
        if (old != null) old.destroyForcibly();
        if (rpc != null) rpc.close(); rpc = null;
        requests.forEach((key, value) -> event("server.resolved", obj("key", key)));
        requests.clear();
        if (terminalProcess != null) terminalProcess.destroyForcibly();
        ready = false; busy = false; turnId = ""; serverThreadId = ""; status = t("연결 종료");
        persist(); publish();
        context.stopService(new Intent(context, EngineService.class));
    }
    private File projectDirectory() { File dir = documents.directDirectory(); return dir == null ? workDir : dir; }
    private File runtimeAliases() throws Exception {
        File bin = new File(context.getFilesDir(), "runtime-bin");
        if (!bin.isDirectory() && !bin.mkdirs()) throw new IOException(t("명령 경로를 만들 수 없습니다."));
        for (String[] pair : new String[][]{{"codex", "libcodex.so"}, {"codex-code-mode-host", "libcodexmodehostx.so"}}) {
            File alias = new File(bin, pair[0]);
            String target = new File(context.getApplicationInfo().nativeLibraryDir, pair[1]).getAbsolutePath();
            try { if (android.system.Os.readlink(alias.getAbsolutePath()).equals(target)) continue; } catch (android.system.ErrnoException ignored) {}
            Files.deleteIfExists(alias.toPath());
            android.system.Os.symlink(target, alias.getAbsolutePath());
        }
        return bin;
    }
    private void runTerminal(String command) throws Exception {
        if (terminalProcess != null && terminalProcess.isAlive()) throw new IOException(t("터미널 명령이 실행 중입니다."));
        if (command.isBlank()) throw new IOException(t("명령을 입력해 주세요."));
        documents.requireWorkspaceAvailable();
        if (documents.workspace().optBoolean("selected") && documents.directDirectory() == null)
            throw new IOException(t("이 문서 제공자 폴더에서는 셸을 실행할 수 없습니다. 기기 파일 접근 권한을 확인하거나 일반 대화의 앱 내부 작업 폴더를 사용해 주세요."));
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command).directory(projectDirectory()).redirectErrorStream(true);
        devTools.configure(builder, codexHome.root(), runtimeAliases());
        Process running = builder.start(); terminalProcess = running;
        Thread reader = new Thread(() -> {
            try (Reader out = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048]; int n;
                while ((n = out.read(buffer)) != -1) event("terminal.output", obj("text", new String(buffer, 0, n)));
                int code = running.waitFor(); event("terminal.exit", obj("code", code));
            } catch (Exception e) { event("error", obj("message", t("터미널 연결이 종료되었습니다."))); }
            finally { if (terminalProcess == running) terminalProcess = null; }
        }, "mobile-terminal"); reader.setDaemon(true); reader.start();
    }
}
