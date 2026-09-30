package dev.mobilecodex.app;

import android.app.Application;
import android.content.Intent;
import dev.mobilecodex.app.core.Texts;
import dev.mobilecodex.app.core.Utf8Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowEnvironment;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class EngineTurnCompletionTest {
    private Application application;
    private Engine engine;
    private JSONObject session;
    private JSONObject state;
    private final List<JSONObject> errors = new ArrayList<>();

    @Before public void before() throws Exception {
        Texts.configure(true, Map.of());
        application = RuntimeEnvironment.getApplication();
        // API 32's isExternalStorageManager() reads the first external volume.
        // Robolectric starts with none; provide one so Engine can publish state.
        ShadowEnvironment.addExternalDir("engine-turn-completion");
        application.getSharedPreferences("notifications", 0).edit().putBoolean("enabled", false).commit();
        engine = new Engine(application);
        session = obj("id", "local-thread", "sessionVersion", 2, "codexThreadId", "remote-thread",
            "workspaceKey", "", "messages", array(), "approvalPending", true);
        set("sessions", array(session));
        set("active", session);
        set("threadId", "local-thread");
        set("turnId", "turn-one");
        set("ready", true);
        set("busy", true);
        runningTurns().put("local-thread", "turn-one");
        engine.attach(new Engine.Ui() {
            @Override public void event(String name, JSONObject data) {
                if ("state".equals(name)) state = data;
                if ("error".equals(name)) errors.add(data);
            }
            @Override public void approval(Engine.Approval approval) { }
        });
        CompletableFuture<JSONObject> snapshot = new CompletableFuture<>();
        engine.handle("state", obj(), (value, error) -> {
            if (error == null) snapshot.complete(value); else snapshot.completeExceptionally(error);
        });
        assertTrue(snapshot.get(5, TimeUnit.SECONDS).getBoolean("busy"));
        assertNotNull("Attached UI must receive the initial state", state);
    }

    @After public void after() throws Exception {
        engine.io.shutdownNow();
        ((ScheduledExecutorService) get("approvalTimer")).shutdownNow();
        Texts.configure(true, Map.of());
    }

    @Test public void interruptedTurnReportsStoppedAndPersistsStoppedImageReason() throws Exception {
        session.getJSONArray("messages").put(obj("id", "image-one", "role", "assistant", "kind", "image",
            "imageGroup", "turn-one", "imageStatus", "generating"));

        complete(obj("id", "turn-one", "status", "interrupted", "error", JSONObject.NULL));

        Intent notification = lastTaskNotification();
        assertEquals("interrupted", notification.getStringExtra("kind"));
        assertEquals("작업 중지됨", notification.getStringExtra("title"));
        assertEquals("Codex 작업이 중지되었습니다.", notification.getStringExtra("message"));
        assertEquals("local-thread", notification.getStringExtra("threadId"));
        assertFalse(state.getBoolean("busy"));
        assertEquals("작업 중지됨", state.getString("status"));
        assertFalse(session.getBoolean("approvalPending"));
        assertTrue(errors.isEmpty());
        JSONArray saved = new JSONArray(Utf8Files.read(new File(application.getFilesDir(), "sessions.json").toPath()));
        JSONObject image = saved.getJSONObject(0).getJSONArray("messages").getJSONObject(0);
        assertEquals("failed", image.getString("imageStatus"));
        assertEquals("작업이 중지되어 이미지 생성이 완료되지 않았습니다.", image.getString("imageError"));
    }

    @Test public void completedTurnRetainsSuccessfulNotification() throws Exception {
        complete(obj("id", "turn-one", "status", "completed", "error", JSONObject.NULL));

        Intent notification = lastTaskNotification();
        assertEquals("completed", notification.getStringExtra("kind"));
        assertEquals("답변 완료", notification.getStringExtra("title"));
        assertEquals("Codex가 작업을 마쳤습니다.", notification.getStringExtra("message"));
        assertFalse(state.getBoolean("busy"));
        assertTrue(errors.isEmpty());
    }

    @Test public void failedTurnWithoutErrorObjectStillReportsFailure() throws Exception {
        complete(obj("id", "turn-one", "status", "failed", "error", JSONObject.NULL));

        assertEquals("failed", lastTaskNotification().getStringExtra("kind"));
        assertEquals("작업 실패", lastTaskNotification().getStringExtra("title"));
        assertEquals("작업 실패", state.getString("status"));
        assertEquals(1, errors.size());
        assertEquals("작업 실패", errors.get(0).getString("message"));
    }

    @Test public void failureDetailsRemainVisibleAndLegacyCompletionStillWorks() throws Exception {
        complete(obj("id", "turn-one", "error", obj("message", "Usage limit reached")));
        assertEquals("failed", lastTaskNotification().getStringExtra("kind"));
        assertEquals("Usage limit reached", errors.get(0).getString("message"));

        complete(obj("id", "turn-two"));
        assertEquals("completed", lastTaskNotification().getStringExtra("kind"));
    }

    @Test public void interruptedBackgroundTurnLeavesVisibleTaskRunning() throws Exception {
        JSONObject background = obj("id", "background-local", "sessionVersion", 2,
            "codexThreadId", "background-remote", "messages", array(), "approvalPending", true);
        set("sessions", array(session, background));
        runningTurns().put("background-local", "background-turn");

        notifyCompletion("background-remote", obj("id", "background-turn", "status", "interrupted"));

        assertEquals("background-local", lastTaskNotification().getStringExtra("threadId"));
        assertEquals("interrupted", lastTaskNotification().getStringExtra("kind"));
        assertTrue(state.getBoolean("busy"));
        assertEquals("작업 중", state.getString("status"));
        assertEquals("turn-one", state.getString("turnId"));
        assertFalse(background.getBoolean("approvalPending"));
        assertTrue(session.getBoolean("approvalPending"));
        assertTrue(errors.isEmpty());
    }

    private void complete(JSONObject turn) throws Exception { notifyCompletion("remote-thread", turn); }

    private void notifyCompletion(String remoteThread, JSONObject turn) throws Exception {
        Method notification = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class);
        notification.setAccessible(true);
        state = null;
        engine.io.submit(() -> {
            notification.invoke(engine, "turn/completed", obj("threadId", remoteThread, "turn", turn));
            return null;
        }).get(5, TimeUnit.SECONDS);
        assertNotNull("Completion must publish fresh state; errors=" + errors, state);
    }

    private Intent lastTaskNotification() {
        List<Intent> broadcasts = shadowOf(application).getBroadcastIntents();
        for (int i = broadcasts.size() - 1; i >= 0; i--) {
            Intent intent = broadcasts.get(i);
            if (CodexNotificationReceiver.ACTION.equals(intent.getAction())) return intent;
        }
        throw new AssertionError("No task notification was broadcast");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> runningTurns() throws Exception { return (Map<String, String>) get("runningTurns"); }

    private Object get(String name) throws Exception {
        Field field = Engine.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(engine);
    }

    private void set(String name, Object value) throws Exception {
        Field field = Engine.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(engine, value);
    }
}
