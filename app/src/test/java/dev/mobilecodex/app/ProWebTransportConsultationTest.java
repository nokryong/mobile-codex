package dev.mobilecodex.app;

import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class ProWebTransportConsultationTest {
    private Activity activity;
    private ScriptedPage page;
    private ProWebTransport transport;
    private final List<JSONObject> completions = new ArrayList<>();

    private static final class ScriptedPage extends WebView {
        String url;
        int sendEvaluations;
        boolean holdSend;
        ValueCallback<String> heldSend;
        final List<String> scripts = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        ScriptedPage(Activity activity) { super(activity); }
        @Override public void loadUrl(String value) { url = value; }
        @Override public String getUrl() { return url; }
        @Override public boolean post(Runnable value) { delayed.add(value); return true; }
        @Override public boolean postDelayed(Runnable value, long delayMillis) { delayed.add(value); return true; }
        @Override public void evaluateJavascript(String script, ValueCallback<String> reply) {
            scripts.add(script);
            if (script.contains("JSON.stringify(MCProWeb.clickSend())")) {
                sendEvaluations++;
                if (holdSend) { heldSend = reply; return; }
                respond(reply, obj("status", "clicked"));
            } else if (script.contains("JSON.stringify(MCProWeb.insert(")) {
                respond(reply, obj("status", "inserted", "assistantCount", 0, "lastAssistantId", ""));
            } else if (script.contains("JSON.stringify(MCProWeb.observe(")) {
                respond(reply, obj("status", "observed", "reply", "Pro focused answer", "streaming", false,
                    "conversationId", "remote-one", "conversationPath", "/c/remote-one", "remoteMessageId", "reply-one"));
            } else respond(reply, obj("status", "not_running"));
        }
        void releaseSend() {
            ValueCallback<String> callback = heldSend; heldSend = null;
            respond(callback, obj("status", "clicked"));
        }
        private static void respond(ValueCallback<String> reply, JSONObject value) {
            if (reply != null) reply.onReceiveValue(JSONObject.quote(value.toString()));
        }
    }

    @Before public void before() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("pro-web-operation", 0).edit().clear().commit();
        page = new ScriptedPage(activity);
        transport = new ProWebTransport(activity, new FrameLayout(activity), page);
    }
    @After public void after() { transport.destroy(); }

    private void start(String operationId) throws Exception {
        JSONObject prepared = ProContextBuilder.buildConsultation("EXACT_FOCUSED_PROMPT");
        prepared.put("operationId", operationId).put("threadId", "parent-codex-thread");
        transport.send(prepared, new Uri[0], (result, error) -> {
            assertNull(error); completions.add(result);
        });
    }
    private long generation() throws Exception {
        Field field = ProWebTransport.class.getDeclaredField("generation"); field.setAccessible(true);
        return field.getLong(transport);
    }
    private void invoke(String name, long current) throws Exception {
        Method method = ProWebTransport.class.getDeclaredMethod(name, long.class); method.setAccessible(true);
        method.invoke(transport, current);
    }
    private void answer(long current) throws Exception {
        invoke("poll", current); invoke("poll", current); invoke("poll", current);
    }

    @Test public void repeatedPublicationAndInFlightCallbacksMakeOnlyOneWebSendAndOneFinalResult() throws Exception {
        MainActivity.ConsultationDispatch dispatch = new MainActivity.ConsultationDispatch();
        assertTrue(dispatch.begin("consult-one")); start("consult-one");
        assertFalse(dispatch.begin("consult-one"));
        page.holdSend = true;
        long current = generation();
        invoke("insert", current);
        assertTrue(page.scripts.stream().anyMatch(script -> script.contains(JSONObject.quote("EXACT_FOCUSED_PROMPT"))));
        invoke("click", current); invoke("click", current);
        assertEquals(1, page.sendEvaluations);
        assertTrue(completions.isEmpty());
        page.releaseSend(); answer(current);
        assertEquals(1, completions.size());
        assertEquals("completed", completions.get(0).getString("status"));
        assertEquals("Pro focused answer", completions.get(0).getString("reply"));
        assertTrue(dispatch.complete("consult-one"));
        assertFalse(dispatch.complete("consult-one"));
        assertFalse(dispatch.begin("consult-one"));
    }

    @Test public void cancelWhileSendCallbackIsPendingRetainsUncertaintyAndIgnoresTheLateCallback() throws Exception {
        start("consult-cancel"); page.holdSend = true;
        long current = generation(); invoke("click", current);
        AtomicInteger cancellationReplies = new AtomicInteger();
        transport.cancel((result, error) -> cancellationReplies.incrementAndGet());
        page.releaseSend(); answer(current); invoke("click", current);
        assertEquals(1, page.sendEvaluations);
        assertEquals(1, completions.size());
        assertEquals("uncertain", completions.get(0).getString("status"));
        assertTrue(completions.get(0).getBoolean("clicked"));
        assertEquals(1, cancellationReplies.get());
    }

    @Test public void previousAttemptCannotBeReplayedByActivityRecoveryOrAStaleGeneration() throws Exception {
        start("old-consult"); page.holdSend = true;
        long old = generation(); invoke("click", old);
        JSONObject interrupted = transport.interruptedOperation("old-consult");
        assertEquals("uncertain", interrupted.getString("status"));
        assertNull(transport.interruptedOperation("another-consult"));
        transport.cancel((result, error) -> { });
        transport.acknowledge("old-consult");
        assertNull(transport.interruptedOperation("old-consult"));
        start("new-consult"); page.releaseSend();
        assertEquals(1, completions.size());
        invoke("click", old);
        assertEquals(1, page.sendEvaluations);
        page.holdSend = false;
        long current = generation(); invoke("click", current); answer(current);
        assertEquals(2, page.sendEvaluations);
        assertEquals(2, completions.size());
        assertEquals("new-consult", completions.get(1).getString("operationId"));
    }

    @Test public void missingJavascriptCallbackTimesOutAndDestructionCannotCompleteTheToolTwice() throws Exception {
        start("consult-timeout"); page.holdSend = true;
        long current = generation(); invoke("click", current);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11)); invoke("checkDeadline", current);
        assertEquals(1, completions.size());
        assertEquals("uncertain", completions.get(0).getString("status"));
        transport.destroy(); page.releaseSend();
        assertEquals(1, completions.size());
        assertEquals(1, page.sendEvaluations);
    }
}
