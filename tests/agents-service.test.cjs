const {test}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {once}=require('node:events');
const {createInterface}=require('node:readline');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const service=path.join(__dirname,'../app/src/main/assets/rooms/service.cjs');

// A fake CLI per action: probe reports a ready provider, run streams one Claude answer.
const fakeCli={
 probe:`console.log(JSON.stringify({kind:'probe',state:'ready',auth:'signed_in',models:[{id:'m1',label:'M1',efforts:[]}]}));`,
 run:String.raw`require('node:readline').createInterface({input:process.stdin}).on('line',line=>{
  const prompt=JSON.parse(line).message.content;
  const out=v=>process.stdout.write(JSON.stringify(v)+'\n');
  out({type:'system',session_id:'native-1'});
  out({type:'stream_event',event:{type:'content_block_delta',delta:{type:'text_delta',text:'echo:'}}});
  out({type:'result',subtype:'success',result:'echo:'+prompt.length,session_id:'native-1'});
 });`,
 hang:`process.stdin.resume();`,
};
function start(t,runScript='run'){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'agents-service-'));
 const child=spawn(process.execPath,[service,root],{stdio:['pipe','pipe','pipe']});
 const callbacks=new Map(),events=[],hosts=[],waiters=new Set();let seq=0;
 const write=value=>child.stdin.write(JSON.stringify(value)+'\n');
 const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;callbacks.set(id,{resolve,reject});write({id,method,params});});
 createInterface({input:child.stdout}).on('line',raw=>{
  const m=JSON.parse(raw);
  if(m.method==='host.launch'){hosts.push(m);const action=m.params.action==='run'?runScript:m.params.action;
   write({id:m.id,result:{argv:[process.execPath,'-e',fakeCli[action]||`console.log(JSON.stringify({kind:'output',text:String(process.pid)}));process.stdin.resume();`]}});}
  else if(m.method?.startsWith('host.')){hosts.push(m);write({id:m.id,result:{}});}
  else if(m.method){events.push(m);for(const wake of waiters)wake();}
  else if(callbacks.has(m.id)){const c=callbacks.get(m.id);callbacks.delete(m.id);m.error?c.reject(new Error(m.error.message)):c.resolve(m.result);}
 });
 const waitEvent=match=>new Promise(resolve=>{const check=()=>{const found=events.find(match);if(found){waiters.delete(check);resolve(found);}};waiters.add(check);check();});
 // Hot CLI sessions outlive a turn; shut the service down before removing its folder.
 t.after(async()=>{
  if(child.exitCode===null){const exit=once(child,'exit');call('agents.shutdown').catch(()=>{});await Promise.race([exit,new Promise(r=>setTimeout(r,3000))]);if(child.exitCode===null)child.kill();}
  fs.rmSync(root,{recursive:true,force:true,maxRetries:10,retryDelay:100});
 });
 return {child,call,events,hosts,waitEvent};
}
test('a widget refresh checks only its requested provider',{timeout:20000},async t=>{
 const s=start(t);
 const result=await s.call('agents.providers.check',{provider:'claude'});
 assert.deepEqual(result.providers.map(p=>p.id),['claude']);
 assert.deepEqual(s.hosts.filter(h=>h.method==='host.launch').map(h=>h.params.provider),['claude']);
 await assert.rejects(s.call('agents.providers.check',{provider:'unknown'}),/에이전트를 선택/);
});
test('an agent turn streams deltas, then reports its native session',{timeout:20000},async t=>{
 const s=start(t);
 assert.deepEqual(await s.call('agents.run',{runId:'run-1',sessionId:'local-1',memberId:'claude',provider:'claude',model:'m1',mode:'chat',prompt:'hello'}),{ok:true});
 const final=await s.waitEvent(e=>e.method==='agents.event'&&e.params.runId==='run-1'&&['final','error'].includes(e.params.kind));
 assert.equal(final.params.kind,'final',final.params.message);
 assert.equal(final.params.text,'echo:5');assert.equal(final.params.sessionId,'native-1');
 assert.ok(s.events.some(e=>e.params?.kind==='delta'&&e.params.text==='echo:'));
 assert.ok(s.events.some(e=>e.params?.kind==='session'&&e.params.id==='native-1'));
 await assert.rejects(s.call('agents.run',{runId:'bad id',sessionId:'local-1',provider:'claude',prompt:'x'}),/실행 ID/);
 await assert.rejects(s.call('agents.run',{runId:'run-2',sessionId:'local-1',provider:'codex',prompt:'x'}),/지원하지 않는 에이전트/);
 await assert.rejects(s.call('rooms.list'),/지원하지 않는/);
});
test('stopping a running agent ends its turn as interrupted',{timeout:20000},async t=>{
 const s=start(t,'hang');
 await s.call('agents.run',{runId:'run-1',sessionId:'local-1',memberId:'gemini',provider:'agy',model:'m1',mode:'chat',prompt:'hello'});
 await new Promise(resolve=>{const tick=()=>s.hosts.some(h=>h.params?.action==='run')?resolve():setTimeout(tick,10);tick();});
 await s.call('agents.stop',{runId:'run-1'});
 const done=await s.waitEvent(e=>e.method==='agents.event'&&e.params.runId==='run-1'&&e.params.kind==='error');
 assert.equal(done.params.interrupted,true);
});
test('real service shutdown closes an installed-provider setup subprocess',{timeout:20000},async t=>{
 const s=start(t);let pid;
 await s.call('agents.provider.login',{provider:'agy'});
 const output=await s.waitEvent(e=>e.method==='agents.setup'&&e.params.kind==='output'&&/^\d+$/.test(e.params.text));pid=Number(output.params.text);
 assert.doesNotThrow(()=>process.kill(pid,0));
 const exit=once(s.child,'exit');await s.call('agents.shutdown');await exit;
 assert.throws(()=>process.kill(pid,0));assert.equal(s.child.exitCode,0);
});
