package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.os.*;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.*;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static dev.mobilecodex.app.core.Json.*;

public final class MainActivity extends Activity implements Engine.Ui {
    static boolean isChatIconPath(String path) {
        return path != null && path.matches("/chat-icons/[0-9]{2}-[a-z]+(?:-[a-z]+)*\\.png");
    }
    static boolean isPackagedLogoPath(String path) {
        return "/codex-logo.png".equals(path);
    }
    static WebResourceResponse packagedLogoResponse(AssetManager assets, Uri uri) {
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || !isPackagedLogoPath(uri.getPath())) {
            return deniedResponse();
        }
        try {
            return new WebResourceResponse("image/png", null, 200, "OK",
                Map.of("Cache-Control", "private, max-age=86400", "X-Content-Type-Options", "nosniff"), assets.open("web/codex-logo.png"));
        } catch (Exception e) {
            return deniedResponse();
        }
    }
    static boolean isBrowserUri(Uri uri, boolean allowHttp) {
        return ("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme())))
            && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null;
    }
    static final String HOST = "appassets.androidplatform.net";
    private static final int PICK_FOLDER = 31, EXPORT_RECOVERY = 32, IMPORT_SKILL = 33, EXPORT_IMAGE = 34, PICK_ATTACHMENTS = 35, EXPORT_ATTACHMENT = 36, INSTALL_UPDATE = 37, PICK_CHARACTERS = 38;
    private WebView web;
    private SafeWebViewLayout root;
    private ChatWebTransport chatWeb;
    private boolean keyboardVisible;
    private String theme = "system";
    private Engine engine;
    private CharacterPacks characterPacks;
    private InlineDictation dictation;
    private static final int MICROPHONE_PERMISSION = 82;
    private boolean loaded, foreground;
    private AppUpdates updates;
    private AlertDialog approvalDialog;
    private String approvalId = "", exportId = "";
    private String pendingPickerId, pendingSkillId;
    private String pendingImageId;
    private String reconnectProjectKey = "", pendingAttachmentRequest, attachmentDraftKey, exportAttachmentId;
    private final java.util.ArrayDeque<String> pendingUiEvents = new java.util.ArrayDeque<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        engine = ((MobileCodexApp) getApplication()).engine();
        characterPacks = new CharacterPacks(this);
        updates = ((MobileCodexApp) getApplication()).updates();
        if (savedInstanceState != null) { exportId = savedInstanceState.getString("exportId", ""); pendingPickerId = savedInstanceState.getString("pickerId"); pendingSkillId = savedInstanceState.getString("skillId"); }
        if (savedInstanceState != null) pendingImageId = savedInstanceState.getString("imageId");
        if (savedInstanceState != null) {
            reconnectProjectKey = savedInstanceState.getString("reconnectProjectKey", "");
            attachmentDraftKey = savedInstanceState.getString("attachmentDraftKey");
            exportAttachmentId = savedInstanceState.getString("exportAttachmentId");
        }
        dictation = new InlineDictation(this, value -> event("voice.state", value));
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) return denied();
                String path = uri.getPath();
                if (path != null && path.startsWith("/character-packs/")) return characterPacks.route(uri);
                if (path != null && path.startsWith("/images/")) {
                    try {
                        String id = path.substring("/images/".length());
                        return new WebResourceResponse(engine.images.mime(id), null, 200, "OK",
                            Map.of("Cache-Control", "private, max-age=86400", "X-Content-Type-Options", "nosniff"), engine.images.open(id));
                    } catch (Exception e) { return denied(); }
                }
                // Only this dedicated packaged PNG directory is exposed; no arbitrary asset or file paths.
                if (isChatIconPath(path)) {
                    try { return new WebResourceResponse("image/png", null, 200, "OK",
                        Map.of("Cache-Control", "private, max-age=86400", "X-Content-Type-Options", "nosniff"), getAssets().open("web" + path)); }
                    catch (Exception e) { return denied(); }
                }
                if (isPackagedLogoPath(path)) {
                    return packagedLogoResponse(getAssets(), uri);
                }
                if (path == null || !(path.equals("/index.html") || path.equals("/app.css") || path.equals("/app.js") || path.equals("/ui-core.js") || path.equals("/translations.js") || path.equals("/locale.js"))) return denied();
                String mime = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
                try {
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK",
                        Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"), getAssets().open("web" + path));
                } catch (Exception e) { return denied(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (url.equals("https://" + HOST + "/index.html")) {
                    loaded = true;
                    while (!pendingUiEvents.isEmpty()) web.evaluateJavascript(pendingUiEvents.removeFirst(), null);
                    engine.attach(MainActivity.this);
                    handleNotificationIntent(getIntent());
                    event("viewport", obj("keyboardVisible", keyboardVisible));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Mobile Codex").setMessage(message)
                    .setPositiveButton(t("확인"), (dialog, which) -> result.confirm())
                    .setNegativeButton(t("취소"), (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        root = new SafeWebViewLayout(this, visible -> {
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                event("viewport", obj("keyboardVisible", visible));
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyTheme("system");
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        web.loadUrl("https://" + HOST + "/index.html");
        handleNotificationIntent(getIntent());
    }
    private WebResourceResponse denied() {
        return deniedResponse();
    }
    private static WebResourceResponse deniedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Map.of(), new ByteArrayInputStream(new byte[0]));
    }
    @Override protected void onStart() { super.onStart(); if (loaded) engine.attach(this); }
    @Override protected void onResume() { super.onResume(); AppLanguage.configure(this); foreground = true; getSharedPreferences("notifications", 0).edit().putBoolean("foreground", true).commit(); requestNotificationPermissionIfNeeded(); event("notifications.changed", obj()); event("updates.changed", updates.snapshot()); if (loaded) engine.attach(this); event("voice.changed", obj()); if (chatWeb != null) chatWeb.reloadIfIdle(); }
    @Override protected void onPause() { foreground = false; getSharedPreferences("notifications", 0).edit().putBoolean("foreground", false).commit(); if (dictation != null && !dictation.waitingPermission()) dictation.cancel(); super.onPause(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleNotificationIntent(intent); }
    private void handleNotificationIntent(Intent intent) {
        if (intent == null || !CodexNotificationReceiver.ACTION.equals(intent.getAction())) return;
        String thread = intent.getStringExtra("threadId"); if (thread == null) thread = "";
        final String target = thread;
        if (loaded) runOnUiThread(() -> event("notification.open", obj("threadId", target, "approvalId", intent.getStringExtra("approvalId"))));
    }
    @Override protected void onStop() { if (dictation != null) dictation.cancel(); engine.detach(this); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("reconnectProjectKey", reconnectProjectKey);
        out.putString("attachmentDraftKey", attachmentDraftKey);
        out.putString("exportAttachmentId", exportAttachmentId);
        out.putString("imageId", pendingImageId);
        out.putString("skillId", pendingSkillId); out.putString("exportId", exportId); out.putString("pickerId", pendingPickerId); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        if (dictation != null) dictation.cancel();
        engine.detach(this);
        if (approvalDialog != null) approvalDialog.dismiss();
        if (chatWeb != null) chatWeb.destroy();
        web.removeJavascriptInterface("Native"); web.destroy(); super.onDestroy();
    }
    private ChatWebTransport chatWeb() {
        if (chatWeb == null) chatWeb = new ChatWebTransport(this, root);
        return chatWeb;
    }
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (dictation != null && !dictation.snapshot().optString("phase").equals("idle")) { dictation.cancel(); return; }
        if (keyboardVisible) {
            WindowCompat.getInsetsController(getWindow(), web).hide(WindowInsetsCompat.Type.ime());
            return;
        }
        if (!loaded) { moveTaskToBack(true); return; }
        web.evaluateJavascript("window.mobileCodexBack ? window.mobileCodexBack() : false", handled -> {
            if (!"true".equals(handled) && !isDestroyed()) moveTaskToBack(true);
        });
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == MICROPHONE_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) dictation.start(); else dictation.denied();
        } else if (code == 41) event("notifications.changed", obj());
    }
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        var preferences = getSharedPreferences("notifications", 0);
        if (!preferences.getBoolean("enabled", true)
            || preferences.getBoolean("permissionAsked", false)
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        preferences.edit().putBoolean("permissionAsked", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
    }
    private void applyTheme(String choice) {
        theme = choice;
        boolean dark = "dark".equals(choice) || ("system".equals(choice)
            && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        int background = Color.parseColor(dark ? "#101012" : "#fafafa");
        root.setBackgroundColor(background); web.setBackgroundColor(background);
        var controller = WindowCompat.getInsetsController(getWindow(), web);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
        event("theme", obj("theme", dark ? "dark" : "light"));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); applyTheme(theme);
    }
    @Override public void event(String name, JSONObject data) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if ("state".equals(name)) getSharedPreferences("notifications", 0).edit().putString("visibleThread", data.optString("threadId", "")).commit();
            String script = "window.mobileCodexEvent(" + JSONObject.quote(name) + "," + data + ");";
            if (loaded) web.evaluateJavascript(script, null); else pendingUiEvents.addLast(script);
        });
    }
    private void respond(String id, JSONObject result, Throwable error) {
        event("response", obj("id", id, "result", result, "error", error == null ? null : error.getMessage()));
    }
    @Override public void approval(Engine.Approval approval) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing() || approval.decision.isDone() || approvalId.equals(approval.id)) return;
            approvalId = approval.id;
            TextView text = new TextView(this);
            text.setText(approval.message); text.setTextIsSelectable(true); text.setTextSize(14); text.setPadding(40, 24, 40, 24);
            ScrollView scroll = new ScrollView(this); scroll.addView(text);
            approvalDialog = new AlertDialog.Builder(this).setTitle(approval.title).setView(scroll)
                .setPositiveButton(t("적용"), (dialog, which) -> approval.decision.complete(true))
                .setNegativeButton(t("취소"), (dialog, which) -> approval.decision.complete(false))
                .setOnCancelListener(dialog -> approval.decision.complete(false)).create();
            approvalDialog.setOnDismissListener(dialog -> approvalId = "");
            approvalDialog.show();
            approval.decision.whenComplete((result, error) -> runOnUiThread(() -> {
                if (approvalId.equals(approval.id) && approvalDialog != null) approvalDialog.dismiss();
            }));
        });
    }
    private void chooseFolder(String id, String projectKey) {
        if (pendingPickerId != null) { respond(id, null, new IllegalStateException(t("폴더 선택이 진행 중입니다."))); return; }
        pendingPickerId = id;
        reconnectProjectKey = projectKey;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_FOLDER); }
        catch (Exception e) { pendingPickerId = null; reconnectProjectKey = ""; respond(id, null, e); }
    }
    private void chooseCharacters(String id) {
        if (pendingPickerId != null) { respond(id, null, new IllegalStateException(t("폴더 선택이 진행 중입니다."))); return; }
        pendingPickerId = id;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_CHARACTERS); }
        catch (Exception e) { pendingPickerId = null; respond(id, null, e); }
    }
    @SuppressLint("WrongConstant") // URI grant flags are explicitly masked to the two accepted constants.
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_UPDATE) { updates.installEnded(); event("updates.changed", updates.snapshot()); }
        else if (request == PICK_ATTACHMENTS) {
            String id = pendingAttachmentRequest, scope = attachmentDraftKey;
            pendingAttachmentRequest = null; attachmentDraftKey = null;
            java.util.LinkedHashSet<Uri> uris = new java.util.LinkedHashSet<>();
            if (result == RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            engine.io.execute(() -> {
                org.json.JSONArray attachments = new org.json.JSONArray(), errors = new org.json.JSONArray();
                for (Uri uri : uris) {
                    try { attachments.put(engine.attachments.importUri(uri)); }
                    catch (Exception e) { errors.put(e.getMessage() == null ? t("파일을 읽지 못했습니다.") : e.getMessage()); }
                }
                JSONObject receipt = obj("receiptId", java.util.UUID.randomUUID().toString(), "draftKey", scope,
                    "attachments", attachments, "errors", errors, "cancelled", uris.isEmpty());
                // Persist before notifying: Android can recreate the WebView while the picker is open.
                getSharedPreferences("attachment-result", 0).edit().putString("pending", receipt.toString()).commit();
                event("attachments.picked", receipt);
                if (id != null) respond(id, receipt, null);
            });
        } else if (request == EXPORT_ATTACHMENT) {
            String attachmentId = exportAttachmentId; exportAttachmentId = null;
            if (result != RESULT_OK || data == null || data.getData() == null || attachmentId == null) return;
            Uri destination = data.getData();
            engine.io.execute(() -> {
                try (var in = engine.attachments.open(attachmentId); var out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new java.io.IOException(t("저장 위치를 열 수 없습니다."));
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    event("notice", obj("message", t("첨부 파일을 저장했습니다.")));
                } catch (Exception e) { event("error", obj("message", e.getMessage())); }
            });
        } else if (request == EXPORT_IMAGE) {
            String id = pendingImageId; pendingImageId = null;
            if (result != RESULT_OK || data == null || data.getData() == null || id == null) return;
            Uri destination = data.getData();
            engine.io.execute(() -> {
                try {
                    try (var in = engine.images.open(id); var out = getContentResolver().openOutputStream(destination)) {
                        if (out == null) throw new java.io.IOException(t("저장 위치를 열 수 없습니다."));
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    event("notice", obj("message", t("이미지를 저장했습니다.")));
                } catch (Exception e) { event("error", obj("message", e.getMessage())); }
            });
        } else if (request == IMPORT_SKILL) {
            String id = pendingSkillId; pendingSkillId = null;
            if (result != RESULT_OK || data == null || data.getData() == null) { respond(id, obj("cancelled", true), null); return; }
            Uri uri = data.getData();
            engine.io.execute(() -> {
                try { respond(id, obj("path", SkillImporter.install(this, uri)), null); }
                catch (Exception e) { respond(id, null, e); }
            });
        } else if (request == PICK_CHARACTERS) {
            String id = pendingPickerId; pendingPickerId = null;
            if (result != RESULT_OK || data == null || data.getData() == null) { respond(id, obj("cancelled", true), null); return; }
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
                engine.io.execute(() -> { try { characterPacks.setTree(uri); respond(id, characterPacks.refresh(), null); } catch (Exception e) { respond(id, null, e); } });
            } catch (Exception e) { respond(id, null, e); }
        } else if (request == PICK_FOLDER) {
            String id = pendingPickerId; pendingPickerId = null;
            String projectKey = reconnectProjectKey; reconnectProjectKey = "";
            if (result != RESULT_OK || data == null || data.getData() == null) { respond(id, obj("cancelled", true), null); return; }
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
                engine.io.execute(() -> {
                    try {
                        JSONObject workspace = engine.documents.select(uri, projectKey); engine.workspaceChanged(); respond(id, workspace, null);
                    }
                    catch (Exception e) { respond(id, null, e); }
                });
            } catch (Exception e) { respond(id, null, e); }
        } else if (request == EXPORT_RECOVERY && result == RESULT_OK && data != null && data.getData() != null) {
            String id = exportId; exportId = ""; Uri destination = data.getData();
            engine.io.execute(() -> {
                try { engine.documents.exportRecovery(id, destination); event("notice", obj("message", t("복구 사본을 저장했습니다."))); }
                catch (Exception e) { event("error", obj("message", e.getMessage())); }
            });
        }
    }
    private void installUpdate(String requestId, String sha256) {
        try {
            if (!engine.canInstallUpdate()) throw new java.io.IOException(t("진행 중인 작업과 터미널 명령을 마친 뒤 설치해 주세요."));
            if (!getPackageManager().canRequestPackageInstalls()) throw new java.io.IOException(t("먼저 이 앱의 업데이트 설치를 허용해 주세요."));
            updates.prepareInstall(sha256, (file, failure) -> runOnUiThread(() -> {
                if (failure != null) { respond(requestId, null, failure); return; }
                try {
                    if (isDestroyed() || isFinishing() || !foreground) throw new java.io.IOException(t("앱으로 돌아와 설치를 다시 눌러 주세요."));
                    if (!engine.canInstallUpdate()) throw new java.io.IOException(t("진행 중인 작업과 터미널 명령을 마친 뒤 설치해 주세요."));
                    if (!getPackageManager().canRequestPackageInstalls()) throw new java.io.IOException(t("앱 설치 허용 설정을 확인해 주세요."));
                    PhoneUseService.stopControl(); PhoneUseService.closeFloatingForUpdate();
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
                    Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    install.setClipData(ClipData.newRawUri("Mobile Codex update", uri));
                    startActivityForResult(install, INSTALL_UPDATE); respond(requestId, obj("ok", true), null);
                } catch (Exception error) { updates.installEnded(); respond(requestId, null, error); }
            }));
        } catch (Exception error) { respond(requestId, null, error); }
    }
    private final class Bridge {
        @JavascriptInterface public String locale() { return AppLanguage.snapshot(MainActivity.this).toString(); }
        @JavascriptInterface public void postMessage(String raw) {
            if (raw == null || raw.length() > 2 * 1024 * 1024) return;
            String requestId = null;
            try {
                JSONObject message = parse(raw);
                String id = message.getString("id"), action = message.getString("action");
                requestId = id;
                JSONObject args = message.optJSONObject("args"); if (args == null) args = new JSONObject();
                JSONObject parameters = args;
                if (action.equals("attachments.recover")) {
                    String saved = getSharedPreferences("attachment-result", 0).getString("pending", "");
                    respond(id, saved.isEmpty() ? obj("attachments", new org.json.JSONArray()) : new JSONObject(saved), null); return;
                }
                if (action.equals("attachments.ack")) {
                    var prefs = getSharedPreferences("attachment-result", 0);
                    String saved = prefs.getString("pending", "");
                    if (!saved.isEmpty() && new JSONObject(saved).optString("receiptId").equals(args.optString("receiptId"))) prefs.edit().remove("pending").apply();
                    respond(id, obj("ok", true), null); return;
                }
                if (action.equals("attachments.pick")) {
                    String scope = args.getString("draftKey");
                    runOnUiThread(() -> {
                        if (attachmentDraftKey != null) { respond(id, null, new IllegalStateException(t("파일 선택이 진행 중입니다."))); return; }
                        pendingAttachmentRequest = id; attachmentDraftKey = scope;
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_ATTACHMENTS);
                        } catch (Exception e) { pendingAttachmentRequest = null; attachmentDraftKey = null; respond(id, null, e); }
                    }); return;
                }
                if (action.equals("attachments.export")) {
                    JSONObject attachment = engine.attachments.get(args.getString("id"));
                    runOnUiThread(() -> {
                        if (exportAttachmentId != null) { respond(id, null, new IllegalStateException(t("첨부 파일 저장 위치를 선택 중입니다."))); return; }
                        exportAttachmentId = attachment.optString("id");
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(attachment.optString("mime", "application/octet-stream"))
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, attachment.optString("name")), EXPORT_ATTACHMENT);
                            respond(id, obj("ok", true), null);
                        } catch (Exception e) { exportAttachmentId = null; respond(id, null, e); }
                    }); return;
                }
                if (action.equals("images.export")) {
                    String imageId = args.getString("id"), name = args.optString("name", "codex-image.png");
                    String mime = engine.images.mime(imageId);
                    runOnUiThread(() -> {
                        if (pendingImageId != null) { respond(id, null, new IllegalStateException(t("이미지 저장 위치를 선택 중입니다."))); return; }
                        pendingImageId = imageId;
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime)
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), EXPORT_IMAGE);
                            respond(id, obj("ok", true), null);
                        } catch (Exception e) { pendingImageId = null; respond(id, null, e); }
                    }); return;
                }
                if (action.equals("notifications.state")) {
                    var preferences = getSharedPreferences("notifications", 0);
                    respond(id, obj("enabled", preferences.getBoolean("enabled", true), "vibration", preferences.getBoolean("vibration", true),
                        "permission", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED), null); return;
                }
                if (action.equals("notifications.configure")) {
                    boolean enabled = args.optBoolean("enabled", true), vibration = args.optBoolean("vibration", true);
                    var notificationPreferences = getSharedPreferences("notifications", 0);
                    notificationPreferences.edit().putBoolean("enabled", enabled).putBoolean("vibration", vibration).apply();
                    if (Build.VERSION.SDK_INT >= 33 && enabled && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPreferences.edit().putBoolean("permissionAsked", true).apply();
                        runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41));
                    }
                    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).deleteNotificationChannel(CodexNotificationReceiver.CHANNEL);
                    respond(id, obj("enabled", enabled, "vibration", vibration), null); return;
                }
                if (action.equals("notifications.openSettings")) {
                    runOnUiThread(() -> {
                        try {
                            Intent settings = Build.VERSION.SDK_INT >= 26
                                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()));
                            startActivity(settings); respond(id, obj("ok", true), null);
                        } catch (Exception e) { respond(id, null, e); }
                    }); return;
                }
                if (action.equals("updates.state")) { respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.configure")) { updates.configure(args.getString("repository"), args.optBoolean("prereleases", true)); respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.check")) { updates.check(); respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.download")) { updates.download(args.optString("sha256")); respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.cancel")) { updates.cancel(); respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.clear")) { updates.clear(); respond(id, updates.snapshot(), null); return; }
                if (action.equals("updates.install")) { runOnUiThread(() -> installUpdate(id, parameters.optString("sha256"))); return; }
                if (action.equals("characters.list")) { engine.io.execute(() -> { try { respond(id, characterPacks.refresh(), null); } catch (Exception e) { respond(id, null, e); } }); return; }
                if (action.equals("characters.refresh")) { engine.io.execute(() -> { try { respond(id, characterPacks.refresh(), null); } catch (Exception e) { respond(id, null, e); } }); return; }
                if (action.equals("characters.select")) { String packId = args.getString("id"); engine.io.execute(() -> { try { characterPacks.select(packId); respond(id, characterPacks.list(), null); } catch (Exception e) { respond(id, null, e); } }); return; }
                if (action.equals("characters.chooseFolder")) { runOnUiThread(() -> chooseCharacters(id)); return; }
                if (action.equals("updates.permission")) {
                    runOnUiThread(() -> { try { PhoneUseService.stopControl(); PhoneUseService.closeFloatingForUpdate(); startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); respond(id, obj("ok", true), null); } catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("voice.recover")) { runOnUiThread(() -> respond(id, obj("receipts", VoiceInput.pending(MainActivity.this, "main"), "active", VoiceInput.active(), "dictation", dictation.snapshot()), null)); return; }
                if (action.equals("voice.ack")) { VoiceInput.acknowledge(MainActivity.this, "main", args.getString("receiptId")); respond(id, obj("ok", true), null); return; }
                if (action.equals("voice.stop") || action.equals("voice.cancel")) {
                    runOnUiThread(() -> { if (action.equals("voice.stop")) dictation.stop(); else dictation.cancel(); respond(id, dictation.snapshot(), null); }); return;
                }
                if (action.equals("voice.start")) {
                    runOnUiThread(() -> { try { JSONObject result = dictation.prepare(parameters);
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictation.start();
                        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
                        respond(id, result, null); } catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("ui.floatingChat")) {
                    runOnUiThread(() -> { try { PhoneUseService.showFloatingChat(); respond(id, obj("ok", true), null); } catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("ui.phoneSettings")) {
                    runOnUiThread(() -> {
                        try { startActivity(PhoneUseService.settingsIntent(MainActivity.this)); respond(id, obj("ok", true), null); }
                        catch (Exception e) { respond(id, null, e); }
                    }); return;
                }
                if (action.equals("ui.phoneEnable")) {
                    runOnUiThread(() -> {
                        if (!PhoneUseService.status(MainActivity.this).optBoolean("connected")) {
                            respond(id, null, new IllegalStateException(t("접근성 설정에서 Mobile Codex 휴대폰 제어를 먼저 켜 주세요."))); return;
                        }
                        new AlertDialog.Builder(MainActivity.this).setTitle(t("휴대폰 제어 켜기"))
                            .setMessage(t("Codex가 요청을 수행하면서 다른 앱의 화면 내용과 스크린샷을 AI 서비스로 전송하고 탭·입력·스크롤할 수 있습니다. 화면 정보는 Codex 대화 기록에 남을 수 있습니다. 화면 위의 중지 버튼이나 앱 설정에서 언제든 끌 수 있습니다."))
                            .setPositiveButton(t("동의하고 켜기"), (dialog, which) -> {
                                try { PhoneUseService.enableFromUi(); respond(id, PhoneUseService.status(MainActivity.this), null); }
                                catch (Exception e) { respond(id, null, e); }
                            }).setNegativeButton(t("취소"), (dialog, which) -> respond(id, obj("cancelled", true), null))
                            .setOnCancelListener(dialog -> respond(id, obj("cancelled", true), null)).show();
                    }); return;
                }
                if (action.equals("ui.locale")) {
                    AppLanguage.set(MainActivity.this, args.optString("language", "system")); event("updates.changed", updates.snapshot()); respond(id, AppLanguage.snapshot(MainActivity.this), null); return;
                }
                if (action.equals("ui.theme")) {
                    String choice = args.optString("theme", "system");
                    runOnUiThread(() -> { applyTheme(choice); respond(id, obj("ok", true), null); }); return;
                }
                if (action.equals("skills.import")) {
                    runOnUiThread(() -> {
                        if (pendingSkillId != null) { respond(id, null, new IllegalStateException(t("스킬 선택이 진행 중입니다."))); return; }
                        pendingSkillId = id;
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT_SKILL);
                    }); return;
                }
                if (action.equals("ui.externalBrowser") || action.equals("ui.openLink")) {
                    Uri uri = Uri.parse(args.getString("url"));
                    boolean messageLink = action.equals("ui.openLink");
                    if (!isBrowserUri(uri, messageLink))
                        throw new IllegalArgumentException(messageLink ? t("올바른 HTTP 또는 HTTPS 주소만 열 수 있습니다.") : t("HTTPS 주소만 열 수 있습니다."));
                    runOnUiThread(() -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); respond(id, obj("ok", true), null); }
                        catch (Exception e) { respond(id, null, e); }
                    }); return;
                }
                if (action.equals("ui.chatWebProbe")) {
                    runOnUiThread(() -> {
                        try { startActivity(new Intent(MainActivity.this, ChatWebProbeActivity.class)); respond(id, obj("ok", true), null); }
                        catch (Exception e) { respond(id, null, e); }
                    }); return;
                }
                if (action.equals("chat.web.prepare")) {
                    runOnUiThread(() -> { try { chatWeb(); respond(id, obj("ok", true), null); }
                        catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("chat.web.new")) {
                    runOnUiThread(() -> { try { chatWeb().newChat(); respond(id, obj("ok", true), null); }
                        catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("chat.web.send")) {
                    String text = args.getString("text");
                    runOnUiThread(() -> { try { chatWeb().send(text, (result, error) -> respond(id, result, error)); }
                        catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("chat.web.modelState")) {
                    runOnUiThread(() -> { try { chatWeb().modelState((result, error) -> respond(id, result, error)); }
                        catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("chat.web.selectModel")) {
                    String level = args.getString("level");
                    runOnUiThread(() -> { try { chatWeb().selectModel(level, (result, error) -> respond(id, result, error)); }
                        catch (Exception e) { respond(id, null, e); } }); return;
                }
                if (action.equals("ui.storageAccess")) {
                    runOnUiThread(() -> {
                        try {
                            if (Build.VERSION.SDK_INT >= 30) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                            } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
                            respond(id, obj("ok", true), null);
                        } catch (Exception e) { respond(id, null, e); }
                    }); return;
                }
                if (action.equals("files.pick")) { String key = args.optString("projectKey", ""); runOnUiThread(() -> chooseFolder(id, key)); return; }
                if (action.equals("ui.loginBrowser")) {
                    Uri uri = Uri.parse(args.getString("url"));
                    if (!"https".equals(uri.getScheme()) || !("auth.openai.com".equals(uri.getHost()) || "chatgpt.com".equals(uri.getHost())))
                        throw new IllegalArgumentException(t("잘못된 로그인 주소입니다."));
                    runOnUiThread(() -> { startActivity(new Intent(Intent.ACTION_VIEW, uri)); respond(id, obj("ok", true), null); }); return;
                }
                if (action.equals("ui.copyCode")) {
                    String code = args.getString("code");
                    runOnUiThread(() -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        clipboard.setPrimaryClip(ClipData.newPlainText(t("ChatGPT 로그인 코드"), code)); respond(id, obj("ok", true), null);
                    }); return;
                }
                if (action.equals("recovery.export")) {
                    exportId = args.getString("id"); String name = args.getString("name");
                    runOnUiThread(() -> {
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), EXPORT_RECOVERY);
                        respond(id, obj("ok", true), null);
                    }); return;
                }
                if (action.equals("runtime.start") || action.equals("auth.login")) {
                    runOnUiThread(() -> {
                        if (Build.VERSION.SDK_INT >= 33 && getSharedPreferences("notifications", 0).getBoolean("enabled", true)
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
                        engine.handle(action, parameters, (value, error) -> respond(id, value, error));
                    }); return;
                }
                engine.handle(action, args, (value, error) -> respond(id, value, error));
            } catch (Exception e) { if (requestId != null) respond(requestId, null, e); else event("error", obj("message", e.getMessage())); }
        }
    }
}
