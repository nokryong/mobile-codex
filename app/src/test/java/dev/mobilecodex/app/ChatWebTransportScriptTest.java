package dev.mobilecodex.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatWebTransportScriptTest {
    @Test public void conversationRequeryContainsNoLiteralLineBreakInsideJavascript() {
        String script = ChatWebTransport.requeryScript(
            "6ab46e59-8b48-83e8-beb2-04c61e1e5345", "테스트", 1790210700L);
        assertFalse(script.contains("\n"));
        assertTrue(script.contains(".join('\\n')"));
    }
}
