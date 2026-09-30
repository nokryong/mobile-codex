package dev.mobilecodex.app;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static dev.mobilecodex.app.core.Json.obj;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
    shadows = DocumentStoreRelocationTest.ModernQueryContentResolver.class)
public class DocumentStoreRelocationTest {
    private static final String AUTHORITY = "relocation.tests";
    private static final Uri TREE = Uri.parse("content://" + AUTHORITY + "/tree/root");
    private DocumentStore store;
    private RelocatingProvider provider;

    /** Match Android's legacy-to-Bundle query bridge instead of invoking the obsolete provider overload. */
    @Implements(ContentResolver.class)
    public static final class ModernQueryContentResolver extends ShadowContentResolver {
        @Override @Implementation
        protected Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            if (!AUTHORITY.equals(uri.getAuthority())) return super.query(uri, projection, selection, selectionArgs, sortOrder);
            Bundle args = new Bundle();
            if (selection != null) args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            if (selectionArgs != null) args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
            if (sortOrder != null) args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder);
            return super.query(uri, projection, args, null);
        }
    }

    /** Exercises the framework's rename/move contract, including replacement document IDs. */
    public static final class RelocatingProvider extends DocumentsProvider {
        private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
        final Map<String, Entry> entries = new LinkedHashMap<>();
        String lastDocumentId;
        private int relocationCount;

        private static final class Entry {
            final String id, parent, name;
            final File file;
            Entry(String id, String parent, String name, File file) {
                this.id = id; this.parent = parent; this.name = name; this.file = file;
            }
            boolean directory() { return file == null; }
        }

        void seed(File content) {
            entries.put("root", new Entry("root", null, "Project", null));
            entries.put("source", new Entry("source", "root", "source", null));
            entries.put("target", new Entry("target", "root", "target", null));
            entries.put("original-file", new Entry("original-file", "source", "a.txt", content));
        }

        @Override public boolean onCreate() { return true; }

        @Override public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
        }

        @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
            MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
            addRow(cursor, require(documentId));
            return cursor;
        }

        @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
                throws FileNotFoundException {
            require(parentDocumentId);
            MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
            for (Entry entry : entries.values()) if (parentDocumentId.equals(entry.parent)) addRow(cursor, entry);
            return cursor;
        }

        private void addRow(MatrixCursor cursor, Entry entry) {
            Object[] row = new Object[cursor.getColumnCount()];
            String[] columns = cursor.getColumnNames();
            for (int i = 0; i < columns.length; i++) row[i] = switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID -> entry.id;
                case Document.COLUMN_DISPLAY_NAME -> entry.name;
                case Document.COLUMN_MIME_TYPE -> entry.directory() ? Document.MIME_TYPE_DIR : "text/plain";
                case Document.COLUMN_SIZE -> entry.directory() ? 0L : entry.file.length();
                case Document.COLUMN_LAST_MODIFIED -> entry.directory() ? 0L : entry.file.lastModified();
                case Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE
                    | (entry.directory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
                default -> null;
            };
            cursor.addRow(row);
        }

        @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
                throws FileNotFoundException {
            Entry entry = require(documentId);
            if (entry.directory()) throw new FileNotFoundException("Not a file: " + documentId);
            return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode));
        }

        @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
            Entry entry = entries.get(documentId);
            while (entry != null && entry.parent != null) {
                if (parentDocumentId.equals(entry.parent)) return true;
                entry = entries.get(entry.parent);
            }
            return false;
        }

        @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
            Entry entry = require(documentId);
            String normalized = displayName.toLowerCase(Locale.ROOT).replace(' ', '_');
            return relocate(entry, entry.parent, normalized);
        }

        @Override public String moveDocument(String documentId, String sourceParentDocumentId, String targetParentDocumentId)
                throws FileNotFoundException {
            Entry entry = require(documentId);
            if (!sourceParentDocumentId.equals(entry.parent) || !require(targetParentDocumentId).directory())
                throw new FileNotFoundException("Invalid relocation parent");
            return relocate(entry, targetParentDocumentId, "moved-" + entry.name);
        }

        private String relocate(Entry entry, String parent, String name) {
            String replacementId = "relocated-" + ++relocationCount;
            entries.remove(entry.id);
            entries.put(replacementId, new Entry(replacementId, parent, name, entry.file));
            lastDocumentId = replacementId;
            return replacementId;
        }

        private Entry require(String documentId) throws FileNotFoundException {
            Entry entry = entries.get(documentId);
            if (entry == null) throw new FileNotFoundException("Unknown document: " + documentId);
            return entry;
        }
    }

    @Before public void before() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("projects", 0).edit().clear().commit();
        context.getSharedPreferences("workspace", 0).edit().clear().commit();
        File file = new File(context.getCacheDir(), "relocation-content.txt");
        Files.write(file.toPath(), "original content".getBytes(StandardCharsets.UTF_8));
        provider = new RelocatingProvider();
        provider.seed(file);
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
        context.getContentResolver().takePersistableUriPermission(TREE,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        store = new DocumentStore(context);
        store.select(TREE, "relocation-project");
        assertNull("This test must use the provider, not direct filesystem mapping", store.directDirectory());
    }

    @Test public void renameReturnsActualProviderNameAndReplacementDocumentRemainsEditable() throws Exception {
        JSONObject result = store.commit(store.prepare("mobile_rename", obj("path", "source/a.txt", "name", "NEW NAME.txt")));

        assertEquals("source/new_name.txt", result.getString("path"));
        assertFalse(provider.entries.containsKey("original-file"));
        assertNotEquals("original-file", provider.lastDocumentId);
        assertThrows(FileNotFoundException.class, () -> store.read("source/a.txt"));
        assertEditableAtReturnedPath(result, "content after rename");
    }

    @Test public void moveReturnsDestinationAndActualProviderNameWithoutLosingContent() throws Exception {
        JSONObject result = store.commit(store.prepare("mobile_move", obj("path", "source/a.txt", "destination", "target")));

        assertEquals("target/moved-a.txt", result.getString("path"));
        assertFalse(provider.entries.containsKey("original-file"));
        assertNotEquals("original-file", provider.lastDocumentId);
        assertThrows(FileNotFoundException.class, () -> store.read("source/a.txt"));
        assertEditableAtReturnedPath(result, "content after move");
    }

    @Test public void moveToProjectRootReturnsUsableRelativePath() throws Exception {
        JSONObject result = store.commit(store.prepare("mobile_move", obj("path", "source/a.txt", "destination", "")));

        assertEquals("moved-a.txt", result.getString("path"));
        assertEditableAtReturnedPath(result, "content at root");
    }

    private void assertEditableAtReturnedPath(JSONObject result, String replacement) throws Exception {
        String path = result.getString("path");
        JSONObject before = store.read(path);
        assertEquals("original content", before.getString("content"));
        JSONObject written = store.commit(store.prepare("mobile_write", obj("path", path, "content", replacement,
            "expectedSha256", before.getString("sha256"))));
        assertEquals(path, written.getString("path"));
        assertEquals(replacement, store.read(written.getString("path")).getString("content"));
    }
}
