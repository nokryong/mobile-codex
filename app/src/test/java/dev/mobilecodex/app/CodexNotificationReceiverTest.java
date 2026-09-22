package dev.mobilecodex.app;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class CodexNotificationReceiverTest {
    private Context context;
    private NotificationManager manager;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
        manager.cancelAll();
        context.getSharedPreferences("notifications", 0).edit().clear().commit();
    }

    @Test public void visibleConversationStillPostsEnabledTaskNotification() {
        context.getSharedPreferences("notifications", 0).edit()
            .putBoolean("enabled", true).putBoolean("foreground", true)
            .putString("visibleThread", "thread-one").commit();
        Intent intent = new Intent(CodexNotificationReceiver.ACTION)
            .putExtra("kind", "completed").putExtra("title", "답변 완료")
            .putExtra("message", "Codex가 작업을 마쳤습니다.")
            .putExtra("threadId", "thread-one").putExtra("approvalId", "");

        new CodexNotificationReceiver().onReceive(context, intent);

        ShadowNotificationManager notifications = shadowOf(manager);
        assertEquals(1, notifications.size());
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(CodexNotificationReceiver.CHANNEL).getImportance());
    }

    @Test public void disabledTaskNotificationsStaySilent() {
        context.getSharedPreferences("notifications", 0).edit().putBoolean("enabled", false).commit();
        new CodexNotificationReceiver().onReceive(context,
            new Intent(CodexNotificationReceiver.ACTION).putExtra("kind", "completed")
                .putExtra("threadId", "thread-one").putExtra("approvalId", ""));
        assertEquals(0, shadowOf(manager).size());
    }
}
