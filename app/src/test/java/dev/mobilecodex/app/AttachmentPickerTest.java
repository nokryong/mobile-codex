package dev.mobilecodex.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native lifecycle checks: cancellation and restored picker results never switch draft ownership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = MobileCodexApp.class)
public class AttachmentPickerTest {
    private static void set(MainActivity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private static Engine engine(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("engine"); field.setAccessible(true); return (Engine) field.get(activity);
    }
    @Test public void pickerCancellationPersistsEmptyReceiptWithOriginalDraftKey() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); set(activity, "attachmentDraftKey", "draft:project-a");
            activity.onActivityResult(35, Activity.RESULT_CANCELED, null);
            engine(activity).io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            JSONObject receipt = new JSONObject(activity.getSharedPreferences("attachment-result", 0).getString("pending", ""));
            assertTrue(receipt.getBoolean("cancelled")); assertEquals("draft:project-a", receipt.getString("draftKey"));
            assertEquals(0, receipt.getJSONArray("attachments").length()); assertEquals(0, receipt.getJSONArray("errors").length());
        }
    }
    @Test public void recreatedActivityRetainsPickerDraftScopeWithoutReusingOldWebRequestId() throws Exception {
        Bundle saved = new Bundle();
        try (var first = Robolectric.buildActivity(MainActivity.class).setup()) {
            set(first.get(), "attachmentDraftKey", "draft:general"); set(first.get(), "pendingAttachmentRequest", "12");
            first.get().onSaveInstanceState(saved);
        }
        try (var second = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume()) {
            MainActivity activity = second.get();
            Field request = MainActivity.class.getDeclaredField("pendingAttachmentRequest"); request.setAccessible(true); assertNull(request.get(activity));
            activity.onActivityResult(35, Activity.RESULT_CANCELED, new Intent());
            engine(activity).io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            JSONObject receipt = new JSONObject(activity.getSharedPreferences("attachment-result", 0).getString("pending", ""));
            assertEquals("draft:general", receipt.getString("draftKey"));
        }
    }
}
