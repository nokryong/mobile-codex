package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Fail-closed transport for the official ChatGPT web UI. Remote content receives no Android JS bridge. */
final class ProWebTransport {
    interface Done { void complete(JSONObject result, Exception error); }
    static final int FEATURE_VERSION = 3;
    static final String PROJECT_NAME = "mobile-codex-chat";

    private final Activity activity;
    private final WebView page;
    private final String adapter;
    private Done pending;
    private String operationId = "", localThreadId = "", prompt = "", requestedConversationId = "", requestedConversationPath = "", requestedProjectPath = "", resolvedProjectPath = "";
    private Uri[] uploads = new Uri[0];
    private ValueCallback<Uri[]> fileCallback;
    private boolean pageLoaded, clicked, destroyed, waitingForChooser, projectVerified;
    private int projectCreationStage;
    private int assistantCount, stablePolls;
    private String baselineAssistantId = "", lastReply = "", lastConversationId = "", lastConversationPath = "";
    private long generation, deadline;

    @SuppressLint("SetJavaScriptEnabled")
    ProWebTransport(Activity activity, FrameLayout root) {
        this.activity = activity;
        try (InputStream source = activity.getAssets().open("pro-web-transport.js")) { adapter = read(source); }
        catch (Exception error) { throw new IllegalStateException("GPT-6-Pro 웹 어댑터를 읽지 못했습니다.", error); }
        page = new WebView(activity);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false);
        page.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!waitingForChooser || uploads.length == 0 || fileCallback != null) return false;
                fileCallback = callback; waitingForChooser = false;
                callback.onReceiveValue(uploads); fileCallback = null; uploads = new Uri[0];
                long current = generation; page.postDelayed(() -> insert(current), 1200);
                return true;
            }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || !("chatgpt.com".equals(uri.getHost()) || "auth.openai.com".equals(uri.getHost()));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { pageLoaded = false; }
            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = "chatgpt.com".equals(Uri.parse(url).getHost());
                if (pending != null) {
                    long current = generation;
                    page.postDelayed(() -> { if (clicked) poll(current); else ensureProject(current, 0); }, 350);
                }
            }
        });
        // The packaged UI remains the top child. This page is never exposed to the Android bridge.
        root.addView(page, 0, new FrameLayout.LayoutParams(-1, -1));
        page.loadUrl("https://chatgpt.com/");
    }

    void send(JSONObject prepared, Uri[] allowedUploads, Done done) {
        if (destroyed) { done.complete(null, new IllegalStateException("GPT-6-Pro 웹 화면이 닫혔습니다.")); return; }
        if (!activity.getSharedPreferences("settings", 0).getBoolean("proWebEnabled", true)) {
            done.complete(status("unavailable", "GPT-6-Pro 웹 기능이 비활성화되어 있습니다."), null); return;
        }
        if (pending != null) { done.complete(null, new IllegalStateException("GPT-6-Pro 작업이 진행 중입니다.")); return; }
        String requested = prepared.optString("requestedModel");
        if (!ProContextBuilder.MODEL_ID.equals(requested)) { done.complete(null, new IllegalArgumentException("잘못된 GPT-6-Pro 요청입니다.")); return; }
        pending = done; operationId = prepared.optString("operationId"); localThreadId = prepared.optString("threadId"); prompt = prepared.optString("prompt");
        requestedConversationId = safeId(prepared.optString("chatConversationId"));
        requestedProjectPath = safeProjectPath(prepared.optString("chatProjectPath"));
        requestedConversationPath = safeConversationPath(prepared.optString("chatConversationPath"), requestedConversationId, requestedProjectPath);
        uploads = allowedUploads == null ? new Uri[0] : allowedUploads;
        clicked = false; waitingForChooser = false; assistantCount = 0; baselineAssistantId = ""; stablePolls = 0; lastReply = ""; lastConversationId = ""; lastConversationPath = "";
        projectVerified = false; projectCreationStage = 0; resolvedProjectPath = "";
        deadline = SystemClock.elapsedRealtime() + 10 * 60_000; long current = ++generation;
        journal();
        if (pageLoaded) page.post(() -> ensureProject(current, 0));
        else if (page.getUrl() == null || !page.getUrl().startsWith("https://chatgpt.com")) page.loadUrl("https://chatgpt.com/");
    }

    private void ensureProject(long current, int attempt) {
        if (!live(current) || clicked) return;
        if (expired()) { finish(status("web_changed", "ChatGPT 프로젝트를 확인하지 못했습니다.")); return; }
        if (!pageLoaded) { retry(() -> ensureProject(current, attempt + 1), attempt, 40, "login_required", "ChatGPT 로그인이 필요합니다."); return; }
        if (projectVerified) { openTarget(current); return; }
        if (projectCreationStage == 1) { finishProjectCreation(current, attempt); return; }
        evaluate("inspectProject(" + JSONObject.quote(PROJECT_NAME) + ")", project -> {
            if (!live(current)) return;
            String state = project.optString("status");
            if ("opening_sidebar".equals(state) || "opening_projects".equals(state) || "opening_project".equals(state)
                    || "closing_sidebar".equals(state) || "waiting".equals(state)) {
                retry(() -> ensureProject(current, attempt + 1), attempt, 40, "unavailable", reason(project)); return;
            }
            if ("available".equals(state)) {
                String found = safeProjectPath(project.optString("projectPath"));
                if (found.isBlank()) { finish(status("web_changed", "ChatGPT 프로젝트 주소를 확인하지 못했습니다.")); return; }
                if (!requestedProjectPath.isBlank() && !requestedProjectPath.equals(found)) {
                    finish(status("unavailable", "기존 대화가 연결된 mobile-codex-chat 프로젝트를 찾지 못했습니다.")); return;
                }
                resolvedProjectPath = found; projectVerified = true;
                activity.getSharedPreferences("pro-web", 0).edit().putString("projectPath", found).apply();
                journal();
                openTarget(current); return;
            }
            if ("login_required".equals(state)) { finish(status("login_required", "ChatGPT 웹 로그인이 필요합니다.")); return; }
            if ("missing".equals(state) && projectCreationStage == 0) {
                evaluate("startProjectCreation()", opened -> {
                    if (!live(current)) return;
                    if (!"creation_opened".equals(opened.optString("status"))) { finish(status(opened.optString("status", "unavailable"), reason(opened))); return; }
                    projectCreationStage = 1; page.postDelayed(() -> ensureProject(current, 0), 200);
                });
                return;
            }
            if (projectCreationStage == 2 && attempt < 40) { page.postDelayed(() -> ensureProject(current, attempt + 1), 500); return; }
            finish(status("missing".equals(state) ? "unavailable" : state.isBlank() ? "web_changed" : state, reason(project)));
        });
    }

    private void finishProjectCreation(long current, int attempt) {
        if (!live(current) || clicked || projectCreationStage != 1) return;
        evaluate("finishProjectCreation(" + JSONObject.quote(PROJECT_NAME) + ")", created -> {
            if (!live(current) || projectCreationStage != 1) return;
            String state = created.optString("status");
            if ("waiting".equals(state) && attempt < 60 && !expired()) {
                page.postDelayed(() -> finishProjectCreation(current, attempt + 1), 200); return;
            }
            if (!"creation_submitted".equals(state)) {
                finish(status("web_changed", "waiting".equals(state)
                    ? "프로젝트 생성창이 준비되지 않았습니다. 잠시 후 다시 시도해 주세요." : reason(created))); return;
            }
            projectCreationStage = 2;
            page.postDelayed(() -> ensureProject(current, 0), 350);
        });
    }

    private void openTarget(long current) {
        if (!live(current) || clicked) return;
        Uri currentUri = Uri.parse(page.getUrl() == null ? "" : page.getUrl());
        String currentPath = currentUri.getPath() == null ? "" : currentUri.getPath().replaceAll("/$", "");
        if (!requestedConversationId.isBlank() && !safeConversationPath(currentPath, requestedConversationId, resolvedProjectPath).isBlank()) {
            selectModel(current, 0); return;
        }
        String target = requestedConversationId.isBlank() ? resolvedProjectPath
            : requestedConversationPath.isBlank() ? "/c/" + requestedConversationId : requestedConversationPath;
        if (!target.equals(currentPath)) { pageLoaded = false; page.loadUrl("https://chatgpt.com" + target); return; }
        selectModel(current, 0);
    }

    private void selectModel(long current, int attempt) {
        if (!live(current) || clicked) return;
        if (expired()) { finish(status("web_changed", "ChatGPT 모델 선택기를 확인하지 못했습니다.")); return; }
        if (!pageLoaded) { retry(() -> selectModel(current, attempt + 1), attempt, 40, "login_required", "ChatGPT 로그인이 필요합니다."); return; }
        evaluate("prepareComposer()", state -> {
            if (!live(current)) return;
            String status = state.optString("status");
            if ("closing_sidebar".equals(status) || "waiting".equals(status)) {
                retry(() -> selectModel(current, attempt + 1), attempt, 40, "unavailable", reason(state)); return;
            }
            if ("login_required".equals(status)) { finish(status("login_required", "ChatGPT 웹 로그인이 필요합니다.")); return; }
            if (!"available".equals(status)) { finish(status(status.isBlank() ? "web_changed" : status, reason(state))); return; }
            if ("GPT-6 Pro".equals(state.optString("currentModel"))) { confirmModel(current, 0); return; }
            evaluate("openModelPicker()", opened -> {
                if (!live(current)) return;
                if (!"opened".equals(opened.optString("status"))) { finish(status("unavailable", reason(opened))); return; }
                page.postDelayed(() -> chooseModel(current), 250);
            });
        });
    }

    private void chooseModel(long current) {
        if (!live(current) || clicked) return;
        evaluate("choosePro()", chosen -> {
            if (!live(current)) return;
            String status = chosen.optString("status");
            if (!"selected".equals(status)) { finish(status(status.isBlank() ? "web_changed" : status, reason(chosen))); return; }
            page.postDelayed(() -> confirmModel(current, 0), 350);
        });
    }

    private void confirmModel(long current, int attempt) {
        if (!live(current) || clicked) return;
        evaluate("confirmPro()", confirmed -> {
            if (!live(current)) return;
            if ("available".equals(confirmed.optString("status")) && "GPT-6 Pro".equals(confirmed.optString("confirmedModel"))) {
                if (uploads.length == 0) insert(current); else requestFiles(current);
                return;
            }
            if (attempt < 12) page.postDelayed(() -> confirmModel(current, attempt + 1), 150);
            else finish(status(confirmed.optString("status", "web_changed"), reason(confirmed)));
        });
    }

    private void requestFiles(long current) {
        waitingForChooser = true;
        evaluate("requestFiles()", result -> {
            if (!live(current)) return;
            if (!"chooser_requested".equals(result.optString("status"))) {
                waitingForChooser = false; finish(status(result.optString("status", "web_changed"), reason(result)));
            } else page.postDelayed(() -> {
                if (live(current) && waitingForChooser) { waitingForChooser = false; finish(status("web_changed", "ChatGPT 파일 선택기를 열지 못했습니다.")); }
            }, 2500);
        });
    }

    private void insert(long current) {
        if (!live(current) || clicked) return;
        evaluate("insert(" + JSONObject.quote(prompt) + ")", result -> {
            if (!live(current)) return;
            if (!"inserted".equals(result.optString("status"))) { finish(status(result.optString("status", "web_changed"), reason(result))); return; }
            assistantCount = result.optInt("assistantCount"); baselineAssistantId = result.optString("lastAssistantId");
            page.postDelayed(() -> click(current), 200);
        });
    }

    private void click(long current) {
        if (!live(current) || clicked) return;
        evaluate("clickSend()", result -> {
            if (!live(current)) return;
            if (!"clicked".equals(result.optString("status"))) { finish(status(result.optString("status", "not_sent"), reason(result))); return; }
            // This is the sole send click for this operation. No retry is allowed after this point.
            clicked = true; page.postDelayed(() -> poll(current), 800);
        });
    }

    private void poll(long current) {
        if (!live(current) || !clicked) return;
        if (expired()) { finish(status("uncertain", "전송 후 답변 저장을 확인하지 못했습니다. 같은 메시지를 다시 보내기 전에 ChatGPT 웹 대화를 확인해 주세요.")); return; }
        evaluate("observe(" + assistantCount + "," + JSONObject.quote(baselineAssistantId) + ")", result -> {
            if (!live(current)) return;
            String reply = result.optString("reply");
            String observedConversation = safeId(result.optString("conversationId"));
            String observedPath = safeConversationPath(result.optString("conversationPath"), observedConversation, resolvedProjectPath);
            if (!observedConversation.isBlank() && !observedPath.isBlank()) { lastConversationId = observedConversation; lastConversationPath = observedPath; }
            if (!observedConversation.isBlank() && !observedPath.isBlank()) journal();
            if (!requestedConversationId.isBlank() && !observedConversation.isBlank() && !requestedConversationId.equals(observedConversation)) {
                finish(status("uncertain", "저장된 ChatGPT 대화와 다른 대화가 열렸습니다. 자동 재전송하지 않습니다.")); return;
            }
            if ("observed".equals(result.optString("status")) && !result.optBoolean("streaming") && !reply.isBlank()
                    && !observedConversation.isBlank() && !observedPath.isBlank()) {
                stablePolls = reply.equals(lastReply) ? stablePolls + 1 : 0; lastReply = reply;
                if (stablePolls >= 2) {
                    JSONObject completed = status("completed", "");
                    try { completed.put("reply", reply).put("remoteMessageId", safeId(result.optString("remoteMessageId")))
                        .put("chatConversationId", observedConversation).put("chatConversationPath", observedPath)
                        .put("chatProjectPath", resolvedProjectPath).put("confirmedModel", "GPT-6 Pro"); }
                    catch (Exception ignored) {}
                    finish(completed); return;
                }
            }
            page.postDelayed(() -> poll(current), 1000);
        });
    }

    void cancel(Done done) {
        if (pending == null) { done.complete(status("not_sent", "진행 중인 GPT-6-Pro 작업이 없습니다."), null); return; }
        boolean wasClicked = clicked;
        if (wasClicked && !destroyed) evaluate("stop()", ignored -> {});
        JSONObject result = status(wasClicked ? "uncertain" : "not_sent",
            wasClicked ? "중지를 요청했습니다. ChatGPT 웹 대화에서 전송 결과를 확인해 주세요." : "전송 전에 취소했습니다.");
        Done original = pending; pending = null; generation++; clear();
        if (original != null) original.complete(result, null);
        done.complete(result, null);
    }

    JSONObject diagnostic() {
        JSONObject result = new JSONObject();
        try {
            Uri uri = Uri.parse(page.getUrl() == null ? "" : page.getUrl());
            result.put("featureVersion", FEATURE_VERSION).put("enabled", activity.getSharedPreferences("settings", 0).getBoolean("proWebEnabled", true));
            result.put("host", "chatgpt.com".equals(uri.getHost()) ? "chatgpt.com" : "other");
            result.put("pathKind", uri.getPath() != null && uri.getPath().startsWith("/c/") ? "conversation" : "new-or-login");
            var packageInfo = WebView.getCurrentWebViewPackage();
            result.put("webViewVersion", packageInfo == null ? "unknown" : packageInfo.versionName).put("androidApi", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {}
        return result;
    }

    private void evaluate(String command, java.util.function.Consumer<JSONObject> callback) {
        String script = adapter + "\n(function(){try{return JSON.stringify(MCProWeb." + command + ")}catch(error){return JSON.stringify({status:'web_changed',reason:error.name})}})()";
        page.evaluateJavascript(script, raw -> {
            try { callback.accept(new JSONObject(jsString(raw))); }
            catch (Exception error) { callback.accept(status("web_changed", "ChatGPT 웹 응답을 읽지 못했습니다.")); }
        });
    }

    private void retry(Runnable step, int attempt, int maximum, String status, String reason) {
        if (attempt >= maximum || expired()) finish(status(status, reason)); else page.postDelayed(step, 500);
    }
    private boolean live(long current) { return !destroyed && pending != null && generation == current; }
    private boolean expired() { return SystemClock.elapsedRealtime() > deadline; }
    private static String reason(JSONObject value) {
        String reason = value.optString("reason");
        return switch (reason) {
            case "sidebar_closing" -> "ChatGPT 사이드바가 닫히기를 기다리다 중단되었습니다.";
            case "sidebar_close_ambiguous" -> "ChatGPT 사이드바 닫기 버튼을 구분하지 못했습니다.";
            case "project_page_pending", "project_navigation_pending" -> "ChatGPT 프로젝트 화면이 준비되지 않았습니다.";
            case "project_missing" -> "mobile-codex-chat 프로젝트를 확인하지 못했습니다.";
            case "project_ambiguous" -> "mobile-codex-chat 프로젝트가 여러 개라 구분하지 못했습니다.";
            case "composer_blocked" -> "ChatGPT 입력창이 다른 창에 가려져 있습니다.";
            case "prompt_missing" -> "ChatGPT 메시지 입력창을 찾지 못했습니다.";
            case "model_trigger_missing" -> "ChatGPT 모델 선택 버튼을 찾지 못했습니다.";
            case "model_trigger_ambiguous" -> "ChatGPT 모델 선택 버튼을 구분하지 못했습니다.";
            case "project_dialog_ambiguous" -> "프로젝트 생성창이 여러 개 열려 있습니다. ChatGPT 화면을 확인해 주세요.";
            case "project_name_input_ambiguous" -> "프로젝트 이름 입력란을 구분하지 못했습니다. ChatGPT 화면을 확인해 주세요.";
            case "project_create_button_ambiguous" -> "프로젝트 만들기 버튼을 구분하지 못했습니다. ChatGPT 화면을 확인해 주세요.";
            default -> reason.isBlank() ? "ChatGPT 웹 화면의 준비 상태를 확인하지 못했습니다. (" + value.optString("status", "unknown") + ")" : reason;
        };
    }
    private JSONObject status(String value, String reason) {
        JSONObject result = new JSONObject();
        try { result.put("status", value).put("operationId", operationId).put("reason", reason).put("clicked", clicked)
            .put("chatConversationId", lastConversationId).put("chatConversationPath", lastConversationPath).put("chatProjectPath", resolvedProjectPath); }
        catch (Exception ignored) {} return result;
    }
    private void finish(JSONObject result) {
        Done done = pending; pending = null; generation++; clear(); if (done != null) done.complete(result, null);
    }
    void acknowledge(String completedOperationId) {
        String saved = activity.getSharedPreferences("pro-web-operation", 0).getString("value", "");
        try {
            if (!saved.isBlank() && completedOperationId.equals(new JSONObject(saved).optString("operationId")))
                activity.getSharedPreferences("pro-web-operation", 0).edit().remove("value").apply();
        } catch (Exception ignored) { }
    }
    private void journal() {
        try {
            JSONObject value = new JSONObject().put("operationId", operationId).put("threadId", localThreadId)
                .put("chatConversationId", lastConversationId).put("chatConversationPath", lastConversationPath).put("chatProjectPath", resolvedProjectPath);
            activity.getSharedPreferences("pro-web-operation", 0).edit().putString("value", value.toString()).commit();
        } catch (Exception ignored) { }
    }
    private void clear() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        waitingForChooser = false; uploads = new Uri[0]; operationId = ""; localThreadId = ""; prompt = ""; requestedConversationId = ""; requestedConversationPath = ""; requestedProjectPath = ""; resolvedProjectPath = "";
        clicked = false; stablePolls = 0; lastReply = ""; lastConversationId = ""; lastConversationPath = "";
    }
    void destroy() {
        if (pending != null) {
            JSONObject result = status(clicked ? "uncertain" : "not_sent", "GPT-6-Pro 웹 화면이 닫혔습니다.");
            finish(result);
        }
        destroyed = true; page.stopLoading(); page.destroy();
    }
    private static String safeId(String value) { return value != null && value.length() <= 200 && value.matches("[A-Za-z0-9_-]+") ? value : ""; }
    private static String safeProjectPath(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("/$", "");
        return normalized.matches("/g/g-p-[A-Za-z0-9_-]+/project|/projects/[A-Za-z0-9_-]+") ? normalized : "";
    }
    private static String conversationPathFor(String projectPath, String conversationId) {
        if (conversationId.isBlank()) return "";
        if (projectPath.matches("/g/g-p-[A-Za-z0-9_-]+/project")) return projectPath.substring(0, projectPath.length() - "/project".length()) + "/c/" + conversationId;
        if (projectPath.matches("/projects/[A-Za-z0-9_-]+")) return projectPath + "/c/" + conversationId;
        return "/c/" + conversationId;
    }
    private static String safeConversationPath(String value, String conversationId, String projectPath) {
        if (value == null || conversationId == null || conversationId.isBlank()) return "";
        String normalized = value.replaceAll("/$", "");
        if (normalized.equals("/c/" + conversationId)) return normalized;
        String scoped = conversationPathFor(projectPath, conversationId);
        return normalized.equals(scoped) ? normalized : "";
    }
    private static String jsString(String raw) {
        try { return new JSONArray("[" + raw + "]").optString(0, ""); }
        catch (Exception ignored) { return ""; }
    }
    private static String read(InputStream source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
