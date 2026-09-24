package dev.mobilecodex.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Image-only route: the owner's public Firebase images, private cache, packaged fallback. */
final class ChatIconStore {
    static final String PREFIX = "/__mobile_codex_icons__/";
    static final String BUCKET = "mytaskmanager-cf059.appspot.com";
    private final Context context;
    private final Set<String> files;
    private final File cache;
    private static final java.util.concurrent.ExecutorService downloads = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final Set<String> refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    ChatIconStore(Context context) {
        this.context = context;
        try { files = new HashSet<>(Arrays.asList(context.getAssets().list("web/chat-icons"))); }
        catch (IOException e) { throw new IllegalStateException("아이콘 목록을 읽지 못했습니다.", e); }
        cache = new File(context.getCacheDir(), "chat-firebase-icons");
    }
    static boolean imageName(String name) { return name != null && name.matches("[0-9]{2}-[a-z]+(?:-[a-z]+)*\\.png"); }
    WebResourceResponse intercept(Uri uri) {
        if (!ChatWebActivity.official(uri) || uri.getPath() == null || !uri.getPath().startsWith(PREFIX)) return null;
        String name = uri.getPath().substring(PREFIX.length());
        if (!imageName(name) || !files.contains(name) || uri.getQuery() != null) return denied();
        File target = new File(cache, name);
        if (!target.isFile() || System.currentTimeMillis() - target.lastModified() >= 86400000L) {
            if (refreshing.add(name)) downloads.execute(() -> {
                try (InputStream ignored = download(name)) { /* response is cached for later image loads */ }
                catch (IOException ignored) { /* keep existing cache/bundled image */ }
                finally { refreshing.remove(name); }
            });
        }
        try { return png(target.isFile() ? new FileInputStream(target) : context.getAssets().open("web/chat-icons/" + name)); }
        catch (Exception error) {
            try { return png(context.getAssets().open("web/chat-icons/" + name)); }
            catch (IOException ignored) { return denied(); }
        }
    }
    private InputStream download(String name) throws IOException {
        File target = new File(cache, name);
        if (target.isFile() && System.currentTimeMillis() - target.lastModified() < 86400000L) return new FileInputStream(target);
        URL url = new URL("https://firebasestorage.googleapis.com/v0/b/" + BUCKET + "/o/" + Uri.encode("aicons4/gpt/" + name) + "?alt=media");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "image/png");
        try {
            if (connection.getResponseCode() != 200 || !String.valueOf(connection.getContentType()).startsWith("image/png")) throw new IOException("Image unavailable");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > 8 * 1024 * 1024) throw new IOException("Image too large");
                    output.write(buffer, 0, size);
                }
            }
            byte[] bytes = output.toByteArray();
            byte[] pngSignature = {(byte)137,80,78,71,13,10,26,10};
            if (bytes.length < 24 || !Arrays.equals(Arrays.copyOf(bytes,8), pngSignature)) throw new IOException("Invalid image");
            java.nio.ByteBuffer dimensions = java.nio.ByteBuffer.wrap(bytes, 16, 8);
            int width = dimensions.getInt(), height = dimensions.getInt();
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || (long)width * height > 16777216L) throw new IOException("Image dimensions too large");
            if (cache.isDirectory() || cache.mkdirs()) {
                File temp = File.createTempFile("icon-", ".tmp", cache);
                try { Files.write(temp.toPath(), bytes); if (!temp.renameTo(target)) temp.delete(); }
                finally { temp.delete(); }
            }
            return new ByteArrayInputStream(bytes);
        } finally { connection.disconnect(); }
    }
    private static WebResourceResponse png(InputStream stream) {
        return new WebResourceResponse("image/png", null, 200, "OK",
            Map.of("Cache-Control", "private, max-age=86400", "X-Content-Type-Options", "nosniff"), stream);
    }
    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", StandardCharsets.UTF_8.name(), 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
    }
}
