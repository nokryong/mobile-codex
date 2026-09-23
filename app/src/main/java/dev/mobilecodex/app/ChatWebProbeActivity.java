package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import java.util.ArrayList;

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
    private volatile boolean recordingProtocol;
    private final ArrayList<String> nativeRequestPaths = new ArrayList<>();
    private TextView protocolDetails;

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
        protocolDetails = new TextView(this); protocolDetails.setTextIsSelectable(true); protocolDetails.setMaxLines(12);
        protocolDetails.setText("전송 전에는 메서드·경로·본문 구조 진단이 비어 있습니다.");
        root.addView(protocolDetails);
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
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (recordingProtocol && "chatgpt.com".equals(request.getUrl().getHost())
                    && "POST".equalsIgnoreCase(request.getMethod())) {
                    String path = request.getUrl().getPath();
                    if (path != null && path.startsWith("/backend-api/")) synchronized (nativeRequestPaths) {
                        if (nativeRequestPaths.size() < 20) nativeRequestPaths.add("POST " + safePath(path));
                    }
                }
                return null;
            }
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
        Button inspectButton = new Button(this); inspectButton.setText("전송 경로 진단 갱신");
        inspectButton.setOnClickListener(v -> displayProtocol()); root.addView(inspectButton);
        Button copyButton = new Button(this); copyButton.setText("비밀값 제외 진단 복사");
        copyButton.setOnClickListener(v -> displayProtocol(true)); root.addView(copyButton);
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

    private static String safePath(String path) {
        return path.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", ":id")
            .replaceAll("/[A-Za-z0-9_-]{32,}(?=/|$)", "/:id")
            .replaceAll("[^/]+@[^/]+", ":account");
    }

    private static String protocolCaptureScript() {
        return "(function(){if(window.__mcProtocol)return 'ready';window.__mcProtocol=[];"
            + "const shape=(v,d=0)=>{if(d>3)return Array.isArray(v)?'array':typeof v;"
            + "if(v instanceof FormData)return Object.fromEntries([...v.keys()].slice(0,25).map(k=>[k,'form-field']));"
            + "if(v instanceof URLSearchParams)return Object.fromEntries([...v.keys()].slice(0,25).map(k=>[k,'parameter']));"
            + "if(Array.isArray(v))return v.length?[shape(v[0],d+1)]:[];"
            + "if(v&&typeof v==='object'){const o={};for(const k of Object.keys(v).slice(0,25)){const value=v[k],name=/^[A-Za-z_][A-Za-z0-9_-]{0,63}$/.test(k)?k:'[other-field]';"
            + "o[name]=/token|cookie|password|secret|proof|captcha|arkose|authorization/i.test(k)?'[redacted]':"
            + "(/^(action|model|role|content_type|recipient|conversation_mode)$/.test(k)&&typeof value==='string'&&/^[A-Za-z0-9_.-]{1,80}$/.test(value))?value:"
            + "(k==='stream'&&typeof value==='boolean')?value:shape(value,d+1);}return o;}return typeof v;};"
            + "const bodyShape=b=>{if(b==null)return 'none';if(typeof b==='string'){try{return shape(JSON.parse(b));}catch{return 'string';}}return shape(b);};"
            + "const headerNames=h=>{try{return [...new Headers(h||{}).keys()].slice(0,30);}catch{return [];}};"
            + "const path=u=>u.pathname.replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/ig,':id').replace(/\\/[A-Za-z0-9_-]{32,}(?=\\/|$)/g,'/:id').replace(/[^/]+@[^/]+/g,':account');"
            + "const add=(method,url,body)=>{try{const u=new URL(url,location.href);if(u.host!==location.host||method==='GET'||!u.pathname.startsWith('/backend-api/'))return null;"
            + "const r={method,path:path(u),body:bodyShape(body),headers:[],status:'pending'};window.__mcProtocol.push(r);if(window.__mcProtocol.length>20)window.__mcProtocol.shift();return r;}catch{return null;}};"
            + "const fetch0=window.fetch;window.fetch=function(input,init){const method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();"
            + "const url=typeof input==='string'?input:(input&&input.url)||'';const body=init&&init.body;const r=add(method,url,body);if(r)r.headers=headerNames((init&&init.headers)||(input&&input.headers));"
            + "if(r&&body==null&&input instanceof Request)input.clone().text().then(s=>{r.body=bodyShape(s);}).catch(()=>{});"
            + "const p=fetch0.apply(this,arguments);return r?p.then(v=>{r.status=v.status;r.contentType=(v.headers&&v.headers.get('content-type')||'').split(';')[0];r.requestId=v.headers&&v.headers.get('x-request-id')||'';"
            + "if(!v.ok)v.clone().json().then(j=>{const code=String((j.error&&j.error.code)||j.code||'');r.errorCode=/^[A-Za-z0-9_.-]{0,80}$/.test(code)?code:'other';}).catch(()=>{});return v;},e=>{r.status=e.name||'error';throw e;}):p;};"
            + "const open0=XMLHttpRequest.prototype.open,send0=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(method,url){this.__mcMethod=String(method).toUpperCase();this.__mcUrl=url;return open0.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(body){const r=add(this.__mcMethod||'GET',this.__mcUrl||'',body);if(r)this.addEventListener('loadend',()=>{r.status=this.status;r.contentType=(this.getResponseHeader('content-type')||'').split(';')[0];r.requestId=this.getResponseHeader('x-request-id')||'';},{once:true});return send0.apply(this,arguments);};"
            + "return 'armed';})()";
    }

    private void armProtocol(Runnable next) {
        synchronized (nativeRequestPaths) { nativeRequestPaths.clear(); }
        recordingProtocol = true;
        page.evaluateJavascript(protocolCaptureScript(), value -> {
            stage("전송 경로 진단: " + javascriptString(value));
            next.run();
        });
    }

    private void displayProtocol() { displayProtocol(false); }

    private void displayProtocol(boolean copy) {
        page.evaluateJavascript("JSON.stringify(window.__mcProtocol||[])", value -> {
            String raw = javascriptString(value);
            StringBuilder shown = new StringBuilder("웹 요청 구조 (값 제외)\n");
            try {
                JSONArray entries = new JSONArray(raw);
                for (int i = 0; i < Math.min(entries.length(), 12); i++) {
                    JSONObject item = entries.optJSONObject(i);
                    if (item != null) shown.append(item.optString("method")).append(' ').append(item.optString("path"))
                        .append(" · HTTP ").append(item.optString("status")).append(" · fields=")
                        .append(item.opt("body")).append(" · headers=").append(item.opt("headers"))
                        .append(" · type=").append(item.optString("contentType"))
                        .append(" · error=").append(item.optString("errorCode"))
                        .append(" · requestId=").append(item.optString("requestId")).append('\n');
                }
            } catch (Exception ignored) { shown.append("웹 진단을 읽지 못했습니다.\n"); }
            synchronized (nativeRequestPaths) {
                shown.append("Android 관측 경로\n");
                for (String request : nativeRequestPaths) shown.append(request).append('\n');
            }
            protocolDetails.setText(shown.toString());
            if (copy) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Chat 전송 진단", shown.toString()));
                stage("비밀값 제외 진단을 복사했습니다.");
            }
        });
    }

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
                armProtocol(() -> insertIntoWebComposer(text));
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
            if (!"send_clicked".equals(outcome)) { stage("웹 전송 클릭 실패: " + outcome); displayProtocol(); sendButton.setEnabled(true); return; }
            stage("웹 전송 버튼 클릭됨; HTTP 전송 성공은 아직 미확인");
            page.postDelayed(this::displayProtocol, 2500);
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
                displayProtocol();
                requeryButton.setEnabled(true);
                sendButton.setEnabled(true);
                page.postDelayed(this::requeryConversation, 500);
            } else if (attempt < 120) page.postDelayed(() -> pollAssistant(attempt + 1), 1000);
            else { waitingForAssistant = false; sendButton.setEnabled(true); stage("답변 표시 시간 초과; 화면을 직접 확인해 주세요."); displayProtocol(); }
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
