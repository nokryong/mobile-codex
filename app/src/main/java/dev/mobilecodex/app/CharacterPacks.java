package dev.mobilecodex.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.WebResourceResponse;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import static dev.mobilecodex.app.core.Json.obj;

/** Reads user supplied character packs directly from one persisted SAF tree. */
final class CharacterPacks {
    static final String BUILTIN_ID = "builtin";
    static final List<String> KEYS = List.of("idle","acknowledged","thinking","working","question","done","blocked","smug","greeting","inspecting","explaining","discovery","caution","sorry","happy","skeptical","eohu","uhehe","insult","punch","uncertain","disagree","not-allowed","file-request","reviewed","source","fixed","lol","good-grief","wink","heart","sleep");
    private static final int MAX_PACKS = 64, MAX_FILE_BYTES = 4 * 1024 * 1024, MAX_DIMENSION = 2048;
    private final Context context;
    private final ContentResolver resolver;
    private final File metadata;
    private final Object lock = new Object();
    private final LinkedHashMap<String, Pack> packs = new LinkedHashMap<>();
    private long generation;
    private long configRevision;
    private String selectedId;
    private String tree;
    private String folderName = "";
    private String error;
    private record Image(Uri uri, String mime) {}
    private static final class Pack {
        final String id, name, error;
        final Map<String, Image> images;
        Pack(String id, String name, String error, Map<String, Image> images) { this.id=id; this.name=name; this.error=error; this.images=images; }
        boolean valid() { return error == null && images.size() == KEYS.size(); }
    }

    CharacterPacks(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
        File m = null;
        try { m = CodexHome.open(this.context).child("character-packs.json"); } catch (Exception ignored) { error = "캐릭터팩 설정을 저장할 수 없습니다."; }
        metadata = m;
        try { JSONObject saved = new JSONObject(dev.mobilecodex.app.core.Utf8Files.read(metadata.toPath())); tree = saved.optString("tree", null); selectedId = saved.optString("selected", BUILTIN_ID); } catch (Exception ignored) { selectedId = BUILTIN_ID; }
        packs.put(BUILTIN_ID, builtin());
    }

    JSONObject list() {
        synchronized (lock) { return snapshotLocked(); }
    }
    void select(String id) {
        synchronized (lock) {
            if (!BUILTIN_ID.equals(id) && (!packs.containsKey(id) || !packs.get(id).valid())) throw new IllegalArgumentException("유효한 캐릭터팩이 아닙니다.");
            String previous = selectedId; selectedId = id;
            try { saveMetadata(); } catch (RuntimeException e) { selectedId = previous; throw e; }
            configRevision++; generation++;
        }
    }
    JSONObject refresh() {
        final String requestedTree; final long requestedRevision;
        synchronized (lock) { requestedTree = tree; requestedRevision = configRevision; }
        LinkedHashMap<String, Pack> scanned = new LinkedHashMap<>(); scanned.put(BUILTIN_ID, builtin());
        String scannedFolder = ""; String scannedError = null;
        if (requestedTree != null && !requestedTree.isBlank()) try {
                Uri root = Uri.parse(requestedTree); scannedFolder = displayName(root);
                String rootDoc = DocumentsContract.getTreeDocumentId(root);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, rootDoc);
                try (Cursor c = resolver.query(children, columns(), null, null, null)) {
                    if (c == null) throw new IllegalStateException("캐릭터 폴더를 읽지 못했습니다.");
                    int rows = 0, directories = 0;
                    while (c.moveToNext() && rows++ < MAX_PACKS * 8) {
                        String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                        if (!isSafeId(name) || !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                        if (directories++ >= MAX_PACKS) { scannedError = "캐릭터팩이 너무 많습니다."; break; }
                        String packId = packId(id);
                        Pack pack = readPack(root, id, packId, name);
                        scanned.put(packId, pack);
                    }
                }
            } catch (Exception e) { scannedError = message(e); }
        synchronized (lock) {
            if (requestedRevision != configRevision || !Objects.equals(requestedTree, tree)) return snapshotLocked();
            packs.clear(); packs.putAll(scanned); folderName = scannedFolder; error = scannedError;
            if (!packs.containsKey(selectedId) || !packs.get(selectedId).valid()) { selectedId = BUILTIN_ID; saveMetadata(); }
            generation++; return snapshotLocked();
        }
    }
    void setTree(Uri uri) {
        synchronized (lock) {
            String previous = tree; tree = uri == null ? null : uri.toString(); folderName = ""; error = null;
            try { saveMetadata(); } catch (RuntimeException e) { tree = previous; throw e; }
            configRevision++;
        }
    }
    WebResourceResponse route(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || !MainActivity.HOST.equals(uri.getHost())) return denied();
        String[] p = uri.getPath() == null ? new String[0] : uri.getPath().split("/", -1);
        if (p.length != 4 || !"character-packs".equals(p[1]) || !isSafeId(p[2]) || !KEYS.contains(p[3])) return denied();
        if (BUILTIN_ID.equals(p[2])) return denied();
        final Image image; final Pack sourcePack; final long routeRevision;
        synchronized (lock) { sourcePack = packs.get(p[2]); image = sourcePack == null || !sourcePack.valid() ? null : sourcePack.images.get(p[3]); routeRevision = configRevision; }
        if (image == null) return builtinResponse(p[3]);
        try {
            byte[] bytes = readBounded(image.uri); validateImage(bytes, image.mime);
            return new WebResourceResponse(image.mime, null, 200, "OK", Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"), new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            synchronized(lock) {
                if (routeRevision == configRevision && packs.get(p[2]) == sourcePack) {
                    packs.put(p[2], new Pack(p[2], sourcePack.name, message(e), Map.of()));
                    error = message(e);
                    if (p[2].equals(selectedId)) { selectedId = BUILTIN_ID; try { saveMetadata(); } catch (RuntimeException saveError) { error = message(saveError); } }
                    configRevision++; generation++;
                }
            }
            return builtinResponse(p[3]);
        }
    }
    private Pack readPack(Uri root, String docId, String id, String folderName) {
        try {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(root, docId);
            Map<String, Uri> files = new HashMap<>(); Uri mapping = null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, docId);
            try (Cursor c = resolver.query(children, columns(), null, null, null)) {
                if (c == null) throw new IllegalStateException("팩 폴더를 읽지 못했습니다.");
                int fileCount = 0;
                while (c.moveToNext()) {
                    if (++fileCount > 128) throw new IllegalArgumentException("팩 파일 수가 너무 많습니다.");
                    String childId=c.getString(0), name=c.getString(1), mime=c.getString(2);
                    if ("mapping.json".equals(name) && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) mapping = DocumentsContract.buildDocumentUriUsingTree(root, childId);
                    else if (isSafeFile(name) && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) files.put(name, DocumentsContract.buildDocumentUriUsingTree(root, childId));
                }
            }
            if (mapping == null) throw new IllegalArgumentException("mapping.json이 없습니다.");
            JSONObject json; try (InputStream in=resolver.openInputStream(mapping)) { if (in == null) throw new IllegalStateException("mapping.json을 열 수 없습니다."); json = new JSONObject(new String(readBounded(in, 256 * 1024), java.nio.charset.StandardCharsets.UTF_8)); }
            JSONObject icons=json.optJSONObject("icons"); if (icons == null) throw new IllegalArgumentException("icons가 없습니다.");
            Map<String, Image> images = new LinkedHashMap<>(); for (String key: KEYS) {
                String filename=icons.optString(key, ""); if (!isSafeFile(filename) || !files.containsKey(filename)) throw new IllegalArgumentException("아이콘이 없습니다: " + key);
                String mime=mime(filename); if (mime == null) throw new IllegalArgumentException("지원하지 않는 이미지입니다: " + key);
                validateImage(readBounded(files.get(filename)), mime);
                images.put(key, new Image(files.get(filename), mime));
            }
            return new Pack(id, json.optString("name", folderName), null, images);
        } catch (Exception e) { return new Pack(id, folderName, message(e), Map.of()); }
    }
    private JSONObject snapshotLocked() {
        org.json.JSONArray out = new org.json.JSONArray(); for (Pack p: packs.values()) {
            JSONObject icons=obj(); if (p.valid()) for (String key: KEYS) try { icons.put(key, iconUrl(p.id,key)); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            out.put(obj("id",p.id,"name",p.name,"valid",p.valid(),"error",p.error,"icons",icons));
        }
        return obj("folderName",folderName,"folderConfigured",tree != null,"selectedPackId",selectedId,"packs",out,"error",error);
    }
    private String iconUrl(String id,String key) { if (BUILTIN_ID.equals(id)) return "https://"+MainActivity.HOST+"/chat-icons/"+String.format(Locale.ROOT,"%02d",KEYS.indexOf(key)+1)+"-"+key+".png"; return "https://"+MainActivity.HOST+"/character-packs/"+id+"/"+key+"?v="+generation; }
    private Pack builtin() { Map<String,Image> m=new LinkedHashMap<>(); for(String key:KEYS)m.put(key,new Image(null,"image/png")); return new Pack(BUILTIN_ID,"기본 캐릭터",null,m); }
    private String displayName(Uri uri) { try { Uri doc=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)); try (Cursor c=resolver.query(doc,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) { return c!=null&&c.moveToFirst()?c.getString(0):""; } } catch(Exception e){return "";} }
    private static String[] columns(){return new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};}
    private byte[] readBounded(Uri uri) throws Exception { try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IllegalStateException();return readBounded(in);} }
    private static byte[] readBounded(InputStream in)throws Exception{return readBounded(in,MAX_FILE_BYTES);}
    private static byte[] readBounded(InputStream in,int limit)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IllegalArgumentException("파일이 너무 큽니다.");out.write(b,0,n);}return out.toByteArray();}
    private static void validateImage(byte[] b,String mime){boolean png=b.length>=8&&b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10;boolean webp=b.length>=12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P';if(("image/png".equals(mime)&&!png)||("image/webp".equals(mime)&&!webp))throw new IllegalArgumentException("이미지 형식이 일치하지 않습니다.");BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(b,0,b.length,o);if(o.outWidth<=0||o.outHeight<=0||o.outWidth>MAX_DIMENSION||o.outHeight>MAX_DIMENSION)throw new IllegalArgumentException("이미지 크기가 올바르지 않습니다.");}
    private static String mime(String n){String x=n.toLowerCase(Locale.ROOT);if(x.endsWith(".png"))return "image/png";if(x.endsWith(".webp"))return "image/webp";return null;}
    private void saveMetadata(){if(metadata==null)throw new IllegalStateException("캐릭터팩 설정을 저장할 수 없습니다.");try{JSONObject value=obj("tree",tree,"selected",selectedId);File tmp=new File(metadata.getParentFile(),metadata.getName()+".tmp");dev.mobilecodex.app.core.Utf8Files.write(tmp.toPath(),value.toString());try{Files.move(tmp.toPath(),metadata.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){Files.move(tmp.toPath(),metadata.toPath(),StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("캐릭터팩 설정을 저장할 수 없습니다.",e);}}
    private static boolean isSafeId(String x){return x!=null&&x.length()>0&&x.length()<=128&&!x.equals(".")&&!x.equals("..");}
    private static boolean isSafeFile(String x){return x!=null&&x.length()<=128&&!x.contains("/")&&!x.contains("\\")&&!x.equals(".")&&!x.equals("..")&&mime(x)!=null;}
    private static String packId(String documentId){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(documentId.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder("custom-");for(int i=0;i<8;i++)s.append(String.format(Locale.ROOT,"%02x",b[i]));return s.toString();}catch(Exception e){return "custom-"+Integer.toHexString(documentId.hashCode());}}
    private static String message(Exception e){return e.getMessage()==null?"캐릭터팩을 읽지 못했습니다.":e.getMessage();}
    private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Map.of(),new ByteArrayInputStream(new byte[0]));}
    private WebResourceResponse builtinResponse(String key){try{int i=KEYS.indexOf(key)+1;return new WebResourceResponse("image/png",null,200,"OK",Map.of("Cache-Control","no-store","X-Content-Type-Options","nosniff"),context.getAssets().open("web/chat-icons/"+String.format(Locale.ROOT,"%02d",i)+"-"+key+".png"));}catch(Exception e){return denied();}}
}
