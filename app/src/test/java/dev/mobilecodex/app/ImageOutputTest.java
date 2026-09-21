package dev.mobilecodex.app;

import android.app.Application;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.lang.reflect.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// Native graphics at API 35 is unsupported on Windows by Robolectric 4.14.
// Exercise the real decoder at our minimum supported Android API instead.
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageOutputTest {
    private byte[] png(int color) {
        // Static PNG fixtures generated independently of Android's encoder.
        String data = switch (color) {
            case 0xffff0000 -> "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAEklEQVR4nGP4z8DwHx9mGAoKAP3CX6FlCOtUAAAAAElFTkSuQmCC";
            case 0xff00ff00 -> "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAD0lEQVR4nGNg+E8ADgUFAM3yX6FGP44aAAAAAElFTkSuQmCC";
            case 0xff0000ff -> "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAEUlEQVR4nGNgYPj/Hz8eAgoAniJfoeWDPP0AAAAASUVORK5CYII=";
            case 0xff00ffff -> "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAEklEQVR4nGNg+P//P148FBQAADSXj3GLdqI/AAAAAElFTkSuQmCC";
            default -> throw new IllegalArgumentException("Unknown fixture color");
        };
        return Base64.decode(data, Base64.DEFAULT);
    }
    private void set(Engine engine, String name, Object value) throws Exception {
        Field f = Engine.class.getDeclaredField(name); f.setAccessible(true); f.set(engine, value);
    }
    private void event(Engine engine, JSONObject item) throws Exception {
        Method method = Engine.class.getDeclaredMethod("onNotification", String.class, JSONObject.class); method.setAccessible(true);
        method.invoke(engine, "item/completed", obj("threadId", "thread", "turnId", "turn", "item", item));
    }
    private Engine engine(JSONObject session) throws Exception {
        Engine engine = new Engine(RuntimeEnvironment.getApplication());
        set(engine, "active", session); set(engine, "sessions", array(session)); set(engine, "threadId", "thread"); return engine;
    }
    @Test public void multipleGeneratedImagesArePersistedWithoutBase64InSessionState() throws Exception {
        JSONObject session = obj("id", "thread", "messages", new JSONArray()); Engine engine = engine(session);
        byte[] first = png(0xffff0000), second = png(0xff00ff00);
        event(engine, obj("type", "imageGeneration", "id", "a", "status", "completed", "result", Base64.encodeToString(first, Base64.NO_WRAP)));
        event(engine, obj("type", "imageGeneration", "id", "b", "status", "completed", "savedPath", "/missing/image.png", "result", Base64.encodeToString(second, Base64.NO_WRAP)));
        JSONArray messages = session.getJSONArray("messages"); assertEquals(2, messages.length());
        JSONObject image = messages.getJSONObject(0).getJSONArray("images").getJSONObject(0);
        assertEquals("turn", messages.getJSONObject(0).getString("imageGroup"));
        assertEquals("turn", messages.getJSONObject(1).getString("imageGroup"));
        assertEquals(8, image.getInt("width")); assertEquals(6, image.getInt("height"));
        ImageStore reopened = new ImageStore(RuntimeEnvironment.getApplication());
        try (InputStream in = reopened.open(image.getString("id"))) { assertArrayEquals(first, in.readAllBytes()); }
        String saved = dev.mobilecodex.app.core.Utf8Files.read(new File(RuntimeEnvironment.getApplication().getFilesDir(), "sessions.json").toPath());
        assertTrue(saved.contains(image.getString("id"))); assertFalse(saved.contains(Base64.encodeToString(first, Base64.NO_WRAP)));
        engine.io.shutdownNow();
    }
    @Test public void mcpMultipleImageContentIsRetainedAndRepeatedCompletionDoesNotDuplicate() throws Exception {
        JSONObject session = obj("id", "thread", "messages", new JSONArray()); Engine engine = engine(session);
        String data = Base64.encodeToString(png(0xff0000ff), Base64.NO_WRAP);
        JSONObject item = obj("id", "mcp", "type", "mcpToolCall", "status", "completed", "result", obj("content", array(
            obj("type", "image", "mimeType", "image/png", "data", data), obj("type", "image", "mimeType", "image/png", "data", data))));
        event(engine, item); event(engine, item);
        assertEquals(1, session.getJSONArray("messages").length());
        assertEquals(2, session.getJSONArray("messages").getJSONObject(0).getJSONArray("images").length()); engine.io.shutdownNow();
    }
    @Test public void invalidAndFailedOutputsAreVisibleAndOpaqueIdsCannotTraverseFiles() throws Exception {
        JSONObject session = obj("id", "thread", "messages", new JSONArray()); Engine engine = engine(session);
        event(engine, obj("id", "failed", "type", "imageGeneration", "status", "failed", "failure", obj("type", "usageLimitExceeded")));
        assertEquals("failed", session.getJSONArray("messages").getJSONObject(0).getString("imageStatus"));
        assertTrue(session.getJSONArray("messages").getJSONObject(0).getString("imageError").contains("한도"));
        assertThrows(IOException.class, () -> engine.images.importBase64(Base64.encodeToString("not an image".getBytes(), Base64.NO_WRAP), "fake.png"));
        assertThrows(IOException.class, () -> engine.images.open("../codex/auth.json")); engine.io.shutdownNow();
    }
    @Test public void aBrokenImageDoesNotDiscardOtherImagesInTheSameToolResult() throws Exception {
        JSONObject session = obj("id", "thread", "messages", new JSONArray()); Engine engine = engine(session);
        event(engine, obj("id", "mixed", "type", "mcpToolCall", "result", obj("content", array(
            obj("type", "image", "data", "broken"),
            obj("type", "image", "data", Base64.encodeToString(png(0xff00ffff), Base64.NO_WRAP))))));
        JSONObject message = session.getJSONArray("messages").getJSONObject(0);
        assertEquals(1, message.getJSONArray("images").length()); assertTrue(message.getString("imageError").contains("1번째"));
        engine.io.shutdownNow();
    }
}
