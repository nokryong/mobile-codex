package dev.mobilecodex.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import dev.mobilecodex.app.core.WorkspacePath;

/** Copies a user-picked skill tree into Codex's own skills directory, preserving assets. */
public final class SkillImporter {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
    private SkillImporter() {}
    public static String install(Context context, Uri tree) throws Exception {
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException("스킬 폴더를 선택해 주세요.");
        String rootId = DocumentsContract.getTreeDocumentId(tree), name;
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        try (Cursor c = context.getContentResolver().query(root, COLUMNS, null, null, null)) {
            if (c == null || !c.moveToFirst() || !Document.MIME_TYPE_DIR.equals(c.getString(2))) throw new IOException("폴더를 열 수 없습니다.");
            name = c.getString(1); WorkspacePath.checkName(name);
        }
        File skills = new File(CodexHome.open(context).root(), "skills");
        if (!skills.isDirectory() && !skills.mkdirs()) throw new IOException("스킬 저장소를 만들 수 없습니다.");
        File target = new File(skills, name);
        if (target.exists()) throw new IOException("같은 이름의 스킬이 있습니다. 기존 스킬을 편집하거나 다른 이름을 사용해 주세요.");
        File staging = new File(context.getCacheDir(), "skill-import-" + UUID.randomUUID());
        try {
            copy(context, tree, rootId, staging, new HashSet<>());
            if (!new File(staging, "SKILL.md").isFile()) throw new IOException("선택한 폴더의 바로 아래에 SKILL.md가 있어야 합니다.");
            Files.move(staging.toPath(), target.toPath());
            return target.getAbsolutePath();
        } finally { remove(staging); }
    }
    private static void copy(Context context, Uri tree, String id, File destination, Set<String> visited) throws Exception {
        if (!visited.add(id)) throw new IOException("스킬 폴더에 순환 경로가 있습니다.");
        if (!destination.mkdirs()) throw new IOException("폴더를 만들 수 없습니다.");
        List<String[]> entries = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLUMNS, null, null, null)) {
            if (c == null) throw new IOException("스킬 폴더를 읽을 수 없습니다.");
            while (c.moveToNext()) entries.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        for (String[] entry : entries) {
            WorkspacePath.checkName(entry[1]); File output = new File(destination, entry[1]);
            if (output.exists()) throw new IOException("이름이 같은 항목이 있습니다: " + entry[1]);
            if (Document.MIME_TYPE_DIR.equals(entry[2])) copy(context, tree, entry[0], output, visited);
            else try (InputStream input = context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, entry[0])); OutputStream out = new FileOutputStream(output)) {
                if (input == null) throw new IOException("파일을 읽을 수 없습니다.");
                byte[] buffer = new byte[16384]; int n;
                while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists()) file.delete();
    }
}
