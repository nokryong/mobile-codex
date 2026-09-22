package dev.mobilecodex.app.core;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Uses a real temporary Git repository; no developer repository or index is touched. */
public class ChangeReviewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static String readText(java.nio.file.Path path)throws IOException{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}
    private static void writeText(java.nio.file.Path path,String text)throws IOException{Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private File root; private ChangeReview changes;
    private byte[] git(File directory, List<String> args) throws Exception {
        ArrayList<String> argv = new ArrayList<>(List.of("git", "--literal-pathspecs")); argv.addAll(args);
        return ProcessOutput.run(new ProcessBuilder(argv).directory(directory), 16*1024*1024, 10);
    }
    private String git(String... args) throws Exception { return new String(git(root, Arrays.asList(args)), StandardCharsets.UTF_8).trim(); }
    private void write(String path,String text) throws Exception { Path p=root.toPath().resolve(path); Files.createDirectories(p.getParent()); writeText(p,text); }
    @Before public void setup() throws Exception {
        root=temp.newFolder("project"); changes=new ChangeReview(temp.newFolder("backups"), this::git);
        git("init"); git("config","user.email","test@example.invalid"); git("config","user.name","Test");
        write("한 글[1].txt","original\n"); git("add","."); git("commit","-m","baseline");
    }
    @Test public void restoresOnlySelectedWorktreeFileAndCanUndoRestoreWithoutChangingIndex() throws Exception {
        write("한 글[1].txt","staged\n"); git("add","."); write("한 글[1].txt","working\n"); write("other.txt","keep");
        String index=git("write-tree"); JSONObject preview=changes.preview(root,"한 글[1].txt");
        assertEquals("original\n",preview.getString("before")); assertEquals("working\n",preview.getString("after"));
        JSONObject result=changes.restore(root,preview.getString("token"));
        assertEquals("original\n",readText(root.toPath().resolve("한 글[1].txt"))); assertEquals(index,git("write-tree"));
        assertEquals("keep",readText(root.toPath().resolve("other.txt")));
        JSONObject backup=changes.previewBackup(root,result.getString("backupId")); changes.restore(root,backup.getString("token"));
        assertEquals("working\n",readText(root.toPath().resolve("한 글[1].txt"))); assertEquals(index,git("write-tree"));
    }
    @Test public void refusesChangedFilesHeadAndCrossProjectTokens() throws Exception {
        write("한 글[1].txt","edit"); JSONObject p=changes.preview(root,"한 글[1].txt"); write("한 글[1].txt","external");
        assertThrows(IOException.class,()->changes.restore(root,p.getString("token"))); assertEquals("external",readText(root.toPath().resolve("한 글[1].txt")));
        JSONObject q=changes.preview(root,"한 글[1].txt"); git("add","."); git("commit","-m","moved head");
        assertThrows(IOException.class,()->changes.restore(root,q.getString("token")));
        assertThrows(IOException.class,()->changes.restore(temp.newFolder("other"),q.getString("token")));
    }
    @Test public void newFileRemovalHasRecoverableCopyAndRejectsLaterEditsToRestoreResult() throws Exception {
        write("new.txt","new content"); JSONObject p=changes.preview(root,"new.txt"); assertFalse(p.getBoolean("beforeExists"));
        JSONObject restored=changes.restore(root,p.getString("token")); assertFalse(new File(root,"new.txt").exists());
        JSONObject backup=changes.previewBackup(root,restored.getString("backupId")); changes.restore(root,backup.getString("token"));
        assertEquals("new content",readText(root.toPath().resolve("new.txt")));
        assertThrows(IOException.class,()->changes.previewBackup(root,restored.getString("backupId")));
    }
    @Test public void subdirectoryReviewDoesNotIncludeOrRestoreSiblingFiles() throws Exception {
        write("sub/a.txt","old"); write("sibling.txt","sibling"); git("add","."); git("commit","-m","folders");
        write("sub/a.txt","new"); write("sibling.txt","untouched edit"); File sub=new File(root,"sub");
        JSONObject list=changes.list(sub); assertEquals(1,list.getJSONArray("entries").length());
        JSONObject p=changes.preview(sub,"a.txt"); assertEquals("old",p.getString("before")); changes.restore(sub,p.getString("token"));
        assertEquals("untouched edit",readText(root.toPath().resolve("sibling.txt")));
    }
    @Test public void rejectsTraversalGitInternalsAndSymlinks() throws Exception {
        assertThrows(IOException.class,()->changes.preview(root,"../outside")); assertThrows(IOException.class,()->changes.preview(root,".git/config"));
        Path outside=temp.newFile("outside").toPath(); writeText(outside,"preserve"); Files.createSymbolicLink(new File(root,"link").toPath(),outside);
        assertThrows(IOException.class,()->changes.preview(root,"link")); assertEquals("preserve",readText(outside));
    }
    @Test public void emptyRepositoryAndDeletedFilesCanBeReviewed() throws Exception {
        File empty=temp.newFolder("empty"); git(empty,List.of("init")); writeText(new File(empty,"first.txt").toPath(),"first");
        assertFalse(changes.preview(empty,"first.txt").getBoolean("beforeExists"));
        Files.delete(root.toPath().resolve("한 글[1].txt")); JSONObject p=changes.preview(root,"한 글[1].txt"); assertFalse(p.getBoolean("afterExists")); changes.restore(root,p.getString("token"));
        assertEquals("original\n",readText(root.toPath().resolve("한 글[1].txt")));
    }
}
