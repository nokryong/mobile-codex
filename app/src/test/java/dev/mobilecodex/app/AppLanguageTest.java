package dev.mobilecodex.app;

import android.app.Application;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import dev.mobilecodex.app.core.Texts;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class, qualifiers="en")
public class AppLanguageTest {
    @After public void cleanup(){Texts.configure(true,java.util.Map.of());}
    @Test public void deviceDefaultAndExplicitChoiceAreAppliedAndPersisted()throws Exception{
        var context=RuntimeEnvironment.getApplication();AppLanguage.initialize(context);
        assertEquals("Settings",Texts.t("설정"));AppLanguage.set(context,"ko");assertEquals("설정",Texts.t("설정"));
        AppLanguage.initialize(context);assertEquals("ko",AppLanguage.choice(context));
        AppLanguage.set(context,"en");assertEquals("Settings",Texts.t("설정"));assertEquals("user content",Texts.t("user content"));
        assertThrows(IllegalArgumentException.class,()->AppLanguage.set(context,"unsupported"));
    }
}
