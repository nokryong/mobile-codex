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
final class ImageStore {
    private final File directory;
    ImageStore(Context context) { directory = new File(context.getFilesDir(), "images"); directory.mkdirs(); }

    JSONObject importFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) { return store(in, file.getName()); }
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
        File pending = File.createTempFile("image-", ".tmp", directory);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new FileOutputStream(pending)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) { hash.update(buffer, 0, count); out.write(buffer, 0, count); }
            }
            BitmapFactory.Options bounds = bounds(pending);
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
    private BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null)
            throw new IOException(t("표시 가능한 이미지 파일이 아닙니다."));
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
