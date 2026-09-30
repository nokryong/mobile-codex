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
    private FrameLayout root;
    private FrameLayout bubble;
    private LinearLayout panel;
    private WindowManager.LayoutParams layout;
    private EditText input;
    private TextView transcript, status, send, stop, resume, microphone;
    private ImageView bubbleFace, headerFace;
    private View bubbleDot;
    private JSONObject state = obj();
    private String scope = "", transcriptText = "", face = "";
    private boolean expanded, visible, binding, sending, attention;
    private final java.util.Map<String, android.graphics.Bitmap> faces = new java.util.HashMap<>();
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
    private boolean dark() { return (service.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES; }
    // Same neutral palette as the web UI tokens (visual-system.css).
    private int surface() { return dark() ? Color.rgb(42,42,42) : Color.WHITE; }
    private int text() { return dark() ? Color.rgb(236,236,236) : Color.rgb(13,13,13); }
    private int text2() { return dark() ? Color.rgb(180,180,180) : Color.rgb(93,93,93); }
    private int fill() { return dark() ? Color.argb(20,255,255,255) : Color.argb(10,0,0,0); }
    private int line() { return dark() ? Color.argb(26,255,255,255) : Color.argb(26,0,0,0); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView pill(String label, boolean primary, Runnable action) {
        TextView b = new TextView(service); b.setText(label); b.setTextSize(13); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(36)); b.setPadding(dp(14), 0, dp(14), 0);
        b.setTextColor(primary ? surface() : text()); b.setBackground(shape(primary ? text() : fill(), 18)); b.setClickable(true); b.setFocusable(true);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private TextView iconButton(String glyph, String description, Runnable action) {
        TextView b = new TextView(service); b.setText(glyph); b.setTextSize(17); b.setGravity(Gravity.CENTER); b.setTextColor(text2()); b.setContentDescription(description);
        b.setClickable(true); b.setFocusable(true); b.setOnClickListener(v -> action.run()); return b;
    }
    private ImageView faceView(int size) {
        ImageView view = new ImageView(service); view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(Color.WHITE);
        view.setBackground(circle); view.setClipToOutline(true); view.setLayoutParams(new LinearLayout.LayoutParams(size, size)); return view;
    }
    /** Built-in character faces, cropped from the chat stickers (1254px squares). */
    @SuppressWarnings("deprecation")
    private android.graphics.Bitmap faceBitmap(String name) {
        return faces.computeIfAbsent(name, key -> {
            try (java.io.InputStream stream = service.getAssets().open("web/chat-icons/" + key + ".png")) {
                android.graphics.BitmapRegionDecoder decoder = android.graphics.BitmapRegionDecoder.newInstance(stream, false);
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 4;
                android.graphics.Bitmap bitmap = decoder.decodeRegion(new Rect(300, 390, 940, 1030), options); decoder.recycle(); return bitmap;
            } catch (Exception error) { return null; }
        });
    }
    private String faceName() {
        if (attention) return "05-question";
        if (state.optBoolean("busy")) return "04-working";
        return "01-idle";
    }
    private void updateFace() {
        if (bubbleFace == null) return;
        String next = faceName();
        if (!next.equals(face)) { face = next; android.graphics.Bitmap bitmap = faceBitmap(next); bubbleFace.setImageBitmap(bitmap); headerFace.setImageBitmap(bitmap); }
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setStroke(dp(2), surface());
        dot.setColor(attention ? Color.rgb(245,158,11) : state.optBoolean("busy") ? Color.rgb(47,124,246) : Color.rgb(16,163,127));
        bubbleDot.setBackground(dot);
    }
    private void build() {
        root = new FrameLayout(service); root.setClipChildren(false); root.setClipToPadding(false); root.setPadding(dp(6), dp(6), dp(6), dp(6));
        // Collapsed: only the character face, draggable; tap to open.
        bubble = new FrameLayout(service); bubble.setContentDescription(t("Codex 플로팅 대화 열기"));
        bubbleFace = faceView(dp(56)); bubbleFace.setElevation(dp(6));
        bubble.addView(bubbleFace, new FrameLayout.LayoutParams(dp(56), dp(56)));
        bubbleDot = new View(service); bubbleDot.setElevation(dp(7));
        FrameLayout.LayoutParams dotLayout = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM | Gravity.END); dotLayout.setMargins(0, 0, dp(1), dp(1));
        bubble.addView(bubbleDot, dotLayout);
        bubble.setOnClickListener(v -> { attention = false; setExpanded(true); });
        root.addView(bubble, new FrameLayout.LayoutParams(dp(56), dp(56)));
        // Expanded: a card with header, recent transcript and one input row.
        panel = new LinearLayout(service); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(14), dp(10), dp(10), dp(12));
        GradientDrawable card = shape(surface(), 20); card.setStroke(dp(1), line()); panel.setBackground(card); panel.setElevation(dp(10));
        LinearLayout header = new LinearLayout(service); header.setGravity(Gravity.CENTER_VERTICAL);
        headerFace = faceView(dp(32)); header.addView(headerFace);
        LinearLayout titles = new LinearLayout(service); titles.setOrientation(LinearLayout.VERTICAL); titles.setPadding(dp(10), 0, dp(4), 0);
        TextView title = new TextView(service); title.setText("Codex"); title.setTextSize(15); title.setTextColor(text()); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); titles.addView(title);
        status = new TextView(service); status.setTextColor(text2()); status.setTextSize(12); status.setMaxLines(2); status.setEllipsize(TextUtils.TruncateAt.END); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView app = pill(t("앱에서 열기"), false, this::openApp); app.setMinHeight(dp(32)); app.setTextSize(12); header.addView(app, new LinearLayout.LayoutParams(-2, dp(32)));
        header.addView(iconButton("–", t("접기"), () -> setExpanded(false)), new LinearLayout.LayoutParams(dp(40), dp(44)));
        header.addView(iconButton("✕", t("닫기"), this::close), new LinearLayout.LayoutParams(dp(40), dp(44)));
        panel.addView(header);
        ScrollView scroll = new ScrollView(service); scroll.setBackground(shape(fill(), 14));
        LinearLayout.LayoutParams scrollLayout = new LinearLayout.LayoutParams(-1, 0, 1); scrollLayout.setMargins(0, dp(8), dp(4), dp(8));
        transcript = new TextView(service); transcript.setTextColor(text()); transcript.setTextSize(14); transcript.setLineSpacing(0, 1.2f); transcript.setTextIsSelectable(true); transcript.setPadding(dp(12), dp(10), dp(12), dp(10)); scroll.addView(transcript);
        panel.addView(scroll, scrollLayout);
        LinearLayout actions = new LinearLayout(service); actions.setGravity(Gravity.CENTER_VERTICAL);
        stop = pill(t("중단"), false, () -> command("chat.stop", obj(), null));
        resume = pill(t("계속"), false, () -> { if (input.getText().toString().isBlank()) input.setText(t("중단한 작업을 현재 상태부터 확인하고 이어서 진행해 줘.")); submit(); });
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(-2, dp(32)); actionLayout.setMargins(0, 0, dp(6), dp(8));
        actions.addView(stop, actionLayout); actions.addView(resume, new LinearLayout.LayoutParams(actionLayout));
        panel.addView(actions);
        LinearLayout inputRow = new LinearLayout(service); inputRow.setGravity(Gravity.CENTER_VERTICAL); inputRow.setBackground(shape(fill(), 22)); inputRow.setPadding(dp(14), dp(2), dp(4), dp(2));
        input = new EditText(service); input.setTextColor(text()); input.setHintTextColor(text2()); input.setHint(t("작업 요청 또는 추가 지시")); input.setTextSize(15); input.setBackground(null); input.setPadding(0, dp(8), dp(4), dp(8));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1); input.setMaxLines(4);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        microphone = iconButton("●", t("음성으로 초안 입력"), this::dictate); microphone.setText(t("음성")); microphone.setTextSize(13);
        inputRow.addView(microphone, new LinearLayout.LayoutParams(dp(48), dp(44)));
        send = iconButton("↑", t("보내기"), this::submit); send.setTextSize(18); send.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams sendLayout = new LinearLayout.LayoutParams(dp(36), dp(36)); sendLayout.setMargins(dp(2), 0, dp(2), 0);
        inputRow.addView(send, sendLayout);
        panel.addView(inputRow);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        layout = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START; layout.x = dp(8); layout.y = dp(120);
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        // Window insets handle IME space on API 30+, including Samsung keyboards.
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (expanded) {
                int bottom = insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.systemBars()).bottom;
                int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int available = Math.max(dp(180), service.getResources().getDisplayMetrics().heightPixels - bottom - top - dp(16));
                int height = Math.min(dp(440), available);
                if (layout.height != height || layout.y != top + dp(8)) { layout.height = height; layout.y = top + dp(8); windows.updateViewLayout(root, layout); }
            }
            return insets;
        });
        attachDrag(bubble); attachDrag(header);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!binding) saveDraft(); updateButtons(); }
            public void afterTextChanged(Editable e) {}
        });
        binding = true; input.setText(drafts.getString(draftKey(scope), "")); binding = false;
        windows.addView(root, layout); display();
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachDrag(View handle) {
        float[] drag = new float[4]; boolean[] moved = new boolean[1];
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { drag[0]=event.getRawX(); drag[1]=event.getRawY(); drag[2]=layout.x; drag[3]=layout.y; moved[0]=false; return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx=event.getRawX()-drag[0], dy=event.getRawY()-drag[1]; if (Math.abs(dx)+Math.abs(dy)>dp(10)) moved[0]=true;
                if (moved[0]) { android.util.DisplayMetrics size=service.getResources().getDisplayMetrics(); layout.x=Math.max(0,Math.min(size.widthPixels-root.getWidth(),(int)(drag[2]+dx))); layout.y=Math.max(dp(28),Math.min(size.heightPixels-root.getHeight(),(int)(drag[3]+dy))); windows.updateViewLayout(root,layout); }
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
        layout.width = value ? Math.min(dp(380), service.getResources().getDisplayMetrics().widthPixels - dp(16)) : -2;
        layout.height = value ? Math.min(dp(440), service.getResources().getDisplayMetrics().heightPixels - dp(100)) : -2;
        if (value) { layout.x = Math.max(0, Math.min(layout.x, service.getResources().getDisplayMetrics().widthPixels - layout.width)); layout.y = dp(36); }
        display(); windows.updateViewLayout(root, layout); root.requestApplyInsets();
    }
    private void hideKeyboard() { if (input != null) service.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0); }
    private void openApp() { attention = false; collapseForAction(); service.startActivity(new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private String scope(JSONObject s) { JSONObject workspace=s.optJSONObject("workspace"); return s.optString("threadId").isEmpty() ? "workspace:"+(workspace==null?"":workspace.optString("key")) : "thread:"+s.optString("threadId"); }
    private String draftKey(String scope) { return dev.mobilecodex.app.core.WorkspacePath.hash(scope); }
    private void saveDraft() { if (input!=null && !scope.isEmpty() && !binding) drafts.edit().putString(draftKey(scope),input.getText().toString()).apply(); }
    private void display() {
        if (root==null) return;
        bubble.setVisibility(expanded?View.GONE:View.VISIBLE);
        panel.setVisibility(expanded?View.VISIBLE:View.GONE);
        status.setVisibility(expanded ? View.VISIBLE : View.GONE);
        status.setText(state.optString("status", t("연결 확인 중")) + (expanded ? t(" · 창을 접으면 휴대폰 조작 가능") : "")); transcript.setText(transcriptText); updateButtons(); updateFace();
    }
    private void updateButtons() {
        if(send==null) return;
        boolean voice = !scope.isEmpty() && !sending && !VoiceInput.active();
        microphone.setEnabled(voice); microphone.setAlpha(voice ? 1f : .4f);
        boolean busy=state.optBoolean("busy"), canSend=!sending&&!input.getText().toString().isBlank();
        send.setContentDescription(busy?t("추가 지시"):t("보내기")); send.setEnabled(canSend);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(canSend ? text() : line()); send.setBackground(circle); send.setTextColor(canSend ? surface() : text2());
        stop.setVisibility(busy ? View.VISIBLE : View.GONE); stop.setEnabled(busy);
        resume.setVisibility(!busy && !sending ? View.VISIBLE : View.GONE); resume.setEnabled(!busy&&!sending);
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
            else if(name.equals("server.request")) { attention = true; status.setText(t("앱에서 승인 / 질문을 확인해 주세요.")); updateFace(); }
            else if(name.equals("error")) status.setText(copy.optString("message"));
        });
    }
    @Override public void approval(Engine.Approval approval) { main.post(()->{if(visible){attention=true;status.setText(t("앱에서 파일 변경을 확인해 주세요."));updateFace();}}); }
}
