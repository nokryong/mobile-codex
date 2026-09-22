package dev.mobilecodex.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.*;
import android.widget.Button;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import dev.mobilecodex.app.core.PhoneControlGate;
import dev.mobilecodex.app.core.ToolCatalog;
import static dev.mobilecodex.app.core.Json.*;

/** Explicitly enabled, visible Android automation. No event text logging or background recording. */
public final class PhoneUseService extends AccessibilityService {
    private static volatile PhoneUseService instance;
    private final PhoneControlGate gate = new PhoneControlGate();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<CompletableFuture<JSONObject>> pending = ConcurrentHashMap.newKeySet();
    private long revision;
    private PhoneScreen screen;
    private Button stopButton;
    private FloatingChat floatingChat;

    static JSONObject status(Context context) {
        PhoneUseService service = instance;
        boolean connected = service != null, enabled = connected && service.gate.enabled();
        return obj("connected", connected, "enabled", enabled, "floating", connected && service.floatingChat != null && service.floatingChat.visible(), "floatingChatOpen", connected && service.floatingChat != null && service.floatingChat.editing(), "voiceInputActive", VoiceInput.active(), "screenshotsSupported", Build.VERSION.SDK_INT >= 30,
            "status", enabled ? "휴대폰 제어 켜짐" : connected ? "접근성 연결됨 · 제어 꺼짐" : "접근성 권한 필요");
    }
    static void closeFloatingForUpdate() { PhoneUseService service = instance; if (service != null && service.floatingChat != null) service.floatingChat.close(); }
    static void voiceInputChanged() {
        PhoneUseService service = instance;
        if (service != null) { service.invalidate(); if (service.floatingChat != null) service.floatingChat.voiceChanged(); service.changed(); }
    }
    static void showFloatingChat() throws Exception {
        PhoneUseService service = instance;
        if (service == null) throw new IOException("접근성 설정에서 Mobile Codex 휴대폰 제어를 먼저 연결해 주세요.");
        service.requireUnlocked();
        if (service.floatingChat == null) service.floatingChat = new FloatingChat(service, ((MobileCodexApp) service.getApplication()).engine());
        service.floatingChat.show(); service.changed();
    }
    static Intent settingsIntent(Context context) {
        // Service-detail actions are not part of the public SDK; use the supported settings entry.
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
    /** Only called from the trusted, local settings UI; never exposed as a model tool. */
    static void enableFromUi() throws Exception {
        PhoneUseService service = instance;
        if (service == null) throw new IOException("Android 접근성 설정에서 Mobile Codex 휴대폰 제어를 먼저 켜 주세요.");
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        service.requireUnlocked();
        service.showStopButton();
        service.invalidate(); service.gate.enable(); service.changed();
    }
    /** Revocation is synchronous even if Engine's serial I/O queue is occupied. */
    static void stopControl() {
        PhoneUseService service = instance;
        if (service == null) return;
        service.gate.stop();
        for (CompletableFuture<JSONObject> future : service.pending)
            future.completeExceptionally(new IOException("휴대폰 제어가 중지되었습니다."));
        Runnable cleanup = () -> {
            // A delayed cleanup from an earlier stop must not remove a newly enabled stop button.
            if (service.gate.enabled()) return;
            service.invalidate(); service.removeStopButton(); service.changed();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run(); else service.main.post(cleanup);
    }
    @Override protected void onServiceConnected() { instance = this; gate.stop(); changed(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!gate.enabled()) return;
        // Only invalidate references; event contents are neither recorded nor sent to the model.
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) invalidate();
    }
    @Override public void onInterrupt() { stopControl(); }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    private void disconnect() {
        gate.stop(); for (CompletableFuture<JSONObject> future : pending) future.completeExceptionally(new IOException("접근성 연결이 끊어졌습니다."));
        invalidate(); removeStopButton(); if (floatingChat != null) { floatingChat.close(); floatingChat = null; } if (instance == this) instance = null; changed();
    }
    private void changed() {
        if (getApplication() instanceof MobileCodexApp app) app.engine().phoneStateChanged();
    }
    private void invalidate() { revision++; if (screen != null) { screen.close(); screen = null; } }
    private void requireUnlocked() throws IOException {
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        if ((lock != null && lock.isDeviceLocked()) || (power != null && !power.isInteractive()))
            throw new IOException("기기의 잠금을 직접 해제하고 화면을 켜 주세요.");
    }
    private void showStopButton() {
        if (stopButton != null) return;
        Button button = new Button(this); button.setText("휴대폰 제어 중지"); button.setTextSize(13);
        button.setTextColor(Color.WHITE); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(125, 37, 37)));
        button.setContentDescription("Codex의 휴대폰 제어 즉시 중지");
        button.setOnClickListener(v -> stopControl());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END; params.y = Math.round(48 * getResources().getDisplayMetrics().density);
        // Human drag keeps the stop affordance from permanently covering another app's controls.
        float[] drag = new float[4]; boolean[] moved = new boolean[1];
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                drag[0] = event.getRawX(); drag[1] = event.getRawY(); drag[2] = params.x; drag[3] = params.y; moved[0] = false; return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - drag[0], dy = event.getRawY() - drag[1];
                if (Math.abs(dx) + Math.abs(dy) > 12 * getResources().getDisplayMetrics().density) moved[0] = true;
                if (moved[0]) {
                    DisplayMetrics size = metrics();
                    params.x = Math.max(0, Math.min(size.widthPixels - view.getWidth(), (int)(drag[2] - dx)));
                    params.y = Math.max(0, Math.min(size.heightPixels - view.getHeight(), (int)(drag[3] + dy)));
                    getSystemService(WindowManager.class).updateViewLayout(view, params); invalidate();
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { if (!moved[0]) view.performClick(); return true; }
            return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        });
        getSystemService(WindowManager.class).addView(button, params); stopButton = button;
    }
    private void removeStopButton() {
        if (stopButton != null) { try { getSystemService(WindowManager.class).removeView(stopButton); } catch (IllegalArgumentException ignored) {} stopButton = null; }
    }
    private Rect stopBounds() {
        Rect bounds = new Rect();
        if (stopButton != null) { int[] xy = new int[2]; stopButton.getLocationOnScreen(xy); bounds.set(xy[0], xy[1], xy[0] + stopButton.getWidth(), xy[1] + stopButton.getHeight()); }
        return bounds;
    }
    @SuppressWarnings("deprecation")
    private DisplayMetrics metrics() {
        DisplayMetrics metrics = new DisplayMetrics(); getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics); return metrics;
    }
    private void requireDefaultDisplay(AccessibilityNodeInfo node) throws IOException {
        if (Build.VERSION.SDK_INT >= 30) {
            AccessibilityWindowInfo window = node.getWindow();
            if (window != null) {
                try { if (window.getDisplayId() != Display.DEFAULT_DISPLAY) throw new IOException("기기의 기본 화면에서 앱을 열어 주세요. 외부 화면은 제어하지 않습니다."); }
                finally { window.recycle(); }
            }
        }
    }
    static JSONObject execute(Context context, String tool, JSONObject args) throws Exception {
        if (tool.equals("mobile_phone_status")) return ToolCatalog.result(true, status(context).toString());
        PhoneUseService service = instance;
        if (service == null) throw new IOException("접근성 서비스가 연결되어 있지 않습니다. 설정 → 도구 → 접근성 설정을 열어 주세요.");
        long ticket = service.gate.ticket(); CompletableFuture<JSONObject> future = new CompletableFuture<>();
        service.pending.add(future);
        service.main.post(() -> {
            try {
                if (future.isDone()) return;
                service.gate.check(ticket); service.requireUnlocked();
                switch (tool) {
                    case "mobile_phone_apps" -> service.success(future, ticket, service.apps());
                    case "mobile_phone_screen" -> service.observe(args, ticket, future);
                    case "mobile_phone_action" -> service.act(args, ticket, future);
                    default -> throw new IOException("알 수 없는 휴대폰 도구입니다.");
                }
            } catch (Exception e) { future.completeExceptionally(e); }
        });
        try { return future.get(12, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new IOException("휴대폰 응답 시간이 초과되었습니다. 실행 여부를 화면에서 확인해 주세요.", e); }
        finally { future.cancel(false); service.pending.remove(future); }
    }
    private void success(CompletableFuture<JSONObject> future, long ticket, JSONObject value) {
        try { gate.check(ticket); future.complete(ToolCatalog.result(true, value.toString())); }
        catch (Exception e) { future.completeExceptionally(e); }
    }
    private JSONObject apps() {
        JSONArray apps = new JSONArray(); Set<String> seen = new HashSet<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (app.activityInfo != null && seen.add(app.activityInfo.packageName))
                apps.put(obj("package", app.activityInfo.packageName, "label", app.loadLabel(getPackageManager()).toString()));
        }
        return obj("apps", apps);
    }
    private void observe(JSONObject args, long ticket, CompletableFuture<JSONObject> future) throws Exception {
        requireAutomationView();
        invalidate(); AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IOException("활성 화면을 읽을 수 없습니다. 앱을 열고 다시 시도해 주세요.");
        DisplayMetrics metrics = metrics();
        try { requireDefaultDisplay(root); screen = new PhoneScreen(root, revision, metrics.widthPixels, metrics.heightPixels); }
        finally { root.recycle(); }
        PhoneScreen captured = screen;
        Rect overlay = stopBounds();
        Rect chat = floatingChat == null ? new Rect() : floatingChat.bounds(); captured.data.put("floatingChatBounds", array(chat.left, chat.top, chat.right, chat.bottom));
        captured.data.put("stopButtonBounds", array(overlay.left, overlay.top, overlay.right, overlay.bottom));
        if (!args.optBoolean("screenshot", true)) { success(future, ticket, captured.data); return; }
        if (Build.VERSION.SDK_INT < 30 || captured.passwordVisible) {
            captured.data.put("screenshotUnavailable", captured.passwordVisible ? "비밀번호 필드가 보이는 화면은 이미지로 전송하지 않습니다." : "Android 10은 화면 요소로 조작합니다. 스크린샷 API는 Android 11 이상에서 제공됩니다.");
            success(future, ticket, captured.data); return;
        }
        capture(captured, ticket, future);
    }
    @android.annotation.TargetApi(30)
    private void capture(PhoneScreen captured, long ticket, CompletableFuture<JSONObject> future) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer(); Bitmap hardware = null, bitmap = null;
                try {
                    if (future.isDone()) return;
                    gate.check(ticket); requireUnlocked(); requireAutomationView();
                    if (screen != captured || revision != captured.revision) throw new IOException("캡처 중 화면이 변경되었습니다. 화면을 다시 읽어 주세요.");
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IOException("화면 이미지 변환에 실패했습니다.");
                    bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (bitmap == null || bitmap.getWidth() != captured.width || bitmap.getHeight() != captured.height)
                        throw new IOException("화면 크기가 변경되었습니다. 화면을 다시 읽어 주세요.");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) throw new IOException("화면 이미지 인코딩에 실패했습니다.");
                    gate.check(ticket);
                    JSONObject output = ToolCatalog.result(true, captured.data.toString());
                    output.getJSONArray("contentItems").put(obj("type", "inputImage", "imageUrl", "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)));
                    future.complete(output);
                } catch (Exception e) { future.completeExceptionally(e); }
                finally { if (bitmap != null) bitmap.recycle(); if (hardware != null) hardware.recycle(); buffer.close(); }
            }
            @Override public void onFailure(int errorCode) {
                try { captured.data.put("screenshotUnavailable", "Android가 화면 캡처를 허용하지 않았습니다 (" + errorCode + "). 화면 요소만 반환합니다."); success(future, ticket, captured.data); }
                catch (Exception e) { future.completeExceptionally(e); }
            }
        });
    }
    private void act(JSONObject args, long ticket, CompletableFuture<JSONObject> future) throws Exception {
        requireAutomationView();
        String action = args.getString("action");
        Integer global = switch (action) {
            case "back" -> GLOBAL_ACTION_BACK; case "home" -> GLOBAL_ACTION_HOME; case "recents" -> GLOBAL_ACTION_RECENTS;
            case "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; case "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS; default -> null;
        };
        if (global != null) { boolean accepted = performGlobalAction(global); invalidate(); success(future, ticket, obj("accepted", accepted, "action", action)); return; }
        if (action.equals("open_app")) {
            String name = args.getString("package"); Intent launch = getPackageManager().getLaunchIntentForPackage(name);
            if (launch == null) throw new IOException("실행 가능한 앱이 없습니다. mobile_phone_apps로 패키지 이름을 확인해 주세요.");
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); invalidate(); success(future, ticket, obj("accepted", true, "package", name)); return;
        }
        if (screen == null) throw new IOException("mobile_phone_screen으로 화면을 먼저 읽어 주세요.");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IOException("활성 화면이 없습니다.");
        DisplayMetrics metrics = metrics();
        try { requireDefaultDisplay(root); screen.requireFresh(args.optString("snapshotId"), revision, root.getWindowId(), PhoneScreen.value(root.getPackageName()), metrics.widthPixels, metrics.heightPixels); }
        finally { root.recycle(); }
        if (!args.optString("nodeId").isEmpty()) {
            AccessibilityNodeInfo node = screen.node(args.getString("nodeId"));
            AccessibilityWindowInfo window = node.getWindow();
            if (window != null) {
                try { if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) throw new IOException("접근성 제어창은 사용자가 직접 조작합니다."); }
                finally { window.recycle(); }
            }
            int nativeAction = switch (action) {
                case "tap" -> AccessibilityNodeInfo.ACTION_CLICK; case "long_press" -> AccessibilityNodeInfo.ACTION_LONG_CLICK;
                case "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT; case "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                case "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; default -> throw new IOException("이 작업은 화면 요소를 사용하지 않습니다.");
            };
            Bundle arguments = new Bundle();
            if (action.equals("set_text")) {
                if (!node.isEditable()) throw new IOException("입력 가능한 요소가 아닙니다.");
                String text = args.getString("text"); if (text.length() > 50000) throw new IOException("입력할 텍스트가 너무 깁니다.");
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            }
            boolean accepted = node.performAction(nativeAction, arguments); invalidate();
            success(future, ticket, obj("accepted", accepted, "action", action, "verify", "mobile_phone_screen")); return;
        }
        if (!Set.of("tap", "long_press", "swipe").contains(action)) throw new IOException("이 작업에는 화면 요소 nodeId가 필요합니다.");
        float x = PhoneScreen.coordinate(args, "x", screen.width), y = PhoneScreen.coordinate(args, "y", screen.height);
        float endX = action.equals("swipe") ? PhoneScreen.coordinate(args, "endX", screen.width) : x;
        float endY = action.equals("swipe") ? PhoneScreen.coordinate(args, "endY", screen.height) : y;
        Rect overlay = stopBounds();
        Rect chat = floatingChat == null ? new Rect() : floatingChat.bounds();
        Rect gestureBounds = new Rect((int)Math.min(x, endX), (int)Math.min(y, endY), (int)Math.max(x, endX) + 1, (int)Math.max(y, endY) + 1);
        if (Rect.intersects(chat, gestureBounds)) throw new IOException("플로팅 대화창은 사용자가 직접 조작합니다. 다른 영역을 선택해 주세요.");
        // Never let an agent hide or activate its own emergency stop control.
        if (Rect.intersects(overlay, new Rect((int)Math.min(x, endX), (int)Math.min(y, endY), (int)Math.max(x, endX) + 1, (int)Math.max(y, endY) + 1)))
            throw new IOException("중지 버튼 영역은 직접 조작할 수 없습니다.");
        long duration = action.equals("swipe") ? args.optLong("durationMs", 400) : action.equals("long_press") ? 650 : 70;
        if (duration < 1 || duration > 2000) throw new IOException("제스처 시간은 2초 이하여야 합니다.");
        Path path = new Path(); path.moveTo(x, y); if (action.equals("swipe")) path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { success(future, ticket, obj("accepted", true, "action", action, "verify", "mobile_phone_screen")); }
            @Override public void onCancelled(GestureDescription description) { success(future, ticket, obj("accepted", false, "action", action, "reason", "Android cancelled gesture; inspect before retrying")); }
        }, main);
        invalidate(); if (!dispatched) success(future, ticket, obj("accepted", false, "action", action));
    }
    private void requireAutomationView() throws IOException {
        if (VoiceInput.active()) throw new IOException("사용자가 음성 입력 중입니다. 인식이 끝날 때까지 화면 읽기·조작을 기다려 주세요.");
        if (floatingChat != null && floatingChat.editing()) throw new IOException("플로팅 대화창에서 사용자가 입력 중입니다. 창을 접거나 지시를 보낼 때까지 화면 읽기·조작을 기다려 주세요.");
    }
}
