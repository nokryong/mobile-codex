package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.CookieManager;
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

/** Keeps the ordinary ChatGPT web session behind the packaged Mobile Codex UI. */
final class ChatWebTransport {
    interface Done { void complete(JSONObject result, Exception error); }

    private final Activity activity;
    private final WebView page;
    private final String modelDomSource;
    private final String requerySource;
    private Done pending;
    private String submittedText = "";
    private boolean clicked;
    private boolean inserting;
    private boolean pageLoaded;
    private boolean modelChanging;
    private long modelDeadline;
    private long sessionEpoch = 1;
    private long nextModelOperation;
    private long activeModelOperation;
    private Done modelDone;
    private boolean destroyed;
    private long modelStartedAt;
    private String modelStage = "idle";
    private final JSONArray modelEvents = new JSONArray();
    private long deadline;
    private long sendStartedAt;
    private String lastDiagnostic = "";
    private String sendOperationId = "";
    private String requestedOptionId = "";
    private String uiConfirmedOptionId = "";
    private long selectionRevision;
    private long sendGeneration;
    private String lastConversationId = "";
    private String sendObservedUserId = "";

    @SuppressLint("SetJavaScriptEnabled")
    ChatWebTransport(Activity activity, FrameLayout root) {
        this.activity = activity;
        try (var source = activity.getAssets().open("chat-model-dom.js")) {
            modelDomSource = readAsset(source);
        } catch (Exception error) {
            throw new IllegalStateException("Chat 모델 제어 코드를 읽지 못했습니다.", error);
        }
        try (var source = activity.getAssets().open("chat-requery.js")) {
            requerySource = readAsset(source);
        } catch (Exception error) {
            throw new IllegalStateException("Chat 재조회 코드를 읽지 못했습니다.", error);
        }
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
                if (pending != null && !clicked && !inserting) {
                    long generation = sendGeneration;
                    page.postDelayed(() -> insert(0, generation), 350);
                }
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                sessionEpoch++;
                pageLoaded = false;
                cancelModel("ChatGPT 화면이 바뀌어 모델 선택을 다시 확인해야 합니다.");
            }
        });
        root.addView(page, 0, new FrameLayout.LayoutParams(-1, -1));
        String saved = activity.getSharedPreferences("general-chat", 0).getString("url", "");
        page.loadUrl(conversationId(saved).isEmpty() ? "https://chatgpt.com/" : saved);
    }

    private static String readAsset(InputStream source) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    void reloadIfIdle() {
        if (pending == null && !modelChanging) { pageLoaded = false; page.reload(); }
    }

    void newChat() {
        if (pending != null || modelChanging) throw new IllegalStateException("Chat 작업이 진행 중입니다.");
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

    private void inspectModel(java.util.function.Consumer<JSONObject> callback) {
        evaluateModel("inspect()", state -> { recordModelStage(modelStage, state); callback.accept(state); });
    }

    private void recordModelStage(String stage, JSONObject state) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("stage", stage);
            entry.put("elapsedMs", modelStartedAt == 0 ? 0 : SystemClock.elapsedRealtime() - modelStartedAt);
            entry.put("sessionEpoch", sessionEpoch);
            entry.put("operationId", activeModelOperation);
            entry.put("state", state.optString("state", "unknown"));
            entry.put("type", state.optString("type", "unknown"));
            entry.put("level", state.optString("level", ""));
            entry.put("position", state.optInt("position", 0));
            entry.put("total", state.optInt("total", 0));
            entry.put("trigger", state.optJSONObject("trigger"));
            entry.put("control", state.optJSONObject("control"));
            entry.put("focus", state.optJSONObject("focus"));
            entry.put("viewFocused", page.hasFocus());
            modelEvents.put(entry);
            if (modelEvents.length() > 32) modelEvents.remove(0);
        } catch (Exception ignored) {}
    }

    void diagnostic(Done done) {
        inspectModel(state -> {
            Uri uri = Uri.parse(page.getUrl() == null ? "" : page.getUrl());
            JSONObject result = new JSONObject();
            try {
                result.put("buildSha", BuildConfig.SOURCE_SHA);
                result.put("androidApi", Build.VERSION.SDK_INT);
                var webview = WebView.getCurrentWebViewPackage();
                result.put("webViewVersion", webview == null ? "unknown" : webview.versionName);
                result.put("locale", activity.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
                result.put("viewWidth", page.getWidth()); result.put("viewHeight", page.getHeight());
                result.put("host", "chatgpt.com".equals(uri.getHost()) ? "chatgpt.com" : "other");
                result.put("pathKind", conversationId(page.getUrl()).isEmpty() ? "new-or-login" : "conversation");
                result.put("sessionEpoch", sessionEpoch);
                result.put("operationId", activeModelOperation);
                result.put("stage", modelStage);
                result.put("current", state);
                result.put("events", modelEvents);
            } catch (Exception ignored) {}
            done.complete(result, null);
        });
    }

    private void evaluateModel(String command, java.util.function.Consumer<JSONObject> callback) {
        String script = modelDomSource + "\n(function(){try{return JSON.stringify(MCChatModelDom." + command
            + ")}catch(error){return JSON.stringify({state:'error',reason:error.name})}})()";
        page.evaluateJavascript(script, raw -> {
            try { callback.accept(new JSONObject(jsString(raw))); }
            catch (Exception error) { callback.accept(new JSONObject()); }
        });
    }

    private boolean liveModel(long operation) {
        if (destroyed || !modelChanging || activeModelOperation != operation) return false;
        if (SystemClock.elapsedRealtime() > modelDeadline) {
            completeModel(operation, null, new IllegalStateException("ChatGPT 설정 확인 시간이 초과됐습니다."));
            return false;
        }
        return true;
    }

    private void completeModel(long operation, JSONObject result, Exception error) {
        if (activeModelOperation != operation || !modelChanging) return;
        Done callback = modelDone;
        modelDone = null;
        modelChanging = false;
        activeModelOperation++;
        page.clearFocus();
        modelStage = error == null ? "ui-confirmed" : "failed";
        if (callback != null) callback.complete(result, error);
    }

    private void cancelModel(String reason) {
        if (modelChanging) completeModel(activeModelOperation, null, new IllegalStateException(reason));
    }

    void modelState(Done done) {
        if (pending != null || modelChanging) {
            done.complete(null, new IllegalStateException("ChatGPT 설정을 읽을 수 없는 상태입니다.")); return;
        }
        modelChanging = true;
        modelStartedAt = SystemClock.elapsedRealtime(); modelStage = "reading";
        modelDone = done;
        long operation = activeModelOperation = ++nextModelOperation;
        modelDeadline = SystemClock.elapsedRealtime() + 10_000;
        readModelState(operation, 0);
    }

    private void readModelState(long operation, int attempt) {
        if (!liveModel(operation)) return;
        if (!pageLoaded) {
            if (attempt < 40) page.postDelayed(() -> readModelState(operation, attempt + 1), 200);
            else completeModel(operation, null, new IllegalStateException("ChatGPT 웹 화면이 준비되지 않았습니다. 로그인 상태를 확인해 주세요."));
            return;
        }
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            String level = state.optString("level", "");
            if (modelIndex(level) >= 0 && ("closed".equals(state.optString("state")) || "open".equals(state.optString("state")))) {
                JSONObject result = new JSONObject();
                try { result.put("level", level); result.put("sessionEpoch", sessionEpoch); result.put("verification", "ui-confirmed"); }
                catch (Exception ignored) {}
                completeModel(operation, result, null);
            } else if (attempt < 40) page.postDelayed(() -> readModelState(operation, attempt + 1), 200);
            else completeModel(operation, null, new IllegalStateException("ChatGPT 모델 설정을 확인하지 못했습니다."));
        });
    }

    void selectModel(String level, Done done) {
        if (modelIndex(level) < 0) { done.complete(null, new IllegalArgumentException("지원하지 않는 Chat 모델 단계입니다.")); return; }
        if (pending != null || modelChanging) { done.complete(null, new IllegalStateException("ChatGPT 설정을 변경할 수 없는 상태입니다.")); return; }
        modelChanging = true;
        modelStartedAt = SystemClock.elapsedRealtime(); modelStage = "opening";
        modelDone = done;
        long operation = activeModelOperation = ++nextModelOperation;
        modelDeadline = SystemClock.elapsedRealtime() + 20_000;
        openForSelection(operation, level, 0);
    }

    private void openForSelection(long operation, String target, int steps) {
        if (!liveModel(operation)) return;
        if (!pageLoaded) { completeModel(operation, null, new IllegalStateException("ChatGPT 페이지가 변경됐습니다.")); return; }
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            String stage = state.optString("state");
            if ("ambiguous".equals(stage) || "error".equals(stage)) { modelError(operation, "ChatGPT 설정 컨트롤을 특정하지 못했습니다."); return; }
            if ("open".equals(stage)) { adjustModel(operation, target, steps, state); return; }
            if (!"closed".equals(stage) || state.isNull("trigger")) { modelError(operation, "ChatGPT 모델 버튼을 찾지 못했습니다."); return; }
            if (target.equals(state.optString("level"))) { verifyModelSelection(operation, target, 0); return; }
            tapModelButton(state.optJSONObject("trigger"), state.optJSONObject("viewport"));
            waitOpen(operation, target, steps, 0);
        });
    }

    private void waitOpen(long operation, String target, int steps, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if ("open".equals(state.optString("state"))) { adjustModel(operation, target, steps, state); return; }
            if (attempt >= 15) { modelError(operation, "ChatGPT 설정 메뉴가 열리지 않았습니다."); return; }
            page.postDelayed(() -> waitOpen(operation, target, steps, attempt + 1), 150);
        });
    }

    private void adjustModel(long operation, String target, int steps, JSONObject state) {
        if (!liveModel(operation)) return;
        if (steps > 8) { modelError(operation, "모델 설정 변경 횟수를 초과했습니다."); return; }
        String type = state.optString("type"), current = state.optString("level");
        JSONObject control = state.optJSONObject("control");
        if ("submenu".equals(type)) {
            modelStage = "opening-submenu";
            if (control == null || control.optBoolean("disabled")) { modelError(operation, "성능 메뉴를 열 수 없습니다."); return; }
            tapModelButton(control, state.optJSONObject("viewport"));
            waitSubmenu(operation, target, steps, 0);
            return;
        }
        if ("options".equals(type)) {
            modelStage = "selecting-option";
            evaluateModel("choose(" + JSONObject.quote(target) + ")", chosen -> {
                if (!liveModel(operation)) return;
                if (!chosen.optBoolean("ok")) { modelError(operation, "선택한 ChatGPT 옵션을 사용할 수 없습니다."); return; }
                waitModelChange(operation, target, current, steps + 1, 0);
            });
            return;
        }
        if (!"slider".equals(type) && !"stepper".equals(type)) { modelError(operation, "알 수 없는 ChatGPT 설정 컨트롤입니다."); return; }
        if (control == null || control.optBoolean("disabled")) { modelError(operation, "ChatGPT 설정 컨트롤이 비활성화돼 있습니다."); return; }
        if (target.equals(current)) { verifyModelSelection(operation, target, 0); return; }
        if (modelIndex(current) < 0) { modelError(operation, "현재 ChatGPT 설정값을 읽지 못했습니다."); return; }
        if (state.optInt("total", 0) != 0 && state.optInt("total") != MODEL_LEVELS.length) {
            modelError(operation, "현재 계정의 설정 단계가 앱과 다릅니다."); return;
        }
        page.requestFocus();
        modelStage = "changing-value";
        if (!page.hasFocus()) { modelError(operation, "ChatGPT WebView가 키 입력 포커스를 받지 못했습니다."); return; }
        evaluateModel("focus()", focus -> {
            if (!liveModel(operation)) return;
            if (!focus.optBoolean("ok")) { modelError(operation, "설정 요소가 키 입력 포커스를 받지 못했습니다."); return; }
            int key = modelIndex(target) > modelIndex(current) ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            waitModelChange(operation, target, current, steps + 1, 0);
        });
    }

    private void waitSubmenu(long operation, String target, int steps, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if ("open".equals(state.optString("state")) && !"submenu".equals(state.optString("type"))) {
                adjustModel(operation, target, steps, state); return;
            }
            if (attempt >= 12) { modelError(operation, "성능 하위 메뉴가 열리지 않았습니다."); return; }
            page.postDelayed(() -> waitSubmenu(operation, target, steps, attempt + 1), 150);
        });
    }

    private void waitModelChange(long operation, String target, String before, int steps, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            String current = state.optString("level");
            if (target.equals(current)) { verifyModelSelection(operation, target, 0); return; }
            if (modelIndex(current) >= 0 && !current.equals(before) && "open".equals(state.optString("state"))) {
                adjustModel(operation, target, steps, state); return;
            }
            if (attempt >= 10) { modelError(operation, "설정값 변경을 확인하지 못했습니다."); return; }
            page.postDelayed(() -> waitModelChange(operation, target, before, steps, attempt + 1), 150);
        });
    }

    private void verifyModelSelection(long operation, String target, int phase) {
        if (!liveModel(operation)) return;
        modelStage = phase == 0 ? "closing-before-verify" : "reopened-verifying";
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            String stage = state.optString("state");
            if (phase == 0) {
                if ("open".equals(stage)) {
                    tapModelButton(state.optJSONObject("trigger"), state.optJSONObject("viewport"));
                    waitClosed(operation, target, 0);
                } else if ("closed".equals(stage)) reopenForVerification(operation, target);
                else modelError(operation, "설정 메뉴를 닫지 못했습니다.");
            } else if (phase == 1) {
                if (!"open".equals(stage) || !target.equals(state.optString("level"))) {
                    modelError(operation, "메뉴 재조회에서 요청한 설정을 확인하지 못했습니다."); return;
                }
                tapModelButton(state.optJSONObject("trigger"), state.optJSONObject("viewport"));
                waitFinalClosed(operation, target, 0);
            }
        });
    }

    private void waitClosed(long operation, String target, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if ("closed".equals(state.optString("state"))) { reopenForVerification(operation, target); return; }
            if (attempt == 6) { page.requestFocus(); page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)); page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)); }
            if (attempt >= 15) { modelError(operation, "설정 메뉴를 닫지 못했습니다."); return; }
            page.postDelayed(() -> waitClosed(operation, target, attempt + 1), 150);
        });
    }

    private void reopenForVerification(long operation, String target) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if (!"closed".equals(state.optString("state"))) { modelError(operation, "재확인할 모델 버튼이 없습니다."); return; }
            tapModelButton(state.optJSONObject("trigger"), state.optJSONObject("viewport"));
            waitReopened(operation, target, 0);
        });
    }

    private void waitReopened(long operation, String target, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if ("open".equals(state.optString("state"))) { verifyModelSelection(operation, target, 1); return; }
            if (attempt >= 15) { modelError(operation, "설정 메뉴 재조회에 실패했습니다."); return; }
            page.postDelayed(() -> waitReopened(operation, target, attempt + 1), 150);
        });
    }

    private void waitFinalClosed(long operation, String target, int attempt) {
        if (!liveModel(operation)) return;
        inspectModel(state -> {
            if (!liveModel(operation)) return;
            if ("closed".equals(state.optString("state"))) {
                if (!target.equals(state.optString("level"))) { modelError(operation, "메뉴를 닫은 뒤 선택값이 유지되지 않았습니다."); return; }
                JSONObject result = new JSONObject();
                try { result.put("level", target); result.put("sessionEpoch", sessionEpoch); result.put("verification", "ui-confirmed"); }
                catch (Exception ignored) {}
                completeModel(operation, result, null);
                return;
            }
            if (attempt >= 12) { modelError(operation, "선택값 확인 뒤 메뉴를 닫지 못했습니다."); return; }
            page.postDelayed(() -> waitFinalClosed(operation, target, attempt + 1), 150);
        });
    }

    private void modelError(long operation, String message) {
        completeModel(operation, null, new IllegalStateException(message));
    }

    private void tapModelButton(JSONObject element, JSONObject viewport) {
        if (element == null || viewport == null) return;
        JSONObject box = element.optJSONObject("box");
        if (box == null || viewport.optDouble("width") <= 0 || viewport.optDouble("height") <= 0) return;
        float x = (float) ((box.optDouble("x") + box.optDouble("width") / 2) * page.getWidth() / viewport.optDouble("width"));
        float y = (float) ((box.optDouble("y") + box.optDouble("height") / 2) * page.getHeight() / viewport.optDouble("height"));
        if (x < 0 || y < 0 || x >= page.getWidth() || y >= page.getHeight()) return;
        page.requestFocus();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        page.dispatchTouchEvent(down);
        down.recycle();
        page.postDelayed(() -> {
            if (destroyed) return;
            MotionEvent up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            page.dispatchTouchEvent(up);
            up.recycle();
        }, 80);
    }

    void send(String text, String requestedOption, String operationId, long revision,
              long expectedEpoch, Done done) {
        if (text == null || text.trim().isEmpty()) { done.complete(null, new IllegalArgumentException("메시지를 입력해 주세요.")); return; }
        if (!requestedOption.isEmpty() && modelIndex(requestedOption) < 0) {
            done.complete(null, new IllegalArgumentException("지원하지 않는 Chat 옵션입니다.")); return;
        }
        if (pending != null || modelChanging) {
            done.complete(null, new IllegalStateException("Chat 작업이 진행 중입니다.")); return;
        }
        if (expectedEpoch > 0 && expectedEpoch != sessionEpoch) {
            JSONObject rejected = new JSONObject();
            try { rejected.put("status", "not_sent"); rejected.put("operationId", operationId);
                rejected.put("reason", "웹 세션이 바뀌어 ChatGPT 설정을 다시 확인해야 합니다."); }
            catch (Exception ignored) {}
            done.complete(rejected, null); return;
        }
        if (!requestedOption.isEmpty()) {
            selectModel(requestedOption, (state, error) -> {
                if (error != null) {
                    JSONObject rejected = new JSONObject();
                    try { rejected.put("status", "not_sent"); rejected.put("operationId", operationId);
                        rejected.put("reason", error.getMessage()); }
                    catch (Exception ignored) {}
                    done.complete(rejected, null); return;
                }
                sendOperationId = operationId;
                requestedOptionId = requestedOption;
                uiConfirmedOptionId = state.optString("level", "");
                selectionRevision = revision;
                send(text, done);
            });
        } else {
            sendOperationId = operationId;
            requestedOptionId = "";
            uiConfirmedOptionId = "";
            selectionRevision = revision;
            send(text, done);
        }
    }

    private void send(String text, Done done) {
        if (modelChanging) { done.complete(null, new IllegalStateException("ChatGPT 모델 설정을 변경하는 중입니다.")); return; }
        if (pending != null) { done.complete(null, new IllegalStateException("일반 Chat 답변을 기다리는 중입니다.")); return; }
        if (text == null || text.trim().isEmpty()) { done.complete(null, new IllegalArgumentException("메시지를 입력해 주세요.")); return; }
        pending = done;
        submittedText = text.trim();
        sendStartedAt = System.currentTimeMillis() / 1000;
        lastDiagnostic = "";
        lastConversationId = "";
        sendObservedUserId = "";
        clicked = false;
        inserting = false;
        deadline = SystemClock.elapsedRealtime() + 600_000;
        insert(0, ++sendGeneration);
    }

    private boolean liveSend(long generation) {
        return pending != null && sendGeneration == generation && !destroyed;
    }

    private void insert(int attempt, long generation) {
        if (!liveSend(generation) || clicked || inserting) return;
        if (expired()) { finish(null, new IllegalStateException("ChatGPT 입력창을 찾지 못했습니다. 웹 로그인 상태를 확인해 주세요.")); return; }
        String host = Uri.parse(page.getUrl() == null ? "" : page.getUrl()).getHost();
        if (!"chatgpt.com".equals(host) || !pageLoaded) { retry(() -> insert(attempt + 1, generation), attempt, 40, "ChatGPT 로그인이 필요합니다."); return; }
        String script = "(function(){" + sendObserverScript()
            + "const e=document.querySelector('#prompt-textarea,[data-testid=\"prompt-textarea\"]');"
            + "if(!e)return 'missing';if((e.value||e.textContent||'').trim())return 'not_empty';e.focus();"
            + "const ok=document.execCommand('insertText',false," + JSONObject.quote(submittedText) + ");"
            + "return ok&&(e.value||e.textContent||'').trim()?'inserted':'failed'})()";
        inserting = true;
        page.evaluateJavascript(script, raw -> {
            if (!liveSend(generation)) return;
            inserting = false;
            if (clicked) return;
            String outcome = jsString(raw);
            if ("inserted".equals(outcome)) { page.postDelayed(() -> click(0, generation), 200); return; }
            if ("missing".equals(outcome)) { retry(() -> insert(attempt + 1, generation), attempt, 40, "ChatGPT 웹 입력창을 찾지 못했습니다. 웹 로그인/모델 설정을 열어 확인해 주세요."); return; }
            finish(null, new IllegalStateException("웹 입력 실패: " + outcome));
        });
    }

    /** Observes only the official client's request outcome and conversation ID, never credentials or proofs. */
    static String sendObserverScript() {
        return "window.__mcChatObserved={id:'',userMessageId:'',parentMessageId:'',sendStatus:0,prepareStatus:0,active:false};"
            + "if(!window.__mcChatFetchObserved){const original=window.fetch;"
            + "window.fetch=function(input,init){const result=original.apply(this,arguments);try{"
            + "const u=new URL(typeof input==='string'?input:input.url,location.href);"
            + "const method=(init&&init.method)||(input&&input.method)||'GET';"
            + "if(u.origin===location.origin&&method.toUpperCase()==='POST'"
            + "&&(u.pathname==='/backend-api/f/conversation'||u.pathname==='/backend-api/f/conversation/prepare')){"
            + "const observed=window.__mcChatObserved,prepare=u.pathname.endsWith('/prepare');"
            + "if(!observed||!observed.active)return result;"
            + "const record=body=>{try{const data=JSON.parse(body),id=data.conversation_id;"
            + "if(typeof id==='string'&&/^[0-9a-fA-F-]{36}$/.test(id))observed.id=id;"
            + "const user=data.messages&&data.messages[0];"
            + "if(user&&user.author&&user.author.role==='user'&&typeof user.id==='string'&&/^[0-9a-fA-F-]{36}$/.test(user.id))observed.userMessageId=user.id;"
            + "if(typeof data.parent_message_id==='string'&&/^[0-9a-fA-F-]{36}$/.test(data.parent_message_id))observed.parentMessageId=data.parent_message_id;}catch(e){}};"
            + "if(init&&typeof init.body==='string')record(init.body);"
            + "else if(typeof Request!=='undefined'&&input instanceof Request)input.clone().text().then(record).catch(()=>{});"
            + "result.then(r=>{if(prepare)observed.prepareStatus=r.status;else observed.sendStatus=r.status;}).catch(()=>{});"
            + "}}catch(e){}return result;};window.__mcChatFetchObserved=true;}";
    }

    private void click(int attempt, long generation) {
        if (!liveSend(generation) || clicked) return;
        String script = "(function(){const b=document.querySelector('#composer-submit-button,[data-testid=\"send-button\"]');"
            + "if(!b)return 'missing';if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'disabled';"
            + "if(window.__mcChatObserved)window.__mcChatObserved.active=true;b.click();return 'clicked'})()";
        page.evaluateJavascript(script, raw -> {
            if (!liveSend(generation) || clicked) return;
            String outcome = jsString(raw);
            if ("clicked".equals(outcome)) { clicked = true; page.postDelayed(() -> requery(0, generation), 700); return; }
            if ("disabled".equals(outcome) || "missing".equals(outcome)) { retry(() -> click(attempt + 1, generation), attempt, 30, "ChatGPT 전송 버튼을 누르지 못했습니다."); return; }
            finish(null, new IllegalStateException("웹 전송 실패: " + outcome));
        });
    }

    private void requery(int attempt, long generation) {
        if (!liveSend(generation)) return;
        if (expired()) { finish(null, new IllegalStateException("전송 후 답변 저장을 확인하지 못했습니다. 같은 메시지를 다시 보내기 전에 웹 대화를 확인해 주세요.")); return; }
        String inspect = "(function(){const observed=window.__mcChatObserved||{};return JSON.stringify({path:location.pathname,"
            + "observedId:observed.id||'',userMessageId:observed.userMessageId||'',sendStatus:observed.sendStatus||0,prepareStatus:observed.prepareStatus||0,"
            + "visibility:document.visibilityState,ready:document.readyState,"
            + "users:document.querySelectorAll('[data-message-author-role=user]').length,"
            + "assistants:document.querySelectorAll('[data-message-author-role=assistant]').length,"
            + "composer:!!document.querySelector('#prompt-textarea'),"
            + "network:performance.getEntriesByType('resource').filter(x=>x.name.includes('/backend-api/conversation')).slice(-3)"
            + ".map(x=>({kind:new URL(x.name).pathname.includes('/conversation/')?'read':'send',status:x.responseStatus||0}))})})()";
        page.evaluateJavascript(inspect, raw -> {
            if (!liveSend(generation)) return;
            JSONObject evidence;
            try { evidence = new JSONObject(jsString(raw)); }
            catch (Exception ignored) { evidence = new JSONObject(); }
            String path = evidence.optString("path", "");
            String id = conversationId("https://chatgpt.com" + path);
            if (id.isEmpty()) id = conversationId(page.getUrl());
            if (id.isEmpty()) id = conversationId("https://chatgpt.com/c/" + evidence.optString("observedId", ""));
            if (!id.isEmpty()) {
                lastConversationId = id;
                activity.getSharedPreferences("general-chat", 0).edit()
                    .putString("url", "https://chatgpt.com/c/" + id).apply();
            }
            String observedUserId = evidence.optString("userMessageId", "");
            if (!observedUserId.isEmpty()) sendObservedUserId = observedUserId;
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
            if (id.isEmpty()) { retry(() -> requery(attempt + 1, generation), attempt, 1200, "대화 주소를 찾지 못했습니다."); return; }
            String script = requeryScript(id, submittedText, sendStartedAt, sendOperationId,
                sendObservedUserId, requerySource);
            final String foundId = id;
            page.evaluateJavascript(script, ignored -> pollResult(attempt, 0, foundId, generation));
        });
    }

    private void pollResult(int attempt, int polls, String id, long generation) {
        if (!liveSend(generation)) return;
        String key = JSONObject.quote(sendOperationId);
        page.evaluateJavascript("JSON.stringify(window.__mcChatQueries?.[" + key + "]?.result||{kind:'pending'})", raw -> {
            if (!liveSend(generation)) return;
            JSONObject result;
            try { result = new JSONObject(jsString(raw)); }
            catch (Exception error) { finish(null, new IllegalStateException("일반 Chat 조회 결과를 읽지 못했습니다.")); return; }
            String kind = result.optString("kind");
            if ("pending".equals(kind) && polls < 60) { page.postDelayed(() -> pollResult(attempt, polls + 1, id, generation), 250); return; }
            if ("waiting".equals(kind) || "pending".equals(kind)) { page.postDelayed(() -> requery(attempt + 1, generation), 2500); return; }
            if ("ok".equals(kind)) {
                activity.getSharedPreferences("general-chat", 0).edit().putString("url", "https://chatgpt.com/c/" + id).apply();
                finish(result, null); return;
            }
            if ("network".equals(kind)) { page.postDelayed(() -> requery(attempt + 1, generation), 2500); return; }
            finish(null, new IllegalStateException(result.optString("reason", "일반 Chat 조회 실패")));
        });
    }

    static String requeryScript(String id, String expected, long sentAtSeconds, String operationId,
                                String observedUserId, String matcherSource) {
        String key = JSONObject.quote(operationId);
        return matcherSource + "\n(function(){"
            + "const key=" + key + ",queries=window.__mcChatQueries||(window.__mcChatQueries={});"
            + "const old=queries[key];if(old?.controller)old.controller.abort();"
            + "const sequence=(old?.sequence||0)+1,controller=new AbortController();"
            + "queries[key]={sequence,controller,result:{kind:'pending'}};"
            + "const set=value=>{if(queries[key]?.sequence===sequence)queries[key].result=value};"
            + "fetch('/api/auth/session',{credentials:'include',headers:{Accept:'application/json'},signal:controller.signal})"
            + ".then(async s=>{if(!s.ok){set({kind:'error',reason:'웹 세션 HTTP '+s.status});return;}"
            + "const session=await s.json(),token=session.accessToken;"
            + "if(typeof token!=='string'||!token){set({kind:'error',reason:'웹 로그인이 필요합니다.'});return;}"
            + "const r=await fetch('/backend-api/conversation/" + id + "',{credentials:'include',"
            + "headers:{Authorization:'Bearer '+token,Accept:'application/json'},signal:controller.signal});"
            + "if(r.status===404){set({kind:'waiting'});return;}"
            + "if(!r.ok){set({kind:'error',reason:'대화 조회 HTTP '+r.status});return;}"
            + "const data=await r.json(),match=MCChatRequery.match(data," + JSONObject.quote(expected) + ","
            + sentAtSeconds + "," + JSONObject.quote(observedUserId) + ");"
            + "set(match.kind==='ok'?{...match,conversationId:" + JSONObject.quote(id) + "}:match);})"
            + ".catch(error=>{if(error.name!=='AbortError')set({kind:'network',reason:'대화 재조회 네트워크 오류'});});"
            + "return 'started'})()";
    }

    private void retry(Runnable step, int attempt, int maximum, String error) {
        if (attempt >= maximum || expired()) finish(null, new IllegalStateException(error + (lastDiagnostic.isEmpty() ? "" : " [" + lastDiagnostic + "]")));
        else page.postDelayed(step, 500);
    }

    private boolean expired() { return SystemClock.elapsedRealtime() > deadline; }

    void reconcile(String operationId, String text, String conversationId, long sentAtSeconds,
                   String observedUserId, Done done) {
        if (pending != null || modelChanging) { done.complete(null, new IllegalStateException("Chat 작업이 진행 중입니다.")); return; }
        if (operationId.isEmpty() || text.isEmpty() || !conversationId.matches("[0-9a-fA-F-]{36}")) {
            done.complete(null, new IllegalArgumentException("재조회할 대화 주소와 작업 기록이 없습니다.")); return;
        }
        String currentId = conversationId(page.getUrl());
        if (!currentId.equals(conversationId)) {
            done.complete(null, new IllegalStateException("현재 열린 웹 대화가 기록된 대화와 다릅니다. 해당 대화를 연 뒤 다시 확인해 주세요.")); return;
        }
        pending = done;
        sendOperationId = operationId;
        submittedText = text;
        sendStartedAt = sentAtSeconds;
        sendObservedUserId = observedUserId;
        lastConversationId = conversationId;
        clicked = true;
        deadline = SystemClock.elapsedRealtime() + 60_000;
        requery(0, ++sendGeneration);
    }

    private void finish(JSONObject result, Exception error) {
        Done done = pending;
        pending = null;
        sendGeneration++;
        if (!sendOperationId.isEmpty() && !destroyed) {
            String key = JSONObject.quote(sendOperationId);
            page.evaluateJavascript("(function(){const q=window.__mcChatQueries?.[" + key + "];if(q?.controller)q.controller.abort();if(window.__mcChatQueries)delete window.__mcChatQueries[" + key + "]})()", null);
        }
        if (result == null && error != null) {
            result = new JSONObject();
            try {
                result.put("status", clicked ? "needs_reconciliation" : "not_sent");
                result.put("reason", error.getMessage());
                result.put("conversationId", lastConversationId);
                result.put("observedUserMessageId", sendObservedUserId);
                result.put("sentAtSeconds", sendStartedAt);
            } catch (Exception ignored) {}
            error = null;
        }
        if (result != null) try {
            result.put("operationId", sendOperationId);
            result.put("requestedSetting", requestedOptionId);
            result.put("uiConfirmedSetting", uiConfirmedOptionId);
            result.put("selectionRevision", selectionRevision);
            result.put("sessionEpoch", sessionEpoch);
            result.put("actualResponseModel", result.optString("model", ""));
            result.put("observedRequestSetting", JSONObject.NULL);
        } catch (Exception ignored) {}
        sendOperationId = "";
        lastConversationId = "";
        sendObservedUserId = "";
        requestedOptionId = "";
        uiConfirmedOptionId = "";
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
        cancelModel("Chat 화면이 닫혔습니다.");
        if (pending != null) finish(null, new IllegalStateException("일반 Chat 화면이 닫혔습니다."));
        destroyed = true;
        page.stopLoading();
        page.destroy();
    }
}
