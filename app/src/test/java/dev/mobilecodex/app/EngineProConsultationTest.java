package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import dev.mobilecodex.app.core.RpcClient;
import dev.mobilecodex.app.core.ToolCatalog;
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
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EngineProConsultationTest {
    private Context context;
    private Engine engine;
    private RpcClient connection;
    private ByteArrayOutputStream wire;
    private final List<RpcClient> connections = new ArrayList<>();
    private final List<Engine> engines = new ArrayList<>();
    private final List<JSONObject> calls = new ArrayList<>();
    private int threads, turns;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        for (String key : new String[]{"projects", "workspace", "settings"}) context.getSharedPreferences(key, 0).edit().clear().commit();
        clearSessions(); engine = newEngine(); wire = new ByteArrayOutputStream(); connection = rpc(wire);
        set(engine, "rpc", connection);
    }
    @After public void after() throws Exception {
        for (Engine value : engines) {
            value.io.shutdownNow(); ((ScheduledExecutorService) get(value, "approvalTimer")).shutdownNow();
        }
        for (RpcClient value : connections) value.close();
        clearSessions();
    }
    private void clearSessions() {
        File[] files = context.getFilesDir().listFiles((dir, name) -> name.startsWith("sessions"));
        if (files != null) for (File file : files) file.delete();
    }
    private Engine newEngine() throws Exception {
        Engine value = new Engine(context); engines.add(value); set(value, "account", obj("type", "chatgpt"));
        set(value, "models", array(obj("model", "test-model", "serviceTiers", array(obj("id", "priority")))));
        value.setTestTransport((method, params) -> {
            calls.add(obj("method", method, "params", params == null ? JSONObject.NULL : new JSONObject(params.toString())));
            if (method.equals("thread/start")) return obj("thread", obj("id", "remote-" + ++threads));
            if (method.equals("turn/start")) return obj("turn", obj("id", "turn-" + ++turns));
            if (method.equals("thread/resume") || method.equals("turn/steer") || method.equals("turn/interrupt")) return obj();
            throw new AssertionError(method);
        });
        return value;
    }
    private RpcClient rpc(ByteArrayOutputStream output) {
        RpcClient value = new RpcClient(new ByteArrayInputStream(new byte[0]), output, new RpcClient.Listener() {
            public void notification(String name, JSONObject params) { }
            public void request(Object id, String name, JSONObject params) { }
            public void disconnected(Throwable error) { }
        }); connections.add(value); return value;
    }
    private static Object get(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static void set(Object value, String name, Object content) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, content);
    }
    private JSONObject handle(Engine value, String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        value.handle(action, args, (reply, error) -> { if (error == null) result.complete(reply); else result.completeExceptionally(error); });
        return result.get(10, TimeUnit.SECONDS);
    }
    private JSONObject state() throws Exception { return handle(engine, "state", obj()); }
    private JSONObject send(boolean consult) throws Exception {
        handle(engine, "chat.send", obj("text", "current user request", "model", "test-model", "effort", "high", "fastMode", true, "consultPro", consult));
        return state();
    }
    private JSONObject last(String method) throws Exception {
        for (int i = calls.size() - 1; i >= 0; i--) if (method.equals(calls.get(i).optString("method"))) return calls.get(i).getJSONObject("params");
        throw new AssertionError(method);
    }
    private long count(String method) { return calls.stream().filter(c -> method.equals(c.optString("method"))).count(); }
    private void tool(Object id, String remote, String turn, String prompt) throws Exception {
        Method request = Engine.class.getDeclaredMethod("onRequest", RpcClient.class, Object.class, String.class, JSONObject.class); request.setAccessible(true);
        engine.io.submit(() -> {
            try { request.invoke(engine, connection, id, "item/tool/call", obj("threadId", remote, "turnId", turn,
                "tool", "mobile_consult_pro", "arguments", obj("prompt", prompt))); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).get(10, TimeUnit.SECONDS);
    }
    private JSONArray replies() throws Exception {
        JSONArray result = array();
        for (String line : new String(wire.toByteArray(), StandardCharsets.UTF_8).split("\n")) if (!line.isBlank()) result.put(new JSONObject(line));
        return result;
    }
    private JSONObject pending() throws Exception { return state().getJSONObject("pendingProConsultation"); }
    private JSONObject complete(JSONObject pending, String status, String reply) throws Exception {
        return handle(engine, "chat.pro.consult.complete", obj("operationId", pending.getString("operationId"), "threadId", pending.getString("threadId"),
            "status", status, "reply", reply, "reason", reply));
    }
    private JSONObject consultation(JSONObject session) throws Exception {
        JSONArray messages = session.getJSONArray("messages");
        for (int i = messages.length() - 1; i >= 0; i--) if ("proConsultation".equals(messages.getJSONObject(i).optString("kind"))) return messages.getJSONObject(i);
        throw new AssertionError("Missing consultation card");
    }
    private JSONObject session(String id) throws Exception {
        JSONArray sessions = (JSONArray) get(engine, "sessions");
        for (int i = 0; i < sessions.length(); i++) if (id.equals(sessions.getJSONObject(i).optString("id"))) return sessions.getJSONObject(i);
        throw new AssertionError(id);
    }
    private String token(String local) throws Exception {
        return (String) get(((Map<?, ?>) get(engine, "consultGrants")).get(local), "token");
    }

    @Test public void explicitConsultationPreservesCodexParametersAndOriginalRpcContinuation() throws Exception {
        JSONObject sent = send(true), params = last("turn/start");
        assertEquals("test-model", params.getString("model")); assertEquals("high", params.getString("effort")); assertEquals("priority", params.getString("serviceTierForTurn"));
        assertTrue(last("thread/start").getJSONArray("dynamicTools").toString().contains("mobile_consult_pro"));
        assertEquals("current user request", params.getJSONArray("input").getJSONObject(0).getString("text"));
        assertTrue(params.getJSONArray("input").getJSONObject(1).getString("text").contains("mobile_consult_pro"));
        String focused = "only this focused question and evidence";
        tool(42, params.getString("threadId"), sent.getString("turnId"), focused);
        JSONObject pending = pending(); assertEquals(focused, pending.getString("prompt")); assertEquals(0, pending.getJSONArray("uploads").length());
        assertEquals(0, replies().length()); assertTrue(state().getBoolean("busy")); assertTrue(state().getBoolean("proBusy"));
        ByteArrayOutputStream replacementOutput = new ByteArrayOutputStream(); set(engine, "rpc", rpc(replacementOutput));
        String answer = "review body " + "full text ".repeat(800) + "complete tail";
        complete(pending, "completed", answer);
        assertEquals(1, replies().length()); assertEquals(42, replies().getJSONObject(0).getInt("id"));
        JSONObject result = replies().getJSONObject(0).getJSONObject("result"); assertTrue(result.getBoolean("success"));
        assertEquals(answer, result.getJSONArray("contentItems").getJSONObject(0).getString("text")); assertEquals(0, replacementOutput.size());
        assertEquals(1, count("turn/start")); assertFalse(state().getBoolean("proBusy")); assertTrue(state().getBoolean("busy"));
        JSONObject card = consultation(session(sent.getString("threadId"))); assertEquals("completed", card.getString("status")); assertEquals(answer, card.getString("text"));
        assertTrue(complete(pending, "completed", "duplicate").getBoolean("stale")); assertEquals(1, replies().length());
    }

    @Test public void ordinaryRequestsAndForeignOrOldTurnsCannotInvokePro() throws Exception {
        JSONObject normal = send(false), params = last("turn/start"); assertEquals(1, params.getJSONArray("input").length());
        tool(1, params.getString("threadId"), normal.getString("turnId"), "not authorized"); assertFalse(replies().getJSONObject(0).getJSONObject("result").getBoolean("success"));
        handle(engine, "chat.steer", obj("text", "explicit followup", "expectedTurnId", normal.getString("turnId"), "consultPro", true));
        assertTrue(last("turn/steer").getJSONArray("input").toString().contains("mobile_consult_pro"));
        tool(2, params.getString("threadId"), "old-turn", "wrong turn"); tool(3, "foreign-thread", normal.getString("turnId"), "foreign");
        assertEquals(3, replies().length()); assertTrue(state().isNull("pendingProConsultation"));
        tool(4, params.getString("threadId"), normal.getString("turnId"), "correct turn"); assertNotNull(pending());
    }

    @Test public void failedConsultationConsumesGrantButNewExplicitSteerGetsOneNewGrant() throws Exception {
        JSONObject sent = send(true); String remote = last("turn/start").getString("threadId");
        tool(1, remote, sent.getString("turnId"), "review"); complete(pending(), "failed", "network failure");
        tool(2, remote, sent.getString("turnId"), "automatic retry"); assertEquals(2, replies().length());
        assertFalse(replies().getJSONObject(0).getJSONObject("result").getBoolean("success")); assertTrue(state().isNull("pendingProConsultation"));
        handle(engine, "chat.steer", obj("text", "normal steer", "expectedTurnId", sent.getString("turnId"))); tool(3, remote, sent.getString("turnId"), "still no retry");
        assertEquals(1, last("turn/steer").getJSONArray("input").length()); assertTrue(state().isNull("pendingProConsultation"));
        handle(engine, "chat.steer", obj("text", "ask again explicitly", "expectedTurnId", sent.getString("turnId"), "consultPro", true));
        tool(4, remote, sent.getString("turnId"), "new explicit consultation"); assertNotNull(pending());
    }

    @Test public void completionTargetsOriginalConversationAndReattachmentDoesNotCreateANewCall() throws Exception {
        JSONObject a = send(true); tool(11, last("turn/start").getString("threadId"), a.getString("turnId"), "A question"); JSONObject pending = pending();
        List<JSONObject> reattached = new ArrayList<>(); engine.attach(new Engine.Ui() {
            public void event(String name, JSONObject data) { if (name.equals("pro.consult")) reattached.add(data); }
            public void approval(Engine.Approval approval) { }
        }); state(); assertEquals(1, reattached.size()); assertEquals(pending.getString("operationId"), reattached.get(0).getString("operationId"));
        handle(engine, "chat.new", obj("workspaceKey", "")); JSONObject b = send(false);
        complete(pending, "completed", "A answer");
        assertEquals(b.getString("threadId"), state().getString("threadId")); assertEquals(1, state().getJSONArray("messages").length());
        assertEquals("A answer", consultation(session(a.getString("threadId"))).getString("text")); assertEquals(1, replies().length());
        assertEquals(2, count("turn/start"));
    }

    @Test public void stopCancelsWaitingProAndInterruptsSameCodexTurnExactlyOnce() throws Exception {
        JSONObject sent = send(true); String remote = last("turn/start").getString("threadId");
        tool(9, remote, sent.getString("turnId"), "question"); JSONObject pending = pending(); handle(engine, "chat.stop", obj());
        assertEquals(1, replies().length()); assertFalse(replies().getJSONObject(0).getJSONObject("result").getBoolean("success"));
        assertEquals(remote, last("turn/interrupt").getString("threadId")); assertEquals(sent.getString("turnId"), last("turn/interrupt").getString("turnId"));
        assertEquals("cancelled", consultation(session(sent.getString("threadId"))).getString("status")); assertTrue(state().isNull("pendingProConsultation"));
        complete(pending, "completed", "late reply"); assertEquals(1, replies().length());
    }

    @Test public void invalidTransportCompletionsResolveFailureImmediatelyInsteadOfLeavingAWaiter() throws Exception {
        for (String invalid : new String[]{"", "x".repeat(200001)}) {
            if (turns > 0) handle(engine, "chat.new", obj("workspaceKey", ""));
            JSONObject sent = send(true); tool(turns, last("turn/start").getString("threadId"), sent.getString("turnId"), "question");
            complete(pending(), "completed", invalid); assertTrue(state().isNull("pendingProConsultation"));
            assertFalse(replies().getJSONObject(replies().length() - 1).getJSONObject("result").getBoolean("success"));
        }
        handle(engine, "chat.new", obj("workspaceKey", "")); JSONObject sent = send(true); tool(99, last("turn/start").getString("threadId"), sent.getString("turnId"), "question");
        complete(pending(), "unknown_status", "body"); assertTrue(state().isNull("pendingProConsultation")); assertEquals(3, replies().length());
    }

    @Test public void legacyConversationIsOfferedUntilItsMcpCheckActuallyFails() throws Exception {
        JSONObject saved = obj("id", "local-legacy", "sessionVersion", 2, "codexThreadId", "legacy-remote", "workspaceKey", "", "workspace", "",
            "messages", array(), "imageHistoryVersion", 1);
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(saved).toString());
        engine = newEngine(); set(engine, "rpc", connection); handle(engine, "chat.resume", obj("id", "local-legacy"));
        // Opening an old conversation does not resume it on the server, so nothing has been checked yet.
        assertTrue(state().getBoolean("consultProAvailable"));
        set(engine, "consultMcpThread", "legacy-remote"); set(engine, "consultMcpReady", false); set(engine, "consultMcpError", "Pro 문의 MCP 서버(mobile_codex_pro)가 시작되지 않았습니다.");
        JSONObject failed = state(); assertFalse(failed.getBoolean("consultProAvailable"));
        assertTrue(failed.getString("consultProUnavailableReason").contains("mobile_codex_pro"));
        set(engine, "consultMcpReady", true); JSONObject ready = state();
        assertTrue(ready.getBoolean("consultProAvailable")); assertEquals("", ready.getString("consultProUnavailableReason"));
    }
    @Test public void consultToolIsRecognisedInPlainQualifiedAndListShapes() {
        assertTrue(Engine.hasConsultTool(obj("consult_pro", obj())));
        assertTrue(Engine.hasConsultTool(obj("mcp__mobile_codex_pro__consult_pro", obj())));
        assertTrue(Engine.hasConsultTool(array(obj("name", "consult_pro"))));
        assertTrue(Engine.hasConsultTool(array("mobile_codex_pro.consult_pro")));
        assertFalse(Engine.hasConsultTool(obj("consult_professional", obj())));
        assertFalse(Engine.hasConsultTool(obj()));
        assertFalse(Engine.hasConsultTool(null));
    }
    @Test public void mcpTokenUsesSameQuotaAndLegacyThreadKeepsItsRemoteIdentity() throws Exception {
        JSONObject saved = obj("id", "local-legacy", "sessionVersion", 2, "codexThreadId", "legacy-remote", "workspaceKey", "", "workspace", "",
            "messages", array(obj("id", "historic", "role", "assistant", "text", "original remote history")), "imageHistoryVersion", 1);
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(saved).toString());
        engine = newEngine(); set(engine, "rpc", connection); set(engine, "consultMcpReady", true); set(engine, "consultMcpThread", "legacy-remote"); handle(engine, "chat.resume", obj("id", "local-legacy"));
        JSONObject sent = send(true); assertEquals("legacy-remote", last("turn/start").getString("threadId")); assertEquals(0, count("thread/start"));
        String token = token(sent.getString("threadId")); assertTrue(last("turn/start").getJSONArray("input").toString().contains(token));
        CompletableFuture<JSONObject> mcp = new CompletableFuture<>(); engine.consultPro(token, "MCP focused question", (value, error) -> mcp.complete(value));
        JSONObject pending = pending(); assertFalse(mcp.isDone());
        tool(51, "legacy-remote", sent.getString("turnId"), "second native attempt"); assertFalse(replies().getJSONObject(0).getJSONObject("result").getBoolean("success"));
        complete(pending, "completed", "shared quota answer"); assertTrue(mcp.get(5, TimeUnit.SECONDS).getBoolean("success"));
        assertEquals("shared quota answer", mcp.get().getJSONArray("contentItems").getJSONObject(0).getString("text"));
        CompletableFuture<JSONObject> retry = new CompletableFuture<>(); engine.consultPro(token, "retry", (value, error) -> retry.complete(value));
        assertFalse(retry.get(5, TimeUnit.SECONDS).getBoolean("success")); assertTrue(state().isNull("pendingProConsultation"));
        assertEquals("original remote history", session("local-legacy").getJSONArray("messages").getJSONObject(0).getString("text"));
    }

    @Test public void processRestartMarksPendingCardFailedWithoutResendingOrCreatingAGrant() throws Exception {
        JSONObject sent = send(true); tool(77, last("turn/start").getString("threadId"), sent.getString("turnId"), "pending question");
        Engine reopened = newEngine(); handle(reopened, "chat.resume", obj("id", sent.getString("threadId")));
        JSONObject state = handle(reopened, "state", obj()); assertFalse(state.getBoolean("proBusy")); assertTrue(state.isNull("pendingProConsultation"));
        assertEquals("failed", consultation((JSONObject) get(reopened, "active")).getString("status"));
        assertEquals(0, ((Map<?, ?>) get(reopened, "consultGrants")).size()); assertEquals(1, count("turn/start"));
    }

    @Test public void consultationStartsFreshFromLegacyWebHistoryAndPersistsItsOwnConversation() throws Exception {
        JSONObject legacy = obj("id", "local-namespace", "sessionVersion", 2, "codexThreadId", "existing-codex-remote", "workspaceKey", "", "workspace", "",
            "consultToolsVersion", 1, "imageHistoryVersion", 1, "chatConversationId", "legacy-web", "chatConversationPath", "/c/legacy-web",
            "chatProjectPath", "/g/g-p-legacy/project", "messages", array(obj("id", "old-message", "role", "assistant", "text", "OLD_READ_ONLY_AGENTS_DUMP")));
        Utf8Files.write(new File(context.getFilesDir(), "sessions.json").toPath(), array(legacy).toString());
        engine = newEngine(); set(engine, "rpc", connection); handle(engine, "chat.resume", obj("id", "local-namespace"));
        JSONObject first = send(true);
        tool(101, "existing-codex-remote", first.getString("turnId"), "focused question only");
        JSONObject prepared = pending();
        assertEquals("", prepared.getString("chatConversationId")); assertEquals("", prepared.getString("chatConversationPath"));
        assertEquals("/g/g-p-legacy/project", prepared.getString("chatProjectPath")); assertEquals("focused question only", prepared.getString("prompt"));
        handle(engine, "chat.pro.consult.complete", obj("operationId", prepared.getString("operationId"), "threadId", "local-namespace", "status", "completed",
            "reply", "fresh consultation answer", "chatConversationId", "consult-web", "chatConversationPath", "/g/g-p-consult/c/consult-web", "chatProjectPath", "/g/g-p-consult/project"));
        JSONObject stored = session("local-namespace");
        assertEquals("legacy-web", stored.getString("chatConversationId")); assertEquals("/c/legacy-web", stored.getString("chatConversationPath"));
        assertEquals("/g/g-p-legacy/project", stored.getString("chatProjectPath"));
        assertEquals("consult-web", stored.getString("proConsultConversationId")); assertEquals("/g/g-p-consult/c/consult-web", stored.getString("proConsultConversationPath"));
        assertEquals("/g/g-p-consult/project", stored.getString("proConsultProjectPath"));
        assertEquals("OLD_READ_ONLY_AGENTS_DUMP", stored.getJSONArray("messages").getJSONObject(0).getString("text"));

        engine = newEngine(); set(engine, "rpc", connection); handle(engine, "chat.resume", obj("id", "local-namespace"));
        JSONObject second = send(true); tool(102, "existing-codex-remote", second.getString("turnId"), "next focused question");
        JSONObject next = pending();
        assertEquals("consult-web", next.getString("chatConversationId")); assertEquals("/g/g-p-consult/c/consult-web", next.getString("chatConversationPath"));
        assertEquals("/g/g-p-consult/project", next.getString("chatProjectPath")); assertEquals("next focused question", next.getString("prompt"));
        assertEquals("existing-codex-remote", last("turn/start").getString("threadId")); assertEquals(0, count("thread/start")); assertEquals(2, count("turn/start"));
        assertEquals("legacy-web", session("local-namespace").getString("chatConversationId"));
        assertEquals("OLD_READ_ONLY_AGENTS_DUMP", session("local-namespace").getJSONArray("messages").getJSONObject(0).getString("text"));
    }
}
