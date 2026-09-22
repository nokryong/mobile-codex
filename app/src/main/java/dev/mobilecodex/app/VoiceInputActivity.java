package dev.mobilecodex.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;

/** Private trampoline for the installed recognizer's visible microphone UI, also usable from the overlay. */
public final class VoiceInputActivity extends Activity {
    private static final int RECOGNIZE = 81;
    private JSONObject request;
    private boolean delivered;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        request = parse(getIntent().getStringExtra("request"));
        if (request.optString("receiptId").isEmpty()) { finish(); return; }
        VoiceInput.activate(this, request.optString("receiptId"));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(32, 48, 32, 32);
        TextView note = new TextView(this); note.setText("음성 입력\n인식된 내용은 초안에 넣습니다. 확인한 뒤 보내세요.\n음성 처리는 기기에 설정된 인식 서비스가 담당합니다."); note.setTextSize(16); panel.addView(note);
        Button cancel = new Button(this); cancel.setText("취소"); cancel.setOnClickListener(v -> deliver("", "")); panel.addView(cancel); setContentView(panel);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> deliver("", ""));
        if (saved == null) {
            try { startActivityForResult(recognitionIntent(Locale.getDefault()), RECOGNIZE); }
            catch (ActivityNotFoundException error) { deliver("", "사용 가능한 음성 인식 앱이 없습니다. 기기의 음성 입력 서비스를 설치하거나 활성화해 주세요."); }
            catch (RuntimeException error) { deliver("", "음성 인식을 시작하지 못했습니다. 기기의 음성 입력 설정과 마이크 권한을 확인해 주세요."); }
        }
    }
    static Intent recognitionIntent(Locale locale) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "말씀하세요. 확인한 뒤 보내실 수 있습니다.");
    }
    static String recognizedText(int result, Intent data) {
        if (result != RESULT_OK || data == null) return "";
        ArrayList<String> alternatives = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (alternatives != null) for (String text : alternatives) if (text != null && !text.isBlank()) return text.trim();
        return "";
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == RECOGNIZE) {
            String text = recognizedText(result, data);
            deliver(text, result == RESULT_OK && text.isEmpty() ? "음성을 인식하지 못했습니다. 다시 시도해 주세요." : "");
        }
    }
    private void deliver(String text, String error) {
        if (delivered) return;
        try { VoiceInput.complete(this, request, text, error); delivered = true; finish(); }
        catch (Exception failure) {
            new AlertDialog.Builder(this).setTitle("음성 결과를 저장하지 못했습니다").setMessage(text.isEmpty() ? failure.getMessage() : text)
                .setPositiveButton("복사하고 닫기", (dialog, which) -> { if (!text.isEmpty()) getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("음성 입력", text)); delivered = true; finish(); })
                .setNegativeButton("다시 저장", (dialog, which) -> deliver(text, error)).setCancelable(false).show();
        }
    }
    @Override public void onBackPressed() { deliver("", ""); }
    @Override protected void onDestroy() {
        if (isFinishing() && request != null) VoiceInput.release(this, request.optString("receiptId"));
        super.onDestroy();
    }
}
