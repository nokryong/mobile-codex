package dev.mobilecodex.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import dev.mobilecodex.app.core.sync.PortableProjects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = MobileCodexApp.class)
public class ProjectTransferPickerTest {
    private static Object field(MainActivity a, String key) throws Exception {Field f = MainActivity.class.getDeclaredField(key); f.setAccessible(true); return f.get(a);}
    private static void choose(MainActivity a, String content) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("chooseProjectFile",String.class,String.class); m.setAccessible(true); m.invoke(a,"old-request",content);
    }
    private static void idle(MainActivity a) throws Exception {((Engine)field(a,"engine")).io.submit(() -> {}).get(5,TimeUnit.SECONDS);}
    @Test public void importUsesDocumentPickerAndCancellationDoesNotWriteRegistry() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); idle(a);
            String before = a.getSharedPreferences("projects",0).getString("registry", "");
            choose(a,null);
            var started = Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,started.intent.getAction()); assertEquals("application/json",started.intent.getType()); assertEquals(39,started.requestCode);
            a.onActivityResult(39,Activity.RESULT_CANCELED,null); idle(a);
            assertNull(field(a,"pendingProjectRequest"));
            assertEquals(before,a.getSharedPreferences("projects",0).getString("registry", ""));
        }
    }
    @Test public void exportSnapshotSurvivesRecreationWithoutReusingWebRequestIdAndCancelCleansIt() throws Exception {
        Bundle saved = new Bundle(); String filename;
        PortableProjects p = new PortableProjects(); p.create("proj_a","A","device_phone",false);
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); choose(a,p.json().toString());
            var started = Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,started.intent.getAction()); assertEquals(40,started.requestCode);
            filename = (String)field(a,"pendingProjectExport"); assertTrue(new File(a.getCacheDir(),filename).isFile());
            a.onSaveInstanceState(saved);
        }
        try (var controller = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume()) {
            MainActivity a = controller.get(); assertNull(field(a,"pendingProjectRequest")); assertEquals(filename,field(a,"pendingProjectExport"));
            a.onActivityResult(40,Activity.RESULT_CANCELED,null); idle(a); assertFalse(new File(a.getCacheDir(),filename).exists());
        }
    }
}
