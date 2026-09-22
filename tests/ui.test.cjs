const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const C = require('../app/src/main/assets/web/ui-core.js');
const root = 'app/src/main/assets/web/';
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(r => setTimeout(r, 10));
function setup(overrides = {}, options = {}) {
  const dom = new JSDOM(fs.readFileSync(root+'index.html','utf8'), {url: 'https://appassets.androidplatform.net/index.html', runScripts: 'outside-only'}); opened.push(dom);
  const w = dom.window, calls = [], responses = [];
  w.matchMedia = query => ({matches:!!options.mobile && query.includes('max-width'), addEventListener(){}});
  if (options.drafts) for (const [key,value] of Object.entries(options.drafts)) w.localStorage.setItem(key,value);
  w.HTMLDialogElement.prototype.showModal = function(){this.setAttribute('open','');};
  w.HTMLDialogElement.prototype.close = function(){this.removeAttribute('open');this.dispatchEvent(new w.Event('close'));};
  w.confirm = options.confirm || (() => true);
  const snapshot = {ready:true,busy:false,permissions:'workspace-write',models:[],messages:[],sessions:[],account:{type:'chatgpt',email:'me@example.test'},workspace:{selected:true,name:'Project'},cwd:'/test/project',threadId:'t',status:'연결됨'};
  w.Native = {postMessage(raw){ const m=JSON.parse(raw); calls.push(m); queueMicrotask(async () => {
    if(m.action==='rpc.respond') responses.push(m.args);
    try {
      const result=overrides[m.action] ? await overrides[m.action](m) : m.action==='state' ? snapshot : m.action==='rpc' ? {data:[],marketplaces:[]} : {};
      w.mobileCodexEvent('response',{id:m.id,result});
    } catch(error) { w.mobileCodexEvent('response',{id:m.id,error:error.message}); }
  });}};
  w.Native.locale = () => JSON.stringify({choice:options.language || 'ko',systemLanguage:options.systemLanguage || 'ko'});
  w.eval(fs.readFileSync(root+'translations.js','utf8')); w.eval(fs.readFileSync(root+'locale.js','utf8'));
  w.eval(fs.readFileSync(root+'ui-core.js','utf8')); w.eval(fs.readFileSync(root+'app.js','utf8'));
  return {w,calls,responses,snapshot};
}
test('login URLs reject non-OpenAI hosts and embedded credentials',()=>{
 assert.equal(C.safeLoginUrl('https://auth.openai.com/codex/device'),true);
 for(const url of ['javascript:alert(1)','http://auth.openai.com','https://auth.openai.com.evil.test','https://user:pass@auth.openai.com'])assert.equal(C.safeLoginUrl(url),false);
});
test('streaming reuses message identity',()=>{const m=[];C.appendDelta(m,'one','가');C.appendDelta(m,'one','나');assert.equal(m.length,1);assert.equal(m[0].text,'가나');});
test('every wired control exists and initial state renders safely',async()=>{
 const {w,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'u',role:'user',text:'<img src=x onerror=alert(1)>'},{id:'a',role:'assistant',text:'```html\n<script>alert(1)</script>\n```'}]});
 assert.equal(w.document.querySelectorAll('#messages img:not(.chat-character), #messages script').length,0);
 assert.match(w.document.getElementById('messages').textContent,/<script>/);
 assert.equal(w.document.getElementById('project-label').textContent,'Project');
});
test('submit is cancelled synchronously and calls native with chosen model and effort',async()=>{
 const {w,calls}=setup();await tick();w.document.getElementById('prompt').value='실제 파일 수정';
 const e=new w.Event('submit',{cancelable:true});w.document.getElementById('composer').dispatchEvent(e);
 assert.equal(e.defaultPrevented,true);await tick();
 assert.equal(calls.find(m=>m.action==='chat.send').args.text,'실제 파일 수정');
});
test('approval UI returns original key and acceptance, never silently auto-approves',async()=>{
 const {w,responses}=setup();await tick();w.mobileCodexEvent('server.request',{key:'k',method:'item/commandExecution/requestApproval',params:{command:'pwd'}});
 assert.equal(responses.length,0);
 const approve=[...w.document.querySelectorAll('#request-actions button')].find(b=>b.textContent==='허용');approve.click();await tick();
 assert.deepEqual(responses[0],{key:'k',result:{decision:'accept'}});
});
test('question UI preserves stable question ids and answer array',async()=>{
 const {w,responses}=setup();await tick();w.mobileCodexEvent('server.request',{key:'q',method:'item/tool/requestUserInput',params:{questions:[{id:'lang',question:'언어는?',options:[{label:'Java'}]}]}});
 w.document.querySelector('#request-fields input').value='Java';w.document.querySelector('#request-actions button').click();await tick();
 assert.deepEqual(responses[0].result,{answers:{lang:{answers:['Java']}}});
});
test('plugin, skill, MCP screens query real protocol routes',async()=>{
 const {w,calls}=setup();await tick();w.document.getElementById('show-tools').click();await tick();w.document.getElementById('show-tools-panel').click();await tick();await tick();
 const routes=calls.filter(m=>m.action==='rpc').map(m=>m.args.method);
 assert.deepEqual(routes,['plugin/list','skills/list','mcpServerStatus/list']);
});
test('tool refresh keeps a prior category list when one protocol request fails',async()=>{
 let pluginCalls=0;
 const {w}=setup({'rpc':m=>{
   if(m.args.method==='plugin/list') { pluginCalls++; if(pluginCalls > 1) throw new Error('transport closed'); return {marketplaces:[{name:'local',plugins:[{name:'Keep me',id:'keep'}]}]}; }
   if(m.args.method==='skills/list') return {data:[]};
   return {data:[]};
 }});await tick();w.document.getElementById('show-tools').click();await tick();w.document.getElementById('show-tools-panel').click();await tick();await tick();
 assert.match(w.document.getElementById('tools-list').textContent,/Keep me/);
 w.document.getElementById('refresh-tools').click();await tick();await tick();
 assert.match(w.document.getElementById('tools-list').textContent,/이전 목록을 표시합니다/);
 assert.match(w.document.getElementById('tools-list').textContent,/Keep me/);
});
test('tool recovery action keeps protocol failures handled after menu relocation',async()=>{
 const {w}=setup({'recovery.list':()=>{throw new Error('recovery offline');}});let unhandled=0;
 w.addEventListener('unhandledrejection',()=>unhandled++);await tick();
 w.document.getElementById('show-tools').click();await tick();w.document.getElementById('show-recovery').click();await tick();await tick();
 assert.match(w.document.getElementById('toast').textContent,/recovery offline/);assert.equal(unhandled,0);
});
test('account usage reads the app-server snapshot and prefers multi-bucket limits',async()=>{
 const {w,calls}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?{rateLimits:{primary:{usedPercent:1}},rateLimitsByLimitId:{codex:{limitName:'Codex',primary:{usedPercent:42,windowDurationMins:300,resetsAt:2000000000}}}}:{data:[]}});await tick();
 w.document.querySelector('[data-settings-tab="account"]').click();await tick();await tick();
 assert.equal(calls.filter(c=>c.action==='rpc'&&c.args.method==='account/rateLimits/read').length,1);
 const text=w.document.getElementById('usage-limits').textContent;
 assert.match(text,/Codex/);assert.match(text,/남은 58%/);assert.match(text,/5시간/);
});

test('usage never turns missing limits into zero and drops a response from a previous account',async()=>{
 let finish, delayed=false;
 const {w,snapshot}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?(delayed?new Promise(r=>finish=r):{rateLimits:{primary:{usedPercent:null}}}):{data:[]}});await tick();
 w.document.getElementById('usage-refresh').click();await tick();await tick();
 assert.equal(w.document.getElementById('usage-limits').textContent,'');
 assert.match(w.document.getElementById('usage-status').textContent,/제공/);
 delayed=true;w.document.getElementById('usage-refresh').click();await tick();
 w.mobileCodexEvent('state',{...snapshot,account:{}});
 finish({rateLimits:{primary:{usedPercent:42}}});await tick();await tick();
 assert.equal(w.document.getElementById('usage-limits').textContent,'');
 assert.match(w.document.getElementById('usage-status').textContent,/계정이 바뀌/);
});

test('a different project cannot inherit the previous project tool cache after a failure',async()=>{
 let offline=false;
 const {w,snapshot}=setup({'rpc':m=>{if(offline)throw new Error('offline');return m.args.method==='skills/list'?{data:[{skills:[{name:'project-only-skill',description:'local',path:'/first/SKILL.md'}]}]}:{data:[],marketplaces:[]};}});await tick();
  w.document.getElementById('show-tools').click();await tick();w.document.getElementById('show-tools-panel').click();await tick();await tick();
 assert.match(w.document.getElementById('tools-list').textContent,/project-only-skill/);
 offline=true;w.mobileCodexEvent('state',{...snapshot,cwd:'/second',threadId:'second'});
 w.document.getElementById('refresh-tools').click();await tick();await tick();
 assert.doesNotMatch(w.document.getElementById('tools-list').textContent,/project-only-skill/);
});

test('MCP forms collect typed answers without asking users to write JSON',async()=>{
 const {w,responses}=setup();await tick();
 w.mobileCodexEvent('server.request',{key:'mcp',method:'mcpServer/elicitation/request',params:{serverName:'demo',mode:'form',message:'환경 선택',requestedSchema:{type:'object',required:['environment'],properties:{environment:{type:'string',enum:['dev','prod']},retries:{type:'integer',minimum:0},enabled:{type:'boolean',default:true}}}}});
 assert.equal(w.document.querySelectorAll('#request-fields textarea').length,0);
 w.document.querySelector('#request-fields select').value='0';w.document.querySelector('#request-fields input[type=number]').value='3';
 [...w.document.querySelectorAll('#request-actions button')].find(b=>b.textContent==='전송').click();await tick();
 assert.deepEqual(responses[0].result,{action:'accept',content:{environment:'dev',retries:3,enabled:true}});
});

test('composer survives keyboard resize, grows with content and preserves input focus',async()=>{
 const {w}=setup();await tick();const d=w.document,prompt=d.getElementById('prompt');
 Object.defineProperty(prompt,'scrollHeight',{configurable:true,get:()=>300});
 prompt.value='긴 요청\n두 번째 줄';prompt.focus();prompt.dispatchEvent(new w.Event('input'));
 assert.equal(prompt.style.height,'160px');
 Object.defineProperty(w,'innerHeight',{value:320,configurable:true});w.dispatchEvent(new w.Event('resize'));await tick();
 assert.equal(d.documentElement.style.getPropertyValue('--app-height'),'320px');
 assert.equal(prompt.style.height,'76.8px');assert.equal(d.activeElement,prompt);assert.equal(prompt.value,'긴 요청\n두 번째 줄');
 Object.defineProperty(w,'innerHeight',{value:800});w.dispatchEvent(new w.Event('resize'));
 assert.equal(d.documentElement.style.getPropertyValue('--app-height'),'800px');
});
test('drafts restore separately for each thread and remain after sending fails',async()=>{
 const key='draft:'+JSON.stringify(['/test/project','t']);
 const {w,snapshot}=setup({'chat.send':()=>{throw new Error('offline');}},{drafts:{[key]:'작성하던 요청'}});await tick();
 const prompt=w.document.getElementById('prompt');assert.equal(prompt.value,'작성하던 요청');
 w.mobileCodexEvent('state',{...snapshot,threadId:'second'});assert.equal(prompt.value,'');
 prompt.value='다른 대화';prompt.dispatchEvent(new w.Event('input'));
 w.mobileCodexEvent('state',snapshot);assert.equal(prompt.value,'작성하던 요청');
 w.document.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 assert.equal(prompt.value,'작성하던 요청');assert.equal(w.localStorage.getItem(key),'작성하던 요청');
});
test('a send acknowledgement never erases newer text and duplicate submits are ignored',async()=>{
 let finish;const {w,calls}=setup({'chat.send':()=>new Promise(resolve=>finish=resolve)});await tick();
 const d=w.document,p=d.getElementById('prompt');p.value='보낼 내용';p.dispatchEvent(new w.Event('input'));
 d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 p.value='다음 요청 작성 중';p.dispatchEvent(new w.Event('input'));finish({});await tick();
 assert.equal(calls.filter(c=>c.action==='chat.send').length,1);assert.equal(p.value,'다음 요청 작성 중');
});
test('a first send clears only its transferred draft, without losing the new thread',async()=>{
 let finish;const {w,snapshot}=setup({'chat.send':()=>new Promise(resolve=>finish=resolve)});await tick();
 w.mobileCodexEvent('state',{...snapshot,threadId:''});const d=w.document,p=d.getElementById('prompt');
 p.value='첫 요청';p.dispatchEvent(new w.Event('input'));d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 w.mobileCodexEvent('state',{...snapshot,threadId:'created',busy:true});finish({});await tick();
 assert.equal(p.value,'');assert.equal(w.localStorage.getItem('draft:'+JSON.stringify(['/test/project','new'])),null);
 assert.equal(w.localStorage.getItem('draft:'+JSON.stringify(['/test/project','created'])),null);
});
test('reading older messages stops streaming auto-scroll until latest is requested',async()=>{
 const {w,snapshot}=setup();await tick();const area=w.document.getElementById('chat-scroll');
 Object.defineProperties(area,{scrollHeight:{value:1800},clientHeight:{value:500}});
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'a',role:'assistant',text:'이전 답변'}]});
 area.scrollTop=100;area.dispatchEvent(new w.Event('scroll'));
 w.mobileCodexEvent('message.delta',{id:'a',delta:' 새 내용'});assert.equal(area.scrollTop,100);
 assert.equal(w.document.getElementById('jump-latest').hidden,false);
 w.document.getElementById('jump-latest').click();await tick();assert.equal(area.scrollTop,1800);
});
test('mobile settings expose full model, reasoning and permission controls',async()=>{
 const {w,snapshot,calls}=setup({}, {mobile:true});await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,models:[{id:'model-long',model:'model-long',displayName:'A model with a long name',supportedReasoningEfforts:[{reasoningEffort:'high'}]}]});
 d.getElementById('composer-options').click();await tick();assert.equal(d.getElementById('options-dialog').open,true);
 const model=d.getElementById('model');model.value='model-long';model.dispatchEvent(new w.Event('change'));await tick();
 d.getElementById('effort').value='high';d.getElementById('permissions').value='danger-full-access';d.getElementById('permissions').dispatchEvent(new w.Event('change'));await tick();
 assert.equal(d.getElementById('model-summary').textContent,'A model with a long name');
 assert.equal(calls.find(c=>c.action==='permissions.set').args.mode,'danger-full-access');
 assert.equal(d.getElementById('effort').value,'high');
});
test('back closes the actual top dialog, then the mobile drawer, then yields to Android',async()=>{
 const {w}=setup({}, {mobile:true});await tick();const d=w.document;
  d.getElementById('show-tools').click();await tick();d.getElementById('show-tools-panel').click();await tick();await tick();d.getElementById('marketplace-add').click();await tick();
 assert.equal(d.getElementById('input-dialog').open,true);assert.equal(w.mobileCodexBack(),true);
 assert.equal(d.getElementById('input-dialog').open,false);assert.equal(d.getElementById('tools-dialog').open,true);
 w.mobileCodexBack();d.querySelector('.topbar .sidebar-toggle').click();assert.equal(d.querySelector('main').inert,true);
 w.mobileCodexBack();assert.equal(d.querySelector('main').inert,false);assert.equal(w.mobileCodexBack(),false);
});
test('file management uses labelled actions and protects unsaved editor changes',async()=>{
 const {w,calls}=setup({'files.list':()=>({entries:[{name:'test.md',path:'test.md',size:3}]}),'files.read':()=>({path:'test.md',content:'old',sha256:'hash'})});await tick();const d=w.document;
 d.getElementById('files-toggle').click();await tick();d.querySelector('.file-entry .icon-button').click();await tick();
 assert.deepEqual([...d.querySelectorAll('#file-actions button')].map(b=>b.textContent),['이름 변경','이동','삭제']);
 d.querySelector('#file-actions button').click();await tick();d.getElementById('input-value').value='new.md';d.getElementById('input-confirm').click();await tick();await tick();
 assert.deepEqual(calls.find(c=>c.action==='files.mutate').args,{operation:'mobile_rename',arguments:{path:'test.md',name:'new.md'}});
 d.querySelector('.file-row').click();await tick();d.getElementById('editor').value='unsaved';w.confirm=()=>false;w.mobileCodexBack();assert.equal(d.getElementById('editor-dialog').open,true);
});
test('generated images appear, open full-screen, export and survive a snapshot refresh',async()=>{
 const {w,snapshot,calls}=setup();await tick();const d=w.document,id='a'.repeat(64),image={id,url:'/images/'+id,name:'robot.png',width:1024,height:1024};
 const message={id:'image-call',role:'assistant',text:'',imageStatus:'completed',images:[image]};
 w.mobileCodexEvent('state',{...snapshot,messages:[message]});assert.equal(d.querySelector('#messages img:not(.chat-character)').getAttribute('src'),image.url);
 d.querySelector('.image-open').click();await tick();assert.equal(d.getElementById('image-dialog').open,true);
 d.getElementById('image-save').click();await tick();assert.deepEqual(calls.find(c=>c.action==='images.export').args,{id,name:'robot.png'});
 w.mobileCodexEvent('state',{...snapshot,messages:[message]});assert.equal(d.querySelectorAll('#messages img:not(.chat-character)').length,1);
});
test('local Markdown images resolve via native, while arbitrary image URLs do not load',async()=>{
 const image={id:'b'.repeat(64),url:'/images/'+'b'.repeat(64),name:'local.png'};
 const {w,snapshot,calls}=setup({'images.read':()=>image});await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'a',role:'assistant',text:'- **결과** `test.md`\n\n![로봇](sandbox:/output/robot.png)\n\n![bad](https://evil.test/pixel)'}]});await tick();
 assert.equal(w.document.querySelectorAll('#messages img:not(.chat-character)').length,1);assert.equal(w.document.querySelector('#messages strong').textContent,'결과');
 assert.equal(w.document.querySelector('#messages code').textContent,'test.md');assert.equal(calls.filter(c=>c.action==='images.read').length,1);
 assert.equal(C.safeImageUrl('javascript:alert(1)'),false);assert.equal(C.safeImageUrl('/images/../../auth.json'),false);
});
test('image generation failures are visible instead of an empty successful message',async()=>{
 const {w,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'image',role:'assistant',text:'',imageStatus:'failed',imageError:'이미지 사용 한도를 확인해 주세요.',images:[]}]});
 assert.match(w.document.querySelector('.image-error').textContent,/한도/);assert.equal(w.document.querySelectorAll('#messages img:not(.chat-character)').length,0);
});

test('separate image tool results from one turn form a gallery with correct navigation and saving',async()=>{
 const {w,snapshot,calls}=setup();await tick();const d=w.document;
 const attachments=['a','b','c'].map((char,i)=>({id:char.repeat(64),url:'/images/'+char.repeat(64),name:'image-'+(i+1)+'.png'}));
 const messages=attachments.map((image,i)=>({id:'call-'+i,role:'assistant',kind:'image',imageGroup:'turn-one',text:'',images:[image],imageStatus:'completed'}));
 w.mobileCodexEvent('state',{...snapshot,messages});
 assert.equal(d.querySelectorAll('#messages .image-gallery').length,1);assert.equal(d.querySelectorAll('#messages .image-gallery img').length,3);
 d.querySelectorAll('#messages .image-open')[1].click();await tick();assert.equal(d.getElementById('image-counter').textContent,'2 / 3');
 d.getElementById('image-next').click();await tick();assert.equal(d.getElementById('image-counter').textContent,'3 / 3');assert.equal(d.getElementById('image-next').disabled,true);
 d.getElementById('image-save').click();await tick();assert.equal(calls.find(c=>c.action==='images.export').args.id,attachments[2].id);
 d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowLeft'}));assert.equal(d.getElementById('image-counter').textContent,'2 / 3');
 d.querySelector('.image-thumbnail').click();await tick();assert.equal(d.getElementById('image-counter').textContent,'1 / 3');assert.equal(d.getElementById('image-prev').disabled,true);
});
test('a gallery keeps partial successes, later images and failures without grouping other turns',async()=>{
 const image={id:'a'.repeat(64),url:'/images/'+'a'.repeat(64),name:'a.png'};
 const {w,snapshot}=setup();await tick();const d=w.document;
 const first={id:'a',role:'assistant',kind:'image',imageGroup:'one',images:[image],imageStatus:'completed'};
 const pending={id:'b',role:'assistant',kind:'image',imageGroup:'one',imageStatus:'generating'};
 w.mobileCodexEvent('state',{...snapshot,messages:[first,pending]});assert.match(d.querySelector('.image-placeholder').textContent,/생성 중/);
 d.querySelector('.image-open').click();await tick();
 const second={...pending,images:[{id:'b'.repeat(64),url:'/images/'+'b'.repeat(64),name:'b.png'}],imageStatus:'completed'};
 w.mobileCodexEvent('state',{...snapshot,messages:[first,second,{id:'c',role:'assistant',kind:'image',imageGroup:'two',images:[image],imageStatus:'completed'}]});
 assert.equal(d.getElementById('image-counter').textContent,'1 / 2');assert.equal(d.querySelectorAll('#messages .image-gallery').length,2);
 w.mobileCodexEvent('state',{...snapshot,messages:[first,{...pending,imageStatus:'failed',imageError:'두 번째 이미지 실패'}]});
 assert.equal(d.querySelectorAll('#messages img:not(.chat-character)').length,1);assert.match(d.querySelector('.image-error').textContent,/두 번째/);
});
test('a horizontal swipe changes images but zoomed panning does not',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 const images=['a','b'].map(s=>({id:s.repeat(64),url:'/images/'+s.repeat(64),name:s+'.png'}));
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'images',role:'assistant',images}]});d.querySelector('.image-open').click();await tick();
 function swipe(){const start=new w.Event('touchstart');Object.defineProperty(start,'touches',{value:[{clientX:250,clientY:100}]});d.getElementById('image-stage').dispatchEvent(start);const end=new w.Event('touchend');Object.defineProperty(end,'changedTouches',{value:[{clientX:60,clientY:110}]});d.getElementById('image-stage').dispatchEvent(end);}
 swipe();assert.equal(d.getElementById('image-counter').textContent,'2 / 2');d.getElementById('image-prev').click();await tick();d.getElementById('image-zoom').click();await tick();swipe();assert.equal(d.getElementById('image-counter').textContent,'1 / 2');
});

test('project tree groups workspace history while general history stays separate',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'p1',name:'Alpha'},projects:[{key:'p1',name:'Alpha',selected:true,available:true}],sessions:[{id:'a',title:'프로젝트 대화',workspaceKey:'p1'},{id:'g',title:'일반 대화'}]});
 const tree=w.document.querySelector('.project-tree');assert.match(tree.textContent,/프로젝트 대화/);assert.doesNotMatch(w.document.getElementById('sessions').textContent,/프로젝트 대화/);assert.match(w.document.getElementById('sessions').textContent,/일반 대화/);
 tree.querySelector('.tree-toggle').click();await tick();assert.equal(tree.classList.contains('collapsed'),true);
 w.document.querySelector('.general-project .project-button').click();await tick();assert.deepEqual(calls.find(c=>c.action==='projects.select').args,{key:''});
});

test('general and workspace drafts keep attachments separately and picker receipts survive duplicate delivery',async()=>{
 const generalKey='draft:'+C.draftKey('', 't'), projectKey='draft:'+C.draftKey('p1','t');
 const {w,snapshot,calls}=setup({}, {drafts:{[generalKey]:'일반 초안',[generalKey+':context']:JSON.stringify({attachments:[{id:'g1',name:'general.txt'}],mentions:[],skills:[]}),[projectKey]:'프로젝트 초안'}});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:false,key:'',name:'일반'},threadId:'t'});assert.equal(w.document.getElementById('prompt').value,'일반 초안');assert.match(w.document.getElementById('draft-context').textContent,/general.txt/);
 w.mobileCodexEvent('attachments.picked',{receiptId:'r1',draftKey:projectKey.slice(6),attachments:[{id:'p1',name:'project.txt'}],errors:[]});await tick();
 w.mobileCodexEvent('attachments.picked',{receiptId:'r1',draftKey:projectKey.slice(6),attachments:[{id:'p1',name:'project.txt'}],errors:[]});await tick();
 assert.match(w.localStorage.getItem(projectKey+':context'),/project.txt/);assert.equal(calls.filter(c=>c.action==='attachments.ack').length,1);
});

test('picker cancellation and partial errors preserve the draft while accepted attachments are sent',async()=>{
 const {w,calls}=setup({'attachments.pick':()=>({cancelled:false,attachments:[{id:'a1',name:'ok.txt'}],errors:['bad.bin을 읽지 못했습니다.']})});await tick();const d=w.document;
 d.getElementById('add-attachment').click();await tick();assert.match(d.getElementById('draft-context').textContent,/ok.txt/);
 d.getElementById('prompt').value='첨부 전송';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 assert.deepEqual(calls.find(c=>c.action==='chat.send').args.attachments,['a1']);
});

test('autocomplete resolves actual file and app mentions plus structured skills in send payload',async()=>{
 const {w,calls}=setup({'files.list':()=>({entries:[{name:'README.md',path:'README.md'}]}),'files.mention':()=>({name:'README.md',path:'README.md'}),'rpc':m=>m.args.method==='app/list'?{data:[{id:'calendar',name:'Calendar',isAccessible:true,isEnabled:true},{id:'off',name:'Off',isAccessible:false,isEnabled:true}]}:m.args.method==='skills/list'?{data:[{skills:[{name:'review',path:'/skills/review'},{name:'hidden',path:'/skills/hidden',enabled:false}]}]}:{}});await tick();const d=w.document,p=d.getElementById('prompt');
 p.value='@';p.selectionStart=p.selectionEnd=p.value.length;p.dispatchEvent(new w.Event('input'));await new Promise(r=>setTimeout(r,150));assert.match(d.getElementById('autocomplete').textContent,/README/);assert.match(d.getElementById('autocomplete').textContent,/Calendar/);assert.doesNotMatch(d.getElementById('autocomplete').textContent,/Off/);d.querySelector('.autocomplete-item').click();await tick();assert.equal(p.value,'@README.md ');
 p.value='$re';p.selectionStart=p.selectionEnd=p.value.length;p.dispatchEvent(new w.Event('input'));await new Promise(r=>setTimeout(r,150));assert.doesNotMatch(d.getElementById('autocomplete').textContent,/hidden/);d.querySelector('.autocomplete-item').click();await tick();assert.equal(p.value,'$review ');
 p.value='검토';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();const sent=calls.find(c=>c.action==='chat.send').args;
 assert.deepEqual(sent.mentions,[{name:'README.md',path:'README.md'}]);assert.deepEqual(sent.skills,[{name:'review',path:'/skills/review'}]);
});

test('picker result lands in its original workspace draft after the user switches projects',async()=>{
 let finish;const {w,snapshot}=setup({'attachments.pick':()=>new Promise(resolve=>finish=resolve)});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'one',name:'One'},threadId:'t'});w.document.getElementById('add-attachment').click();await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'two',name:'Two'},threadId:'t'});finish({receiptId:'switch',draftKey:C.draftKey('one','t'),attachments:[{id:'one-file',name:'one.txt'}],errors:[]});await tick();
 assert.equal(w.document.getElementById('draft-context').hidden,true);assert.match(w.localStorage.getItem('draft:'+C.draftKey('one','t')+':context'),/one.txt/);
});

test('stale autocomplete results and delayed file mentions cannot leak into a switched workspace',async()=>{
 let resolveSearch,resolveMention;const {w,snapshot}=setup({'files.list':()=>new Promise(resolve=>resolveSearch=resolve),'rpc':()=>({data:[]}), 'files.mention':()=>new Promise(resolve=>resolveMention=resolve)});await tick();const p=w.document.getElementById('prompt');
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'one',name:'One'},threadId:'t'});p.value='@';p.selectionStart=p.selectionEnd=1;p.dispatchEvent(new w.Event('input'));await new Promise(r=>setTimeout(r,140));
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'two',name:'Two'},threadId:'t'});resolveSearch({entries:[{name:'one.md',path:'one.md'}]});await tick();assert.equal(w.document.getElementById('autocomplete').hidden,true);
 // A result already chosen before a switch must also be discarded when files.mention returns late.
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'one',name:'One'},threadId:'t'});p.value='@';p.selectionStart=p.selectionEnd=1;p.dispatchEvent(new w.Event('input'));await new Promise(r=>setTimeout(r,140));
 resolveSearch({entries:[{name:'one.md',path:'one.md'}]});await tick();w.document.querySelector('.autocomplete-item').click();await tick();w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'two',name:'Two'},threadId:'t'});resolveMention({name:'one.md',path:'one.md'});await tick();assert.equal(w.document.getElementById('draft-context').hidden,true);
});

test('a successful send preserves a newer draft and its attachment context',async()=>{
 let finish;const {w}=setup({'chat.send':()=>new Promise(resolve=>finish=resolve)});await tick();const d=w.document,p=d.getElementById('prompt');
 w.mobileCodexEvent('attachments.picked',{receiptId:'old',draftKey:C.draftKey('/test/project','t'),attachments:[{id:'old',name:'old.txt'}],errors:[]});await tick();p.value='first';p.dispatchEvent(new w.Event('input'));d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();p.value='second';p.dispatchEvent(new w.Event('input'));finish({});await tick();
 assert.equal(p.value,'second');assert.match(d.getElementById('draft-context').textContent,/old.txt/);
});

test('permission radio values remain synchronized with the native permission command',async()=>{
 const {w,calls,snapshot}=setup({}, {mobile:true});await tick();w.mobileCodexEvent('state',{...snapshot,permissions:'read-only'});const d=w.document;
 assert.equal(d.querySelector('input[name=permission][value="read-only"]').checked,true);d.querySelector('input[name=permission][value="danger-full-access"]').click();await tick();
 assert.equal(d.getElementById('permissions').value,'danger-full-access');assert.deepEqual(calls.find(c=>c.action==='permissions.set').args,{mode:'danger-full-access'});
});

test('adding a project never rebinds the selected project while reconnect targets its stable key',async()=>{
 const {w,calls,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'a',name:'A'},projects:[{key:'a',name:'A',selected:true,available:true},{key:'b',name:'B',available:false}]});const d=w.document;
 d.getElementById('add-project').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='files.pick').at(-1).args,{});
 [...d.querySelectorAll('.project-tree')].at(-1).querySelector('.new-thread').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='files.pick').at(-1).args,{projectKey:'b'});
});

test('missing folder permission does not hide saved project conversations',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,ready:false,account:{},workspace:{selected:true,key:'lost',name:'Lost',available:false},projects:[{key:'lost',name:'Lost',available:false}],sessions:[{id:'saved',title:'권한 없어도 읽는 기록',workspaceKey:'lost'}]});
 const row=w.document.querySelector('.project-tree .session');assert.ok(row);row.click();await tick();
 assert.deepEqual(calls.find(c=>c.action==='chat.resume').args,{id:'saved'});
 assert.equal(calls.some(c=>c.action==='runtime.start'||c.action==='auth.login'),false);
});

test('attachments picked during send survive acknowledgement even when text is unchanged',async()=>{
 let finish;const {w}=setup({'chat.send':()=>new Promise(resolve=>finish=resolve)});await tick();const d=w.document;
 const receipt=(id)=>w.mobileCodexEvent('attachments.picked',{receiptId:id,draftKey:C.draftKey('/test/project','t'),attachments:[{id,name:id+'.txt'}],errors:[]});
 receipt('sent');await tick();d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 receipt('new');await tick();finish({});await tick();
 assert.match(d.getElementById('draft-context').textContent,/new.txt/);assert.doesNotMatch(d.getElementById('draft-context').textContent,/sent.txt/);
});

test('accepted first send transfers model options into its new conversation scope',async()=>{
 let finish;const {w,snapshot}=setup({'chat.send':()=>new Promise(resolve=>finish=resolve)});await tick();const d=w.document;
 const models=[{id:'model-a',supportedReasoningEfforts:[{reasoningEffort:'high'}]}];
 w.mobileCodexEvent('state',{...snapshot,workspace:{key:'p',selected:true},threadId:'',models});
 d.getElementById('model').value='model-a';d.getElementById('model').dispatchEvent(new w.Event('change'));await tick();
 d.getElementById('effort').value='high';d.getElementById('effort').dispatchEvent(new w.Event('change'));await tick();
 d.getElementById('prompt').value='start';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{key:'p',selected:true},threadId:'created',models});finish({});await tick();
 assert.equal(d.getElementById('model').value,'model-a');assert.equal(d.getElementById('effort').value,'high');
 assert.match(w.localStorage.getItem('draft:'+C.draftKey('p','created')+':options'),/model-a/);
});


test('bare @ lists local files immediately while app discovery is pending and can browse folders',async()=>{
 const {w,calls}=setup({'files.list':m=>({entries:m.args.path==='src'?[{name:'Main.java',path:'src/Main.java'}]:[{name:'src',path:'src',directory:true},{name:'README.md',path:'README.md'}]}),'files.search':()=>{throw new Error('blank search must never run');},'rpc':()=>new Promise(()=>{})});await tick();
 const d=w.document,p=d.getElementById('prompt');p.value='@';p.dispatchEvent(new w.Event('input'));await tick();
 assert.equal(d.getElementById('autocomplete').hidden,false);assert.match(d.getElementById('autocomplete').textContent,/README.md/);assert.match(d.getElementById('autocomplete').textContent,/앱을 불러오는 중/);assert.equal(calls.some(c=>c.action==='files.search'),false);
 [...d.querySelectorAll('.autocomplete-item')].find(b=>b.textContent.includes('src/')).click();await tick();assert.match(d.getElementById('autocomplete').textContent,/Main.java/);assert.equal(calls.filter(c=>c.action==='files.list').at(-1).args.path,'src');
});
test('bare $ lists every enabled skill with descriptions and exposes empty or failed loading',async()=>{
 let response={data:[{skills:Array.from({length:16},(_,i)=>({name:'skill'+i,path:'/skills/'+i,description:'작업 '+i}))}]};
 const {w}=setup({'rpc':()=>{if(response instanceof Error)throw response;return response;}});await tick();const d=w.document,p=d.getElementById('prompt');
 const type=()=>{p.value='$';p.dispatchEvent(new w.Event('input'));};type();await tick();assert.match(d.getElementById('autocomplete').textContent,/skill15/);assert.match(d.getElementById('autocomplete').textContent,/작업 15/);
 response={data:[]};type();await tick();assert.equal(d.getElementById('autocomplete').hidden,false);assert.match(d.getElementById('autocomplete').textContent,/사용 가능한 스킬이 없습니다/);assert.match(d.getElementById('autocomplete').textContent,/스킬 가져오기/);
 response=new Error('offline');type();await tick();assert.match(d.getElementById('autocomplete').textContent,/offline/);assert.match(d.getElementById('autocomplete').textContent,/다시 불러오기/);
});
test('erasing trigger invalidates a late result and composition Enter does not choose a skill',async()=>{
 let finish;const {w}=setup({'rpc':()=>new Promise(resolve=>finish=resolve)});await tick();const d=w.document,p=d.getElementById('prompt');
 p.value='$';p.dispatchEvent(new w.Event('input'));await tick();p.value='plain';p.dispatchEvent(new w.Event('input'));finish({data:[{skills:[{name:'late',path:'/late'}]}]});await tick();assert.equal(d.getElementById('autocomplete').hidden,true);
 p.value='$';p.dispatchEvent(new w.Event('input'));await tick();finish({data:[{skills:[{name:'review',path:'/review'}]}]});await tick();p.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Enter',isComposing:true,cancelable:true}));assert.equal(p.value,'$');
});
test('personal instructions load offline, retain failed saves and can be cleared',async()=>{
 let failed=true;const {w,calls}=setup({'instructions.read':()=>({content:'한국어로 답변',activePath:'/private/.codex/AGENTS.md'}),'instructions.save':()=>{if(failed)throw new Error('disk full');return {ok:true};}});await tick();const d=w.document;
 d.querySelector('[data-settings-tab="personal"]').click();await tick();assert.equal(d.getElementById('instructions-editor').value,'한국어로 답변');assert.match(d.getElementById('instructions-path').textContent,/\.codex\/AGENTS.md/);assert.equal(calls.some(c=>c.action==='runtime.start'),false);
 d.getElementById('instructions-editor').value='새 지침';d.getElementById('instructions-save').click();await tick();assert.equal(d.getElementById('instructions-editor').value,'새 지침');assert.match(d.getElementById('instructions-status').textContent,/disk full/);
 failed=false;d.getElementById('instructions-editor').value='';d.getElementById('instructions-save').click();await tick();assert.equal(calls.filter(c=>c.action==='instructions.save').at(-1).args.content,'');assert.match(d.getElementById('instructions-status').textContent,/저장했습니다/);
});
test('personal instructions preserve unsaved edits on cancel and explain an active override',async()=>{
 const {w}=setup({'instructions.read':()=>({content:'override',activePath:'/private/.codex/AGENTS.override.md',notice:'AGENTS.override.md가 우선 적용됩니다.'})});await tick();const d=w.document;
 d.getElementById('settings').click();await tick();d.querySelector('[data-settings-tab="personal"]').click();await tick();assert.match(d.getElementById('instructions-status').textContent,/우선 적용/);
 d.getElementById('instructions-editor').value='작성 중';w.confirm=()=>false;d.querySelector('[data-close="settings-dialog"]').click();assert.equal(d.getElementById('settings-dialog').open,true);assert.equal(d.getElementById('instructions-editor').value,'작성 중');
});

test('clearing an override shows the newly effective base instructions without lying about disabling both',async()=>{
 const {w}=setup({'instructions.read':()=>({content:'override',path:'/home/.codex/AGENTS.override.md'}),'instructions.save':()=>({content:'base rules',activePath:'/home/.codex/AGENTS.md',notice:'AGENTS.md가 다시 활성화됩니다.'})});await tick();const d=w.document;
 d.querySelector('[data-settings-tab="personal"]').click();await tick();d.getElementById('instructions-editor').value='';d.getElementById('instructions-save').click();await tick();
 assert.equal(d.getElementById('instructions-editor').value,'base rules');assert.match(d.getElementById('instructions-path').textContent,/AGENTS.md$/);assert.match(d.getElementById('instructions-status').textContent,/다시 활성화/);
});


test('character switch hides stickers immediately, retains generated images, and persists across reload',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document, toggle=d.getElementById('chat-icons-toggle');
 const state={...snapshot,messages:[{id:'u',role:'user',text:'만들어줘'},{id:'a',role:'assistant',text:'완료했어요.',images:[{id:'a'.repeat(64),url:'/images/'+'a'.repeat(64),name:'result.png'}]}]};w.mobileCodexEvent('state',state);
 assert.equal(toggle.checked,true);assert.equal(d.querySelectorAll('.message.user .chat-character').length,0);assert.match(d.querySelector('.message.assistant .chat-character').src,/06-done.png$/);
 toggle.checked=false;toggle.dispatchEvent(new w.Event('change'));await tick();assert.equal(d.querySelectorAll('.chat-character').length,0);assert.equal(d.querySelectorAll('.image-gallery img').length,1);assert.match(d.getElementById('messages').textContent,/완료했어요/);
 const next=setup({}, {drafts:{'chat-icons':w.localStorage.getItem('chat-icons')}});await tick();next.w.mobileCodexEvent('state',state);assert.equal(next.w.document.querySelectorAll('.chat-character').length,0);assert.equal(next.w.document.getElementById('chat-icons-toggle').checked,false);
 toggle.checked=true;toggle.dispatchEvent(new w.Event('change'));await tick();assert.equal(d.querySelectorAll('.message.assistant .chat-character').length,1);
});
test('character packs hydrate from initial RPC, select, and refresh the same id when icon URLs change',async()=>{
 let revision=0; const packState=()=>({folderName:'Packs',folderConfigured:true,selectedPackId:'custom',packs:[{id:'builtin',name:'Builtin',valid:true,icons:{}},{id:'custom',name:'English pack',valid:true,icons:{done:`/packs/done-${revision}.png`}}]});
 const {w,snapshot,calls}=setup({'characters.list':()=>packState(),'characters.select':()=>({...packState(),selectedPackId:'builtin'}),'characters.refresh':()=>{revision=1;return packState();}});await tick();
 assert.ok(calls.some(call=>call.action==='characters.list')); w.mobileCodexEvent('state',{...snapshot,messages:[{id:'a',role:'assistant',text:'완료했어요.'}]}); await tick(); w.document.getElementById('settings').click(); await tick();
 assert.equal(w.document.querySelectorAll('.character-pack-option').length,2); assert.ok(w.document.querySelector('.character-pack-option[aria-pressed="true"]')); assert.match(w.document.querySelector('.message .chat-character').src,/\/packs\/done-0\.png$/);
 w.document.querySelectorAll('.character-pack-option')[0].click(); await tick(); assert.equal(calls.filter(call=>call.action==='characters.select').at(-1).args.id,'builtin'); assert.match(w.document.querySelector('.message .chat-character').src,/\/chat-icons\/06-done\.png$/);
 await w.document.getElementById('character-pack-refresh').click(); await tick(); assert.equal(calls.filter(call=>call.action==='characters.refresh').length,1); assert.match(w.document.querySelector('.message .chat-character').src,/\/packs\/done-1\.png$/);
});
test('character pack requests expose busy state and preserve selection on cancellation',async()=>{
 let finish; const delayed=new Promise(resolve=>{finish=resolve;}); const {w,snapshot,calls}=setup({'characters.select':()=>delayed}); await tick(); const chars={folderName:'Packs',folderConfigured:true,selectedPackId:'custom',packs:[{id:'builtin',name:'Builtin',valid:true,icons:{}},{id:'custom',name:'Custom',valid:true,icons:{done:'/packs/done.png'}}]}; w.mobileCodexEvent('state',{...snapshot,characters:chars,messages:[{id:'a',role:'assistant',text:'완료했어요.'}]}); w.document.getElementById('settings').click(); await tick(); w.document.querySelector('[data-pack-id="builtin"]').click(); await tick(); assert.equal(w.document.querySelector('[data-pack-id="builtin"]').disabled,true); assert.equal(w.document.getElementById('character-pack-folder-button').disabled,true); assert.equal(w.document.querySelector('[aria-pressed="true"]').dataset.packId,'custom'); finish({cancelled:true}); await tick(); assert.equal(w.document.querySelector('[aria-pressed="true"]').dataset.packId,'custom'); assert.equal(w.document.querySelector('[data-pack-id="builtin"]').disabled,false); assert.equal(calls.filter(call=>call.action==='characters.select').length,1);
});
test('live status uses local thinking and working icons without adding transcript messages',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,busy:true,messages:[{id:'a',role:'assistant',text:'설명할게요.'}]});
 assert.match(d.querySelector('#activity-character img').src,/03-thinking.png$/);const first=d.querySelector('.message .chat-character');
 w.mobileCodexEvent('message.delta',{id:'a',delta:' 이어서'});assert.equal(d.querySelector('.message .chat-character'),first);
 w.mobileCodexEvent('tool',{name:'mobile_write',path:'test.txt'});assert.match(d.querySelector('#activity-character img').src,/04-working.png$/);assert.equal(d.querySelectorAll('.message').length,1);
 w.mobileCodexEvent('state',{...snapshot,busy:false,messages:[{id:'a',role:'assistant',text:'수정을 완료했어요.'}]});assert.equal(d.getElementById('activity').hidden,true);assert.match(d.querySelector('.message .chat-character').src,/27-fixed.png$/);
});
test('all character URLs resolve to bundled source bytes and cannot be arbitrary file paths',()=>{
 const path=require('node:path');const dir=root+'chat-icons/';const files=fs.readdirSync(dir).filter(f=>f.endsWith('.png')), hashes=JSON.parse(fs.readFileSync('tests/chat-icons.sha256.json','utf8'));
 assert.equal(files.length,32);for(const file of files){const name=file.replace(/^\d+-/,'').replace(/\.png$/,'');assert.equal(C.chatIconUrl(name),'/chat-icons/'+file);assert.equal(require('node:crypto').createHash('sha256').update(fs.readFileSync(dir+file)).digest('hex'),hashes[file]);}
 for(const bad of ['../auth.json','https://example.test/icon.png','toString',''])assert.equal(C.chatIconUrl(bad),null);
 assert.equal(C.messageIcon({text:'```\nwarning: example\n```\n설명입니다.'}),'explaining');
});

test('the twelve added expressions select their own icons before generic question and completion rules',()=>{
 const examples={uncertain:'조금 애매해요.',disagree:'아닌데?', 'not-allowed':'그건 안돼요.',
  'file-request':'파일을 첨부해 주세요.',reviewed:'검토를 완료했어요.',source:'출처: 공식 문서',
  fixed:'수정을 완료했어요.',lol:'ㄹㅇㅋㅋ', 'good-grief':'정말이지.',wink:'윙크',heart:'하트',sleep:'잘 자요.'};
 for(const [name,text] of Object.entries(examples))assert.equal(C.messageIcon({text}),name,text);
 assert.equal(C.messageIcon({text:'진짜?'}),'skeptical');
 assert.equal(C.messageIcon({text:'```\n출처: 파일을 첨부해 주세요\n```\n설명입니다.'}),'explaining');
 assert.equal(C.messageIcon({text:'검토 완료',imageStatus:'generating'}),'working');
 assert.equal(C.messageIcon({text:'수정 완료',imageError:'decode failed'}),'blocked');
});

test('code blocks render with language label and clipboard copy button',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'m1',role:'assistant',text:'```python\nprint("hello")\n```'}]});
 const card=d.querySelector('.code-card');assert.ok(card);
 const lang=card.querySelector('.code-lang');assert.equal(lang.textContent,'python');
 const pre=card.querySelector('pre code');assert.match(pre.textContent,/print\("hello"\)/);
 const copyBtn=card.querySelector('.code-copy-btn');assert.ok(copyBtn);
 copyBtn.click();await tick();
 assert.match(copyBtn.textContent,/복사됨/);
 const msgCopy=d.querySelector('.msg-copy-btn');assert.ok(msgCopy);
 msgCopy.click();await tick();
 assert.match(msgCopy.textContent,/복사됨/);
});

test('markdown tables render as scrollable table elements',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 const tableMarkdown='| 제목 | 내용 |\n| --- | --- |\n| 1열 | 값1 |\n| 2열 | 값2 |';
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'t1',role:'assistant',text:tableMarkdown}]});
 const wrap=d.querySelector('.table-wrap');assert.ok(wrap);
 const table=wrap.querySelector('table.markdown-table');assert.ok(table);
 const headers=[...table.querySelectorAll('th')].map(th=>th.textContent);
 assert.deepEqual(headers,['제목','내용']);
 const cells=[...table.querySelectorAll('td')].map(td=>td.textContent);
 assert.deepEqual(cells,['1열','값1','2열','값2']);
});

test('session actions allow renaming and deleting sessions via protocol calls',async()=>{
 const {w,calls,snapshot}=setup({
  'chat.rename':({args})=>({...snapshot,sessions:[{id:'s1',title:args.title}]}),
  'chat.delete':()=>({...snapshot,sessions:[]})
 });
 await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,sessions:[{id:'s1',title:'처음 제목'}]});
 const row=d.querySelector('.session-row');assert.ok(row);
 const more=row.querySelector('.session-more');assert.ok(more);
 more.click();await tick();
 assert.ok(d.getElementById('session-actions-dialog').open);
 const renameBtn=[...d.querySelectorAll('#session-actions button')].find(b=>b.textContent==='이름 변경');
 assert.ok(renameBtn);
 renameBtn.click();await tick();
 assert.ok(d.getElementById('input-dialog').open);
 d.getElementById('input-value').value='새로운 대화 제목';
 d.getElementById('input-confirm').click();await tick();
 assert.deepEqual(calls.find(c=>c.action==='chat.rename').args,{id:'s1',title:'새로운 대화 제목'});

 more.click();await tick();
 const deleteBtn=[...d.querySelectorAll('#session-actions button')].find(b=>b.textContent==='삭제');
 assert.ok(deleteBtn);
 deleteBtn.click();await tick();
 assert.deepEqual(calls.find(c=>c.action==='chat.delete').args,{id:'s1'});
});

test('table escaped pipes preserve header and body cells including inline code',async()=>{
 const {w,snapshot}=setup();await tick();
 const text=String.raw`| a\|b | Meaning |
| --- | --- |
| x\|y | either value |
| \`a\|b\` | code expression |
| C:\\ | trailing slash |`.replaceAll('\\`','`');
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'table',role:'assistant',text}]});
 const d=w.document;
 assert.deepEqual([...d.querySelectorAll('th')].map(n=>n.textContent),['a|b','Meaning']);
 assert.deepEqual([...d.querySelectorAll('td')].map(n=>n.textContent),['x|y','either value','a|b','code expression','C:\\','trailing slash']);
 assert.equal(d.querySelector('td code').textContent,'a|b');
});

test('clipboard denial falls back to native and only confirms successful copying',async()=>{
 let fail=false;
 const {w,calls,snapshot}=setup({'ui.copyCode':()=>{if(fail)throw new Error('Clipboard unavailable');return {ok:true};}});await tick();
 Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async()=>{throw new w.DOMException('Denied','NotAllowedError');}}});
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'copy',role:'assistant',text:'```js\nconst x = 1;\n```'}]});
 const d=w.document;
 d.querySelector('.code-copy-btn').click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='ui.copyCode').at(-1).args,{code:'const x = 1;\n'});
 assert.match(d.querySelector('.code-copy-btn').textContent,/복사됨/);
 fail=true;d.querySelector('.msg-copy-btn').click();await tick();
 assert.doesNotMatch(d.querySelector('.msg-copy-btn').textContent,/복사됨/);
 assert.match(d.getElementById('toast').textContent,/복사하지 못했습니다/);
});

test('message HTTP and HTTPS links use the dedicated browser route, unsafe schemes stay text',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'link',role:'assistant',text:'[HTTP](http://example.com) [HTTPS](https://example.com) [bad](javascript:alert)'}]});
 const links=w.document.querySelectorAll('.inline-link');assert.equal(links.length,2);
 links[0].click();await tick();links[1].click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='ui.openLink').map(c=>c.args.url),['http://example.com','https://example.com']);
 assert.equal(calls.some(c=>c.action==='ui.externalBrowser'),false);
});

test('offline deletion is reported as pending instead of claiming the original is deleted',async()=>{
 const {w,snapshot}=setup({'chat.delete':()=>({deletionPending:true})});await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,sessions:[{id:'s',title:'Offline'}]});
 d.querySelector('.session-more').click();await tick();
 [...d.querySelectorAll('#session-actions button')].find(b=>b.textContent==='삭제').click();await tick();
 assert.match(d.getElementById('toast').textContent,/삭제 요청을 저장/);
 assert.match(d.getElementById('toast').textContent,/연결되면 원본 기록도 삭제/);
 w.mobileCodexEvent('state',{...snapshot,sessions:[],pendingDeletionCount:1});
 assert.equal(d.getElementById('pending-deletions').hidden,false);
 assert.match(d.getElementById('pending-deletions').textContent,/원본 삭제 대기 1건/);
 w.mobileCodexEvent('state',{...snapshot,sessions:[],pendingDeletionCount:0});
 assert.equal(d.getElementById('pending-deletions').hidden,true);
});

test('development tools use an honest old-backend fallback and retain the project terminal cwd',async()=>{
 const {w}=setup();await tick();const d=w.document;
 d.querySelector('[data-settings-tab="tools"]').click();await tick();
 assert.match(d.getElementById('devtools-status').textContent,/정보를 제공하지 않는/);
 assert.match(d.getElementById('terminal-cwd').textContent,/\/test\/project/);
 assert.match(d.getElementById('terminal-tools-note').textContent,/상태는 설정/);
});

test('development tool check shows checked versions and HTML-looking command output as text',async()=>{
 const devtools={bundled:true,prepared:true,tools:[{name:'Python',version:'3.13.1'},{name:'Node.js',version:'22.0.0'},{name:'Git',version:'2.47.0'},{name:'npm',version:'10.9.0'},{name:'pip',version:'24.3'}]};
 const {w,calls,snapshot}=setup({'state':()=>({...snapshot,devtools}),'devtools.check':()=>({ok:true,checks:[{name:'Python',ok:true,output:'Python 3.13.1 <img src=x onerror=alert(1)>'}]})});await tick();const d=w.document;
 d.querySelector('[data-settings-tab="tools"]').click();await tick();assert.match(d.getElementById('devtools-versions').textContent,/Node.js 22.0.0/);
 assert.match(d.getElementById('terminal-tools-note').textContent,/Python · Node.js · Git · npm · pip/);
 d.getElementById('devtools-check').click();await tick();
 assert.equal(calls.filter(c=>c.action==='devtools.check').length,1);assert.match(d.getElementById('devtools-status').textContent,/완료/);
 assert.match(d.getElementById('devtools-output').textContent,/<img src=x/);assert.equal(d.getElementById('devtools-output').querySelector('img'),null);
});

test('development tool partial failures and rejected checks keep output and allow retry',async()=>{
 let attempt=0;const devtools={bundled:true,prepared:false,tools:[{name:'Python',version:'확인 전'}]};
 const {w,snapshot}=setup({'state':()=>({...snapshot,devtools}),'devtools.check':()=>{attempt++;if(attempt===1)return {ok:false,checks:[{name:'Python',ok:true,output:'Python 3.13'},{name:'Git',ok:false,output:'not found'}]};if(attempt===2)throw new Error('runtime unavailable');return {ok:true,checks:[{name:'Git',ok:true,output:'git version'}]};}});await tick();const d=w.document;
 d.getElementById('devtools-check').click();await tick();assert.match(d.getElementById('devtools-status').textContent,/일부 도구/);assert.match(d.getElementById('devtools-output').textContent,/✕ Git/);
 d.getElementById('devtools-check').click();await tick();assert.match(d.getElementById('devtools-status').textContent,/실패했습니다/);assert.match(d.getElementById('devtools-output').textContent,/runtime unavailable/);assert.equal(d.getElementById('devtools-check').disabled,false);
 d.getElementById('devtools-check').click();await tick();assert.match(d.getElementById('devtools-status').textContent,/완료/);assert.match(d.getElementById('devtools-output').textContent,/✓ Git/);
});

test('removing a project keeps its conversations and draft scope in the detached group',async()=>{
 const {w,calls,snapshot}=setup({'projects.remove':()=>({...snapshot,workspace:{selected:false},projects:[]})});await tick();const d=w.document;
 const project={key:'gone',name:'Keep files',selected:true,available:true};
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'gone',name:'Keep files'},projects:[project],sessions:[{id:'old',title:'보존 대화',workspaceKey:'gone'}]});
 const draft='draft:'+C.draftKey('gone','old');w.localStorage.setItem(draft,'첨부와 초안');
  const menu=d.querySelector('.project-more');assert.ok(menu);menu.click();await tick();
  assert.match(d.getElementById('project-actions').textContent,/새 대화/);
  [...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 목록에서 제거').click();await tick();
  assert.deepEqual(calls.filter(c=>c.action==='projects.remove').at(-1).args,{key:'gone'});
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:false},projects:[],sessions:[{id:'old',title:'보존 대화',workspaceKey:'gone'}]});
 assert.match(d.querySelector('.detached-projects').textContent,/연결 해제된 프로젝트/);assert.match(d.querySelector('.detached-projects').textContent,/보존 대화/);assert.equal(w.localStorage.getItem(draft),'첨부와 초안');
 d.querySelector('.detached-projects .session').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='chat.resume').at(-1).args,{id:'old'});
 });
test('mobile project menu supports new chat, persisted display rename and cancellable removal',async()=>{
  let confirmResult=true;
  const {w,calls,snapshot}=setup({
    'chat.new':()=>({...snapshot}),
    'projects.rename':m=>({key:m.args.key,name:m.args.name}),
    'projects.remove':()=>({...snapshot,workspace:{selected:false},projects:[]})
  },{mobile:true,confirm:()=>confirmResult});
  await tick();const d=w.document;
  const project={key:'project-menu',name:'Folder name',selected:true,available:true};
  w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:project.key,name:project.name},projects:[project]});await tick();
  const open=()=>{d.querySelector('.project-more').click();};
  open();await tick();assert.equal(d.getElementById('project-actions-dialog').open,true);assert.match(d.getElementById('project-actions').textContent,/새 대화/);
  d.querySelector('#project-actions button').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='chat.new').at(-1).args,{workspaceKey:project.key});
  open();await tick();[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='이름 변경').click();await tick();
  d.getElementById('input-value').value='표시 이름';d.getElementById('input-confirm').click();await tick();await tick();
  assert.deepEqual(calls.filter(c=>c.action==='projects.rename').at(-1).args,{key:project.key,name:'표시 이름'});assert.equal(d.querySelector('.project-tree .project-button span').textContent,'표시 이름');
  open();await tick();confirmResult=false;[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 목록에서 제거').click();await tick();assert.equal(calls.some(c=>c.action==='projects.remove'),false);
  open();await tick();confirmResult=true;[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 목록에서 제거').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='projects.remove').at(-1).args,{key:project.key});
 });

test('phone control requires native consent and does not enable on service connection',async()=>{
 const {w,calls,snapshot}=setup({'ui.phoneEnable':()=>({cancelled:true})});await tick();
 w.mobileCodexEvent('state',{...snapshot,phone:{connected:true,enabled:false,status:'접근성 연결됨',screenshotsSupported:true},phoneToolsAvailable:false});
 assert.equal(calls.some(c=>c.action==='ui.phoneEnable'),false);
 assert.equal(w.document.getElementById('phone-thread-note').hidden,false);
 w.document.getElementById('phone-enable').click();await tick();
 assert.equal(calls.filter(c=>c.action==='ui.phoneEnable').length,1);
 assert.equal(w.document.getElementById('phone-stop-banner').hidden,true);
 w.document.getElementById('phone-settings').click();await tick();
 assert.equal(calls.some(c=>c.action==='ui.phoneSettings'),true);
});
test('phone emergency stop remains visible and stops without ending a chat or losing its draft',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 w.document.getElementById('prompt').value='keep my draft';
 w.mobileCodexEvent('state',{...snapshot,busy:true,phone:{connected:true,enabled:true,status:'휴대폰 제어 켜짐',screenshotsSupported:false},phoneToolsAvailable:true});
 assert.equal(w.document.getElementById('phone-stop-banner').hidden,false);
 assert.match(w.document.getElementById('phone-screenshot-note').textContent,/Android 10/);
 w.document.getElementById('phone-stop-banner').click();await tick();
 assert.equal(calls.some(c=>c.action==='phone.stop'),true);
 assert.equal(calls.some(c=>c.action==='runtime.stop'),false);
 assert.equal(w.document.getElementById('phone-stop-banner').hidden,true);
 assert.equal(w.document.getElementById('prompt').value,'keep my draft');
});

test('busy composer steers the exact active turn and retains rejected instructions',async()=>{
 const {w,calls,snapshot}=setup({'chat.steer':async()=>{throw new Error('turn already completed');}});await tick();
 w.mobileCodexEvent('state',{...snapshot,busy:true,turnId:'active-turn'});
 const prompt=w.document.getElementById('prompt');prompt.value='색만 바꿔';prompt.dispatchEvent(new w.Event('input'));assert.equal(w.document.getElementById('send').disabled,false);assert.equal(w.document.getElementById('send').hidden,false);
 w.document.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 const call=calls.find(x=>x.action==='chat.steer');assert.equal(call.args.expectedTurnId,'active-turn');assert.equal(call.args.expectedThreadId,'t');assert.equal(prompt.value,'색만 바꿔');assert.equal(calls.some(x=>x.action==='chat.send'),false);
});
test('change preview renders file text safely and restore uses its exact token and project',async()=>{
 const {w,calls,snapshot}=setup({'changes.list':()=>({entries:[{path:'a.txt',status:' M'}]}),'changes.preview':()=>({path:'a.txt',token:'review-1',before:'old',after:'<img src=x onerror=alert(1)>',canRestore:true,actionLabel:'복원'})});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'project-a'}});w.document.getElementById('show-changes').click();await tick();
 w.document.querySelector('#changes-list button').click();await tick();assert.equal(w.document.querySelectorAll('#change-diff img').length,0);assert.match(w.document.getElementById('change-diff').textContent,/<img/);
 w.document.getElementById('change-restore').click();await tick();const r=calls.find(x=>x.action==='changes.restore');assert.equal(r.args.token,'review-1');assert.equal(r.args.workspaceKey,'project-a');
});
test('late change previews cannot restore into a switched project',async()=>{
 let resolve;const {w,calls,snapshot}=setup({'changes.list':()=>({entries:[{path:'a.txt'}]}),'changes.preview':()=>new Promise(r=>resolve=r)});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'a'}});w.document.getElementById('show-changes').click();await tick();w.document.querySelector('#changes-list button').click();await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'b'}});resolve({path:'a.txt',token:'old',canRestore:true,before:'one',after:'two'});await tick();
 assert.equal(w.document.getElementById('change-preview').hidden,true);assert.equal(w.document.getElementById('change-restore').disabled,true);assert.equal(calls.some(x=>x.action==='changes.restore'),false);
});
test('floating chat requires an accessibility connection but not armed phone control',async()=>{
 const {w,calls,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,phone:{connected:false,enabled:false}});assert.equal(w.document.getElementById('floating-chat').disabled,true);
 w.mobileCodexEvent('state',{...snapshot,phone:{connected:true,enabled:false}});w.document.getElementById('floating-chat').click();await tick();assert.equal(calls.some(x=>x.action==='ui.floatingChat'),true);assert.equal(calls.some(x=>x.action==='ui.phoneEnable'),false);
});

test('voice input captures selection, fills the draft and never sends automatically',async()=>{
 let receipts=[];const {w,calls}=setup({'voice.recover':()=>({receipts,active:false})});await tick();
 const prompt=w.document.getElementById('prompt');prompt.value='old draft';prompt.setSelectionRange(0,3);prompt.dispatchEvent(new w.Event('input'));
 w.document.getElementById('voice-input').click();await tick();
 const request=calls.find(c=>c.action==='voice.start').args;assert.equal(request.original,'old draft');assert.equal(request.start,0);assert.equal(request.end,3);
 receipts=[{...request,origin:'main',receiptId:'v1',text:'새 음성'}];w.mobileCodexEvent('voice.changed',{});await tick();
 assert.equal(prompt.value,'새 음성 draft');assert.equal(calls.filter(c=>c.action==='chat.send'||c.action==='chat.steer').length,0);
 assert.equal(calls.filter(c=>c.action==='voice.ack').length,1);
 w.mobileCodexEvent('voice.changed',{});await tick();assert.equal(prompt.value,'새 음성 draft');assert.equal(calls.filter(c=>c.action==='voice.ack').length,1);
});
test('voice results stay with the original conversation after a project switch',async()=>{
 let receipts=[];const {w,snapshot,calls}=setup({'voice.recover':()=>({receipts})});await tick();
 const prompt=w.document.getElementById('prompt');prompt.value='A';prompt.setSelectionRange(1,1);prompt.dispatchEvent(new w.Event('input'));
 w.document.getElementById('voice-input').click();await tick();const request=calls.find(c=>c.action==='voice.start').args;
 w.mobileCodexEvent('state',{...snapshot,threadId:'other',workspace:{key:'other-project',name:'Other'}});prompt.value='B';prompt.dispatchEvent(new w.Event('input'));
 receipts=[{...request,origin:'main',receiptId:'v2',text:' 원래 요청'}];w.mobileCodexEvent('voice.changed',{});await tick();
 assert.equal(prompt.value,'B');assert.equal(w.localStorage.getItem('draft:'+request.scope),'A 원래 요청');
 w.mobileCodexEvent('state',snapshot);assert.equal(prompt.value,'A 원래 요청');
});
test('voice errors, cancellation and edits during recognition preserve the current draft',async()=>{
 let receipts=[];const {w,calls}=setup({'voice.recover':()=>({receipts})});await tick();
 const prompt=w.document.getElementById('prompt');prompt.value='before';prompt.dispatchEvent(new w.Event('input'));
 w.document.getElementById('voice-input').click();await tick();const request=calls.find(c=>c.action==='voice.start').args;
 prompt.value='edited';prompt.dispatchEvent(new w.Event('input'));
 receipts=[{...request,origin:'main',receiptId:'v3',text:'dictation'}];w.mobileCodexEvent('voice.changed',{});await tick();assert.equal(prompt.value,'edited\ndictation');
 receipts=[{...request,origin:'main',receiptId:'v4',text:'',error:'인식 서비스 없음'},{...request,origin:'main',receiptId:'v5',text:'',cancelled:true}];w.mobileCodexEvent('voice.changed',{});await tick();
 assert.equal(prompt.value,'edited\ndictation');assert.match(w.document.getElementById('toast').textContent,/인식 서비스 없음/);assert.equal(w.document.getElementById('voice-input').disabled,false);
});
test('interrupted voice receipt application recovers without appending twice',async()=>{
 const scope=C.draftKey('/test/project','t'),receipt={scope,origin:'main',receiptId:'recovered',original:'before',start:6,end:6,text:' voice'};
 const {w,calls}=setup({'voice.recover':()=>({receipts:[receipt]})},{drafts:{['draft:'+scope]:'before voice',['voice-receipt:recovered']:JSON.stringify({before:'before',after:'before voice'})}});await tick();await tick();
 assert.equal(w.document.getElementById('prompt').value,'before voice');assert.equal(calls.filter(c=>c.action==='voice.ack').length,1);
});

test('updates load local version without automatically checking or downloading',async()=>{
 const {w,calls}=setup({'updates.state':()=>({versionName:'0.1.11-alpha',versionCode:12,repository:'nokryong/mobile-codex',prereleases:true,status:'idle',revision:1})});await tick();
 assert.equal(w.document.getElementById('app-version').textContent,'0.1.11-alpha');assert.equal(calls.filter(c=>['updates.check','updates.download','updates.install'].includes(c.action)).length,0);
 const field=w.document.getElementById('update-repository');field.value='other/repo';field.dispatchEvent(new w.Event('input'));
 w.mobileCodexEvent('updates.changed',{revision:2,status:'idle',repository:'old/repo'});assert.equal(field.value,'other/repo');
 w.document.getElementById('update-check').click();await tick();assert.equal(calls.find(c=>c.action==='updates.configure').args.repository,'other/repo');assert.equal(calls.filter(c=>c.action==='updates.check').length,1);
});
test('update progress cannot regress and installation requires explicit permission and click',async()=>{
 const {w,calls,snapshot}=setup();await tick();const candidate={versionName:'0.1.12-alpha',size:100,notes:'<script>bad</script>'};
 w.mobileCodexEvent('updates.changed',{revision:3,status:'downloading',candidate,busy:true,received:50,canCancel:true});assert.equal(w.document.getElementById('update-progress').value,50);assert.equal(w.document.getElementById('update-cancel').hidden,false);
 w.mobileCodexEvent('updates.changed',{revision:2,status:'idle'});assert.equal(w.document.getElementById('update-progress').value,50);assert.equal(w.document.querySelectorAll('#update-notes script').length,0);
 w.mobileCodexEvent('updates.changed',{revision:4,status:'ready',candidate,ready:true,available:true,canInstall:false});assert.equal(w.document.getElementById('update-install').disabled,true);
 w.document.getElementById('update-permission').click();await tick();assert.equal(calls.filter(c=>c.action==='updates.install').length,0);
 w.mobileCodexEvent('updates.changed',{revision:5,status:'ready',candidate,ready:true,canInstall:true});w.mobileCodexEvent('state',{...snapshot,busy:true});assert.equal(w.document.getElementById('update-install').disabled,true);
 w.mobileCodexEvent('state',{...snapshot,busy:false});w.document.getElementById('update-install').click();await tick();assert.equal(calls.filter(c=>c.action==='updates.install').length,1);
});
test('update cancellation and signature errors never offer automatic installation',async()=>{
 const {w,calls}=setup({'updates.cancel':()=>({revision:3,status:'cancelled',message:'취소했습니다',busy:false})});await tick();
 w.mobileCodexEvent('updates.changed',{revision:2,status:'downloading',busy:true,canCancel:true});w.document.getElementById('update-cancel').click();await tick();assert.equal(w.document.getElementById('update-status').textContent,'취소했습니다');
 w.mobileCodexEvent('updates.changed',{revision:4,status:'error',message:'서명키가 다릅니다. <b>삭제하지 마세요</b>',available:true,candidate:{versionName:'0.1.12-alpha',size:200},ready:false});
 assert.equal(w.document.getElementById('update-install').hidden,true);assert.equal(w.document.querySelectorAll('#update-status b').length,0);assert.equal(calls.filter(c=>c.action==='updates.install').length,0);
});

test('edited update source disables old candidate actions and download names its hash',async()=>{
 const {w,calls}=setup();await tick();const candidate={versionName:'0.1.12-alpha',size:100,sha256:'ab'.repeat(32)};
 w.mobileCodexEvent('updates.changed',{revision:1,status:'available',candidate,available:true,repository:'owner/repo'});
 w.document.getElementById('update-download').click();await tick();assert.equal(calls.find(c=>c.action==='updates.download').args.sha256,candidate.sha256);
 w.mobileCodexEvent('updates.changed',{revision:2,status:'ready',candidate,available:true,ready:true,canInstall:true});
 const source=w.document.getElementById('update-repository');source.value='other/repo';source.dispatchEvent(new w.Event('input'));
 assert.equal(w.document.getElementById('update-download').disabled,true);assert.equal(w.document.getElementById('update-install').disabled,true);
 assert.equal(w.document.getElementById('update-check').disabled,false);
});

test('language switch preserves drafts, model content and editor values',async()=>{
 const {w,snapshot,calls}=setup({}, {language:'ko'});await tick();const d=w.document;
 const raw='설정 파일 보내기';w.mobileCodexEvent('state',{...snapshot,messages:[{id:'a',role:'assistant',text:raw}]});
 d.getElementById('prompt').value='기존 초안';d.getElementById('prompt').dispatchEvent(new w.Event('input'));
 d.getElementById('editor').value='사용자 파일';d.getElementById('instructions-editor').value='기존 지침';
 d.getElementById('language').value='en';d.getElementById('language').dispatchEvent(new w.Event('change'));await tick();await tick();
 assert.equal(d.documentElement.lang,'en');assert.match(d.getElementById('settings').textContent,/Settings/);
 assert.equal(d.getElementById('prompt').value,'기존 초안');assert.equal(d.getElementById('editor').value,'사용자 파일');assert.equal(d.getElementById('instructions-editor').value,'기존 지침');
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'a',role:'assistant',text:raw}]});
 assert.match(d.getElementById('messages').textContent,/설정 파일 보내기/);assert.match(d.getElementById('messages').textContent,/Copy/);
 assert.match(d.querySelector('[data-prompt]').dataset.prompt,/Look through/);
 assert.equal(calls.filter(x=>['runtime.stop','runtime.start'].includes(x.action)).length,0);
 d.getElementById('language').value='ko';d.getElementById('language').dispatchEvent(new w.Event('change'));await tick();await tick();
 assert.equal(d.getElementById('prompt').value,'기존 초안');assert.match(d.getElementById('settings').textContent,/설정/);
});
test('system locale defaults to English outside Korean and catalog copies match',async()=>{
 const {w}=setup({}, {language:'system',systemLanguage:'ja-JP'});await tick();
 assert.equal(w.document.documentElement.lang,'en');assert.match(w.document.getElementById('prompt').placeholder,/Ask anything/);
 assert.deepEqual(JSON.parse(JSON.stringify(w.MobileCodexEnglish)), JSON.parse(fs.readFileSync('app/src/main/assets/translations-en.json','utf8')));
});
test('inline dictation displays partial text, supports done and cancel, and cannot auto-send',async()=>{
 const {w,calls}=setup({}, {language:'en'});await tick();const d=w.document;
 d.getElementById('prompt').value='Existing draft';
 w.mobileCodexEvent('voice.state',{phase:'listening',partial:'partial voice',level:0.6,elapsedMs:4200});
 assert.equal(d.getElementById('dictation').hidden,false);assert.match(d.getElementById('dictation-status').textContent,/Listening/);
 assert.equal(d.getElementById('dictation-preview').textContent,'partial voice');assert.equal(d.getElementById('prompt').value,'Existing draft');assert.equal(d.getElementById('prompt').readOnly,true);
 d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();assert.equal(calls.some(x=>x.action==='chat.send'),false);
 d.getElementById('dictation-done').click();await tick();assert.equal(calls.some(x=>x.action==='voice.stop'),true);
 w.mobileCodexEvent('voice.state',{phase:'transcribing'});assert.equal(d.getElementById('dictation-done').disabled,true);
 d.getElementById('dictation-cancel').click();await tick();assert.equal(calls.some(x=>x.action==='voice.cancel'),true);
 w.mobileCodexEvent('voice.state',{phase:'idle'});assert.equal(d.getElementById('dictation').hidden,true);assert.equal(d.getElementById('prompt').readOnly,false);assert.equal(d.getElementById('prompt').value,'Existing draft');
});

function sheetPointer(w, grip, type, x, y) {
 const event=new w.Event(type,{bubbles:true,cancelable:true});
 for(const [key,value] of Object.entries({pointerId:1,isPrimary:true,button:0,clientX:x,clientY:y})) Object.defineProperty(event,key,{value});
 grip.dispatchEvent(event);
}
test('mobile sheet drag closes through the existing unsaved-instructions guard',async()=>{
 const {w}=setup({'instructions.read':()=>({content:'Saved instructions',activePath:'/private/AGENTS.md'})},{mobile:true});await tick();const d=w.document;
 d.getElementById('settings').click();await tick();d.querySelector('[data-settings-tab="personal"]').click();await tick();
 d.getElementById('instructions-editor').value='Unsaved edit';w.confirm=()=>false;
 const sheet=d.getElementById('settings-dialog'),grip=sheet.querySelector('.sheet-grip');
 const drag=()=>{sheetPointer(w,grip,'pointerdown',100,20);sheetPointer(w,grip,'pointermove',105,170);sheetPointer(w,grip,'pointerup',105,170);};
 drag();assert.equal(sheet.open,true);assert.equal(d.getElementById('instructions-editor').value,'Unsaved edit');assert.equal(sheet.style.getPropertyValue('--sheet-offset'),'');
 w.confirm=()=>true;drag();assert.equal(sheet.open,false);
});
test('short or cancelled sheet drags stay open and do not turn into close clicks',async()=>{
 const {w}=setup({}, {mobile:true});await tick();const d=w.document;d.getElementById('composer-options').click();await tick();
 const sheet=d.getElementById('options-dialog'),grip=sheet.querySelector('.sheet-grip');
 sheetPointer(w,grip,'pointerdown',100,20);sheetPointer(w,grip,'pointermove',100,48);sheetPointer(w,grip,'pointerup',100,48);
 grip.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));assert.equal(sheet.open,true);
 sheetPointer(w,grip,'pointerdown',100,20);sheetPointer(w,grip,'pointermove',100,180);sheetPointer(w,grip,'pointercancel',100,180);
 assert.equal(sheet.open,true);assert.equal(sheet.style.getPropertyValue('--sheet-offset'),'');
 // Keyboard activation remains available even immediately after a cancelled pointer gesture.
 grip.click();assert.equal(sheet.open,false);
});
test('dragging content or a desktop dialog never dismisses it and approvals have no grip',async()=>{
 for(const mobile of [true,false]){
  const {w}=setup({}, {mobile});await tick();const d=w.document;d.getElementById('composer-options').click();await tick();
  const sheet=d.getElementById('options-dialog'),target=mobile?sheet.querySelector('.dialog-head'):sheet.querySelector('.sheet-grip');
  sheetPointer(w,target,'pointerdown',100,20);sheetPointer(w,target,'pointermove',100,200);sheetPointer(w,target,'pointerup',100,200);
  assert.equal(sheet.open,true);assert.equal(d.querySelector('#request-dialog .sheet-grip'),null);
 }
});
