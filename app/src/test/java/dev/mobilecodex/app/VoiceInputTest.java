package dev.mobilecodex.app;

import android.app.*;
import android.content.*;
import android.speech.RecognizerIntent;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class VoiceInputTest {
    private Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();}
    @After public void cleanup(){VoiceInput.release(context,"request");VoiceInput.release(context,"other");}
    private JSONObject request(){return obj("origin","main","receiptId","request","scope","project/thread","original","기존 초안","start",0,"end",2);}
    @Test public void recognizerUsesDeviceLanguageAndOnlyReturnsRecognizedText(){
        Intent intent=VoiceInputActivity.recognitionIntent(Locale.KOREAN);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,intent.getAction());
        assertEquals("ko",intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        Intent result=new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList("", " 새 요청 ", "다른 결과")));
        assertEquals("새 요청",VoiceInputActivity.recognizedText(Activity.RESULT_OK,result));
        assertEquals("",VoiceInputActivity.recognizedText(Activity.RESULT_CANCELED,result));
        assertEquals("",VoiceInputActivity.recognizedText(Activity.RESULT_OK,null));
    }
    @Test public void receiptsPersistScopeAndCannotBeAcknowledgedByOtherComposer()throws Exception{
        VoiceInput.activate(context,"request");VoiceInput.complete(context,request(),"음성 요청","");
        assertFalse(VoiceInput.active());JSONObject result=VoiceInput.pending(context,"main").getJSONObject(0);
        assertEquals("project/thread",result.getString("scope"));assertEquals("음성 요청",result.getString("text"));assertEquals(0,VoiceInput.pending(context,"floating").length());
        VoiceInput.acknowledge(context,"floating","request");assertEquals(1,VoiceInput.pending(context,"main").length());
        VoiceInput.acknowledge(context,"main","request");assertEquals(0,VoiceInput.pending(context,"main").length());
    }
    @Test public void selectionIsReplacedOnlyWhenOriginalDraftIsUnchanged()throws Exception{
        JSONObject result=request().put("text","새로운");assertEquals("새로운 초안",VoiceInput.merge("기존 초안",result));
        assertEquals("다른 수정\n새로운",VoiceInput.merge("다른 수정",result));
        result.put("text","");assertEquals("다른 수정",VoiceInput.merge("다른 수정",result));
    }
    @Test public void activeRecognitionBlocksPhoneActionsAndDuplicateStart()throws Exception{
        VoiceInput.activate(context,"request");
        assertThrows(java.io.IOException.class,()->VoiceInput.start(context,"main",obj("scope","p")));
        PhoneUseService service=Robolectric.buildService(PhoneUseService.class).create().get();
        var guard=PhoneUseService.class.getDeclaredMethod("requireAutomationView");guard.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->guard.invoke(service));
        VoiceInput.release(context,"other");assertTrue(VoiceInput.active());
        VoiceInput.release(context,"request");guard.invoke(service);service.onDestroy();
    }
    @Test public void activityResultStoresReceiptBeforeFinishingAndRecreationDoesNotRelaunch()throws Exception{
        Intent intent=new Intent(context,VoiceInputActivity.class).putExtra("request",request().toString());
        var controller=Robolectric.buildActivity(VoiceInputActivity.class,intent).setup();
        var activity=controller.get();assertNotNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        controller.recreate();activity=controller.get();assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        Intent result=new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(List.of("받아쓰기")));
        activity.onActivityResult(81,Activity.RESULT_OK,result);
        assertTrue(activity.isFinishing());assertEquals("받아쓰기",VoiceInput.pending(context,"main").getJSONObject(0).getString("text"));
        controller.pause().stop().destroy();
    }
}
