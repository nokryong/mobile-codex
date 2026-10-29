package dev.mobilecodex.app;

import android.content.Context;
import dev.mobilecodex.app.core.RpcClient;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import java.util.Set;
import static dev.mobilecodex.app.core.Json.*;

/** App-owned Node runner for invited Claude/Antigravity agents. No Electron, PC service, or external Termux. */
final class AgentBridge implements AutoCloseable {
    interface Host { void request(String method, JSONObject args, Engine.Reply reply); }
    interface Events { void event(String name, JSONObject data); }
    private final Context context;
    private final DevTools tools;
    private final File codexHome, aliases, root;
    private final Host host;
    private final Events events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Process process;
    private volatile RpcClient rpc;
    private volatile boolean closing;
    private final Set<String> setups = ConcurrentHashMap.newKeySet();
    AgentBridge(Context context, DevTools tools, File codexHome, File aliases, Host host, Events events) {
        this.context=context;this.tools=tools;this.codexHome=codexHome;this.aliases=aliases;this.host=host;this.events=events;
        // The on-disk runtime keeps its original "rooms" directory so installed CLIs and logins survive.
        root=new File(context.getFilesDir(),"rooms");
    }
    boolean running() { return process!=null && process.isAlive(); }
    boolean busy() { return closing || !setups.isEmpty(); }
    private synchronized void start() throws Exception {
        if(closing)throw new IOException("에이전트 연결을 종료하고 있습니다. 잠시 뒤 다시 시도해 주세요.");
        if(rpc!=null&&!rpc.isClosed()&&running())return;
        root.mkdirs();
        File code=new File(root,"runtime");code.mkdirs();
        for(String name:new String[]{"service.cjs","providers.cjs","provider-events.cjs","provider-runtime.py","terminal_io.py","mobile-mcp.py","command-supervisor.py"}) {
            try(InputStream in=context.getAssets().open("rooms/"+name);OutputStream out=new FileOutputStream(new File(code,name))) { byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count); }
        }
        for(String stale:new String[]{"room-core.cjs","chat-mention.cjs"})new File(code,stale).delete();
        ProcessBuilder builder=new ProcessBuilder("node").directory(root);
        tools.configure(builder,codexHome,aliases);
        builder.command(builder.environment().get("MC_NODE"),new File(code,"service.cjs").getAbsolutePath(),root.getAbsolutePath());
        Process launched=builder.start();process=launched;
        Thread stderr=new Thread(()->{try(InputStream in=launched.getErrorStream()){byte[] buffer=new byte[4096];while(in.read(buffer)!=-1){}}catch(IOException ignored){}},"agents-stderr");stderr.setDaemon(true);stderr.start();
        final RpcClient[] connection=new RpcClient[1];
        connection[0]=new RpcClient(launched.getInputStream(),launched.getOutputStream(),new RpcClient.Listener(){
            public void notification(String name,JSONObject data){
                if(name.equals("agents.setup")){if(data.optString("kind").equals("started"))setups.add(data.optString("provider"));if(data.optString("kind").equals("exit"))setups.remove(data.optString("provider"));}
                events.event(name,data);
            }
            public void request(Object id,String method,JSONObject args){
                host.request(method,args,(result,error)->{try{if(error==null)connection[0].respond(id,result);else connection[0].reject(id,error.getMessage());}catch(IOException ignored){}});
            }
            public void disconnected(Throwable error){
                if(process!=launched)return;
                setups.clear();
                host.request("host.disconnected",obj(),(r,e)->{});
                if(!closing)events.event("agents.error",obj("message","에이전트 연결이 종료되었습니다. 다시 보내면 새로 연결합니다."));
                if(launched.isAlive())launched.destroy();
            }
        });
        rpc=connection[0];rpc.start();
    }
    void handle(String action,JSONObject args,Engine.Reply reply){
        // Never queue a stop behind a slow status/install request.
        worker.execute(()->{
            try{
                start();
                boolean setup=action.equals("agents.provider.install")||action.equals("agents.provider.login");
                if(setup){if(!setups.isEmpty())throw new IOException("진행 중인 설치 또는 로그인을 먼저 마쳐 주세요.");setups.add(args.optString("provider"));}
                rpc.request(action,args).whenComplete((result,error)->{
                    if(setup&&error!=null)setups.remove(args.optString("provider"));
                    reply.complete(result,error);
                });
            }catch(Exception e){reply.complete(null,e);}
        });
    }
    /** Stops go straight to a live connection; they must not start the runner. */
    void stop(String runId){RpcClient current=rpc;if(current!=null&&!current.isClosed())current.request("agents.stop",obj("runId",runId));}
    void stopAll(){RpcClient current=rpc;if(current!=null&&!current.isClosed())current.request("agents.stopAll",obj());}
    @Override public synchronized void close(){
        if(closing)return;closing=true;
        RpcClient current=rpc;Process old=process;
        worker.execute(()->{
            try {
                // Keep the host pipe alive until leases and approvals are released.
                if(current!=null&&!current.isClosed())current.request("agents.shutdown",obj(),10).get(11,TimeUnit.SECONDS);
            }catch(Exception ignored){}finally{
                if(current!=null)current.close();
                if(old!=null)try{if(!old.waitFor(3,TimeUnit.SECONDS))old.destroy();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                rpc=null;process=null;setups.clear();closing=false;
                events.event("agents.lifecycle",obj());
            }
        });
    }
}
