package dev.mobilecodex.app;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import dev.mobilecodex.app.core.*;
import org.json.JSONObject;
import java.io.*;
import java.util.Set;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class EngineForegroundLifecycleTest {
    private Object field(Object object, String name) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private void field(Object object, String name, Object value) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    private static class Child extends Process {
        boolean alive = true;
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return alive; }
        public void destroy() { alive = false; }
    }
    private Engine engine() { return new Engine(RuntimeEnvironment.getApplication()); }
    private void eof(Engine engine, Child child) throws Exception {
        field(engine, "process", child); field(engine, "ready", true);
        engine.io.submit(() -> engine.codexDisconnected(child, null, null, new RuntimeFailure.Tail())).get(5, TimeUnit.SECONDS);
    }
    @Test public void eofRetainsLinuxInstallThenStopsServiceWhenLastWorkFinishes() throws Exception {
        Engine engine = engine(); Object linux = field(engine, "linux");
        try {
            field(linux, "busy", true); eof(engine, new Child());
            assertTrue(engine.hasForegroundWork());
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStoppedService());
            field(linux, "busy", false); engine.reconcileService(); engine.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(engine.hasForegroundWork());
            assertEquals(EngineService.class.getName(), shadowOf(RuntimeEnvironment.getApplication()).getNextStoppedService().getComponent().getClassName());
        } finally { field(linux, "busy", false); engine.io.shutdownNow(); }
    }
    @Test public void agentSetupSurvivesEofAndIdleRunnerDoesNotRetainNotification() throws Exception {
        Engine engine = engine();
        AgentBridge bridge = new AgentBridge(RuntimeEnvironment.getApplication(), (DevTools)field(engine,"devTools"),
            ((CodexHome)field(engine,"codexHome")).root(), new File("unused"), (m,p,r)->{}, (n,d)->{});
        field(engine, "agentBridge", bridge);
        @SuppressWarnings("unchecked") Set<String> setups = (Set<String>)field(bridge,"setups");
        try {
            setups.add("claude"); eof(engine, new Child()); assertTrue(engine.hasForegroundWork());
            setups.clear(); assertFalse(engine.hasForegroundWork());
        } finally { engine.io.shutdownNow(); }
    }
    @Test public void emptyEofStopsServiceAndStaleEofCannotStopReplacement() throws Exception {
        Engine engine = engine();
        try {
            Child stale = new Child(), replacement = new Child();
            field(engine,"process",replacement); field(engine,"ready",true);
            engine.codexDisconnected(stale,null,null,new RuntimeFailure.Tail());
            assertTrue(engine.hasForegroundWork()); assertTrue(replacement.alive);
            eof(engine,replacement); assertFalse(engine.hasForegroundWork());
        } finally { engine.io.shutdownNow(); }
    }
    @Test public void terminalControlsRespondOutsideOccupiedEngineQueueAndTerminalSurvivesEof() throws Exception {
        Engine engine = engine(); TerminalSession terminal = (TerminalSession)field(engine,"terminal");
        CountDownLatch release = new CountDownLatch(1), occupied = new CountDownLatch(1);
        try {
            String id = terminal.start(TerminalSessionTest.child("flood")).getString("id");
            eof(engine,new Child()); assertTrue(terminal.busy()); assertTrue(engine.hasForegroundWork());
            engine.io.execute(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
            assertTrue(occupied.await(5,TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) call(engine,"terminal.input",obj("id",id,"text","x".repeat(16000)));
            assertTrue(call(engine,"terminal.state",obj()).getBoolean("running"));
            call(engine,"terminal.stop",obj("id",id)); call(engine,"terminal.stop",obj("id",id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(terminal.busy()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(terminal.busy()); assertFalse(engine.hasForegroundWork());
        } finally { release.countDown(); terminal.stop(null); engine.io.shutdownNow(); }
    }
    private JSONObject call(Engine engine, String action, JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        engine.handle(action,args,(r,e)->{if(e==null)result.complete(r);else result.completeExceptionally(e);});
        return result.get(1,TimeUnit.SECONDS);
    }
    @Test public void notificationStopCancelsTerminalWhileCodexQueueIsOccupied() throws Exception {
        Engine engine=engine();TerminalSession terminal=(TerminalSession)field(engine,"terminal");
        CountDownLatch release=new CountDownLatch(1),occupied=new CountDownLatch(1);
        try {
            terminal.start(TerminalSessionTest.child("blocked"));
            engine.io.execute(()->{occupied.countDown();try{release.await();}catch(InterruptedException ignored){}});
            assertTrue(occupied.await(5,TimeUnit.SECONDS));
            engine.stop();assertTrue(terminal.snapshot().getBoolean("stopping")||!terminal.busy());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(terminal.busy()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(terminal.busy());
        } finally {release.countDown();terminal.stop(null);engine.io.shutdownNow();}
    }
}
