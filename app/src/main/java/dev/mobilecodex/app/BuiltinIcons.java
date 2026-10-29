package dev.mobilecodex.app;

import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable logical PNG URLs backed by verified lossless WebP deployment assets. */
final class BuiltinIcons {
    private static volatile Map<String,String> entries;
    static Map<String,String> files(AssetManager assets) throws IOException {
        if (entries != null) return entries;
        synchronized (BuiltinIcons.class) {
            if (entries != null) return entries;
            try (InputStream in = assets.open("web/chat-icons/manifest.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] block = new byte[4096]; int count;
                while ((count = in.read(block)) != -1) bytes.write(block, 0, count);
                JSONObject icons = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())).getJSONObject("icons");
                Map<String,String> mapping = new HashMap<>();
                for (Iterator<String> names = icons.keys(); names.hasNext();) {
                    String name = names.next(), asset = icons.getJSONObject(name).getString("asset");
                    if (!ChatIconStore.imageName(name) || !asset.equals(name.replace(".png", ".webp"))) throw new IOException("Invalid bundled icon mapping");
                    mapping.put(name, asset);
                }
                if (mapping.size() != 32) throw new IOException("Incomplete bundled icon pack");
                entries = Map.copyOf(mapping); return entries;
            } catch (Exception error) { throw new IOException("Cannot read bundled icons", error); }
        }
    }
    static WebResourceResponse response(AssetManager assets, String name) throws IOException {
        String file = files(assets).get(name);
        if (file == null) throw new FileNotFoundException("Unknown icon");
        return new WebResourceResponse("image/webp", null, 200, "OK",
            Map.of("Cache-Control", "private, max-age=86400", "X-Content-Type-Options", "nosniff"), assets.open("web/chat-icons/" + file));
    }
}
