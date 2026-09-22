package dev.mobilecodex.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.os.Looper;
import android.os.PowerManager;
import dev.mobilecodex.app.core.PhoneControlGate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.Set;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhoneServiceDispatchTest {
    private PhoneUseService service;
    private PhoneControlGate gate;
    private ExecutorService worker;
    @Before public void before() throws Exception {
        service = Robolectric.buildService(PhoneUseService.class).create().get(); service.onServiceConnected();
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(true);
        java.lang.reflect.Field field = PhoneUseService.class.getDeclaredField("gate"); field.setAccessible(true);
        gate = (PhoneControlGate) field.get(service); gate.enable();
        worker = Executors.newSingleThreadExecutor();
    }
    @After public void after() { service.onDestroy(); worker.shutdownNow(); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Future<JSONObject> home() { return worker.submit(() -> PhoneUseService.execute(service, "mobile_phone_action", obj("action", "home"))); }
    @Test public void globalActionActuallyDispatchesToAndroid() throws Exception {
        Future<JSONObject> result = home();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!result.isDone() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        assertTrue(result.get(1, TimeUnit.SECONDS).getBoolean("success"));
        assertEquals(java.util.List.of(AccessibilityService.GLOBAL_ACTION_HOME), Shadows.shadowOf(service).getGlobalActionsPerformed());
    }
    @Test public void stopBeforeMainQueueDispatchCancelsWorkEvenAfterReenable() throws Exception {
        Future<JSONObject> result = home();
        java.lang.reflect.Field field = PhoneUseService.class.getDeclaredField("pending"); field.setAccessible(true);
        Set<?> pending = (Set<?>) field.get(service);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pending.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);
        assertFalse(pending.isEmpty()); PhoneUseService.stopControl(); gate.enable();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }
    @Test public void serviceDisconnectDisarmsAndCannotResumeByReconnecting() {
        service.onInterrupt(); assertFalse(PhoneUseService.status(service).optBoolean("enabled"));
        service.onServiceConnected(); assertFalse(PhoneUseService.status(service).optBoolean("enabled"));
    }
    @Test public void delayedStopCleanupDoesNotRemoveReenabledEmergencyControl() throws Exception {
        java.lang.reflect.Field field = PhoneUseService.class.getDeclaredField("stopButton"); field.setAccessible(true);
        android.widget.Button control = new android.widget.Button(service); field.set(service, control);
        worker.submit(PhoneUseService::stopControl).get(1, TimeUnit.SECONDS);
        gate.enable();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(control, field.get(service));
        PhoneUseService.stopControl();
        assertNull(field.get(service));
    }
}
