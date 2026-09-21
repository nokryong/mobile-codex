package dev.mobilecodex.app;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// Native graphics at API 35 is unsupported on Windows by Robolectric 4.14.
// Exercise the real decoder at our minimum supported Android API instead.
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AttachmentStoreTest {
    private AttachmentStore store() { var c = RuntimeEnvironment.getApplication(); return new AttachmentStore(c, new ImageStore(c)); }
    @Test public void originalDocumentSurvivesReopenAndInputContainsReadablePath() throws Exception {
        byte[] bytes = "한글 코드\nconst answer = 42;\n".getBytes(StandardCharsets.UTF_8);
        JSONObject file = store().store(new ByteArrayInputStream(bytes), "코드.ts", "text/typescript");
        AttachmentStore reopened = store();
        try (InputStream in = reopened.open(file.getString("id"))) { assertArrayEquals(bytes, in.readAllBytes()); }
        JSONObject input = reopened.inputs(array(file.getString("id"))).getJSONObject(0);
        assertEquals("text", input.getString("type"));
        assertTrue(input.getString("text").contains(JSONObject.quote(file.getString("path"))));
        assertArrayEquals(bytes, Files.readAllBytes(new File(file.getString("path")).toPath()));
        assertTrue(input.has("text_elements"));
    }
    @Test public void imageUsesPinnedLocalImageInputAndPreviewWithoutChangingOriginal() throws Exception {
        byte[] bytes = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEUlEQVR4nGMQMgn7D8MMyBwAdJkJo6/9UVsAAAAASUVORK5CYII=", android.util.Base64.DEFAULT);
        JSONObject file = store().store(new ByteArrayInputStream(bytes), "design.png", "image/png");
        assertEquals(3, file.getJSONObject("image").getInt("width"));
        JSONObject input = store().inputs(array(file.getString("id"))).getJSONObject(0);
        assertEquals("localImage", input.getString("type")); assertEquals(file.getString("path"), input.getString("path"));
        assertArrayEquals(bytes, Files.readAllBytes(new File(input.getString("path")).toPath()));
    }
    @Test public void sameNameFilesRemainDistinctAndProviderNamesCannotEscapeStore() throws Exception {
        AttachmentStore store = store();
        JSONObject first = store.store(new ByteArrayInputStream(new byte[]{1}), "../metadata.json", null);
        JSONObject second = store.store(new ByteArrayInputStream(new byte[]{2}), "../metadata.json", null);
        assertNotEquals(first.getString("path"), second.getString("path"));
        assertEquals(first.getString("id"), new File(first.getString("path")).getParentFile().getName());
        assertThrows(IOException.class, () -> store.get("../codex/auth.json"));
        assertEquals(2, store.inputs(array(first.getString("id"), second.getString("id"))).length());
    }
    @Test public void interruptedCopyLeavesNoUsablePartialAttachment() throws Exception {
        File dir = new File(RuntimeEnvironment.getApplication().getFilesDir(), "attachments"); AttachmentStore store = store();
        int count = dir.list().length;
        assertThrows(IOException.class, () -> store.store(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("provider disconnected"); }
        }, "broken.pdf", "application/pdf"));
        assertEquals(count, dir.list().length);
        JSONObject good = store.store(new ByteArrayInputStream(new byte[]{9}), "good.pdf", "application/pdf");
        assertEquals(1, store.inputs(array(good.getString("id"))).length());
    }
}
