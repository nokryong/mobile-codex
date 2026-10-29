package dev.mobilecodex.app.core;

import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class TerminalSessionTest {
    // A real child JVM: exercises pipe flushing, newlines, process identity and exit.
    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args.length > 0) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { Thread.sleep(30000); } catch (InterruptedException ignored) {} }));
                System.out.println("NOT_READING");
                if (args[0].equals("flood")) while (true) { System.out.print("x".repeat(8192)); System.out.flush(); Thread.sleep(1); }
                Thread.sleep(30000); return;
            }
            System.out.println("PASTE_CODE");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
            if (!"fixture-private-code".equals(in.readLine())) System.exit(31);
            System.out.println("CODE_ACCEPTED");
            if (!"".equals(in.readLine())) System.exit(32);
            System.out.println("LOGIN_INPUT_FINISHED");
        }
    }
    public static ProcessBuilder child(String... args) throws Exception {
        String binary = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        File location = new File(Child.class.getResource("TerminalSessionTest$Child.class").toURI());
        for (int i = 0; i < Child.class.getName().split("\\.").length; i++) location = location.getParentFile();
        String classes = location.getPath();
        List<String> command = new ArrayList<>(List.of(binary, "-cp", classes, Child.class.getName()));
        command.addAll(List.of(args)); return new ProcessBuilder(command);
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call());
    }
    @Test public void repliesGoToSameProcessAndNeverBecomeCommandsOrLoggedSecrets() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        TerminalSession terminal = new TerminalSession((name, data) -> events.add(name + data));
        try {
            JSONObject started = terminal.start(child()); String id = started.getString("id");
            await(() -> terminal.snapshot().getString("output").contains("PASTE_CODE"));
            assertThrows(IOException.class, () -> terminal.start(child()));
            assertThrows(IOException.class, () -> terminal.input("old-session", "fixture-private-code"));
            assertThrows(IOException.class, () -> terminal.input(id, "x".repeat(16385)));
            terminal.input(id, "fixture-private-code\r\n");
            await(() -> terminal.snapshot().getString("output").contains("CODE_ACCEPTED"));
            assertTrue(terminal.busy()); // Reading state again doesn't restart the process.
            assertEquals(id, terminal.snapshot().getString("id"));
            terminal.input(id, "");
            await(() -> !terminal.busy());
            assertEquals(0, terminal.snapshot().getInt("exitCode"));
            assertTrue(terminal.snapshot().getString("output").contains("LOGIN_INPUT_FINISHED"));
            assertFalse(String.join("", events).contains("fixture-private-code"));
            assertThrows(IOException.class, () -> terminal.input(id, "late-code"));
        } finally { terminal.stop(null); }
    }
    @Test public void stopAndOldSessionCannotWriteOrStopReplacementProcess() throws Exception {
        TerminalSession terminal = new TerminalSession((name, data) -> {});
        try {
            String old = terminal.start(child()).getString("id");
            terminal.stop(old); await(() -> !terminal.busy());
            String fresh = terminal.start(child()).getString("id");
            assertNotEquals(old, fresh);
            assertThrows(IOException.class, () -> terminal.input(old, "old-code"));
            assertThrows(IOException.class, () -> terminal.stop(old));
            assertTrue(terminal.busy());
            terminal.stop(fresh); await(() -> !terminal.busy());
        } finally { terminal.stop(null); }
    }

    @Test public void blockedInputDoesNotPreventStateReadsOrStop() throws Exception {
        TerminalSession terminal = new TerminalSession((name, data) -> {});
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            String id = terminal.start(child("blocked")).getString("id");
            await(() -> terminal.snapshot().getString("output").contains("NOT_READING"));
            assertTrue(terminal.input(id, "x".repeat(16384)).getBoolean("ok"));
            List<Future<JSONObject>> inputs = new ArrayList<>();
            for (int i = 0; i < 32; i++) inputs.add(workers.submit(() -> terminal.input(id, "x".repeat(16000))));
            int rejected = 0;
            for (Future<JSONObject> input : inputs) if (!input.get(1, TimeUnit.SECONDS).getBoolean("ok")) rejected++;
            assertTrue("real unread pipe must apply bounded backpressure", rejected > 0);
            assertEquals(id, workers.submit(terminal::snapshot).get(1, TimeUnit.SECONDS).getString("id"));
            long start = System.nanoTime();
            workers.submit(() -> { terminal.stop(id); terminal.stop(id); return null; }).get(1, TimeUnit.SECONDS);
            await(() -> !terminal.busy());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4500);
        } finally {
            terminal.stop(null); await(() -> !terminal.busy());
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
