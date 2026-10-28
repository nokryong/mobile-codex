package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
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
    /** State events are frequent and cross the WebView bridge, so only expose a bounded tail. */
    private static final int SNAPSHOT_MESSAGE_LIMIT = 40;
    public interface Ui {
        void event(String name, JSONObject data);
        void approval(Approval approval);
    }
    public interface Reply { void complete(JSONObject result, Throwable error); }
    /** Narrow in-process seam for protocol regression tests; production always uses RpcClient. */
    interface TestTransport { JSONObject call(String method, JSONObject params) throws Exception; }
    interface TestAgentTransport { void handle(String action, JSONObject args, Reply reply); }
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
    private TestAgentTransport testAgentTransport;
    // Test-only startup validation keeps account-switch rollback tests on the
    // same account/read + rate-limit path as the real app-server startup.
    private boolean testAccountValidation;
    private volatile boolean ready, busy;
    /** Turn ids are scoped to their server thread. The UI may move to another
     * conversation while an earlier thread continues in the same app-server. */
    private final Map<String, String> runningTurns = new HashMap<>();
    private final Map<String, ConsultGrant> consultGrants = new HashMap<>();
    private volatile PendingConsultation pendingConsultation;
    private boolean consultMcpReady;
    private String consultMcpThread = "";
    private String consultMcpError = "";
    private final Set<String> consultMcpThreads = new HashSet<>();
    private ProConsultMcp consultMcp;
    private static final class ConsultGrant {
        final String token = UUID.randomUUID().toString();
        final String workspaceKey;
        String turnId;
        boolean consumed;
        ConsultGrant(String turnId, String workspaceKey) { this.turnId = turnId; this.workspaceKey = workspaceKey; }
    }
    private interface ConsultResponder { void respond(JSONObject result) throws IOException; }
    private static final class PendingConsultation {
        final RpcClient connection;
        final Object requestId;
        final String operationId, localThread, remoteThread, turnId, messageId, grantToken;
        final JSONObject prepared;
        final ConsultResponder responder;
        ScheduledFuture<?> timeout;
        PendingConsultation(RpcClient connection, Object requestId, String operationId, String localThread,
                String remoteThread, String turnId, String messageId, String grantToken, JSONObject prepared, ConsultResponder responder) {
            this.connection = connection; this.requestId = requestId; this.operationId = operationId;
            this.localThread = localThread; this.remoteThread = remoteThread; this.turnId = turnId;
            this.messageId = messageId; this.prepared = prepared;
            this.grantToken = grantToken;
            this.responder = responder;
        }
    }
    private String toolRequestThread = "";
    private String permissionMode, approvalMode;
    private boolean chatIconsEnabled = true;
    private final Map<String, PendingRequest> requests = new LinkedHashMap<>();
    private final TerminalSession terminal = new TerminalSession((name, data) -> {
        event(name, data);
        if (name.equals("terminal.state") && !data.optBoolean("running") && !io.isShutdown()) io.execute(this::stopServiceIfIdle);
    });
    private String status = t("시작할 준비가 됐습니다"), threadId = "", turnId = "", serverThreadId = "";
    private JSONObject account = new JSONObject();
    // Unknown is not signed out. Keep startup/refresh mechanics out of the login UI.
    private String authState = "unknown", authError = "";
    private long lastAccountCheckElapsed = -1;
    private boolean autoRestorePaused, loginInProgress;
    private JSONObject rateLimits = new JSONObject();
    /** Do not publish a staged target until account/rate-limit validation passes. */
    private boolean suppressStatePublish;
    private boolean stagedSwitchValidation;
    private String stagedSwitchKey = "";
    private JSONArray models = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONObject active;
    private volatile String liveRemote = "", liveLocal = "", liveKey = "";
    private Approval pendingApproval;
    private File stateFile;
    private final File workDir;
    private final CodexHome codexHome;
    /** CODEX_HOME used by the currently running app-server. Add-login uses an
     * isolated temporary home until the new credential is durably promoted. */
    private File processHome;
    private final AccountProfiles accountProfiles;
    private final PersonalInstructions instructions;
    private final DevTools devTools;
    private final LinuxRuntime linux;
    private AgentBridge agentBridge;
    /** Tool permission keyed by a unique agent turn, never shared between participants. */
    private final Map<String, JSONObject> roomLeases = new ConcurrentHashMap<>();
    private volatile Process roomCommand;
    private volatile String roomCommandRunId = "";
    /** Agents invited before a new chat's first message creates its session. */
    private JSONArray draftAgents = new JSONArray();
    private JSONArray agentProviders = new JSONArray();
    /** A parallel reply batch; explicit follow-ups start after the batch finishes. */
    private static final class AgentTurn {
        final String id, runId = UUID.randomUUID().toString(), cursorId;
        final JSONObject message;
        String status = "responding";
        AgentTurn(String id, JSONObject message, String cursorId) {
            this.id = id; this.message = message; this.cursorId = cursorId;
        }
    }
    private static final class GroupRun {
        final String local;
        final Set<String> queue = new LinkedHashSet<>();
        final Map<String, AgentTurn> active = new LinkedHashMap<>();
        JSONArray context;
        int codexFrom = -1; boolean cancelled, codexRunning, initialDispatch = true;
        String current = "", model = "", effort = "", codexCursor = "", writer = "";
        GroupRun(String local) { this.local = local; }
    }
    private record AgentConsent(GroupRun group, AgentTurn turn, JSONObject params, Reply reply) {}
    private final ArrayDeque<AgentConsent> agentConsents = new ArrayDeque<>();
    private volatile GroupRun group;
    private Approval agentApproval;
    private AgentConsent activeAgentConsent;
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
        if (!accountProfiles.hasSavedLogin()) authState = "signed_out";
        processHome = codexHome.root();
        instructions = new PersonalInstructions(codexHome);
        devTools = new DevTools(context);
        linux = new LinuxRuntime(context, devTools, this::linuxChanged);
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
            if (pendingConsultation != null) ui.event("pro.consult", (JSONObject) uiJsonCopy(pendingConsultation.prepared));
            requests.forEach((key, request) -> event("server.request", obj("key", key, "method", request.method, "params", requestUiParams(request))));
        });
    }
    public void detach(Ui ui) { if (this.ui == ui) this.ui = null; }
    /** Called by the visible Codex Activity, never by the remote Chat WebView. */
    public void restoreAccount() {
        io.execute(() -> {
            // Lifecycle callbacks can arrive together. All checks and mutations
            // share the same queue as logout, account switching, and runtime stop.
            if (autoRestorePaused || loginInProgress || hasRunningTurns()) return;
            long cooldown = "error".equals(authState) ? 10_000 : 60_000;
            if (lastAccountCheckElapsed >= 0 && SystemClock.elapsedRealtime() - lastAccountCheckElapsed < cooldown) return;
            try { refreshAccount(); }
            catch (Exception ignored) { /* State carries the failure; no startup toast/dialog. */ }
        });
    }
    private void refreshAccount() throws Exception {
        if (loginInProgress) return;
        lastAccountCheckElapsed = SystemClock.elapsedRealtime();
        if (!ready && !accountProfiles.hasSavedLogin()) {
            account = new JSONObject(); rateLimits = new JSONObject();
            authState = "signed_out"; authError = ""; publish(); return;
        }
        // Retain a previously confirmed account while refreshing in the background.
        authState = "checking"; authError = "";
        try {
            if (!ready || (testTransport == null && !processIsAlive())) start();
            else { readAccount(); readRateLimits(); publish(); }
        } catch (Exception error) {
            if (!"signed_out".equals(authState)) {
                authState = "error";
                authError = t("계정 정보를 불러오지 못했습니다. 다시 시도해 주세요.");
            }
            publish(); throw error;
        } finally { lastAccountCheckElapsed = SystemClock.elapsedRealtime(); }
    }
    public void observe(Ui observer) { observers.add(observer); io.execute(() -> { if (observers.contains(observer)) { observer.event("state", snapshot()); if (pendingApproval != null && !pendingApproval.decision.isDone()) observer.approval(pendingApproval); if (pendingConsultation != null) observer.event("pro.consult", (JSONObject) uiJsonCopy(pendingConsultation.prepared)); requests.forEach((key, request) -> observer.event("server.request", obj("key", key, "method", request.method, "params", requestUiParams(request)))); } }); }
    public void unobserve(Ui observer) { observers.remove(observer); }
    void setTestTransport(TestTransport value) { testTransport = value; }
    void setTestAgentTransport(TestAgentTransport value) { testAgentTransport = value; }
    void setTestAccountValidation(boolean value) { testAccountValidation = value; }
    File processHomeForTest() { return processHome; }
    private void event(String name, JSONObject data) { Ui current = ui; if (current != null) current.event(name, data); for (Ui observer : observers) if (observer != current) observer.event(name, data); }
    private JSONObject requestUiParams(PendingRequest request) {
        try {
            JSONObject result = new JSONObject(request.params.toString());
            String local = localIdForCodexThread(result.optString("threadId"));
            if (!local.isBlank()) result.put("threadId", local);
            return result;
        } catch (Exception ignored) { return request.params; }
    }
    static Intent taskNotificationIntent(Context context, String kind, String title, String message, String thread, String approval) {
        return new Intent(context, CodexNotificationReceiver.class).setAction(CodexNotificationReceiver.ACTION)
            .putExtra("kind", kind).putExtra("title", title).putExtra("message", message)
            .putExtra("threadId", thread == null ? "" : thread).putExtra("approvalId", approval == null ? "" : approval);
    }
    private void taskNotification(String kind, String title, String message, String thread, String approval) {
        context.sendBroadcast(taskNotificationIntent(context, kind, title, message, thread, approval));
    }
    private JSONObject snapshot() {
        JSONArray summaries = new JSONArray();
        for (int i = sessions.length() - 1; i >= 0; i--) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null && !s.optBoolean("deletionPending")) summaries.put(obj("id", s.optString("id"), "title", s.optString("title"),
                "workspace", s.optString("workspace"), "workspaceKey", s.optString("workspaceKey"), "projectId", documents.projectId(s.optString("workspaceKey")),
                "busy", runningTurns.containsKey(s.optString("id")) || s.optJSONObject("proOperation") != null || groupRunning(s.optString("id")),
                "approvalPending", s.optBoolean("approvalPending")));
        }
        return obj("ready", ready, "busy", busy, "status", t(status), "account", account,
            "authState", authState, "authError", t(authError),
            "accounts", accountProfiles.list(), "rateLimits", rateLimits, "models", models, "workspace", documents.workspace(), "projects", documents.projects(), "sessions", summaries,
            "threadId", threadId, "turnId", turnId, "live", obj("key", liveKey, "threadId", liveLocal), "proBusy", (pendingConsultation != null && pendingConsultation.localThread.equals(threadId)) || (active != null && active.optJSONObject("proOperation") != null),
            "pendingProConsultation", pendingConsultation == null ? JSONObject.NULL : uiJsonCopy(pendingConsultation.prepared),
            "consultProAvailable", consultProAvailable(),
            "consultProUnavailableReason", consultProAvailable() ? "" : t(consultMcpError),
            "turnDiff", active == null ? "" : active.optString("turnDiff"), "messages", snapshotMessages(),
            "messageHistory", messageHistory(active == null ? null : active.optJSONArray("messages"), snapshotMessageStart()),
            "fastMode", active != null && active.optBoolean("fastMode"),
            "agents", agentsView(), "agentProviders", uiJsonCopy(agentProviders), "agentRun", agentRunView(),
            "pendingDeletionCount", pendingDeletionCount(), "devtools", devTools.status(), "linux", linux.status(),
            "phone", PhoneUseService.status(context), "phoneToolsAvailable", active == null || active.optInt("phoneToolsVersion") >= 1,
            "permissions", permissionMode, "approvalMode", approvalMode, "allFilesAccess", (Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED),
            "directWorkspace", documents.directDirectory() != null, "cwd", projectDirectory().getAbsolutePath(), "terminal", terminal.snapshot());
    }
    /** Return the most recent messages in their original chronological order. */
    private JSONArray snapshotMessages() {
        JSONArray messages = active == null ? null : active.optJSONArray("messages");
        if (messages == null) return new JSONArray();
        int start = Math.max(0, messages.length() - SNAPSHOT_MESSAGE_LIMIT);
        JSONArray result = new JSONArray();
        // MainActivity serializes events after crossing to the UI thread.
        // Copy this bounded page so a later streaming delta cannot mutate an
        // already-emitted state snapshot before WebView serialization.
        for (int i = start; i < messages.length(); i++) result.put(uiJsonCopy(messages.opt(i)));
        return result;
    }
    private static Object uiJsonCopy(Object value) {
        try {
            if (value instanceof JSONObject) return new JSONObject(value.toString());
            if (value instanceof JSONArray) return new JSONArray(value.toString());
        } catch (Exception ignored) { }
        return value;
    }
    private int snapshotMessageStart() {
        JSONArray messages = active == null ? null : active.optJSONArray("messages");
        return messages == null ? 0 : Math.max(0, messages.length() - SNAPSHOT_MESSAGE_LIMIT);
    }
    /** Metadata points to the first returned item; history requests exclude that cursor item. */
    private JSONObject messageHistory(JSONArray messages, int start) {
        int total = messages == null ? 0 : messages.length();
        start = Math.max(0, Math.min(total, start));
        String beforeId = "";
        if (start > 0) {
            JSONObject first = messages.optJSONObject(start);
            beforeId = first == null ? "" : first.optString("id");
        }
        return obj("hasMore", start > 0, "beforeId", beforeId, "total", total);
    }
    private void publish() { if (!suppressStatePublish) event("state", snapshot()); }
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
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optBoolean("deletionPending")) count++;
        }
        return count;
    }
    /** Test-only state-store seam for durable-write failure coverage. */
    void setStateFileForTest(File file) { stateFile = file; }
    /** Reconcile durable sessions with current bindings without changing chat identity or contents. */
    private void restoreSessionProjects() {
        boolean changed = false, generalized = false;
        String original = sessions.toString();
        JSONObject proJournal = null;
        try {
            String saved = context.getSharedPreferences("pro-web-operation", 0).getString("value", "");
            if (!saved.isBlank()) proJournal = new JSONObject(saved);
        } catch (Exception ignored) { }
        for (int i = 0; i < sessions.length(); i++) {
            try {
                JSONObject session = sessions.optJSONObject(i); if (session == null) continue;
                // Conversations belong to this app installation, not to the
                // credential currently used for the next Codex request.
                if (session.has("accountProfileKey")) { session.remove("accountProfileKey"); changed = true; }
                // Legacy records used the Codex server thread as the local UI id.
                // Preserve that local id for drafts while recording the remote id explicitly.
                if (!session.has("sessionVersion") && !session.optString("id").isBlank()) {
                    session.put("codexThreadId", session.optString("id")); session.put("sessionVersion", 2); changed = true;
                }
                if (session.optBoolean("deletionPending")) continue;
                if (!session.has("workspaceKey")) {
                    String key = documents.restoreProjectKey(session.optString("id"), "", session.optString("workspace"));
                    session.put("workspaceKey", key);
                    if (key.isBlank() && !session.optString("workspace").isBlank()) { session.put("workspace", ""); generalized = true; }
                    changed = true;
                } else if (!session.optString("workspaceKey").isBlank()) {
                    String key = documents.restoreProjectKey(session.optString("id"), session.optString("workspaceKey"), session.optString("workspace"));
                    if (!key.equals(session.optString("workspaceKey"))) {
                        session.put("workspaceKey", key);
                        if (key.isBlank()) session.put("workspace", "");
                        changed = true; generalized |= key.isBlank();
                    }
                } else if (!session.optString("workspace").isBlank()) {
                    session.put("workspace", ""); changed = true; generalized = true;
                }
                if (!session.has("messages") || session.optJSONArray("messages") == null) { session.put("messages", new JSONArray()); changed = true; }
                JSONArray restoredMessages = session.getJSONArray("messages");
                for (int m = 0; m < restoredMessages.length(); m++) {
                    JSONObject message = restoredMessages.optJSONObject(m);
                    if (message != null && "proConsultation".equals(message.optString("kind")) && "pending".equals(message.optString("status"))) {
                        message.put("status", "failed").put("text", t("앱이 종료되어 Pro 문의 결과를 원래 Codex 작업에 전달하지 못했습니다. 자동으로 다시 보내지 않습니다."));
                        changed = true;
                    }
                }
                JSONObject interruptedPro = session.optJSONObject("proOperation");
                if (interruptedPro != null) {
                    if (proJournal != null && session.optString("id").equals(proJournal.optString("threadId"))
                            && interruptedPro.optString("id").equals(proJournal.optString("operationId"))) {
                        String recoveredConversation = boundedRemoteId(proJournal.optString("chatConversationId"));
                        String recoveredProject = boundedProjectPath(proJournal.optString("chatProjectPath"));
                        if (!recoveredConversation.isBlank()) session.put("chatConversationId", recoveredConversation);
                        if (!recoveredProject.isBlank()) session.put("chatProjectPath", recoveredProject);
                        String effectiveProject = recoveredProject.isBlank() ? session.optString("chatProjectPath") : recoveredProject;
                        String recoveredPath = boundedConversationPath(proJournal.optString("chatConversationPath"), recoveredConversation, effectiveProject);
                        if (!recoveredPath.isBlank()) session.put("chatConversationPath", recoveredPath);
                    }
                    JSONObject message = messageIn(session, interruptedPro.optString("messageId"));
                    if (message != null && "sending".equals(message.optString("status"))) message.put("status", "uncertain")
                        .put("error", t("앱이 종료되어 웹 전송 결과를 확인하지 못했습니다. 다시 보내기 전에 ChatGPT 웹 대화를 확인해 주세요."));
                    session.remove("proOperation"); changed = true;
                }
            } catch (Exception ignored) { }
        }
        if (changed) {
            try {
                if (generalized) backupSessionsBeforeGeneralMigration(original);
                persistSessions(sessions);
            } catch (IOException error) {
                try { sessions = new JSONArray(original); } catch (Exception ignored) { }
                throw new IllegalStateException(t("대화 작업 폴더 이전을 저장하지 못했습니다."), error);
            }
        }
    }
    private void backupSessionsBeforeGeneralMigration(String original) throws IOException {
        File backup = new File(stateFile.getParentFile(), "sessions-before-general-workspace-v1.json");
        if (backup.exists()) return;
        File pending = new File(stateFile.getParentFile(), "sessions-before-general-workspace-v1.json.tmp");
        try {
            dev.mobilecodex.app.core.Utf8Files.write(pending.toPath(), original);
            try { Files.move(pending.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(pending.toPath(), backup.toPath()); }
        } catch (Exception error) {
            pending.delete(); throw new IOException(t("대화 이전 복구 사본을 저장하지 못했습니다."), error);
        }
    }
    private void backupSessionsForProjectRemoval(String original) throws IOException {
        File backup = new File(stateFile.getParentFile(), "sessions-before-project-remove-" + UUID.randomUUID() + ".json");
        File pending = new File(backup.getPath() + ".tmp");
        try {
            dev.mobilecodex.app.core.Utf8Files.write(pending.toPath(), original);
            try { Files.move(pending.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(pending.toPath(), backup.toPath()); }
        } catch (Exception error) {
            pending.delete(); throw new IOException(t("프로젝트 연결 해제 전 대화 복구 사본을 저장하지 못했습니다."), error);
        }
    }
    private int moveSessionsToGeneral(JSONArray values, String workspaceKey) throws Exception {
        if (workspaceKey == null || workspaceKey.isBlank()) return 0;
        int moved = 0;
        for (int i = 0; i < values.length(); i++) {
            JSONObject session = values.optJSONObject(i);
            if (session == null || !workspaceKey.equals(session.optString("workspaceKey"))) continue;
            session.put("workspaceKey", "").put("workspace", ""); moved++;
        }
        return moved;
    }
    public void updatesChanged(JSONObject data) { event("updates.changed", data); }
    public boolean canInstallUpdate() { return liveRemote.isBlank() && (agentBridge == null || !agentBridge.busy()) && (roomCommand == null || !roomCommand.isAlive()) && roomLeases.isEmpty() && group == null && runningTurns.isEmpty() && !hasProOperations() && !VoiceInput.active() && !linux.status().optBoolean("busy") && !terminal.busy(); }
    public void voiceInputChanged() { event("voice.changed", obj()); }
    void liveAudioChanged(JSONObject state) { event("live.audio", state); }
    public void stopLiveFromActivity() { io.execute(this::stopLive); }
    private JSONObject startLive(JSONObject args) throws Exception {
        ensureEngineIdle();
        String key = args.getString("key"), sdp = args.getString("sdp");
        String language = args.optString("language", "auto");
        if (!Set.of("auto", "ko", "en").contains(language)) throw new IOException(t("지원하지 않는 언어 설정입니다."));
        if (key.isBlank() || key.length() > 100 || !sdp.startsWith("v=0") || sdp.length() > 100000)
            throw new IOException(t("음성 연결 요청이 올바르지 않습니다."));
        documents.requireWorkspaceAvailable(); start();
        if (account.length() == 0) throw new IOException(t("ChatGPT 계정으로 로그인해 주세요."));
        JSONObject catalog = call("thread/realtime/listVoices", obj()).getJSONObject("voices");
        JSONArray voices = catalog.optJSONArray("v3");
        if (voices == null || voices.length() == 0) voices = catalog.getJSONArray("v1");
        String voice = args.optString("voice", catalog.optString("defaultV1"));
        boolean found = false;
        for (int i = 0; i < voices.length(); i++) if (voice.equals(voices.optString(i))) found = true;
        if (!found) throw new IOException(t("지원하지 않는 음성입니다. 목록을 새로고침해 주세요."));
        String model = resolvedModel(args.optString("model"));
        JSONObject config = obj();
        if (!args.optString("effort").isBlank()) config.put("model_reasoning_effort", args.getString("effort"));
        JSONObject params = threadStartParams(model);
        params.put("config", config);
        params.put("serviceTier", args.optBoolean("fastMode") ? fastServiceTier(model) : "default");
        if (active == null || activeCodexThreadId().isBlank()) {
            String remote = call("thread/start", params).getJSONObject("thread").getString("id");
            if (active == null) {
                threadId = "local-" + UUID.randomUUID();
                active = obj("id", threadId, "sessionVersion", 2, "title", t("음성 대화"), "workspace", sessionWorkspaceName(),
                    "workspaceKey", documents.key(), "model", model, "messages", new JSONArray(), "imageHistoryVersion", 1, "phoneToolsVersion", 1);
                if (draftAgents.length() > 0) active.put("agents", new JSONArray(draftAgents.toString()));
                draftAgents = new JSONArray(); sessions.put(active);
            }
            active.put("codexThreadId", remote); serverThreadId = remote;
        } else {
            if (!active.optString("workspaceKey").equals(documents.key())) throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
            params.remove("dynamicTools"); params.put("threadId", activeCodexThreadId()).put("excludeTurns", true);
            call("thread/resume", params); serverThreadId = activeCodexThreadId();
        }
        active.put("model", model).put("fastMode", args.optBoolean("fastMode")); persist();
        liveRemote = activeCodexThreadId(); liveLocal = threadId; liveKey = key;
        String instructions = "You are the voice interface for this Codex conversation. Delegate requested file and coding work to Codex. Existing permissions and approvals apply. Do not treat ordinary speech interruption as cancelling work.";
        if (language.equals("ko")) instructions += " Speak Korean unless the user requests another language.";
        else if (language.equals("en")) instructions += " Speak English unless the user requests another language.";
        JSONObject request = obj("threadId", liveRemote, "version", "v3", "outputModality", "audio", "voice", voice,
            "transport", obj("type", "webrtc", "sdp", sdp), "clientManagedHandoffs", false,
            "includeStartupContext", true, "prompt", instructions, "realtimeSessionId", key);
        try { call("thread/realtime/start", request); publish(); return obj("key", key, "threadId", threadId); }
        catch (Exception error) { stopLive(); throw error; }
    }
    private void clearLive(String reason) {
        if (liveKey.isBlank()) return;
        String key = liveKey;
        liveRemote = ""; liveLocal = ""; liveKey = "";
        event("live.event", obj("key", key, "method", "thread/realtime/closed", "params", obj("reason", reason)));
    }
    private void stopLive() {
        String remote = liveRemote;
        clearLive("requested");
        if (!remote.isBlank()) try { call("thread/realtime/stop", obj("threadId", remote), 10); } catch (Exception ignored) { }
    }
    private void liveNotification(String method, JSONObject params) throws Exception {
        boolean currentLive = !liveRemote.isBlank() && liveRemote.equals(params.optString("threadId"));
        if (currentLive) event("live.event", obj("key", liveKey, "method", method, "params", params));
        if (method.equals("thread/realtime/item/completed")) {
            // Canonical transcript tails may arrive after stop was acknowledged.
            JSONObject item = params.optJSONObject("item"), owner = sessionByCodexThreadId(params.optString("threadId"));
            if (item != null && owner != null && "transcriptSegment".equals(item.optString("type")) && !item.optString("text").isBlank()) {
                String id = "live-" + item.getString("id"), role = item.getString("role");
                JSONArray messages = owner.getJSONArray("messages");
                boolean exists = false;
                for (int i = 0; i < messages.length(); i++) if (id.equals(messages.getJSONObject(i).optString("id"))) exists = true;
                if (!exists && Set.of("user", "assistant").contains(role)) {
                    messages.put(obj("id", id, "role", role, "text", item.getString("text"), "kind", "liveTranscript", "createdAt", System.currentTimeMillis()));
                    persist(); publish();
                }
            }
        }
        if (currentLive && method.equals("thread/realtime/error")) stopLive();
        else if (currentLive && method.equals("thread/realtime/closed")) clearLive("closed");
    }
    public void phoneStateChanged() { io.execute(this::publish); }
    public void handle(String action, JSONObject args, Reply reply) {
        if (action.startsWith("agents.")) {
            try {
                context.startForegroundService(new Intent(context, EngineService.class));
                agentBridge().handle(action, args, (result, error) -> {
                    if (error == null && result != null && action.equals("agents.providers.check")) {
                        JSONArray providers = result.optJSONArray("providers");
                        io.execute(() -> { mergeAgentProviders(providers); publish(); });
                    }
                    reply.complete(result, error);
                });
            } catch (Exception e) { reply.complete(null, e); }
            return;
        }
        if (action.startsWith("chat.pro.") || ((action.equals("chat.send") || action.equals("chat.steer")) && args.optBoolean("consultPro"))) {
            reply.complete(null, new IOException(t("제거된 기능입니다."))); return;
        }
        // Never queue consent revocation behind a slow engine/tool operation.
        if (Set.of("phone.stop", "chat.stop", "runtime.stop", "auth.logout").contains(action)) PhoneUseService.stopControl();
        // Installation cancellation must not queue behind a long Codex RPC.
        if (action.equals("linux.cancel")) { linux.cancel(); reply.complete(linux.status(), null); return; }
        io.execute(() -> {
            try {
                if (Set.of("terminal.run", "files.mutate", "changes.restore", "recovery.restore").contains(action) && (!roomLeases.isEmpty() || (roomCommand != null && roomCommand.isAlive())))
                    throw new IOException(t("초대한 에이전트의 작업을 중지한 뒤 파일이나 명령을 실행해 주세요."));
                switch (action) {
                    case "live.voices" -> { start(); reply.complete(call("thread/realtime/listVoices", obj()), null); }
                    case "live.start" -> { requireChatScope(args); reply.complete(startLive(args), null); }
                    case "live.stop" -> { if (args.optString("key").equals(liveKey)) stopLive(); reply.complete(obj("ok", true), null); }
                    case "live.text" -> {
                        if (liveRemote.isBlank() || !args.optString("key").equals(liveKey)) throw new IOException(t("음성 연결이 종료되었습니다."));
                        String text = args.optString("text").trim();
                        if (text.isEmpty() || text.length() > 50000) throw new IOException(t("메시지는 최대 50,000자까지 입력할 수 있습니다."));
                        JSONObject result = call("thread/realtime/appendText", obj("threadId", liveRemote, "role", "user", "text", text));
                        JSONObject owner = session(liveLocal);
                        if (owner != null) { owner.getJSONArray("messages").put(obj("id", UUID.randomUUID().toString(), "role", "user", "kind", "liveText", "text", text, "createdAt", System.currentTimeMillis())); persist(); publish(); }
                        reply.complete(result, null);
                    }
                    case "state" -> reply.complete(snapshot(), null);
                    case "phone.stop" -> { publish(); reply.complete(snapshot(), null); }
                    case "runtime.start" -> { autoRestorePaused = false; start(); reply.complete(snapshot(), null); }
                    case "auth.refresh" -> { refreshAccount(); reply.complete(snapshot(), null); }
                    case "models.refresh" -> {
                        if (!ready) start();
                        JSONArray latest = call("model/list", obj("limit", 100, "includeHidden", false), 10).optJSONArray("data");
                        if (latest == null) throw new IOException(t("모델 목록 응답이 올바르지 않습니다."));
                        models = latest; publish(); reply.complete(obj("models", models), null);
                    }
                    case "devtools.check" -> { JSONObject result = devTools.check(codexHome.root(), runtimeAliases()); publish(); reply.complete(result, null); }
                    case "linux.status" -> reply.complete(linux.status(), null);
                    case "linux.install" -> {
                        context.startForegroundService(new Intent(context, EngineService.class));
                        try { linux.install(codexHome.root(), runtimeAliases()); reply.complete(linux.status(), null); }
                        catch (Exception error) { stopServiceIfIdle(); throw error; }
                    }
                    case "linux.enable" -> {
                        ensureRuntimeSettingsIdle(); linux.setEnabled(args.getBoolean("enabled"));
                        // Re-read developer instructions on the next turn, including restored chats.
                        serverThreadId = ""; publish(); reply.complete(linux.status(), null);
                    }
                    case "linux.remove" -> {
                        ensureRuntimeSettingsIdle(); linux.remove(); serverThreadId = "";
                        publish(); reply.complete(linux.status(), null);
                    }
                    case "runtime.stop" -> { autoRestorePaused = true; linux.cancel(); stopNow(); reply.complete(snapshot(), null); }
                    case "auth.login" -> reply.complete(beginLogin(false), null);
                    case "auth.add" -> reply.complete(beginLogin(true), null);
                    case "auth.cancel" -> {
                        JSONObject result;
                        try { result = call("account/login/cancel", args); }
                        finally { loginInProgress = false; restoreAccountAfterCancelledLogin(); }
                        reply.complete(result, null);
                    }
                    case "auth.switch" -> { switchAccount(args.getString("key")); reply.complete(snapshot(), null); }
                    case "auth.remove" -> { accountProfiles.delete(args.getString("key")); publish(); reply.complete(snapshot(), null); }
                    case "auth.logout" -> {
                        ensureEngineIdle(); start(); call("account/logout", new JSONObject()); accountProfiles.removeActiveProfile();
                        account = new JSONObject(); rateLimits = new JSONObject(); authState = "signed_out"; authError = "";
                        autoRestorePaused = true; publish(); reply.complete(obj("ok", true), null);
                    }
                    case "permissions.set" -> {
                        ensureEngineIdle();
                        String mode = args.getString("mode");
                        if (!Set.of("read-only", "workspace-write", "danger-full-access").contains(mode)) throw new IOException(t("잘못된 권한 모드입니다."));
                        permissionMode = mode;
                        context.getSharedPreferences("settings", 0).edit().putString("permissions", mode).apply();
                        if (active != null && ready) resumeRemote(true);
                        publish(); reply.complete(obj("ok", true), null);
                    }
                    case "approvals.set" -> {
                        ensureEngineIdle(); String mode = args.getString("mode");
                        if (!Set.of("ask", "auto-review", "allow-all").contains(mode)) throw new IOException(t("잘못된 승인 방식입니다."));
                        approvalMode = mode; context.getSharedPreferences("settings", 0).edit().putString("approvalMode", mode).apply();
                        if (active != null && ready) resumeRemote(true);
                        publish(); reply.complete(obj("ok", true), null);
                    }
                    case "config.read" -> { File config = new File(codexHome.root(), "config.toml"); reply.complete(obj("content", config.exists() ? dev.mobilecodex.app.core.Utf8Files.read(config.toPath()) : ""), null); }
                    case "config.save" -> {
                        ensureEngineIdle(); dev.mobilecodex.app.core.Utf8Files.write(new File(codexHome.root(), "config.toml").toPath(), args.getString("content"));
                        stopCodex(); reply.complete(obj("ok", true), null);
                    }
                    case "instructions.read" -> reply.complete(instructions.read(), null);
                    case "instructions.save" -> {
                        ensureEngineIdle(); JSONObject saved = instructions.save(args.getString("content"));
                        // AGENTS files are read when Codex starts a new execution; force that boundary after a successful write.
                        stopCodex(); reply.complete(saved, null);
                    }
                    case "rpc" -> {
                        start();
                        String method = args.getString("method");
                        JSONObject rpcParams = args.optJSONObject("params") == null ? new JSONObject() : new JSONObject(args.getJSONObject("params").toString());
                        if (rpcParams.has("threadId")) {
                            JSONObject local = session(rpcParams.optString("threadId"));
                            String remote = codexThreadId(local);
                            if (remote.isBlank()) rpcParams.remove("threadId"); else rpcParams.put("threadId", remote);
                        }
                        JSONObject result = call(method, rpcParams);
                        // Usage reads and reset-credit redemption may rotate an
                        // expired access/refresh token inside app-server.auth.
                        // Keep the profile copy in sync before the next
                        // account switch or process restart can restore the
                        // pre-rotation token.
                        if ((method.equals("account/rateLimits/read") || method.equals("account/rateLimitResetCredit/consume")) && account.length() > 0) {
                            accountProfiles.saveCurrentSnapshot(account);
                            if (method.equals("account/rateLimits/read")) {
                                rateLimits = result == null ? new JSONObject() : result;
                                publish();
                            }
                        }
                        reply.complete(result, null);
                    }
                    case "rpc.respond" -> {
                        PendingRequest pending = requests.remove(args.getString("key"));
                        if (pending == null) throw new IOException(t("이미 종료된 요청입니다."));
                        if (pending.method.contains("requestApproval")) {
                            JSONObject approvalSession = sessionByCodexThreadId(pending.params.optString("threadId"));
                            if (approvalSession != null) { approvalSession.put("approvalPending", false); persist(); }
                        }
                        pending.connection.respond(pending.id, args.getJSONObject("result"));
                        reply.complete(obj("ok", true), null);
                    }
                    case "terminal.run" -> reply.complete(runTerminal(args.getString("command")), null);
                    case "terminal.state" -> reply.complete(terminal.snapshot(), null);
                    case "terminal.input" -> reply.complete(terminal.input(args.getString("id"), args.getString("text")), null);
                    case "terminal.stop" -> { terminal.stop(args.getString("id")); reply.complete(terminal.snapshot(), null); }
                    case "files.list" -> { requireFileScope(args); reply.complete(documents.list(args.optString("path", "")), null); }
                    case "files.search" -> { requireFileScope(args); reply.complete(documents.search(args.getString("query")), null); }
                    case "files.read" -> { requireFileScope(args); reply.complete(documents.read(args.getString("path")), null); }
                    case "files.mention" -> { requireFileScope(args); reply.complete(documents.mention(args.getString("path"), attachments), null); }
                    case "images.read" -> { requireFileScope(args); reply.complete(readImage(args.getString("path")), null); }
                    case "files.mutate" -> { requireFileScope(args); mutate(args.getString("operation"), args.getJSONObject("arguments"), true, reply); }
                    case "recovery.list" -> reply.complete(obj("entries", documents.recoveryList()), null);
                    case "recovery.preview" -> { requireScope(args); reply.complete(documents.previewRecovery(args.getString("id")), null); }
                    case "recovery.restore" -> { ensureEngineIdle(); requireScope(args); JSONObject restore = documents.recoveryMutation(args.getString("id")); mutate(restore.getString("operation"), restore.getJSONObject("arguments"), true, reply); }
                    case "changes.list" -> { requireScope(args); reply.complete(changes.list(reviewDirectory()), null); }
                    case "changes.history" -> { requireScope(args); reply.complete(obj("entries", changes.history(reviewDirectory())), null); }
                    case "changes.preview" -> { requireScope(args); reply.complete(changes.preview(reviewDirectory(), args.getString("path")), null); }
                    case "changes.backupPreview" -> { requireScope(args); reply.complete(changes.previewBackup(reviewDirectory(), args.getString("id")), null); }
                    case "changes.restore" -> {
                        ensureEngineIdle(); requireScope(args);
                        if (permissionMode.equals("read-only")) throw new IOException(t("읽기 전용 모드에서는 복원할 수 없습니다."));
                        if (terminal.busy()) throw new IOException(t("실행 중인 터미널 명령을 먼저 중지해 주세요."));
                        JSONObject result = changes.restore(reviewDirectory(), args.getString("token")); event("files.changed", result); reply.complete(result, null);
                    }
                    case "projects.select" -> { documents.selectProject(args.optString("key", "")); clearActive(); publish(); reply.complete(snapshot(), null); }
                    case "projects.remove" -> {
                        String key = args.getString("key");
                        ensureProjectIdle(key);
                        boolean activeProjectRemoved = active != null && key.equals(active.optString("workspaceKey"));
                        JSONArray replacement = new JSONArray(sessions.toString()); int moved = moveSessionsToGeneral(replacement, key);
                        if (moved > 0) backupSessionsForProjectRemoval(sessions.toString());
                        JSONObject removed = documents.removeProject(key);
                        if (moved > 0) {
                            try { persistSessions(replacement); }
                            catch (IOException error) {
                                // The durable project tombstone is itself the recovery journal:
                                // startup will idempotently generalize the still-intact sessions file.
                                replaceSessions(replacement); if (activeProjectRemoved) serverThreadId = ""; syncCurrentTurn(); publish();
                                throw new IOException(t("프로젝트 연결은 해제됐지만 대화 기록 저장을 마치지 못했습니다. 대화는 보존되며 앱을 다시 시작하면 일반 대화 이전을 재시도합니다."), error);
                            }
                            replaceSessions(replacement); if (activeProjectRemoved) serverThreadId = ""; syncCurrentTurn();
                        }
                        publish();
                        removed.put("movedChats", moved).put("workspace", documents.workspace());
                        reply.complete(removed, null);
                    }
                    case "projects.rename" -> { JSONObject renamed = documents.renameProject(args.getString("key"), args.getString("name")); publish(); reply.complete(renamed, null); }
                    case "projects.export" -> reply.complete(documents.exportProject(args.getString("key")), null);
                    case "projects.import.preview" -> reply.complete(documents.previewProjects(args.getString("content")), null);
                    case "projects.import.apply" -> { ensureEngineIdle(); JSONObject imported = documents.importProjects(args.getString("content"), args.getString("token")); publish(); reply.complete(imported, null); }
                    case "projects.create" -> { ensureEngineIdle(); JSONObject created = documents.createProject(args.getString("name")); clearActive(); publish(); reply.complete(created, null); }
                    case "projects.merge" -> { ensureEngineIdle(); JSONObject merged = documents.mergeProjects(args.getString("sourceKey"), args.getString("targetKey")); publish(); reply.complete(merged, null); }
                    case "projects.prefer" -> { ensureEngineIdle(); JSONObject result = documents.preferProject(args.getString("key")); publish(); reply.complete(result, null); }
                    case "documents.projects" -> reply.complete(obj("projects", documents.projects()), null);
                    case "chat.icons.set" -> { setChatIconsEnabled(args.getBoolean("enabled")); reply.complete(obj("ok", true), null); }
                    case "chat.send" -> { requireChatScope(args); if (args.has("chatIconsEnabled")) setChatIconsEnabled(args.getBoolean("chatIconsEnabled"));
                        send(args.optString("text", ""), args.optString("model", ""), args.optString("effort", ""),
                        args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions"),
                        args.has("fastMode") ? args.getBoolean("fastMode") : null, args.optBoolean("consultPro")); reply.complete(obj("ok", true), null); }
                    case "chat.history" -> reply.complete(history(args), null);
                    case "chat.pro.prepare" -> reply.complete(preparePro(args), null);
                    case "chat.pro.complete" -> reply.complete(completePro(args), null);
                    case "chat.pro.fail" -> reply.complete(failPro(args), null);
                    case "chat.pro.consult.complete" -> reply.complete(completeConsultation(args), null);
                    case "chat.steer" -> { steer(args); reply.complete(obj("ok", true), null); }
                    case "chat.new" -> {
                        String key = args.has("workspaceKey") ? args.optString("workspaceKey", "") : documents.key();
                        documents.selectProject(key); clearActive(); draftAgents = new JSONArray(); publish(); reply.complete(snapshot(), null);
                    }
                    case "chat.resume" -> { resume(args.getString("id")); draftAgents = new JSONArray(); reply.complete(snapshot(), null); }
                    case "chat.agents.set" -> { setAgents(args.optJSONArray("agents")); reply.complete(snapshot(), null); }
                    case "chat.rename" -> { renameSession(args.getString("id"), args.getString("title")); publish(); reply.complete(snapshot(), null); }
                    case "chat.delete" -> {
                        boolean deletionPending = deleteSession(args.getString("id"));
                        publish();
                        JSONObject result = snapshot(); result.put("deletionPending", deletionPending);
                        reply.complete(result, null);
                    }
                    case "chat.stop" -> {
                        if (pendingConsultation != null && pendingConsultation.localThread.equals(threadId))
                            finishConsultation(pendingConsultation, "cancelled", t("사용자가 Pro 문의를 취소했습니다."), null);
                        consultGrants.remove(threadId);
                        GroupRun run = group;
                        if (run != null && run.local.equals(threadId)) cancelGroup(run);
                        else if (!turnId.isEmpty()) call("turn/interrupt", obj("threadId", activeCodexThreadId(), "turnId", turnId));
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
    /** Runs folder binding on the engine queue and rechecks all background work. */
    JSONObject selectProjectFolder(Uri uri, String projectKey) throws Exception {
        ensureEngineIdle();
        JSONObject workspace = documents.select(uri, projectKey);
        clearActive(); publish();
        return workspace;
    }
    private void syncCurrentTurn() {
        turnId = threadId.isBlank() ? "" : runningTurns.getOrDefault(threadId, "");
        GroupRun run = group;
        String speaking = run != null && !threadId.isBlank() && run.local.equals(threadId) ? run.current : "";
        busy = !turnId.isBlank() || groupRunning(threadId);
        status = busy ? (!speaking.isBlank() && !speaking.equals(AgentGroup.CODEX) ? AgentGroup.name(speaking) + " · " + t("응답 중") : t("작업 중")) : (ready ? t("연결됨") : status);
    }
    private void clearActive() { stopLive(); active = null; threadId = ""; turnId = ""; serverThreadId = ""; busy = false; if (ready) status = t("연결됨"); }
    public boolean isBusy() { return busy; }
    private boolean hasRunningTurns() { return !runningTurns.isEmpty(); }
    private boolean hasProOperations() {
        if (pendingConsultation != null) return true;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optJSONObject("proOperation") != null) return true;
        }
        return false;
    }
    private void ensureSessionIdle(String id) throws IOException {
        if (!liveLocal.isBlank() && liveLocal.equals(id)) throw new IOException(t("음성 대화를 먼저 종료해 주세요."));
        JSONObject target = session(id);
        if (runningTurns.containsKey(id) || groupRunning(id) || (id.equals(threadId) && busy))
            throw new IOException(t("현재 대화의 작업을 먼저 중지해 주세요."));
        if (target != null && target.optJSONObject("proOperation") != null)
            throw new IOException(t("GPT-6-Pro 답변을 기다리는 대화는 먼저 중지해 주세요."));
        if (pendingConsultation != null && pendingConsultation.localThread.equals(id))
            throw new IOException(t("Pro 문의를 기다리는 대화는 먼저 중지해 주세요."));
    }
    private void ensureProjectIdle(String key) throws IOException {
        if(roomCommand!=null&&roomCommand.isAlive())throw new IOException(t("이전 명령이 종료 중입니다. 잠시 후 다시 보내 주세요."));
        if(roomLeases.values().stream().anyMatch(lease->key.equals(lease.optString("projectKey"))))throw new IOException(t("이 프로젝트에서 답하는 에이전트를 먼저 중지해 주세요."));
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject target = sessions.optJSONObject(i);
            if (target != null && key.equals(target.optString("workspaceKey"))) ensureSessionIdle(target.optString("id"));
        }
    }
    private void ensureEngineIdle() throws IOException {
        if (!liveRemote.isBlank()) throw new IOException(t("음성 대화를 먼저 종료해 주세요."));
        if(roomCommand!=null&&roomCommand.isAlive())throw new IOException(t("이전 명령이 종료 중입니다. 잠시 후 다시 보내 주세요."));
        if(!roomLeases.isEmpty()||group!=null)throw new IOException(t("초대한 에이전트의 답변을 먼저 중지해 주세요."));
        if (hasRunningTurns() || hasProOperations()) throw new IOException(t("진행 중인 작업을 먼저 중지해 주세요."));
    }
    private void ensureRuntimeSettingsIdle() throws IOException {
        ensureEngineIdle();
        if(agentBridge!=null&&agentBridge.busy())throw new IOException(t("에이전트 설치·로그인 또는 연결 종료를 먼저 마쳐 주세요."));
        if (terminal.busy()) throw new IOException(t("터미널 명령을 마친 뒤 Linux 설정을 변경해 주세요."));
        if (linux.status().optBoolean("busy")) throw new IOException(t("Linux 설치 작업을 마친 뒤 다시 시도해 주세요."));
    }
    private void linuxChanged() {
        if (io.isShutdown()) return;
        io.execute(() -> { event("linux.changed", linux.status()); stopServiceIfIdle(); });
    }
    private void stopServiceIfIdle() {
        if (roomLeases.isEmpty() && (agentBridge == null || !agentBridge.running()) && !linux.status().optBoolean("busy") && !ready && (process == null || !process.isAlive())
                && !terminal.busy()) context.stopService(new Intent(context, EngineService.class));
    }
    private void clearRunningState() {
        clearLive("runtimeStopped");
        GroupRun run = group; if (run != null) cancelGroup(run);
        if (pendingConsultation != null) finishConsultation(pendingConsultation, "failed", t("Codex 연결이 종료되어 Pro 문의를 완료하지 못했습니다."), null);
        consultGrants.clear(); consultMcpReady = false; consultMcpThread = ""; consultMcpError = ""; consultMcpThreads.clear();
        runningTurns.clear();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject value = sessions.optJSONObject(i);
            if (value != null) try { value.put("approvalPending", false); } catch (Exception ignored) {}
        }
        busy = false; turnId = "";
    }
    private void start() throws Exception {
        autoRestorePaused = false;
        if (testTransport != null) {
            ready = true;
            if (testAccountValidation) { readAccount(); readRateLimits(); }
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
        File config = new File(processHome, "config.toml");
        if (!config.exists()) dev.mobilecodex.app.core.Utf8Files.write(config.toPath(), "cli_auth_credentials_store = \"file\"\napproval_policy = \"on-request\"\n");
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "app-server", "--listen", "stdio://");
        builder.directory(projectDirectory());
        devTools.configure(builder, processHome, runtimeAliases());
        // Retire the app-owned Pro bridge left in old configurations. Only disable an entry
        // that exists: Codex rejects an MCP server that has no transport and will not start.
        if (retiredProBridgeConfigured(config)) builder.command().addAll(List.of("-c", "mcp_servers.mobile_codex_pro.enabled=false"));
        linux.configureEnvironment(builder.environment());
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
            final RpcClient[] launchedConnection = new RpcClient[1];
            rpc = new RpcClient(process.getInputStream(), process.getOutputStream(), new RpcClient.Listener() {
                @Override public void notification(String method, JSONObject params) { io.execute(() -> onNotification(method, params)); }
                @Override public void request(Object id, String method, JSONObject params) { io.execute(() -> onRequest(launchedConnection[0], id, method, params)); }
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
                        ready = false; clearRunningState(); serverThreadId = "";
                        if (consultMcp != null) consultMcp.close(); consultMcp = null;
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
            launchedConnection[0] = rpc;
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
            stopCodex(); throw new IOException(detail, e);
        }
    }
    private JSONObject call(String method, JSONObject params) throws Exception { return call(method, params, 65); }
    private JSONObject call(String method, JSONObject params, int timeoutSeconds) throws Exception {
        if (testTransport != null) return testTransport.call(method, params);
        if (rpc == null) throw new IOException(t("먼저 Codex를 시작해 주세요."));
        return rpc.request(method, params).get(timeoutSeconds, TimeUnit.SECONDS);
    }
    private void readAccount() throws Exception {
        // Do not force a refresh here. In app-server v0.155.1,
        // account/read(refreshToken:true) unconditionally consumes and rotates
        // the refresh token, even when the access token is still fresh. The
        // normal auth() path refreshes only when needed; profile snapshots are
        // updated after account/rateLimits/read if that path rotates auth.
        JSONObject data = call("account/read", obj("refreshToken", false));
        account = data.optJSONObject("account"); if (account == null) account = new JSONObject();
        if (account.length() > 0) {
            boolean activate = !stagedSwitchValidation && !(addAccountRestoreKey != null
                && !addAccountRestoreKey.isBlank() && accountProfiles.hasPendingAddLogin());
            if (activate) {
                accountProfiles.saveCurrent(account);
                // If the app restarted after a successful add-login but
                // before cleanup, the live account is the new one. Persist it
                // and clear only that completion marker; do not restore old auth.
                if (accountProfiles.isAddLoginCompleting()) accountProfiles.finishAddLogin(account);
            } else accountProfiles.saveCurrentSnapshot(account);
        }
        authState = account.length() > 0 ? "signed_in" : "signed_out";
        authError = ""; lastAccountCheckElapsed = SystemClock.elapsedRealtime();
        // Account display must not wait for quota/model network requests.
        // An isolated login is not active until its credential promotion commits.
        if (addAccountRestoreKey.isBlank() || !accountProfiles.hasPendingAddLogin()) publish();
    }
    private void readRateLimits() throws Exception {
        if (account.length() == 0) { rateLimits = new JSONObject(); return; }
        try {
            rateLimits = call("account/rateLimits/read", new JSONObject());
            // auth() may have refreshed credentials while fetching limits.
            // Persist that generation to the currently selected profile.
            accountProfiles.saveCurrentSnapshot(account);
        }
        catch (Exception error) {
            rateLimits = new JSONObject();
            String message = unwrap(error).getMessage();
            if (message != null && (message.contains("token_revoked") || message.contains("invalidated oauth token") || message.contains("401")))
                {
                    String invalidKey = stagedSwitchKey.isBlank() ? accountProfiles.activeKey() : stagedSwitchKey;
                    try { accountProfiles.markNeedsLogin(invalidKey); } catch (Exception ignored) { }
                    account = new JSONObject(); authState = "signed_out";
                    authError = t("저장된 계정의 로그인 토큰이 폐기되었습니다. 이 계정은 다시 로그인해야 합니다.");
                    throw new IOException(authError, error);
                }
        }
    }
    private JSONObject beginLogin(boolean add) throws Exception {
        ensureEngineIdle();
        autoRestorePaused = false;
        // Re-login must not validate a revoked token before reaching device login.
        // Use the existing isolated transaction so cancellation preserves the profile.
        if (!add && accountProfiles.hasSavedLogin() && accountProfiles.activeNeedsLogin()) add = true;
        if (add) {
            // Recovery must not validate the existing account first: a
            // token_revoked account cannot reach device login otherwise.
            // Snapshot only while a live server is available, then cross the
            // process boundary before starting the isolated login home. Never logout.
            boolean snapshotted = ready && processIsAlive();
            if (snapshotted) accountProfiles.saveCurrentForAddLogin(account);
            stopCodex();
            JSONObject profile = accountProfiles.prepareAddLogin(account, snapshotted);
            addAccountRestoreKey = profile.optString("key");
            account = new JSONObject(); rateLimits = new JSONObject(); publish();
            processHome = accountProfiles.pendingLoginHome();
            if (processHome == null) throw new IOException(t("새 로그인 공간을 준비하지 못했습니다."));
            start();
        } else {
            start();
            addAccountRestoreKey = "";
        }
        loginInProgress = true;
        try { return call("account/login/start", obj("type", "chatgptDeviceCode")); }
        catch (Exception error) { loginInProgress = false; throw error; }
    }

    private boolean processIsAlive() {
        return process != null && process.isAlive() && rpc != null && !rpc.isClosed();
    }
    private void restoreAccountAfterCancelledLogin() throws Exception {
        if (addAccountRestoreKey.isBlank() && !accountProfiles.hasPendingAddLogin()) return;
        addAccountRestoreKey = "";
        stopCodex(); accountProfiles.restorePreparedAddLogin(); processHome = codexHome.root(); account = new JSONObject(); rateLimits = new JSONObject(); start();
    }
    private void switchAccount(String key) throws Exception {
        ensureEngineIdle();
        String previous = accountProfiles.activeKey();
        if (key.equals(previous)) return;
        if (account.length() > 0) accountProfiles.saveCurrent(account);
        suppressStatePublish = true;
        stopCodex();
        stagedSwitchValidation = true;
        stagedSwitchKey = key;
        try {
            accountProfiles.stageSwitch(key);
            account = new JSONObject(); rateLimits = new JSONObject(); start();
            // Only now expose the target as the active account. If validation
            // fails, the marker and live auth still point to the old account.
            accountProfiles.commitStagedSwitch(key);
        } catch (Exception error) {
            try { stopCodex(); } catch (Exception ignored) { }
            try { accountProfiles.rollbackStagedSwitch(); } catch (Exception ignored) { }
            account = new JSONObject(); rateLimits = new JSONObject();
            try { stagedSwitchValidation = false; start(); } catch (Exception ignored) { }
            throw error;
        } finally {
            stagedSwitchValidation = false;
            stagedSwitchKey = "";
            suppressStatePublish = false;
            publish();
        }
    }
    private String resolvedModel(String requested) {
        if (ProContextBuilder.MODEL_ID.equals(requested))
            throw new IllegalArgumentException(t("GPT-6-Pro 웹 모델은 Codex 요청으로 보낼 수 없습니다."));
        if (requested != null && !requested.isBlank()) return requested;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && model.optBoolean("isDefault")) return model.optString("model", model.optString("id"));
        }
        return "";
    }
    /** Catalog-driven Fast selection. Do not infer support from a model id or display name. */
    private String fastServiceTier(String model) throws IOException {
        JSONObject selected = null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject candidate = models.optJSONObject(i);
            if (candidate != null && (model.equals(candidate.optString("model")) || model.equals(candidate.optString("id")))) {
                selected = candidate; break;
            }
        }
        if (selected != null) {
            JSONArray tiers = selected.optJSONArray("serviceTiers");
            for (String desired : new String[]{"fast", "priority"}) {
                if (tiers != null) for (int i = 0; i < tiers.length(); i++) {
                    JSONObject tier = tiers.optJSONObject(i);
                    if (tier != null && desired.equals(tier.optString("id"))) return desired;
                }
            }
            JSONArray legacy = selected.optJSONArray("additionalSpeedTiers");
            for (String desired : new String[]{"fast", "priority"}) {
                if (legacy != null) for (int i = 0; i < legacy.length(); i++)
                    if (desired.equals(legacy.optString(i))) return desired;
            }
        }
        throw new IOException(t("선택한 모델에서는 Fast 모드를 지원하지 않습니다."));
    }
    private void setChatIconsEnabled(boolean enabled) {
        if (chatIconsEnabled != enabled) {
            chatIconsEnabled = enabled;
            // Apply changed developer instructions when this conversation next resumes.
            serverThreadId = "";
        }
    }
    private String workspaceInstructions(String model) {
        JSONObject workspace = documents.workspace();
        return ToolCatalog.INSTRUCTIONS + " Selected Android folder: " + workspace.optString("name", "none")
            + ". Folder available: " + workspace.optBoolean("available") + ". Direct shell access to that folder: "
            + (documents.directDirectory() != null) + ". The exact model requested for this thread is "
            + (model == null || model.isBlank() ? "not available from the runtime" : model)
            + ". When the user asks which model you are, report that exact requested model id."
            + linuxInstructions(linux.status()) + ChatIconInstructions.forEnabled(chatIconsEnabled);
    }
    static String linuxInstructions(JSONObject state) {
        if (!state.optBoolean("installed") || !state.optBoolean("enabled"))
            return " Optional Linux development environment is disabled. Do not download or enable it automatically. The user can install and enable it in Settings > Tools.";
        return " Optional Arch Linux ARM64 environment is enabled. For Linux development commands use the existing shell tool with `mc-linux -- /bin/bash -lc 'command'`; `mc-linux status` reports readiness. "
            + "It uses the shell's current working directory as /workspace in Linux, so change the host working directory to the requested project first. Files in /workspace are the same project files. "
            + "Linux packages and /root persist separately from the Android toolchain. Check available commands before assuming a compiler or package is installed. "
            + "Use the normal approval policy for Linux commands and package installations. PRoot does not grant Android root or provide a security sandbox. "
            + "Host credentials are not automatically available inside Linux. Do not copy them to work around login requirements. Model inference remains online.";
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
    private String codexThreadId(JSONObject session) {
        if (session == null) return "";
        if (session.has("codexThreadId")) return session.optString("codexThreadId");
        return session.has("sessionVersion") ? "" : session.optString("id");
    }
    private String activeCodexThreadId() { return codexThreadId(active); }
    private JSONObject sessionByCodexThreadId(String remoteId) {
        if (remoteId == null || remoteId.isBlank()) return null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject value = sessions.optJSONObject(i);
            if (value != null && remoteId.equals(codexThreadId(value))) return value;
        }
        return null;
    }
    private String localIdForCodexThread(String remoteId) {
        JSONObject value = sessionByCodexThreadId(remoteId);
        return value == null ? "" : value.optString("id");
    }
    /** thread/resume schema accepts cwd and instructions but not dynamicTools. */
    private void resumeRemote(boolean force) throws Exception { resumeRemote(force, active == null ? "" : active.optString("model")); }
    private void resumeRemote(boolean force, String model) throws Exception {
        String remoteId = activeCodexThreadId();
        if (active == null || remoteId.isEmpty() || (!force && remoteId.equals(serverThreadId))) return;
        if (!active.optString("workspaceKey").equals(documents.key()))
            throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
        documents.requireWorkspaceAvailable();
        call("thread/resume", obj("threadId", remoteId, "excludeTurns", true, "cwd", projectDirectory().getAbsolutePath(),
            "sandbox", permissionMode, "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer(), "developerInstructions", workspaceInstructions(resolvedModel(model))));
        serverThreadId = remoteId;
        restoreImageHistory();
        refreshConsultMcpAvailability(remoteId);
    }
    /**
     * Existing conversations can only reach Pro through the MCP bridge. Report it unavailable only
     * after this conversation was actually checked; an unchecked conversation is verified on send.
     */
    private boolean consultProAvailable() {
        return false;
    }
    private void refreshConsultMcpAvailability(String remoteId) {
        if (consultMcp == null || testTransport != null) return;
        consultMcpThread = remoteId;
        if (consultMcpThreads.contains(remoteId)) { consultMcpReady = true; consultMcpError = ""; return; }
        consultMcpReady = false;
        long deadline = SystemClock.elapsedRealtime() + 20000;
        // The stdio MCP server starts asynchronously after thread/resume; retry while it comes up.
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                if (deadline - SystemClock.elapsedRealtime() < 1500) break;
                SystemClock.sleep(1500);
            }
            try {
                String result = findConsultMcpTool(remoteId, deadline);
                if (result.isEmpty()) { consultMcpThreads.add(remoteId); consultMcpReady = true; consultMcpError = ""; return; }
                consultMcpError = result;
            } catch (Exception error) {
                consultMcpError = "Pro 문의 도구 상태를 확인하지 못했습니다: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
    }
    /** @return an empty string when consult_pro is listed, otherwise the reason it is unavailable. */
    private String findConsultMcpTool(String remoteId, long deadline) throws Exception {
        String cursor = "", reason = "Pro 문의 MCP 서버(mobile_codex_pro)가 시작되지 않았습니다.";
        for (int page = 0; page < 20; page++) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            JSONObject params = obj("detail", "toolsAndAuthOnly", "limit", 100);
            if (!remoteId.isBlank()) params.put("threadId", remoteId);
            if (!cursor.isBlank()) params.put("cursor", cursor);
            JSONObject response = call("mcpServerStatus/list", params, Math.max(1, (int) ((remaining + 999) / 1000)));
            JSONArray data = response.optJSONArray("data");
            if (data != null) for (int i = 0; i < data.length(); i++) {
                JSONObject server = data.optJSONObject(i);
                if (server == null || !"mobile_codex_pro".equals(server.optString("name"))) continue;
                if (hasConsultTool(server.opt("tools"))) return "";
                String toolsError = server.optString("toolsError", server.optString("error"));
                reason = toolsError.isBlank() ? "Pro 문의 MCP 서버에 consult_pro 도구가 없습니다." : "Pro 문의 MCP 서버 오류: " + toolsError;
            }
            String next = response.optString("nextCursor");
            if (next.isBlank() || next.equals(cursor) || "null".equals(next)) break;
            cursor = next;
        }
        return reason;
    }
    /** Tool maps may be keyed by plain or server-qualified names; arrays carry a name field. */
    static boolean hasConsultTool(Object tools) {
        if (tools instanceof JSONObject map) {
            for (java.util.Iterator<String> keys = map.keys(); keys.hasNext(); ) if (isConsultToolName(keys.next())) return true;
        } else if (tools instanceof JSONArray list) {
            for (int i = 0; i < list.length(); i++) {
                Object item = list.opt(i);
                String name = item instanceof JSONObject tool ? tool.optString("name") : String.valueOf(item);
                if (isConsultToolName(name)) return true;
            }
        }
        return false;
    }
    private static boolean isConsultToolName(String name) {
        return "consult_pro".equals(name) || name.endsWith("__consult_pro") || name.endsWith(".consult_pro") || name.endsWith("/consult_pro");
    }
    /** Old local records predate persisted image IDs; only recover them after a server resume. */
    private void restoreImageHistory() {
        if (active == null || active.optInt("imageHistoryVersion") >= 1) return;
        try {
            String remoteId = activeCodexThreadId();
            if (remoteId.isBlank()) return;
            JSONObject thread = call("thread/read", obj("threadId", remoteId, "includeTurns", true)).optJSONObject("thread");
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
    private String sessionWorkspaceName() {
        return documents.key().isBlank() ? "" : documents.workspace().optString("name");
    }
    private JSONObject userMessage(String text, JSONArray attachmentIds, JSONArray skills, JSONArray mentions) throws Exception {
        JSONObject message = obj("role", "user", "text", text, "id", UUID.randomUUID().toString(), "createdAt", System.currentTimeMillis());
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
    private void requireFileScope(JSONObject args) throws IOException {
        // Older packaged UIs omitted this field. Scoped requests must match before any I/O.
        if (args.has("workspaceKey")) requireScope(args);
    }
    private void requireChatScope(JSONObject args) throws IOException {
        if (args.has("expectedThreadId") && !args.optString("expectedThreadId").equals(threadId)) throw new IOException(t("대화가 바뀌었습니다. 현재 대화에서 다시 보내 주세요."));
        if (args.has("workspaceKey")) requireScope(args);
    }
    /** Fetch older local display history without exposing another thread or an overlapping cursor item. */
    private JSONObject history(JSONObject args) throws IOException {
        // JSONObject#getString throws checked JSONException. Validate the wire
        // shape explicitly so malformed/stale requests take the normal RPC
        // error path and this helper remains an IOException-only boundary.
        Object rawThread = args.opt("threadId");
        Object rawBeforeId = args.opt("beforeId");
        if (!(rawThread instanceof String) || !(rawBeforeId instanceof String))
            throw new IOException(t("이전 메시지 위치가 오래되었습니다. 대화를 다시 열어 주세요."));
        String requestedThread = (String) rawThread;
        String beforeId = (String) rawBeforeId;
        if (requestedThread.isBlank() || beforeId.isBlank() || active == null || !requestedThread.equals(threadId)
                || !requestedThread.equals(active.optString("id")))
            throw new IOException(t("대화가 바뀌었습니다. 현재 대화를 다시 열어 주세요."));
        JSONObject stored = session(requestedThread);
        if (stored == null || stored.optBoolean("deletionPending")) throw new IOException(t("대화를 찾을 수 없습니다."));
        JSONArray messages = stored.optJSONArray("messages");
        if (messages == null) messages = new JSONArray();
        int cursor = -1;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && beforeId.equals(message.optString("id"))) {
                if (cursor >= 0) throw new IOException(t("이전 메시지 위치가 오래되었습니다. 대화를 다시 열어 주세요."));
                cursor = i;
            }
        }
        if (cursor < 0) throw new IOException(t("이전 메시지 위치가 오래되었습니다. 대화를 다시 열어 주세요."));
        int requestedLimit = args.has("limit") ? args.optInt("limit", SNAPSHOT_MESSAGE_LIMIT) : SNAPSHOT_MESSAGE_LIMIT;
        int limit = Math.max(1, Math.min(SNAPSHOT_MESSAGE_LIMIT, requestedLimit));
        int start = Math.max(0, cursor - limit);
        JSONArray page = new JSONArray();
        for (int i = start; i < cursor; i++) page.put(uiJsonCopy(messages.opt(i)));
        return obj("threadId", requestedThread, "messages", page, "messageHistory", messageHistory(messages, start));
    }
    private void steer(JSONObject args) throws Exception {
        requireChatScope(args);
        String text = args.optString("text").trim();
        if (text.isEmpty() || text.length() > 50000) throw new IOException(t("추가 지시는 1~50,000자로 입력해 주세요."));
        if (!busy || turnId.isEmpty() || !turnId.equals(args.optString("expectedTurnId"))) throw new IOException(t("진행 중인 작업이 변경되거나 종료되었습니다. 새 메시지로 보내 주세요."));
        JSONArray input = input(text, args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions"));
        GroupRun interruptedGroup = group;
        if (interruptedGroup != null && interruptedGroup.local.equals(threadId)) {
            Set<String> alreadySent = new HashSet<>();
            for (int i = 0; i < interruptedGroup.context.length(); i++) alreadySent.add(interruptedGroup.context.optJSONObject(i).optString("id"));
            input = prepend(input, AgentGroup.codexContext(active, active.getJSONArray("messages"), active.getJSONArray("messages").length(), alreadySent));
        }
        ConsultGrant consultation = null;
        if (args.optBoolean("consultPro")) {
            requireConsultationTool(); consultation = new ConsultGrant(turnId, active.optString("workspaceKey"));
            input = consultationInput(input, consultation, active.optInt("consultToolsVersion") >= 1);
        }
        String remoteId = activeCodexThreadId();
        if (remoteId.isBlank()) throw new IOException(t("이 대화에는 진행 중인 Codex 작업이 없습니다."));
        call("turn/steer", obj("threadId", remoteId, "expectedTurnId", turnId, "input", input));
        if (consultation != null) consultGrants.put(threadId, consultation);
        JSONObject message = userMessage(text, args.optJSONArray("attachments"), args.optJSONArray("skills"), args.optJSONArray("mentions"));
        if (consultation != null) message.put("consultPro", true);
        active.getJSONArray("messages").put(message);
        if (interruptedGroup != null && interruptedGroup.local.equals(threadId)) {
            active.put("codexCursorId", message.optString("id"));
            cancelGroup(interruptedGroup, false);
        }
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
    private String proGitDiff() {
        try {
            File directory = documents.directDirectory();
            if (directory == null || !new File(directory, ".git").exists()) return "";
            return new String(git(directory, List.of("diff", "--no-ext-diff", "--unified=3", "--", ".")), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
    private void requireConsultationTool() throws IOException {
        if (active == null || activeCodexThreadId().isBlank() || active.optInt("consultToolsVersion") >= 1) return;
        String remoteId = activeCodexThreadId();
        // A previous check may have run before the MCP server finished starting.
        if (!(consultMcpReady && remoteId.equals(consultMcpThread))) { consultMcpThreads.remove(remoteId); refreshConsultMcpAvailability(remoteId); }
        if (!(consultMcpReady && remoteId.equals(consultMcpThread))) {
            publish();
            throw new IOException(t("이 대화의 Pro 문의 도구를 연결하지 못했습니다. 연결 후 다시 시도해 주세요.") + (consultMcpError.isBlank() ? "" : " (" + t(consultMcpError) + ")"));
        }
    }
    private JSONArray consultationInput(JSONArray input, ConsultGrant grant, boolean nativeTool) throws Exception {
        String route = nativeTool ? "Call mobile_consult_pro with {prompt: focusedQuestionAndEvidence}."
            : "Call the mobile_codex_pro MCP consult_pro tool with requestToken=" + grant.token + " and prompt=focusedQuestionAndEvidence.";
        input.put(obj("type", "text", "text", "[Explicit user request: consult ChatGPT Pro]\n"
            + "For this user request, gather the relevant evidence and prepare one focused question for ChatGPT Pro. "
            + route + " Invoke it once before continuing this task. Do not automatically retry a failed consultation. "
            + "Send only the focused question and necessary evidence, without unrelated conversation history, global instructions, or project-wide dumps. "
            + "Use the consultant's result to continue the same Codex turn and answer the user.", "text_elements", new JSONArray()));
        return input;
    }
    /** Authenticated MCP clients pass an opaque per-request grant; they never choose the target conversation. */
    void consultPro(String requestToken, String prompt, Reply reply) {
        io.execute(() -> {
            try {
                String local = ""; ConsultGrant grant = null;
                for (Map.Entry<String, ConsultGrant> entry : consultGrants.entrySet()) {
                    if (entry.getValue().token.equals(requestToken)) { local = entry.getKey(); grant = entry.getValue(); break; }
                }
                if (grant == null) throw new IOException(t("이 사용자 요청에는 Pro 문의 권한이 없습니다."));
                if (!ready || rpc == null || rpc.isClosed()) throw new IOException(t("Codex 연결이 종료되었습니다."));
                JSONObject target = session(local);
                if (target == null) throw new IOException(t("대화 작업을 찾을 수 없습니다."));
                RpcClient connection = rpc;
                beginConsultation(connection, requestToken, local, codexThreadId(target), grant.turnId, prompt,
                    result -> reply.complete(result, null));
            } catch (Exception error) { reply.complete(ToolCatalog.result(false, unwrap(error).getMessage()), null); }
        });
    }
    void cancelProConsultation(String requestToken) {
        io.execute(() -> {
            PendingConsultation pending = pendingConsultation;
            if (pending != null && pending.grantToken.equals(requestToken))
                finishConsultation(pending, "cancelled", t("Pro 문의 연결이 취소되었습니다."), null);
        });
    }
    private void beginConsultation(RpcClient connection, Object requestId, String localThread, String remoteThread,
            String requestTurn, String prompt, ConsultResponder responder) throws Exception {
        if (connection == null || connection.isClosed()) throw new IOException(t("Codex 연결이 종료되었습니다."));
        if (prompt == null || prompt.isBlank() || prompt.length() > 50000)
            throw new IOException(t("Pro 문의는 1~50,000자로 입력해 주세요."));
        ConsultGrant grant = consultGrants.get(localThread);
        String running = runningTurns.get(localThread);
        if (grant == null || grant.consumed || requestTurn == null || requestTurn.isBlank()
                || !requestTurn.equals(running) || (!grant.turnId.isBlank() && !grant.turnId.equals(requestTurn)))
            throw new IOException(t("이 사용자 요청의 Pro 문의 권한이 없거나 이미 사용되었습니다."));
        JSONObject target = session(localThread);
        if (target == null || target.optBoolean("deletionPending") || !remoteThread.equals(codexThreadId(target))
                || !grant.workspaceKey.equals(target.optString("workspaceKey")))
            throw new IOException(t("대화 작업을 찾을 수 없습니다."));
        grant.turnId = requestTurn; grant.consumed = true;
        if (hasProOperations()) throw new IOException(t("다른 Pro 문의가 진행 중입니다. 자동으로 다시 문의하지 않습니다."));
        String operationId = UUID.randomUUID().toString(), messageId = UUID.randomUUID().toString();
        JSONObject prepared = ProContextBuilder.buildConsultation(prompt);
        String consultProject = target.optString("proConsultProjectPath");
        if (consultProject.isBlank()) consultProject = target.optString("chatProjectPath");
        prepared.put("operationId", operationId).put("threadId", localThread).put("turnId", requestTurn)
            .put("chatConversationId", target.optString("proConsultConversationId"))
            .put("chatConversationPath", target.optString("proConsultConversationPath"))
            .put("chatProjectPath", consultProject);
        JSONArray replacement = new JSONArray(sessions.toString());
        JSONObject staged = sessionIn(replacement, localThread);
        staged.getJSONArray("messages").put(obj("id", messageId, "role", "assistant", "kind", "proConsultation",
            "backend", "chatgpt-web", "source", "ChatGPT Pro", "text", t("ChatGPT Pro에 문의 중입니다."),
            "prompt", prompt, "status", "pending", "operationId", operationId, "callId", String.valueOf(requestId), "createdAt", System.currentTimeMillis()));
        persistSessions(replacement); replaceSessions(replacement);
        PendingConsultation pending = new PendingConsultation(connection, requestId, operationId, localThread,
            remoteThread, requestTurn, messageId, grant.token, prepared, responder);
        pendingConsultation = pending;
        pending.timeout = approvalTimer.schedule(() -> io.execute(() ->
            finishConsultation(pending, "timeout", t("Pro 문의 응답 시간이 초과되었습니다. 자동으로 다시 보내지 않습니다."), null)), 640, TimeUnit.SECONDS);
        publish(); event("pro.consult", (JSONObject) uiJsonCopy(prepared));
    }
    private JSONObject completeConsultation(JSONObject args) throws Exception {
        PendingConsultation pending = pendingConsultation;
        if (pending == null || !pending.operationId.equals(args.optString("operationId")) || !pending.localThread.equals(args.optString("threadId")))
            return obj("ok", true, "stale", true);
        String status = args.optString("status", "completed");
        if (!Set.of("completed", "failed", "cancelled", "timeout").contains(status)) {
            finishConsultation(pending, "failed", t("Pro 문의가 잘못된 응답 상태를 반환했습니다."), null);
            return obj("ok", true);
        }
        String text = "completed".equals(status) ? args.optString("reply") : args.optString("reason", t("Pro 문의에 실패했습니다."));
        if ("completed".equals(status) && (text.isBlank() || text.length() > 200000)) {
            finishConsultation(pending, "failed", t("Pro 답변 길이가 올바르지 않습니다."), null);
            return obj("ok", true);
        }
        if (!"completed".equals(status)) text = text.isBlank() ? t("Pro 문의를 완료하지 못했습니다.") : text.substring(0, Math.min(1000, text.length()));
        if (!pending.turnId.equals(runningTurns.get(pending.localThread)) || pending.connection.isClosed()) {
            finishConsultation(pending, "failed", t("원래 Codex 작업이나 연결이 종료되어 Pro 결과를 전달하지 못했습니다."), null);
            return obj("ok", true, "stale", true);
        }
        finishConsultation(pending, status, text, args);
        return obj("ok", true);
    }
    /** Clear the live waiter before any callback so cancellation and late web replies cannot answer twice. */
    private void finishConsultation(PendingConsultation pending, String status, String text, JSONObject args) {
        if (pending == null || pendingConsultation != pending) return;
        pendingConsultation = null;
        if (pending.timeout != null) pending.timeout.cancel(false);
        JSONObject target = session(pending.localThread), message = target == null ? null : messageIn(target, pending.messageId);
        if (message == null) { status = "failed"; text = t("원래 Pro 문의 대화를 찾을 수 없습니다."); }
        else {
            try {
                message.put("status", status).put("text", text);
                if (args != null && "completed".equals(status)) {
                    String remoteMessage = boundedRemoteId(args.optString("remoteMessageId"));
                    String conversation = boundedRemoteId(args.optString("chatConversationId"));
                    String project = boundedProjectPath(args.optString("chatProjectPath"));
                    if (!remoteMessage.isBlank()) message.put("remoteMessageId", remoteMessage);
                    if (!conversation.isBlank()) target.put("proConsultConversationId", conversation);
                    if (!project.isBlank()) target.put("proConsultProjectPath", project);
                    String effectiveProject = target.optString("proConsultProjectPath");
                    if (effectiveProject.isBlank()) effectiveProject = target.optString("chatProjectPath");
                    String path = boundedConversationPath(args.optString("chatConversationPath"), conversation, effectiveProject);
                    if (!path.isBlank()) target.put("proConsultConversationPath", path);
                }
                persistSessions(sessions);
            } catch (Exception error) {
                status = "failed"; text = t("Pro 문의 결과를 저장하지 못했습니다. 자동으로 다시 보내지 않습니다.");
                try { message.put("status", status).put("text", text); } catch (Exception ignored) {}
                event("error", obj("threadId", pending.localThread, "message", text));
            }
        }
        try { pending.responder.respond(ToolCatalog.result("completed".equals(status), text)); }
        catch (IOException error) {
            status = "failed";
            if (message != null) try {
                message.put("status", "failed").put("deliveryStatus", "failed").put("error", t("Pro 결과를 원래 Codex 연결에 전달하지 못했습니다."));
                persistSessions(sessions);
            } catch (Exception ignored) {}
        }
        publish(); event("pro.consult.resolved", obj("operationId", pending.operationId, "threadId", pending.localThread, "status", status));
    }
    private JSONObject preparePro(JSONObject args) throws Exception {
        requireChatScope(args);
        if (!ProContextBuilder.MODEL_ID.equals(args.optString("model"))) throw new IOException(t("잘못된 GPT-6-Pro 모델 요청입니다."));
        if (busy) throw new IOException(t("현재 Codex 작업을 먼저 중지해 주세요."));
        if (hasProOperations()) throw new IOException(t("GPT-6-Pro 답변을 기다리는 중입니다."));
        JSONArray skills = args.optJSONArray("skills");
        if (skills != null && skills.length() > 0) throw new IOException(t("GPT-6-Pro 읽기 전용 모드에서는 스킬을 사용할 수 없습니다."));
        documents.requireWorkspaceAvailable();
        String text = args.optString("text", "");
        JSONObject contextBundle = new ProContextBuilder(documents, attachments, instructions).build(
            text, args.optJSONArray("attachments"), args.optJSONArray("mentions"), active, proGitDiff());
        String operationId = UUID.randomUUID().toString();
        String localId = active == null ? "local-" + UUID.randomUUID() : threadId;
        JSONArray replacement = new JSONArray(sessions.toString());
        JSONObject target = null;
        for (int i = 0; i < replacement.length(); i++) {
            JSONObject value = replacement.optJSONObject(i);
            if (value != null && localId.equals(value.optString("id"))) { target = value; break; }
        }
        if (target == null) {
            target = obj("id", localId, "sessionVersion", 2, "title", titleFor(text, args.optJSONArray("attachments")),
                "workspace", sessionWorkspaceName(), "workspaceKey", documents.key(),
                "messages", new JSONArray(), "imageHistoryVersion", 1, "phoneToolsVersion", 1, "fastMode", false);
            replacement.put(target);
        }
        JSONObject message = userMessage(text, args.optJSONArray("attachments"), null, args.optJSONArray("mentions"));
        message.put("backend", "chatgpt-web").put("source", "ChatGPT Pro")
            .put("requestedModel", ProContextBuilder.MODEL_ID).put("displayModel", ProContextBuilder.DISPLAY_MODEL)
            .put("operationId", operationId).put("contextHash", contextBundle.getString("contextHash")).put("status", "sending");
        target.getJSONArray("messages").put(message);
        target.put("lastBackend", "chatgpt-web").put("proOperation", obj("id", operationId,
            "messageId", message.getString("id"), "contextHash", contextBundle.getString("contextHash"),
            "startedAt", System.currentTimeMillis()));
        persistSessions(replacement);
        replaceSessions(replacement);
        threadId = localId; active = session(localId); syncCurrentTurn(); status = t("Pro 답변 중"); publish();
        JSONObject result = new JSONObject(contextBundle.toString());
        result.put("operationId", operationId).put("threadId", localId)
            .put("requestedModel", ProContextBuilder.MODEL_ID).put("displayModel", ProContextBuilder.DISPLAY_MODEL)
            .put("chatConversationId", active.optString("chatConversationId"))
            .put("chatConversationPath", active.optString("chatConversationPath"))
            .put("chatProjectPath", active.optString("chatProjectPath"));
        return result;
    }
    private JSONObject completePro(JSONObject args) throws Exception {
        String localId = args.getString("threadId"), operationId = args.getString("operationId");
        String reply = args.optString("reply").trim();
        if (reply.isEmpty() || reply.length() > 200_000) throw new IOException(t("ChatGPT Pro 답변 길이가 올바르지 않습니다."));
        JSONArray replacement = new JSONArray(sessions.toString());
        JSONObject target = sessionIn(replacement, localId);
        if (target == null) throw new IOException(t("대화를 찾을 수 없습니다."));
        if (hasCompletedPro(target, operationId)) return obj("ok", true, "duplicate", true);
        JSONObject operation = target.optJSONObject("proOperation");
        if (operation == null || !operationId.equals(operation.optString("id"))) throw new IOException(t("GPT-6-Pro 작업이 변경되었습니다."));
        JSONObject user = messageIn(target, operation.optString("messageId"));
        if (user == null || !operationId.equals(user.optString("operationId"))) throw new IOException(t("GPT-6-Pro 사용자 메시지를 찾을 수 없습니다."));
        user.put("status", "completed");
        JSONObject assistant = obj("id", UUID.randomUUID().toString(), "role", "assistant", "text", reply,
            "backend", "chatgpt-web", "source", "ChatGPT Pro", "requestedModel", ProContextBuilder.MODEL_ID,
            "displayModel", ProContextBuilder.DISPLAY_MODEL, "operationId", operationId,
            "contextHash", user.optString("contextHash"), "status", "completed");
        String remoteMessageId = boundedRemoteId(args.optString("remoteMessageId"));
        if (!remoteMessageId.isBlank()) assistant.put("remoteMessageId", remoteMessageId);
        String conversationId = boundedRemoteId(args.optString("chatConversationId"));
        if (!conversationId.isBlank()) target.put("chatConversationId", conversationId);
        String projectPath = boundedProjectPath(args.optString("chatProjectPath"));
        if (!projectPath.isBlank()) target.put("chatProjectPath", projectPath);
        String effectiveProject = projectPath.isBlank() ? target.optString("chatProjectPath") : projectPath;
        String conversationPath = boundedConversationPath(args.optString("chatConversationPath"), conversationId, effectiveProject);
        if (!conversationPath.isBlank()) target.put("chatConversationPath", conversationPath);
        target.getJSONArray("messages").put(assistant); target.remove("proOperation");
        persistSessions(replacement);
        replaceSessions(replacement);
        if (localId.equals(threadId)) { active = session(localId); status = ready ? t("연결됨") : t("시작할 준비가 됐습니다"); }
        publish();
        taskNotification("completed", t("답변 완료"), t("ChatGPT Pro가 답변을 마쳤습니다."), localId, "");
        return obj("ok", true, "threadId", localId);
    }
    private JSONObject failPro(JSONObject args) throws Exception {
        String localId = args.getString("threadId"), operationId = args.getString("operationId");
        String resultStatus = args.optString("status", "failed");
        if (!Set.of("not_sent", "uncertain", "failed").contains(resultStatus)) throw new IOException(t("잘못된 GPT-6-Pro 작업 상태입니다."));
        JSONArray replacement = new JSONArray(sessions.toString());
        JSONObject target = sessionIn(replacement, localId);
        if (target == null) throw new IOException(t("대화를 찾을 수 없습니다."));
        if (hasCompletedPro(target, operationId)) return obj("ok", true, "completed", true);
        JSONObject operation = target.optJSONObject("proOperation");
        if (operation == null || !operationId.equals(operation.optString("id"))) return obj("ok", true, "stale", true);
        JSONObject user = messageIn(target, operation.optString("messageId"));
        if (user != null) {
            user.put("status", resultStatus);
            String reason = args.optString("reason");
            if (!reason.isBlank()) user.put("error", reason.substring(0, Math.min(1000, reason.length())));
        }
        String conversationId = boundedRemoteId(args.optString("chatConversationId"));
        if (!conversationId.isBlank()) target.put("chatConversationId", conversationId);
        String projectPath = boundedProjectPath(args.optString("chatProjectPath"));
        if (!projectPath.isBlank()) target.put("chatProjectPath", projectPath);
        String effectiveProject = projectPath.isBlank() ? target.optString("chatProjectPath") : projectPath;
        String conversationPath = boundedConversationPath(args.optString("chatConversationPath"), conversationId, effectiveProject);
        if (!conversationPath.isBlank()) target.put("chatConversationPath", conversationPath);
        target.remove("proOperation");
        persistSessions(replacement); replaceSessions(replacement);
        if (localId.equals(threadId)) { active = session(localId); status = resultStatus.equals("uncertain") ? t("웹 전송 상태 확인 필요") : t("요청 실패"); }
        publish(); return obj("ok", true, "status", resultStatus);
    }
    private static JSONObject sessionIn(JSONArray values, String id) {
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return value;
        }
        return null;
    }
    private static JSONObject messageIn(JSONObject session, String id) {
        JSONArray messages = session.optJSONArray("messages"); if (messages == null) return null;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject value = messages.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return value;
        }
        return null;
    }
    private static boolean hasCompletedPro(JSONObject session, String operationId) {
        JSONArray messages = session.optJSONArray("messages"); if (messages == null) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject value = messages.optJSONObject(i);
            if (value != null && "assistant".equals(value.optString("role")) && operationId.equals(value.optString("operationId"))
                    && "completed".equals(value.optString("status"))) return true;
        }
        return false;
    }
    private static String boundedRemoteId(String value) {
        if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9_-]*")) return "";
        return value;
    }
    private static String boundedProjectPath(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("/$", "");
        return normalized.matches("/g/g-p-[A-Za-z0-9_-]+/project|/projects/[A-Za-z0-9_-]+") ? normalized : "";
    }
    private static String boundedConversationPath(String value, String conversationId, String projectPath) {
        if (value == null || conversationId == null || conversationId.isBlank()) return "";
        String normalized = value.replaceAll("/$", "");
        if (normalized.equals("/c/" + conversationId)) return normalized;
        String scoped = "";
        if (projectPath != null && projectPath.matches("/g/g-p-[A-Za-z0-9_-]+/project"))
            scoped = projectPath.substring(0, projectPath.length() - "/project".length()) + "/c/" + conversationId;
        else if (projectPath != null && projectPath.matches("/projects/[A-Za-z0-9_-]+")) scoped = projectPath + "/c/" + conversationId;
        return normalized.equals(scoped) ? normalized : "";
    }
    private void send(String text, String model, String effort, JSONArray attachmentIds, JSONArray skills, JSONArray mentions, Boolean fastMode, boolean consultPro) throws Exception {
        if (!liveRemote.isBlank()) throw new IOException(t("음성 화면에서 메시지를 보내거나 음성 대화를 종료해 주세요."));
        // A new message from the user ends the previous answering order.
        GroupRun previousRun = group; if (previousRun != null) cancelGroup(previousRun);
        if (roomCommand != null && roomCommand.isAlive()) throw new IOException(t("이전 명령이 종료 중입니다. 잠시 후 다시 보내 주세요."));
        if (ProContextBuilder.MODEL_ID.equals(model)) throw new IOException(t("GPT-6-Pro 웹 모델은 전용 전송 경로를 사용해야 합니다."));
        if (text.length() > 50000) throw new IOException(t("메시지는 최대 50,000자까지 입력할 수 있습니다."));
        documents.requireWorkspaceAvailable();
        JSONArray input = input(text, attachmentIds, skills, mentions);
        if (input.length() == 0) throw new IOException(t("메시지나 첨부 파일을 추가해 주세요."));
        start();
        if (account.length() == 0) throw new IOException(t("ChatGPT 계정으로 로그인해 주세요."));
        String actualModel = resolvedModel(model);
        // A supplied value applies to this new turn only. Omission deliberately
        // preserves legacy inheritance and does not alter a session preference.
        String serviceTierForTurn = fastMode == null ? null : (fastMode ? fastServiceTier(actualModel) : "default");
        JSONObject candidate = null;
        String candidateRemoteId = "", candidateLocalId = "";
        if (active == null) {
            JSONObject params = threadStartParams(actualModel);
            JSONObject thread = call("thread/start", params).getJSONObject("thread");
            candidateRemoteId = thread.getString("id");
            candidateLocalId = "local-" + UUID.randomUUID();
            candidate = obj("id", candidateLocalId, "sessionVersion", 2, "codexThreadId", candidateRemoteId,
                "title", titleFor(text, attachmentIds), "workspace", sessionWorkspaceName(), "workspaceKey", documents.key(), "model", actualModel,
                "messages", new JSONArray(), "imageHistoryVersion", 1, "phoneToolsVersion", 1, "consultToolsVersion", 1, "fastMode", false);
        } else {
            if (!active.optString("workspaceKey").equals(documents.key())) throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
            if (activeCodexThreadId().isBlank()) {
                JSONObject thread = call("thread/start", threadStartParams(actualModel)).getJSONObject("thread");
                candidateRemoteId = thread.getString("id");
            } else resumeRemote(!actualModel.equals(active.optString("model")), actualModel);
        }
        String targetLocal = candidate == null ? threadId : candidateLocalId;
        String targetRemote = candidateRemoteId.isBlank() ? activeCodexThreadId() : candidateRemoteId;
        JSONObject owner = candidate == null ? active : candidate;
        if (candidate != null && draftAgents.length() > 0) candidate.put("agents", new JSONArray(draftAgents.toString()));
        List<String> targets = AgentGroup.grouped(owner) ? AgentGroup.targets(text, owner) : List.of(AgentGroup.CODEX);
        if (!targets.contains(AgentGroup.CODEX)) {
            // Only invited agents were called; Codex reads this message on its next turn.
            if (candidate != null) { active = candidate; threadId = candidateLocalId; serverThreadId = candidateRemoteId; sessions.put(active); }
            else if (activeCodexThreadId().isBlank() && !candidateRemoteId.isBlank()) { active.put("codexThreadId", candidateRemoteId); serverThreadId = candidateRemoteId; }
            active.getJSONArray("messages").put(userMessage(text, attachmentIds, skills, mentions));
            draftAgents = new JSONArray();
            GroupRun run = new GroupRun(threadId); run.model = actualModel; run.effort = effort;
            run.context = new JSONArray(active.getJSONArray("messages").toString());
            run.queue.addAll(targets);
            group = run; persist(); advanceGroup(run); return;
        }
        if (AgentGroup.grouped(owner))
            input = prepend(input, AgentGroup.codexContext(owner, owner.getJSONArray("messages"), owner.getJSONArray("messages").length(), Set.of()));
        ConsultGrant consultation = consultPro ? new ConsultGrant("", candidate == null ? active.optString("workspaceKey") : candidate.optString("workspaceKey")) : null;
        if (consultation != null) {
            if (candidateRemoteId.isBlank()) requireConsultationTool();
            input = consultationInput(input, consultation, !candidateRemoteId.isBlank() || active.optInt("consultToolsVersion") >= 1);
        }
        busy = true; status = t("작업 중"); publish();
        try {
            JSONObject params = obj("threadId", targetRemote, "input", input, "cwd", projectDirectory().getAbsolutePath(), "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer());
            if (!actualModel.isEmpty()) params.put("model", actualModel);
            if (!effort.isEmpty()) params.put("effort", effort);
            if (serviceTierForTurn != null) params.put("serviceTierForTurn", serviceTierForTurn);
            JSONObject turn = call("turn/start", params).optJSONObject("turn");
            if (candidate != null) { active = candidate; threadId = candidateLocalId; serverThreadId = candidateRemoteId; sessions.put(active); }
            else {
                active.put("model", actualModel);
                if (activeCodexThreadId().isBlank()) { active.put("codexThreadId", targetRemote).put("consultToolsVersion", 1); serverThreadId = targetRemote; }
            }
            if (fastMode != null) active.put("fastMode", fastMode);
            JSONObject message = userMessage(text, attachmentIds, skills, mentions);
            if (consultation != null) message.put("consultPro", true);
            active.getJSONArray("messages").put(message);
            draftAgents = new JSONArray();
            if (AgentGroup.grouped(active)) {
                GroupRun run = new GroupRun(targetLocal); run.model = actualModel; run.effort = effort;
                run.current = AgentGroup.CODEX; run.codexRunning = true; run.codexFrom = active.getJSONArray("messages").length() - 1;
                run.context = new JSONArray(active.getJSONArray("messages").toString()); run.codexCursor = message.optString("id");
                for (String id : targets) if (!id.equals(AgentGroup.CODEX)) run.queue.add(id);
                group = run;
            }
            if (turn != null) {
                String startedTurn = turn.optString("id", "");
                if (!startedTurn.isBlank()) runningTurns.put(targetLocal, startedTurn);
                if (consultation != null) consultation.turnId = startedTurn;
            }
            if (consultation != null) consultGrants.put(targetLocal, consultation);
            else consultGrants.remove(targetLocal);
            syncCurrentTurn();
            persist(); publish();
            if (group != null && group.local.equals(targetLocal)) advanceGroup(group);
        } catch (Exception e) { syncCurrentTurn(); status = t("요청 실패"); publish(); throw e; }
    }
    private void resume(String id) throws Exception {
        if (!id.equals(threadId)) stopLive();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && session.optString("id").equals(id)) {
                String key = session.optString("workspaceKey");
                try { documents.selectProject(key); }
                catch (Exception ignored) { documents.selectProject(""); }
                threadId = id; serverThreadId = ""; active = session; syncCurrentTurn();
                publish(); return;
            }
        }
        throw new IOException(t("대화를 찾을 수 없습니다."));
    }
    private void renameSession(String id, String title) throws Exception {
        // Titles are Mobile Codex local aliases: they remain available without starting or signing in to Codex.
        String trimmed = title.trim();
        if (trimmed.isEmpty()) throw new IOException(t("대화 제목을 입력해 주세요."));
        JSONArray replacement = new JSONArray(sessions.toString());
        boolean found = false;
        for (int i = 0; i < replacement.length(); i++) {
            JSONObject session = replacement.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && session.optString("id").equals(id)) {
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
        ensureSessionIdle(id);
        boolean found = false;
        JSONArray marked = new JSONArray(sessions.toString());
        for (int i = 0; i < marked.length(); i++) {
            JSONObject session = marked.getJSONObject(i);
            if (!session.optBoolean("deletionPending") && session.optString("id").equals(id)) {
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
        JSONObject markedSession = session(id);
        String remoteId = codexThreadId(markedSession);
        if (!remoteId.isBlank() && (!ready || !deleteRemote(remoteId, false))) return true;

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
        ArrayList<String> completed = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optBoolean("deletionPending")) {
                String remoteId = codexThreadId(session);
                if (remoteId.isBlank() || deleteRemote(remoteId, true)) completed.add(session.optString("id"));
            }
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
            if (method.startsWith("thread/realtime/")) { liveNotification(method, p); return; }
            if (method.equals("account/login/completed")) {
                loginInProgress = false;
                if (p.optBoolean("success")) {
                    boolean adding = !addAccountRestoreKey.isBlank() && accountProfiles.hasPendingAddLogin();
                    boolean previousSuppression = suppressStatePublish;
                    if (adding) suppressStatePublish = true;
                    try {
                        if (adding) accountProfiles.markAddLoginCompleting();
                        readAccount();
                        readRateLimits();
                        if (adding) {
                            // Stop the isolated server before promoting its
                            // auth.json, so no late refresh write can race the
                            // promotion into the primary CODEX_HOME.
                            stopCodex();
                            accountProfiles.finishAddLogin(account);
                            processHome = codexHome.root();
                            start();
                        }
                        addAccountRestoreKey = ""; status = t("연결됨");
                        try { JSONArray data = call("model/list", obj("limit", 100)).optJSONArray("data"); if (data != null) models = data; } catch (Exception ignored) {}
                        if (active != null && active.optString("workspaceKey").equals(documents.key()) && documents.workspace().optBoolean("available")) resumeRemote(false);
                    } catch (Exception loginError) {
                        if (adding) {
                            try {
                                stopCodex();
                                accountProfiles.restorePreparedAddLogin(true);
                            } catch (Exception restoreError) {
                                event("error", obj("message", t("이전 계정을 복원하지 못했습니다: ") + unwrap(restoreError).getMessage()));
                            }
                            addAccountRestoreKey = "";
                            processHome = codexHome.root();
                            account = new JSONObject(); rateLimits = new JSONObject();
                            try { start(); } catch (Exception ignored) { }
                        }
                        event("error", obj("message", unwrap(loginError).getMessage()));
                        JSONObject failed = new JSONObject(p.toString()); failed.put("success", false);
                        failed.put("error", unwrap(loginError).getMessage());
                        event("login.completed", failed); publish(); return;
                    } finally {
                        suppressStatePublish = previousSuppression;
                        if (adding) publish();
                    }
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
                        PendingRequest request = entry.getValue();
                        if (request.method.contains("requestApproval")) {
                            try { JSONObject approvalSession = sessionByCodexThreadId(request.params.optString("threadId")); if (approvalSession != null) { approvalSession.put("approvalPending", false); persist(); } } catch (Exception ignored) {}
                        }
                        event("server.resolved", obj("key", entry.getKey())); return true;
                    }
                    return false;
                });
                return;
            }
            String remoteEventThreadId = p.optString("threadId");
            boolean implicitCurrentThread = remoteEventThreadId.isBlank();
            String eventThreadId = implicitCurrentThread ? threadId : localIdForCodexThread(remoteEventThreadId);
            JSONObject target = implicitCurrentThread ? active : (eventThreadId.isBlank() ? null : session(eventThreadId));
            boolean currentThread = implicitCurrentThread || eventThreadId.equals(threadId);
            if (method.equals("item/agentMessage/delta") && target != null) {
                String id = p.optString("itemId");
                JSONObject previous = active;
                try { active = target; JSONObject message = message(id); message.put("text", message.optString("text") + p.optString("delta")); }
                finally { active = previous; }
                if (currentThread) event("message.delta", obj("threadId", eventThreadId, "id", id, "delta", p.optString("delta")));
                else publish();
            } else if ((method.equals("item/completed") || method.equals("item/started")) && target != null) {
                JSONObject item = p.optJSONObject("item");
                boolean completed = method.equals("item/completed");
                boolean imageHandled = false, messageHandled = false;
                JSONObject previous = active;
                try {
                    active = target;
                    if (item != null) imageHandled = withWorkspace(target.optString("workspaceKey"), () -> recordImages(item, completed, p.optString("turnId", turnId)));
                    if (!imageHandled && completed && item != null && "agentMessage".equals(item.optString("type"))) {
                        message(item.getString("id")).put("text", item.optString("text"));
                        messageHandled = true;
                    }
                } finally { active = previous; }
                if (imageHandled || messageHandled) { persist(); publish(); }
                else if (currentThread) event("agent.event", obj("threadId", eventThreadId, "method", method, "params", p));
            } else if (method.equals("turn/diff/updated") && target != null) {
                target.put("turnDiff", p.optString("diff")); persist(); publish();
            } else if (method.equals("turn/started")) {
                JSONObject turn = p.optJSONObject("turn"); String started = turn == null ? "" : turn.optString("id");
                if (!eventThreadId.isBlank() && !started.isBlank()) runningTurns.put(eventThreadId, started);
                ConsultGrant grant = consultGrants.get(eventThreadId);
                if (grant != null && grant.turnId.isBlank()) grant.turnId = started;
                syncCurrentTurn(); publish();
            } else if (method.equals("turn/completed")) {
                JSONObject completedTurn = p.optJSONObject("turn");
                String completedId = completedTurn == null ? "" : completedTurn.optString("id");
                String runningId = runningTurns.get(eventThreadId);
                if (!completedId.isBlank() && runningId != null && !completedId.equals(runningId)) return;
                if (pendingConsultation != null && pendingConsultation.localThread.equals(eventThreadId))
                    finishConsultation(pendingConsultation, "cancelled", t("원래 Codex 작업이 종료되어 Pro 문의를 취소했습니다."), null);
                consultGrants.remove(eventThreadId);
                if (!eventThreadId.isBlank()) runningTurns.remove(eventThreadId);
                if (target != null) target.put("approvalPending", false);
                syncCurrentTurn();
                JSONObject turn = p.optJSONObject("turn");
                String completionStatus = turn == null ? "" : turn.optString("status");
                boolean interrupted = "interrupted".equals(completionStatus);
                JSONObject error = turn == null ? null : turn.optJSONObject("error");
                boolean failed = !interrupted && ("failed".equals(completionStatus) || error != null);
                if (currentThread && (interrupted || failed)) status = interrupted ? t("작업 중지됨") : t("작업 실패");
                if (failed) event("error", obj("threadId", eventThreadId, "message", error == null ? t("작업 실패") : error.optString("message", t("작업 실패"))));
                taskNotification(interrupted ? "interrupted" : failed ? "failed" : "completed",
                    interrupted ? t("작업 중지됨") : failed ? t("작업 실패") : t("답변 완료"),
                    interrupted ? t("Codex 작업이 중지되었습니다.") : failed ? t("Codex 작업이 실패했습니다.") : t("Codex가 작업을 마쳤습니다."), eventThreadId, "");
                if (currentThread && pendingApproval != null && pendingApproval != agentApproval) pendingApproval.decision.complete(false);
                if (target != null) {
                    JSONObject previous = active;
                    try {
                        active = target;
                        JSONArray messages = active.getJSONArray("messages");
                        for (int i = 0; i < messages.length(); i++) {
                            JSONObject message = messages.getJSONObject(i);
                            if (message.optString("imageStatus").equals("generating")) message.put("imageStatus", "failed")
                                .put("imageError", interrupted ? t("작업이 중지되어 이미지 생성이 완료되지 않았습니다.") : t("이미지 생성이 완료되지 않았습니다. 다시 시도해 주세요."));
                        }
                    } finally { active = previous; }
                }
                persist(); publish();
                GroupRun run = group;
                if (run != null && target != null && run.local.equals(eventThreadId) && run.codexRunning) codexAnswered(run, target, interrupted || failed);
                drainAgentConsents();
            } else if (method.equals("error")) {
                JSONObject error = p.optJSONObject("error");
                event("error", obj("threadId", eventThreadId, "message", error == null ? t("Codex 요청 오류") : error.optString("message", t("Codex 요청 오류"))));
            } else {
                event("agent.event", obj("method", method, "params", p));
            }
        } catch (Exception e) {
            String remoteFailedThread = p.optString("threadId");
            String failedThread = remoteFailedThread.isBlank() ? threadId : localIdForCodexThread(remoteFailedThread);
            event("error", obj("threadId", failedThread, "message", unwrap(e).getMessage()));
        }
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
    private void onRequest(Object id, String method, JSONObject p) { onRequest(rpc, id, method, p); }
    private void onRequest(RpcClient connection, Object id, String method, JSONObject p) {
        try {
            if (!method.equals("item/tool/call")) {
                String key = UUID.randomUUID().toString();
                requests.put(key, new PendingRequest(connection, id, method, p));
                String remoteThread = p.optString("threadId");
                String localThread = remoteThread.isBlank() ? threadId : localIdForCodexThread(remoteThread);
                if (method.contains("requestApproval")) {
                    String approvalThread = localThread;
                    JSONObject approvalSession = session(approvalThread);
                    if (approvalSession != null) { approvalSession.put("approvalPending", true); persist(); publish(); }
                    taskNotification("approval", t("승인 필요"), p.optString("reason", t("Codex 작업의 승인이 필요합니다.")), approvalThread, key);
                }
                JSONObject uiParams = new JSONObject(p.toString());
                if (!localThread.isBlank()) uiParams.put("threadId", localThread);
                event("server.request", obj("key", key, "method", method, "params", uiParams));
                return;
            }
            String requestThreadId = localIdForCodexThread(p.optString("threadId"));
            JSONObject requestSession = session(requestThreadId);
            if (requestSession == null) throw new IOException(t("대화 작업을 찾을 수 없습니다."));
            String requestWorkspaceKey = requestSession.optString("workspaceKey");
            String tool = p.getString("tool"); JSONObject args = p.getJSONObject("arguments");
            event("tool", obj("threadId", requestThreadId, "name", tool, "path", args.optString("path", args.optString("query", ""))));
            if (tool.equals("mobile_consult_pro")) {
                if (connection != rpc) throw new IOException(t("Codex 연결이 변경되었습니다."));
                if (args.length() != 1 || !(args.opt("prompt") instanceof String)) throw new IOException(t("Pro 문의 정보가 올바르지 않습니다."));
                beginConsultation(connection, id, requestThreadId, p.optString("threadId"), p.optString("turnId"), args.getString("prompt"),
                    result -> connection.respond(id, result));
                return;
            }
            String previousToolThread = toolRequestThread;
            toolRequestThread = requestThreadId;
            try { withWorkspace(requestWorkspaceKey, () -> {
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
                return null;
            }); } finally { toolRequestThread = previousToolThread; }
        } catch (Exception e) {
            if (connection != null) try { connection.respond(id, ToolCatalog.result(false, unwrap(e).getMessage())); } catch (IOException ignored) {}
        }
    }
    private void mutate(String operation, JSONObject args, boolean interactive, Reply reply) throws Exception {
        if (!ToolCatalog.WRITE.contains(operation)) throw new IOException(t("지원하지 않는 파일 작업입니다."));
        if (permissionMode.equals("read-only")) throw new IOException(t("현재 읽기 전용 모드입니다. 권한 설정을 변경해 주세요."));
        if (pendingApproval != null && !pendingApproval.decision.isDone()) throw new IOException(t("다른 변경 사항을 확인 중입니다. 한 번에 하나씩 요청해 주세요."));
        DocumentStore.Mutation mutation = documents.prepare(operation, args);
        if (!interactive) {
            JSONObject result = documents.commit(mutation); filesChanged(result); reply.complete(result, null); return;
        }
        Approval approval = new Approval(mutation.title, mutation.preview);
        String approvalWorkspaceKey = documents.key();
        pendingApproval = approval;
        String approvalThread = threadId;
        JSONObject approvalSession = session(approvalThread);
        if (approvalSession != null) { approvalSession.put("approvalPending", true); persist(); publish(); }
        ScheduledFuture<?> timeout = approvalTimer.schedule(() -> approval.decision.complete(false), 10, TimeUnit.MINUTES);
        approval.decision.whenCompleteAsync((approved, error) -> {
            timeout.cancel(false);
            if (pendingApproval == approval) pendingApproval = null;
            try {
                JSONObject finishedApprovalSession = session(approvalThread);
                if (finishedApprovalSession != null) { finishedApprovalSession.put("approvalPending", false); persist(); publish(); }
                if (error != null || !Boolean.TRUE.equals(approved)) throw new IOException(t("사용자가 변경을 취소했습니다."));
                if (!approvalWorkspaceKey.equals(documents.key())) throw new IOException(t("프로젝트가 바뀌었습니다. 다시 열어 주세요."));
                JSONObject result = documents.commit(mutation);
                filesChanged(result); reply.complete(result, null);
            } catch (Throwable e) { reply.complete(null, unwrap(e)); }
            finally { drainAgentConsents(); }
        }, io);
        Ui current = ui;
        taskNotification("approval", t("승인 필요"), approval.title, threadId, approval.id);
        if (current != null) current.approval(approval);
        for (Ui observer : observers) if (observer != current) observer.approval(approval);
        if (current == null && observers.isEmpty()) approval.decision.complete(false);
    }
    public void stop() {
        if (agentBridge != null) agentBridge.stopAll();
        PhoneUseService.stopControl();
        linux.cancel();
        // Closing approval first allows a pending action to resolve without changes.
        Approval approval = pendingApproval; if (approval != null) approval.decision.complete(false);
        io.execute(() -> { autoRestorePaused = true; stopNow(); });
    }
    /** Stops Codex and the invited-agent runner, including CLI installs and sign-ins. */
    private void stopNow() {
        GroupRun run = group; if (run != null) cancelGroup(run);
        if (agentBridge != null) { agentBridge.stopAll(); agentBridge.close(); }
        stopCodex();
    }
    /** Restarts only Codex (sign-in, account switch, config). Claude/Antigravity setup keeps running. */
    private void stopCodex() {
        GroupRun run = group; if (run != null) cancelGroup(run);
        if (roomCommand != null) roomCommand.destroy();
        if (pendingConsultation != null) finishConsultation(pendingConsultation, "cancelled", t("Codex 실행이 중지되어 Pro 문의를 취소했습니다."), null);
        consultGrants.clear(); consultMcpReady = false; consultMcpThread = ""; consultMcpError = ""; consultMcpThreads.clear();
        loginInProgress = false;
        PhoneUseService.stopControl();
        if (pendingApproval != null) pendingApproval.decision.complete(false);
        Process old = process; process = null;
        if (old != null) {
            old.destroyForcibly();
            // Account switching replaces auth.json immediately after this
            // method returns. Wait briefly for the old app-server to exit so a
            // pending refresh cannot write its rotated auth after the target
            // profile has been staged.
            try { if (!old.waitFor(2, TimeUnit.SECONDS) && old.isAlive()) old.destroyForcibly(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (rpc != null) rpc.close(); rpc = null;
        if (consultMcp != null) consultMcp.close(); consultMcp = null;
        requests.forEach((key, value) -> event("server.resolved", obj("key", key)));
        requests.clear();
        stopTerminalProcess();
        clearRunningState();
        ready = false; serverThreadId = ""; status = t("연결 종료");
        persist(); publish();
        stopServiceIfIdle();
    }
    /** Whether a config still declares the retired Pro MCP server (table or dotted keys). */
    static boolean retiredProBridgeConfigured(File config) {
        try { return config.isFile() && java.util.regex.Pattern.compile("(?m)^\\s*\\[?\\s*mcp_servers\\.\"?mobile_codex_pro\\b").matcher(dev.mobilecodex.app.core.Utf8Files.read(config.toPath())).find(); }
        catch (Exception e) { return false; }
    }
    private File projectDirectory() { File dir = documents.directDirectory(); return dir == null ? workDir : dir; }
    /**
     * DocumentStore intentionally has one selected tree. Background turns must
     * nevertheless use their own project without changing the visible project.
     * Engine work is serialized on io, so a scoped switch is safe as long as it
     * is restored in finally before any state is published.
     */
    private <T> T withWorkspace(String workspaceKey, Callable<T> work) throws Exception {
        String previous = documents.key();
        boolean switched = !Objects.equals(previous, workspaceKey);
        if (switched) documents.selectProject(workspaceKey == null ? "" : workspaceKey);
        try { return work.call(); }
        finally { if (switched) documents.selectProject(previous); }
    }
    private void filesChanged(JSONObject result) {
        try {
            JSONObject payload = new JSONObject(result.toString());
            payload.put("threadId", toolRequestThread.isBlank() ? threadId : toolRequestThread);
            event("files.changed", payload);
        } catch (Exception ignored) { event("files.changed", result); }
    }
    /** Every room tool is authorized again here, below the CLI/MCP boundary. */
    private void roomHost(String method, JSONObject p, Reply reply) {
        io.execute(() -> {
            try {
                switch (method) {
                    case "host.launch" -> {
                        String provider=p.getString("provider"),action=p.getString("action");
                        if(!Set.of("claude","agy").contains(provider)||!Set.of("probe","install","login","run").contains(action))throw new IOException("Invalid provider operation");
                        File directory=new File(p.getString("directory")).getCanonicalFile();
                        File allowed=new File(context.getFilesDir(),"rooms/work").getCanonicalFile();
                        if(!directory.toPath().startsWith(allowed.toPath()))throw new IOException("Invalid provider directory");
                        // Rootfs snapshots can retain sync databases whose timestamps make -Sy
                        // skip a download. Force fresh metadata before the full upgrade; old
                        // package versions may already have disappeared from rolling mirrors.
                        // stdout belongs to the JSONL protocol; surface pacman progress on stderr.
                        String bootstrap=action.equals("install")?"pacman -Syyu --noconfirm --needed python curl ca-certificates >&2 && ":"";
                        ProcessBuilder builder=linux.command(bootstrap+"exec /usr/bin/python3 /workspace/provider-runtime.py "+provider+" "+action,directory,codexHome.root(),runtimeAliases());
                        reply.complete(obj("argv",new JSONArray(builder.command())),null);
                    }
                    case "host.tools" -> {
                        JSONObject lease=requireRoomLease(p);reply.complete(obj("tools",roomTools(lease.optString("mode"),true)),null);
                    }
                    case "host.tool" -> {
                        JSONObject lease=requireRoomLease(p);
                        String tool=p.getString("name");JSONObject arguments=p.optJSONObject("arguments");
                        if(arguments==null)arguments=obj();
                        roomTool(lease,tool,arguments,reply);
                    }
                    case "host.approve" -> { agentApprove(p, reply); return; }
                    case "host.disconnected" -> {
                        GroupRun run=group;
                        if(run!=null)for(AgentTurn turn:new ArrayList<>(run.active.values()))agentRunEvent(obj("runId",turn.runId,"kind","error","message",t("에이전트 연결이 종료되었습니다.")));
                        if(roomCommand!=null)roomCommand.destroy();
                        roomLeases.clear();reply.complete(obj(),null);
                    }
                    default -> throw new IOException("Unsupported room host operation");
                }
            }catch(Exception e){reply.complete(null,unwrap(e));}
        });
    }
    private JSONObject requireRoomLease(JSONObject p) throws IOException {
        JSONObject lease=roomLeases.get(p.optString("runId"));if(lease==null)throw new IOException(t("발언이 종료되었습니다."));
        if(!p.optString("roomId").equals(lease.optString("roomId")))throw new IOException("Room changed");
        if(p.has("mode")&&!p.optString("mode").equals(lease.optString("mode")))throw new IOException("Room permission changed");
        if(p.has("projectKey")&&!p.optString("projectKey").equals(lease.optString("projectKey")))throw new IOException("Room project changed");
        return lease;
    }
    private JSONArray roomTools(String mode,boolean mcp) throws Exception {
        JSONArray out=new JSONArray();if(mode.equals("chat"))return out;
        JSONArray all=ToolCatalog.all();
        all.put(obj("type","function","name","mobile_exec","description","Run one shell command in the selected project. Requires write mode, the participant approval policy and the project write slot.",
            "inputSchema",obj("type","object","properties",obj("command",obj("type","string","maxLength",8000)),"required",array("command"),"additionalProperties",false)));
        for(int i=0;i<all.length();i++){
            JSONObject tool=all.getJSONObject(i);String name=tool.optString("name");
            if(name.equals("mobile_consult_pro"))continue;
            if(mode.equals("read")&&!ToolCatalog.READ.contains(name)&&!Set.of("mobile_phone_status","mobile_phone_apps","mobile_phone_screen").contains(name))continue;
            out.put(mcp?obj("name",name,"description",tool.optString("description"),"inputSchema",tool.getJSONObject("inputSchema")):tool);
        }
        return out;
    }
    private void roomTool(JSONObject lease,String tool,JSONObject args,Reply reply) throws Exception {
        String mode=lease.optString("mode"),key=lease.optString("projectKey"),roomId=lease.optString("roomId");
        boolean read=ToolCatalog.READ.contains(tool)||Set.of("mobile_phone_status","mobile_phone_apps","mobile_phone_screen").contains(tool);
        if(mode.equals("chat")||(!read&&!mode.equals("write")))throw new IOException(t("이 에이전트에게 허용되지 않은 도구입니다."));
        if(!read&&permissionMode.equals("read-only"))throw new IOException(t("앱의 파일 권한이 읽기 전용입니다."));
        if(!read&&roomCommand!=null&&roomCommand.isAlive())throw new IOException(t("승인한 명령이 실행 중입니다. 명령이 끝난 뒤 다음 작업을 요청해 주세요."));
        if(!read){
            JSONObject grant=lease.optJSONObject("grant");lease.remove("grant");
            if(group==null||!lease.optString("runId").equals(group.writer)||projectHasCodexTurn(key)
                    ||grant==null||!tool.equals(grant.optString("name"))
                    ||!args.toString().equals(new JSONObject(grant.optString("detail","{}")).toString()))
                throw new IOException(t("이 작업에 대한 승인이 없거나 작업 차례가 바뀌었습니다."));
        }
        if(tool.equals("mobile_exec")){
            String command=args.getString("command");if(command.isBlank()||command.length()>8000)throw new IOException("Invalid command");
            withWorkspace(key,()->{
                File cwd=documents.directDirectory();if(cwd==null)throw new IOException(t("명령 실행에는 기기에서 직접 접근 가능한 프로젝트 폴더가 필요합니다."));
                File supervisor=new File(context.getFilesDir(),"rooms/runtime/command-supervisor.py");
                ProcessBuilder builder=new ProcessBuilder("python3",supervisor.getAbsolutePath()).directory(cwd).redirectErrorStream(true);devTools.configure(builder,codexHome.root(),runtimeAliases());
                builder.command().set(0,builder.environment().get("MC_PYTHON"));
                linux.configureEnvironment(builder.environment());Process child=builder.start();roomCommand=child;roomCommandRunId=lease.optString("runId");
                child.getOutputStream().write((obj("command",command).toString()+"\n").getBytes(StandardCharsets.UTF_8));child.getOutputStream().close();
                Thread worker=new Thread(()->{
                    try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=child.getInputStream().read(b))!=-1){if(out.size()+n>1024*1024){child.destroy();throw new IOException("Command output exceeded 1 MiB");}out.write(b,0,n);}int code=child.waitFor();reply.complete(obj("exitCode",code,"output",new String(out.toByteArray(),StandardCharsets.UTF_8)),null);}
                    catch(Exception e){child.destroy();try{child.waitFor(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}reply.complete(null,e);}finally{if(roomCommand==child&&!child.isAlive()){roomCommand=null;roomCommandRunId="";}if(!io.isShutdown())io.execute(()->{drainAgentConsents();publish();});}
                },"room-command");worker.setDaemon(true);worker.start();
                approvalTimer.schedule(()->{if(child.isAlive())child.destroy();},60,TimeUnit.SECONDS);
                return null;
            });return;
        }
        JSONObject result=withWorkspace(key,()->{
            documents.requireWorkspaceAvailable();
            if(ToolCatalog.WRITE.contains(tool))return documents.commit(documents.prepare(tool,args));
            if(PhoneToolCatalog.NAMES.contains(tool))return PhoneUseService.execute(context,tool,args);
            return switch(tool){case "mobile_read"->documents.read(args.getString("path"));case "mobile_list"->documents.list(args.optString("path"));case "mobile_search"->documents.search(args.getString("query"));default->throw new IOException("Unsupported mobile tool");};
        });
        if(!read)event("files.changed",obj("roomId",roomId));reply.complete(result,null);
    }
    // ---- Invited agents -------------------------------------------------------------------
    // The chat's message list is the one shared history. Codex answers through its normal
    // turn; Claude/Antigravity stream concurrently through AgentBridge.
    private AgentBridge agentBridge() throws Exception {
        if (agentBridge == null) synchronized (this) {
            if (agentBridge == null) agentBridge = new AgentBridge(context, devTools, codexHome.root(), runtimeAliases(), this::roomHost, this::agentBridgeEvent);
        }
        return agentBridge;
    }
    private void agentBridgeEvent(String name, JSONObject data) {
        if (name.equals("agents.event")) { io.execute(() -> agentRunEvent(data)); return; }
        if (name.equals("agents.providers")) io.execute(() -> { mergeAgentProviders(data.optJSONArray("providers")); publish(); });
        event(name, data);
    }
    private void mergeAgentProviders(JSONArray providers) {
        if (providers == null) return;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject provider = providers.optJSONObject(i); if (provider == null) continue;
            int at = -1;
            for (int j = 0; j < agentProviders.length(); j++) if (agentProviders.optJSONObject(j).optString("id").equals(provider.optString("id"))) at = j;
            try { if (at < 0) agentProviders.put(provider); else agentProviders.put(at, provider); } catch (Exception ignored) {}
        }
    }
    private boolean groupRunning(String local) { GroupRun run = group; return run != null && !local.isBlank() && run.local.equals(local); }
    private JSONArray agentsView() {
        JSONArray source = active == null ? draftAgents : active.optJSONArray("agents"), out = new JSONArray();
        if (source != null) for (int i = 0; i < source.length(); i++) {
            JSONObject agent = source.optJSONObject(i); if (agent == null) continue;
            out.put(obj("id", agent.optString("id"), "provider", agent.optString("provider"), "name", agent.optString("name"),
                "model", agent.optString("model"), "effort", agent.optString("effort"), "mode", agent.optString("mode", "chat"),
                "approvalMode", agent.optString("approvalMode", "ask"), "character", agent.optString("character"), "enabled", agent.optBoolean("enabled", true)));
        }
        return out;
    }
    private Object agentRunView() {
        GroupRun run = group;
        if (run == null || threadId.isBlank() || !run.local.equals(threadId)) return JSONObject.NULL;
        JSONArray queue = new JSONArray(); for (String next : run.queue) queue.put(next);
        JSONArray active = new JSONArray(); JSONObject states = obj();
        if (run.codexRunning) { active.put(AgentGroup.CODEX); try { states.put(AgentGroup.CODEX, "responding"); } catch (Exception ignored) {} }
        for (AgentTurn turn : run.active.values()) { active.put(turn.id); try { states.put(turn.id, turn.status); } catch (Exception ignored) {} }
        return obj("current", run.current, "active", active, "states", states, "queue", queue);
    }
    /** Invites, reconfigures or removes agents of the open chat (or of the next new chat). */
    private void setAgents(JSONArray changes) throws Exception {
        if (!liveRemote.isBlank()) throw new IOException(t("음성 대화를 먼저 종료해 주세요."));
        if (changes == null || changes.length() == 0) throw new IOException(t("초대할 에이전트를 선택해 주세요."));
        if (groupRunning(threadId)) throw new IOException(t("에이전트가 답하는 중에는 참가자를 바꿀 수 없습니다. 먼저 중지해 주세요."));
        JSONObject owner = active;
        boolean firstInvite = owner != null && !AgentGroup.grouped(owner);
        JSONArray list = owner == null ? draftAgents : owner.optJSONArray("agents");
        if (list == null) { list = new JSONArray(); owner.put("agents", list); }
        JSONArray next = new JSONArray(list.toString());
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.getJSONObject(i);
            String id = change.optString("id"), mode = change.optString("mode", "");
            if (AgentGroup.agent(id) == null) throw new IOException(t("지원하지 않는 에이전트입니다."));
            if (!mode.isEmpty() && !AgentGroup.MODES.contains(mode)) throw new IOException(t("잘못된 권한입니다."));
            String approval = change.optString("approvalMode", "ask");
            if (!Set.of("ask", "allow-all", "auto-review").contains(approval) || ("auto-review".equals(approval) && !id.equals("claude")))
                throw new IOException(t("이 에이전트가 지원하지 않는 승인 방식입니다."));
            JSONObject agent = null;
            for (int j = 0; j < next.length(); j++) if (next.getJSONObject(j).optString("id").equals(id)) agent = next.getJSONObject(j);
            if (agent == null) {
                if (change.has("enabled") && !change.optBoolean("enabled")) continue;
                JSONObject created = AgentGroup.newAgent(id, change.optString("model"), change.optString("effort"), mode.isEmpty() ? "chat" : mode);
                created.put("approvalMode", approval);
                if (change.has("character") && change.optString("character").trim().length() <= 120) created.put("character", change.optString("character").trim());
                next.put(created);
                continue;
            }
            if (change.has("model")) agent.put("model", change.optString("model"));
            if (change.has("effort")) agent.put("effort", change.optString("effort"));
            if (!mode.isEmpty()) agent.put("mode", mode);
            if (change.has("approvalMode")) agent.put("approvalMode", approval);
            // A character pack id chosen in the participants panel; empty keeps the letter avatar.
            if (change.has("character")) agent.put("character", change.optString("character").trim().length() > 120 ? "" : change.optString("character").trim());
            // A removed agent keeps its own session and cursor, so a re-invite continues from there.
            if (change.has("enabled")) agent.put("enabled", change.optBoolean("enabled"));
        }
        if (owner == null) { draftAgents = next; publish(); return; }
        owner.put("agents", next);
        // Codex already answered everything before the first invitation.
        JSONArray messages = owner.optJSONArray("messages");
        if (firstInvite && owner.optString("codexCursorId").isBlank() && messages != null && messages.length() > 0)
            owner.put("codexCursorId", messages.getJSONObject(messages.length() - 1).optString("id"));
        persist(); publish();
    }
    private static JSONArray prepend(JSONArray input, String text) throws Exception {
        JSONArray out = new JSONArray().put(obj("type", "text", "text", text, "text_elements", new JSONArray()));
        for (int i = 0; i < input.length(); i++) out.put(input.get(i));
        return out;
    }
    private JSONObject findMessage(JSONObject session, String id) {
        JSONArray messages = session == null ? null : session.optJSONArray("messages");
        int at = messages == null ? -1 : AgentGroup.indexOf(messages, id);
        return at < 0 ? null : messages.optJSONObject(at);
    }
    private void queueFollowUps(GroupRun run, JSONObject session, String text, String self) {
        // Coalesce only pending calls; a new completed reply can call the same peer again.
        run.queue.addAll(AgentGroup.followUps(text, session, self));
    }
    private void updateGroupCurrent(GroupRun run) {
        run.current = run.codexRunning ? AgentGroup.CODEX : run.active.values().stream().map(turn -> turn.id).findFirst().orElse("");
        syncCurrentTurn();
    }
    private void codexAnswered(GroupRun run, JSONObject session, boolean stopped) {
        if (group != run) return;
        run.codexRunning = false;
        try {
            if (!stopped) {
                JSONArray messages = session.getJSONArray("messages");
                StringBuilder reply = new StringBuilder();
                for (int i = Math.max(0, run.codexFrom + 1); i < messages.length(); i++) {
                    JSONObject m = messages.getJSONObject(i);
                    if ("assistant".equals(m.optString("role")) && m.optString("agent").isBlank()) reply.append(m.optString("text")).append('\n');
                }
                // Mark the input snapshot consumed, not concurrently arriving peer answers.
                session.put("codexCursorId", run.codexCursor);
                queueFollowUps(run, session, reply.toString(), AgentGroup.CODEX);
            }
        } catch (Exception ignored) {}
        updateGroupCurrent(run); persist(); drainAgentConsents(); advanceGroup(run);
    }
    private void advanceGroup(GroupRun run) {
        if (group != run || run.cancelled) return;
        JSONObject owner = session(run.local);
        if (owner == null) { cancelGroup(run); return; }
        // Initial recipients share one snapshot. Chained mentions wait for the batch,
        // so they receive all the preceding answers rather than a partial stream.
        if (!run.initialDispatch) {
            if (run.codexRunning || !run.active.isEmpty()) { updateGroupCurrent(run); publish(); return; }
            try { run.context = new JSONArray(owner.getJSONArray("messages").toString()); }
            catch (Exception e) { cancelGroup(run); return; }
        }
        run.initialDispatch = false;
        List<String> batch = new ArrayList<>(run.queue);
        run.queue.clear();
        for (String next : batch) {
            if (next.equals(AgentGroup.CODEX)) {
                startCodexForGroup(run, owner);
            } else {
                JSONObject member = AgentGroup.member(owner, next);
                if (member != null) startAgent(run, owner, member);
            }
        }
        updateGroupCurrent(run);
        if (!run.codexRunning && run.active.isEmpty()) {
            if (run.queue.isEmpty()) finishGroup(run);
            else advanceGroup(run);
        } else { persist(); publish(); }
    }
    /** Codex can be called by a completed batch, after its project writers finish. */
    private void startCodexForGroup(GroupRun run, JSONObject session) {
        try {
            if (active != session || !run.local.equals(threadId)) return;
            if (roomCommand != null && roomCommand.isAlive()) throw new IOException(t("이전 명령이 종료 중입니다. 잠시 후 다시 보내 주세요."));
            start();
            if (account.length() == 0) throw new IOException(t("ChatGPT 계정으로 로그인해 주세요."));
            if (!active.optString("workspaceKey").equals(documents.key())) throw new IOException(t("이 대화의 원래 작업 폴더를 다시 연결해 주세요."));
            String model = run.model.isBlank() ? active.optString("model") : run.model;
            if (activeCodexThreadId().isBlank()) {
                String remote = call("thread/start", threadStartParams(model)).getJSONObject("thread").getString("id");
                active.put("codexThreadId", remote); serverThreadId = remote;
            } else resumeRemote(false, model);
            JSONArray messages = active.getJSONArray("messages");
            String context = AgentGroup.codexContext(active, run.context, run.context.length(), Set.of()) + "\n\n다른 참가자가 Codex를 호출했습니다. 위 대화에 이어서 답하세요.";
            JSONObject params = obj("threadId", activeCodexThreadId(), "input", array(obj("type", "text", "text", context, "text_elements", new JSONArray())),
                "cwd", projectDirectory().getAbsolutePath(), "approvalPolicy", approvalPolicy(), "approvalsReviewer", approvalsReviewer());
            if (!model.isBlank()) params.put("model", model);
            if (!run.effort.isBlank()) params.put("effort", run.effort);
            run.codexFrom = messages.length() - 1; run.codexCursor = lastMessageId(run.context);
            JSONObject turn = call("turn/start", params).optJSONObject("turn");
            String started = turn == null ? "" : turn.optString("id");
            if (!started.isBlank()) runningTurns.put(threadId, started);
            run.codexRunning = true;
        } catch (Exception e) {
            run.codexRunning = false;
            event("error", obj("threadId", run.local, "message", t("Codex가 이어서 답하지 못했습니다: ") + unwrap(e).getMessage()));
        }
    }
    private static String lastMessageId(JSONArray messages) {
        JSONObject last = messages == null || messages.length() == 0 ? null : messages.optJSONObject(messages.length() - 1);
        return last == null ? "" : last.optString("id");
    }
    private void agentRequest(String action, JSONObject args, Reply reply) throws Exception {
        if (testAgentTransport != null) testAgentTransport.handle(action, args, reply);
        else agentBridge().handle(action, args, reply);
    }
    private void stopAgent(String runId) {
        if (testAgentTransport != null) testAgentTransport.handle("agents.stop", obj("runId", runId), (r, e) -> {});
        else if (agentBridge != null) agentBridge.stop(runId);
    }
    private void startAgent(GroupRun run, JSONObject owner, JSONObject member) {
        String id = member.optString("id"), mode = member.optString("mode", "chat"), key = owner.optString("workspaceKey");
        JSONObject message = obj("id", UUID.randomUUID().toString(), "role", "assistant", "agent", id, "agentName", AgentGroup.name(id),
            "text", "", "status", "streaming", "createdAt", System.currentTimeMillis());
        AgentTurn turn = new AgentTurn(id, message, lastMessageId(run.context));
        run.active.put(turn.runId, turn);
        try {
            if (!mode.equals("chat")) {
                if (key.isBlank()) throw new IOException(t("작업 폴더가 없는 대화입니다. 참가자 설정에서 권한을 '대화만'으로 바꿔 주세요."));
                if (terminal.busy()) throw new IOException(t("실행 중인 터미널 명령을 먼저 중지해 주세요."));
                withWorkspace(key, () -> { documents.requireWorkspaceAvailable(); return null; });
            }
            String projectKey = mode.equals("chat") ? "" : key;
            roomLeases.put(turn.runId, obj("runId", turn.runId, "roomId", run.local, "projectKey", projectKey, "mode", mode,
                "approvalMode", member.optString("approvalMode", "ask")));
            context.startForegroundService(new Intent(context, EngineService.class));
            agentRequest("agents.run", obj("runId", turn.runId, "sessionId", run.local, "memberId", id, "provider", member.optString("provider"),
                "model", member.optString("model"), "effort", member.optString("effort"), "mode", mode, "projectKey", projectKey,
                "approvalMode", member.optString("approvalMode", "ask"), "nativeSession", member.optString("nativeSession"),
                "prompt", AgentGroup.agentPrompt(owner, member, run.context, chatIconsEnabled)), (result, error) -> {
                    if (error != null) io.execute(() -> agentRunEvent(obj("runId", turn.runId, "kind", "error", "message", unwrap(error).getMessage())));
                });
        } catch (Exception e) {
            try { owner.getJSONArray("messages").put(message); message.put("status", "error").put("error", unwrap(e).getMessage()); } catch (Exception ignored) {}
            endAgentTurn(run, turn);
        }
    }
    private boolean appendAgentMessage(JSONObject owner, AgentTurn turn) throws Exception {
        JSONArray messages = owner.getJSONArray("messages");
        if (AgentGroup.indexOf(messages, turn.message.optString("id")) >= 0) return false;
        messages.put(turn.message); return true;
    }
    private void agentRunEvent(JSONObject data) {
        GroupRun run = group;
        AgentTurn turn = run == null ? null : run.active.get(data.optString("runId"));
        if (turn == null || run.cancelled) return;
        JSONObject owner = session(run.local), message = turn.message, member = AgentGroup.member(owner, turn.id);
        if (owner == null) { cancelGroup(run); return; }
        try {
            switch (data.optString("kind")) {
                case "session" -> { if (member != null) { member.put("nativeSession", data.optString("id")); persist(); } }
                case "status" -> {
                    if (!Set.of("waiting", "approval").contains(turn.status)) turn.status = "working";
                    publish();
                }
                case "delta" -> {
                    String delta = data.optString("text"); if (delta.isEmpty()) return;
                    turn.status = "responding"; message.put("text", message.optString("text") + delta);
                    if (appendAgentMessage(owner, turn)) { persist(); publish(); }
                    else if (run.local.equals(threadId)) event("message.delta", obj("threadId", run.local, "id", message.optString("id"), "delta", delta));
                }
                case "final" -> {
                    message.put("text", data.optString("text")).put("status", "complete"); appendAgentMessage(owner, turn);
                    if (member != null) member.put("nativeSession", data.optString("sessionId")).put("cursorId", turn.cursorId);
                    endAgentTurn(run, turn);
                    queueFollowUps(run, owner, data.optString("text"), turn.id);
                    persist(); publish(); advanceGroup(run);
                }
                case "error" -> {
                    message.put("status", data.optBoolean("interrupted") ? "interrupted" : "error").put("error", data.optString("message"));
                    appendAgentMessage(owner, turn); endAgentTurn(run, turn);
                    persist(); publish(); advanceGroup(run);
                }
                default -> {}
            }
        } catch (Exception e) { event("error", obj("threadId", run.local, "message", unwrap(e).getMessage())); }
    }
    private void endAgentTurn(GroupRun run, AgentTurn turn) {
        roomLeases.remove(turn.runId); run.active.remove(turn.runId);
        if (run.writer.equals(turn.runId)) run.writer = "";
        if (roomCommand != null && turn.runId.equals(roomCommandRunId)) roomCommand.destroy();
        if (activeAgentConsent != null && activeAgentConsent.turn == turn && agentApproval != null) agentApproval.decision.complete(false);
        updateGroupCurrent(run); drainAgentConsents();
    }
    private void finishGroup(GroupRun run) {
        if (group != run) return;
        group = null; drainAgentConsents();
        syncCurrentTurn(); persist(); publish(); stopServiceIfIdle();
    }
    /** User intervention revokes every live lease and all queued/chained turns. */
    private void cancelGroup(GroupRun run) {
        cancelGroup(run, true);
    }
    private void cancelGroup(GroupRun run, boolean interruptCodex) {
        run.cancelled = true; run.queue.clear();
        if (interruptCodex && run.codexRunning) {
            String running = runningTurns.getOrDefault(run.local, "");
            JSONObject owner = session(run.local);
            if (!running.isBlank() && owner != null) try { call("turn/interrupt", obj("threadId", owner.optString("codexThreadId"), "turnId", running)); }
            catch (Exception e) { event("error", obj("threadId", run.local, "message", unwrap(e).getMessage())); }
        }
        for (AgentTurn turn : new ArrayList<>(run.active.values())) {
            stopAgent(turn.runId);
            try { turn.message.put("status", "interrupted"); } catch (Exception ignored) {}
            endAgentTurn(run, turn);
        }
        if (group == run) group = null;
        drainAgentConsents(); syncCurrentTurn(); persist(); publish();
    }
    private boolean validConsent(AgentConsent request) {
        JSONObject lease = roomLeases.get(request.turn.runId);
        return group == request.group && !request.group.cancelled && request.group.active.get(request.turn.runId) == request.turn
            && lease != null && "write".equals(lease.optString("mode")) && !"read-only".equals(permissionMode);
    }
    private boolean projectHasCodexTurn(String key) {
        for (String local : runningTurns.keySet()) {
            JSONObject owner = session(local);
            if (owner != null && key.equals(owner.optString("workspaceKey"))) return true;
        }
        return false;
    }
    private boolean canWrite(AgentConsent request) {
        JSONObject lease = roomLeases.get(request.turn.runId);
        return lease != null && !projectHasCodexTurn(lease.optString("projectKey"))
            && (request.group.writer.isBlank() || request.group.writer.equals(request.turn.runId))
            && (roomCommand == null || !roomCommand.isAlive());
    }
    /** Approval and the project write slot are independent gates. */
    private void agentApprove(JSONObject p, Reply reply) {
        GroupRun run = group; AgentTurn turn = run == null ? null : run.active.get(p.optString("runId"));
        if (turn == null || !turn.id.equals(p.optString("memberId")) || !run.local.equals(p.optString("sessionId"))) {
            reply.complete(obj("allow", false), null); return;
        }
        AgentConsent request = new AgentConsent(run, turn, p, reply);
        if (!validConsent(request) || agentConsents.size() >= 32) { reply.complete(obj("allow", false), null); return; }
        agentConsents.add(request); turn.status = "waiting"; drainAgentConsents(); publish();
    }
    private void grantConsent(AgentConsent request, boolean allowed) {
        boolean grant = allowed && validConsent(request) && canWrite(request);
        if (grant) {
            request.group.writer = request.turn.runId; request.turn.status = "working";
            JSONObject lease = roomLeases.get(request.turn.runId);
            // A single grant is bound to this exact tool and arguments, never a reusable bypass.
            try { lease.put("grant", obj("name", request.params.optString("title"), "detail", request.params.optString("detail"))); } catch (Exception ignored) {}
        } else request.turn.status = "responding";
        request.reply.complete(obj("allow", grant), null);
    }
    private void drainAgentConsents() {
        if (pendingApproval != null && !pendingApproval.decision.isDone()) return;
        Iterator<AgentConsent> it = agentConsents.iterator();
        while (it.hasNext()) {
            AgentConsent request = it.next();
            if (!validConsent(request)) { it.remove(); request.reply.complete(obj("allow", false), null); continue; }
            if (!canWrite(request)) continue;
            it.remove();
            JSONObject lease = roomLeases.get(request.turn.runId);
            String policy = lease.optString("approvalMode", "ask");
            if ("allow-all".equals(policy) || ("auto-review".equals(policy) && "claude".equals(request.turn.id))) {
                grantConsent(request, true); continue;
            }
            String detail = request.params.optString("detail");
            Approval approval = new Approval(AgentGroup.name(request.turn.id) + " · " + request.params.optString("title"),
                detail.length() > 12000 ? detail.substring(0, 12000) + "\n…" : detail);
            pendingApproval = approval; agentApproval = approval; activeAgentConsent = request; request.turn.status = "approval";
            try { JSONObject owner = session(request.group.local); if (owner != null) owner.put("approvalPending", true); } catch (Exception ignored) {}
            ScheduledFuture<?> timeout = approvalTimer.schedule(() -> approval.decision.complete(false), 3, TimeUnit.MINUTES);
            approval.decision.whenCompleteAsync((approved, error) -> {
                timeout.cancel(false);
                if (pendingApproval == approval) pendingApproval = null;
                if (agentApproval == approval) { agentApproval = null; activeAgentConsent = null; }
                try { JSONObject owner = session(request.group.local); if (owner != null) owner.put("approvalPending", false); } catch (Exception ignored) {}
                grantConsent(request, error == null && Boolean.TRUE.equals(approved));
                drainAgentConsents(); persist(); publish();
            }, io);
            Ui current = ui;
            taskNotification("approval", t("승인 필요"), approval.title, request.group.local, approval.id);
            if (current != null) current.approval(approval);
            for (Ui observer : observers) if (observer != current) observer.approval(approval);
            if (current == null && observers.isEmpty()) approval.decision.complete(false);
            persist(); publish(); return;
        }
    }
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
    private JSONObject runTerminal(String command) throws Exception {
        if (terminal.busy()) throw new IOException(t("터미널 명령이 실행 중입니다."));
        if (command.isBlank()) throw new IOException(t("명령을 입력해 주세요."));
        documents.requireWorkspaceAvailable();
        if (documents.workspace().optBoolean("selected") && documents.directDirectory() == null)
            throw new IOException(t("이 문서 제공자 폴더에서는 셸을 실행할 수 없습니다. 기기 파일 접근 권한을 확인하거나 일반 대화의 앱 내부 작업 폴더를 사용해 주세요."));
        ProcessBuilder builder;
        boolean useLinux = linux.status().optBoolean("enabled");
        if (useLinux) builder = linux.command(command, projectDirectory(), codexHome.root(), runtimeAliases());
        else {
            builder = new ProcessBuilder("/system/bin/sh", "-c", command).directory(projectDirectory());
            devTools.configure(builder, codexHome.root(), runtimeAliases());
            linux.configureEnvironment(builder.environment());
        }
        builder.redirectErrorStream(true);
        File helper = new File(context.getFilesDir(), "terminal_io.py");
        try (InputStream in = context.getAssets().open("rooms/terminal_io.py")) {
            Files.copy(in, helper.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> argv = new ArrayList<>(List.of(builder.environment().get("MC_PYTHON"), helper.getAbsolutePath()));
        argv.addAll(builder.command()); builder.command(argv);
        context.startForegroundService(new Intent(context, EngineService.class));
        try { return terminal.start(builder); }
        catch (Exception error) { stopServiceIfIdle(); throw error; }
    }
    private void stopTerminalProcess() {
        try { terminal.stop(null); } catch (IOException ignored) {}
    }
}
