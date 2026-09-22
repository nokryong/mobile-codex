package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** The app's Codex home, with a non-destructive migration from the pre-0.1.3 location. */
public final class CodexHome {
    public static final class Migration {
        public final int copied, conflicts, skippedEntries;
        Migration(int copied, int conflicts, int skippedEntries) {
            this.copied = copied; this.conflicts = conflicts; this.skippedEntries = skippedEntries;
        }
        public String notice() {
            if (conflicts > 0) return t("기존 Codex 데이터 ") + conflicts + t("개는 새 파일과 충돌해 이전 위치에 보존했습니다.");
            if (copied > 0) return t("기존 Codex 설정과 기록 ") + copied + t("개를 새 홈으로 복사했습니다.");
            return "";
        }
    }

    private final File root, legacy;
    private final Migration migration;

    private CodexHome(File root, File legacy, Migration migration) {
        this.root = root; this.legacy = legacy; this.migration = migration;
    }
    public static CodexHome open(Context context) throws IOException {
        File files = context.getFilesDir();
        File root = new File(files, ".codex"), legacy = new File(files, "codex");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException(t("Codex 홈 폴더를 만들 수 없습니다."));
        int[] counts = new int[3];
        android.content.SharedPreferences migration = context.getSharedPreferences("codex-home-migration", 0);
        if (!migration.getBoolean("legacy-codex-copied", false)) {
            if (legacy.isDirectory()) copyMissing(legacy, root, counts);
            // Do not resurrect credentials or history from the legacy root after a user removes them from .codex.
            if (!migration.edit().putBoolean("legacy-codex-copied", true).commit()) {
                throw new IOException(t("기존 Codex 데이터 이전 상태를 저장하지 못했습니다."));
            }
        }
        return new CodexHome(root.getCanonicalFile(), legacy.getCanonicalFile(), new Migration(counts[0], counts[1], counts[2]));
    }
    public File root() { return root; }
    /** The old directory remains in place, so older absolute config paths still resolve. */
    public File legacy() { return legacy; }
    public Migration migration() { return migration; }
    public File child(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IOException(t("Codex 홈 파일 이름이 올바르지 않습니다."));
        File file = new File(root, name).getCanonicalFile();
        if (!file.getParentFile().equals(root)) throw new IOException(t("Codex 홈 밖의 파일은 사용할 수 없습니다."));
        return file;
    }
    private static void copyMissing(File source, File target, int[] counts) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) {
            counts[2]++;
            return;
        }
        if (source.isDirectory()) {
            if (target.exists() && !target.isDirectory()) { counts[1]++; return; }
            if (!target.exists() && !target.mkdirs()) throw new IOException(t("기존 Codex 폴더를 옮길 수 없습니다."));
            File[] entries = source.listFiles(); if (entries == null) throw new IOException(t("기존 Codex 폴더를 읽을 수 없습니다."));
            for (File entry : entries) copyMissing(entry, new File(target, entry.getName()), counts);
            return;
        }
        if (!Files.isRegularFile(source.toPath())) {
            counts[2]++;
            return;
        }
        if (target.exists()) { counts[1]++; return; }
        File parent = target.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(t("Codex 홈 폴더를 만들 수 없습니다."));
        File pending = new File(parent, "." + target.getName() + ".migration-" + UUID.randomUUID());
        boolean copied = false;
        try {
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(pending)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            try { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(pending.toPath(), target.toPath()); }
            copied = true;
            counts[0]++;
        } finally {
            if (!copied) Files.deleteIfExists(pending.toPath());
        }
    }
}
