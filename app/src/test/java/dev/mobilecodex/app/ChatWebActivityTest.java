package dev.mobilecodex.app;

import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ChatWebActivityTest {
    @Test public void navigationAcceptsOnlyExactOriginsAndModeCommand() {
        assertTrue(ChatWebActivity.official(Uri.parse("https://chatgpt.com/c/example")));
        assertTrue(ChatWebActivity.modeLink(Uri.parse("mobilecodex://mode/codex")));
        assertTrue(ChatWebActivity.internal(Uri.parse("https://auth.openai.com/log-in")));
        for (String url : new String[]{"https://chatgpt.com.evil.test/", "https://user:secret@chatgpt.com/", "http://chatgpt.com/", "javascript:alert(1)"}) {
            assertFalse(ChatWebActivity.official(Uri.parse(url)));
            assertFalse(ChatWebActivity.internal(Uri.parse(url)));
        }
        assertFalse(ChatWebActivity.modeLink(Uri.parse("mobilecodex://mode/codex?command=delete")));
    }
    @Test public void imageRouteCannotSelectArbitraryPathsOrScripts() {
        assertTrue(ChatIconStore.imageName("01-idle.png"));
        for (String name : new String[]{"../auth.json", "01-idle.js", "folder/01-idle.png", "01-idle.png?token=secret", "evil.png"})
            assertFalse(ChatIconStore.imageName(name));
    }
}
