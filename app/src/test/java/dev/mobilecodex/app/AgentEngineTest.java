package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class)
public class AgentEngineTest {
    Context context;
    Engine engine;
    final List<JSONObject> calls = Collections.synchronizedList(new ArrayList<>());
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(),"sessions.json").delete();
        context.getSharedPreferences("projects",0).edit().clear().commit();
        context.getSharedPreferences("workspace",0).edit().clear().commit();
        context.getSharedPreferences("settings",0).edit().clear().commit();
        engine=new Engine(context);engine.setTestTransport((m,p)->{throw new AssertionError("Unexpected RPC: "+m);});
    }
    @After public void teardown() throws Exception {
        engine.io.shutdownNow();Field f=Engine.class.getDeclaredField("approvalTimer");f.setAccessible(true);((ScheduledExecutorService)f.get(engine)).shutdownNow();
        new File(context.getFilesDir(),"sessions.json").delete();
    }
    JSONObject host(String method,JSONObject args)throws Exception{
        CompletableFuture<JSONObject> f=new CompletableFuture<>();Method m=Engine.class.getDeclaredMethod("roomHost",String.class,JSONObject.class,Engine.Reply.class);m.setAccessible(true);
        m.invoke(engine,"host."+method,args,(Engine.Reply)(r,e)->{if(e==null)f.complete(r);else f.completeExceptionally(e);});return f.get(5,TimeUnit.SECONDS);
    }
    JSONObject call(String action,JSONObject args)throws Exception{CompletableFuture<JSONObject> f=new CompletableFuture<>();engine.handle(action,args,(r,e)->{if(e==null)f.complete(r);else f.completeExceptionally(e);});return f.get(5,TimeUnit.SECONDS);}
    void fails(Callable<?> action)throws Exception{try{action.call();fail("Operation should fail closed");}catch(ExecutionException expected){assertNotNull(expected.getCause());}}
    void field(String name,Object value)throws Exception{Field f=Engine.class.getDeclaredField(name);f.setAccessible(true);f.set(engine,value);}
    @SuppressWarnings("unchecked") Map<String,JSONObject> leases()throws Exception{Field f=Engine.class.getDeclaredField("roomLeases");f.setAccessible(true);return (Map<String,JSONObject>)f.get(engine);}
    void notify(String method,JSONObject params)throws Exception{
        Method m=Engine.class.getDeclaredMethod("onNotification",String.class,JSONObject.class);m.setAccessible(true);
        engine.io.submit(()->{m.invoke(engine,method,params);return null;}).get(5,TimeUnit.SECONDS);
    }
    void codex(){
        engine.setTestTransport((method,params)->{
            calls.add(obj("method",method,"params",new JSONObject(params.toString())));
            return switch(method){
                case "thread/start"->obj("thread",obj("id","remote-1"));
                case "thread/resume"->obj();
                case "turn/start"->obj("turn",obj("id","turn-"+calls.size()));
                case "turn/interrupt","turn/steer"->obj();
                default->throw new AssertionError(method);
            };
        });
    }
    List<JSONObject> turns(){synchronized(calls){List<JSONObject> out=new ArrayList<>();for(JSONObject c:calls)if(c.optString("method").equals("turn/start"))out.add(c.optJSONObject("params"));return out;}}
    JSONObject waitFor(Callable<Boolean> done)throws Exception{for(int i=0;i<200;i++){JSONObject state=call("state",obj());if(done.call())return state;Thread.sleep(25);}fail("Condition was not reached");return null;}
    static JSONObject agentMessage(JSONObject state,String agent){JSONArray messages=state.optJSONArray("messages");for(int i=messages.length()-1;i>=0;i--)if(agent.equals(messages.optJSONObject(i).optString("agent")))return messages.optJSONObject(i);return null;}

    @Test public void chatAgentHasNoToolsAndCannotEscalateItsLease()throws Exception{
        leases().put("one",obj("runId","one","roomId","one","mode","chat","projectKey",""));
        assertEquals(0,host("tools",obj("runId","one","roomId","one")).getJSONArray("tools").length());
        fails(()->host("tool",obj("runId","one","roomId","one","mode","write","projectKey","","name","mobile_create","arguments",obj("path","bad.txt","content","bad"))));
        fails(()->host("tool",obj("runId","one","roomId","one","name","mobile_read","arguments",obj("path","secret"))));
        leases().remove("one");
        fails(()->host("tools",obj("runId","one","roomId","one")));
    }
    @Test public void answeringAgentExcludesTerminalPermissionsAndUpdates()throws Exception{
        leases().put("one",obj("runId","one","roomId","one","mode","chat","projectKey",""));
        fails(()->call("terminal.run",obj("command","echo bad")));
        fails(()->call("permissions.set",obj("mode","danger-full-access")));
        assertFalse(engine.canInstallUpdate());
        leases().remove("one");assertTrue(engine.canInstallUpdate());
    }
    @Test public void removedRoomGithubAndProActionsAreRejected()throws Exception{
        for(String action:List.of("rooms.list","rooms.create","rooms.send","sync.status","sync.login.start","sync.apply","chat.pro.prepare","chat.pro.complete"))
            fails(()->call(action,obj()));
        fails(()->call("chat.send",obj("text","hello","consultPro",true)));
        assertFalse(dev.mobilecodex.app.core.ToolCatalog.all().toString().contains("mobile_consult_pro"));
    }
    @Test public void readOnlyCatalogExcludesMutationsAndCommand()throws Exception{
        Method m=Engine.class.getDeclaredMethod("roomTools",String.class,boolean.class);m.setAccessible(true);
        JSONArray tools=(JSONArray)m.invoke(engine,"read",true);
        Set<String> names=new HashSet<>();for(int i=0;i<tools.length();i++)names.add(tools.getJSONObject(i).getString("name"));
        assertTrue(names.contains("mobile_read"));assertTrue(names.contains("mobile_phone_screen"));assertFalse(names.contains("mobile_create"));assertFalse(names.contains("mobile_exec"));assertFalse(names.contains("mobile_phone_action"));
    }
    @Test public void agentApprovalWithoutTheAnsweringRunIsRefused()throws Exception{
        assertFalse(host("approve",obj("runId","stale","memberId","claude","title","mobile_create","detail","{}")).getBoolean("allow"));
    }
    @Test public void packagedAgentAssetsAccessibleButRuntimeScriptsNotWebExposed(){
        var assets=context.getAssets();
        assertEquals(200,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/agents-ui.js")).getStatusCode());
        assertEquals(200,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/agents.css")).getStatusCode());
        assertEquals(403,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/rooms-ui.js")).getStatusCode());
        assertEquals(403,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/rooms/provider-runtime.py")).getStatusCode());
    }
    @Test public void inviteValidatesAgentAndPermission()throws Exception{
        fails(()->call("chat.agents.set",obj("agents",array(obj("id","codex")))));
        fails(()->call("chat.agents.set",obj("agents",array(obj("id","claude","mode","allow-all")))));
        fails(()->call("chat.agents.set",obj("agents",new JSONArray())));
        assertEquals(0,call("state",obj()).getJSONArray("agents").length());
    }
    @Test public void draftInviteDispatchesConcurrentlyWithSharedContext()throws Exception{
        field("account",obj("type","chatgpt"));codex();
        fakeAgents();
        JSONObject draft=call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","effort","high","mode","chat"))));
        assertEquals("claude",draft.getJSONArray("agents").getJSONObject(0).getString("id"));
        call("chat.send",obj("text","안녕하세요","model","gpt-test","effort","medium"));JSONObject sent=call("state",obj());
        JSONObject turn=turns().get(0);
        JSONArray input=turn.getJSONArray("input");
        assertTrue(input.getJSONObject(0).getString("text").startsWith("[그룹 대화]"));
        assertTrue(input.getJSONObject(0).getString("text").contains("@claude (Claude)"));
        assertEquals("안녕하세요",input.getJSONObject(1).getString("text"));
        assertEquals("안녕하세요",sent.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertTrue(sent.getBoolean("busy"));
        assertEquals("codex",sent.getJSONObject("agentRun").getString("current"));
        assertEquals(0,sent.getJSONObject("agentRun").getJSONArray("queue").length());
        assertEquals(2,sent.getJSONObject("agentRun").getJSONArray("active").length());
        assertEquals(1,launches().size());
        assertEquals(1,sent.getJSONArray("agents").length());

        // A provider failure leaves Codex running and finishes visibly.
        notify("item/completed",obj("threadId","remote-1","turnId",turn.optString("id"),"item",obj("type","agentMessage","id","codex-reply","text","제 의견입니다.")));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","completed")));
        agentEvent("claude", "error", obj("message", "CLI unavailable"));
        JSONObject[] last=new JSONObject[1];
        JSONObject done=waitFor(()->{JSONObject s=call("state",obj());JSONObject m=agentMessage(s,"claude");last[0]=s;return m!=null&&"error".equals(m.optString("status"))&&!s.getBoolean("busy");});
        JSONObject claude=agentMessage(done,"claude");
        assertEquals("Claude",claude.getString("agentName"));
        assertFalse(claude.optString("error").isBlank());
        assertTrue(done.isNull("agentRun"));
        assertTrue(leases().isEmpty());
    }
    @Test public void mentionOnlyAgentSkipsCodexWhichReadsItOnItsNextTurn()throws Exception{
        field("account",obj("type","chatgpt"));codex();
        call("chat.send",obj("text","시작","model","gpt-test"));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","completed")));
        call("chat.agents.set",obj("agents",array(obj("id","gemini","model","g","mode","chat"))));
        int before=turns().size();
        call("chat.send",obj("text","@제미나이야 너만 답해줘","model","gpt-test"));
        assertEquals(before,turns().size());
        waitFor(()->{JSONObject m=agentMessage(call("state",obj()),"gemini");return m!=null&&!"streaming".equals(m.optString("status"))&&!call("state",obj()).getBoolean("busy");});
        call("chat.send",obj("text","이제 모두","model","gpt-test"));
        JSONObject next=turns().get(turns().size()-1);
        String context=next.getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(context.contains("[사용자] @제미나이야 너만 답해줘"));
        assertFalse(context.contains("[사용자] 시작"));
        assertEquals("이제 모두",next.getJSONArray("input").getJSONObject(1).getString("text"));
    }
    @Test public void stopEndsTheAnsweringOrderAndRemovedAgentLeavesTheGroup()throws Exception{
        fakeAgents();
        field("account",obj("type","chatgpt"));codex();
        call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","mode","chat"),obj("id","gemini","model","g","mode","chat"))));
        call("chat.send",obj("text","의견 주세요","model","gpt-test"));JSONObject sent=call("state",obj());
        assertEquals(3,sent.getJSONObject("agentRun").getJSONArray("active").length());
        call("chat.stop",obj());
        JSONObject stopped=call("state",obj());
        assertTrue(stopped.isNull("agentRun"));
        assertEquals(2,agentCalls.stream().filter(c->c.optString("action").equals("agents.stop")).count());
        assertTrue(calls.stream().anyMatch(c->c.optString("method").equals("turn/interrupt")));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","interrupted")));
        assertNull(agentMessage(call("state",obj()),"claude"));

        call("chat.agents.set",obj("agents",array(obj("id","claude","enabled",false),obj("id","gemini","enabled",false))));
        call("chat.send",obj("text","혼자","model","gpt-test"));JSONObject alone=call("state",obj());
        JSONArray input=turns().get(turns().size()-1).getJSONArray("input");
        assertEquals("혼자",input.getJSONObject(0).getString("text"));
        assertTrue(alone.isNull("agentRun"));
        assertFalse(alone.getJSONArray("agents").getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void retiredProBridgeIsDisabledOnlyWhenAnOldConfigDeclaresIt()throws Exception{
        // Codex 0.162 refuses to start on `-c mcp_servers.X.enabled=false` when X has no transport.
        File config=File.createTempFile("codex-config",".toml");
        try{
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"cli_auth_credentials_store = \"file\"\napproval_policy = \"on-request\"\n");
            assertFalse(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"model = \"x\"\n[mcp_servers.mobile_codex_pro]\ncommand = \"python3\"\n");
            assertTrue(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"mcp_servers.\"mobile_codex_pro\".command = \"python3\"\n");
            assertTrue(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"# mobile_codex_pro was removed\n");
            assertFalse(Engine.retiredProBridgeConfigured(config));
        }finally{config.delete();}
        assertFalse(Engine.retiredProBridgeConfigured(new File(context.getFilesDir(),"missing.toml")));
    }
    @Test public void agentCharacterPackIsKeptPerAgentAndCanBeCleared()throws Exception{
        JSONObject invited=call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","mode","chat","character","gptchan"))));
        assertEquals("gptchan",invited.getJSONArray("agents").getJSONObject(0).getString("character"));
        JSONObject gemini=call("chat.agents.set",obj("agents",array(obj("id","gemini","model","g","mode","chat"))));
        assertEquals("",gemini.getJSONArray("agents").getJSONObject(1).getString("character"));
        JSONObject changed=call("chat.agents.set",obj("agents",array(obj("id","claude","character","astrachan"))));
        assertEquals("astrachan",changed.getJSONArray("agents").getJSONObject(0).getString("character"));
        assertEquals("opus",changed.getJSONArray("agents").getJSONObject(0).getString("model"));
        JSONObject cleared=call("chat.agents.set",obj("agents",array(obj("id","claude","character",""))));
        assertEquals("",cleared.getJSONArray("agents").getJSONObject(0).getString("character"));
        JSONObject oversized=call("chat.agents.set",obj("agents",array(obj("id","claude","character","x".repeat(121)))));
        assertEquals("",oversized.getJSONArray("agents").getJSONObject(0).getString("character"));
    }

    final List<JSONObject> agentCalls = Collections.synchronizedList(new ArrayList<>());
    void fakeAgents() {
        engine.setTestAgentTransport((action,args,reply)->{
            agentCalls.add(obj("action",action,"args",args));
            reply.complete(obj("ok",true),null);
        });
    }
    List<JSONObject> launches() {
        synchronized(agentCalls){return agentCalls.stream().filter(c->c.optString("action").equals("agents.run")).map(c->c.optJSONObject("args")).toList();}
    }
    JSONObject launch(String id) {
        List<JSONObject> list=launches();
        for(int i=list.size()-1;i>=0;i--)if(id.equals(list.get(i).optString("memberId")))return list.get(i);
        throw new AssertionError("No launch for "+id);
    }
    void event(JSONObject data)throws Exception{
        Method m=Engine.class.getDeclaredMethod("agentRunEvent",JSONObject.class);m.setAccessible(true);
        engine.io.submit(()->{m.invoke(engine,data);return null;}).get(5,TimeUnit.SECONDS);
    }
    void agentEvent(String id,String kind,JSONObject data)throws Exception{
        data.put("runId",launch(id).getString("runId")).put("kind",kind);event(data);
    }
    void beginGroup(String text)throws Exception{
        field("account",obj("type","chatgpt"));codex();fakeAgents();
        call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus"),obj("id","gemini","model","g"))));
        call("chat.send",obj("text",text,"model","gpt-test"));
    }
    void completeCodex(String status)throws Exception{
        Field f=Engine.class.getDeclaredField("runningTurns");f.setAccessible(true);
        String id=((Map<String,String>)f.get(engine)).values().iterator().next();
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id",id,"status",status)));
    }
    @Test public void steeringStopsOtherParticipantsAndPendingFollowUps()throws Exception{
        beginGroup("질문");
        agentEvent("gemini","final",obj("text","참고할 의견 @codex 이어서 검토","sessionId","g"));
        JSONObject state=call("state",obj());
        assertEquals("codex",state.getJSONObject("agentRun").getJSONArray("queue").getString(0));
        call("chat.steer",obj("text","방향 변경","expectedThreadId",state.getString("threadId"),"expectedTurnId",state.getString("turnId"),"workspaceKey",""));
        assertTrue(call("state",obj()).isNull("agentRun"));
        assertTrue(leases().isEmpty());
        assertEquals(1,agentCalls.stream().filter(c->c.optString("action").equals("agents.stop")).count());
        assertFalse(calls.stream().anyMatch(c->c.optString("method").equals("turn/interrupt")));
        JSONObject steering=calls.stream().filter(c->c.optString("method").equals("turn/steer")).findFirst().get().getJSONObject("params");
        assertTrue(steering.getJSONArray("input").getJSONObject(0).getString("text").contains("참고할 의견"));
        assertFalse(steering.getJSONArray("input").getJSONObject(0).getString("text").contains("[사용자] 질문"));
        completeCodex("completed");
        assertEquals(2,launches().size());
    }
    @Test public void inputCursorNativeSessionAndPolicySurviveAppRestart()throws Exception{
        beginGroup("@claude 첫 질문");
        agentEvent("claude","final",obj("text","첫 답변","sessionId","native-persisted"));
        call("chat.agents.set",obj("agents",array(obj("id","claude","approvalMode","auto-review"))));
        String local=call("state",obj()).getString("threadId");
        engine.io.shutdownNow();Field timer=Engine.class.getDeclaredField("approvalTimer");timer.setAccessible(true);
        ((ScheduledExecutorService)timer.get(engine)).shutdownNow();
        engine=new Engine(context);field("account",obj("type","chatgpt"));codex();fakeAgents();
        call("chat.resume",obj("id",local));
        assertEquals("auto-review",call("state",obj()).getJSONArray("agents").getJSONObject(0).getString("approvalMode"));
        call("chat.send",obj("text","@claude 둘째 질문","model","gpt-test"));
        assertEquals("native-persisted",launch("claude").getString("nativeSession"));
        assertFalse(launch("claude").getString("prompt").contains("첫 질문"));
        assertFalse(launch("claude").getString("prompt").contains("첫 답변"));
        assertTrue(launch("claude").getString("prompt").contains("둘째 질문"));
        call("chat.stop",obj());
    }
    @Test public void streamsAppearInArrivalOrderAndNextRoundIncludesAllPeers()throws Exception{
        beginGroup("첫 질문");
        assertEquals(2,launches().size());
        assertEquals(1,call("state",obj()).getJSONArray("messages").length());
        assertTrue(launch("claude").getString("prompt").contains("첫 질문"));
        assertTrue(launch("gemini").getString("prompt").contains("첫 질문"));
        assertTrue(launch("claude").getString("prompt").contains("[[icon:태그명]]"));
        agentEvent("gemini","delta",obj("text","Gemini 먼저"));
        notify("item/completed",obj("threadId","remote-1","item",obj("type","agentMessage","id","c1","text","Codex 의견")));
        agentEvent("claude","delta",obj("text","Claude 나중"));
        agentEvent("claude","final",obj("text","Claude 나중","sessionId","claude-native"));
        completeCodex("completed");
        agentEvent("gemini","final",obj("text","Gemini 먼저","sessionId","gemini-native"));
        JSONObject done=call("state",obj());JSONArray messages=done.getJSONArray("messages");
        assertEquals("gemini",messages.getJSONObject(1).getString("agent"));
        assertEquals("c1",messages.getJSONObject(2).getString("id"));
        assertEquals("claude",messages.getJSONObject(3).getString("agent"));
        assertFalse(done.getBoolean("busy"));assertTrue(leases().isEmpty());
        call("chat.send",obj("text","둘째 질문","model","gpt-test"));
        String claude=launch("claude").getString("prompt"),gemini=launch("gemini").getString("prompt");
        assertTrue(claude.contains("Gemini 먼저"));assertTrue(claude.contains("Codex 의견"));assertFalse(claude.contains("Claude 나중"));
        assertTrue(gemini.contains("Claude 나중"));assertTrue(gemini.contains("Codex 의견"));assertFalse(gemini.contains("Gemini 먼저"));
        assertFalse(claude.contains("첫 질문"));assertTrue(claude.contains("둘째 질문"));
        assertEquals("claude-native",launch("claude").getString("nativeSession"));
        String codex=turns().get(1).getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(codex.contains("Claude 나중"));assertTrue(codex.contains("Gemini 먼저"));assertFalse(codex.contains("Codex 의견"));
        call("chat.stop",obj());
    }
    @Test public void invitedAgentsDoNotReceiveIconGuidanceWhenIconsAreDisabled()throws Exception{
        call("chat.icons.set",obj("enabled",false));
        beginGroup("첫 질문");
        assertFalse(launch("claude").getString("prompt").contains("[[icon:태그명]]"));
        assertFalse(launch("gemini").getString("prompt").contains("[[icon:태그명]]"));
        call("chat.stop",obj());
    }
    @Test public void lastClaudeReplyCanRecallCodexAfterEveryoneHasAnswered()throws Exception{
        beginGroup("서로 불러서 검토해 주세요");
        notify("item/completed",obj("threadId","remote-1","item",obj("type","agentMessage","id","initial-codex","text","Codex 첫 의견")));
        completeCodex("completed");
        agentEvent("gemini","final",obj("text","Gemini 검토 결과","sessionId","g-native"));
        agentEvent("claude","final",obj("text","Claude 검토 결과. @codex 확인해 주세요","sessionId","c-native"));
        assertEquals("A completed initial reply must not suppress a later explicit handoff",2,turns().size());
        String prompt=turns().get(1).getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(prompt.contains("Claude 검토 결과"));assertTrue(prompt.contains("Gemini 검토 결과"));
        assertFalse(prompt.contains("Codex 첫 의견"));
        assertEquals("codex",call("state",obj()).getJSONObject("agentRun").getJSONArray("active").getString(0));
        completeCodex("completed");
        assertFalse(call("state",obj()).getBoolean("busy"));assertTrue(leases().isEmpty());
    }
    @Test public void recalledCodexWaitsForCurrentTurnAndCoalescesRequests()throws Exception{
        beginGroup("모두 검토해 주세요");
        agentEvent("claude","final",obj("text","Claude 의견 @codex @codex 확인","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","Gemini 의견 @codex 확인","sessionId","g-native"));
        assertEquals(1,turns().size());
        assertEquals(1,call("state",obj()).getJSONObject("agentRun").getJSONArray("queue").length());
        completeCodex("completed");
        assertEquals(2,turns().size());
        String prompt=turns().get(1).getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(prompt.contains("Claude 의견"));assertTrue(prompt.contains("Gemini 의견"));
        completeCodex("completed");
        assertEquals(2,turns().size());assertFalse(call("state",obj()).getBoolean("busy"));
    }
    @Test public void followUpsWaitForBatchAndAllowRepliesToEarlierParticipants()throws Exception{
        beginGroup("@claude @gemini 의견");
        assertEquals(0,turns().size());
        agentEvent("claude","final",obj("text","첫 의견 @codex 확인 @claude 자기호출","sessionId","c-native"));
        assertEquals(0,turns().size());
        agentEvent("gemini","final",obj("text","둘째 의견 @codex 다시 호출","sessionId","g-native"));
        assertEquals(1,turns().size());
        String prompt=turns().get(0).getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(prompt.contains("첫 의견"));assertTrue(prompt.contains("둘째 의견"));
        notify("item/completed",obj("threadId","remote-1","item",obj("type","agentMessage","id","chain","text","@claude @gemini 다시")));
        completeCodex("completed");
        assertEquals(4,launches().size());
        assertEquals("c-native",launch("claude").getString("nativeSession"));
        assertTrue(launch("claude").getString("prompt").contains("@claude @gemini 다시"));
        assertTrue(launch("claude").getString("prompt").contains("둘째 의견"));
        assertFalse(launch("claude").getString("prompt").contains("첫 의견"));
        agentEvent("claude","final",obj("text","@gemini 더 검토","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","@claude 더 검토","sessionId","g-native"));
        assertEquals(6,launches().size());assertEquals(1,turns().size());
        agentEvent("claude","final",obj("text","검토 완료","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","검토 완료","sessionId","g-native"));
        assertFalse(call("state",obj()).getBoolean("busy"));assertTrue(leases().isEmpty());
    }
    @Test public void newRepliesCanRepeatHandoffsButCompletedEventsCannot()throws Exception{
        beginGroup("모두 검토해 주세요");
        notify("item/completed",obj("threadId","remote-1","item",obj("type","agentMessage","id","calls","text","@claude @gemini 검토")));
        completeCodex("completed");
        agentEvent("claude","final",obj("text","@gemini 검토","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","@claude 검토","sessionId","g-native"));
        assertEquals(4,launches().size());
        String previous=launch("claude").getString("runId");
        agentEvent("claude","final",obj("text","@gemini 다시 검토","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","@claude 다시 검토","sessionId","g-native"));
        assertEquals(6,launches().size());
        event(obj("runId",previous,"kind","final","text","@codex @gemini 중복 이벤트","sessionId","c-native"));
        assertEquals(0,call("state",obj()).getJSONObject("agentRun").getJSONArray("queue").length());
        agentEvent("claude","final",obj("text","완료","sessionId","c-native"));
        agentEvent("gemini","final",obj("text","완료","sessionId","g-native"));
        assertEquals(6,launches().size());assertEquals(1,turns().size());
        assertFalse(call("state",obj()).getBoolean("busy"));
    }
    @Test public void collaborationContinuesBeyondFormerDepthAndTurnCaps()throws Exception{
        beginGroup("@claude 검토 시작");
        for(int round=0;round<5;round++){
            agentEvent("claude","final",obj("text","Claude 결과 "+round+" @gemini @gemini 검토","sessionId","c-native"));
            assertTrue(launch("gemini").getString("prompt").contains("Claude 결과 "+round));
            agentEvent("gemini","final",obj("text","Gemini 결과 "+round+" @codex 검토","sessionId","g-native"));
            assertEquals(round+1,turns().size());
            assertTrue(turns().get(round).getJSONArray("input").getJSONObject(0).getString("text").contains("Gemini 결과 "+round));
            notify("item/completed",obj("threadId","remote-1","item",obj("type","agentMessage","id","round-"+round,"text","Codex 결과 "+round+" @claude 재검토")));
            completeCodex("completed");
            assertEquals(3*(round+1)+1,turns().size()+launches().size());
            assertTrue(launch("claude").getString("prompt").contains("Codex 결과 "+round));
            assertEquals("c-native",launch("claude").getString("nativeSession"));
        }
        agentEvent("claude","final",obj("text","검토 완료","sessionId","c-native"));
        assertEquals(16,turns().size()+launches().size());
        assertFalse(call("state",obj()).getBoolean("busy"));assertTrue(leases().isEmpty());
    }
    @Test public void providerFailureAndLateCancelledEventsCannotStopOrChangePeers()throws Exception{
        beginGroup("질문");
        String stale=launch("gemini").getString("runId");
        agentEvent("claude","error",obj("message","provider unavailable"));
        assertEquals(2,call("state",obj()).getJSONObject("agentRun").getJSONArray("active").length());
        completeCodex("failed");
        assertTrue(call("state",obj()).getBoolean("busy"));
        agentEvent("gemini","delta",obj("text","살아있는 답"));
        call("chat.stop",obj());
        event(obj("runId",stale,"kind","final","text","늦은 응답","sessionId","late"));
        JSONObject msg=agentMessage(call("state",obj()),"gemini");
        assertEquals("살아있는 답",msg.getString("text"));assertEquals("interrupted",msg.getString("status"));
        assertTrue(leases().isEmpty());
    }
    CompletableFuture<JSONObject> requestHost(String action,JSONObject args)throws Exception{
        CompletableFuture<JSONObject> future=new CompletableFuture<>();
        Method m=Engine.class.getDeclaredMethod("roomHost",String.class,JSONObject.class,Engine.Reply.class);m.setAccessible(true);
        m.invoke(engine,"host."+action,args,(Engine.Reply)(r,e)->{if(e==null)future.complete(r);else future.completeExceptionally(e);});
        call("state",obj());return future;
    }
    JSONObject consent(String id)throws Exception{
        JSONObject launch=launch(id);
        return obj("runId",launch.getString("runId"),"sessionId",launch.getString("sessionId"),"memberId",id,"title","mobile_create","detail","{\"path\":\"x.txt\",\"content\":\"one\"}");
    }
    void writeLease(String id,String policy)throws Exception{
        // Isolate the host gate using an issued live turn; no external CLI is required.
        JSONObject lease=leases().get(launch(id).getString("runId"));lease.put("mode","write").put("approvalMode",policy);
    }
    @Test public void allAllowedStillSerializesProjectWritersAndRevokesQueuedWorkOnStop()throws Exception{
        beginGroup("작업");writeLease("claude","allow-all");writeLease("gemini","allow-all");
        CompletableFuture<JSONObject> c=requestHost("approve",consent("claude")),g=requestHost("approve",consent("gemini"));
        assertFalse(c.isDone());assertFalse(g.isDone()); // Codex owns the project first.
        completeCodex("completed");
        assertTrue(c.get(2,TimeUnit.SECONDS).getBoolean("allow"));assertFalse(g.isDone());
        JSONObject lease=leases().get(launch("claude").getString("runId"));
        JSONObject wrong=obj("runId",lease.getString("runId"),"roomId",lease.getString("roomId"),"mode","write",
            "name","mobile_create","arguments",obj("path","different.txt","content","wrong"));
        fails(()->host("tool",wrong)); // An approved call cannot be changed.
        call("chat.stop",obj());assertFalse(g.get(2,TimeUnit.SECONDS).getBoolean("allow"));
        assertFalse(host("approve",consent("claude")).getBoolean("allow"));
    }
    @Test public void writerSlotTransfersAfterCompletionAndReadOnlyOverridesAllAllowed()throws Exception{
        beginGroup("@claude @gemini 작업");writeLease("claude","allow-all");writeLease("gemini","allow-all");
        assertTrue(host("approve",consent("claude")).getBoolean("allow"));
        CompletableFuture<JSONObject> pending=requestHost("approve",consent("gemini"));assertFalse(pending.isDone());
        agentEvent("claude","final",obj("text","끝","sessionId","c"));
        assertTrue(pending.get(2,TimeUnit.SECONDS).getBoolean("allow"));
        field("permissionMode","read-only");
        assertFalse(host("approve",consent("gemini")).getBoolean("allow"));
        call("chat.stop",obj());
    }
    @Test public void automaticPoliciesGrantOnlyTheExactCallOnce()throws Exception{
        for(String policy:List.of("allow-all","auto-review")){
            beginGroup("@claude 작업");writeLease("claude",policy);
            String name="agent-"+UUID.randomUUID()+".txt";
            JSONObject args=obj("path",name,"content","approved contents");
            JSONObject approval=consent("claude").put("detail",args.toString(2));
            JSONObject request=obj("runId",launch("claude").getString("runId"),"roomId",launch("claude").getString("sessionId"),
                "mode","write","projectKey","","name","mobile_create","arguments",args);
            File output=new File(new File(context.getFilesDir(),"workspace"),name);
            try{
                assertTrue(host("approve",approval).getBoolean("allow"));
                host("tool",request);
                assertEquals("approved contents",new String(java.nio.file.Files.readAllBytes(output.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                fails(()->host("tool",request));
                assertEquals("approved contents",new String(java.nio.file.Files.readAllBytes(output.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            }finally{output.delete();call("chat.stop",obj());}
        }
    }
    @Test public void manualApprovalIsDisplayedAndCanBeDeniedAndUnsupportedModesAreRejected()throws Exception{
        beginGroup("@claude @gemini 작업");writeLease("claude","ask");writeLease("gemini","auto-review");
        final List<Engine.Approval> prompts=new ArrayList<>();
        Engine.Ui observer=new Engine.Ui(){
            public void event(String name,JSONObject data){}
            public void approval(Engine.Approval approval){prompts.add(approval);}
        };
        engine.attach(observer);
        CompletableFuture<JSONObject> manual=requestHost("approve",consent("claude"));
        assertEquals(1,prompts.size());assertFalse(manual.isDone());
        prompts.get(0).decision.complete(false);
        assertFalse(manual.get(2,TimeUnit.SECONDS).getBoolean("allow"));
        // AGY cannot choose auto-review through the public settings boundary.
        call("chat.stop",obj());
        fails(()->call("chat.agents.set",obj("agents",array(obj("id","gemini","approvalMode","auto-review")))));
        JSONObject result=call("chat.agents.set",obj("agents",array(obj("id","claude","approvalMode","auto-review"))));
        assertEquals("auto-review",result.getJSONArray("agents").getJSONObject(0).getString("approvalMode"));
    }
}
