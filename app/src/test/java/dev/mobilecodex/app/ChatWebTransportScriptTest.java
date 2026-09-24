package dev.mobilecodex.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatWebTransportScriptTest {
    @Test public void conversationRequeryScopesResultToOperationAndObservedUser() {
        String script = ChatWebTransport.requeryScript(
            "6ab46e59-8b48-83e8-beb2-04c61e1e5345", "테스트", 1790210700L,
            "local-operation-1", "2ab46e59-8b48-83e8-beb2-04c61e1e5345", "// bundled matcher");
        assertTrue(script.contains("window.__mcChatQueries"));
        assertTrue(script.contains("local-operation-1"));
        assertTrue(script.contains("2ab46e59-8b48-83e8-beb2-04c61e1e5345"));
        assertTrue(script.contains("AbortController"));
        assertTrue(script.contains("MCChatRequery.match"));
        assertFalse(script.contains("__mcChatQueryResult"));
    }
}
