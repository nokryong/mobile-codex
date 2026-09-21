package dev.mobilecodex.app.core;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class WorkspacePathTest {
    @Test public void acceptsUnicodeAndChecksVersion() {
        assertEquals(2, WorkspacePath.segments("프로젝트/문서.txt").size());
        byte[] bytes = "한글 수정".getBytes(StandardCharsets.UTF_8);
        WorkspacePath.requireVersion(WorkspacePath.hash(bytes), bytes);
        assertThrows(IllegalStateException.class, () -> WorkspacePath.requireVersion(WorkspacePath.hash("old"), bytes));
    }
    @Test public void rejectsProviderTraversalAndInvalidNames() {
        for (String path : new String[]{"../secret", "/data/data", "a/../../b", "a//b", "a/", "a\\b", "a\0b"})
            assertThrows(path, IllegalArgumentException.class, () -> WorkspacePath.segments(path));
        assertTrue(WorkspacePath.segments("").isEmpty());
        assertEquals("a", WorkspacePath.parent("a/b"));
    }
}
