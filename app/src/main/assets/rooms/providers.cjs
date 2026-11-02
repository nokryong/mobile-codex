'use strict';
const fs=require('node:fs');
const path=require('node:path');
const http=require('node:http');
const {spawn}=require('node:child_process');
const {randomBytes}=require('node:crypto');
const {normalize}=require('./provider-events.cjs');

const limit=2*1024*1024;
const probeLimit=120000; // Above PROBE_SECONDS in provider-runtime.py, which bounds its own steps.
const probeFresh=300000; // Every probe starts several CLIs that share one credential file.
function lines(stream, receive, failure) {
  let pending='';stream.setEncoding('utf8');
  stream.on('data',chunk=>{pending+=chunk;if(Buffer.byteLength(pending)>limit){failure(new Error('CLI 출력 제한을 초과했습니다.'));return;}
    let at;while((at=pending.indexOf('\n'))>=0){const line=pending.slice(0,at);pending=pending.slice(at+1);if(line.trim())try{receive(JSON.parse(line));}catch(error){failure(new Error('CLI 구조화 응답을 읽지 못했습니다.'));}}});
}
function choice(member,cap) {
  if(!cap.models?.length)throw new Error('모델 목록을 불러오지 못했습니다. 계정에서 다시 조회해 주세요.');
  if(!member.model)throw new Error('참가자 설정에서 사용할 모델을 선택해 주세요.');
  if(member.model && !cap.models.some(m=>m.id===member.model))throw new Error('공급자 목록에 없는 모델입니다. 준비 상태를 새로 확인해 주세요.');
  const m=cap.models.find(m=>m.id===member.model);
  if(m?.efforts?.length&&!member.effort)throw new Error('참가자 설정에서 추론 강도를 선택해 주세요.');
  if(member.effort && !(m?.efforts||[]).includes(member.effort))throw new Error('이 모델이 제공하지 않는 추론 설정입니다.');
}
// CLIs run as Node → PRoot → sh → provider-runtime.py → CLI. PRoot ignores SIGTERM and its
// tracees can outlive it while holding our pipes, so a stop must never wait on the pipes:
// close stdin first (provider-runtime.py stops its CLI on EOF), then signal the whole group.
const groups=process.platform!=='win32';
function signalGroup(child,signal) {
  try{if(groups&&child.pid){process.kill(-child.pid,signal);return;}}catch{}
  try{child.kill(signal);}catch{}
}
function exited(child){return child.exitCode!==null||child.signalCode!==null;}
function waitExit(child,ms) {
  if(exited(child))return Promise.resolve(true);
  return new Promise(resolve=>{const done=()=>{clearTimeout(timer);resolve(true);};const timer=setTimeout(()=>{child.off('exit',done);resolve(false);},ms);child.once('exit',done);});
}
/** Ends leftovers of an exited process group and releases its pipes so 'close' always fires. */
function release(child) {
  if(groups&&child.pid)try{process.kill(-child.pid,'SIGKILL');}catch{}
  for(const stream of [child.stdin,child.stdout,child.stderr])stream?.destroy();
}
async function terminate(child,grace=3000) {
  if(!child)return;
  if(!exited(child)) {
    try{child.stdin?.end();}catch{}
    if(!await waitExit(child,grace)){signalGroup(child,'SIGTERM');if(!await waitExit(child,500)){signalGroup(child,'SIGKILL');await waitExit(child,3000);}}
  }
  release(child);
}
/** A process that exits by itself may leave a child holding stdout; settle it shortly after. */
function settleOnExit(child) {
  let closed=false;child.once('close',()=>{closed=true;});
  child.once('exit',()=>setTimeout(()=>{if(!closed)release(child);},1000).unref());
  return child;
}

class CliProvider {
  constructor(id,{root,host,emit}) {this.id=id;this.root=root;this.host=host;this.emit=emit;this.sessions=new Map();this.setup=null;this.checkedAt=0;this.cap={id,name:id==='agy'?'Antigravity':'Claude Code',state:'unchecked',auth:'unknown',models:[],resume:true,streaming:true,approval:'mobile-mcp',nativeApproval:false};}
  async launch(action,config={},key='setup') {
    if(this.disposed)throw new Error('공급자 연결이 종료되었습니다.');
    const dir=path.join(this.root,'work',this.id,key);fs.mkdirSync(dir,{recursive:true,mode:0o700});
    for(const name of ['provider-runtime.py','terminal_io.py','mobile-mcp.py'])fs.copyFileSync(path.join(__dirname,name),path.join(dir,name));
    fs.writeFileSync(path.join(dir,'run.json'),JSON.stringify(config),{mode:0o600});
    const launch=await this.host('launch',{provider:this.id,action,directory:dir});
    if(this.disposed)throw new Error('공급자 연결이 종료되었습니다.');
    // Own process group, so a stop reaches PRoot's children too.
    return settleOnExit(spawn(launch.argv[0],launch.argv.slice(1),{cwd:dir,env:process.env,stdio:['pipe','pipe','pipe'],windowsHide:true,detached:groups}));
  }
  async check({force=false}={}) {
    if(this.setup||this.preparing||this.disposed)return this.cap;
    if(this.checking)return this.checking;
    // A running turn already uses the account; a second CLI would only compete for its token.
    if(this.cap.state==='ready'&&([...this.sessions.values()].some(s=>s.turn)||(!force&&Date.now()-this.checkedAt<probeFresh)))return this.cap;
    this.checking=this.probe().finally(()=>{this.checking=null;});return this.checking;
  }
  async probe() {
    let child,timer;
    try {
      child=await this.launch('probe');this.probeChild=child;
      if(this.preparing)throw new Error('설치 또는 로그인으로 확인을 중단했습니다.');
      const data=await new Promise((resolve,reject)=>{
        let result;timer=setTimeout(()=>reject(new Error('준비 상태 확인 시간이 초과됐습니다.')),probeLimit);
        lines(child.stdout,e=>{if(e.kind==='probe')result=e;},reject);child.stderr.resume();
        child.once('error',reject);child.once('close',code=>{clearTimeout(timer);result?resolve(result):reject(new Error('CLI 확인에 실패했습니다 ('+code+'). Linux 도구를 설치·활성화해 주세요.'));});child.stdin.end();
      });
      this.settle(data);
    }catch(e){
      // Once the CLI could start, a failed check says nothing about the account. Not starting
      // at all means Linux itself is unavailable. A login that cancelled this check owns the state.
      if(!this.preparing&&!this.disposed)this.settle({state:child?'unverified':'unavailable',auth:'unknown',reason:e.message,models:[]});
    }
    finally{clearTimeout(timer);await terminate(child);if(this.probeChild===child)this.probeChild=null;}
    this.emit('agents.providers',{providers:[this.cap]});return this.cap;
  }
  settle({kind,steps,...data}) {
    if(data.state==='unverified'&&this.cap.state==='ready') {
      this.cap={...this.cap,reason:'상태를 다시 확인하지 못해 마지막 연결 상태를 표시합니다. '+data.reason};return;
    }
    this.checkedAt=data.state==='ready'?Date.now():0;
    // Only a signed-in or unanswered check may keep the lists from the last good one.
    const keep=['ready','unverified'].includes(data.state);
    this.cap={...this.cap,...data,id:this.id,nativeAutoReview:data.nativeAutoReview===true,autoReviewUnavailableModels:[],
      models:keep&&!data.models?.length?this.cap.models:data.models||[],usage:keep&&!data.usage?.length?this.cap.usage:data.usage||[]};
  }
  async validate(member) {
    if(this.setup||this.preparing||this.disposed)throw new Error('공급자의 설치·로그인 또는 종료를 먼저 마쳐 주세요.');
    if(this.cap.state!=='ready')await this.check();
    if(this.cap.state!=='ready')throw new Error(this.cap.reason||'공급자 설치와 로그인을 확인해 주세요.');
    choice(member,this.cap);
    const approval=member.approvalMode||'ask';
    if(!['ask','allow-all','auto-review'].includes(approval))throw new Error('지원하지 않는 승인 방식입니다.');
    if(approval==='auto-review'&&(this.id!=='claude'||!this.cap.nativeAutoReview||this.cap.autoReviewUnavailableModels?.includes(member.model)))
      throw new Error('이 CLI 또는 모델에서 자동 검토를 사용할 수 없습니다. 참가자 승인 방식을 다시 선택해 주세요.');
  }
  async setupStart(action) {
    if(!['install','login'].includes(action))throw new Error('지원하지 않는 준비 작업입니다.');
    if(this.setup||this.preparing)throw new Error('진행 중인 설치 또는 로그인을 먼저 마쳐 주세요.');
    if([...this.sessions.values()].some(s=>s.turn))throw new Error('공급자의 발언을 먼저 중지해 주세요.');
    this.preparing=true;
    let child;
    try{if(this.checking){terminate(this.probeChild);await this.checking;}await this.close();child=await this.launch(action);this.setup=child;}
    finally{this.preparing=false;}
    this.cap.state=action==='install'?'installing':'login';
    this.emit('agents.setup',{provider:this.id,kind:'started',action,text:action==='install'?'Linux 도구 갱신과 CLI 설치를 시작합니다.\n':'CLI 로그인을 시작합니다.\n'});
    lines(child.stdout,e=>this.emit('agents.setup',{provider:this.id,...e,...(e.kind==='runtime-error'?{kind:'error',text:e.message}:{})}),()=>this.emit('agents.setup',{provider:this.id,kind:'output',text:'CLI 출력을 읽지 못했습니다.'}));
    child.stderr.setEncoding('utf8');child.stderr.on('data',text=>this.emit('agents.setup',{provider:this.id,kind:'output',text:text.slice(0,4000)}));
    child.stdin.on('error',()=>{});
    child.once('error',e=>this.emit('agents.setup',{provider:this.id,kind:'error',text:e.message}));
    child.once('close',code=>{if(this.setup===child)this.setup=null;this.emit('agents.setup',{provider:this.id,kind:'exit',code});if(!this.disposed)this.check().catch(()=>{});});
    return {ok:true};
  }
  setupInput(text) {
    const child=this.setup;
    if(!child||child.exitCode!==null||child.signalCode||child.stdin.destroyed||!child.stdin.writable)throw new Error('진행 중인 설치 또는 로그인이 없습니다.');
    if(String(text).length>16000)throw new Error('입력이 너무 깁니다.');
    return new Promise((resolve,reject)=>child.stdin.write(JSON.stringify({text:String(text)})+'\n',error=>error?reject(new Error('입력을 전달하지 못했습니다. 설치 또는 로그인 상태를 확인해 주세요.')):resolve({ok:true})));
  }
  async setupStop() {const child=this.setup;this.setup=null;await terminate(child);return {ok:true};}
  async run(input) {
    const {room,member,signal}=input, key=room.id+'-'+member.id;
    const signatureFor=id=>JSON.stringify([room.mode,room.projectKey,member.model,member.effort,member.approvalMode||'ask',id]);
    const signature=signatureFor(member.session.id);
    let s=this.sessions.get(key);
    // Stop and immediate resend can arrive before the previous process exits.
    // Never replace its turn callbacks or write the new prompt into a dying CLI.
    if(s?.ending || s?.turn?.signal.aborted){await this.end(s);s=null;}
    if(s?.turn)throw new Error('이 참가자가 이미 답하고 있습니다. 먼저 중지해 주세요.');
    if(s){clearTimeout(s.idleTimer);s.idleTimer=null;}
    if(s && (s.signature!==signature||s.child.exitCode!==null||s.child.signalCode)){await this.end(s);s=null;}
    if(!s) {
      // Keep hot sessions without accumulating a CLI process for every room.
      if(this.sessions.size>=2){const idle=[...this.sessions.values()].find(value=>!value.turn);if(idle)await this.end(idle);}
      s={key,signature,turn:null,nativeAutoReview:false,token:randomBytes(32).toString('hex'),server:null,child:null};
      s.server=http.createServer((req,res)=>this.mcp(s,req,res));
      await new Promise((resolve,reject)=>{s.server.once('error',reject);s.server.listen(0,'127.0.0.1',resolve);});
      try{s.child=await this.launch('run',{sessionId:member.session.id,model:member.model,effort:member.effort,mode:room.mode,approvalMode:member.approvalMode||'ask',
        mcp:{port:s.server.address().port,token:s.token}},key);}catch(e){s.server.close();throw e;}
      this.sessions.set(key,s);
      lines(s.child.stdout,e=>{
        const turn=s.turn;if(!turn)return;
        if(this.id==='claude'&&e.type==='system'&&e.subtype==='init') {
          s.nativeAutoReview=e.permissionMode==='auto';
          if(turn.member.approvalMode==='auto-review'&&!s.nativeAutoReview) {
            this.cap.autoReviewUnavailableModels=[...new Set([...(this.cap.autoReviewUnavailableModels||[]),turn.member.model])];
            this.emit('agents.providers',{providers:[this.cap]});
            turn.reject(new Error('Claude가 자동 검토 모드를 활성화하지 못했습니다. CLI·모델·계정 설정을 확인하거나 승인 방식을 다시 선택해 주세요.'));
            this.end(s);return;
          }
        }
        if(e.kind==='runtime-error'){turn.reject(new Error(e.message));return;}
        for(const n of normalize(this.id,e)) {
          if(n.kind==='session'){s.sessionId=n.id;turn.event(n);}
          else if(n.kind==='final'){s.signature=signatureFor(n.sessionId||s.sessionId);turn.resolve({text:n.text,sessionId:n.sessionId||s.sessionId});}
          else if(n.kind==='error')turn.reject(new Error(n.message));else turn.event(n);
        }
      },error=>{s.turn?.reject(error);terminate(s.child);});
      s.child.stderr.resume(); // Credentials and raw diagnostics never enter history.
      s.child.stdin.on('error',e=>s.turn?.reject(e));
      s.child.once('error',e=>s.turn?.reject(new Error('CLI를 실행하지 못했습니다: '+e.message)));
      s.child.once('close',code=>{s.turn?.reject(new Error('CLI가 종료되었습니다 ('+code+'). 준비 상태를 확인해 주세요.'));s.server.close();if(this.sessions.get(key)===s)this.sessions.delete(key);});
    }
    if(signal.aborted){await this.end(s);throw new Error('발언이 중지되었습니다.');}
    return new Promise((resolve,reject)=>{
      let settled=false;
      const finish=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);signal.removeEventListener('abort',cancel);s.turn=null;
        // A failed turn may be the first sign of a lost login: don't answer the next check from cache.
        if(error&&!signal.aborted)this.checkedAt=0;
        if(!error)s.idleTimer=setTimeout(()=>this.end(s),300000).unref();error?reject(error):resolve(value);};
      const cancel=()=>this.end(s).then(()=>finish(new Error('발언이 중지되었습니다.')));
      const timer=setTimeout(()=>this.end(s).then(()=>finish(new Error('응답 시간이 10분을 초과했습니다.'))),600000);
      s.turn={...input,resolve:value=>finish(null,value),reject:error=>finish(error)};
      signal.addEventListener('abort',cancel,{once:true});
      const data=this.id==='claude'?{type:'user',message:{role:'user',content:input.prompt}}:{event:'user',message:{content:input.prompt}};
      s.child.stdin.write(JSON.stringify(data)+'\n',error=>{if(error)finish(error);});
    });
  }
  async mcp(s,req,res) {
    let body='';req.on('data',d=>{body+=d;if(body.length>limit)req.destroy();});
    req.on('end',async()=>{
      try {
        if(req.method!=='POST'||req.headers.authorization!=='Bearer '+s.token)throw new Error('접근할 수 없습니다.');
        const p=JSON.parse(body),turn=s.turn;
        if(!turn||turn.signal.aborted)throw new Error('발언이 종료되었습니다.');
        const {room,member}=turn;let result;
        if(p.action==='list')result=await this.host('tools',{roomId:room.id,runId:room.runId,mode:room.mode});
        else {
          if(room.mode==='chat')throw new Error('대화 전용 방에서는 도구를 사용할 수 없습니다.');
          const read=['mobile_read','mobile_list','mobile_search','mobile_phone_status','mobile_phone_apps','mobile_phone_screen'];
          const mutating=!read.includes(p.name);
          if(mutating && room.mode!=='write')throw new Error('읽기 전용 방에서는 수정·실행할 수 없습니다.');
          if(mutating && member.approvalMode==='auto-review' && (this.id!=='claude'||!s.nativeAutoReview))
            throw new Error('공급자의 자동 검토가 활성화되지 않았습니다.');
          if(mutating && !await turn.approve(p.name,JSON.stringify(p.arguments,null,2)))throw new Error('사용자가 작업을 거절했습니다.');
          if(turn.signal.aborted||s.turn!==turn)throw new Error('작업이 중지되었습니다.');
          turn.event({kind:'status',status:'working'});
          result=await this.host('tool',{roomId:room.id,runId:room.runId,projectKey:room.projectKey,mode:room.mode,name:p.name,arguments:p.arguments||{}});
        }
        res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(result));
      }catch(error){res.writeHead(403,{'Content-Type':'application/json'});res.end(JSON.stringify({error:error.message}));}
    });
  }
  end(s) {
    if(s.ending)return s.ending;
    clearTimeout(s.idleTimer);
    s.ending=(async()=>{await terminate(s.child);s.server?.close();if(this.sessions.get(s.key)===s)this.sessions.delete(s.key);})();
    return s.ending;
  }
  async close(){await Promise.all([...this.sessions.values()].map(s=>this.end(s)));}
  async dispose(){this.disposed=true;await Promise.all([this.close(),this.setupStop(),terminate(this.probeChild)]);}
}

module.exports={CliProvider,choice,normalize,terminate,settleOnExit,lines};
