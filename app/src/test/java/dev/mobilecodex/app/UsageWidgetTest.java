package dev.mobilecodex.app;

import android.app.Application;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.graphics.Bitmap;
import dev.mobilecodex.app.core.UsageWidgetData;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class UsageWidgetTest {
    private Context context;
    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("usage_widgets", 0).edit().clear().commit();
        context.getSystemService(JobScheduler.class).cancelAll();
    }
    @Test public void changingAccountsCannotExposePreviousAccountsBalance() {
        UsageWidgetStore store = new UsageWidgetStore(context);
        var limits = obj("rateLimits", obj("primary", obj("usedPercent", 15)));
        store.codex("first", "signed_in", limits);
        assertEquals(85, store.read("codex").limits().get(0).remaining(), 0);
        store.codex("second", "signed_in", obj()); assertTrue(store.read("codex").limits().isEmpty());
        store.codex("second", "signed_in", limits); store.codex("", "signed_out", obj());
        assertEquals("signed_out", store.read("codex").status());
    }
    @Test public void refreshFailureRetainsItsOriginalObservationTime() {
        UsageWidgetStore store = new UsageWidgetStore(context);
        store.codex("one", "signed_in", obj("rateLimits", obj("primary", obj("usedPercent", 36))));
        UsageWidgetData saved = store.read("codex");
        store.codex("one", "signed_in", obj());
        assertEquals(saved, new UsageWidgetStore(context).read("codex"));
    }
    @Test public void independentWidgetsScheduleOnlyTheirOwnDeduplicatedJobs() {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        new UsageWidgetProvider.Claude().onUpdate(context, manager, new int[0]);
        new UsageWidgetProvider.Claude().onUpdate(context, manager, new int[0]);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        assertEquals(1, scheduler.getAllPendingJobs().size());
        assertEquals("claude", scheduler.getAllPendingJobs().get(0).getExtras().getString("provider"));
        new UsageWidgetProvider.Gemini().onUpdate(context, manager, new int[0]);
        assertEquals(2, scheduler.getAllPendingJobs().size());
        new UsageWidgetProvider.Claude().onDisabled(context); assertEquals(1, scheduler.getAllPendingJobs().size());
    }
    @Test public void batteryFitsSmallTallAndWideBoundsWithoutOversizedBinderImages() {
        UsageWidgetData data = new UsageWidgetData("ready", System.currentTimeMillis(),
            List.of(new UsageWidgetData.Limit("week", 28, 0)));
        for (int[] size : new int[][]{{68,68}, {150,150}, {150,240}, {320,90}, {600,600}}) {
            Bitmap bitmap = UsageWidgetRenderer.render(context, "claude", data, size[0], size[1]);
            assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
            assertTrue(bitmap.getWidth() <= 400 && bitmap.getHeight() <= 400);
            assertTrue(bitmap.getAllocationByteCount() <= 640000);
        }
        assertTrue(UsageWidgetRenderer.description(context, "claude", data).contains("28%"));
    }
}
