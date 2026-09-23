package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;

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
        setTitle("Chat 전송 실험");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
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
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout composer = new LinearLayout(this);
        input = new EditText(this); input.setSingleLine(false); input.setMinLines(1); input.setMaxLines(3);
        input.setHint("앱 입력창에서 보낼 테스트 메시지");
        input.setText("MC-NATIVE-" + System.currentTimeMillis());
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = new Button(this); sendButton.setText("보내기"); sendButton.setOnClickListener(v -> sendFromNativeInput());
        composer.addView(sendButton); root.addView(composer);
        requeryButton = new Button(this); requeryButton.setText("같은 대화 서버 재조회");
        requeryButton.setEnabled(false); requeryButton.setOnClickListener(v -> requeryConversation());
        root.addView(requeryButton);
        setContentView(root);
        stage("WebView 준비. 메시지 전송 전 로그인 상태를 확인하세요.");
        page.loadUrl("https://chatgpt.com/");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void stage(String description) {
        if (stages.length() > 2500) stages.delete(0, stages.length() - 2000);
        stages.append(System.currentTimeMillis()).append(" · ").append(description).append('\n');
        diagnostic.setText(stages.toString());
    }

    private void sendFromNativeInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { stage("입력 없음"); return; }
        String host = android.net.Uri.parse(page.getUrl() == null ? "" : page.getUrl()).getHost();
        if (!"chatgpt.com".equals(host)) { stage("ChatGPT 페이지에서 로그인 후 다시 시도해 주세요."); return; }
        submittedText = text;
        sendButton.setEnabled(false);
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
            } else if (attempt < 120) page.postDelayed(() -> pollAssistant(attempt + 1), 1000);
            else { waitingForAssistant = false; sendButton.setEnabled(true); stage("답변 표시 시간 초과; 화면을 직접 확인해 주세요."); }
        });
    }

    private static String javascriptString(String value) {
        try { return new JSONArray("[" + value + "]").optString(0, "unknown"); }
        catch (Exception ignored) { return "unknown"; }
    }

    private void requeryConversation() {
        String path = android.net.Uri.parse(page.getUrl() == null ? "" : page.getUrl()).getPath();
        String id = path != null && path.matches("/c/[0-9a-fA-F-]{36}") ? path.substring(3) : "";
        if (id.isEmpty()) { stage("대화 ID를 주소에서 찾지 못함; 공식 앱에서 직접 확인해 주세요."); return; }
        stage("같은 일반 Chat 대화 서버 재조회 시작");
        requeryButton.setEnabled(false);
        new Thread(() -> {
            String outcome = verifyConversation(id, submittedText);
            runOnUiThread(() -> { stage(outcome); requeryButton.setEnabled(true); });
        }, "chat-web-requery").start();
    }

    private String verifyConversation(String id, String expectedText) {
        HttpURLConnection connection = null;
        try {
            FileHome home = new FileHome();
            JSONObject auth = new JSONObject(Files.readString(home.authFile.toPath(), StandardCharsets.UTF_8));
            String token = auth.getJSONObject("tokens").getString("access_token");
            URL url = new URL("https://chatgpt.com/backend-api/conversation/" + id);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) return "재조회 HTTP " + status + "; 계정 일치 또는 인증 상태 확인 필요";
            JSONObject conversation;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 8_000_000) return "재조회 응답이 너무 큼";
                    bytes.write(buffer, 0, count);
                }
                conversation = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            JSONObject mapping = conversation.optJSONObject("mapping");
            String matchingUserNode = "";
            if (mapping != null) for (String key : mapping.keySet()) {
                JSONObject node = mapping.optJSONObject(key), message = node == null ? null : node.optJSONObject("message");
                if (message == null) continue;
                String role = message.optJSONObject("author") == null ? "" : message.optJSONObject("author").optString("role");
                if ("user".equals(role)) {
                    JSONArray parts = message.optJSONObject("content") == null ? null : message.optJSONObject("content").optJSONArray("parts");
                    if (parts != null) for (int n = 0; n < parts.length(); n++)
                        if (expectedText.equals(parts.optString(n))) matchingUserNode = key;
                }
            }
            boolean answerLinked = false;
            String answerModel = "";
            if (mapping != null && !matchingUserNode.isEmpty()) for (String key : mapping.keySet()) {
                JSONObject node = mapping.optJSONObject(key), message = node == null ? null : node.optJSONObject("message");
                if (message == null || message.optJSONObject("author") == null
                    || !"assistant".equals(message.optJSONObject("author").optString("role"))
                    || !"finished_successfully".equals(message.optString("status"))) continue;
                String parent = node.optString("parent");
                HashSet<String> visited = new HashSet<>();
                while (!parent.isEmpty() && visited.add(parent)) {
                    if (matchingUserNode.equals(parent)) {
                        answerLinked = true;
                        JSONObject metadata = message.optJSONObject("metadata");
                        answerModel = metadata == null ? "" : metadata.optString("resolved_model_slug", metadata.optString("model_slug"));
                        break;
                    }
                    JSONObject previous = mapping.optJSONObject(parent);
                    parent = previous == null ? "" : previous.optString("parent");
                }
                if (answerLinked) break;
            }
            return "같은 대화 재조회: 테스트 메시지 " + (matchingUserNode.isEmpty() ? "없음" : "있음")
                + ", 그 메시지에 연결된 완료 답변 " + (answerLinked ? "있음" : "없음")
                + (answerModel.isEmpty() ? "" : ", 모델 " + answerModel + (answerModel.endsWith("-wm") ? " (Work)" : ""));
        } catch (Exception error) {
            return "재조회 도구 오류: " + error.getClass().getSimpleName();
        } finally { if (connection != null) connection.disconnect(); }
    }

    private final class FileHome {
        final java.io.File authFile;
        FileHome() throws java.io.IOException { authFile = CodexHome.open(ChatWebProbeActivity.this).child("auth.json"); }
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
