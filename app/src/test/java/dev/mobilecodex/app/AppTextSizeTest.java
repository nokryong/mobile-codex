package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.webkit.WebView;
import java.io.IOException;
import java.lang.reflect.Proxy;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class AppTextSizeTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", 0).edit().clear().commit();
    }

    @Test public void defaultAndEverySupportedLevelRoundTrip() throws Exception {
        assertEquals(100, AppTextSize.percent(context));
        assertEquals(100, AppTextSize.snapshot(context).getInt("percent"));
        for (int percent : new int[]{100, 115, 130, 150}) {
            AppTextSize.set(context, percent);
            assertEquals(percent, AppTextSize.percent(new ContextWrapper(context)));
            assertEquals(percent, AppTextSize.snapshot(context).getInt("percent"));
        }
    }

    @Test public void invalidValueDoesNotOverwriteSavedChoice() throws Exception {
        AppTextSize.set(context, 130);
        assertThrows(IllegalArgumentException.class, () -> AppTextSize.set(context, 125));
        assertThrows(IllegalArgumentException.class, () -> AppTextSize.requireSupportedNumber(150.5));
        assertThrows(IllegalArgumentException.class, () -> AppTextSize.requireSupportedNumber("150"));
        assertThrows(IllegalArgumentException.class, () -> AppTextSize.requireSupportedNumber(null));
        assertEquals(150, AppTextSize.requireSupportedNumber(150L));
        assertEquals(130, context.getSharedPreferences("appearance", 0).getInt("textSizePercent", -1));
        assertEquals(130, AppTextSize.percent(context));
    }

    @Test public void appliesSavedPercentToWebSettings() throws Exception {
        AppTextSize.set(context, 150);
        WebView webView = new WebView(context);
        try {
            AppTextSize.apply(context, webView);
            assertEquals(150, webView.getSettings().getTextZoom());
        } finally { webView.destroy(); }
    }

    @Test public void corruptStoredValueFallsBackWithoutChangingStorage() {
        context.getSharedPreferences("appearance", 0).edit().putInt("textSizePercent", 999).commit();
        assertEquals(100, AppTextSize.percent(context));
        assertEquals(999, context.getSharedPreferences("appearance", 0).getInt("textSizePercent", -1));
    }

    @Test public void failedCommitIsReportedAndDoesNotPublishNewValue() throws Exception {
        AppTextSize.set(context, 115);
        context.getSharedPreferences("appearance", 0).edit().putString("language", "ko").commit();
        SharedPreferences real = context.getSharedPreferences("appearance", 0);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (!method.getName().equals("edit")) return method.invoke(real, args);
                SharedPreferences.Editor editor = real.edit();
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (p, m, a) -> {
                    if (m.getName().equals("commit")) { m.invoke(editor, a); return false; }
                    if (m.getReturnType() == SharedPreferences.Editor.class) { m.invoke(editor, a); return p; }
                    return m.invoke(editor, a);
                });
            });
        Context wrapper = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("appearance") ? failing : super.getSharedPreferences(name, mode);
            }
        };
        assertThrows(IOException.class, () -> AppTextSize.set(wrapper, 150));
        assertEquals(115, AppTextSize.percent(context));
        assertEquals("ko", context.getSharedPreferences("appearance", 0).getString("language", ""));
    }
}
