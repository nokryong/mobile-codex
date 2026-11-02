const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http');
const {spawn}=require('node:child_process');
const {CliProvider}=require('../app/src/main/assets/rooms/providers.cjs');

test('auto review is offered only by a capable Claude CLI and remains model gated',async()=>{
 const provider=new CliProvider('claude',{root:'.',host:async()=>({}),emit:()=>{}});
 provider.cap={state:'ready',models:[{id:'m',efforts:[]}],nativeAutoReview:true};
 const member={model:'m',approvalMode:'auto-review'};
 await provider.validate(member);
 provider.cap.autoReviewUnavailableModels=['m'];
 await assert.rejects(provider.validate(member),/자동 검토/);
 provider.cap.autoReviewUnavailableModels=[];provider.cap.nativeAutoReview=false;
 await assert.rejects(provider.validate(member),/자동 검토/);
 provider.id='agy';provider.cap.nativeAutoReview=true;
 await assert.rejects(provider.validate(member),/자동 검토/);
});
// Answers each probe with the next scripted line; undefined exits without answering.
function probing(t,answers,id='claude') {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-probe-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider(id,{root,host:async()=>({}),emit:()=>{}});provider.launches=0;
 provider.launch=async()=>{const answer=answers[provider.launches++];
  return spawn(process.execPath,['-e',answer===undefined?'console.error("token=abc");process.exit(3)':`console.log(${JSON.stringify(JSON.stringify({kind:'probe',...answer}))})`],{stdio:['pipe','pipe','pipe'],windowsHide:true});};
 return provider;
}
const models=[{id:'m',efforts:[]}],usage=[{label:'week',remainingPercent:50}];
const ready={state:'ready',auth:'signed_in',reason:'',models,usage,nativeAutoReview:true},slow={state:'unverified',auth:'unknown',reason:'느림',models:[]};

test('a failed check keeps the last good connection and lists',{timeout:10000},async t=>{
 const provider=probing(t,[ready,slow,undefined,{...ready,models:[],usage:[]},ready]);
 assert.equal((await provider.check()).state,'ready');
 for(let i=0;i<2;i++){
  const cap=await provider.check({force:true});
  assert.equal(cap.state,'ready');assert.equal(cap.auth,'signed_in');assert.deepEqual(cap.models,models);assert.deepEqual(cap.usage,usage);assert.equal(cap.nativeAutoReview,true);
  assert.match(cap.reason,/마지막 연결 상태/);
 }
 const partial=await provider.check({force:true});
 assert.deepEqual(partial.models,models);assert.deepEqual(partial.usage,usage);assert.equal(partial.reason,'');
});
test('the CLI saying signed out still ends the connection and clears its lists',{timeout:10000},async t=>{
 const provider=probing(t,[ready,{state:'login_required',auth:'signed_out',reason:'로그인',models:[],usage:[]}]);
 await provider.check();const cap=await provider.check({force:true});
 assert.equal(cap.state,'login_required');assert.deepEqual(cap.models,[]);assert.deepEqual(cap.usage,[]);
});
test('a first failed check is unverified, and Linux not starting stays unavailable',{timeout:10000},async t=>{
 const provider=probing(t,[slow,undefined]);
 assert.equal((await provider.check()).state,'unverified');
 assert.equal((await provider.check()).state,'unverified');
 provider.launch=async()=>{throw new Error('Linux 비활성');};
 const cap=await provider.check();assert.equal(cap.state,'unavailable');assert.equal(cap.reason,'Linux 비활성');
});
test('a recent good check is reused, unless forced or a turn failed',{timeout:10000},async t=>{
 const provider=probing(t,[ready,ready,ready]);
 await provider.check();await provider.check();assert.equal(provider.launches,1);
 await provider.check({force:true});assert.equal(provider.launches,2);
 await provider.check();assert.equal(provider.launches,2);
 provider.checkedAt=0;await provider.check();assert.equal(provider.launches,3);
});
test('no check starts while a turn is using the account',{timeout:10000},async t=>{
 const provider=probing(t,[ready]);await provider.check();
 provider.sessions.set('busy',{turn:{}});
 await provider.check({force:true});assert.equal(provider.launches,1);
});
test('starting a login cancels a running check without letting it overwrite the state',{timeout:15000},async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-cancel-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('claude',{root,host:async()=>({}),emit:()=>{}});t.after(()=>provider.dispose());
 provider.launch=async()=>spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:['pipe','pipe','pipe'],windowsHide:true});
 const check=provider.check();while(!provider.probeChild)await new Promise(r=>setTimeout(r,5));
 const started=Date.now();await provider.setupStart('login');await check;
 assert.ok(Date.now()-started<10000);assert.equal(provider.cap.state,'login');
 await provider.setupStop();
});
test('a failed turn makes the next check real',{timeout:10000},async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-turn-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('agy',{root,host:async()=>({}),emit:()=>{}});provider.checkedAt=Date.now();
 provider.launch=async()=>spawn(process.execPath,['-e','console.error("invalid_grant");setTimeout(()=>process.exit(1),100)'],{stdio:['pipe','pipe','pipe'],windowsHide:true});
 t.after(()=>provider.close());
 await assert.rejects(provider.run({room:{id:'r',runId:'a',mode:'chat'},member:{id:'g',model:'',session:{id:''}},prompt:'p',signal:new AbortController().signal,event:()=>{}}),/CLI가 종료/);
 assert.equal(provider.checkedAt,0);
});
const shell=process.platform==='win32'
 ? [path.join(process.env.ProgramFiles||'C:/Program Files','Git/bin/sh.exe'),path.join(process.env.LOCALAPPDATA||'','Programs/Git/bin/sh.exe')].find(p=>fs.existsSync(p))
 : '/bin/sh';
const engineSource=fs.readFileSync(path.join(__dirname,'../app/src/main/java/dev/mobilecodex/app/Engine.java'),'utf8');

for (const permission of ['auto','default','missing']) test('Claude MCP auto review requires effective native mode: '+permission,{timeout:10000},async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-auto-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const hosts=[],events=[];const provider=new CliProvider('claude',{root,host:async(method,args)=>{hosts.push({method,args});return {ok:true};},emit:(name,data)=>events.push({name,data})});
 const script=`const readline=require('node:readline');readline.createInterface({input:process.stdin}).on('line',()=>{
   const mode=${JSON.stringify(permission)};
   if(mode!=='missing')console.log(JSON.stringify({type:'system',subtype:'init',session_id:'s',permissionMode:mode}));
 });`;
 let config;provider.launch=async(action,c)=>{config=c;return spawn(process.execPath,['-e',script],{stdio:['pipe','pipe','pipe']});};
 t.after(()=>provider.close());const controller=new AbortController();
 const pending=provider.run({room:{id:'room',runId:'turn-1',mode:'write',projectKey:'p'},
   member:{id:'claude',model:'m',effort:'',approvalMode:'auto-review',session:{id:''}},
   prompt:'wait',signal:controller.signal,event:()=>{},approve:async()=>true});
 const outcome=pending.then(value=>({value}),error=>({error}));
 if(permission==='default'){
   assert.match((await outcome).error.message,/자동 검토/);
   assert.deepEqual(provider.cap.autoReviewUnavailableModels,['m']);
   assert.ok(events.some(e=>e.name==='agents.providers'));
   return;
 }
 for(let i=0;i<100;i++){
   const s=provider.sessions.get('room-claude');
   if(s?.turn&&(permission==='missing'||s.nativeAutoReview))break;
   await new Promise(resolve=>setTimeout(resolve,10));
 }
 const s=provider.sessions.get('room-claude');
 const result=await httpCall(s.server.address().port,s.token,{action:'call',name:'mobile_exec',arguments:{command:'echo ok'}});
 assert.equal(result.code,permission==='auto'?200:403);
 assert.equal(hosts.length,permission==='auto'?1:0);
 if(permission==='auto')assert.equal(hosts[0].args.runId,'turn-1');
 assert.equal(config.approvalMode,'auto-review');
 controller.abort();assert.ok((await outcome).error);
});

test('changing approval policy restarts a hot CLI with the new policy',async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-policy-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('agy',{root,host:async()=>({}),emit:()=>{}}),configs=[];
 provider.launch=async(action,config)=>{configs.push(config);return spawn(process.execPath,['-e',fixture],{stdio:['pipe','pipe','pipe']});};
 t.after(()=>provider.close());
 const room={id:'r',runId:'a',mode:'write'},member={id:'gemini',model:'m',approvalMode:'ask',session:{id:''}};
 const run=()=>provider.run({room,member,prompt:'answer',signal:new AbortController().signal,event:()=>{}});
 member.session.id=(await run()).sessionId;
 member.approvalMode='allow-all';room.runId='b';await run();
 assert.equal(configs.length,2);assert.deepEqual(configs.map(c=>c.approvalMode),['ask','allow-all']);
});

test('immediate resend waits for the cancelled CLI and uses a fresh process',{timeout:10000},async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-resend-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('agy',{root,host:async()=>({}),emit:()=>{}});let launches=0;
 provider.launch=async()=>{launches++;return spawn(process.execPath,['-e',fixture],{stdio:['pipe','pipe','pipe']});};
 t.after(()=>provider.close());const first=new AbortController();
 const input={room:{id:'r',runId:'one',mode:'chat'},member:{id:'g',model:'m',session:{id:''}},event:()=>{}};
 const old=provider.run({...input,prompt:'wait',signal:first.signal}).then(()=>false,()=>true);
 while(!provider.sessions.get('r-g')?.turn)await new Promise(resolve=>setTimeout(resolve,5));
 first.abort();
 const next=await provider.run({...input,room:{...input.room,runId:'two'},prompt:'new',signal:new AbortController().signal});
 assert.equal(await old,true);assert.equal(next.text,'answer-1');assert.equal(launches,2);
});
const installBootstrap=JSON.parse('"'+engineSource.match(/String bootstrap=action.equals\("install"\)\?"([^"\n]+)"/)[1]+'"');

for(const id of ['claude','agy'])test(id+' installer waits for explicit answers and forwards y/n/Enter to its child',{timeout:10000},async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-input-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const events=[],waiters=[];let finish;const finished=new Promise(resolve=>finish=resolve);
 const provider=new CliProvider(id,{root,host:async()=>({}),emit:(_,data)=>{events.push(data);for(const wake of waiters)wake();if(data.kind==='exit')finish(data.code);}});
 provider.check=async()=>provider.cap;t.after(()=>provider.dispose());
 const script=String.raw`
const readline=require('node:readline');const expected=['y\n','n\n','\n','custom answer\n'];let next=0;
const output=text=>process.stdout.write(JSON.stringify({kind:'output',text})+'\n');
output('PROMPT_0');readline.createInterface({input:process.stdin}).on('line',line=>{
 if(JSON.parse(line).text!==expected[next])process.exit(31);
 next++;if(next===expected.length){process.stdout.write(JSON.stringify({kind:'output',text:'INPUTS_ACCEPTED'})+'\n',()=>process.exit(0));}
 else output('PROMPT_'+next);
});`;
 provider.launch=async()=>spawn(process.execPath,['-e',script],{stdio:['pipe','pipe','pipe'],windowsHide:true});
 const waitText=text=>new Promise(resolve=>{const check=()=>{if(events.some(e=>e.text===text))resolve();};waiters.push(check);check();});
 await provider.setupStart('install');await waitText('PROMPT_0');assert.equal(provider.setup.exitCode,null);
 for(const [index,text] of ['y\n','n\n','\n','custom answer\n'].entries()){
  assert.deepEqual(await provider.setupInput(text),{ok:true});
  await waitText(index===3?'INPUTS_ACCEPTED':'PROMPT_'+(index+1));
 }
 assert.equal(await finished,0);assert.throws(()=>provider.setupInput('y\n'),/진행 중인 설치 또는 로그인이 없습니다/);
});

// Exercise the shipped shell prefix through the real setup process/event reader.
// The fixture models a rootfs sync DB that -Sy considers current even though its
// package URLs are gone. It performs no downloads or changes to the host system.
async function bootstrapFixture(t,bootstrap,offline=false){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-bootstrap-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const events=[];let finished;const done=new Promise(resolve=>finished=resolve);
 const provider=new CliProvider('claude',{root,host:async()=>({}),emit:(name,data)=>{events.push({name,...data});if(data.kind==='exit')finished(data.code);}});
 provider.check=async()=>provider.cap;t.after(()=>provider.dispose());
 const pacman=String.raw`pacman() {
   printf 'Synchronizing package databases...\n'
   if [ "$fixture_offline" = yes ]; then printf 'error: repository unavailable\n' >&2; return 1; fi
   case "$*" in
     *-Syyu*) printf 'python current; mpdecimal current\n'; return 0 ;;
     *) printf "error: failed retrieving file 'python-3.13.7-1-aarch64.pkg.tar.xz': 404\n" >&2; return 1 ;;
   esac
 }
 `;
 const marker=`printf '%s\\n' '{"kind":"output","text":"OFFICIAL_INSTALLER_STARTED"}'`;
 provider.launch=async()=>spawn(shell,['-c',pacman+bootstrap+marker],{env:{...process.env,fixture_offline:offline?'yes':'no'},stdio:['pipe','pipe','pipe'],windowsHide:true});
 await provider.setupStart('install');return {code:await done,events};
}

test('CLI installation refreshes stale sync metadata and streams bootstrap diagnostics without corrupting JSONL',{skip:!shell,timeout:10000},async t=>{
 const before=await bootstrapFixture(t,'pacman -Syu --noconfirm --needed python curl ca-certificates >/dev/null && ');
 assert.equal(before.code,1);assert.ok(before.events.some(e=>e.text?.includes('404')));
 const after=await bootstrapFixture(t,installBootstrap);
 assert.equal(after.code,0);assert.ok(after.events.some(e=>e.text?.includes('Synchronizing package databases')));
 assert.ok(after.events.some(e=>e.text?.includes('OFFICIAL_INSTALLER_STARTED')));
 assert.ok(!after.events.some(e=>e.text?.includes('CLI 출력을 읽지 못했습니다.')));
});

test('failed package synchronization keeps diagnostics and never starts the official CLI installer',{skip:!shell,timeout:10000},async t=>{
 const result=await bootstrapFixture(t,installBootstrap,true);
 assert.equal(result.code,1);assert.ok(result.events.some(e=>e.text?.includes('repository unavailable')));
 assert.ok(!result.events.some(e=>e.text?.includes('OFFICIAL_INSTALLER_STARTED')));
});
const fixture=String.raw`
const readline=require('node:readline');let n=0;
readline.createInterface({input:process.stdin}).on('line',raw=>{const p=JSON.parse(raw);n++;if(p.message.content==='wait')return;
process.stdout.write(JSON.stringify({event:'init',conversation_id:'native-session'})+'\n');
process.stdout.write(JSON.stringify({event:'step_update',step_update:{step_type:'agent_response',text_delta:'chunk-'+n}})+'\n');
process.stdout.write(JSON.stringify({event:'result',result:{status:'SUCCESS',response:'answer-'+n,conversation_id:'native-session'}})+'\n');});`;
function httpCall(port,token,value){return new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port,method:'POST',headers:{authorization:'Bearer '+token}},res=>{let text='';res.on('data',d=>text+=d);res.on('end',()=>resolve({code:res.statusCode,body:JSON.parse(text)}));});req.on('error',reject);req.end(JSON.stringify(value));});}
test('real child stdio stays alive between turns and stop terminates it',async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-test-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('agy',{root,host:async()=>({}),emit:()=>{}});let launches=0;
 provider.launch=async()=>{launches++;return spawn(process.execPath,['-e',fixture],{stdio:['pipe','pipe','pipe']});};t.after(()=>provider.close());
 const room={id:'room',mode:'chat'},member={id:'agy',model:'',effort:'',session:{id:''}};
 const events=[];let a=await provider.run({room,member,prompt:'first',signal:new AbortController().signal,event:e=>events.push(e)});member.session.id=a.sessionId;
 let b=await provider.run({room,member,prompt:'second',signal:new AbortController().signal,event:e=>events.push(e)});
 assert.equal(a.text,'answer-1');assert.equal(b.text,'answer-2');assert.equal(launches,1);assert.equal(a.sessionId,b.sessionId);assert.ok(events.some(e=>e.kind==='delta'));
 const controller=new AbortController();const pending=provider.run({room,member,prompt:'wait',signal:controller.signal,event:()=>{}});await new Promise(r=>setTimeout(r,20));controller.abort();await assert.rejects(pending);assert.equal(provider.sessions.size,0);
});
test('actual MCP HTTP endpoint authenticates, gates writes, revokes after cancellation',async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-mcp-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const tools=[];const provider=new CliProvider('agy',{root,host:async(method,args)=>{tools.push({method,args});return {ok:true};},emit:()=>{}});
 provider.launch=async()=>spawn(process.execPath,['-e',fixture],{stdio:['pipe','pipe','pipe']});t.after(()=>provider.close());
 const controller=new AbortController();let approved=false;
 const pending=provider.run({room:{id:'room',mode:'write',projectKey:'p'},member:{id:'agy',model:'',effort:'',session:{id:''}},prompt:'wait',signal:controller.signal,event:()=>{},approve:async()=>approved});
 await new Promise(r=>setTimeout(r,50));const s=provider.sessions.get('room-agy'),port=s.server.address().port;
 assert.equal((await httpCall(port,'wrong',{action:'list'})).code,403);
 assert.equal((await httpCall(port,s.token,{action:'call',name:'mobile_write',arguments:{path:'x'}})).code,403);assert.equal(tools.length,0);
 approved=true;assert.equal((await httpCall(port,s.token,{action:'call',name:'mobile_write',arguments:{path:'x'}})).code,200);assert.equal(tools.length,1);
 controller.abort();await assert.rejects(pending);await assert.rejects(httpCall(port,s.token,{action:'list'}));
});
test('read mode denies writes even when a caller offers approval',async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'provider-read-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const provider=new CliProvider('agy',{root,host:async()=>{throw new Error('must not reach host');},emit:()=>{}});provider.launch=async()=>spawn(process.execPath,['-e',fixture],{stdio:['pipe','pipe','pipe']});t.after(()=>provider.close());
 const controller=new AbortController();const pending=provider.run({room:{id:'room',mode:'read'},member:{id:'agy',model:'',effort:'',session:{id:''}},prompt:'wait',signal:controller.signal,event:()=>{},approve:async()=>true});
 await new Promise(r=>setTimeout(r,50));const s=provider.sessions.get('room-agy');const response=await httpCall(s.server.address().port,s.token,{action:'call',name:'mobile_exec',arguments:{command:'echo bad'}});assert.equal(response.code,403);
 controller.abort();await assert.rejects(pending);
});

// Stops must work through PRoot, which ignores SIGTERM and whose children can keep our pipes.
const {terminate,settleOnExit}=require('../app/src/main/assets/rooms/providers.cjs');
const posix=process.platform!=='win32';
const node=(script)=>settleOnExit(spawn(process.execPath,['-e',script],{stdio:['pipe','pipe','pipe'],windowsHide:true,detached:posix}));
const closed=child=>new Promise(resolve=>child.once('close',resolve));
const alive=pid=>{try{process.kill(pid,0);return true;}catch{return false;}};
test('stop closes stdin first, so a CLI that ignores SIGTERM still ends gracefully',{timeout:10000},async()=>{
 const child=node(`process.on('SIGTERM',()=>{});process.stdin.on('end',()=>process.exit(7));process.stdin.resume();console.log('ready');`);
 await new Promise(resolve=>child.stdout.once('data',resolve));
 const started=Date.now(),done=closed(child);await terminate(child);await done;
 assert.equal(child.exitCode,7);assert.ok(Date.now()-started<2500);
});
test('stop kills a SIGTERM-ignoring leader and the children holding its pipes',{timeout:15000,skip:!posix&&'process groups are POSIX-only'},async()=>{
 const child=node(String.raw`process.on('SIGTERM',()=>{});process.stdin.on('end',()=>{});process.stdin.resume();
  const kid=require('node:child_process').spawn(process.execPath,['-e','process.on("SIGTERM",()=>{});setInterval(()=>{},1000)'],{stdio:['ignore','inherit','inherit']});
  console.log(kid.pid);`);
 const grandchild=Number(await new Promise(resolve=>child.stdout.once('data',d=>resolve(String(d).trim()))));
 assert.ok(alive(grandchild));
 const done=closed(child);await terminate(child,300);await done;
 await new Promise(r=>setTimeout(r,100));assert.equal(alive(grandchild),false);
});
test('a process that exits on its own still closes when a child keeps its stdout',{timeout:10000},async t=>{
 const child=node(String.raw`const kid=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:['ignore','inherit','inherit']});
  console.log(kid.pid);setTimeout(()=>process.exit(0),100);`);
 const grandchild=Number(await new Promise(resolve=>child.stdout.once('data',d=>resolve(String(d).trim()))));
 t.after(()=>{try{process.kill(grandchild,'SIGKILL');}catch{}});
 await closed(child);
 if(posix){await new Promise(r=>setTimeout(r,100));assert.equal(alive(grandchild),false);}
});
