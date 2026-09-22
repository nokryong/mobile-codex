package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import dev.mobilecodex.app.core.ToolCatalog;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
public class PhoneUseTest {
    @Test public void passwordNodesAreRedactedAndSuppressScreenshot() throws Exception {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setPackageName("test.app"); root.setText("private-password"); root.setContentDescription("also-private");
        root.setPassword(true); root.setVisibleToUser(true); root.setBoundsInScreen(new Rect(0, 0, 200, 80));
        try (PhoneScreen screen = new PhoneScreen(root, 7, 400, 800)) {
            assertTrue(screen.passwordVisible);
            assertFalse(screen.data.toString().contains("private-password"));
            assertFalse(screen.data.toString().contains("also-private"));
            assertEquals("[password]", screen.data.getJSONArray("nodes").getJSONObject(0).getString("text"));
        } finally { root.recycle(); }
    }
    @Test public void observationCannotBeReusedAfterScreenChangeRotationOrAppSwitch() throws Exception {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(); root.setPackageName("test.app");
        try (PhoneScreen screen = new PhoneScreen(root, 7, 400, 800)) {
            screen.requireFresh(screen.id, 7, root.getWindowId(), "test.app", 400, 800);
            assertThrows(IOException.class, () -> screen.requireFresh("old-id", 7, root.getWindowId(), "test.app", 400, 800));
            assertThrows(IOException.class, () -> screen.requireFresh(screen.id, 8, root.getWindowId(), "test.app", 400, 800));
            assertThrows(IOException.class, () -> screen.requireFresh(screen.id, 7, root.getWindowId(), "other.app", 400, 800));
            assertThrows(IOException.class, () -> screen.requireFresh(screen.id, 7, root.getWindowId(), "test.app", 800, 400));
            assertThrows(IOException.class, () -> PhoneScreen.coordinate(obj("x", 400), "x", 400));
            assertThrows(IOException.class, () -> PhoneScreen.coordinate(obj("x", -1), "x", 400));
        } finally { root.recycle(); }
    }
    @Test public void statusCanExplainSetupButToolsCannotEnableControl() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(PhoneUseService.status(context).getBoolean("enabled"));
        assertTrue(PhoneUseService.execute(context, "mobile_phone_status", obj()).getBoolean("success"));
        assertThrows(IOException.class, () -> PhoneUseService.execute(context, "mobile_phone_apps", obj()));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < ToolCatalog.all().length(); i++) names.add(ToolCatalog.all().getJSONObject(i).getString("name"));
        assertTrue(names.containsAll(java.util.Set.of("mobile_phone_screen", "mobile_phone_action", "mobile_read", "mobile_write")));
        assertFalse(names.contains("mobile_phone_enable"));
    }
    @Test public void phoneScreenshotsDoNotBecomeGeneratedImagesInGallery() throws Exception {
        Engine engine = new Engine(RuntimeEnvironment.getApplication());
        try {
            Method record = Engine.class.getDeclaredMethod("recordImages", JSONObject.class, boolean.class, String.class); record.setAccessible(true);
            JSONObject item = obj("type", "dynamicToolCall", "tool", "mobile_phone_screen", "id", "screenshot", "contentItems",
                array(obj("type", "inputImage", "imageUrl", "data:image/png;base64,not-a-gallery-image")));
            assertEquals(false, record.invoke(engine, item, true, "turn"));
        } finally { engine.io.shutdownNow(); }
    }
    // This protocol-only test follows EngineOfflineSessionTest's SDK: API 35's unconfigured
    // Robolectric storage volumes cannot satisfy Engine's unrelated all-files-access query.
    @Test @Config(sdk = 29) public void newThreadsAdvertisePhoneToolsAndLegacyHistoryReportsMissingTools() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Engine engine = new Engine(context);
        try {
            Method start = Engine.class.getDeclaredMethod("threadStartParams", String.class); start.setAccessible(true);
            JSONObject params = (JSONObject) start.invoke(engine, "");
            assertTrue(params.getJSONArray("dynamicTools").toString().contains("mobile_phone_screen"));
            java.lang.reflect.Field active = Engine.class.getDeclaredField("active"); active.setAccessible(true);
            active.set(engine, obj("id", "legacy", "messages", array()));
            CompletableFuture<JSONObject> state = new CompletableFuture<>();
            engine.handle("state", obj(), (value, error) -> { if (error != null) state.completeExceptionally(error); else state.complete(value); });
            assertFalse(state.get(5, TimeUnit.SECONDS).getBoolean("phoneToolsAvailable"));
        } finally { engine.io.shutdownNow(); }
    }
}
