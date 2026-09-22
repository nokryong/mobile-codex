package dev.mobilecodex.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import static dev.mobilecodex.app.core.Texts.t;

/** Posts durable task notifications even when MainActivity is not attached. */
public final class CodexNotificationReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.mobilecodex.app.TASK_NOTIFICATION";
    static final String CHANNEL = "codex_task_events";
    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        var prefs = context.getSharedPreferences("notifications", 0);
        String threadId = intent.getStringExtra("threadId");
        if (!prefs.getBoolean("enabled", true)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        String kind = intent.getStringExtra("kind");
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        String approvalId = intent.getStringExtra("approvalId");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, t("Codex 작업 알림"), NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(prefs.getBoolean("vibration", true));
            channel.setVibrationPattern(new long[]{0, 180, 100, 180});
            manager.createNotificationChannel(channel);
        }
        Intent openIntent = new Intent(context, MainActivity.class).setAction(ACTION)
            .putExtra("threadId", threadId).putExtra("approvalId", approvalId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, (threadId + approvalId).hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.notification_icon).setContentTitle(title == null ? t("Mobile Codex") : title)
            .setContentText(message == null ? "" : message).setContentIntent(open).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26 && prefs.getBoolean("vibration", true)) builder.setVibrate(new long[]{0, 180, 100, 180});
        int id = Math.abs((threadId + approvalId + kind).hashCode());
        manager.notify(id == 0 ? 1001 : id, builder.build());
    }
}
