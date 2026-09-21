package dev.mobilecodex.app.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class RuntimePayloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private byte[] archive(String name, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    @Test public void extractsPinnedOfflinePayload() throws Exception {
        byte[] zip = archive("lib/python3.14/example.py", "print('test')");
        Path root = temporary.getRoot().toPath().resolve("usr");
        RuntimePayload.extract(new ByteArrayInputStream(zip), root, RuntimePayload.sha256(zip));
        assertEquals("print('test')", new String(Files.readAllBytes(root.resolve("lib/python3.14/example.py")), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolveSibling("usr.zip")));
    }
    @Test public void tamperedPayloadNeverBecomesInstalled() throws Exception {
        byte[] zip = archive("file", "tampered");
        Path root = temporary.getRoot().toPath().resolve("usr");
        assertThrows(IOException.class, () -> RuntimePayload.extract(new ByteArrayInputStream(zip), root, "0".repeat(64)));
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(root.resolveSibling("usr.zip")));
    }
    @Test public void traversalAndAbsolutePathsCannotEscapeStaging() throws Exception {
        for (String name : new String[]{"../secret", "lib/../../secret", "/secret", "C:/secret", "lib\\secret", "./secret"}) {
            byte[] zip = archive(name, "untrusted");
            Path parent = temporary.newFolder().toPath(), root = parent.resolve("usr");
            assertThrows(name, IOException.class, () -> RuntimePayload.extract(new ByteArrayInputStream(zip), root, RuntimePayload.sha256(zip)));
            assertFalse(Files.exists(parent.resolve("secret")));
        }
    }
}
