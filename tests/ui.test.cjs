const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const C = require('../app/src/main/assets/web/ui-core.js');
const root = 'app/src/main/assets/web/';
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(r => setTimeout(r, 10));
const fastModel = {id:'fast-model',model:'fast-model',displayName:'Fast-capable model',isDefault:true,serviceTiers:[{id:'priority',name:'Fast'}]};
const historyMessages = (from, to) => Array.from({length:to-from+1}, (_,i)=>({id:'m'+(from+i),role:(from+i)%2?'user':'assistant',text:'Message '+(from+i)}));
const historyMeta = (before, total=100) => ({beforeId:before?'m'+before:'',hasMore:!!before,total});
const resetCredit = (id, expiresAt) => ({id, expiresAt, grantedAt:1900000000, status:'available', resetType:'codexRateLimits'});
function setup(overrides = {}, options = {}) {
  const dom = new JSDOM(fs.readFileSync(root+'index.html','utf8'), {url: 'https://appassets.androidplatform.net/index.html', runScripts: 'outside-only'}); opened.push(dom);
  const w = dom.window, calls = [], responses = [], frames = [];
  if (options.manualFrames) w.requestAnimationFrame = callback => frames.push(callback);
  w.matchMedia = query => ({matches:!!options.mobile && query.includes('max-width'), addEventListener(){}});
  if (options.drafts) for (const [key,value] of Object.entries(options.drafts)) w.localStorage.setItem(key,value);
  w.HTMLDialogElement.prototype.showModal = function(){this.setAttribute('open','');};
  w.HTMLDialogElement.prototype.close = function(){this.removeAttribute('open');this.dispatchEvent(new w.Event('close'));};
  w.confirm = options.confirm || (() => true);
  const snapshot = {ready:true,busy:false,permissions:'workspace-write',models:[],messages:[],sessions:[],account:{type:'chatgpt',email:'me@example.test'},workspace:{selected:true,name:'Project'},cwd:'/test/project',threadId:'t',status:'연결됨'};
  w.Native = {postMessage(raw){ const m=JSON.parse(raw); calls.push(m); queueMicrotask(async () => {
    if(m.action==='rpc.respond') responses.push(m.args);
    try {
      if (m.action === 'rpc' && m.args.method?.startsWith('sync.')) throw new Error('Sync belongs to the native bridge, not Codex RPC');
      const result=m.action.startsWith('sync.') && overrides.sync ? await overrides.sync(m) : overrides[m.action] ? await overrides[m.action](m) : m.action==='state' ? snapshot : m.action==='rpc' ? {data:[],marketplaces:[]} : {};
      w.mobileCodexEvent('response',{id:m.id,result});
    } catch(error) { w.mobileCodexEvent('response',{id:m.id,error:error.message}); }
  });}};
  w.Native.locale = () => JSON.stringify({choice:options.language || 'ko',systemLanguage:options.systemLanguage || 'ko'});
  w.Native.textSize = () => JSON.stringify({percent:options.textSize ?? 100});
  w.eval(fs.readFileSync(root+'translations.js','utf8')); w.eval(fs.readFileSync(root+'locale.js','utf8'));
  w.eval(fs.readFileSync(root+'ui-core.js','utf8')); w.eval(fs.readFileSync(root+'app.js','utf8'));
  return {w,calls,responses,snapshot,frames};
}
test('text size restores natively, saves explicitly and preserves the conversation and draft',async()=>{
 let saved=130;
 const {w,calls,snapshot}=setup({'ui.textSize':m=>({percent:saved=m.args.percent})},{textSize:saved});await tick();
 const d=w.document,select=d.getElementById('text-size');
 assert.equal(select.value,'130');assert.equal(d.documentElement.dataset.textSize,'130');
 assert.equal(calls.some(c=>c.action==='ui.textSize'),false,'startup does not overwrite saved preference');
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'keep',role:'user',text:'keep me'}]});
 d.getElementById('prompt').value='unsent draft';
 assert.deepEqual([...select.options].map(option=>option.value),['75','85','100','115','130','150']);
 for(const size of [150,115,85,75,100]) {
  select.value=String(size);select.dispatchEvent(new w.Event('change'));await tick();
  assert.equal(saved,size);assert.equal(d.documentElement.dataset.textSize,String(size));assert.equal(select.disabled,false);
 }
 assert.equal(d.getElementById('prompt').value,'unsent draft');assert.equal(d.querySelector('[data-id="keep"]').textContent,'keep me');
 assert.equal(calls.some(c=>c.action==='chat.send'||c.action==='config.save'||c.action==='projects.select'),false);
 const reopened=setup({}, {textSize:saved});await tick();assert.equal(reopened.w.document.getElementById('text-size').value,String(saved));
});
test('text shrink is opt-in, restores a saved reduced value and leaves the default at 100',async()=>{
 const fresh=setup();await tick();
 assert.equal(fresh.w.document.getElementById('text-size').value,'100');
 assert.equal(fresh.w.document.documentElement.style.getPropertyValue('--text-size-factor'),'1');
 assert.equal(fresh.calls.some(c=>c.action==='ui.textSize'),false);
 for(const size of [75,85]) {
  const reduced=setup({}, {textSize:size});await tick();
  assert.equal(reduced.w.document.getElementById('text-size').value,String(size));
  assert.equal(reduced.w.document.documentElement.style.getPropertyValue('--text-size-factor'),String(size/100));
  assert.equal(reduced.calls.some(c=>c.action==='ui.textSize'),false);
 }
});
test('text size rolls back failed saves and rejects an invalid native value',async()=>{
 const {w}=setup({'ui.textSize':()=>{throw Error('storage failed');}},{textSize:130});await tick();
 const select=w.document.getElementById('text-size');select.value='150';select.dispatchEvent(new w.Event('change'));await tick();
 assert.equal(select.value,'130');assert.equal(select.disabled,false);assert.equal(w.document.documentElement.dataset.textSize,'130');
 assert.match(w.document.getElementById('toast').textContent,/storage failed/);
 const invalid=setup({}, {textSize:999});await tick();assert.equal(invalid.w.document.getElementById('text-size').value,'100');
});
test('Fast is an explicit per-draft lightning toggle first in the bottom row',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,models:[fastModel]});
 const toggle=d.getElementById('fast-mode');
 assert.equal(d.querySelector('.composer-actions').firstElementChild,toggle);
 assert.equal(toggle.querySelector('use').getAttribute('href'),'#i-bolt');
 assert.equal(toggle.disabled,false);assert.equal(toggle.getAttribute('aria-pressed'),'false');
 toggle.click();await tick();assert.equal(toggle.getAttribute('aria-pressed'),'true');
 assert.equal(calls.some(c=>c.action==='chat.send'),false,'toggling never sends a paid request');
 d.getElementById('prompt').value='test';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 assert.equal(calls.find(c=>c.action==='chat.send').args.fastMode,true);
 toggle.click();await tick();d.getElementById('prompt').value='normal';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 assert.equal(calls.filter(c=>c.action==='chat.send').at(-1).args.fastMode,false);
 assert.equal(calls.some(c=>c.action==='config.write'||c.action==='permissions.set'),false);
});
test('Fast stays unavailable for unsupported models and preserves its choice with Pro consultation',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document,toggle=d.getElementById('fast-mode');
 assert.equal(toggle.disabled,true);
 w.mobileCodexEvent('state',{...snapshot,models:[fastModel],busy:true});assert.equal(toggle.disabled,true);
 w.mobileCodexEvent('state',{...snapshot,models:[fastModel]});assert.equal(toggle.disabled,false);
 d.getElementById('add-attachment').click();d.getElementById('composer-consult-pro').click();await tick();
 assert.equal(toggle.disabled,false);toggle.click();await tick();assert.equal(toggle.getAttribute('aria-pressed'),'true');
 d.getElementById('prompt').value='Pro test';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 const sent=calls.find(c=>c.action==='chat.send');assert.equal(sent.args.fastMode,true);assert.equal(sent.args.consultPro,true);
 assert.equal(calls.some(c=>c.action==='chat.pro.send'),false);
});
test('Fast choices are isolated between conversation drafts',async()=>{
 const {w,snapshot}=setup();await tick();const toggle=w.document.getElementById('fast-mode');
 const first={...snapshot,models:[fastModel]};w.mobileCodexEvent('state',first);
 toggle.click();await tick();assert.equal(toggle.getAttribute('aria-pressed'),'true');
 w.mobileCodexEvent('state',{...first,threadId:'second',fastMode:false});assert.equal(toggle.getAttribute('aria-pressed'),'false');
 w.mobileCodexEvent('state',first);assert.equal(toggle.getAttribute('aria-pressed'),'true');
});
test('user bubbles show small dates only when a real timestamp exists',async()=>{
 const {w,snapshot}=setup();await tick();const now=Date.UTC(2026,8,29,12,34);
 w.mobileCodexEvent('state',{...snapshot,messages:[
  {id:'dated',role:'user',text:'hello',createdAt:now},
  {id:'assistant',role:'assistant',text:'answer',createdAt:now},
  {id:'legacy',role:'user',text:'old'},
  {id:'invalid',role:'user',text:'invalid',createdAt:'bad date'},
  {id:'zero',role:'user',text:'unknown',createdAt:0}
 ]});
 const dates=w.document.querySelectorAll('#messages time');assert.equal(dates.length,1);
 assert.equal(dates[0].dateTime,new Date(now).toISOString());assert.match(dates[0].textContent,/09\.29 \d{2}:\d{2}/);
 assert.equal(dates[0].closest('article').dataset.id,'dated');assert.ok(dates[0].title);
});
test('history lazy-loads in the existing scroller and keeps order, nodes and reading position',async()=>{
 let resolveHistory;
 const {w,calls,snapshot}=setup({'chat.history':()=>new Promise(resolve=>{resolveHistory=resolve;})});await tick();
 const d=w.document,area=d.getElementById('chat-scroll'),list=d.getElementById('messages');
 Object.defineProperty(area,'scrollHeight',{get:()=>list.children.length*100});Object.defineProperty(area,'clientHeight',{value:300});
 const recent={...snapshot,messages:historyMessages(61,100),messageHistory:historyMeta(61)};
 w.mobileCodexEvent('state',recent);await tick();assert.equal(list.children.length,40);
 assert.equal(calls.filter(c=>c.action==='chat.history').length,0);
 const original=d.querySelector('[data-id="m61"]');area.scrollTop=100;area.dispatchEvent(new w.Event('scroll'));await tick();
 area.dispatchEvent(new w.Event('wheel'));await tick();
 assert.equal(calls.filter(c=>c.action==='chat.history').length,1);
 assert.deepEqual(calls.find(c=>c.action==='chat.history').args,{threadId:'t',beforeId:'m61',limit:40});
 resolveHistory({threadId:'t',messages:historyMessages(21,61),messageHistory:historyMeta(21)});await tick();
 assert.equal(list.children.length,80);assert.equal(list.firstElementChild.dataset.id,'m21');assert.equal(list.lastElementChild.dataset.id,'m100');
 assert.equal(d.querySelector('[data-id="m61"]'),original);assert.equal(area.scrollTop,4100);
 assert.equal(d.querySelectorAll('#chat-scroll').length,1);assert.equal(d.querySelector('#history-status button'),null);
 w.mobileCodexEvent('state',{...recent,messages:historyMessages(62,101),messageHistory:historyMeta(62,101)});
 assert.equal(list.children.length,81);assert.equal(list.firstElementChild.dataset.id,'m21');assert.equal(list.lastElementChild.dataset.id,'m101');
 assert.equal(d.querySelector('[data-id="m61"]'),original);
});
test('deferred viewport and focus scrolling respects newer upward navigation',async()=>{
 for (const trigger of ['viewport','focus']) {
  const {w,snapshot,frames}=setup({}, {manualFrames:true});await tick();
  const area=w.document.getElementById('chat-scroll');
  Object.defineProperty(area,'scrollHeight',{value:4000});
  Object.defineProperty(area,'clientHeight',{value:300});
  w.mobileCodexEvent('state',{...snapshot,messages:historyMessages(61,100),messageHistory:historyMeta(0)});
  for(const callback of frames.splice(0))callback();
  if(trigger==='viewport')w.mobileCodexEvent('viewport',{keyboardVisible:true});
  else w.document.getElementById('prompt').dispatchEvent(new w.Event('focus'));
  assert.ok(frames.length>0,trigger+' schedules a layout follow-up');
  area.scrollTop=100;area.dispatchEvent(new w.Event('scroll'));
  for(const callback of frames.splice(0))callback();
  assert.equal(area.scrollTop,100,trigger+' must not pull the reader back to the tail');
 }
});
test('history never expands the recent window without upward navigation',async()=>{
 const {w,calls,snapshot}=setup();await tick();const list=w.document.getElementById('messages');
 w.mobileCodexEvent('state',{...snapshot,messages:historyMessages(61,100),messageHistory:historyMeta(61)});
 w.mobileCodexEvent('state',{...snapshot,messages:historyMessages(62,101),messageHistory:historyMeta(62,101)});
 assert.equal(list.children.length,40);assert.equal(list.firstElementChild.dataset.id,'m62');
 assert.equal(calls.some(c=>c.action==='chat.history'),false);
});
test('history discards an in-flight page after switching conversations',async()=>{
 let resolveHistory;
 const {w,snapshot}=setup({'chat.history':()=>new Promise(resolve=>{resolveHistory=resolve;})});await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:historyMessages(61,100),messageHistory:historyMeta(61)});
 w.document.getElementById('chat-scroll').dispatchEvent(new w.WheelEvent('wheel',{deltaY:-20}));await tick();
 w.mobileCodexEvent('state',{...snapshot,threadId:'second',messages:[{id:'other',role:'user',text:'Other conversation'}],messageHistory:historyMeta(0,1)});
 resolveHistory({threadId:'t',messages:historyMessages(21,60),messageHistory:historyMeta(21)});await tick();
 assert.equal(w.document.getElementById('messages').children.length,1);assert.equal(w.document.querySelector('#messages article').dataset.id,'other');
});
test('history preserves the live tail while a page is pending and errors retry only on upward navigation',async()=>{
 let resolveHistory,attempt=0;
 const {w,calls,snapshot}=setup({'chat.history':()=>{if(++attempt===1)throw new Error('offline');return new Promise(resolve=>{resolveHistory=resolve;});}});await tick();
 const area=w.document.getElementById('chat-scroll'),list=w.document.getElementById('messages');
 const recent={...snapshot,messages:historyMessages(61,100),messageHistory:historyMeta(61)};w.mobileCodexEvent('state',recent);
 area.dispatchEvent(new w.WheelEvent('wheel',{deltaY:-20}));await tick();assert.equal(list.children.length,40);
 assert.match(w.document.getElementById('history-status').textContent,/다시 시도/);await tick();assert.equal(attempt,1);
 area.dispatchEvent(new w.WheelEvent('wheel',{deltaY:-20}));await tick();
 w.mobileCodexEvent('state',{...recent,messages:historyMessages(62,101),messageHistory:historyMeta(62,101)});
 resolveHistory({threadId:'t',messages:historyMessages(21,60),messageHistory:historyMeta(21,100)});await tick();
 assert.equal(list.children.length,81);assert.equal(list.firstElementChild.dataset.id,'m21');assert.equal(list.lastElementChild.dataset.id,'m101');
 assert.equal(calls.filter(c=>c.action==='chat.history').length,2);assert.equal(w.document.getElementById('history-status').hidden,true);
});
test('cold startup keeps the normal screen while the account is restored silently',async()=>{
 let resolveState;
 const {w}=setup({state:()=>new Promise(resolve=>{resolveState=resolve;})});
 const d=w.document;
 assert.equal(d.getElementById('onboarding').hidden,true);
 assert.doesNotMatch(d.getElementById('quota-caption').textContent,/로그인/);
 await tick();
 resolveState({ready:false,account:{},authState:'unknown',accounts:[{active:true,email:'saved@example.test',planType:'Pro'}],messages:[],sessions:[],workspace:{},models:[]});
 await tick();
 assert.equal(d.getElementById('onboarding').hidden,true);
 assert.equal(d.getElementById('suggestions').hidden,false);
 assert.equal(d.getElementById('quota-percent').textContent,'saved@example.test');
 assert.equal(d.getElementById('prompt').disabled,false);
 assert.equal(d.querySelector('dialog[open]'),null);
});
test('account checks and temporary failures do not flash a login invitation',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 for(const authState of ['unknown','checking','error']){
   w.mobileCodexEvent('state',{...snapshot,ready:false,account:{},authState,authError:authState==='error'?'계정 정보를 불러오지 못했습니다. 다시 시도해 주세요.':''});
   assert.equal(d.getElementById('onboarding').hidden,true);
   assert.doesNotMatch(d.getElementById('quota-caption').textContent,/로그인/);
   assert.equal(d.getElementById('toast').textContent,'');
 }
 w.mobileCodexEvent('state',{...snapshot,account:{},authState:'signed_out'});
 assert.equal(d.getElementById('onboarding').hidden,false);
 assert.match(d.getElementById('quota-caption').textContent,/로그인/);
 w.mobileCodexEvent('state',{...snapshot,authState:'signed_in'});
 assert.equal(d.getElementById('onboarding').hidden,true);
});
test('opening account settings retries an unconfirmed account without starting login',async()=>{
 let restored;
 const {w,calls,snapshot}=setup({'auth.refresh':()=>restored});await tick();
 restored={...snapshot,authState:'signed_in',rateLimits:{rateLimits:{primary:{usedPercent:12,windowDurationMins:300}}}};
 w.mobileCodexEvent('state',{...snapshot,account:{},authState:'error'});
 w.document.getElementById('account-button').click();await tick();await tick();
 assert.equal(calls.filter(c=>c.action==='auth.refresh').length,1);
 assert.equal(calls.some(c=>c.action==='auth.login'||c.action==='auth.add'),false);
 assert.equal(w.document.getElementById('login-dialog').open,false);
 assert.equal(w.document.getElementById('quota-percent').textContent,'88%');
});
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
test('Linux environment stays idle until the user explicitly installs it',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 const linux={supported:true,installed:false,enabled:false,busy:false,state:'not_installed',label:'Debian',downloadBytes:152000000,requiredFreeBytes:300000000,availableBytes:900000000};
 w.mobileCodexEvent('state',{...snapshot,linux});await tick();
 assert.equal(calls.filter(c=>c.action==='linux.install').length,0);
 assert.match(w.document.getElementById('linux-environment-details').textContent,/Debian/);
 assert.match(w.document.getElementById('linux-environment-details').textContent,/B/);
 w.MobileCodexLocale.set('en');w.mobileCodexEvent('state',{...snapshot,linux});await tick();
 assert.match(w.document.getElementById('linux-environment-description').textContent,/Codex and terminal/);
});
test('Linux progress and cancellation reflect native status events',async()=>{
 const {w,calls,snapshot}=setup();await tick();
 const linux={supported:true,installed:false,enabled:false,busy:true,state:'downloading',label:'Debian',downloadedBytes:25,totalBytes:100};
 w.mobileCodexEvent('state',{...snapshot,linux});await tick();
 const progress=w.document.getElementById('linux-environment-progress');
 assert.equal(progress.hidden,false);assert.equal(progress.max,100);assert.equal(progress.value,25);
 assert.equal(w.document.getElementById('linux-install').hidden,true);
 w.document.getElementById('linux-cancel').click();await tick();
  assert.equal(calls.filter(c=>c.action==='linux.cancel').length,1);
 w.mobileCodexEvent('linux.changed',{...linux,state:'verifying'});await tick();
 assert.equal(progress.hasAttribute('value'),false);
  w.mobileCodexEvent('linux.changed',{...linux,busy:false,state:'cancelled'});await tick();
 assert.match(w.document.getElementById('linux-environment-status').textContent,/취소/);
 assert.equal(w.document.getElementById('linux-install').textContent,'다시 시도');
});
test('Linux settings suppress duplicate bridge mutations and reset the terminal note after disabling',async()=>{
 let resolveInstall;
 const {w,calls,snapshot}=setup({'linux.install':()=>new Promise(resolve=>{resolveInstall=resolve;})});await tick();
 const linux={supported:true,installed:false,enabled:false,busy:false,state:'not_installed',label:'Debian'};
 w.mobileCodexEvent('state',{...snapshot,devtools:{bundled:false},linux});await tick();
 const install=w.document.getElementById('linux-install');install.click();install.click();await tick();
 assert.equal(calls.filter(c=>c.action==='linux.install').length,1);assert.equal(install.disabled,true);
 resolveInstall({...linux,installed:true,state:'ready',enabled:true});await tick();
 assert.equal(install.disabled,false);
 assert.match(w.document.getElementById('terminal-tools-note').textContent,/Debian/);
 w.mobileCodexEvent('linux.changed',{...linux,installed:true,state:'ready',enabled:false});await tick();
 assert.match(w.document.getElementById('terminal-tools-note').textContent,/번들 개발 도구는 사용할 수 없습니다/);
});
test('Linux installation errors retry only on click and failed enable never flips the toggle',async()=>{
 const {w,calls,snapshot}=setup({'linux.enable':()=>{throw new Error('<img src=x onerror=alert(1)>');}});await tick();
 const failed={supported:true,installed:false,enabled:false,busy:false,state:'error',label:'Debian',error:'network down'};
 w.mobileCodexEvent('state',{...snapshot,linux:failed});await tick();
 const install=w.document.getElementById('linux-install');assert.equal(install.textContent,'다시 시도');install.click();await tick();
 assert.equal(calls.filter(c=>c.action==='linux.install').length,1);
 const ready={supported:true,installed:true,enabled:false,busy:false,state:'ready',label:'Debian'};
 w.mobileCodexEvent('linux.changed',ready);await tick();
 const enabled=w.document.getElementById('linux-enabled');enabled.checked=true;enabled.dispatchEvent(new w.Event('change'));await tick();
 assert.equal(calls.filter(c=>c.action==='linux.enable').at(-1).args.enabled,true);
 assert.equal(enabled.checked,false);
 assert.equal(w.document.querySelector('#linux-environment-status img'),null);
 assert.match(w.document.getElementById('linux-environment-status').textContent,/<img/);
});
test('Linux incomplete installation can be explicitly removed without automatic retry',async()=>{
 const {w,calls,snapshot}=setup({'linux.remove':()=>({supported:true,installed:false,hasFiles:false,enabled:false,busy:false,state:'not_installed'})});await tick();
 w.mobileCodexEvent('state',{...snapshot,linux:{supported:true,installed:false,hasFiles:true,enabled:false,busy:false,state:'error',error:'incomplete installation'}});await tick();
 const remove=w.document.getElementById('linux-remove');
 assert.equal(remove.hidden,false);
 w.confirm=()=>false;remove.click();await tick();assert.equal(calls.filter(c=>c.action==='linux.remove').length,0);
 w.confirm=()=>true;remove.click();await tick();assert.equal(calls.filter(c=>c.action==='linux.remove').length,1);
 assert.equal(remove.hidden,true);assert.equal(calls.filter(c=>c.action==='linux.install').length,0);
});
test('account settings open official Chat sign-in without experiment controls',async()=>{
 const {w,calls}=setup();await tick();
 const button=w.document.getElementById('chat-account-login');
 assert.ok(button);
 button.click();await tick();
 assert.equal(calls.filter(call=>call.action==='ui.chatLogin').length,1);
 assert.ok(w.document.querySelector('[data-settings-panel="account"] #chat-account-login'));
 assert.doesNotMatch(w.document.body.textContent,/전송 실험|동작은 아직 검증되지 않았습니다/);
 assert.equal(w.document.getElementById('chat-login'),null);
});
test('submit is cancelled synchronously and calls native with chosen model and effort',async()=>{
 const {w,calls}=setup();await tick();w.document.getElementById('prompt').value='실제 파일 수정';
 const e=new w.Event('submit',{cancelable:true});w.document.getElementById('composer').dispatchEvent(e);
 assert.equal(e.defaultPrevented,true);await tick();
 assert.equal(calls.find(m=>m.action==='chat.send').args.text,'실제 파일 수정');
});
test('Chat opens official WebView without importing old local replies or replacing Codex state',async()=>{
 const {w,calls,snapshot}=setup({}, {drafts:{'chat-web-messages':JSON.stringify([{id:'old',role:'assistant',text:'Legacy Chat reply'}]),'chat-web-draft':'Legacy Chat draft'}});await tick();
 w.mobileCodexEvent('state',{...snapshot,busy:true,messages:[{id:'a',role:'assistant',text:'Codex answer'}]});
 const d=w.document;d.getElementById('prompt').value='Codex draft';
 d.getElementById('prompt').dispatchEvent(new w.Event('input'));
 d.getElementById('mode-chat').click();await tick();
 assert.equal(calls.filter(m=>m.action==='ui.chat.open').length,1);
 assert.equal(calls.filter(m=>m.action.startsWith('chat.web.')).length,0);
 assert.equal(d.getElementById('prompt').value,'Codex draft');
 assert.equal(d.body.classList.contains('chat-mode'),false);
 assert.match(d.getElementById('messages').textContent,/Codex answer/);
 assert.doesNotMatch(d.getElementById('messages').textContent,/Legacy Chat reply/);
 assert.equal(d.getElementById('chat-model-settings'),null);
 assert.equal(d.getElementById('mode-codex').getAttribute('aria-pressed'),'true');
 assert.ok(d.querySelector('.sidebar-top .brand + .mode-switch'));
 assert.equal(d.querySelector('.topbar .mode-switch'),null);
});
test('Chat opening failure leaves Codex usable and reports the failure',async()=>{
 const {w}=setup({'ui.chat.open':()=>{throw new Error('open failed');}});await tick();
 w.document.getElementById('mode-chat').click();await tick();
 assert.equal(w.document.body.classList.contains('chat-mode'),false);
 assert.match(w.document.getElementById('toast').textContent,/open failed/);
});
test('GPT-6-Pro uses an isolated transport without a remote Android bridge or credential observer',()=>{
 const main=fs.readFileSync('app/src/main/java/dev/mobilecodex/app/MainActivity.java','utf8');
 const app=fs.readFileSync(root+'app.js','utf8');
 const web=fs.readFileSync('app/src/main/java/dev/mobilecodex/app/ChatWebActivity.java','utf8');
 const transport=fs.readFileSync('app/src/main/java/dev/mobilecodex/app/ProWebTransport.java','utf8');
 const adapter=fs.readFileSync('app/src/main/assets/pro-web-transport.js','utf8');
 assert.equal(fs.existsSync('app/src/main/java/dev/mobilecodex/app/ChatWebTransport.java'),false);
 assert.match(main,/chat\.pro\.send|ProWebTransport/);
 assert.match(app,/consultPro/);assert.doesNotMatch(app,/chat\.pro\.send/);
 assert.doesNotMatch(app,/chat\.web\.|chat-web-messages|chat-web-draft/);
 assert.doesNotMatch(web,/addJavascriptInterface|backend-api\/f\/conversation/);
 assert.doesNotMatch(transport,/addJavascriptInterface|getCookie|accessToken|Authorization|backend-api/);
 assert.match(transport,/onPermissionRequest\(PermissionRequest request\)[\s\S]*?request\.deny\(\)/);
 assert.doesNotMatch(adapter,/document\.cookie|localStorage|sessionStorage|accessToken|Authorization|backend-api|window\.fetch\s*=/);
 for(const name of ['chat-web-custom.js','chat-icon-renderer.js']) {
   const script=fs.readFileSync('app/src/main/assets/'+name,'utf8');
   assert.doesNotMatch(script,/window\.fetch\s*=|XMLHttpRequest|backend-api\/f\/conversation/);
 }
});
test('Pro consultation uses the selected Codex request without changing its permissions',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,permissions:'danger-full-access',approvalMode:'allow-all'});
 assert.equal(d.querySelector('#model-list input[value="chatgpt-web:gpt-6-pro"]'),null);
 d.getElementById('add-attachment').click();d.getElementById('composer-consult-pro').click();await tick();
 assert.equal(d.getElementById('model-summary').textContent,'기본 모델');assert.equal(d.getElementById('effort').disabled,false);
 assert.equal(d.getElementById('permissions').value,'danger-full-access');assert.equal(d.getElementById('permissions').disabled,false);
 assert.equal(d.getElementById('approval-mode').value,'allow-all');assert.equal(d.getElementById('approval-mode').disabled,false);
 assert.equal(calls.some(call=>call.action==='permissions.set'||call.action==='approvals.set'),false);
 d.getElementById('prompt').value='이 변경을 검토해줘';d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();
 const sent=calls.find(call=>call.action==='chat.send');assert.ok(sent);assert.equal(sent.args.consultPro,true);assert.equal(sent.args.model,'');
 assert.equal(calls.some(call=>call.action==='chat.pro.send'),false);
 w.mobileCodexEvent('state',{...snapshot,busy:true,proBusy:true,pendingProConsultation:{operationId:'pro'}});assert.equal(d.getElementById('activity-text').textContent,'Pro 답변 중');d.getElementById('stop').click();await tick();
 assert.equal(calls.at(-1).action,'chat.stop');
});
test('ChatGPT Pro message metadata is visible and uncertain sends warn against retry',async()=>{
 const {w,snapshot}=setup();await tick();
 w.mobileCodexEvent('state',{...snapshot,messages:[
  {id:'u',role:'user',text:'검토',backend:'chatgpt-web',source:'ChatGPT Pro',displayModel:'GPT-6-Pro',status:'uncertain'},
  {id:'a',role:'assistant',text:'답변',backend:'chatgpt-web',source:'ChatGPT Pro',status:'completed'}
 ]});
 const text=w.document.getElementById('messages').textContent;assert.match(text,/GPT-6-Pro/);assert.match(text,/ChatGPT Pro/);assert.match(text,/다시 보내기 전에/);
});
test('sidebar sections collapse independently and restore their state',async()=>{
 const {w,snapshot}=setup();await tick();const d=w.document;
 d.getElementById('toggle-projects').click();await tick();
 assert.equal(d.getElementById('projects').hidden,true);
 assert.equal(d.getElementById('sessions').hidden,false);
 w.mobileCodexEvent('state',snapshot);
 assert.equal(d.getElementById('projects').hidden,true);
 d.getElementById('toggle-history').click();await tick();
 assert.equal(d.getElementById('sessions').hidden,true);
 d.getElementById('toggle-projects').click();await tick();
 assert.equal(d.getElementById('projects').hidden,false);
 assert.equal(d.getElementById('toggle-projects').getAttribute('aria-expanded'),'true');
 assert.equal(d.querySelector('.topbar .sidebar-toggle use').getAttribute('href'),'#i-menu');
 assert.equal(d.querySelector('#files-toggle use').getAttribute('href'),'#i-panel');
 for(const id of ['new-chat','projects','sessions']) assert.ok(d.getElementById(id).closest('.sidebar-scroll'));
 for(const id of ['show-tools','settings','account-button']) assert.equal(d.getElementById(id).closest('.sidebar-bottom'),d.querySelector('.sidebar-bottom'));
 assert.equal(d.querySelector('.sidebar-bottom').parentElement,d.getElementById('sidebar'));
 assert.equal(d.querySelector('.sidebar-bottom').closest('.sidebar-scroll'),null);
 const restored=setup({}, {drafts:{'sidebar-section-sessions':'collapsed'}});await tick();
 assert.equal(restored.w.document.getElementById('sessions').hidden,true);
});
test('composer exposes approval review beside the model without changing file access',async()=>{
 const {w,calls,snapshot}=setup({'approvals.set':()=>({ok:true})});await tick();
 const select=w.document.getElementById('approval-mode');assert.equal(select.value,'auto-review');
 select.value='allow-all';select.dispatchEvent(new w.Event('change'));await tick();
 assert.equal(calls.filter(m=>m.action==='approvals.set').at(-1).args.mode,'allow-all');
 assert.equal(w.document.getElementById('permissions').value,'workspace-write');
 assert.match(select.title,/모두 허용/);
 w.mobileCodexEvent('state',{...snapshot,busy:true,approvalMode:'allow-all'});
 assert.equal(select.disabled,true);
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
test('plugins, skills and MCP servers have separate tabs with counts and search',async()=>{
 const {w}=setup({'rpc':m=>{
   if(m.args.method==='plugin/list') return {marketplaces:[{name:'local',plugins:[{name:'Alpha plugin',id:'a'},{name:'Beta plugin',id:'b'}]}]};
   if(m.args.method==='skills/list') return {data:[{skills:[{name:'writer',description:'Drafts docs',path:'/s/writer',enabled:true}]}]};
   return {data:[{name:'github',tools:{a:{},b:{}}},{name:'linear',tools:{}},{name:'figma',tools:{}}]};
 }});await tick();w.document.getElementById('show-tools').click();await tick();w.document.getElementById('show-tools-panel').click();await tick();await tick();
 const d=w.document,tabs=[...d.querySelectorAll('.tools-tab')],panels=[...d.querySelectorAll('.tools-panel')];
 assert.deepEqual(tabs.map(t=>t.querySelector('.tools-count').textContent),['2','1','3']);
 assert.deepEqual(panels.map(p=>p.hidden),[false,true,true]);
 tabs[2].click();assert.deepEqual(panels.map(p=>p.hidden),[true,true,false]);assert.equal(tabs[2].getAttribute('aria-selected'),'true');
 const search=d.querySelector('.tools-search');search.value='lin';search.dispatchEvent(new w.Event('input'));
 assert.deepEqual([...panels[2].querySelectorAll('.tool-card')].filter(c=>!c.hidden).map(c=>c.querySelector('strong').textContent),['linear']);
 search.value='nothing matches';search.dispatchEvent(new w.Event('input'));assert.equal(d.querySelector('.tools-no-match').hidden,false);
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

test('reset credits show available count and consume one after confirmation',async()=>{
 let reads=0, consumed=[];
 const {w,calls}=setup({'rpc':m=>{
   if(m.args.method==='account/rateLimits/read') { reads++; return {rateLimits:{primary:{usedPercent:40}},rateLimitResetCredits:{availableCount:2,credits:[resetCredit('late',2100000000),resetCredit('early',2000000000)]}}; }
   if(m.args.method==='account/rateLimitResetCredit/consume') { consumed.push(m.args.params); return {outcome:'reset'}; }
   return {data:[]};
 }}); await tick(); w.document.querySelector('[data-settings-tab="account"]').click(); await tick(); await tick();
 const d=w.document; assert.match(d.getElementById('reset-credits').textContent,/2개 사용 가능/);
 const use=d.getElementById('reset-credit-use'); assert.ok(use); use.click(); for(let i=0;i<5;i++) await tick();
 assert.equal(consumed.length,1); assert.equal(consumed[0].creditId,'early'); assert.ok(consumed[0].idempotencyKey);
 assert.equal(reads,2); assert.match(d.getElementById('reset-credits').textContent,/사용 한도를 초기화했습니다/);
});

test('completed reset cannot spend another credit when the follow-up usage read fails',async()=>{
 let reads=0, consumed=0;
 const {w}=setup({'rpc':m=>{
   if(m.args.method==='account/rateLimits/read') { if(++reads>1) throw new Error('offline'); return {rateLimits:{},rateLimitResetCredits:{availableCount:2,credits:[resetCredit('first',2000000000),resetCredit('second',2100000000)]}}; }
   if(m.args.method==='account/rateLimitResetCredit/consume') { consumed++; return {outcome:'alreadyRedeemed'}; }
   return {data:[]};
 }}); await tick(); w.document.querySelector('[data-settings-tab="account"]').click(); await tick(); await tick();
 w.document.getElementById('reset-credit-use').click(); for(let i=0;i<5;i++) await tick();
 assert.equal(consumed,1); assert.equal(reads,2);
 assert.equal(w.document.getElementById('reset-credit-use'),null);
 assert.match(w.document.getElementById('reset-credits').textContent,/사용 한도를 초기화했습니다/);
});

test('reset credits omit the action when count is unavailable or zero',async()=>{
 let count=0;
 const {w}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?{rateLimits:{},rateLimitResetCredits:count++?{availableCount:0}:{credits:null}}:{data:[]}}); await tick();
 w.document.querySelector('[data-settings-tab="account"]').click(); await tick(); await tick();
 assert.match(w.document.getElementById('reset-credits').textContent,/정보를 사용할 수 없습니다/); assert.equal(w.document.getElementById('reset-credit-use'),null);
 w.document.getElementById('usage-refresh').click(); await tick(); await tick();
 assert.match(w.document.getElementById('reset-credits').textContent,/사용 가능한 초기화권이 없습니다/); assert.equal(w.document.getElementById('reset-credit-use'),null);
});

test('reset credit retry reuses key, while account change drops stale response',async()=>{
 let rejectConsume, consumeCalls=0, attempts=[], reads=0;
 const {w,snapshot}=setup({'rpc':m=>{
   if(m.args.method==='account/rateLimits/read') return {rateLimits:{},rateLimitResetCredits:{availableCount:2,credits:++reads===1?[resetCredit('first',2000000000),resetCredit('second',2100000000)]:[resetCredit('first',2200000000),resetCredit('second',2100000000)]}};
   if(m.args.method==='account/rateLimitResetCredit/consume') { consumeCalls++; attempts.push(m.args.params); return new Promise((resolve,reject)=>{rejectConsume=reject;}); }
   return {data:[]};
 }}); await tick(); w.document.querySelector('[data-settings-tab="account"]').click(); await tick(); await tick();
 const use=w.document.getElementById('reset-credit-use'); use.click(); await tick();
 const first=w.document.querySelector('#reset-credit-use'); assert.equal(first.disabled,true); rejectConsume(new Error('offline')); for(let i=0;i<3;i++) await tick();
 assert.match(w.document.getElementById('reset-credits').textContent,/다시 시도/);
 w.document.getElementById('usage-refresh').click(); await tick(); await tick();
 w.document.getElementById('reset-credit-use').click(); await tick(); assert.equal(consumeCalls,2); assert.equal(attempts[1].idempotencyKey,attempts[0].idempotencyKey); assert.equal(attempts[1].creditId,'first');
 const calls=w.document.querySelector('#reset-credits'); w.mobileCodexEvent('state',{...snapshot,account:{type:'chatgpt',email:'other@example.test'}}); await tick();
 assert.match(calls.textContent,/정보를 사용할 수 없습니다/); assert.doesNotMatch(calls.textContent,/사용 중/);
});

test('reset credit selection waits for complete expiry details and treats no expiry as last',async()=>{
 let snapshot={availableCount:2,credits:[resetCredit('never',null),resetCredit('soon',2000000000)]};
 const attempts=[];
 const {w}=setup({'rpc':m=>{
   if(m.args.method==='account/rateLimits/read') return {rateLimits:{},rateLimitResetCredits:snapshot};
   if(m.args.method==='account/rateLimitResetCredit/consume') { attempts.push(m.args.params); return {outcome:'reset'}; }
   return {data:[]};
 }}); await tick(); w.document.querySelector('[data-settings-tab="account"]').click(); await tick(); await tick();
 assert.equal(w.document.getElementById('reset-credit-use').disabled,false);
 assert.match(w.document.getElementById('reset-credits').textContent,/가장 먼저 만료/);
 snapshot={availableCount:2,credits:[resetCredit('soon',2000000000)]};
 w.document.getElementById('usage-refresh').click(); await tick(); await tick();
 assert.equal(w.document.getElementById('reset-credit-use').disabled,true);
 assert.match(w.document.getElementById('reset-credits').textContent,/만료일 전체를 확인할 수 없어/);
 w.document.getElementById('reset-credit-use').click(); await tick(); assert.equal(attempts.length,0);
 snapshot={availableCount:2,credits:null};
 w.document.getElementById('usage-refresh').click(); await tick(); await tick();
 assert.equal(w.document.getElementById('reset-credit-use').disabled,true);
 snapshot={availableCount:2,credits:[resetCredit('never',null),resetCredit('soon',2000000000)]};
 w.document.getElementById('usage-refresh').click(); await tick(); await tick();
 w.document.getElementById('reset-credit-use').click(); for(let i=0;i<5;i++) await tick();
 assert.equal(attempts[0].creditId,'soon');
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

test('sidebar quota shows remaining percent and opens graphed account details',async()=>{
 const limits={rateLimits:{primary:{usedPercent:37,windowDurationMins:300,resetsAt:2000000000}}};
 const {w,snapshot}=setup({'rpc':message=>message.args.method==='account/rateLimits/read'?limits:{data:[]}});await tick();
 w.mobileCodexEvent('state',{...snapshot,rateLimits:limits,accounts:[{key:'one',email:'me@example.test',planType:'pro',active:true}]});
 const d=w.document, ring=d.getElementById('quota-ring');
 assert.equal(d.getElementById('quota-percent').textContent,'63%');assert.equal(ring.style.getPropertyValue('--remaining'),'63');assert.match(ring.getAttribute('aria-label'),/63%/);
 d.getElementById('account-button').click();await tick();await tick();
 assert.equal(d.querySelector('[data-settings-panel="account"]').hidden,false);assert.match(d.getElementById('usage-limits').textContent,/남은 63%/);assert.equal(d.querySelectorAll('.usage-gauge .quota-ring').length,1);
});

test('quota captions use the API reset time while settings keep human window labels',async()=>{
 const now=new Date(), resetDate=new Date(now.getFullYear(),9,2,15,20,0), resetsAt=Math.floor(resetDate.getTime()/1000);
 const expected=resetDate.toLocaleString('ko-KR',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'});
 const limits={rateLimitsByLimitId:{codex:{limitName:'Codex',primary:{usedPercent:37,windowDurationMins:10080,resetsAt},secondary:{usedPercent:20,windowDurationMins:300,resetsAt}},other:{limitName:'Other',primary:{usedPercent:10,windowDurationMins:60,resetsAt}}}};
 const {w,calls,snapshot}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?limits:{data:[]}});await tick();
 w.mobileCodexEvent('state',{...snapshot,rateLimits:limits});const d=w.document;
 assert.equal(d.getElementById('quota-percent').textContent,'63%');
 assert.equal(d.getElementById('quota-caption').textContent,expected+' 초기화');
 assert.doesNotMatch(d.getElementById('quota-caption').textContent,/168시간|5시간/);
 assert.equal(calls.filter(call=>call.action==='rpc').length,0,'rendering quota never makes another request');
 d.getElementById('account-button').click();await tick();await tick();
 const text=d.getElementById('usage-limits').textContent;
 assert.match(text,new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')));assert.match(text,/주간 한도/);assert.match(text,/5시간 한도/);assert.match(text,/사용 한도/);assert.doesNotMatch(text,/168시간|1시간/);
 assert.equal(calls.filter(call=>call.action==='rpc'&&call.args.method==='account/rateLimits/read').length,1);
});

test('quota never infers a reset time from a duration when resetsAt is invalid',async()=>{
 let current={rateLimits:{primary:{usedPercent:37,windowDurationMins:10080,resetsAt:0}}};
 const {w,calls,snapshot}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?current:{data:[]}});await tick();const d=w.document;
 for(const resetsAt of [0,null,NaN,8640000000001]) {
   current={rateLimits:{primary:{usedPercent:37,windowDurationMins:10080,resetsAt}}};
   w.mobileCodexEvent('state',{...snapshot,rateLimits:current});
   assert.equal(d.getElementById('quota-percent').textContent,'63%');
   assert.equal(d.getElementById('quota-caption').textContent,'초기화 시각 확인 불가');
 }
 assert.equal(calls.filter(call=>call.action==='rpc').length,0);
 d.getElementById('account-button').click();await tick();await tick();
 assert.match(d.getElementById('usage-limits').textContent,/초기화 시각 확인 불가/);
 assert.doesNotMatch(d.getElementById('usage-limits').textContent,/168시간/);
 assert.equal(calls.filter(call=>call.action==='rpc'&&call.args.method==='account\/rateLimits\/read').length,1);
});

test('quota reset captions localize English without changing the percentage',async()=>{
 const resetDate=new Date(new Date().getFullYear(),9,2,15,20,0), resetsAt=Math.floor(resetDate.getTime()/1000);
 const limits={rateLimits:{primary:{usedPercent:37,windowDurationMins:10080,resetsAt}}};
 const {w,snapshot}=setup({'rpc':m=>m.args.method==='account/rateLimits/read'?limits:{data:[]}}, {language:'en'});await tick();
 w.mobileCodexEvent('state',{...snapshot,rateLimits:limits});const d=w.document;
 assert.equal(d.getElementById('quota-percent').textContent,'63%');assert.match(d.getElementById('quota-caption').textContent,/^Resets /);
 d.getElementById('account-button').click();await tick();await tick();
 assert.match(d.getElementById('usage-limits').textContent,/Resets [\s\S]*Weekly limit/);
});

test('account profiles can add, switch and remove without exposing credentials',async()=>{
 const {w,calls,snapshot}=setup({
   'auth.add':()=>({loginId:'new',verificationUrl:'https://auth.openai.com/codex/device',userCode:'ABCD'}),
   'auth.switch':()=>({}),'auth.remove':()=>({})
 });await tick();
 w.mobileCodexEvent('state',{...snapshot,accounts:[{key:'current',email:'one@example.test',active:true},{key:'other',email:'two@example.test',active:false}],rateLimits:{}});
 const d=w.document;d.getElementById('account-button').click();await tick();
 assert.doesNotMatch(d.getElementById('accounts-list').textContent,/access_token|refresh_token/);
 [...d.querySelectorAll('.account-profile-actions button')].find(button=>button.textContent==='전환').click();await tick();
 assert.deepEqual(calls.filter(call=>call.action==='auth.switch').at(-1).args,{key:'other'});
 [...d.querySelectorAll('.account-profile-actions button')].find(button=>button.textContent==='삭제').click();await tick();
 assert.deepEqual(calls.filter(call=>call.action==='auth.remove').at(-1).args,{key:'other'});
 d.getElementById('account-add').click();await tick();assert.ok(calls.some(call=>call.action==='auth.add'));assert.equal(d.getElementById('device-code').textContent,'ABCD');
});

test('account switch failures remain visible inside the settings dialog',async()=>{
 const initial={ready:true,busy:false,permissions:'workspace-write',models:[],messages:[],sessions:[],account:{type:'chatgpt',email:'one@example.test'},workspace:{selected:true,name:'Project'},cwd:'/test/project',threadId:'t',status:'연결됨',accounts:[{key:'current',email:'one@example.test',active:true},{key:'other',email:'two@example.test',active:false,needsLogin:false}],rateLimits:{}};
 const latest={...initial,accounts:[{key:'current',email:'one@example.test',active:true},{key:'other',email:'two@example.test',active:false,needsLogin:true}]};
 const {w,calls}=setup({'auth.switch':()=>{throw new Error('target unavailable');},state:()=>latest});await tick();
 w.mobileCodexEvent('state',initial);w.document.getElementById('account-button').click();await tick();
 [...w.document.querySelectorAll('.account-profile-actions button')].find(button=>button.textContent==='전환').click();await tick();await tick();
 assert.equal(calls.filter(call=>call.action==='auth.switch').length,1);
 assert.equal(w.document.getElementById('accounts-status').getAttribute('role'),'status');
 assert.match(w.document.getElementById('accounts-status').textContent,/계정 전환에 실패했습니다.*target unavailable/);
 assert.match(w.document.getElementById('accounts-list').textContent,/다시 로그인이 필요합니다/);
 assert.equal(w.document.getElementById('toast').hidden,true);
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
test('opening model settings refreshes available models without waiting to show the sheet',async()=>{
 let finish;
 const oldModels=[{id:'gpt-6-astra',displayName:'GPT-6-Astra'},{id:'gpt-5.6-sol',displayName:'GPT-5.6-Sol'}];
 const newModels=[...oldModels,{id:'gpt-6-sol',displayName:'GPT-6-Sol'},{id:'gpt-6-luna',displayName:'GPT-6-Luna'}];
 const {w,calls,snapshot}=setup({'models.refresh':()=>new Promise(resolve=>{finish=resolve;})},{mobile:true});await tick();
 const d=w.document;w.mobileCodexEvent('state',{...snapshot,models:oldModels});
 d.getElementById('model').value='gpt-6-astra';d.getElementById('model').dispatchEvent(new w.Event('change'));
 d.getElementById('composer-options').click();await tick();
 assert.equal(d.getElementById('options-dialog').open,true);
 assert.equal(calls.filter(c=>c.action==='models.refresh').length,1);
 assert.match(d.getElementById('model-list').textContent,/GPT-5.6-Sol/);
 assert.doesNotMatch(d.getElementById('model-list').textContent,/GPT-6-Luna/);
 assert.equal(d.getElementById('model-refresh-status').hidden,false);
 w.mobileCodexEvent('state',{...snapshot,models:newModels});finish({models:newModels});await tick();
 assert.match(d.getElementById('model-list').textContent,/GPT-6-Sol/);
 assert.match(d.getElementById('model-list').textContent,/GPT-6-Luna/);
 assert.equal(d.querySelector('#model-list input[value="gpt-6-astra"]').checked,true);
 assert.equal(d.getElementById('model-refresh-status').hidden,true);
 assert.equal(calls.some(c=>c.action==='runtime.stop'||c.action==='runtime.start'),false);
});
test('model refresh failure keeps the previous choices and explains their age',async()=>{
 const oldModels=[{id:'gpt-5.6-sol',displayName:'GPT-5.6-Sol'}];
 const {w,snapshot}=setup({'models.refresh':()=>{throw new Error('offline');}},{mobile:true});await tick();
 const d=w.document;w.mobileCodexEvent('state',{...snapshot,models:oldModels});
 d.getElementById('composer-options').click();await tick();
 assert.match(d.getElementById('model-list').textContent,/GPT-5.6-Sol/);
 assert.match(d.getElementById('model-refresh-status').textContent,/이전 목록/);
 assert.equal(d.getElementById('model-refresh-status').hidden,false);
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
 assert.deepEqual(calls.find(c=>c.action==='files.mutate').args,{operation:'mobile_rename',arguments:{path:'test.md',name:'new.md'},workspaceKey:''});
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
 w.document.getElementById('new-chat').click();await tick();assert.deepEqual(calls.find(c=>c.action==='chat.new').args,{workspaceKey:''});
});

test('general and workspace drafts keep attachments separately and picker receipts survive duplicate delivery',async()=>{
 const generalKey='draft:'+C.draftKey('', 't'), projectKey='draft:'+C.draftKey('p1','t');
 const {w,snapshot,calls}=setup({}, {drafts:{[generalKey]:'일반 초안',[generalKey+':context']:JSON.stringify({attachments:[{id:'g1',name:'general.txt'}],mentions:[],skills:[]}),[projectKey]:'프로젝트 초안'}});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:false,key:'',name:'일반'},threadId:'t'});assert.equal(w.document.getElementById('prompt').value,'일반 초안');assert.match(w.document.getElementById('draft-context').textContent,/general.txt/);
 w.mobileCodexEvent('attachments.picked',{receiptId:'r1',draftKey:projectKey.slice(6),attachments:[{id:'p1',name:'project.txt'}],errors:[]});await tick();
 w.mobileCodexEvent('attachments.picked',{receiptId:'r1',draftKey:projectKey.slice(6),attachments:[{id:'p1',name:'project.txt'}],errors:[]});await tick();
 assert.match(w.localStorage.getItem(projectKey+':context'),/project.txt/);assert.equal(calls.filter(c=>c.action==='attachments.ack').length,1);
});

test('a removed project keeps its same-thread draft when the conversation becomes general',async()=>{
 const {w,snapshot}=setup({
  'files.list':()=>({entries:[{name:'old.md',path:'old.md'}]}),
  'files.mention':()=>({name:'old.md',path:'old.md'}),
  rpc:m=>m.args.method==='skills/list'?{data:[{skills:[{name:'review',path:'/skills/review'}]}]}:{data:[]},
 });await tick();const d=w.document,p=d.getElementById('prompt');
 const project={...snapshot,models:[fastModel],threadId:'moved',workspace:{selected:true,key:'gone',name:'Gone',available:true},projects:[{key:'gone',name:'Gone',available:true}]};
 w.mobileCodexEvent('state',project);await tick();
 w.mobileCodexEvent('attachments.picked',{receiptId:'gone-attachment',draftKey:C.draftKey('gone','moved'),attachments:[{id:'keep',name:'keep.txt'}],errors:[]});await tick();
 p.value='@';p.setSelectionRange(1,1);p.click();await tick();d.querySelector('.autocomplete-item').click();await tick();
 p.value='$re';p.setSelectionRange(p.value.length,p.value.length);p.click();await tick();d.querySelector('.autocomplete-item').click();await tick();
 p.value='keep this draft';p.dispatchEvent(new w.Event('input'));d.getElementById('fast-mode').click();await tick();
 const source='draft:'+C.draftKey('gone','moved'), target='draft:'+C.draftKey('','moved');
 w.mobileCodexEvent('state',{...project,workspace:{selected:false,key:'',projectId:'',name:'Codex',hasLocalFolder:true,available:true,defaultWorkspace:true},projects:[],sessions:[{id:'moved',title:'Moved chat'}]});await tick();
 assert.equal(p.value,'keep this draft');assert.equal(w.localStorage.getItem(source),'keep this draft');assert.equal(w.localStorage.getItem(target),'keep this draft');
 const oldContext=JSON.parse(w.localStorage.getItem(source+':context')), movedContext=JSON.parse(w.localStorage.getItem(target+':context'));
 assert.deepEqual(movedContext.attachments,[{id:'keep',name:'keep.txt'}]);assert.deepEqual(movedContext.skills,[{name:'review',path:'/skills/review'}]);assert.deepEqual(movedContext.mentions,[]);
 assert.deepEqual(oldContext.mentions,[{name:'old.md',path:'old.md'}]);assert.equal(JSON.parse(w.localStorage.getItem(target+':options')).fastMode,true);
 assert.match(d.getElementById('toast').textContent,/원래 초안/);
});

test('picker cancellation and partial errors preserve the draft while accepted attachments are sent',async()=>{
 const {w,calls}=setup({'attachments.pick':()=>({cancelled:false,attachments:[{id:'a1',name:'ok.txt'}],errors:['bad.bin을 읽지 못했습니다.']})});await tick();const d=w.document;
 d.getElementById('add-attachment').click();d.getElementById('composer-attach').click();await tick();assert.match(d.getElementById('draft-context').textContent,/ok.txt/);
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

test('clicking the prompt preserves file search text and opens the root for bare @',async()=>{
 const {w,calls}=setup({
  'files.search':()=>({entries:[{name:'README.md',path:'README.md'}]}),
  'files.list':()=>({entries:[{name:'src',path:'src',directory:true}]}),
 });await tick();const d=w.document,p=d.getElementById('prompt');
 p.value='@README';p.setSelectionRange(p.value.length,p.value.length);p.click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='files.search').map(c=>c.args),[{query:'README'}]);
 assert.equal(calls.some(c=>c.action==='files.list'),false);
 assert.match(d.getElementById('autocomplete').textContent,/README.md/);
 assert.equal(p.value,'@README');
 p.value='@';p.setSelectionRange(1,1);p.click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='files.list').map(c=>c.args),[{path:''}]);
 assert.match(d.getElementById('autocomplete').textContent,/src\//);
 assert.equal(calls.some(c=>c.action==='chat.send'),false);
});

test('clicking the prompt keeps the skill query filtered and selectable',async()=>{
 const {w,calls}=setup({rpc:m=>m.args.method==='skills/list'?{data:[{skills:[
  {name:'review',path:'/skills/review'},
  {name:'deploy',path:'/skills/deploy'},
  {name:'review-disabled',path:'/skills/disabled',enabled:false},
 ]}]}:{data:[]}});await tick();const d=w.document,p=d.getElementById('prompt');
 p.value='$re';p.setSelectionRange(p.value.length,p.value.length);p.click();await tick();
 const labels=[...d.querySelectorAll('.autocomplete-item strong')].map(el=>el.textContent);
 assert.ok(labels.includes('$review'));
 assert.equal(labels.includes('$deploy'),false);
 assert.equal(labels.includes('$review-disabled'),false);
 assert.equal(p.value,'$re');
 [...d.querySelectorAll('.autocomplete-item')].find(el=>el.querySelector('strong').textContent==='$review').click();await tick();
 assert.equal(p.value,'$review ');
 assert.equal(calls.some(c=>c.action==='files.list'||c.action==='chat.send'),false);
});

test('picker result lands in its original workspace draft after the user switches projects',async()=>{
 let finish;const {w,snapshot}=setup({'attachments.pick':()=>new Promise(resolve=>finish=resolve)});await tick();
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'one',name:'One'},threadId:'t'});w.document.getElementById('add-attachment').click();w.document.getElementById('composer-attach').click();await tick();
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
 d.getElementById('add-project').click();await tick();
 [...d.querySelectorAll('#project-link-choices button')].find(b=>b.textContent==='로컬 폴더 연결').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='files.pick').at(-1).args,{});
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
 let finish; const delayed=new Promise(resolve=>{finish=resolve;}); const {w,snapshot,calls}=setup({'characters.select':()=>delayed}); await tick(); const chars={folderName:'Packs',folderConfigured:true,selectedPackId:'custom',packs:[{id:'builtin',name:'Builtin',valid:true,icons:{}},{id:'custom',name:'Custom',valid:true,icons:{done:'/packs/done.png'}}]}; w.mobileCodexEvent('state',{...snapshot,characters:chars,messages:[{id:'a',role:'assistant',text:'완료했어요.'}]}); w.document.getElementById('settings').click(); await tick(); w.document.querySelector('[data-pack-id="builtin"]').click(); await tick(); assert.equal(w.document.querySelector('[data-pack-id="builtin"]').disabled,true); assert.equal(w.document.getElementById('character-pack-folder-button').disabled,true); assert.equal(w.document.querySelector('.character-pack-option[aria-pressed="true"]').dataset.packId,'custom'); finish({cancelled:true}); await tick(); assert.equal(w.document.querySelector('.character-pack-option[aria-pressed="true"]').dataset.packId,'custom'); assert.equal(w.document.querySelector('[data-pack-id="builtin"]').disabled,false); assert.equal(calls.filter(call=>call.action==='characters.select').length,1);
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

test('general Codex workspace uses its default folder and orphan conversations fall back to general chat',async()=>{
 const {w,calls,snapshot}=setup({'projects.remove':()=>({...snapshot,workspace:{selected:false},projects:[]})});await tick();const d=w.document;
 const project={key:'kept',name:'Keep this project',selected:false,available:false,hasLocalFolder:true};
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:false,key:'',projectId:'',name:'Codex',hasLocalFolder:true,available:true,defaultWorkspace:true},projects:[project],sessions:[{id:'old',title:'보존 대화',workspaceKey:'gone'}]});
 assert.equal(d.getElementById('context-folder').textContent,'Codex');assert.equal(d.getElementById('header-project').textContent,'일반 대화');
 assert.equal(d.querySelectorAll('.detached-projects').length,0);assert.equal(d.querySelectorAll('#projects .session').length,0);assert.equal(d.querySelectorAll('#sessions .session').length,1);
 assert.equal(d.querySelector('#projects .new-thread').textContent,'폴더 다시 연결');
 d.getElementById('files-toggle').click();await tick();assert.equal(calls.some(c=>c.action==='files.list'),true);
  const menu=d.querySelector('.project-more');assert.ok(menu);menu.click();await tick();
 const confirmation=[];w.confirm=message=>{confirmation.push(message);return true;};
 [...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 목록에서 제거').click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='projects.remove').at(-1).args,{key:'kept'});assert.match(confirmation[0],/^프로젝트를 제거할까요\? 대화는 일반 대화로 이동해요\./);assert.match(confirmation[0],/파일은 그대로 유지됩니다/);
 d.querySelector('#sessions .session').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='chat.resume').at(-1).args,{id:'old'});
 });
test('project menus remain available while global import controls wait for running work',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document;
 const project={key:'a',projectId:'pa',name:'A',available:false,hasLocalFolder:false,bindings:[{key:'a',name:'A'}]};
 w.mobileCodexEvent('state',{...snapshot,projects:[project],sessions:[{id:'other',title:'Other',workspaceKey:'',busy:false}]});
 d.getElementById('add-project').click();await tick();assert.equal(d.querySelectorAll('#project-link-choices button').length,3);
 w.mobileCodexEvent('state',{...snapshot,proBusy:true,projects:[project],sessions:[{id:'other',title:'Other',workspaceKey:'',busy:false}]});
 assert.equal(d.getElementById('add-project').disabled,true);assert.ok([...d.querySelectorAll('#project-link-choices button')].every(button=>button.disabled));assert.equal(d.querySelector('.project-more').disabled,false);assert.equal(d.querySelector('.project-new').disabled,true);assert.equal(d.querySelector('#projects .new-thread').disabled,true);
 d.getElementById('add-project').click();await tick();assert.equal(calls.some(c=>/^projects\.(create|import|merge|prefer|rename|remove)$/.test(c.action)||c.action==='files.pick'),false);
 w.mobileCodexEvent('state',{...snapshot,projects:[project],sessions:[{id:'other',title:'Other',workspaceKey:'',busy:true}]});
 assert.equal(d.getElementById('add-project').disabled,true);assert.equal(d.querySelector('.project-more').disabled,false);assert.equal(d.querySelector('#projects .new-thread').disabled,true);
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

test('shared projects group conversations without changing their original workspace keys',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document;
 const project={key:'b',projectId:'shared',name:'Shared',selected:true,available:true,workspaceKeys:['a','b'],bindings:[{key:'a',name:'A',uri:'content://a',hasLocalFolder:true},{key:'b',name:'B',uri:'content://b',hasLocalFolder:true}]};
 w.mobileCodexEvent('state',{...snapshot,workspace:{selected:true,key:'a',name:'Shared'},projects:[project],sessions:[{id:'one',workspaceKey:'a',title:'From A'},{id:'two',workspaceKey:'b',title:'From B'}]});
 assert.equal(d.querySelectorAll('.project-tree').length,1);assert.equal(d.querySelectorAll('.detached-projects').length,0);
 assert.equal(d.querySelectorAll('#projects .session').length,2);
 d.querySelectorAll('#projects .session')[0].click();await tick();assert.deepEqual(calls.filter(c=>c.action==='chat.resume').at(-1).args,{id:'one'});
 d.querySelector('.project-new').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='chat.new').at(-1).args,{workspaceKey:'b'});
});
test('project merge requires confirmation, handles failure, and suppresses duplicate writes',async()=>{
 let confirmResult=false, resolveMerge, fail=true;
 const {w,calls,snapshot}=setup({'projects.merge':()=>fail?Promise.reject(new Error('storage full')):new Promise(resolve=>{resolveMerge=resolve;})},{confirm:()=>confirmResult});
 await tick();const d=w.document;
 const projects=[{key:'a',projectId:'pa',name:'Same',available:true,bindings:[{key:'a',uri:'content://one'}]},{key:'b',projectId:'pb',name:'Same',available:true,bindings:[{key:'b',uri:'content://two'}]}];
 w.mobileCodexEvent('state',{...snapshot,projects});d.querySelector('.project-more').click();await tick();
 [...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 병합').click();await tick();
 let choice=d.querySelector('#project-link-choices button');assert.match(choice.textContent,/content:\/\/two/);
 choice.click();await tick();assert.equal(calls.some(c=>c.action==='projects.merge'),false);
 confirmResult=true;choice.click();await tick();assert.equal(d.getElementById('project-link-dialog').open,true);assert.match(d.getElementById('toast').textContent,/storage full/);
 assert.equal(d.querySelectorAll('.project-tree').length,2);
 fail=false;choice.click();choice.click();await tick();assert.equal(calls.filter(c=>c.action==='projects.merge').length,2);
 assert.deepEqual(calls.filter(c=>c.action==='projects.merge').at(-1).args,{sourceKey:'a',targetKey:'b'});
 resolveMerge({projects:[{...projects[1],workspaceKeys:['a','b']}]});await tick();await tick();
 assert.equal(d.getElementById('project-link-dialog').open,false);assert.equal(d.querySelectorAll('.project-tree').length,1);
 assert.equal(d.activeElement.dataset.projectMenuKey,'b');
});
test('default local binding is persisted without switching an existing conversation',async()=>{
 const {w,calls,snapshot}=setup({'projects.prefer':()=>({})});await tick();const d=w.document;
 const project={key:'a',projectId:'p',name:'Shared',available:true,workspaceKeys:['a','b'],bindings:[{key:'a',name:'A',uri:'content://a'},{key:'b',name:'B',uri:'content://b'}]};
 w.mobileCodexEvent('state',{...snapshot,projects:[project]});d.querySelector('.project-more').click();await tick();
 [...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='기본 로컬 폴더').click();await tick();
 d.querySelectorAll('#project-link-choices button')[1].click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='projects.prefer').at(-1).args,{key:'b'});
 assert.equal(calls.some(c=>c.action==='projects.select'||c.action==='chat.new'),false);
});
test('unbound projects keep history readable but block send including keyboard submit',async()=>{
 const {w,calls,snapshot}=setup();await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,workspace:{key:'none',selected:true,available:false},projects:[{key:'none',projectId:'p',name:'Remote',hasLocalFolder:false,available:false}],sessions:[{id:'saved',workspaceKey:'none',title:'Saved'}]});
 assert.match(d.getElementById('projects').textContent,/로컬 폴더 연결/);assert.equal(d.querySelector('#projects .sidebar-empty'),null);
 assert.equal(d.querySelector('.project-new').disabled,true);assert.equal(d.querySelector('#projects .session').disabled,false);
 d.getElementById('prompt').value='Run';d.getElementById('prompt').dispatchEvent(new w.Event('input'));
 assert.equal(d.getElementById('send').disabled,true);
 d.getElementById('composer').dispatchEvent(new w.Event('submit',{cancelable:true,bubbles:true}));await tick();
 assert.equal(calls.some(c=>c.action==='chat.send'),false);
 d.querySelector('#projects .new-thread').click();await tick();assert.deepEqual(calls.filter(c=>c.action==='files.pick').at(-1).args,{projectKey:'none'});
});
test('project creation without a folder persists only after entering a name',async()=>{
 const {w,calls}=setup();await tick();const d=w.document;
 d.getElementById('add-project').click();await tick();[...d.querySelectorAll('#project-link-choices button')].find(b=>b.textContent==='폴더 없이 프로젝트 만들기').click();await tick();
 d.getElementById('input-value').value='Metadata only';d.getElementById('input-confirm').click();await tick();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='projects.create').at(-1).args,{name:'Metadata only'});
 assert.equal(calls.some(c=>c.action==='files.pick'||c.action==='runtime.start'),false);
});
test('shared project choices escape project content and translate action labels',async()=>{
 const {w,snapshot}=setup({}, {language:'en'});await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,projects:[{key:'a',projectId:'pa',name:'A'},{key:'b',projectId:'pb',name:'<img src=x onerror=bad()>',bindings:[{uri:'<script>bad()</script>'}]}]});
 d.querySelector('.project-more').click();await tick();[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='Merge projects').click();await tick();
 assert.equal(d.querySelectorAll('#project-link-choices img, #project-link-choices script').length,0);
 assert.match(d.getElementById('project-link-choices').textContent,/<script>/);
});

test('project import previews safely, cancels without applying, and uses the exact confirmation token',async()=>{
 let cancelled=false, fail=false, applied=0;
 const project={key:'new',projectId:'proj_new',name:'<img src=x>',nameConflicts:['Desktop','Phone'],available:false,hasLocalFolder:false};
 const {w,calls}=setup({
  'ui.projects.importFile':()=>cancelled?{cancelled:true}:{content:'portable-content'},
  'projects.import.preview':()=>({token:'exact-preview',result:{addedEvents:2,summary:{projects:[project],linkCount:1}}}),
  'projects.import.apply':()=>{applied++;if(fail)throw new Error('stale preview');return{projects:[project]};}
 }); await tick();const d=w.document;
 const pick=async()=>{d.getElementById('add-project').click();await tick();[...d.querySelectorAll('#project-link-choices button')].find(b=>b.textContent==='프로젝트 가져오기').click();await tick();await tick();};
 cancelled=true;await pick();assert.equal(calls.some(c=>c.action==='projects.import.preview'),false);d.getElementById('project-link-dialog').close();
 cancelled=false;await pick();assert.equal(applied,0);assert.match(d.getElementById('project-link-description').textContent,/이름 충돌/);assert.equal(d.querySelector('#project-link-dialog img'),null);
 d.getElementById('project-link-dialog').close();assert.equal(applied,0);
 await pick();fail=true;d.querySelector('#project-link-choices button').click();await tick();assert.equal(d.getElementById('project-link-dialog').open,true);assert.match(d.getElementById('toast').textContent,/stale preview/);
 fail=false;d.querySelector('#project-link-choices button').click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='projects.import.apply').at(-1).args,{content:'portable-content',token:'exact-preview'});
 assert.equal(d.getElementById('project-link-dialog').open,false);assert.equal(d.querySelector('.project-new').disabled,true);
});
test('project export asks before opening a destination and conflict resolution can select current display name',async()=>{
 let approved=false;
 const bundle={format:'mobile-codex-projects',schemaVersion:1,events:[]};
 const {w,calls,snapshot}=setup({'projects.export':()=>bundle},{confirm:()=>approved}); await tick();const d=w.document;
 w.mobileCodexEvent('state',{...snapshot,projects:[{key:'a',projectId:'proj_a',name:'Desktop',nameConflicts:['Desktop','Phone'],available:true}]});
 const exportIt=async()=>{d.querySelector('.project-more').click();await tick();[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 내보내기').click();await tick();};
 await exportIt();assert.equal(calls.some(c=>c.action==='ui.projects.exportFile'),false);
 approved=true;await exportIt();assert.equal(calls.filter(c=>c.action==='ui.projects.exportFile').at(-1).args.content,JSON.stringify(bundle));
 [...d.querySelectorAll('#projects button')].find(b=>b.textContent==='이름 충돌 해결').click();await tick();d.querySelector('#project-link-choices button').click();await tick();
 assert.deepEqual(calls.filter(c=>c.action==='projects.rename').at(-1).args,{key:'a',name:'Desktop'});
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
 assert.equal(w.document.documentElement.lang,'en');assert.match(w.document.getElementById('prompt').placeholder,/Ask Codex/);
 assert.deepEqual(JSON.parse(JSON.stringify(w.MobileCodexEnglish)), JSON.parse(fs.readFileSync('app/src/main/assets/translations-en.json','utf8')));
});
test('inline dictation displays partial text, supports done and cancel, and cannot auto-send',async()=>{
 const {w,calls}=setup({}, {language:'en'});await tick();const d=w.document;
 d.getElementById('prompt').value='Existing draft';
 w.mobileCodexEvent('voice.state',{phase:'listening',partial:'partial voice',level:0.6,elapsedMs:4200});
 assert.equal(d.getElementById('dictation').hidden,false);assert.equal(d.getElementById('composer').classList.contains('composer-expanded'),true);assert.match(d.getElementById('dictation-status').textContent,/Listening/);
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
function backdropPointer(w, dialog, x, y, target = dialog) {
 dialog.getBoundingClientRect = () => ({left:20,right:320,top:200,bottom:600});
 sheetPointer(w,target,'pointerdown',x,y);
 sheetPointer(w,target,'pointerup',x,y);
}
test('outside taps dismiss sheets and centered dialogs, while inside taps stay open',async()=>{
 const {w}=setup({}, {mobile:true});await tick();const d=w.document;
 d.getElementById('composer-options').click();await tick();
 const sheet=d.getElementById('options-dialog');
 backdropPointer(w,sheet,100,300);
 assert.equal(sheet.open,true);
 backdropPointer(w,sheet,100,100,sheet.querySelector('.dialog-head'));
 assert.equal(sheet.open,true);
 backdropPointer(w,sheet,100,100);
 assert.equal(sheet.open,false);
 d.getElementById('settings').click();await tick();
 const modal=d.getElementById('settings-dialog');
 backdropPointer(w,modal,400,300);
 assert.equal(modal.open,false);
});
test('outside taps respect unsaved changes and do not answer approval requests',async()=>{
 const {w,responses}=setup({'instructions.read':()=>({content:'Saved instructions',activePath:'/private/AGENTS.md'})},{mobile:true});await tick();const d=w.document;
 d.getElementById('settings').click();await tick();d.querySelector('[data-settings-tab="personal"]').click();await tick();
 d.getElementById('instructions-editor').value='Unsaved edit';w.confirm=()=>false;
 const settings=d.getElementById('settings-dialog');backdropPointer(w,settings,100,100);
 assert.equal(settings.open,true);assert.equal(d.getElementById('instructions-editor').value,'Unsaved edit');
 w.confirm=()=>true;backdropPointer(w,settings,100,100);assert.equal(settings.open,false);
 w.mobileCodexEvent('server.request',{key:'approval',method:'item/commandExecution/requestApproval',params:{threadId:'t',command:'true'}});
 const request=d.getElementById('request-dialog');assert.equal(request.open,true);
 backdropPointer(w,request,100,100);assert.equal(request.open,false);assert.equal(responses.length,0);
 w.mobileCodexEvent('state',{ready:true,busy:false,permissions:'workspace-write',models:[],messages:[],sessions:[],account:{type:'chatgpt'},workspace:{selected:true,name:'Project'},cwd:'/test/project',threadId:'t',status:'연결됨'});
 assert.equal(request.open,true);
});
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
test('composer stays compact until focus and expands without losing the draft',async()=>{
 const {w}=setup();await tick();const d=w.document,composer=d.getElementById('composer'),prompt=d.getElementById('prompt');
 assert.equal(composer.classList.contains('composer-expanded'),false);prompt.value='보존할 초안';prompt.focus();assert.equal(composer.classList.contains('composer-expanded'),true);
 prompt.blur();await tick();assert.equal(prompt.value,'보존할 초안');assert.equal(composer.classList.contains('composer-expanded'),false);
});
test('project tree has one project plus and no duplicate general-chat row',async()=>{
 const {w,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,projects:[{key:'p',name:'Project',selected:true,available:true}],sessions:[{id:'s',title:'Existing',workspaceKey:'p'}]});
 const d=w.document;assert.equal(d.querySelectorAll('.general-project').length,0);assert.equal(d.querySelectorAll('.project-tree .project-new').length,1);assert.equal(d.querySelectorAll('.project-tree .project-sessions .new-thread').length,0);
});
test('session list marks background work and approval separately',async()=>{
 const {w,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,sessions:[{id:'busy',title:'Working',workspaceKey:'p',busy:true},{id:'approval',title:'Needs approval',workspaceKey:'p',approvalPending:true}]});
 assert.equal(w.document.querySelectorAll('.session-progress-indicator').length,1);assert.equal(w.document.querySelectorAll('.session-approval-indicator').length,1);
});
test('notification settings are persisted through the native bridge',async()=>{
 const {w,calls}=setup({'notifications.state':()=>({enabled:true,vibration:true}),'notifications.configure':m=>m.args});await tick();w.document.getElementById('settings').click();await tick();
 const toggle=w.document.getElementById('notifications-toggle');assert.ok(toggle);toggle.checked=false;toggle.dispatchEvent(new w.Event('change'));await tick();
 assert.deepEqual(calls.find(c=>c.action==='notifications.configure').args,{enabled:false,vibration:true});
});
test('background message deltas cannot alter the visible conversation',async()=>{
 const {w,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,threadId:'current',messages:[{id:'m',role:'assistant',text:'현재'}]});
 w.mobileCodexEvent('message.delta',{threadId:'background',id:'m',delta:' 침범'});assert.match(w.document.getElementById('messages').textContent,/현재/);assert.doesNotMatch(w.document.getElementById('messages').textContent,/침범/);
});
test('background errors do not become a toast for the visible conversation',async()=>{
 const {w,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,threadId:'current'});w.mobileCodexEvent('error',{threadId:'background',message:'다른 대화 실패'});
 assert.equal(w.document.getElementById('toast').hidden,true);w.mobileCodexEvent('error',{threadId:'current',message:'현재 대화 실패'});assert.equal(w.document.getElementById('toast').hidden,false);
});
test('request queue keeps background approvals hidden and opens them after switching sessions',async()=>{
 const {w,snapshot}=setup();await tick();
 w.mobileCodexEvent('server.request',{key:'background',method:'item/commandExecution/requestApproval',params:{threadId:'other',command:'background'}});
 assert.equal(w.document.getElementById('request-dialog').open,false);
 w.mobileCodexEvent('server.request',{key:'current',method:'item/commandExecution/requestApproval',params:{threadId:'t',command:'current'}});
 assert.equal(w.document.getElementById('request-dialog').open,true);assert.match(w.document.getElementById('request-detail').textContent,/current/);
 w.mobileCodexEvent('server.resolved',{key:'current'});assert.equal(w.document.getElementById('request-dialog').open,false);
 w.mobileCodexEvent('state',{...snapshot,threadId:'other',sessions:[{id:'other',title:'다른 대화',workspaceKey:'',approvalPending:true}]});
 assert.equal(w.document.getElementById('request-dialog').open,true);assert.match(w.document.getElementById('request-detail').textContent,/background/);
});
test('session title grows before the fixed activity indicator at the row end',async()=>{
 const {w,snapshot}=setup();await tick();w.mobileCodexEvent('state',{...snapshot,sessions:[{id:'t',title:'아주 긴 대화 제목이 잘려야 하는 항목',workspaceKey:'',busy:true}]});
 const row=w.document.querySelector('#sessions .session'),title=row.querySelector('.session-title'),indicator=row.querySelector('.session-progress-indicator');
 assert.ok(title&&indicator);assert.equal(title.nextElementSibling,indicator);assert.match(fs.readFileSync(root+'app.css','utf8'),/\.session-title\s*\{[^}]*flex:1/);
});
test('notification permission guidance follows the Android permission state',async()=>{
 let permission=false;const {w,calls}=setup({'notifications.state':()=>({enabled:true,vibration:true,permission}),'notifications.openSettings':()=>({ok:true})});await tick();
 w.document.getElementById('settings').click();await tick();await tick();const row=w.document.querySelector('.notification-permission-row');
 assert.equal(row.hidden,false);assert.equal(w.document.getElementById('notification-settings-button').textContent,'Android 알림 설정');w.document.getElementById('notification-settings-button').click();await tick();
 assert.ok(calls.some(call=>call.action==='notifications.openSettings'));permission=true;w.mobileCodexEvent('notifications.changed',{});await tick();await tick();assert.equal(row.hidden,true);
});

test('Codex user messages stay fully visible even when long',async()=>{
 const {w,snapshot}=setup();await tick();
 const text='<img src=x onerror=alert(1)>\n'+'긴 사용자 메시지 '.repeat(100);
 w.mobileCodexEvent('state',{...snapshot,messages:[{id:'long-user',role:'user',text}]});
 const body=w.document.querySelector('[data-id="long-user"] .user-message-text');
 assert.equal(body.textContent,text); assert.equal(body.querySelector('img'),null);
 assert.equal(body.classList.contains('is-collapsed'),false);
 assert.equal(w.document.querySelector('.user-message-toggle'),null);
});

test('sync is a settings tab and never asks for a personal access token',async()=>{
 const status={configured:false,authenticated:false,connected:false,account:'',repository:'',branch:'',lastSynced:0,selectedKeys:[],projects:[]};
 const {w,calls}=setup({'sync':m=>{assert.equal(m.action,'sync.status');return status;}});await tick();
 const d=w.document;assert.ok(d.querySelector('[data-settings-tab="sync"]'));
 assert.equal(d.querySelector('#sync-controls input[type="password"]'),null);
 assert.doesNotMatch(d.getElementById('sync-controls').innerHTML,/<input[^>]*(token|pat|client)/i);
 d.querySelector('[data-settings-tab="sync"]').click();await tick();await tick();
 assert.match(d.getElementById('sync-unavailable').textContent,/구성되지 않았습니다/);
 assert.equal(d.getElementById('sync-controls').hidden,true);
 assert.equal(calls.some(call=>call.action==='sync.login.start'),false);
});

test('sync device login is explicit and cancelling it invalidates the flow',async()=>{
 const status={configured:true,authenticated:false,connected:false,account:'',repository:'',branch:'main',lastSynced:0,selectedKeys:[],projects:[]};
 const {w,calls}=setup({'sync':m=>{
   if(m.action==='sync.status')return status;
   if(m.action==='sync.login.start')return {flowId:'flow-1',userCode:'ABCD-EFGH',verificationUri:'https://github.com/login/device',interval:60,expiresAt:Date.now()+60000};
   if(m.action==='sync.login.cancel')return status;
   throw Error('unexpected '+m.action);
 }});await tick();const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();
 d.getElementById('sync-login').click();await tick();
 assert.equal(d.getElementById('sync-device-flow').hidden,false);assert.equal(d.getElementById('sync-device-code').textContent,'ABCD-EFGH');
 assert.equal(calls.some(call=>call.action==='ui.externalBrowser'),false,'login never opens a browser by itself');
 d.getElementById('sync-open-browser').click();await tick();
 assert.ok(calls.some(call=>call.action==='ui.externalBrowser'&&call.args.url==='https://github.com/login/device'));
 assert.equal(calls.some(call=>call.action==='rpc'&&call.args.method.startsWith('sync.')),false);
 d.getElementById('sync-cancel-login').click();await tick();
 assert.ok(calls.some(call=>call.action==='sync.login.cancel'&&call.args.flowId==='flow-1'));
 assert.equal(d.getElementById('sync-device-flow').hidden,true);
});

test('sync rejects invalid state and summarizes verbose failures without exposing response bodies',async()=>{
 let response={}, fail=false;
 const {w}=setup({sync:()=>{if(fail)throw Error('<html>Authorization: Bearer private-token\n'+'trace line\n'.repeat(300));return response;}});
 await tick();const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();
 assert.match(d.getElementById('sync-status').textContent,/상태를 불러오지 못했습니다/);
 assert.equal(d.getElementById('sync-login').disabled,true);
 response={configured:true,authenticated:false,connected:false,projects:[],selectedKeys:[]};
 d.getElementById('sync-refresh').click();await tick();assert.equal(d.getElementById('sync-login').disabled,false);
 fail=true;d.getElementById('sync-login').click();await tick();
 assert.equal(d.getElementById('sync-status').textContent,'GitHub 로그인을 시작하지 못했습니다.');
 assert.doesNotMatch(d.getElementById('sync-status').textContent,/private-token|trace|html/);
 assert.equal(d.getElementById('sync-login').disabled,false,'failed login remains retryable');
});

test('sync browser launch failures are visible inline and keep the code available for retry',async()=>{
 const status={configured:true,authenticated:false,connected:false,projects:[],selectedKeys:[]};let attempts=0;
 const {w}=setup({sync:m=>m.action==='sync.login.start'?{flowId:'browser-flow',userCode:'ABCD-EFGH',verificationUri:'https://github.com/login/device',interval:60}:status,
  'ui.externalBrowser':()=>{attempts++;throw Error('Android exception\n'+'stack frame\n'.repeat(300));}});
 await tick();const d=w.document;d.getElementById('settings').click();await tick();d.querySelector('[data-settings-tab="sync"]').click();await tick();
 d.getElementById('sync-login').click();await tick();d.getElementById('sync-open-browser').click();await tick();
 assert.match(d.getElementById('sync-status').textContent,/브라우저를 열지 못했습니다/);
 assert.doesNotMatch(d.getElementById('sync-status').textContent,/stack frame/);
 assert.equal(d.getElementById('sync-device-code').textContent,'ABCD-EFGH');
 d.getElementById('sync-open-browser').click();await tick();assert.equal(attempts,2);
});

test('tool errors are compact and failed buttons show feedback inside the open modal',async()=>{
 const payload='<html>private-response\n'+'diagnostic line\n'.repeat(300);
 const {w}=setup({rpc:m=>{
  if(m.args.method==='plugin/list')return {marketplaces:[],marketplaceLoadErrors:[{message:payload}]};
  if(m.args.method==='skills/list')return {data:[]};
  if(m.args.method==='mcpServerStatus/list')return {data:[{name:'GitHub',tools:{},toolsError:payload}]};
  throw Error(payload);
 }});await tick();const d=w.document;d.getElementById('show-tools').click();await tick();d.getElementById('show-tools-panel').click();await tick();await tick();
 assert.doesNotMatch(d.getElementById('tools-list').textContent,/private-response|diagnostic line/);
 const login=[...d.querySelectorAll('#tools-list button')].find(b=>b.textContent==='로그인');assert.ok(login);
 login.click();await tick();const feedback=d.getElementById('toast');
 assert.equal(feedback.parentElement,d.getElementById('tools-dialog'));
 assert.equal(feedback.hidden,false);assert.match(feedback.textContent,/요청을 처리하지 못했습니다/);
 assert.equal(login.disabled,false);
});

test('sync keeps migrated GitHub credentials usable when device login is not configured',async()=>{
 const status={configured:false,authenticated:true,connected:false,account:'legacy-octo',repository:'legacy/projects',branch:'main',lastSynced:0,selectedKeys:[],projects:[]};
 const {w}=setup({'sync':m=>m.action==='sync.status'?status:{repositories:[{fullName:'legacy/projects'}],page:1,hasMore:false}});await tick();
 const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();await tick();
 assert.equal(d.getElementById('sync-controls').hidden,false);
 assert.equal(d.getElementById('sync-login').hidden,true);
 assert.equal(d.getElementById('sync-disconnect').hidden,false);
});

test('a device flow returned after leaving sync is cancelled without starting a poll',async()=>{
 let resolveStart;
 const status={configured:true,authenticated:false,connected:false,account:'',repository:'',branch:'main',lastSynced:0,selectedKeys:[],projects:[]};
 const {w,calls}=setup({'sync':m=>{
   if(m.action==='sync.status')return status;
   if(m.action==='sync.login.start')return new Promise(resolve=>{resolveStart=resolve;});
   if(m.action==='sync.login.cancel')return status;
   throw Error('unexpected '+m.action);
 }});await tick();const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();
 d.getElementById('sync-login').click();await tick();d.querySelector('[data-settings-tab="general"]').click();
 resolveStart({flowId:'late-flow',userCode:'LATE-CODE',verificationUri:'https://github.com/login/device',interval:60});await tick();await tick();
 assert.ok(calls.some(call=>call.action==='sync.login.cancel'&&call.args.flowId==='late-flow'));
 assert.equal(calls.some(call=>call.action==='sync.login.poll'),false);
});

test('sync persists selected uploads, previews incoming projects, applies only the preview, and leaves errors inline',async()=>{
 let status={configured:true,authenticated:true,connected:true,account:'octo',repository:'octo/projects',branch:'main',lastSynced:0,selectedKeys:[],projects:[{key:'local-a',projectId:'local-a',name:'Local A'}]};
 const {w,calls}=setup({'sync':m=>{
   if(m.action==='sync.status')return status;
   if(m.action==='sync.repositories')return {repositories:[{fullName:'octo/projects'}],page:1,hasMore:false};
   if(m.action==='sync.selection'){assert.deepEqual(m.args.keys,['local-a']);return status={...status,selectedKeys:['local-a']};}
   if(m.action==='sync.preview'){assert.deepEqual(m.args.keys,['local-a']);return {token:'preview-1',summary:{projects:[{projectId:'remote-a',name:'Remote A',nameConflicts:['Local A']}],eventCount:2,linkCount:1,conflictCount:1},addedEvents:0,uploadCount:1};}
   if(m.action==='sync.apply'){assert.deepEqual(m.args,{token:'preview-1'});return {...status,account:'octo',lastSynced:Date.UTC(2026,8,29),projects:[{key:'local-a',projectId:'local-a',name:'Merged A'}],workspace:{selected:false},changed:true};}
   throw Error('unexpected '+m.action);
 }});await tick();const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();await tick();
 const check=d.querySelector('#sync-project-list input');check.checked=true;check.dispatchEvent(new w.Event('change'));await tick();
 d.getElementById('sync-preview').click();await tick();
 assert.match(d.getElementById('sync-incoming-projects').textContent,/Remote A/);assert.match(d.getElementById('sync-incoming-projects').textContent,/이름 충돌/);assert.match(d.getElementById('sync-preview-summary').textContent,/새 변경 0개/);
 d.getElementById('sync-apply').click();await tick();await tick();
 assert.ok(calls.some(call=>call.action==='sync.apply'));
 assert.equal(d.getElementById('sync-preview-result').hidden,true);
 assert.equal(d.getElementById('logout').textContent,'로그아웃','sync account text must not replace the app account object');
 const failed=setup({'sync':m=>m.action==='sync.status'?status:Promise.reject(Error('remote failed'))});await tick();
 failed.w.document.querySelector('[data-settings-tab="sync"]').click();await tick();await tick();failed.w.document.getElementById('sync-preview').click();await tick();
 assert.match(failed.w.document.getElementById('sync-status').textContent,/remote failed/);
 assert.equal(failed.w.document.getElementById('sync-preview-result').hidden,true);
});

test('a failed sync apply consumes its preview and requires a fresh preview',async()=>{
 const status={configured:true,authenticated:true,connected:true,account:'octo',repository:'octo/projects',branch:'main',lastSynced:0,selectedKeys:[],projects:[]}; let applyCalls=0;
 const {w,calls}=setup({'sync':m=>{
   if(m.action==='sync.status')return status;
   if(m.action==='sync.repositories')return {repositories:[{fullName:'octo/projects'}],page:1,hasMore:false};
   if(m.action==='sync.preview')return {token:'single-use',summary:{projects:[],eventCount:0,linkCount:0,conflictCount:0},addedEvents:0,uploadCount:0};
   if(m.action==='sync.apply'){applyCalls++;throw Error('apply rejected');}
   throw Error('unexpected '+m.action);
 }});await tick();const d=w.document;d.querySelector('[data-settings-tab="sync"]').click();await tick();await tick();
 d.getElementById('sync-preview').click();await tick();d.getElementById('sync-apply').click();await tick();
 assert.equal(applyCalls,1);assert.equal(d.getElementById('sync-preview-result').hidden,true);assert.equal(d.getElementById('sync-apply').disabled,true);
 d.getElementById('sync-apply').click();await tick();assert.equal(applyCalls,1);
 assert.match(d.getElementById('sync-status').textContent,/apply rejected/);
 assert.equal(calls.filter(call=>call.action==='sync.apply').length,1);
});


test('deleting unrelated projects and conversations remains available during Codex or Pro work',async()=>{
 for (const mode of ['busy','proBusy','background']) {
  const {w,calls,snapshot}=setup();await tick();const d=w.document;
  const projects=[{key:'a',name:'A',available:true},{key:'b',name:'B',available:true}];
  const sessions=[{id:'a-chat',title:'A chat',workspaceKey:'a',busy:true},{id:'b-chat',title:'B chat',workspaceKey:'b',busy:false}];
  const current={...snapshot,threadId:mode==='background'?'b-chat':'a-chat',workspace:{key:mode==='background'?'b':'a',selected:true},projects,sessions,[mode==='background'?'busy':mode]:mode!=='background'};
  w.mobileCodexEvent('state',current);
  d.querySelector('[data-project-menu-key="b"]').click();await tick();
  const removal=()=>[...d.querySelectorAll('#project-actions button')].find(b=>b.textContent==='프로젝트 목록에서 제거');
  assert.equal(removal().disabled,false);
  w.mobileCodexEvent('state',current);assert.equal(removal().disabled,false,'live updates preserve target-scoped availability');
  removal().click();await tick();assert.deepEqual(calls.find(c=>c.action==='projects.remove').args,{key:'b'});
  d.querySelector('[data-project-menu-key="a"]').click();await tick();assert.equal(removal().disabled,true);
  d.getElementById('project-actions-dialog').close();
  [...d.querySelectorAll('.session-more')].find(b=>b.getAttribute('aria-label')==='B chat 관리').click();await tick();
  [...d.querySelectorAll('#session-actions button')].find(b=>b.textContent==='삭제').click();await tick();
  assert.deepEqual(calls.find(c=>c.action==='chat.delete').args,{id:'b-chat'});
 }
});
