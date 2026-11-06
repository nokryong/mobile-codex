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
    @Test public void segmentsSnapSliversWithoutHidingAnyRemainingQuota() {
        assertEquals(1f, UsageWidgetRenderer.segmentFill(72, 8, 4), 0);
        assertEquals(.76f, UsageWidgetRenderer.segmentFill(72, 8, 5), .001f);
        assertEquals(0f, UsageWidgetRenderer.segmentFill(38, 8, 3), 0);
        assertEquals(1f, UsageWidgetRenderer.segmentFill(99, 8, 7), 0);
        assertEquals(.12f, UsageWidgetRenderer.segmentFill(1, 8, 0), 0);
        assertEquals(0f, UsageWidgetRenderer.segmentFill(0, 8, 0), 0);
        assertEquals(0f, UsageWidgetRenderer.segmentFill(-1, 8, 0), 0);
    }
    @Test public void longProviderLabelsKeepTheLimitKindVisible() {
        assertEquals("Weekly", UsageWidgetRenderer.chipLabel(context, "Gemini 4 Argon · Gemini 4 Argon Weekly Limit"));
        assertEquals("5 hours", UsageWidgetRenderer.chipLabel(context, "Gemini Models · Five Hour Limit Remaining"));
        assertEquals("Weekly · Gemini", UsageWidgetRenderer.rowLabel(context, "Gemini Models · Weekly Limit Remaining"));
        assertEquals("Weekly · Gemini 4 Argon", UsageWidgetRenderer.rowLabel(context, "Gemini 4 Argon · Gemini 4 Argon Weekly Limit"));
        assertEquals("5 hours", UsageWidgetRenderer.rowLabel(context, "session"));
    }
    @Test public void onlyGeminiChipsNameASpecificModel() {
        assertEquals("Argon", UsageWidgetRenderer.modelName("agy", "Gemini 4 Argon · Gemini 4 Argon Weekly Limit"));
        assertEquals("", UsageWidgetRenderer.modelName("agy", "Gemini Models · Weekly Limit Remaining"));
        assertEquals("", UsageWidgetRenderer.modelName("agy", "Claude and GPT models · Weekly Limit Remaining"));
        assertEquals("", UsageWidgetRenderer.modelName("codex", "codex · week"));
        assertEquals("", UsageWidgetRenderer.modelName("agy", "session"));
    }
    @Test @Config(qualifiers = "xxxhdpi") public void gaugeFitsSmallTallAndWideBoundsWithoutOversizedBinderImages() {
        UsageWidgetData data = new UsageWidgetData("ready", System.currentTimeMillis(),
            List.of(new UsageWidgetData.Limit("week", 28, 0), new UsageWidgetData.Limit("session", 64, 0)));
        for (int[] size : new int[][]{{68,68}, {110,110}, {150,150}, {150,240}, {304,118}, {320,90}, {200,68}, {68,200}, {600,600}}) {
            Bitmap bitmap = UsageWidgetRenderer.render(context, "claude", data, size[0], size[1]);
            assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
            assertTrue(bitmap.getAllocationByteCount() <= 4_000_000);
        }
        assertTrue(UsageWidgetRenderer.description(context, "claude", data).contains("28%"));
    }
}
