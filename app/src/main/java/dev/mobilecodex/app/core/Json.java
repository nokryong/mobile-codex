package dev.mobilecodex.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Json {
    private Json() {}
    public static JSONObject obj(Object... pairs) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2)
                out.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            return out;
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    public static JSONArray array(Object... values) {
        JSONArray out = new JSONArray();
        for (Object value : values) out.put(value);
        return out;
    }
    public static JSONObject parse(String value) {
        try { return new JSONObject(value); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
}
