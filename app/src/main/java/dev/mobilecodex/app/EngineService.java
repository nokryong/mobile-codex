package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.app.*;
import android.content.Intent;
import android.os.IBinder;

public final class EngineService extends Service {
    private static final String CHANNEL = "codex_runtime";
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, t("진행 중인 작업"), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, EngineService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.notification_icon).setContentTitle("Mobile Codex")
            .setContentText(t("기기에서 실행 중 · 눌러서 열기")).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, t("종료"), stop).build()).build();
        startForeground(21, notification);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            ((MobileCodexApp) getApplication()).engine().stop();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        // Explicit stop requests own process shutdown. A stale service callback must
        // not stop a newly restarted engine.
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
