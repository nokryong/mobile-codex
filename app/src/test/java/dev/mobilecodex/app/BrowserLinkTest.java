package dev.mobilecodex.app;

import android.app.Application;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class BrowserLinkTest {
    @Test public void messageLinksAllowHttpWithoutWeakeningAccountFlows() {
        assertTrue(MainActivity.isBrowserUri(Uri.parse("http://example.com/docs"), true));
        assertTrue(MainActivity.isBrowserUri(Uri.parse("https://example.com/docs"), true));
        assertTrue(MainActivity.isBrowserUri(Uri.parse("https://example.com/oauth"), false));
        assertFalse(MainActivity.isBrowserUri(Uri.parse("http://example.com/oauth"), false));
    }
    @Test public void neitherRouteAcceptsCredentialsFilesOrExecutableSchemes() {
        for (String url : new String[]{"javascript:alert(1)", "file:///private/auth.json", "content://provider/file", "intent://example.com", "https://user:pass@example.com", "https:///", "/relative"}) {
            assertFalse(url, MainActivity.isBrowserUri(Uri.parse(url), true));
            assertFalse(url, MainActivity.isBrowserUri(Uri.parse(url), false));
        }
    }
}
