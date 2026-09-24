package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-device experiment: user-authenticated ChatGPT web page with a Mobile Codex input. */
public final class ChatWebProbeActivity extends Activity {
    private WebView page;
    private EditText input;
    private TextView diagnostic;
    private Button sendButton, requeryButton;
    private final StringBuilder stages = new StringBuilder();
    private String submittedText = "";
    private long beforeAssistantCount = -1;
    private boolean waitingForAssistant;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setTitle("Chat 전송 실험");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int gap = dp(8);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(gap + safe.left, gap + safe.top, gap + safe.right, gap + safe.bottom);
            return insets;
        });
        TextView note = new TextView(this);
        note.setText("공식 ChatGPT 웹을 앱 안에서 엽니다. 로그인·검증을 완료하고 일반 Chat 모델을 선택한 뒤 앱 입력창으로 보내 주세요. 이 화면은 전송 경로 시험용입니다.");
        root.addView(note);
        diagnostic = new TextView(this); diagnostic.setTextIsSelectable(true); diagnostic.setMaxLines(5);
        root.addView(diagnostic);
        page = new WebView(this);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true);
        // Remote web content never gets a JavaScriptInterface or access to the packaged app UI.
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("https".equals(request.getUrl().getScheme())) return false;
                stage("외부 주소 필요: 이 WebView에서는 열지 않음");
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                String host = android.net.Uri.parse(url).getHost();
                stage("페이지 표시: " + (host == null ? "알 수 없는 호스트" : host));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) stage("페이지 오류 코드: " + error.getErrorCode());
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) stage("페이지 HTTP 상태: " + response.getStatusCode());
            }
        });
        requeryButton = new Button(this); requeryButton.setText("같은 대화 서버 재조회");
        requeryButton.setEnabled(false); requeryButton.setOnClickListener(v -> requeryConversation());
        root.addView(requeryButton);
        Button inspectModel = new Button(this); inspectModel.setText("모델 UI 검사");
        inspectModel.setOnClickListener(v -> page.evaluateJavascript(
            "(function(){const b=[...document.querySelectorAll('button')].find(e=>/^(Instant|Medium|High|X-High|Pro)$/.test((e.textContent||'').trim()));"
                + "if(b&&b.getAttribute('aria-expanded')!=='true')b.click();"
                + "setTimeout(()=>{const s=document.querySelector('[role=slider],input[type=range],[aria-valuenow]');"
                + "window.__mcModelInspection=JSON.stringify({button:b?.outerHTML.slice(0,700),slider:s?.outerHTML.slice(0,1000),parent:s?.parentElement?.outerHTML.slice(0,1100),"
                + "levels:[...document.querySelectorAll('[role=menuitem],button')].filter(e=>/^(Instant|Medium|High|X-High|Pro)$/.test((e.textContent||'').trim())).map(e=>e.outerHTML.slice(0,250))})},350);return 'started'})()",
            raw -> page.postDelayed(() -> page.evaluateJavascript("window.__mcModelInspection||'pending'",
                inspected -> stage("모델 UI: " + javascriptString(inspected))), 500)));
        root.addView(inspectModel);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout composer = new LinearLayout(this);
        input = new EditText(this); input.setSingleLine(false); input.setMinLines(1); input.setMaxLines(3);
        input.setHint("앱 입력창에서 보낼 테스트 메시지");
        input.setText("MC-NATIVE-" + System.currentTimeMillis());
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = new Button(this); sendButton.setText("보내기"); sendButton.setOnClickListener(v -> sendFromNativeInput());
        composer.addView(sendButton); root.addView(composer);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        stage("WebView 준비. 메시지 전송 전 로그인 상태를 확인하세요.");
        page.loadUrl("https://chatgpt.com/");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void stage(String description) {
        if (stages.length() > 2500) stages.delete(0, stages.length() - 2000);
        stages.append(System.currentTimeMillis()).append(" · ").append(description).append('\n');
        String[] lines = stages.toString().split("\n");
        StringBuilder recent = new StringBuilder();
        for (int i = Math.max(0, lines.length - 5); i < lines.length; i++)
            recent.append(lines[i]).append('\n');
        diagnostic.setText(recent.toString());
    }

    private void sendFromNativeInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { stage("입력 없음"); return; }
        String host = android.net.Uri.parse(page.getUrl() == null ? "" : page.getUrl()).getHost();
        if (!"chatgpt.com".equals(host)) { stage("ChatGPT 페이지에서 로그인 후 다시 시도해 주세요."); return; }
        submittedText = text;
        sendButton.setEnabled(false);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        stage("앱 입력 접수; 웹 입력 요소 검사 시작");
        page.evaluateJavascript("document.querySelectorAll('[data-message-author-role=\"assistant\"]').length",
            value -> {
                try { beforeAssistantCount = Long.parseLong(value); }
                catch (Exception ignored) { beforeAssistantCount = -1; }
                insertIntoWebComposer(text);
            });
    }

    private void insertIntoWebComposer(String text) {
        String script = "(function(){const e=document.querySelector('#prompt-textarea,[data-testid=\\\"prompt-textarea\\\"]');"
            + "if(!e)return 'composer_missing';if((e.value||e.textContent||'').trim())return 'composer_not_empty';e.focus();"
            + "const ok=document.execCommand('insertText',false," + JSONObject.quote(text) + ");"
            + "return ok&&(e.value||e.textContent||'').trim()?'text_inserted':'insert_failed'})()";
        page.evaluateJavascript(script, result -> {
            String outcome = javascriptString(result);
            if (!"text_inserted".equals(outcome)) { stage("웹 입력 실패: " + outcome); sendButton.setEnabled(true); return; }
            stage("웹 입력 요소에 텍스트 삽입됨; 전송 버튼 확인 중");
            page.postDelayed(() -> clickWebSend(0), 200);
        });
    }

    private void clickWebSend(int attempt) {
        String script = "(function(){const b=document.querySelector('#composer-submit-button,[data-testid=\\\"send-button\\\"]');"
            + "if(!b)return 'send_button_missing';if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'send_button_disabled';"
            + "b.click();return 'send_clicked'})()";
        page.evaluateJavascript(script, result -> {
            String outcome = javascriptString(result);
            if ("send_button_disabled".equals(outcome) && attempt < 15) { page.postDelayed(() -> clickWebSend(attempt + 1), 200); return; }
            if (!"send_clicked".equals(outcome)) { stage("웹 전송 클릭 실패: " + outcome); sendButton.setEnabled(true); return; }
            stage("웹 전송 버튼 클릭됨; HTTP 전송 성공은 아직 미확인");
            input.setText("");
            waitingForAssistant = true;
            pollAssistant(0);
        });
    }

    private void pollAssistant(int attempt) {
        if (!waitingForAssistant || isFinishing()) return;
        page.evaluateJavascript("document.querySelectorAll('[data-message-author-role=\"assistant\"]').length", result -> {
            long count;
            try { count = Long.parseLong(result); } catch (Exception ignored) { count = -1; }
            if (beforeAssistantCount >= 0 && count > beforeAssistantCount) {
                waitingForAssistant = false;
                stage("앱 WebView에 새 답변 표시 확인");
                requeryButton.setEnabled(true);
                sendButton.setEnabled(true);
                page.postDelayed(this::requeryConversation, 500);
            } else if (attempt < 120) page.postDelayed(() -> pollAssistant(attempt + 1), 1000);
            else { waitingForAssistant = false; sendButton.setEnabled(true); stage("답변 표시 시간 초과; 화면을 직접 확인해 주세요."); }
        });
    }

    private static String javascriptString(String value) {
        try { return new JSONArray("[" + value + "]").optString(0, "unknown"); }
        catch (Exception ignored) { return "unknown"; }
    }

    private void requeryConversation() {
        android.net.Uri pageUri = android.net.Uri.parse(page.getUrl() == null ? "" : page.getUrl());
        if (!"chatgpt.com".equals(pageUri.getHost())) { stage("ChatGPT 페이지가 아니어서 재조회할 수 없음"); return; }
        String path = pageUri.getPath();
        String id = path != null && path.matches("/c/[0-9a-fA-F-]{36}") ? path.substring(3) : "";
        if (id.isEmpty()) { stage("대화 ID를 주소에서 찾지 못함; 공식 앱에서 직접 확인해 주세요."); return; }
        stage("같은 대화 서버 재조회 시작");
        requeryButton.setEnabled(false);
        String script = "(function(){window.__mcProbeResult='pending';"
            + "fetch('/api/auth/session',{credentials:'include',headers:{Accept:'application/json'}})"
            + ".then(async sessionResponse=>{if(!sessionResponse.ok){window.__mcProbeResult='세션 HTTP '+sessionResponse.status;return;}"
            + "const session=await sessionResponse.json(),token=session.accessToken;"
            + "if(typeof token!=='string'||!token){window.__mcProbeResult='웹 세션 인증 정보 없음';return;}"
            + "const r=await fetch('/backend-api/conversation/" + id + "',{credentials:'include',headers:{Authorization:'Bearer '+token,Accept:'application/json'}});"
            + "if(!r.ok){window.__mcProbeResult='재조회 HTTP '+r.status;return;}"
            + "const data=await r.json(),mapping=data.mapping||{},nodes=Object.entries(mapping),expected=" + JSONObject.quote(submittedText) + ";"
            + "let match='';for(const [key,node] of nodes){const m=node&&node.message,c=m&&m.content,parts=c&&c.parts;"
            + "if(m&&m.author&&m.author.role==='user'&&Array.isArray(parts)&&parts.some(p=>p===expected)){match=key;break;}}"
            + "let linked=false,model='';if(match)for(const [key,node] of nodes){const m=node&&node.message;"
            + "if(!m||!m.author||m.author.role!=='assistant'||m.status!=='finished_successfully')continue;"
            + "let parent=node.parent;const seen=new Set();while(parent&&!seen.has(parent)){seen.add(parent);"
            + "if(parent===match){linked=true;const meta=m.metadata||{};model=meta.resolved_model_slug||meta.model_slug||'';break;}"
            + "parent=mapping[parent]&&mapping[parent].parent;}if(linked)break;}"
            + "window.__mcProbeResult='같은 대화 재조회: 테스트 메시지 '+(match?'있음':'없음')"
            + "+', 연결된 완료 답변 '+(linked?'있음':'없음')+(model?', 모델 '+model+(model.endsWith('-wm')?' (Work)':''):'');})"
            + ".catch(e=>{window.__mcProbeResult='재조회 오류: '+e.name;});return 'started';})()";
        page.evaluateJavascript(script, value -> pollRequery(0));
    }

    private void pollRequery(int attempt) {
        if (isFinishing()) return;
        page.evaluateJavascript("window.__mcProbeResult || 'pending'", value -> {
            String result = javascriptString(value);
            if ("pending".equals(result) && attempt < 50) {
                page.postDelayed(() -> pollRequery(attempt + 1), 300);
                return;
            }
            stage("pending".equals(result) ? "재조회 시간 초과" : result);
            requeryButton.setEnabled(true);
        });
    }

    @Override public void onBackPressed() {
        if (page.canGoBack()) page.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        waitingForAssistant = false;
        page.stopLoading(); page.destroy();
        super.onDestroy();
    }
}
