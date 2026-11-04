package dev.mobilecodex.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import dev.mobilecodex.app.core.UsageWidgetData;

final class UsageWidgetStore {
    private final Context context;
    private final SharedPreferences preferences;
    private JSONObject lastLimits;
    private String lastOwner = "", lastState = "";
    UsageWidgetStore(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("usage_widgets", Context.MODE_PRIVATE);
    }
    UsageWidgetData read(String provider) { return UsageWidgetData.read(preferences.getString(provider, "")); }
    void codex(String owner, String state, JSONObject limits) {
        if (!state.equals("signed_in") && !state.equals("signed_out")) return;
        if (limits == lastLimits && owner.equals(lastOwner) && state.equals(lastState)) return;
        lastLimits = limits; lastOwner = owner; lastState = state;
        if (state.equals("signed_out")) { preferences.edit().remove("codex_owner").apply(); save("codex", UsageWidgetData.empty("signed_out")); return; }
        boolean changedAccount = !owner.equals(preferences.getString("codex_owner", ""));
        UsageWidgetData value = UsageWidgetData.codex(limits, System.currentTimeMillis());
        // A failed refresh retains the timestamp of the last successful reading.
        // A newly selected account must never inherit another account's balance.
        if (changedAccount || !value.limits().isEmpty()) {
            preferences.edit().putString("codex_owner", owner).apply();
            save("codex", value);
        }
    }
    void provider(JSONObject provider) {
        String id = provider.optString("id");
        if (!id.equals("claude") && !id.equals("agy")) return;
        UsageWidgetData value = UsageWidgetData.provider(provider);
        if (value.status().equals("signed_out") || (!value.limits().isEmpty() && value.updatedAt() > 0)) save(id, value);
    }
    private void save(String provider, UsageWidgetData value) {
        String json = value.json().toString();
        if (json.equals(preferences.getString(provider, ""))) return;
        preferences.edit().putString(provider, json).apply();
        UsageWidgetProvider.update(context, provider);
    }
}
