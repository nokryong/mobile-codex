package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.app.Activity;
import android.content.*;
import android.os.Looper;
import org.json.*;
import java.io.IOException;
import java.util.UUID;
import static dev.mobilecodex.app.core.Json.*;

/** User-initiated dictation. Receipts survive Activity/process recreation until the draft acknowledges them. */
final class VoiceInput {
    private static volatile String activeId = "";
    static boolean active() { return !activeId.isEmpty(); }
    private static SharedPreferences receipts(Context context) { return context.getSharedPreferences("voice-results", 0); }
    static JSONObject begin(Context context, String origin, JSONObject draft) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        if (active()) throw new IOException(t("이미 음성 입력 중입니다. 인식을 마치거나 취소해 주세요."));
        if ((!origin.equals("main") && !origin.equals("floating")) || draft.optString("scope").isEmpty()) throw new IOException(t("입력할 대화를 먼저 열어 주세요."));
        if (draft.optString("original").length() > 100000) throw new IOException(t("초안이 너무 깁니다. 내용을 나눠서 입력해 주세요."));
        JSONObject request = parse(draft.toString());
        request.put("origin", origin); request.put("receiptId", UUID.randomUUID().toString());
        activate(context, request.getString("receiptId"));
        return request;
    }
    static JSONObject start(Context context, String origin, JSONObject draft) throws Exception {
        JSONObject request = begin(context, origin, draft);
        try {
            Intent intent = new Intent(context, VoiceInputActivity.class).putExtra("request", request.toString());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException error) { release(context, request.getString("receiptId")); throw error; }
        return obj("requestId", request.getString("receiptId"));
    }
    static void activate(Context context, String id) { activeId = id; changed(context); }
    static void release(Context context, String id) { if (activeId.equals(id)) { activeId = ""; changed(context); } }
    private static void changed(Context context) {
        PhoneUseService.voiceInputChanged();
        if (context.getApplicationContext() instanceof MobileCodexApp app) app.engine().voiceInputChanged();
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Persist before the recognizer Activity finishes.
    static void complete(Context context, JSONObject request, String text, String error) throws Exception {
        JSONObject result = parse(request.toString()); result.put("text", text); result.put("error", error);
        result.put("cancelled", text.isEmpty() && error.isEmpty()); result.put("timestamp", System.currentTimeMillis());
        if (!receipts(context).edit().putString(request.getString("receiptId"), result.toString()).commit()) throw new IOException(t("음성 입력 결과를 저장하지 못했습니다."));
        release(context, request.getString("receiptId")); changed(context);
    }
    static JSONArray pending(Context context, String origin) {
        JSONArray result = array();
        receipts(context).getAll().values().stream().filter(v -> v instanceof String).map(v -> parse((String)v))
            .filter(v -> origin.equals(v.optString("origin"))).sorted(java.util.Comparator.comparingLong(v -> v.optLong("timestamp"))).forEach(result::put);
        return result;
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Acknowledge only after the consumer has durably saved the draft.
    static void acknowledge(Context context, String origin, String id) throws IOException {
        String saved = receipts(context).getString(id, "");
        if (!saved.isEmpty() && origin.equals(parse(saved).optString("origin")) && !receipts(context).edit().remove(id).commit()) throw new IOException(t("음성 입력 확인 기록을 저장하지 못했습니다."));
    }
    static String merge(String current, JSONObject result) {
        String text = result.optString("text"), original = result.optString("original");
        if (text.isEmpty()) return current;
        if (current.equals(original)) {
            int start = Math.max(0, Math.min(current.length(), result.optInt("start", current.length())));
            int end = Math.max(start, Math.min(current.length(), result.optInt("end", start)));
            return current.substring(0, start) + text + current.substring(end);
        }
        return current + (current.isEmpty() || Character.isWhitespace(current.charAt(current.length()-1)) ? "" : "\n") + text;
    }
}
