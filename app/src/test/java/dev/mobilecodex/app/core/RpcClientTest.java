package dev.mobilecodex.app.core;
import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static dev.mobilecodex.app.core.Json.*;
public class RpcClientTest {
    private static RpcClient.Listener listener(AtomicReference<String> received) {
        return new RpcClient.Listener() {
            public void notification(String method, JSONObject params) { received.set(method); }
            public void request(Object id, String method, JSONObject params) { received.set(id + ":" + method); }
            public void disconnected(Throwable e) { received.set("closed"); }
        };
    }
    @Test public void routesOutOfOrderResponsesAndPreservesServerIds() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); AtomicReference<String> event = new AtomicReference<>();
        try (RpcClient rpc = new RpcClient(new ByteArrayInputStream(new byte[0]), out, listener(event))) {
            CompletableFuture<JSONObject> a = rpc.request("one", obj()), b = rpc.request("two", obj());
            rpc.accept(obj("id", 2, "result", obj("value", "second")));
            assertEquals("second", b.get().getString("value")); assertFalse(a.isDone());
            rpc.accept(obj("id", "approval-7", "method", "item/commandExecution/requestApproval", "params", obj()));
            assertEquals("approval-7:item/commandExecution/requestApproval", event.get());
            rpc.respond("approval-7", obj("decision", "accept"));
            String[] lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
            assertEquals("approval-7", parse(lines[2]).getString("id"));
            rpc.accept(obj("id", 1, "error", obj("code", -1, "message", "denied")));
            assertThrows(ExecutionException.class, () -> a.get());
        }
    }
    @Test public void closingFailsOutstandingRequests() {
        RpcClient rpc = new RpcClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), listener(new AtomicReference<>()));
        CompletableFuture<JSONObject> pending = rpc.request("turn/start", obj()); rpc.close();
        assertTrue(pending.isCompletedExceptionally()); assertTrue(rpc.request("account/read", obj()).isCompletedExceptionally());
    }
    @Test public void readsJsonLinesFromProcessStream() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        try (RpcClient rpc = new RpcClient(new ByteArrayInputStream("{\"method\":\"tick\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(), new RpcClient.Listener() {
            public void notification(String m, JSONObject p) { if (m.equals("tick")) got.countDown(); }
            public void request(Object id, String m, JSONObject p) {}
            public void disconnected(Throwable e) {}
        })) { rpc.start(); assertTrue(got.await(2, TimeUnit.SECONDS)); }
    }
    @Test public void eofPreservesASafeCauseForOutstandingRequests() throws Exception {
        AtomicReference<Throwable> closed = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        RpcClient rpc = new RpcClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), new RpcClient.Listener() {
            public void notification(String m, JSONObject p) {}
            public void request(Object id, String m, JSONObject p) {}
            public void disconnected(Throwable error) { closed.set(error); done.countDown(); }
        });
        CompletableFuture<JSONObject> request = rpc.request("plugin/list", obj()); rpc.start();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(closed.get().getMessage().contains("실행이 종료"));
        assertThrows(ExecutionException.class, request::get);
    }
    @Test public void malformedLineDoesNotDiscardTheNextFramedReply() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        try (RpcClient rpc = new RpcClient(new ByteArrayInputStream("not json\n{\"method\":\"tick\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(), new RpcClient.Listener() {
            public void notification(String m, JSONObject p) { if (m.equals("tick")) got.countDown(); }
            public void request(Object id, String m, JSONObject p) {}
            public void disconnected(Throwable error) {}
        })) { rpc.start(); assertTrue(got.await(2, TimeUnit.SECONDS)); }
    }
    @Test public void oversizedLineReportsTheLimitInsteadOfAGenericDisconnect() throws Exception {
        byte[] input = new byte[8 * 1024 * 1024 + 1]; java.util.Arrays.fill(input, (byte) 'x');
        AtomicReference<Throwable> closed = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        try (RpcClient rpc = new RpcClient(new ByteArrayInputStream(input), new ByteArrayOutputStream(), new RpcClient.Listener() {
            public void notification(String m, JSONObject p) {}
            public void request(Object id, String m, JSONObject p) {}
            public void disconnected(Throwable error) { closed.set(error); done.countDown(); }
        })) { rpc.start(); assertTrue(done.await(3, TimeUnit.SECONDS)); assertTrue(closed.get().getMessage().contains("8 MiB")); }
    }
    @Test public void closeRaceFailsEveryQueuedRequest() throws Exception {
        RpcClient rpc = new RpcClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), listener(new AtomicReference<>()));
        ExecutorService workers = Executors.newFixedThreadPool(4); java.util.ArrayList<Future<CompletableFuture<JSONObject>>> started = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) started.add(workers.submit(() -> rpc.request("skills/list", obj())));
        rpc.close();
        for (Future<CompletableFuture<JSONObject>> value : started) assertTrue(value.get().isCompletedExceptionally());
        workers.shutdownNow();
    }
}
