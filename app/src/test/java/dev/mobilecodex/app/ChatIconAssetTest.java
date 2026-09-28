package dev.mobilecodex.app;

import android.app.Application;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class ChatIconAssetTest {
    @Test public void everyStylesheetAndScriptLinkedFromThePagePassesTheWebViewRoute() throws Exception {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        String html;
        try (java.io.InputStream input = assets.open("web/index.html")) {
            html = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.regex.Matcher links = java.util.regex.Pattern.compile("(?:href|src)=\"([^\"#]+\\.(?:css|js))\"").matcher(html);
        int count = 0;
        boolean designStylesheet = false;
        while (links.find()) {
            String path = "/" + links.group(1);
            WebResourceResponse response = MainActivity.packagedUiResponse(assets,
                Uri.parse("https://appassets.androidplatform.net" + path));
            assertEquals(path, 200, response.getStatusCode());
            assertEquals(path, path.endsWith(".css") ? "text/css" : "application/javascript", response.getMimeType());
            try (java.io.InputStream input = response.getData()) { assertTrue(path, input.read() >= 0); }
            designStylesheet |= path.equals("/visual-system.css");
            count++;
        }
        assertTrue("The linked design stylesheet must be served", designStylesheet);
        assertTrue("The page's linked assets were checked", count > 1);
    }

    @Test public void packagedUiRouteKeepsUnknownPathsAndRemoteOriginsBlocked() {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        for (String url : new String[]{
                "https://appassets.androidplatform.net/other.css",
                "https://appassets.androidplatform.net/visual-system.css/extra",
                "https://appassets.androidplatform.net/../visual-system.css",
                "https://appassets.androidplatform.net/chat-web-custom.js",
                "https://appassets.androidplatform.net/legal/LICENSE",
                "http://appassets.androidplatform.net/visual-system.css",
                "https://evil.example/visual-system.css"}) {
            assertEquals(url, 403, MainActivity.packagedUiResponse(assets, Uri.parse(url)).getStatusCode());
        }
    }

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

    @Test public void packagedLogoPassesTheWebViewRouteAndOpens() throws Exception {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        WebResourceResponse response = MainActivity.packagedLogoResponse(assets,
            Uri.parse("https://appassets.androidplatform.net/codex-logo.png"));
        assertEquals(200, response.getStatusCode());
        assertEquals("image/png", response.getMimeType());
        assertArrayEquals("codex-logo.png", new byte[]{(byte)137,80,78,71,13,10,26,10}, response.getData().readNBytes(8));
        for (String url : new String[]{
                "https://appassets.androidplatform.net/codex-logo.png/extra",
                "https://appassets.androidplatform.net/logo.png",
                "http://appassets.androidplatform.net/codex-logo.png",
                "https://evil.example/codex-logo.png"}) {
            WebResourceResponse denied = MainActivity.packagedLogoResponse(assets, Uri.parse(url));
            assertEquals(url, 403, denied.getStatusCode());
        }
    }
}
