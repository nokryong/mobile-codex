package dev.mobilecodex.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import java.io.IOException;
import java.util.Set;
import org.json.JSONObject;
import static dev.mobilecodex.app.core.Json.obj;
import static dev.mobilecodex.app.core.Texts.t;

/** App-owned WebView text zoom. This never changes Android font or display scale. */
final class AppTextSize {
    private static final String PREFERENCES = "appearance";
    private static final String KEY = "textSizePercent";
    private static final int DEFAULT = 100;
    private static final Set<Integer> SUPPORTED = Set.of(100, 115, 130, 150);

    private AppTextSize() {}

    static int percent(Context context) {
        try {
            int value = context.getSharedPreferences(PREFERENCES, 0).getInt(KEY, DEFAULT);
            return SUPPORTED.contains(value) ? value : DEFAULT;
        } catch (ClassCastException ignored) {
            return DEFAULT;
        }
    }

    static JSONObject snapshot(Context context) { return obj("percent", percent(context)); }

    static int requireSupportedNumber(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Unsupported text size");
        Number number = (Number) value;
        double exact = number.doubleValue(); int percent = number.intValue();
        if (!Double.isFinite(exact) || exact != percent || !SUPPORTED.contains(percent))
            throw new IllegalArgumentException("Unsupported text size");
        return percent;
    }

    static void apply(Context context, WebView webView) {
        webView.getSettings().setTextZoom(percent(context));
    }

    @SuppressLint("ApplySharedPref")
    static void set(Context context, int percent) throws IOException {
        if (!SUPPORTED.contains(percent)) throw new IllegalArgumentException("Unsupported text size");
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, 0);
        boolean hadPrevious = preferences.contains(KEY);
        int previous = percent(context);
        if (!preferences.edit().putInt(KEY, percent).commit()) {
            // Android may update this process's SharedPreferences cache even when
            // the durable write reports failure. Restore the prior choice too.
            SharedPreferences.Editor restore = preferences.edit();
            if (hadPrevious) restore.putInt(KEY, previous); else restore.remove(KEY);
            restore.commit();
            throw new IOException(t("글자 크기를 저장하지 못했습니다."));
        }
    }
}
