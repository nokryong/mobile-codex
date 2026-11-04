'use strict';
// Runs invited Claude/Antigravity turns for the Engine. The Engine owns the
// conversation, its order and its history; this process only drives the CLIs.
const {CliProvider,lines}=require('./providers.cjs');
const pending=new Map();let seq=0,closed=false;
const write=value=>{if(!closed)process.stdout.write(JSON.stringify(value)+'\n');};
const emit=(method,params)=>write({method,params});
const host=(method,params)=>new Promise((resolve,reject)=>{
  if(closed){reject(new Error('Android 연결이 종료되었습니다.'));return;}
  const id='host-'+(++seq);const timer=setTimeout(()=>{pending.delete(id);reject(new Error('Android 연결 응답 시간이 초과됐습니다.'));},method==='approve'?600000:240000);
  pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v);},reject:e=>{clearTimeout(timer);reject(e);}});
  write({id,method:'host.'+method,params});
});
const root=process.argv[2];
const providers={claude:new CliProvider('claude',{root,host,emit}),agy:new CliProvider('agy',{root,host,emit})};
const runs=new Map();
let providerChecks=Promise.resolve();

function start(p) {
  const provider=providers[p.provider];if(!provider)throw new Error('지원하지 않는 에이전트입니다.');
  const runId=String(p.runId||'');if(!/^[A-Za-z0-9-]{1,64}$/.test(runId)||runs.has(runId))throw new Error('에이전트 실행 ID가 올바르지 않습니다.');
  if(!/^[A-Za-z0-9_-]{1,80}$/.test(String(p.sessionId||'')))throw new Error('대화 ID가 올바르지 않습니다.');
  const prompt=String(p.prompt||'');if(!prompt||prompt.length>200000)throw new Error('에이전트에게 보낼 문맥이 비어 있거나 너무 깁니다.');
  // The CLI provider calls this pair a room and a member; here the room is one chat.
  const room={id:p.sessionId,runId,mode:p.mode||'chat',projectKey:p.projectKey||''};
  const member={id:p.memberId,model:String(p.model||''),effort:String(p.effort||''),approvalMode:p.approvalMode||'ask',session:{id:String(p.nativeSession||'')}};
  const controller=new AbortController(),signal=controller.signal;let text='';
  const send=(kind,data={})=>emit('agents.event',{runId,kind,...data});
  runs.set(runId,controller);
  (async()=>{
    await provider.validate(member);
    if(signal.aborted)throw new Error('발언이 중지되었습니다.');
    const result=await provider.run({room,member,prompt,signal,
      approve:async(title,detail)=>{if(signal.aborted)return false;const r=await host('approve',{runId,sessionId:room.id,memberId:member.id,title,detail});return r.allow===true;},
      event:e=>{if(signal.aborted)return;
        if(e.kind==='session')send('session',{id:e.id});
        if(e.kind==='delta'){const delta=String(e.text||'');if(text.length+delta.length>1000000){controller.abort();return;}text+=delta;send('delta',{text:delta});}
        if(e.kind==='status')send('status',{status:e.status||'working'});}});
    if(signal.aborted)throw new Error('발언이 중지되었습니다.');
    const final=String(result.text||text);
    if(final.length>1000000)throw new Error('응답이 1,000,000자 제한을 초과했습니다.');
    if(!result.sessionId)throw new Error('공급자가 세션 ID를 반환하지 않았습니다.');
    send('final',{text:final,sessionId:result.sessionId});
  })().catch(error=>send('error',{message:String(error?.message||error).slice(0,1000),interrupted:signal.aborted}))
    .finally(()=>runs.delete(runId));
  return {ok:true};
}
function stop(runId){const controller=runs.get(String(runId||''));controller?.abort();return {ok:true};}

async function action(method,p={}) {
  if(shuttingDown)throw new Error('에이전트 연결을 종료하고 있습니다.');
  const provider=()=>{if(!providers[p.provider])throw new Error('에이전트를 선택해 주세요.');return providers[p.provider];};
  switch(method) {
    case 'agents.providers':return {providers:Object.values(providers).map(p=>p.cap)};
    case 'agents.providers.check': {
      // One at a time: PRoot CLIs started together slow each other past their time limits.
      const selected=p.provider?[provider()]:Object.values(providers);
      const task=providerChecks.then(async()=>{
        const checked=[];for(const item of selected)checked.push(await item.check({force:p.force===true}));
        return {providers:checked};
      });
      providerChecks=task.catch(()=>{});return task;
    }
    case 'agents.run':return start(p);
    case 'agents.stop':return stop(p.runId);
    case 'agents.stopAll':for(const controller of runs.values())controller.abort();return {ok:true};
    case 'agents.shutdown':await shutdown();setTimeout(()=>process.exit(),20);return {ok:true};
    case 'agents.provider.install':return provider().setupStart('install');
    case 'agents.provider.login':return provider().setupStart('login');
    case 'agents.provider.input':return provider().setupInput(p.text);
    case 'agents.provider.stop':return provider().setupStop();
    default:throw new Error('지원하지 않는 에이전트 요청입니다.');
  }
}
lines(process.stdin,async message=>{
  if(!message.method){const callback=pending.get(message.id);if(callback){pending.delete(message.id);message.error?callback.reject(new Error(message.error.message)):callback.resolve(message.result||{});}return;}
  try {const result=await action(message.method,message.params);if(message.id!=null)write({id:message.id,result});}
  catch(error){if(message.id!=null)write({id:message.id,error:{code:-32000,message:error.message}});else emit('agents.error',{message:error.message});}
},error=>{emit('agents.error',{message:error.message});detach();});
let shuttingDown=false,shutdownTask;
function shutdown(detached=false){
  if(detached){closed=true;for(const callback of pending.values())callback.reject(new Error('Android 연결이 종료되었습니다.'));pending.clear();}
  if(shutdownTask)return shutdownTask;
  shuttingDown=true;for(const controller of runs.values())controller.abort();
  shutdownTask=Promise.allSettled([providers.claude.dispose(),providers.agy.dispose()]);return shutdownTask;
}
const detach=()=>shutdown(true).finally(()=>process.exit());
process.stdin.once('end',detach);process.once('SIGTERM',detach);process.once('SIGINT',detach);
