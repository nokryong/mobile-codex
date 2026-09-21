package dev.mobilecodex.app;

import android.app.Application;
import android.content.res.AssetManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class ChatIconAssetTest {
    @Test public void all32PackagedIconsPassTheWebViewRouteAndOpen() throws Exception {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        String[] names = assets.list("web/chat-icons");
        assertNotNull(names);
        assertEquals(32, names.length);
        for (String name : names) {
            assertTrue(name, MainActivity.isChatIconPath("/chat-icons/" + name));
            try (java.io.InputStream input = assets.open("web/chat-icons/" + name)) {
                assertArrayEquals(name, new byte[]{(byte)137,80,78,71,13,10,26,10}, input.readNBytes(8));
            }
        }
    }

    @Test public void iconRouteRejectsTraversalAndNonPngPaths() {
        for (String path : new String[]{null,"/chat-icons/../auth.json","/chat-icons/23-not-allowed.png/extra",
                "/chat-icons/23-not-allowed.svg","/chat-icons/23-not--allowed.png","/other/23-not-allowed.png"}) {
            assertFalse(String.valueOf(path), MainActivity.isChatIconPath(path));
        }
    }
}
