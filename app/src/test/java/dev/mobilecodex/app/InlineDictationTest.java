package dev.mobilecodex.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.speech.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSpeechRecognizer;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class)
public class InlineDictationTest {
    private Activity activity;
    private InlineDictation dictation;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ResolveInfo result = new ResolveInfo(); result.serviceInfo = new ServiceInfo();
        result.serviceInfo.packageName = "test.speech"; result.serviceInfo.name = "Speech";
        Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE), result);
        dictation = new InlineDictation(activity, state -> {});
    }
    @After public void cleanup() { dictation.cancel(); activity.finish(); }
    private ShadowSpeechRecognizer start() throws Exception {
        dictation.prepare(obj("scope","project/thread","original","original draft"));
        dictation.start(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        return Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());
    }
    private Bundle result(String text) { Bundle b = new Bundle(); b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of(text))); return b; }
    @Test public void resultIsPersistedOnlyAfterFinalRecognition() throws Exception {
        var speech = start();speech.triggerOnReadyForSpeech(new Bundle());
        assertEquals("listening",dictation.snapshot().getString("phase"));
        speech.triggerOnPartialResults(result("partial"));assertEquals(0,VoiceInput.pending(activity,"main").length());
        dictation.stop();assertEquals("transcribing",dictation.snapshot().getString("phase"));
        speech.triggerOnResults(result("final transcript"));assertFalse(VoiceInput.active());assertTrue(speech.isDestroyed());
        var receipt=VoiceInput.pending(activity,"main").getJSONObject(0);assertEquals("project/thread",receipt.getString("scope"));assertEquals("final transcript",receipt.getString("text"));
    }
    @Test public void cancelledSessionCannotWriteLateResultsIntoNewSession() throws Exception {
        var old=start();old.triggerOnReadyForSpeech(new Bundle());dictation.cancel();
        var next=start();old.triggerOnResults(result("stale text"));
        assertTrue(VoiceInput.active());assertEquals(1,VoiceInput.pending(activity,"main").length());
        next.triggerOnResults(result("new text"));var pending=VoiceInput.pending(activity,"main");assertEquals(2,pending.length());
        assertFalse(pending.toString().contains("stale text"));assertTrue(pending.toString().contains("new text"));
    }
    @Test public void deniedPermissionReleasesAutomationGuardWithoutChangingDraft() throws Exception {
        dictation.prepare(obj("scope","general/thread","original","draft"));assertTrue(VoiceInput.active());
        dictation.denied();assertFalse(VoiceInput.active());var receipt=VoiceInput.pending(activity,"main").getJSONObject(0);
        assertEquals("",receipt.getString("text"));assertFalse(receipt.getString("error").isEmpty());assertEquals("draft",receipt.getString("original"));
    }
}
