package dev.mobilecodex.app;

import dev.mobilecodex.app.core.sync.PortableProjects;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ProjectTransferFilesTest {
    @Test public void streamRoundTripAndInvalidUtf8Boundary() throws Exception {
        PortableProjects p = new PortableProjects(); p.create("proj_a", "한글", "device_phone", false);
        String raw = p.json().toString(); ByteArrayOutputStream out = new ByteArrayOutputStream(); ProjectTransferFiles.write(out,raw);
        assertEquals(raw,ProjectTransferFiles.read(new ByteArrayInputStream(out.toByteArray())));
        assertThrows(IOException.class, () -> ProjectTransferFiles.read(new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0xaf})));
        assertThrows(IOException.class, () -> ProjectTransferFiles.read(new ByteArrayInputStream(new byte[PortableProjects.MAX_BYTES + 1])));
        ByteArrayOutputStream untouched = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> ProjectTransferFiles.write(untouched,"{\"auth\":true}"));
        assertEquals(0,untouched.size());
    }
}
