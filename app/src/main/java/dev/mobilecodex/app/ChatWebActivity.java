package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.core.view.WindowCompat;
import java.nio.charset.StandardCharsets;
import static dev.mobilecodex.app.core.Texts.t;

/** Visible official ChatGPT client. No app JavaScript interface or copied credentials. */
public final class ChatWebActivity extends Activity {
    private WebView page;
    private LinearLayout fallback;
    private TextView status;
    private ProgressBar progress;
    private String customScript;
    private String iconScript;
    private boolean loadFailed;
    private ValueCallback<Uri[]> fileCallback;
    private static final int CHOOSE_FILE = 61;

    static boolean official(Uri uri) {
        return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost()) && uri.getUserInfo() == null;
    }
    static boolean modeLink(Uri uri) { return "mobilecodex://mode/codex".equals(uri.toString()); }
    static boolean internal(Uri uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
            && ("chatgpt.com".equals(uri.getHost()) || "auth.openai.com".equals(uri.getHost())
            || "auth0.openai.com".equals(uri.getHost()) || "accounts.google.com".equals(uri.getHost())
            || "appleid.apple.com".equals(uri.getHost()) || "login.live.com".equals(uri.getHost())
            || "login.microsoftonline.com".equals(uri.getHost()));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        AppLanguage.initialize(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        try (var ui = getAssets().open("chat-web-custom.js"); var icons = getAssets().open("chat-icon-renderer.js")) {
            customScript = readAsset(ui);
            iconScript = readAsset(icons);
        } catch (Exception error) { throw new IllegalStateException("Chat 화면 코드를 읽지 못했습니다.", error); }
        SafeWebViewLayout safe = new SafeWebViewLayout(this);
        FrameLayout content = new FrameLayout(this);
        safe.addView(content, new FrameLayout.LayoutParams(-1, -1));
        fallback = new LinearLayout(this); fallback.setGravity(Gravity.CENTER); fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setBackgroundColor(Color.WHITE);
        fallback.setVisibility(View.GONE);
        status = new TextView(this); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        fallback.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout errorActions = new LinearLayout(this); errorActions.setGravity(Gravity.CENTER);
        Button back = new Button(this); back.setText("Codex"); back.setOnClickListener(v -> returnToCodex());
        errorActions.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        Button retry = new Button(this); retry.setText(t("새로고침")); retry.setOnClickListener(v -> page.reload());
        errorActions.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
        fallback.addView(errorActions, new LinearLayout.LayoutParams(-1, -2));
        page = new WebView(this);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(page, true);
        ChatIconStore iconStore = new ChatIconStore(this);
        page.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return iconStore.intercept(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (modeLink(uri)) {
                    if (request.isForMainFrame() && official(Uri.parse(view.getUrl() == null ? "" : view.getUrl()))) returnToCodex();
                    return true;
                }
                if (internal(uri)) return false;
                if (request.isForMainFrame() && MainActivity.isBrowserUri(uri, false)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception error) { Toast.makeText(ChatWebActivity.this, t("링크를 열지 못했습니다."), Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false; fallback.setVisibility(View.GONE); progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (official(Uri.parse(url))) inject();
                if (!loadFailed) progress.setVisibility(View.GONE);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                Uri uri = Uri.parse(url);
                if (official(uri) && ("/".equals(uri.getPath()) || uri.getPath().matches("/(?:g/[^/]+/)?c/[a-zA-Z0-9-]+")))
                    getPreferences(0).edit().putString("last-page", "https://chatgpt.com" + uri.getPath()).apply();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showLoadError();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) showLoadError();
            }
        });
        page.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!official(Uri.parse(view.getUrl() == null ? "" : view.getUrl()))) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), CHOOSE_FILE); }
                catch (Exception error) { fileCallback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
        });
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this);
        progress.setContentDescription(t("ChatGPT 불러오는 중"));
        content.addView(progress, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        content.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        setContentView(safe);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        if (saved == null || page.restoreState(saved) == null) {
            String url = getPreferences(0).getString("last-page", "https://chatgpt.com/");
            page.loadUrl(official(Uri.parse(url)) ? url : "https://chatgpt.com/");
        }
    }
    private void inject() {
        if (page == null || !official(Uri.parse(page.getUrl() == null ? "" : page.getUrl()))) return;
        page.evaluateJavascript(customScript, null);
        page.evaluateJavascript(iconScript, null);
    }
    private void showLoadError() { loadFailed = true; progress.setVisibility(View.GONE); fallback.setVisibility(View.VISIBLE); status.setText(t("연결을 확인하고 새로고침해 주세요.")); }
    private void returnToCodex() { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private void back() {
        page.evaluateJavascript("!!(window.__mcChatCustom && window.__mcChatCustom.closeSidebar())", handled -> {
            if (isDestroyed() || "true".equals(handled)) return;
            if (page.canGoBack()) page.goBack(); else returnToCodex();
        });
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onResume() { super.onResume(); inject(); }
    @Override protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { page.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CHOOSE_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null;
        }
    }
    @Override protected void onDestroy() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (page != null) { page.stopLoading(); page.destroy(); }
        super.onDestroy();
    }
    private static String readAsset(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int size;
        while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
