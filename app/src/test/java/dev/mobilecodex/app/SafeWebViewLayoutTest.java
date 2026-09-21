package dev.mobilecodex.app;

import android.app.Application;
import android.view.View;
import android.graphics.Rect;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.DisplayCutoutCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
public class SafeWebViewLayoutTest {
    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    @Test public void keyboardResizesViewportContainerAndRestoresItWithoutDoubleInsets() {
        SafeWebViewLayout root = new SafeWebViewLayout(RuntimeEnvironment.getApplication(), visible -> {});
        View web = new View(root.getContext());
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WindowInsetsCompat bars = new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build();
        ViewCompat.dispatchApplyWindowInsets(root, bars); layout(root, 360, 800);
        assertEquals(24, web.getTop()); assertEquals(752, web.getHeight());
        WindowInsetsCompat keyboard = new WindowInsetsCompat.Builder(bars)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 320)).build();
        // API 29 has no platform IME type: Android reports it through system window insets.
        if (android.os.Build.VERSION.SDK_INT == 29) keyboard = new WindowInsetsCompat.Builder(bars)
            .setSystemWindowInsets(Insets.of(0, 24, 0, 320))
            .setStableInsets(Insets.of(0, 24, 0, 24)).build();
        WindowInsetsCompat child = ViewCompat.dispatchApplyWindowInsets(root, keyboard);
        layout(root, 360, 800);
        assertEquals(456, web.getHeight()); assertEquals(480, web.getBottom());
        assertEquals(Insets.NONE, child.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()));
        ViewCompat.dispatchApplyWindowInsets(root, bars); layout(root, 360, 800);
        assertEquals(752, web.getHeight());
    }
    @Test public void landscapeCutoutAndSideNavigationLeaveContentInsideSafeBounds() {
        SafeWebViewLayout root = new SafeWebViewLayout(RuntimeEnvironment.getApplication(), visible -> {});
        View content = new View(root.getContext()); root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 24, 0))
            .setDisplayCutout(new DisplayCutoutCompat(new Rect(48, 0, 0, 0), java.util.List.of(new Rect(0, 140, 48, 220)))).build();
        ViewCompat.dispatchApplyWindowInsets(root, insets); layout(root, 800, 360);
        assertEquals(48, content.getLeft()); assertEquals(776, content.getRight());
        assertEquals(24, content.getTop()); assertEquals(360, content.getBottom());
    }
}
