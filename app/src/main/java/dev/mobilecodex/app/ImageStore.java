package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Base64InputStream;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import static dev.mobilecodex.app.core.Json.*;

/** Durable image bytes. The WebView receives only opaque IDs, never general file access. */
class ImageStore {
    static final class NotImageException extends IOException { NotImageException() { super(t("표시 가능한 이미지 파일이 아닙니다.")); } }
    private final File directory;
    ImageStore(Context context) { directory = new File(context.getFilesDir(), "images"); directory.mkdirs(); }

    JSONObject importFile(File file) throws Exception {
        // Probe the already saved original before allocating/copying another file.
        BitmapFactory.Options bounds = bounds(file);
        try (InputStream in = readFile(file)) { return store(in, file.getName(), bounds); }
    }
    JSONObject importBase64(String value, String name) throws Exception {
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0 || !value.substring(0, comma).endsWith(";base64")) throw new IOException(t("이미지 데이터 형식을 읽을 수 없습니다."));
            value = value.substring(comma + 1);
        }
        try (InputStream in = new Base64InputStream(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)), Base64.DEFAULT)) {
            return store(in, name);
        }
    }
    JSONObject store(InputStream in, String name) throws Exception {
        return store(in, name, null);
    }
    private JSONObject store(InputStream in, String name, BitmapFactory.Options knownBounds) throws Exception {
        File pending = File.createTempFile("image-", ".tmp", directory);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = writeFile(pending)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Image import cancelled");
                    if (pending.getParentFile().getUsableSpace() < count + 1048576L) throw new IOException(t("저장 공간이 부족합니다."));
                    hash.update(buffer, 0, count); out.write(buffer, 0, count);
                }
            }
            BitmapFactory.Options bounds = knownBounds == null ? bounds(pending) : knownBounds;
            StringBuilder id = new StringBuilder(); for (byte b : hash.digest()) id.append(String.format("%02x", b & 255));
            File saved = new File(directory, id.toString());
            Files.move(pending.toPath(), saved.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String mime = bounds.outMimeType;
            String extension = switch (mime) { case "image/jpeg" -> ".jpg"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; case "image/png" -> ".png"; default -> ".img"; };
            if (name == null || name.isBlank()) name = "codex-" + id.substring(0, 8) + extension;
            return obj("id", id.toString(), "url", "/images/" + id, "name", name, "mime", mime,
                "width", bounds.outWidth, "height", bounds.outHeight);
        } finally { pending.delete(); }
    }
    protected InputStream readFile(File file) throws IOException { return new FileInputStream(file); }
    protected OutputStream writeFile(File file) throws IOException { return new FileOutputStream(file); }
    private BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        IOException[] failure = new IOException[1];
        try (InputStream in = new FilterInputStream(readFile(file)) {
            @Override public int read() throws IOException {
                try { return in.read(); } catch (IOException error) { failure[0] = error; throw error; }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try { return in.read(bytes, offset, length); } catch (IOException error) { failure[0] = error; throw error; }
            }
        }) { BitmapFactory.decodeStream(in, null, bounds); }
        // Native decoders may turn a stream error into an empty decode result.
        if (failure[0] != null) throw failure[0];
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null)
            throw new NotImageException();
        return bounds;
    }
    File file(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IOException(t("잘못된 이미지 ID입니다."));
        File file = new File(directory, id);
        if (!file.isFile()) throw new FileNotFoundException(t("이미지 파일을 찾을 수 없습니다."));
        return file;
    }
    String mime(String id) throws IOException { return bounds(file(id)).outMimeType; }
    InputStream open(String id) throws IOException { return new FileInputStream(file(id)); }
}
