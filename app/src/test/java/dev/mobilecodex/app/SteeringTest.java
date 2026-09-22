package dev.mobilecodex.app;
import android.app.Application;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class SteeringTest {
    private Engine engine;
    private void set(String name,Object value)throws Exception{Field f=Engine.class.getDeclaredField(name);f.setAccessible(true);f.set(engine,value);}
    private JSONObject call(String action,JSONObject args)throws Exception{CompletableFuture<JSONObject> result=new CompletableFuture<>();engine.handle(action,args,(v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(v);});return result.get(5,TimeUnit.SECONDS);}
    @Before public void setup()throws Exception{engine=new Engine(RuntimeEnvironment.getApplication());set("active",obj("id","thread","messages",array()));set("threadId","thread");set("turnId","turn");set("busy",true);}
    @After public void cleanup(){engine.io.shutdownNow();}
    @Test public void steeringUsesActiveTurnAndRecordsOnlyAcceptedInput()throws Exception{
        engine.setTestTransport((method,args)->{assertEquals("turn/steer",method);assertEquals("turn",args.getString("expectedTurnId"));assertEquals("새 지시",args.getJSONArray("input").getJSONObject(0).getString("text"));return obj("turnId","turn");});
        call("chat.steer",obj("text","새 지시","expectedThreadId","thread","expectedTurnId","turn","workspaceKey",""));
        assertEquals(1,call("state",obj()).getJSONArray("messages").length());
        engine.setTestTransport((method,args)->{throw new java.io.IOException("rejected");});
        assertThrows(ExecutionException.class,()->call("chat.steer",obj("text","실패한 지시","expectedTurnId","turn")));
        assertEquals(1,call("state",obj()).getJSONArray("messages").length());
    }
    @Test public void staleTurnOrConversationCannotReceiveInstruction()throws Exception{
        engine.setTestTransport((method,args)->{fail("must not send");return obj();});
        assertThrows(ExecutionException.class,()->call("chat.steer",obj("text","wrong","expectedTurnId","old")));
        assertThrows(ExecutionException.class,()->call("chat.steer",obj("text","wrong","expectedTurnId","turn","expectedThreadId","other")));
        set("busy",false); assertThrows(ExecutionException.class,()->call("chat.steer",obj("text","late","expectedTurnId","turn")));
    }
    @Test public void overlaySubscriptionDoesNotReplaceActivityAndCanDetachIndependently()throws Exception{
        AtomicInteger activity=new AtomicInteger(),overlay=new AtomicInteger();
        Engine.Ui first=new Engine.Ui(){public void event(String n,JSONObject d){activity.incrementAndGet();}public void approval(Engine.Approval a){}};
        Engine.Ui second=new Engine.Ui(){public void event(String n,JSONObject d){overlay.incrementAndGet();}public void approval(Engine.Approval a){}};
        engine.attach(first);engine.observe(second);engine.io.submit(()->{}).get(5,TimeUnit.SECONDS);
        int original=activity.get();engine.phoneStateChanged();engine.io.submit(()->{}).get(5,TimeUnit.SECONDS);assertTrue(activity.get()>original);assertTrue(overlay.get()>0);
        engine.unobserve(second);int detached=overlay.get();engine.phoneStateChanged();engine.io.submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(detached,overlay.get());
    }
}
