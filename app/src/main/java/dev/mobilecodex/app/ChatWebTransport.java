package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps the ordinary ChatGPT web session behind the packaged Mobile Codex UI. */
final class ChatWebTransport {
    interface Done { void complete(JSONObject result, Exception error); }

    private final Activity activity;
    private final WebView page;
    private Done pending;
    private String submittedText = "";
    private boolean clicked;
    private boolean inserting;
    private boolean pageLoaded;
    private boolean modelChanging;
    private long modelDeadline;
    private long deadline;
    private long sendStartedAt;
    private String lastDiagnostic = "";

    @SuppressLint("SetJavaScriptEnabled")
    ChatWebTransport(Activity activity, FrameLayout root) {
        this.activity = activity;
        page = new WebView(activity);
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
        // Remote content has no Android JavaScript interface. The packaged UI covers this view.
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"https".equals(uri.getScheme())
                    || !("chatgpt.com".equals(uri.getHost()) || "auth.openai.com".equals(uri.getHost()));
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = "chatgpt.com".equals(Uri.parse(url).getHost());
                if (pending != null && !clicked && !inserting) page.postDelayed(() -> insert(0), 350);
            }
        });
        root.addView(page, 0, new FrameLayout.LayoutParams(-1, -1));
        String saved = activity.getSharedPreferences("general-chat", 0).getString("url", "");
        page.loadUrl(conversationId(saved).isEmpty() ? "https://chatgpt.com/" : saved);
    }

    void reloadIfIdle() {
        if (pending == null) { pageLoaded = false; page.reload(); }
    }

    void newChat() {
        if (pending != null) throw new IllegalStateException("답변을 기다리는 중입니다.");
        activity.getSharedPreferences("general-chat", 0).edit().remove("url").apply();
        pageLoaded = false;
        page.loadUrl("https://chatgpt.com/");
    }

    private static final String[] MODEL_LEVELS = {"Instant", "Medium", "High", "X-High", "Pro"};

    private static int modelIndex(String level) {
        for (int index = 0; index < MODEL_LEVELS.length; index++)
            if (MODEL_LEVELS[index].equals(level)) return index;
        return -1;
    }

    private static String modelButtonScript() {
        return "[...document.querySelectorAll('button[aria-haspopup=menu]')]"
            + ".find(e=>/^(Instant|Medium|High|X-High|Pro)$/.test((e.textContent||'').trim()))";
    }

    void modelState(Done done) {
        if (pending != null || modelChanging) { done.complete(null, new IllegalStateException("ChatGPT 설정을 변경할 수 없는 상태입니다.")); return; }
        readModelState(done, 0);
    }

    private void readModelState(Done done, int attempt) {
        if (!pageLoaded) {
            if (attempt < 40) page.postDelayed(() -> readModelState(done, attempt + 1), 250);
            else done.complete(null, new IllegalStateException("ChatGPT 웹 화면을 불러오지 못했습니다. 로그인 상태를 확인해 주세요."));
            return;
        }
        page.evaluateJavascript("(function(){const b=" + modelButtonScript() + ";return b?(b.textContent||'').trim():''})()", raw -> {
            String level = jsString(raw);
            if (modelIndex(level) < 0) {
                if (attempt < 40) page.postDelayed(() -> readModelState(done, attempt + 1), 250);
                else done.complete(null, new IllegalStateException("ChatGPT 웹의 모델 설정을 읽지 못했습니다. 로그인 상태를 확인해 주세요."));
                return;
            }
            JSONObject result = new JSONObject();
            try { result.put("level", level); } catch (Exception ignored) {}
            done.complete(result, null);
        });
    }

    void selectModel(String level, Done done) {
        if (modelIndex(level) < 0) { done.complete(null, new IllegalArgumentException("지원하지 않는 Chat 모델 단계입니다.")); return; }
        if (pending != null || modelChanging) { done.complete(null, new IllegalStateException("ChatGPT 설정을 변경할 수 없는 상태입니다.")); return; }
        modelChanging = true;
        modelDeadline = System.currentTimeMillis() + 15_000;
        openModelSlider(level, done, 0, 0);
    }

    private void openModelSlider(String target, Done done, int attempt, int steps) {
        if (attempt >= 40 || System.currentTimeMillis() > modelDeadline) {
            finishModel(done, null, new IllegalStateException("ChatGPT 모델 슬라이더를 열지 못했습니다.")); return;
        }
        if (!pageLoaded) { page.postDelayed(() -> openModelSlider(target, done, attempt + 1, steps), 250); return; }
        String open = "(function(){const b=" + modelButtonScript() + ";"
            + "const s=document.querySelector('[role=slider],input[type=range]');"
            + "if(!b)return 'missing';if((b.textContent||'').trim()===" + JSONObject.quote(target) + ")return 'selected';"
            + "if(s)return 'slider';"
            + "if(b.getAttribute('aria-expanded')==='true'){"
            + "if(" + attempt + "===12)b.click();return 'opening';}b.click();return 'clicked'})()";
        page.evaluateJavascript(open, raw -> {
            String state = jsString(raw);
            if ("selected".equals(state)) { selectedModel(target, done); return; }
            if ("slider".equals(state)) { adjustModel(target, done, steps); return; }
            page.postDelayed(() -> openModelSlider(target, done, attempt + 1, steps), 250);
        });
    }

    private void adjustModel(String target, Done done, int steps) {
        if (steps > 8 || System.currentTimeMillis() > modelDeadline) {
            finishModel(done, null, new IllegalStateException("ChatGPT 모델 선택을 확인하지 못했습니다.")); return;
        }
        String inspect = "(function(){const s=document.querySelector('[role=slider],input[type=range]');"
            + "const b=" + modelButtonScript() + ";if(!s)return JSON.stringify({error:'slider_missing'});"
            + "return JSON.stringify({value:s.getAttribute('aria-valuetext')||s.getAttribute('aria-label')||'',"
            + "button:(b?.textContent||'').trim()})})()";
        page.evaluateJavascript(inspect, raw -> {
            JSONObject state;
            try { state = new JSONObject(jsString(raw)); }
            catch (Exception error) { finishModel(done, null, new IllegalStateException("ChatGPT 모델 선택 상태를 읽지 못했습니다.")); return; }
            if ("slider_missing".equals(state.optString("error"))) { openModelSlider(target, done, 0, steps); return; }
            String current = levelFromSlider(state.optString("value"));
            if (current.isEmpty()) current = state.optString("button");
            int currentIndex = modelIndex(current);
            if (currentIndex < 0) { finishModel(done, null, new IllegalStateException("현재 ChatGPT 모델 단계를 확인하지 못했습니다.")); return; }
            if (target.equals(current)) {
                selectedModel(target, done);
                return;
            }
            int key = modelIndex(target) > currentIndex ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            page.requestFocus();
            page.evaluateJavascript("(function(){const s=document.querySelector('[role=slider],input[type=range]');if(s)s.focus();return !!s})()", focused -> {
                if (!"true".equals(focused)) { openModelSlider(target, done, 0, steps); return; }
                page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                page.postDelayed(() -> adjustModel(target, done, steps + 1), 300);
            });
        });
    }

    private static String levelFromSlider(String value) {
        if (value == null) return "";
        for (String level : MODEL_LEVELS)
            if (value.startsWith(level)) return level;
        return "";
    }

    private void selectedModel(String level, Done done) {
        JSONObject result = new JSONObject();
        try { result.put("level", level); } catch (Exception ignored) {}
        finishModel(done, result, null);
    }

    private void finishModel(Done done, JSONObject result, Exception error) {
        page.evaluateJavascript("(function(){const b=" + modelButtonScript() + ";"
            + "if(b?.getAttribute('aria-expanded')==='true')b.click();return true})()", ignored -> {
            if (result != null) verifyModelSelection(result.optString("level"), done, 0);
            else { modelChanging = false; done.complete(null, error); }
        });
    }

    private void verifyModelSelection(String level, Done done, int attempt) {
        page.evaluateJavascript("(function(){const b=" + modelButtonScript() + ";return b?(b.textContent||'').trim():''})()", raw -> {
            if (level.equals(jsString(raw))) {
                JSONObject result = new JSONObject();
                try { result.put("level", level); } catch (Exception ignored) {}
                modelChanging = false;
                done.complete(result, null);
            } else if (attempt < 8 && System.currentTimeMillis() <= modelDeadline) {
                page.postDelayed(() -> verifyModelSelection(level, done, attempt + 1), 250);
            } else {
                modelChanging = false;
                done.complete(null, new IllegalStateException("ChatGPT 모델 변경이 적용됐는지 확인하지 못했습니다."));
            }
        });
    }

    void send(String text, Done done) {
        if (modelChanging) { done.complete(null, new IllegalStateException("ChatGPT 모델 설정을 변경하는 중입니다.")); return; }
        if (pending != null) { done.complete(null, new IllegalStateException("일반 Chat 답변을 기다리는 중입니다.")); return; }
        if (text == null || text.trim().isEmpty()) { done.complete(null, new IllegalArgumentException("메시지를 입력해 주세요.")); return; }
        pending = done;
        submittedText = text.trim();
        sendStartedAt = System.currentTimeMillis() / 1000;
        lastDiagnostic = "";
        clicked = false;
        inserting = false;
        deadline = System.currentTimeMillis() + 600_000;
        insert(0);
    }

    private void insert(int attempt) {
        if (pending == null || clicked || inserting) return;
        if (expired()) { finish(null, new IllegalStateException("ChatGPT 입력창을 찾지 못했습니다. 웹 로그인 상태를 확인해 주세요.")); return; }
        String host = Uri.parse(page.getUrl() == null ? "" : page.getUrl()).getHost();
        if (!"chatgpt.com".equals(host) || !pageLoaded) { retry(() -> insert(attempt + 1), attempt, 40, "ChatGPT 로그인이 필요합니다."); return; }
        String script = "(function(){" + sendObserverScript()
            + "const e=document.querySelector('#prompt-textarea,[data-testid=\"prompt-textarea\"]');"
            + "if(!e)return 'missing';if((e.value||e.textContent||'').trim())return 'not_empty';e.focus();"
            + "const ok=document.execCommand('insertText',false," + JSONObject.quote(submittedText) + ");"
            + "return ok&&(e.value||e.textContent||'').trim()?'inserted':'failed'})()";
        inserting = true;
        page.evaluateJavascript(script, raw -> {
            inserting = false;
            if (pending == null || clicked) return;
            String outcome = jsString(raw);
            if ("inserted".equals(outcome)) { page.postDelayed(() -> click(0), 200); return; }
            if ("missing".equals(outcome)) { retry(() -> insert(attempt + 1), attempt, 40, "ChatGPT 웹 입력창을 찾지 못했습니다. 웹 로그인/모델 설정을 열어 확인해 주세요."); return; }
            finish(null, new IllegalStateException("웹 입력 실패: " + outcome));
        });
    }

    /** Observes only the official client's request outcome and conversation ID, never credentials or proofs. */
    static String sendObserverScript() {
        return "window.__mcChatObserved={id:'',sendStatus:0,prepareStatus:0,active:false};"
            + "if(!window.__mcChatFetchObserved){const original=window.fetch;"
            + "window.fetch=function(input,init){const result=original.apply(this,arguments);try{"
            + "const u=new URL(typeof input==='string'?input:input.url,location.href);"
            + "const method=(init&&init.method)||(input&&input.method)||'GET';"
            + "if(u.origin===location.origin&&method.toUpperCase()==='POST'"
            + "&&(u.pathname==='/backend-api/f/conversation'||u.pathname==='/backend-api/f/conversation/prepare')){"
            + "const observed=window.__mcChatObserved,prepare=u.pathname.endsWith('/prepare');"
            + "if(!observed||!observed.active)return result;"
            + "const record=body=>{try{const id=JSON.parse(body).conversation_id;"
            + "if(typeof id==='string'&&/^[0-9a-fA-F-]{36}$/.test(id))observed.id=id;}catch(e){}};"
            + "if(init&&typeof init.body==='string')record(init.body);"
            + "else if(typeof Request!=='undefined'&&input instanceof Request)input.clone().text().then(record).catch(()=>{});"
            + "result.then(r=>{if(prepare)observed.prepareStatus=r.status;else observed.sendStatus=r.status;}).catch(()=>{});"
            + "}}catch(e){}return result;};window.__mcChatFetchObserved=true;}";
    }

    private void click(int attempt) {
        if (pending == null || clicked) return;
        String script = "(function(){const b=document.querySelector('#composer-submit-button,[data-testid=\"send-button\"]');"
            + "if(!b)return 'missing';if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'disabled';"
            + "if(window.__mcChatObserved)window.__mcChatObserved.active=true;b.click();return 'clicked'})()";
        page.evaluateJavascript(script, raw -> {
            if (pending == null || clicked) return;
            String outcome = jsString(raw);
            if ("clicked".equals(outcome)) { clicked = true; page.postDelayed(() -> requery(0), 700); return; }
            if ("disabled".equals(outcome) || "missing".equals(outcome)) { retry(() -> click(attempt + 1), attempt, 30, "ChatGPT 전송 버튼을 누르지 못했습니다."); return; }
            finish(null, new IllegalStateException("웹 전송 실패: " + outcome));
        });
    }

    private void requery(int attempt) {
        if (pending == null) return;
        if (expired()) { finish(null, new IllegalStateException("전송 후 답변 저장을 확인하지 못했습니다. 같은 메시지를 다시 보내기 전에 웹 대화를 확인해 주세요.")); return; }
        String inspect = "(function(){const observed=window.__mcChatObserved||{};return JSON.stringify({path:location.pathname,"
            + "observedId:observed.id||'',sendStatus:observed.sendStatus||0,prepareStatus:observed.prepareStatus||0,"
            + "visibility:document.visibilityState,ready:document.readyState,"
            + "users:document.querySelectorAll('[data-message-author-role=user]').length,"
            + "assistants:document.querySelectorAll('[data-message-author-role=assistant]').length,"
            + "composer:!!document.querySelector('#prompt-textarea'),"
            + "network:performance.getEntriesByType('resource').filter(x=>x.name.includes('/backend-api/conversation')).slice(-3)"
            + ".map(x=>({kind:new URL(x.name).pathname.includes('/conversation/')?'read':'send',status:x.responseStatus||0}))})})()";
        page.evaluateJavascript(inspect, raw -> {
            if (pending == null) return;
            JSONObject evidence;
            try { evidence = new JSONObject(jsString(raw)); }
            catch (Exception ignored) { evidence = new JSONObject(); }
            String path = evidence.optString("path", "");
            String id = conversationId("https://chatgpt.com" + path);
            if (id.isEmpty()) id = conversationId(page.getUrl());
            if (id.isEmpty()) id = conversationId("https://chatgpt.com/c/" + evidence.optString("observedId", ""));
            lastDiagnostic = "화면=" + (id.isEmpty() ? ("/".equals(path) ? "첫 화면" : "기타") : "대화")
                + ", 사용자=" + evidence.optInt("users", -1) + ", 답변=" + evidence.optInt("assistants", -1)
                + ", 표시=" + evidence.optString("visibility", "?") + ", 준비=" + evidence.optString("ready", "?")
                + ", 입력창=" + evidence.optBoolean("composer", false)
                + ", 전송 HTTP=" + evidence.optInt("sendStatus", 0)
                + ", 대화 준비 HTTP=" + evidence.optInt("prepareStatus", 0)
                + ", 네트워크=" + evidence.optJSONArray("network");
            int sendStatus = evidence.optInt("sendStatus", 0);
            if (sendStatus >= 400) {
                finish(null, new IllegalStateException("일반 Chat 전송 HTTP " + sendStatus + " [" + lastDiagnostic + "]"));
                return;
            }
            // The official client can emit /f/conversation/prepare only after a slow model finishes.
            if (id.isEmpty()) { retry(() -> requery(attempt + 1), attempt, 1200, "대화 주소를 찾지 못했습니다."); return; }
            String script = requeryScript(id, submittedText, sendStartedAt);
            final String foundId = id;
            page.evaluateJavascript(script, ignored -> pollResult(attempt, 0, foundId));
        });
    }

    private void pollResult(int attempt, int polls, String id) {
        if (pending == null) return;
        page.evaluateJavascript("JSON.stringify(window.__mcChatQueryResult||{kind:'pending'})", raw -> {
            if (pending == null) return;
            JSONObject result;
            try { result = new JSONObject(jsString(raw)); }
            catch (Exception error) { finish(null, new IllegalStateException("일반 Chat 조회 결과를 읽지 못했습니다.")); return; }
            String kind = result.optString("kind");
            if ("pending".equals(kind) && polls < 60) { page.postDelayed(() -> pollResult(attempt, polls + 1, id), 250); return; }
            if ("waiting".equals(kind) || "pending".equals(kind)) { page.postDelayed(() -> requery(attempt + 1), 2500); return; }
            if ("ok".equals(kind)) {
                activity.getSharedPreferences("general-chat", 0).edit().putString("url", "https://chatgpt.com/c/" + id).apply();
                finish(result, null); return;
            }
            finish(null, new IllegalStateException(result.optString("reason", "일반 Chat 조회 실패")));
        });
    }

    static String requeryScript(String id, String expected, long sentAtSeconds) {
        return "(function(){window.__mcChatQueryResult={kind:'pending'};"
            + "fetch('/api/auth/session',{credentials:'include',headers:{Accept:'application/json'}})"
            + ".then(async s=>{if(!s.ok){window.__mcChatQueryResult={kind:'error',reason:'웹 세션 HTTP '+s.status};return;}"
            + "const session=await s.json(),token=session.accessToken;"
            + "if(typeof token!=='string'||!token){window.__mcChatQueryResult={kind:'error',reason:'웹 로그인이 필요합니다.'};return;}"
            + "const r=await fetch('/backend-api/conversation/" + id + "',{credentials:'include',headers:{Authorization:'Bearer '+token,Accept:'application/json'}});"
            + "if(r.status===404){window.__mcChatQueryResult={kind:'waiting'};return;}"
            + "if(!r.ok){window.__mcChatQueryResult={kind:'error',reason:'대화 조회 HTTP '+r.status};return;}"
            + "const data=await r.json(),mapping=data.mapping||{},nodes=Object.entries(mapping),expected=" + JSONObject.quote(expected) + ",sentAt=" + sentAtSeconds + ";"
            + "let match='',matchTime=-1;for(const [key,node] of nodes){const m=node&&node.message,parts=m&&m.content&&m.content.parts;"
            + "if(m&&m.author&&m.author.role==='user'&&Array.isArray(parts)&&parts.some(p=>p===expected)){const time=Number(m.create_time)||0;if(time>=matchTime){match=key;matchTime=time;}}}"
            + "if(matchTime>0&&matchTime<sentAt-3){window.__mcChatQueryResult={kind:'waiting'};return;}"
            + "if(!match){window.__mcChatQueryResult={kind:'waiting'};return;}"
            + "let best=null,bestTime=-1;for(const [key,node] of nodes){const m=node&&node.message;"
            + "if(!m||!m.author||m.author.role!=='assistant'||m.status!=='finished_successfully'||(m.recipient&&m.recipient!=='all'))continue;"
            + "let parent=node.parent;const seen=new Set();let linked=false;while(parent&&!seen.has(parent)){seen.add(parent);"
            + "if(parent===match){linked=true;break;}parent=mapping[parent]&&mapping[parent].parent;}"
            + "if(!linked)continue;const parts=m.content&&m.content.parts,text=Array.isArray(parts)?parts.map(p=>typeof p==='string'?p:(p&&typeof p.text==='string'?p.text:'')).filter(Boolean).join('\\n'):'';"
            + "if(!text)continue;const time=Number(m.create_time)||0;if(time>=bestTime){bestTime=time;best={message:m,text};}}"
            + "if(!best){window.__mcChatQueryResult={kind:'waiting'};return;}"
            + "const meta=best.message.metadata||{},model=meta.resolved_model_slug||meta.model_slug||'';"
            + "window.__mcChatQueryResult={kind:'ok',reply:best.text.slice(0,200000),truncated:best.text.length>200000,model,conversationId:" + JSONObject.quote(id) + "};})"
            + ".catch(e=>{window.__mcChatQueryResult={kind:'error',reason:'대화 조회 오류: '+e.name};});return 'started';})()";
    }

    private void retry(Runnable step, int attempt, int maximum, String error) {
        if (attempt >= maximum || expired()) finish(null, new IllegalStateException(error + (lastDiagnostic.isEmpty() ? "" : " [" + lastDiagnostic + "]")));
        else page.postDelayed(step, 500);
    }

    private boolean expired() { return System.currentTimeMillis() > deadline; }

    private void finish(JSONObject result, Exception error) {
        Done done = pending;
        pending = null;
        submittedText = "";
        clicked = false;
        inserting = false;
        if (done != null) done.complete(result, error);
    }

    private static String jsString(String raw) {
        try { return new JSONArray("[" + raw + "]").optString(0, ""); }
        catch (Exception ignored) { return ""; }
    }

    private static String conversationId(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        String path = "chatgpt.com".equals(uri.getHost()) ? uri.getPath() : "";
        return path != null && path.matches("/c/[0-9a-fA-F-]{36}") ? path.substring(3) : "";
    }

    void destroy() {
        if (pending != null) finish(null, new IllegalStateException("일반 Chat 화면이 닫혔습니다."));
        page.stopLoading();
        page.destroy();
    }
}
