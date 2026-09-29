package dev.mobilecodex.app;

import dev.mobilecodex.app.core.sync.PortableProjects;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Bounded, strict UTF-8 transport; project schema is checked before returning or writing. */
final class ProjectTransferFiles {
    static String read(InputStream in) throws IOException {
        if (in == null) throw new IOException("Cannot open project file");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; int n;
        while ((n = in.read(chunk)) != -1) {
            if (buffer.size() + n > PortableProjects.MAX_BYTES) throw new IOException("Project file exceeds 1 MiB");
            buffer.write(chunk, 0, n);
        }
        String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
        PortableProjects.parse(content, true); return content;
    }
    static void write(OutputStream out, String content) throws IOException {
        PortableProjects.parse(content, true);
        if (out == null) throw new IOException("Cannot open project destination");
        out.write(content.getBytes(StandardCharsets.UTF_8)); out.flush();
    }
}
