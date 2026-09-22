package dev.mobilecodex.app;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.mobilecodex.app.core.Texts;
import static dev.mobilecodex.app.core.Json.*;

final class AppLanguage {
    private static Map<String, String> english = Map.of();
    static void initialize(Context context) {
        try (var stream = context.getAssets().open("translations-en.json")) {
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject json = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            var values = new HashMap<String, String>();
            for (var keys = json.keys(); keys.hasNext();) { String key = keys.next(); values.put(key, json.getString(key)); }
            english = Map.copyOf(values);
        } catch (Exception failure) { android.util.Log.e("AppLanguage", "Unable to load bundled translations", failure); }
        configure(context);
    }
    static String choice(Context context) { return context.getSharedPreferences("appearance", 0).getString("language", "system"); }
    static Locale locale(Context context) {
        String choice = choice(context);
        return choice.equals("system") ? context.getResources().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(choice);
    }
    static void configure(Context context) { Texts.configure(locale(context).getLanguage().equals("ko"), english); }
    static JSONObject snapshot(Context context) { return obj("choice", choice(context), "systemLanguage", context.getResources().getConfiguration().getLocales().get(0).toLanguageTag()); }
    @android.annotation.SuppressLint("ApplySharedPref")
    static void set(Context context, String value) throws java.io.IOException {
        if (!Set.of("system", "en", "ko").contains(value)) throw new IllegalArgumentException("Unsupported language");
        if (!context.getSharedPreferences("appearance", 0).edit().putString("language", value).commit()) throw new java.io.IOException("Unable to save language setting");
        configure(context);
    }
}
