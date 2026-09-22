package dev.mobilecodex.app;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class CharacterPacksTest {
    private static final String AUTHORITY = "character.tests";
    private static final Uri TREE = Uri.parse("content://" + AUTHORITY + "/tree/root");

    /** A small in-memory DocumentsProvider-shaped tree that honors requested projections. */
    public static final class Provider extends ContentProvider {
        static final Map<String, Node> nodes = new LinkedHashMap<>();
        static boolean denyQueries;
        static boolean denyOpens;
        static final class Node {
            final String id, name, mime;
            final List<String> children = new ArrayList<>();
            byte[] bytes;
            Node(String id, String name, String mime, byte[] bytes) { this.id=id; this.name=name; this.mime=mime; this.bytes=bytes; }
        }
        static void reset() {
            nodes.clear(); denyQueries=false; denyOpens=false;
            nodes.put("root", new Node("root", "내 캐릭터 팩", DocumentsContract.Document.MIME_TYPE_DIR, null));
        }
        static void addPack(String id, String name, Map<String, byte[]> files, String mappingJson) {
            Node folder = new Node(id, name, DocumentsContract.Document.MIME_TYPE_DIR, null);
            nodes.put(id, folder); nodes.get("root").children.add(id);
            addFile(folder, id + "-mapping", "mapping.json", "application/json", mappingJson.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> file : files.entrySet()) addFile(folder, id + "-" + file.getKey(), file.getKey(), mimeFor(file.getKey()), file.getValue());
        }
        static void addFile(Node folder, String id, String name, String mime, byte[] bytes) {
            nodes.put(id, new Node(id, name, mime, bytes)); folder.children.add(id);
        }
        static String mimeFor(String name) {
            String lower = name.toLowerCase();
            return lower.endsWith(".webp") ? "image/webp" : lower.endsWith(".json") ? "application/json" : "image/png";
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
            if (denyQueries) throw new SecurityException("tree permission revoked");
            List<String> path = uri.getPathSegments();
            boolean children = !path.isEmpty() && "children".equals(path.get(path.size()-1));
            String id = documentId(uri);
            String[] requested = projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE} : projection;
            MatrixCursor cursor = new MatrixCursor(requested);
            if (children) {
                Node parent = nodes.get(id); if (parent == null) throw new IllegalArgumentException("unknown parent " + id);
                for (String child : parent.children) addRow(cursor, nodes.get(child));
            } else {
                Node node = nodes.get(id); if (node == null) throw new IllegalArgumentException("unknown document " + id); addRow(cursor, node);
            }
            return cursor;
        }
        private static void addRow(MatrixCursor cursor, Node node) {
            Object[] row = new Object[cursor.getColumnCount()]; String[] cols = cursor.getColumnNames();
            for (int i=0; i<cols.length; i++) row[i] = switch (cols[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name;
                case DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime;
                case DocumentsContract.Document.COLUMN_SIZE -> node.bytes == null ? null : node.bytes.length;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L;
                case DocumentsContract.Document.COLUMN_FLAGS -> 0;
                default -> null;
            };
            cursor.addRow(row);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (denyOpens) throw new SecurityException("tree permission revoked");
            Node node = nodes.get(documentId(uri)); if (node == null || node.bytes == null) throw new FileNotFoundException(uri.toString());
            File file = new File(getContext().getCacheDir(), "character-test-" + node.id);
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(node.bytes); } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { Node node = nodes.get(documentId(uri)); return node == null ? null : node.mime; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
        private static String documentId(Uri uri) {
            List<String> path = uri.getPathSegments(); int document = path.indexOf("document");
            if (document >= 0 && document + 1 < path.size()) return path.get(document + 1);
            throw new IllegalArgumentException("not a tree/document URI: " + uri);
        }
    }

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Files.deleteIfExists(new File(new File(context.getFilesDir(), ".codex"), "character-packs.json").toPath());
        Provider.reset(); Provider provider = new Provider(); android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo(); info.authority = AUTHORITY;
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
    }

    @Test public void builtinSnapshotExposesAll32AbsoluteLocalUrls() throws Exception {
        JSONObject snapshot = new CharacterPacks(context()).list(); assertEquals("builtin", snapshot.getString("selectedPackId"));
        JSONArray items = snapshot.getJSONArray("packs"); assertEquals(1, items.length()); JSONObject icons = items.getJSONObject(0).getJSONObject("icons");
        assertEquals(32, icons.length()); assertEquals("https://appassets.androidplatform.net/chat-icons/01-idle.png", icons.getString("idle")); assertEquals("https://appassets.androidplatform.net/chat-icons/32-sleep.png", icons.getString("sleep"));
    }

    @Test public void customRouteRejectsUnknownOriginsAndUnsafePaths() {
        CharacterPacks packs = new CharacterPacks(context());
        assertEquals(403, packs.route(Uri.parse("https://appassets.androidplatform.net/character-packs/builtin/idle")).getStatusCode());
        assertEquals(403, packs.route(Uri.parse("https://evil.example/character-packs/x/idle")).getStatusCode());
        assertEquals(403, packs.route(Uri.parse("http://appassets.androidplatform.net/character-packs/x/idle")).getStatusCode());
        assertEquals(403, packs.route(Uri.parse("https://appassets.androidplatform.net/other/x/idle")).getStatusCode());
        assertEquals(403, packs.route(Uri.parse("https://appassets.androidplatform.net/character-packs/../idle/idle")).getStatusCode());
        assertEquals(403, packs.route(Uri.parse("https://appassets.androidplatform.net/character-packs/x/not-a-key")).getStatusCode());
    }

    @Test public void validSafTreeReadsOriginalBytesAndRefreshChangesVersion() throws Exception {
        Map<String, byte[]> files = allImages(png(0xffff0000)); Provider.addPack("pack-a", "한글팩", files, mapping("한글 표시명", files.keySet()));
        CharacterPacks packs = new CharacterPacks(context()); packs.setTree(TREE); JSONObject first = packs.refresh(); JSONObject item = packByName(first, "한글 표시명");
        assertEquals("내 캐릭터 팩", first.getString("folderName")); assertTrue(item.getBoolean("valid")); String id = item.getString("id"); String firstUrl = item.getJSONObject("icons").getString("idle"); assertTrue(firstUrl.contains("?v="));
        assertArrayEquals(Provider.nodes.get("pack-a-idle.png").bytes, read(packs.route(customUri(id, "idle"))));
        Provider.nodes.get("pack-a-idle.png").bytes = png(0xff0000ff); JSONObject second = packs.refresh();
        assertNotEquals(firstUrl, packByName(second, "한글 표시명").getJSONObject("icons").getString("idle")); assertArrayEquals(Provider.nodes.get("pack-a-idle.png").bytes, read(packs.route(customUri(id, "idle"))));
    }

    @Test public void newInstanceRestoresTreeAndSelectionFromMetadata() throws Exception {
        Map<String, byte[]> files = allImages(png(0xff00ff00)); Provider.addPack("pack-restore", "복원팩", files, mapping("복원팩", files.keySet()));
        CharacterPacks first = new CharacterPacks(context()); first.setTree(TREE); String id = packByName(first.refresh(), "복원팩").getString("id"); first.select(id);
        CharacterPacks restored = new CharacterPacks(context()); JSONObject snapshot = restored.refresh(); assertTrue(snapshot.getBoolean("folderConfigured")); assertEquals(id, snapshot.getString("selectedPackId")); assertTrue(packByName(snapshot, "복원팩").getBoolean("valid"));
    }

    @Test public void invalidMappingFilesAndImagesAreListedWithErrors() throws Exception {
        byte[] valid = png(0xffff0000); Map<String, byte[]> complete = allImages(valid);
        Provider.addPack("missing-mapping", "매핑 없음", complete, "not-json"); Provider.nodes.get("missing-mapping").children.remove("missing-mapping-mapping"); Provider.nodes.remove("missing-mapping-mapping");
        Provider.addPack("malformed-mapping", "매핑 손상", complete, "not-json");
        Provider.addPack("missing-file", "파일 없음", complete, mapping("파일 없음", complete.keySet())); Provider.nodes.get("missing-file").children.remove("missing-file-sleep.png"); Provider.nodes.remove("missing-file-sleep.png");
        Provider.addPack("remote-name", "원격 이름", allImages(valid), mappingWith("idle", "https://example.test/idle.png", complete.keySet()));
        Provider.addPack("traversal-name", "순회 이름", allImages(valid), mappingWith("idle", "../idle.png", complete.keySet()));
        Map<String, byte[]> jpeg = allImages(valid); jpeg.put("idle.png", new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xd9}); Provider.addPack("jpeg-bytes", "JPEG 위장", jpeg, mapping("JPEG 위장", jpeg.keySet()));
        Map<String, byte[]> huge = allImages(valid); huge.put("idle.png", new byte[4*1024*1024+1]); Provider.addPack("oversized", "너무 큼", huge, mapping("너무 큼", huge.keySet()));
        Map<String, byte[]> dimensions = allImages(valid); dimensions.put("idle.png", png(2049, 1, 0xffff0000)); Provider.addPack("oversized-dimensions", "너무 큼 차원", dimensions, mapping("너무 큼 차원", dimensions.keySet()));
        CharacterPacks packs = new CharacterPacks(context()); packs.setTree(TREE); JSONObject snapshot = packs.refresh();
        for (String name : new String[]{"매핑 없음","매핑 손상","파일 없음","원격 이름","순회 이름","JPEG 위장","너무 큼","너무 큼 차원"}) assertFalse(packByName(snapshot, name).getBoolean("valid"));
        assertTrue(packByName(snapshot, "JPEG 위장").getString("error").length() > 0);
    }

    @Test public void unicodeBuiltinNameAndDuplicateNamesRemainSeparate() throws Exception {
        Map<String, byte[]> files = allImages(png(0xff00ffff)); Provider.addPack("literal-builtin", "builtin", files, mapping("사용자 builtin", files.keySet())); Provider.addPack("duplicate-one", "같은 이름", files, mapping("같은 이름", files.keySet())); Provider.addPack("duplicate-two", "같은 이름", files, mapping("같은 이름", files.keySet()));
        CharacterPacks characterPacks = new CharacterPacks(context()); characterPacks.setTree(TREE); JSONObject snapshot = characterPacks.refresh(); JSONArray packs = snapshot.getJSONArray("packs"); assertEquals(4, packs.length()); assertEquals("builtin", packs.getJSONObject(0).getString("id"));
        assertTrue(packs.getJSONObject(1).getString("id").startsWith("custom-")); assertNotEquals(packs.getJSONObject(2).getString("id"), packs.getJSONObject(3).getString("id")); assertTrue(packByName(snapshot, "사용자 builtin").getBoolean("valid"));
    }

    @Test public void selectedPackFallsBackWhenFolderDisappears() throws Exception {
        Map<String, byte[]> files = allImages(png(0xffffff00)); Provider.addPack("pack-remove", "삭제될 팩", files, mapping("삭제될 팩", files.keySet())); CharacterPacks packs = new CharacterPacks(context()); packs.setTree(TREE); String id = packByName(packs.refresh(), "삭제될 팩").getString("id"); packs.select(id); Provider.nodes.get("root").children.remove("pack-remove");
        assertEquals("builtin", packs.refresh().getString("selectedPackId"));
    }

    @Test public void permissionFailureProducesErrorAndBuiltinSelection() throws Exception {
        Map<String, byte[]> files = allImages(png(0xff123456)); Provider.addPack("permission-pack", "권한 팩", files, mapping("권한 팩", files.keySet()));
        CharacterPacks packs = new CharacterPacks(context()); packs.setTree(TREE); String id = packByName(packs.refresh(), "권한 팩").getString("id"); packs.select(id); Provider.denyQueries = true; JSONObject snapshot = packs.refresh(); assertEquals("builtin", snapshot.getString("selectedPackId")); assertTrue(snapshot.getString("error").length() > 0); assertEquals("builtin", new CharacterPacks(context()).list().getString("selectedPackId"));
    }

    @Test public void customImageFailureFallsBackToBundledAssetAndRecordsError() throws Exception {
        Map<String, byte[]> files = allImages(png(0xffff00ff)); Provider.addPack("pack-failure", "실패 팩", files, mapping("실패 팩", files.keySet())); CharacterPacks packs = new CharacterPacks(context()); packs.setTree(TREE); String id = packByName(packs.refresh(), "실패 팩").getString("id"); packs.select(id); Provider.denyOpens = true;
        byte[] fallback = read(packs.route(customUri(id, "idle"))); byte[] bundled; try (InputStream input = context().getAssets().open("web/chat-icons/01-idle.png")) { bundled = input.readAllBytes(); } assertArrayEquals(bundled, fallback); JSONObject failed = packByName(packs.list(), "실패 팩"); assertFalse(failed.getBoolean("valid")); assertEquals("builtin", packs.list().getString("selectedPackId")); assertEquals("builtin", new CharacterPacks(context()).list().getString("selectedPackId")); assertTrue(packs.list().getString("error").length() > 0);
    }

    private static Context context() { return RuntimeEnvironment.getApplication(); }
    private static Map<String, byte[]> allImages(byte[] bytes) { Map<String, byte[]> files = new LinkedHashMap<>(); for (String key : CharacterPacks.KEYS) files.put(key + ".png", bytes); return files; }
    private static String mapping(String displayName, Iterable<String> filenames) { Map<String,String> values = new LinkedHashMap<>(); for (String filename : filenames) values.put(filename.substring(0, filename.length()-4), filename); return mappingWithName(displayName, values); }
    private static String mappingWith(String key, String filename, Iterable<String> filenames) { Map<String,String> values = new LinkedHashMap<>(); for (String file : filenames) values.put(file.substring(0, file.length()-4), file); values.put(key, filename); return mappingWithName("테스트 팩", values); }
    private static String mappingWithName(String displayName, Map<String,String> values) { StringBuilder out = new StringBuilder("{\"name\":").append(JSONObject.quote(displayName)).append(",\"icons\":{"); for (int i=0;i<CharacterPacks.KEYS.size();i++) { if (i>0) out.append(','); String key=CharacterPacks.KEYS.get(i); out.append(JSONObject.quote(key)).append(':').append(JSONObject.quote(values.getOrDefault(key, ""))); } return out.append("}}").toString(); }
    private static JSONObject packByName(JSONObject snapshot, String name) throws Exception { JSONArray packs=snapshot.getJSONArray("packs"); for(int i=0;i<packs.length();i++) if(name.equals(packs.getJSONObject(i).getString("name"))) return packs.getJSONObject(i); throw new AssertionError("missing pack: " + name + " in " + packs); }
    private static Uri customUri(String id, String key) { return Uri.parse("https://appassets.androidplatform.net/character-packs/" + id + "/" + key); }
    private static byte[] read(WebResourceResponse response) throws IOException { try(InputStream input=response.getData()) { return input.readAllBytes(); } }
    private static byte[] png(int color) { return png(2,2,color); }
    private static byte[] png(int width, int height, int color) { Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); ByteArrayOutputStream out=new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); bitmap.recycle(); return out.toByteArray(); }
}
