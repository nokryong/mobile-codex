package dev.mobilecodex.app;

import android.content.*;
import android.os.*;
import android.speech.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.function.Consumer;
import static dev.mobilecodex.app.core.Json.*;
import static dev.mobilecodex.app.core.Texts.t;

/** Owns one microphone session. Results use the same durable, scoped receipts as floating chat. */
final class InlineDictation {
    private final Context context;
    private final Consumer<JSONObject> events;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private JSONObject request;
    private String phase = "idle", partial = "";
    private long started;
    private float level;
    private Runnable deadline;
    InlineDictation(Context context, Consumer<JSONObject> events) { this.context = context; this.events = events; }
    JSONObject prepare(JSONObject draft) throws Exception {
        request = VoiceInput.begin(context, "main", draft);
        partial = ""; level = 0; started = 0;
        phase = "permission"; emit();
        return obj("requestId", request.optString("receiptId"));
    }
    boolean waitingPermission() { return phase.equals("permission"); }
    JSONObject snapshot() { return obj("phase", phase, "partial", partial, "level", level, "elapsedMs", started == 0 ? 0 : SystemClock.elapsedRealtime() - started); }
    private void emit() { events.accept(snapshot()); }
    void start() {
        if (request == null || !waitingPermission()) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { finish("", t("기기의 음성 인식 서비스를 사용할 수 없습니다. 설정에서 활성화해 주세요.")); return; }
        final JSONObject session = request;
        try {
            phase = "starting"; emit();
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return request == session; }
                public void onReadyForSpeech(Bundle params) { if (current() && phase.equals("starting")) { phase = "listening"; started = SystemClock.elapsedRealtime(); emit(); if (deadline != null) handler.removeCallbacks(deadline); } }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { if (current() && phase.equals("listening")) { level = Math.max(0, Math.min(1, (rms + 2) / 12)); emit(); } }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() { if (current()) transcribing(); }
                public void onError(int error) { if (current()) finish("", errorMessage(error)); }
                public void onResults(Bundle results) {
                    if (!current()) return;
                    String text = resultText(results);
                    finish(text, text.isEmpty() ? t("음성을 인식하지 못했습니다. 다시 시도해 주세요.") : "");
                }
                public void onPartialResults(Bundle results) { if (current()) { partial = resultText(results); emit(); } }
                public void onEvent(int type, Bundle params) { }
            });
            Intent intent = VoiceInputActivity.recognitionIntent(AppLanguage.locale(context))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
            timeout(15000);
        } catch (RuntimeException error) { finish("", t("마이크를 시작하지 못했습니다. 권한과 음성 인식 설정을 확인해 주세요.")); }
    }
    static String resultText(Bundle results) {
        ArrayList<String> values = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
    static String errorMessage(int code) {
        return switch (code) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> t("마이크 권한이 필요합니다. 앱 권한에서 마이크를 허용해 주세요.");
            case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> t("음성을 인식하지 못했습니다. 다시 시도해 주세요.");
            case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> t("음성 인식 서비스에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.");
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> t("다른 앱이 음성 인식 서비스를 사용 중입니다. 잠시 뒤 다시 시도해 주세요.");
            default -> t("음성 인식이 중단되었습니다. 다시 시도해 주세요.");
        };
    }
    void denied() { if (waitingPermission()) finish("", errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)); }
    void stop() { if (recognizer != null && (phase.equals("listening") || phase.equals("starting"))) { transcribing(); try { recognizer.stopListening(); } catch (RuntimeException error) { finish("", errorMessage(SpeechRecognizer.ERROR_CLIENT)); } } }
    private void transcribing() { phase = "transcribing"; level = 0; emit(); timeout(15000); }
    private void timeout(long delay) {
        if (deadline != null) handler.removeCallbacks(deadline);
        JSONObject session = request;
        deadline = () -> { if (request == session && session != null) { if (phase.equals("listening")) stop(); else finish("", t("음성 인식 시간이 초과되었습니다. 다시 시도해 주세요.")); } };
        handler.postDelayed(deadline, delay);
    }
    void cancel() { if (request != null) finish("", ""); }
    private void finish(String text, String error) {
        if (request == null) return;
        JSONObject completed = request;
        request = null; // Invalidate late callbacks before cancel/destroy.
        if (deadline != null) handler.removeCallbacks(deadline);
        if (recognizer != null) { SpeechRecognizer old = recognizer; recognizer = null; try { old.cancel(); } catch (RuntimeException ignored) {} old.destroy(); }
        phase = "idle"; partial = ""; level = 0; started = 0;
        try { VoiceInput.complete(context, completed, text, error); }
        catch (Exception failure) {
            // Do not discard a transcript when durable storage is full. Offer a selectable copy.
            VoiceInput.release(context, completed.optString("receiptId"));
            android.widget.TextView message = new android.widget.TextView(context); message.setPadding(32, 24, 32, 24); message.setTextIsSelectable(true);
            message.setText(text.isEmpty() ? t("음성 입력 결과를 저장하지 못했습니다.") : text);
            new android.app.AlertDialog.Builder(context).setTitle(t("음성 결과를 저장하지 못했습니다"))
                .setView(message).setPositiveButton(t("복사하고 닫기"), (dialog, which) -> {
                    if (!text.isEmpty()) context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(t("음성 입력"), text));
                }).show();
        }
        emit();
    }
}
