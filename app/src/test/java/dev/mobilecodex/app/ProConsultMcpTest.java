package dev.mobilecodex.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Uses actual localhost framing and callbacks, with no Android/UI or web dependency. */
public class ProConsultMcpTest {
    private File script;
    private final List<ProConsultMcp> bridges = new ArrayList<>();
    private final List<Socket> sockets = new ArrayList<>();
    private final BlockingQueue<ProConsultMcp.Completion> completions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> prompts = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> cancellations = new LinkedBlockingQueue<>();
    private final AtomicInteger invoked = new AtomicInteger();
    private int port;
    private String secret;

    @Before public void before() throws Exception {
        script = Files.createTempFile("pro-consult-adapter-", ".py").toFile();
        Files.write(script.toPath(), "# transport fixture\n".getBytes(StandardCharsets.UTF_8));
    }
    @After public void after() throws Exception {
        for (Socket socket : sockets) socket.close();
        for (ProConsultMcp bridge : bridges) bridge.close();
        Files.deleteIfExists(script.toPath());
    }

    private ProConsultMcp start(long timeoutMillis) throws Exception {
        ProConsultMcp bridge = new ProConsultMcp(new ProConsultMcp.Handler() {
            @Override public void consult(String token, String prompt, ProConsultMcp.Completion reply) {
                invoked.incrementAndGet(); prompts.add(prompt); completions.add(reply);
            }
            @Override public void cancelled(String token) { cancellations.add(token); }
        }, script, timeoutMillis);
        bridges.add(bridge);
        List<String> override = bridge.configOverrides("/absolute/python3");
        assertEquals("-c", override.get(0));
        String config = override.get(1);
        Matcher args = Pattern.compile("args=(\\[[^\\]]*\\])").matcher(config);
        assertTrue(args.find());
        JSONArray argv = new JSONArray(args.group(1));
        assertEquals(script.getAbsolutePath(), argv.getString(0));
        assertEquals("--port", argv.getString(1));
        port = Integer.parseInt(argv.getString(2));
        Matcher env = Pattern.compile("MC_PRO_CONSULT_SECRET=\"([a-f0-9]{64})\"").matcher(config);
        assertTrue(env.find()); secret = env.group(1);
        assertTrue(config.contains("tool_timeout_sec=660"));
        return bridge;
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port);
        socket.setSoTimeout(3000); sockets.add(socket); return socket;
    }
    private JSONObject request(String prompt) throws Exception {
        return new JSONObject().put("method", "consult").put("secret", secret)
            .put("callId", "a".repeat(32)).put("requestToken", "explicit-grant").put("prompt", prompt);
    }
    private void send(Socket socket, JSONObject value) throws Exception {
        socket.getOutputStream().write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private JSONObject receive(Socket socket) throws Exception {
        String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        assertNotNull("Expected a bounded bridge response", line); return new JSONObject(line);
    }
    private ProConsultMcp.Completion completion() throws Exception {
        ProConsultMcp.Completion reply = completions.poll(2, TimeUnit.SECONDS);
        assertNotNull("Engine handler must be dispatched asynchronously", reply); return reply;
    }
    private void assertFailedOrClosed(Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = input.readLine();
        if (line != null) assertFalse("A cancelled operation must never return success", new JSONObject(line).getBoolean("success"));
        assertEquals("The cancelled connection must close", -1, input.read());
    }

    @Test public void authenticatedAsyncReplyCompletesOnceAndDoesNotCancelNative() throws Exception {
        start(3000); Socket socket = connect(); send(socket, request("상담 질문"));
        ProConsultMcp.Completion reply = completion();
        assertEquals("상담 질문", prompts.poll(1, TimeUnit.SECONDS));
        reply.success("완료된 답변"); reply.error("late duplicate must be ignored");
        JSONObject result = receive(socket);
        assertTrue(result.getBoolean("success")); assertEquals("완료된 답변", result.getString("text"));
        assertEquals("a".repeat(32), result.getString("callId"));
        assertEquals(1, invoked.get());
        assertNull(cancellations.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test public void cliOverrideForwardsOnlyPreparedRuntimeEnvironmentAndPrivateSecret() throws Exception {
        ProConsultMcp bridge = start(3000);
        String config = bridge.configOverrides("/absolute/python3").get(1);
        Matcher inherited = Pattern.compile("env_vars=(\\[[^\\]]*\\])").matcher(config);
        assertTrue("MCP's filtered child environment needs an explicit runtime allowlist", inherited.find());
        JSONArray names = new JSONArray(inherited.group(1));
        Set<String> forwarded = new HashSet<>();
        for (int index = 0; index < names.length(); index++) forwarded.add(names.getString(index));
        assertEquals(Set.of("PYTHONHOME", "PYTHONUSERBASE", "PYTHONUTF8", "LD_LIBRARY_PATH", "LD_PRELOAD",
            "MC_PREFIX", "MC_NATIVE_DIR", "MC_PYTHON", "TMPDIR", "TMP", "TEMP", "HOME", "PATH", "SHELL", "LANG"), forwarded);
        assertFalse(config.contains("CODEX_HOME"));
        assertFalse(config.contains("OPENAI_API_KEY"));
        assertFalse(config.contains("GITHUB_TOKEN"));
        assertFalse(config.contains("GITHUB_OAUTH"));
        assertFalse("The bridge secret must be process-specific, not inherited", forwarded.contains("MC_PRO_CONSULT_SECRET"));
        assertTrue(config.contains("env={MC_PRO_CONSULT_SECRET=\"" + secret + "\"}"));
    }

    @Test public void wrongSecretAndInvalidPromptNeverReachEngineHandler() throws Exception {
        start(3000);
        Socket unauthenticated = connect(); send(unauthenticated, request("question").put("secret", "b".repeat(64)));
        JSONObject denied = receive(unauthenticated);
        assertFalse(denied.getBoolean("success")); assertTrue(denied.getString("text").contains("authentication"));
        Socket oversized = connect(); send(oversized, request("😀".repeat(50_001)));
        assertFalse(receive(oversized).getBoolean("success"));
        assertEquals(0, invoked.get());
    }

    @Test public void exactUnicodePromptAndFullEscapedResultAreNotTruncated() throws Exception {
        start(5000); Socket socket = connect(); String prompt = "😀".repeat(50_000); send(socket, request(prompt));
        ProConsultMcp.Completion reply = completion(); assertEquals(prompt, prompts.poll(1, TimeUnit.SECONDS));
        String text = "\u0000".repeat(200_000); reply.success(text);
        JSONObject result = receive(socket); assertTrue(result.getBoolean("success")); assertEquals(text, result.getString("text"));
    }

    @Test public void clientDisconnectCancelsNativeOnceAndLateCompletionIsIgnored() throws Exception {
        start(3000); Socket socket = connect(); send(socket, request("question"));
        ProConsultMcp.Completion reply = completion(); socket.close();
        assertEquals("explicit-grant", cancellations.poll(1, TimeUnit.SECONDS));
        reply.success("too late");
        assertNull(cancellations.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test public void authenticatedCancellationReturnsFailureAndCancelsNativeOnce() throws Exception {
        start(3000); Socket socket = connect(); send(socket, request("question")); completion();
        send(socket, new JSONObject().put("method", "cancel").put("secret", secret)
            .put("callId", "a".repeat(32)).put("requestToken", "explicit-grant"));
        JSONObject result = receive(socket); assertFalse(result.getBoolean("success"));
        assertTrue(result.getString("text").contains("cancelled"));
        assertEquals("explicit-grant", cancellations.poll(1, TimeUnit.SECONDS));
        assertNull(cancellations.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test public void absoluteTimeoutAndCloseCancelPendingWithoutBlockingCallback() throws Exception {
        start(150); Socket timedOut = connect(); send(timedOut, request("question")); completion();
        assertEquals("explicit-grant", cancellations.poll(2, TimeUnit.SECONDS));
        assertFailedOrClosed(timedOut);
        ProConsultMcp bridge = start(3000); Socket pending = connect(); send(pending, request("question"));
        ProConsultMcp.Completion reply = completion(); bridge.close();
        assertEquals("explicit-grant", cancellations.poll(1, TimeUnit.SECONDS));
        reply.success("late completion");
        assertFailedOrClosed(pending);
        assertThrows(IllegalArgumentException.class, () -> bridge.configOverrides("/absolute/python3"));
    }

    @Test public void concurrentClientLimitRejectsThirdBeforeDispatch() throws Exception {
        start(3000);
        Socket first = connect(); send(first, request("first")); ProConsultMcp.Completion one = completion();
        Socket second = connect(); send(second, request("second")); ProConsultMcp.Completion two = completion();
        Socket third = connect(); assertEquals(-1, third.getInputStream().read());
        assertEquals(2, invoked.get()); one.error("stopped"); two.error("stopped");
        assertFalse(receive(first).getBoolean("success")); assertFalse(receive(second).getBoolean("success"));
    }

    @Test public void invalidUtf8AndOversizedFrameNeverReachEngine() throws Exception {
        start(3000); Socket malformed = connect();
        malformed.getOutputStream().write(new byte[]{(byte) 0xc0, (byte) 0xaf, '\n'});
        assertEquals(-1, malformed.getInputStream().read());
        Socket oversized = connect();
        try { oversized.getOutputStream().write(new byte[ProConsultMcp.MAX_FRAME_BYTES + 1]); }
        catch (java.io.IOException expectedPeerClose) { }
        assertEquals(-1, oversized.getInputStream().read()); assertEquals(0, invoked.get());
    }
}
