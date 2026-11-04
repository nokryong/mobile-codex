package dev.mobilecodex.app;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.RemoteViews;
import dev.mobilecodex.app.core.UsageWidgetData;

/** Three independently addable widget providers sharing one battery renderer. */
public abstract class UsageWidgetProvider extends AppWidgetProvider {
    static final String OPEN = "dev.mobilecodex.app.OPEN_USAGE_WIDGET";
    private static final String REFRESH = "dev.mobilecodex.app.REFRESH_USAGE_WIDGET";
    protected abstract String provider();
    public static final class Codex extends UsageWidgetProvider { protected String provider() { return "codex"; } }
    public static final class Claude extends UsageWidgetProvider { protected String provider() { return "claude"; } }
    public static final class Gemini extends UsageWidgetProvider { protected String provider() { return "agy"; } }
    static Class<? extends UsageWidgetProvider> type(String provider) {
        return switch (provider) { case "codex" -> Codex.class; case "claude" -> Claude.class; case "agy" -> Gemini.class; default -> throw new IllegalArgumentException("Unknown widget provider"); };
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, provider()); schedule(context, provider());
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) { update(context, provider()); }
    @Override public void onDisabled(Context context) { context.getSystemService(JobScheduler.class).cancel(jobId(provider())); }
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) schedule(context, provider());
    }
    private static int jobId(String provider) { return 7300 + switch (provider) { case "codex" -> 0; case "claude" -> 1; case "agy" -> 2; default -> throw new IllegalArgumentException("Unknown widget provider"); }; }
    static void schedule(Context context, String provider) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        // Resize/update broadcasts and multiple copies share one bounded refresh.
        if (scheduler.getPendingJob(jobId(provider)) != null) return;
        PersistableBundle extras = new PersistableBundle(); extras.putString("provider", provider);
        scheduler.schedule(new JobInfo.Builder(jobId(provider), new ComponentName(context, UsageWidgetJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras).build());
    }
    static void update(Context context, String provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, type(provider)));
        if (ids.length == 0) return;
        UsageWidgetData data = new UsageWidgetStore(context).read(provider);
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150);
            int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.usage_widget);
            views.setViewVisibility(R.id.usage_refresh, width >= 100 && height >= 100 ? View.VISIBLE : View.GONE);
            views.setImageViewBitmap(R.id.usage_battery, UsageWidgetRenderer.render(context, provider, data, width, height));
            views.setContentDescription(R.id.usage_battery, UsageWidgetRenderer.description(context, provider, data));
            Intent open = new Intent(context, MainActivity.class).setAction(OPEN).putExtra("provider", provider)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            views.setOnClickPendingIntent(R.id.usage_battery, PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            Intent refresh = new Intent(context, type(provider)).setAction(REFRESH);
            views.setOnClickPendingIntent(R.id.usage_refresh, PendingIntent.getBroadcast(context, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id, views);
        }
    }
    static void updateAll(Context context) {
        for (String provider : new String[]{"codex", "claude", "agy"}) update(context, provider);
    }
}
