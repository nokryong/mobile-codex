package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import static dev.mobilecodex.app.core.Json.*;

/** A native, keyboard-aware second view of the same Engine, owned by the accessibility service. */
final class FloatingChat implements Engine.Ui {
    private final PhoneUseService service;
    private final Engine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private final android.content.SharedPreferences drafts;
    private LinearLayout root;
    private WindowManager.LayoutParams layout;
    private EditText input;
    private TextView transcript, status;
    private Button send, stop, resume, expand, microphone;
    private JSONObject state = obj();
    private String scope = "", transcriptText = "";
    private boolean expanded, visible, binding, sending;
    FloatingChat(PhoneUseService service, Engine engine) {
        this.service = service; this.engine = engine; windows = service.getSystemService(WindowManager.class);
        drafts = service.getSharedPreferences("floating-drafts", 0);
    }
    boolean visible() { return visible; }
    boolean editing() { return visible && expanded; }
    void show() {
        if (visible) { setExpanded(true); return; }
        visible = true; expanded = false;
        try { build(); voiceChanged(); engine.observe(this); }
        catch (RuntimeException error) { visible = false; if (root != null && root.getParent() != null) windows.removeView(root); root = null; throw error; }
    }
    void close() {
        saveDraft(); visible = false; engine.unobserve(this);
        if (root != null) { hideKeyboard(); windows.removeView(root); root = null; }
    }
    void collapseForAction() { if (visible && expanded) setExpanded(false); }
    Rect bounds() {
        Rect box = new Rect();
        if (root != null) { int[] xy = new int[2]; root.getLocationOnScreen(xy); box.set(xy[0], xy[1], xy[0] + root.getWidth(), xy[1] + root.getHeight()); }
        return box;
    }
    private int dp(int value) { return Math.round(value * service.getResources().getDisplayMetrics().density); }
    private Button button(String label, Runnable action) {
        Button b = new Button(service); b.setText(label); b.setTextSize(13); b.setMinWidth(0); b.setMinimumWidth(0); b.setOnClickListener(v -> action.run()); return b;
    }
    private void build() {
        root = new LinearLayout(service); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(247,247,247)); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1), Color.LTGRAY); root.setBackground(bg); root.setElevation(dp(8));
        LinearLayout bar = new LinearLayout(service); bar.setGravity(Gravity.CENTER_VERTICAL);
        expand = button("Codex", () -> setExpanded(!expanded)); bar.addView(expand, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = button(t("닫기"), this::close); bar.addView(close); root.addView(bar);
        status = new TextView(service); status.setTextColor(Color.DKGRAY); status.setTextSize(12); root.addView(status);
        ScrollView scroll = new ScrollView(service); transcript = new TextView(service); transcript.setTextColor(Color.BLACK); transcript.setTextSize(14); transcript.setTextIsSelectable(true); transcript.setPadding(dp(6), dp(8), dp(6), dp(8)); scroll.addView(transcript);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        input = new EditText(service); input.setTextColor(Color.BLACK); input.setHintTextColor(Color.DKGRAY); input.setHint(t("작업 요청 또는 추가 지시")); input.setTextSize(14);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1); input.setMaxLines(3);
        LinearLayout inputRow = new LinearLayout(service); inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        microphone = button(t("음성"), this::dictate); microphone.setContentDescription(t("음성으로 초안 입력")); inputRow.addView(microphone); root.addView(inputRow);
        LinearLayout actions = new LinearLayout(service);
        send = button(t("보내기"), this::submit); stop = button(t("중단"), () -> command("chat.stop", obj(), null));
        resume = button(t("계속"), () -> { if (input.getText().toString().isBlank()) input.setText(t("중단한 작업을 현재 상태부터 확인하고 이어서 진행해 줘.")); submit(); });
        actions.addView(send, new LinearLayout.LayoutParams(0, dp(48), 1)); actions.addView(stop); actions.addView(resume);
        root.addView(actions);
        Button app = button(t("앱에서 전체 대화 / 승인 확인"), this::openApp); root.addView(app);
        layout = new WindowManager.LayoutParams(dp(172), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START; layout.x = dp(12); layout.y = dp(120);
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        // Window insets handle IME space on API 30+, including Samsung keyboards.
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (expanded) {
                int bottom = insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.systemBars()).bottom;
                int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int available = Math.max(dp(180), service.getResources().getDisplayMetrics().heightPixels - bottom - top - dp(16));
                int height = Math.min(dp(420), available);
                if (layout.height != height || layout.y != top + dp(8)) { layout.height = height; layout.y = top + dp(8); windows.updateViewLayout(root, layout); }
            }
            return insets;
        });
        attachDrag(expand);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!binding) saveDraft(); updateButtons(); }
            public void afterTextChanged(Editable e) {}
        });
        binding = true; input.setText(drafts.getString(draftKey(scope), "")); binding = false;
        windows.addView(root, layout); display();
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachDrag(Button handle) {
        float[] drag = new float[4]; boolean[] moved = new boolean[1];
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { drag[0]=event.getRawX(); drag[1]=event.getRawY(); drag[2]=layout.x; drag[3]=layout.y; moved[0]=false; return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx=event.getRawX()-drag[0], dy=event.getRawY()-drag[1]; if (Math.abs(dx)+Math.abs(dy)>dp(10)) moved[0]=true;
                if (moved[0]) { android.util.DisplayMetrics size=service.getResources().getDisplayMetrics(); layout.x=Math.max(0,Math.min(size.widthPixels-root.getWidth(),(int)(drag[2]+dx))); layout.y=Math.max(dp(28),Math.min(size.heightPixels-root.getHeight()-dp(24),(int)(drag[3]+dy))); windows.updateViewLayout(root,layout); }
                return true;
            }
            if (event.getActionMasked()==MotionEvent.ACTION_UP) { if (!moved[0]) view.performClick(); return true; }
            return event.getActionMasked()==MotionEvent.ACTION_CANCEL;
        });
    }
    private void setExpanded(boolean value) {
        if (!visible) return; expanded = value;
        if (!value) hideKeyboard();
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (value && !VoiceInput.active() ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        layout.width = value ? Math.min(dp(380), service.getResources().getDisplayMetrics().widthPixels - dp(24)) : dp(172);
        layout.height = value ? Math.min(dp(420), service.getResources().getDisplayMetrics().heightPixels - dp(100)) : -2;
        layout.x = Math.min(layout.x, service.getResources().getDisplayMetrics().widthPixels - layout.width);
        if (value) layout.y = dp(36);
        display(); windows.updateViewLayout(root, layout); root.requestApplyInsets();
    }
    private void hideKeyboard() { if (input != null) service.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0); }
    private void openApp() { collapseForAction(); service.startActivity(new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private String scope(JSONObject s) { JSONObject workspace=s.optJSONObject("workspace"); return s.optString("threadId").isEmpty() ? "workspace:"+(workspace==null?"":workspace.optString("key")) : "thread:"+s.optString("threadId"); }
    private String draftKey(String scope) { return dev.mobilecodex.app.core.WorkspacePath.hash(scope); }
    private void saveDraft() { if (input!=null && !scope.isEmpty() && !binding) drafts.edit().putString(draftKey(scope),input.getText().toString()).apply(); }
    private void display() {
        if (root==null) return;
        for (int i=2;i<root.getChildCount();i++) root.getChildAt(i).setVisibility(expanded?View.VISIBLE:View.GONE);
        expand.setText(expanded?t("Codex · 접기"):t("Codex · 열기"));
        status.setText(state.optString("status", t("연결 확인 중")) + (expanded ? t(" · 창을 접으면 휴대폰 조작 가능") : "")); transcript.setText(transcriptText); updateButtons();
    }
    private void updateButtons() {
        if(send==null) return;
        microphone.setEnabled(!scope.isEmpty() && !sending && !VoiceInput.active());
        boolean busy=state.optBoolean("busy"); send.setText(busy?t("추가 지시"):t("보내기")); send.setEnabled(!sending&&!input.getText().toString().isBlank()); stop.setEnabled(busy); resume.setEnabled(!busy&&!sending);
    }
    private void dictate() {
        saveDraft();
        try { VoiceInput.start(service, "floating", obj("scope", scope, "original", input.getText().toString(), "start", input.getSelectionStart(), "end", input.getSelectionEnd())); }
        catch (Exception error) { Toast.makeText(service, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Atomically persist text and receipt marker before acknowledging.
    void voiceChanged() {
        if (!visible || root == null) return;
        boolean active = VoiceInput.active();
        root.setVisibility(active ? View.GONE : View.VISIBLE);
        if (active) hideKeyboard();
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (expanded && !active ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        windows.updateViewLayout(root, layout);
        org.json.JSONArray results = VoiceInput.pending(service, "floating");
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i); String id = result.optString("receiptId"), target = result.optString("scope"), key = draftKey(target), marker = "voice:" + id;
            try {
                if (!drafts.getBoolean(marker, false)) {
                    String current = target.equals(scope) ? input.getText().toString() : drafts.getString(key, "");
                    String merged = VoiceInput.merge(current, result);
                    if (!drafts.edit().putString(key, merged).putBoolean(marker, true).commit()) throw new java.io.IOException(t("음성 초안을 저장하지 못했습니다. 창을 다시 열어 주세요."));
                    if (target.equals(scope)) { binding = true; input.setText(merged); input.setSelection(merged.length()); binding = false; }
                    if (!result.optString("error").isEmpty()) Toast.makeText(service, result.optString("error"), Toast.LENGTH_LONG).show();
                    else if (!result.optString("text").isEmpty()) Toast.makeText(service, target.equals(scope) ? t("음성 초안을 확인한 뒤 보내세요.") : t("원래 대화의 음성 초안을 저장했습니다."), Toast.LENGTH_LONG).show();
                }
                VoiceInput.acknowledge(service, "floating", id); drafts.edit().remove(marker).apply();
            } catch (Exception error) { Toast.makeText(service, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }
        updateButtons(); root.requestApplyInsets();
    }
    private void submit() {
        String text=input.getText().toString(); if(sending||text.isBlank()) return;
        String submittedScope=scope; boolean busy=state.optBoolean("busy");
        JSONObject workspace=state.optJSONObject("workspace");
        JSONObject args=obj("text",text,"expectedThreadId",state.optString("threadId"),"workspaceKey",workspace==null?"":workspace.optString("key"),"expectedTurnId",state.optString("turnId"));
        sending=true; updateButtons(); collapseForAction();
        command(busy?"chat.steer":"chat.send",args,() -> {
            if(drafts.getString(draftKey(submittedScope),"").equals(text)) drafts.edit().remove(draftKey(submittedScope)).apply();
            if(scope.equals(submittedScope)&&input.getText().toString().equals(text)) input.setText("");
        });
    }
    private void command(String name,JSONObject args,Runnable success) {
        engine.handle(name,args,(result,error)->main.post(()->{
            sending=false;
            if(error!=null) { Toast.makeText(service,error.getMessage(),Toast.LENGTH_LONG).show(); if(visible) status.setText(error.getMessage()); }
            else if(success!=null) success.run();
            updateButtons();
        }));
    }
    @Override public void event(String name,JSONObject data) {
        final JSONObject copy=parse(data.toString());
        main.post(()->{
            if(!visible) return;
            if(name.equals("voice.changed")) { voiceChanged(); }
            else if(name.equals("state")) {
                String next=scope(copy); if(!next.equals(scope)) { saveDraft(); scope=next; binding=true; input.setText(drafts.getString(draftKey(scope),"")); binding=false; }
                state=copy; JSONArray messages=copy.optJSONArray("messages"); StringBuilder text=new StringBuilder();
                if(messages!=null) for(int i=Math.max(0,messages.length()-4);i<messages.length();i++){JSONObject m=messages.optJSONObject(i); if(m!=null) text.append(m.optString("role").equals("user")?t("나: "):"Codex: ").append(m.optString("text")).append("\n\n");}
                transcriptText=text.length()>12000?text.substring(text.length()-12000):text.toString(); display();
            } else if(name.equals("message.delta")) { transcriptText+=copy.optString("delta"); if(transcriptText.length()>12000)transcriptText=transcriptText.substring(transcriptText.length()-12000); transcript.setText(transcriptText); }
            else if(name.equals("server.request")) { status.setText(t("앱에서 승인 / 질문을 확인해 주세요.")); }
            else if(name.equals("error")) status.setText(copy.optString("message"));
        });
    }
    @Override public void approval(Engine.Approval approval) { main.post(()->{if(visible)status.setText(t("앱에서 파일 변경을 확인해 주세요."));}); }
}
