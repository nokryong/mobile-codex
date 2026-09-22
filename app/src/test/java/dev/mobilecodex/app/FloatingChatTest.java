package dev.mobilecodex.app;
import android.app.Application;
import android.os.Looper;
import android.view.*;
import android.widget.EditText;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class FloatingChatTest {
    private PhoneUseService service; private Engine engine; private FloatingChat chat;
    private Object get(String field)throws Exception{Field f=FloatingChat.class.getDeclaredField(field);f.setAccessible(true);return f.get(chat);}
    private void expand()throws Exception{Method m=FloatingChat.class.getDeclaredMethod("setExpanded",boolean.class);m.setAccessible(true);m.invoke(chat,true);}
    private void drain()throws Exception{engine.io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Before public void setup()throws Exception{service=Robolectric.buildService(PhoneUseService.class).create().get();engine=new Engine(RuntimeEnvironment.getApplication());chat=new FloatingChat(service,engine);chat.show();drain();}
    @After public void cleanup(){VoiceInput.release(service,"floating-test");chat.close();engine.io.shutdownNow();service.onDestroy();}
    @Test public void expandedInputCanFocusAndCollapsingReleasesKeyboardFocus()throws Exception{
        WindowManager.LayoutParams layout=(WindowManager.LayoutParams)get("layout");assertNotEquals(0,layout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        expand();assertEquals(0,layout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,layout.softInputMode);
        chat.collapseForAction();assertNotEquals(0,layout.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }
    @Test public void draftsSurviveClosingAndStayWithTheirConversation()throws Exception{
        chat.event("state",obj("threadId","a","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();
        ((EditText)get("input")).setText("thread A draft");chat.close();chat.show();drain();
        chat.event("state",obj("threadId","a","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("thread A draft",((EditText)get("input")).getText().toString());
        chat.event("state",obj("threadId","b","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("",((EditText)get("input")).getText().toString());
    }
    @Test public void expandedDraftBlocksPhoneInspectionAndActionsUntilCollapsed()throws Exception{
        Field field=PhoneUseService.class.getDeclaredField("floatingChat");field.setAccessible(true);field.set(service,chat);
        Method guard=PhoneUseService.class.getDeclaredMethod("requireAutomationView");guard.setAccessible(true);
        expand();assertThrows(InvocationTargetException.class,()->guard.invoke(service));
        chat.collapseForAction();guard.invoke(service);
    }
    @Test public void voiceResultReturnsToItsOriginalFloatingDraftAndRestoresThePanel()throws Exception{
        chat.event("state",obj("threadId","a","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();expand();
        EditText input=(EditText)get("input");input.setText("draft A");
        VoiceInput.activate(service,"floating-test");chat.voiceChanged();assertEquals(View.GONE,((View)get("root")).getVisibility());
        chat.event("state",obj("threadId","b","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();input.setText("draft B");
        VoiceInput.complete(service,obj("origin","floating","receiptId","floating-test","scope","thread:a","original","draft A","start",7,"end",7)," voice","");chat.voiceChanged();
        assertEquals(View.VISIBLE,((View)get("root")).getVisibility());assertEquals("draft B",input.getText().toString());
        chat.event("state",obj("threadId","a","workspace",obj("key","p"),"messages",array()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("draft A voice",input.getText().toString());
        assertEquals(0,VoiceInput.pending(service,"floating").length());
    }
}
