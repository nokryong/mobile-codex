package dev.mobilecodex.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProContextBuilderTest {
    @Test public void sensitivePathsAreExcludedBeforeContextAssembly() {
        for (String path : new String[]{".git/config", ".codex/auth.json", ".env", "release.jks", "private.key", "cookies.json", "access-token.txt"})
            assertTrue(path, ProContextBuilder.sensitive(path));
        assertFalse(ProContextBuilder.sensitive("app/src/main/java/Main.java"));
    }

    @Test public void likelyCredentialAssignmentsAreRedactedWithoutDroppingNormalCode() {
        String result = ProContextBuilder.redactSecrets("name=mobile-codex\nAPI_KEY=secret\nAuthorization: Bearer opaque\nreturn value;");
        assertTrue(result.contains("name=mobile-codex"));
        assertTrue(result.contains("return value;"));
        assertFalse(result.contains("secret")); assertFalse(result.contains("Bearer opaque"));
    }
}
