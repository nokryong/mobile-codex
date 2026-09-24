package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import static dev.mobilecodex.app.core.Texts.t;

/** Visible official login/verification page. Chat messages use the main app composer. */
public final class ChatWebLoginActivity extends Activity {
    private WebView page;
    private LinearLayout errorRow;
    private ProgressBar progress;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLanguage.initialize(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setTitle(t("ChatGPT 로그인"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(4), dp(12), dp(4));
        TextView title = new TextView(this);
        title.setText(t("ChatGPT 로그인")); title.setTextSize(18); title.setTextColor(Color.BLACK);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = new Button(this);
        done.setText(t("Chat으로 돌아가기")); done.setMinHeight(dp(48));
        done.setOnClickListener(view -> finish());
        toolbar.addView(done);
        root.addView(toolbar);
        TextView note = new TextView(this);
        note.setText(t("공식 ChatGPT 페이지에서 로그인과 인증을 완료한 뒤 Chat으로 돌아가세요. Codex 로그인과 별도로 연결됩니다."));
        note.setTextColor(Color.DKGRAY); note.setTextSize(13);
        note.setPadding(dp(16), 0, dp(16), dp(12));
        root.addView(note);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        errorRow = new LinearLayout(this); errorRow.setGravity(Gravity.CENTER_VERTICAL);
        errorRow.setPadding(dp(16), dp(4), dp(12), dp(4));
        TextView error = new TextView(this);
        error.setText(t("페이지를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요."));
        errorRow.addView(error, new LinearLayout.LayoutParams(0, -2, 1));
        Button retry = new Button(this); retry.setText(t("다시 시도"));
        retry.setOnClickListener(view -> page.reload()); errorRow.addView(retry);
        errorRow.setVisibility(View.GONE); root.addView(errorRow);
        page = new WebView(this);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true);
        // Keep the official login session in WebView's private cookie store. No Native bridge.
        page.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https".equals(request.getUrl().getScheme());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                errorRow.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) errorRow.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) errorRow.setVisibility(View.VISIBLE);
            }
        });
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); ViewCompat.requestApplyInsets(root);
        page.loadUrl("https://chatgpt.com/");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (page.canGoBack()) page.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (page != null) { page.stopLoading(); page.destroy(); }
        super.onDestroy();
    }
}
