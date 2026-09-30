const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const source = fs.readFileSync('app/src/main/assets/pro-web-transport.js', 'utf8');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
function setup(html, url = 'https://chatgpt.com/') {
  const dom = new JSDOM(html, {url, runScripts:'outside-only'}); opened.push(dom);
  const {window:w} = dom;
  w.Element.prototype.getBoundingClientRect = () => ({x:1,y:1,width:120,height:40,left:1,top:1,right:121,bottom:41});
  w.document.execCommand = (_name, _ui, text) => { const target=w.document.activeElement;target.textContent=text;return true; };
  w.eval(source); return {w, adapter:w.MCProWeb};
}

test('recognizes only the exact GPT-6 Pro option and confirms the composer model', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea" contenteditable="true"></div><button id="model" aria-haspopup="menu">GPT-5</button></form>'+
    '<div role="menu"><button role="menuitem">GPT-6 Pro</button><button role="menuitem">Pro Legacy</button></div>');
  const model=w.document.getElementById('model');
  w.document.querySelector('[role="menuitem"]').addEventListener('click',()=>{model.textContent='GPT-6 Pro';});
  assert.equal(adapter.inspect().status,'available');
  assert.equal(adapter.choosePro().status,'selected');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro');
  assert.equal(adapter.exactPro('Pro Legacy'),false);assert.equal(adapter.exactPro('GPT-6 Pro'),true);
});

test('fails closed when GPT-6 Pro is absent or ambiguous', () => {
  let value=setup('<div id="prompt-textarea"></div><button aria-haspopup="menu">GPT-5</button><div role="menu"><button role="menuitem">GPT-6</button></div>').adapter.choosePro();
  assert.equal(value.status,'unavailable');
  value=setup('<div id="prompt-textarea"></div><button aria-haspopup="menu">GPT-5</button><div role="menu"><button>GPT-6 Pro</button><button>6 Pro</button></div>').adapter.choosePro();
  assert.equal(value.status,'web_changed');
});

test('finds the dedicated project by exact name and rejects duplicates', () => {
  let value=setup('<a href="/g/g-p-one/project">mobile-codex-chat</a>').adapter.inspectProject('mobile-codex-chat');
  assert.equal(value.status,'available');assert.equal(value.projectPath,'/g/g-p-one/project');
  value=setup('<a hidden href="/g/g-p-one/project">mobile-codex-chat</a><a href="/g/g-p-one/project">mobile-codex-chat</a>').adapter.inspectProject('mobile-codex-chat');
  assert.equal(value.status,'available');assert.equal(value.projectPath,'/g/g-p-one/project');
  value=setup('<a href="/g/g-p-one/project">mobile-codex-chat</a><a href="/g/g-p-two/project">mobile-codex-chat</a>').adapter.inspectProject('mobile-codex-chat');
  assert.equal(value.status,'web_changed');assert.equal(value.reason,'project_ambiguous');
});

test('expands the project section before deciding that the project is missing', () => {
  const {w,adapter}=setup('<button id="projects" aria-expanded="false">Projects</button><button>New project</button>');
  let clicked=false;w.document.getElementById('projects').addEventListener('click',()=>clicked=true);
  const value=adapter.inspectProject('mobile-codex-chat');
  assert.equal(value.status,'opening_projects');assert.equal(clicked,true);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'unavailable');
});

test('creates the dedicated project only through an exact dialog', () => {
  const {w,adapter}=setup('<button id="new">New project</button><dialog open hidden role="dialog"><h2>Create project</h2><input type="text"><button id="create">Create project</button></dialog>');
  let openedProject=false,created=false;w.document.getElementById('new').addEventListener('click',()=>{openedProject=true;w.document.querySelector('dialog').hidden=false;});
  w.document.getElementById('create').addEventListener('click',()=>created=true);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'missing');
  assert.equal(adapter.startProjectCreation().status,'creation_opened');assert.equal(openedProject,true);
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'creation_submitted');
  assert.equal(w.document.querySelector('input').value,'mobile-codex-chat');assert.equal(created,true);
});

test('ignores the mobile sidebar dialog while filling the project creation dialog', () => {
  const {w,adapter}=setup('<div role="dialog" aria-label="사이드바"><h2>사이드바</h2><button>새 프로젝트</button></div>'+
    '<dialog open aria-labelledby="project-title"><h2 id="project-title">프로젝트 만들기</h2><input type="text"><button id="create">프로젝트 만들기</button></dialog>');
  let submitted=0;w.document.getElementById('create').addEventListener('click',()=>submitted++);
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'creation_submitted');
  assert.equal(w.document.querySelector('input').value,'mobile-codex-chat');assert.equal(submitted,1);
});

test('waits for project dialog rendering and React button enablement without duplicate create clicks', async () => {
  const {w,adapter}=setup('<div role="dialog" aria-label="사이드바"><h2>사이드바</h2></div>');
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'waiting');
  w.document.body.insertAdjacentHTML('beforeend','<dialog open><h2>Create project</h2><input type="text"><button id="create" disabled>Create project</button></dialog>');
  const input=w.document.querySelector('input'),button=w.document.getElementById('create');let submitted=0,changed=0;
  input.addEventListener('input',()=>{changed++;w.setTimeout(()=>{button.disabled=false;},0);});
  button.addEventListener('click',()=>submitted++);
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'waiting');
  assert.equal(submitted,0);
  await new Promise(resolve=>w.setTimeout(resolve,5));
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'creation_submitted');
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'creation_submitted');
  assert.equal(changed,1);assert.equal(submitted,1);
});

test('does not fill unrelated forms or choose between two real project dialogs', () => {
  let {w,adapter}=setup('<dialog open><h2>Rename conversation</h2><input type="text"><button>Create</button></dialog>');
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').status,'waiting');
  assert.equal(w.document.querySelector('input').value,'');
  ({w,adapter}=setup('<dialog open><h2>Create project</h2><input type="text"><button>Create project</button></dialog>'.repeat(2)));
  assert.equal(adapter.finishProjectCreation('mobile-codex-chat').reason,'project_dialog_ambiguous');
  assert.ok([...w.document.querySelectorAll('input')].every(input=>input.value===''));
});

test('observes the current conversation id and never needs a credential or API fetch', () => {
  let adapter=setup('<div id="prompt-textarea"></div><button aria-haspopup="menu">GPT-6 Pro</button><article data-message-author-role="assistant" data-message-id="reply-one">검토 답변</article>', 'https://chatgpt.com/c/conversation-one').adapter;
  let result=adapter.observe(0,'');assert.equal(result.status,'observed');assert.equal(result.conversationId,'conversation-one');assert.equal(result.conversationPath,'/c/conversation-one');assert.equal(result.remoteMessageId,'reply-one');
  adapter=setup('<article data-message-author-role="assistant">이어진 답변</article>', 'https://chatgpt.com/g/g-p-project/c/conversation-one').adapter;
  result=adapter.observe(0,'');assert.equal(result.conversationId,'conversation-one');assert.equal(result.conversationPath,'/g/g-p-project/c/conversation-one');
  assert.doesNotMatch(source,/accessToken|Authorization|document\.cookie|backend-api|window\.fetch\s*=/);
});

test('closes the mobile sidebar before checking an existing project and 6Pro', () => {
  const {w,adapter}=setup('<main aria-hidden="true"><h1>mobile-codex-chat</h1><form><div id="prompt-textarea"></div><button aria-haspopup="menu">6Pro</button></form></main>'+
    '<div role="dialog" aria-label="사이드바"><button id="close" aria-label="사이드바 닫기"></button><div role="button" data-sidebar-item>mobile-codex-chat</div><button>새 프로젝트</button></div>', 'https://chatgpt.com/g/g-p-existing/project');
  let closed=0;w.document.getElementById('close').addEventListener('click',()=>closed++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'closing_sidebar');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'closing_sidebar');
  assert.equal(closed,1);
  w.document.querySelector('[role="dialog"]').hidden=true;w.document.querySelector('main').removeAttribute('aria-hidden');
  assert.equal(adapter.inspectProject('mobile-codex-chat').projectPath,'/g/g-p-existing/project');
  assert.equal(adapter.prepareComposer().currentModel,'GPT-6 Pro');
});

test('opens a non-link sidebar project once instead of creating a duplicate', () => {
  const {w,adapter}=setup('<div role="dialog"><div id="project" role="button" data-sidebar-item><span>mobile-codex-chat</span><button aria-label="mobile-codex-chat 프로젝트 옵션 열기"></button></div><button>새 프로젝트</button></div>');
  let selected=0;w.document.getElementById('project').addEventListener('click',()=>selected++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'opening_project');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'waiting');
  assert.equal(selected,1);
  w.history.replaceState({},'', '/g/g-p-existing/project');
  w.document.querySelector('[role="dialog"]').hidden=true;w.document.body.insertAdjacentHTML('beforeend','<h1>mobile-codex-chat</h1>');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'available');
});

test('waits for project hydration without reopening the sidebar', () => {
  const {w,adapter}=setup('<button id="open" aria-label="사이드바 열기"></button><button>새 프로젝트</button>', 'https://chatgpt.com/g/g-p-existing/project');
  let clicks=0;w.document.getElementById('open').addEventListener('click',()=>clicks++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'waiting');assert.equal(clicks,0);
  w.document.body.insertAdjacentHTML('beforeend','<h1>mobile-codex-chat</h1>');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'available');
});

test('prepares the composer after a link-based project match and delayed sidebar dismissal', () => {
  const {w,adapter}=setup('<main aria-hidden="true"><form><div id="prompt-textarea"></div><button aria-haspopup="menu">6Pro</button></form></main><div role="dialog"><a href="/g/g-p-existing/project">mobile-codex-chat</a><button aria-label="Close sidebar"></button></div>');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'available');
  assert.equal(adapter.prepareComposer().status,'closing_sidebar');
  w.document.querySelector('[role="dialog"]').hidden=true;
  assert.equal(adapter.prepareComposer().status,'waiting');
  w.document.querySelector('main').removeAttribute('aria-hidden');
  assert.equal(adapter.prepareComposer().currentModel,'GPT-6 Pro');
});

test('does not click ambiguous project rows or dismiss unrelated dialogs', () => {
  let {w,adapter}=setup('<div role="button" data-sidebar-item>mobile-codex-chat</div>'.repeat(2)+'<button>새 프로젝트</button>');
  assert.equal(adapter.inspectProject('mobile-codex-chat').reason,'project_ambiguous');
  ({w,adapter}=setup('<main aria-hidden="true"><form><div id="prompt-textarea"></div><button aria-haspopup="menu">6Pro</button></form></main><dialog open><button id="close">Close</button></dialog>'));
  let closed=0;w.document.getElementById('close').addEventListener('click',()=>closed++);
  assert.equal(adapter.prepareComposer().status,'waiting');assert.equal(closed,0);
});

test('can leave a different project without toggling the sidebar closed again', () => {
  const {w,adapter}=setup('<main aria-hidden="true"><h1>Other project</h1></main><div role="dialog"><button id="close" aria-label="Close sidebar"></button><div id="target" role="button" data-sidebar-item>mobile-codex-chat</div></div>', 'https://chatgpt.com/g/g-p-other/project');
  let closed=0,selected=0;w.document.getElementById('close').addEventListener('click',()=>closed++);w.document.getElementById('target').addEventListener('click',()=>selected++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'opening_project');assert.equal(selected,1);assert.equal(closed,0);
});

test('opens project home instead of the accordion that only expands the chat list', () => {
  const {w,adapter}=setup('<div role="dialog"><ul><li><div><div id="project-row" role="button" data-sidebar-item aria-expanded="false" aria-controls="project-chats"><span>mobile-codex-chat</span></div>'+
    '<div><button id="home" aria-label="프로젝트 홈 열기"></button><button id="options" aria-label="mobile-codex-chat 프로젝트 옵션 열기" aria-haspopup="menu"></button></div></div>'+
    '<div id="project-chats" hidden>프로젝트 채팅 없음</div></li></ul><button>새 프로젝트</button></div>');
  let expanded=0,opened=0,options=0;
  w.document.getElementById('project-row').addEventListener('click',()=>{expanded++;w.document.getElementById('project-chats').hidden=false;});
  w.document.getElementById('home').addEventListener('click',()=>opened++);
  w.document.getElementById('options').addEventListener('click',()=>options++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'opening_project');
  assert.equal(opened,1);assert.equal(expanded,0);assert.equal(options,0);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'waiting');assert.equal(opened,1);
  w.history.replaceState({},'', '/g/g-p-existing/project');
  w.document.body.insertAdjacentHTML('afterbegin','<main aria-hidden="true"><h1>mobile-codex-chat</h1><form><div id="prompt-textarea" contenteditable="true" tabindex="0"></div><button aria-haspopup="menu">6Pro</button></form></main>');
  const close=w.document.createElement('button');close.setAttribute('aria-label','사이드바 닫기');w.document.querySelector('[role="dialog"]').append(close);
  close.addEventListener('click',()=>{w.document.querySelector('[role="dialog"]').hidden=true;w.document.querySelector('main').removeAttribute('aria-hidden');});
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'closing_sidebar');
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'available');
  assert.equal(adapter.prepareComposer().currentModel,'GPT-6 Pro');
});

test('never treats expanding a project as navigation when home is absent or ambiguous', () => {
  const row='<div id="project-row" role="button" data-sidebar-item aria-expanded="false" aria-controls="chats">mobile-codex-chat</div>';
  let {w,adapter}=setup('<div>'+row+'</div><button>새 프로젝트</button>');
  let expanded=0;w.document.getElementById('project-row').addEventListener('click',()=>expanded++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').reason,'project_home_pending');assert.equal(expanded,0);
  ({adapter}=setup('<div>'+row+'<button aria-label="Open project home"></button><button aria-label="Open project home"></button></div>'));
  assert.equal(adapter.inspectProject('mobile-codex-chat').reason,'project_home_ambiguous');
});

for (const populated of [false, true]) test(`recognizes mobile project ${populated ? 'list' : 'empty'} with a hidden desktop heading`, () => {
  const {w,adapter}=setup('<div><div>mobile-codex-chat</div></div><div style="display:none"><h1>mobile-codex-chat</h1></div>'+
    (populated ? '<a href="/c/unrelated-chat">New chat</a>' : '<div>아직 채팅 없음</div>')+
    '<form><textarea placeholder="mobile-codex-chat에서 새 채팅" style="display:none"></textarea><div id="prompt-textarea" contenteditable="true" aria-label="mobile-codex-chat에서 새 채팅"></div><button aria-haspopup="menu">Extra High</button></form>',
    'https://chatgpt.com/g/g-p-6abb78d32fd88191a6c6493ae7abfe1b-mobile-codex-chat/project');
  w.document.querySelector('h1').getBoundingClientRect=()=>({width:0,height:0});
  let unrelatedClicks=0;w.document.querySelector('a')?.addEventListener('click',()=>unrelatedClicks++);
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'available');
  assert.equal(adapter.inspectProject('mobile-codex-chat').current,true);
  assert.equal(unrelatedClicks,0);
});

test('inserts into the visible ProseMirror editor, never its earlier hidden fallback textarea', () => {
  const {w,adapter}=setup('<form><textarea placeholder="메시지 입력" style="display:none"></textarea><div id="prompt-textarea" contenteditable="true" tabindex="0"></div><button aria-haspopup="menu">6Pro</button></form>');
  assert.equal(adapter.prepareComposer().currentModel,'GPT-6 Pro');
  assert.equal(adapter.insert('검증 메시지').status,'inserted');
  assert.equal(w.document.querySelector('textarea').value,'');
  assert.equal(w.document.getElementById('prompt-textarea').textContent,'검증 메시지');
});

test('opens the performance picker with pointer events and confirms actual Pro text, not effort', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button type="button" id="effort" aria-haspopup="menu" aria-expanded="false">Extra High</button></form>'+
    '<div role="menu" hidden><div data-testid="composer-intelligence-picker-content"><div role="menuitem" aria-label="모델 선택">Extra High</div>'+
    '<div id="performance" role="menuitem" tabindex="0" aria-keyshortcuts="ArrowLeft ArrowRight"><div data-model-reasoning-effort-slider><span role="slider" aria-valuenow="3" aria-valuemax="4"></span></div></div></div></div>');
  const button=w.document.getElementById('effort'),menu=w.document.querySelector('[role="menu"]'),slider=w.document.querySelector('[role="slider"]');
  let opens=0,steps=0;
  button.addEventListener('pointerdown',()=>{opens++;menu.hidden=false;button.setAttribute('aria-expanded','true');});
  w.document.getElementById('performance').addEventListener('keydown',e=>{if(e.key==='ArrowRight')steps++;});
  menu.addEventListener('keydown',e=>{if(e.key==='Escape'){menu.hidden=true;button.textContent='6Pro';button.setAttribute('aria-expanded','false');}});
  assert.equal(adapter.prepareComposer().status,'available');
  assert.equal(adapter.prepareComposer().currentModel,'');
  assert.notEqual(adapter.confirmPro().status,'available');
  assert.equal(adapter.openModelPicker().status,'opened');
  assert.equal(adapter.openModelPicker().status,'opened');assert.equal(opens,1);
  assert.equal(adapter.choosePro().status,'waiting');assert.equal(steps,1);
  assert.equal(adapter.choosePro().status,'waiting');assert.equal(steps,1);
  slider.setAttribute('aria-valuenow','4');
  assert.equal(adapter.choosePro().status,'selected');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro');
});

test('never adjusts an unrelated slider or treats an inert Pro label as selected', () => {
  const {adapter}=setup('<form><div id="prompt-textarea"></div><button aria-haspopup="menu">Extra High</button></form><div role="menu"><div role="slider" aria-valuenow="0" aria-valuemax="4"></div><div inert><button>GPT-6 Pro</button></div></div>');
  assert.equal(adapter.choosePro().reason,'gpt_6_pro_missing');
});

test('does not accept a hidden project heading alone or a sidebar/chat mention as the visible title', () => {
  const {w,adapter}=setup('<nav><div>mobile-codex-chat</div></nav><h1>mobile-codex-chat</h1><article data-message-author-role="user"><div>mobile-codex-chat</div></article><div id="prompt-textarea"></div>',
    'https://chatgpt.com/g/g-p-existing/project');
  w.document.querySelector('h1').getBoundingClientRect=()=>({width:0,height:0});
  assert.equal(adapter.inspectProject('mobile-codex-chat').status,'waiting');
});

test('accepts any Pro tier regardless of version, and nothing else', () => {
  const {adapter}=setup('<div id="prompt-textarea"></div>');
  for (const name of ['GPT-6 Pro','GPT-6.1 Pro','gpt 6.1 pro','6.1 Pro','6Pro','GPT-7 Pro','Pro','모델: GPT-6.1 Pro']) assert.equal(adapter.exactPro(name),true,name);
  for (const name of ['GPT-6.1','GPT-6.1 Thinking','Pro Legacy','GPT-6.1 Pro Legacy','Professional','Pro 체험판']) assert.equal(adapter.exactPro(name),false,name);
});

test('a renamed Pro model is reported with the labels the menu actually showed', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea" contenteditable="true"></div><button id="model" aria-haspopup="menu">GPT-6.1</button></form>'+
    '<div role="menu"><button role="menuitem">GPT-6.1 Pro</button><button role="menuitem">GPT-6.1 Thinking</button></div>');
  w.document.querySelector('[role="menuitem"]').addEventListener('click',()=>{w.document.getElementById('model').textContent='GPT-6.1 Pro';});
  assert.equal(adapter.choosePro().status,'selected');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro');
  const missing=setup('<div id="prompt-textarea"></div><button aria-haspopup="menu">GPT-6.1</button><div role="menu"><button role="menuitem">Super Pro</button><button role="menuitem">GPT-6.1 Thinking</button></div>').adapter.choosePro();
  assert.equal(missing.reason,'gpt_6_pro_missing');
  assert.deepEqual([...missing.seen],['Super Pro','GPT-6.1 Thinking']);
});

test('reaching the last slider position is not enough when the button does not then read Pro', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button type="button" id="effort" aria-haspopup="menu" aria-expanded="false">추론 수준</button></form>'+
    '<div role="menu" hidden><div data-testid="composer-intelligence-picker-content"><button type="button"><span>6</span> <span>Pro</span><svg></svg></button>'+
    '<div id="performance" role="menuitem" tabindex="0" aria-keyshortcuts="ArrowLeft ArrowRight"><div data-model-reasoning-effort-slider><span role="slider" aria-valuenow="4" aria-valuemax="4"></span></div></div></div></div>');
  const button=w.document.getElementById('effort'),menu=w.document.querySelector('[role="menu"]');
  button.addEventListener('pointerdown',()=>{menu.hidden=false;button.setAttribute('aria-expanded','true');});
  menu.addEventListener('keydown',e=>{if(e.key==='Escape'){menu.hidden=true;button.setAttribute('aria-expanded','false');}});
  assert.equal(adapter.openModelPicker().status,'opened');
  assert.equal(adapter.choosePro().status,'selected'); assert.equal(menu.hidden,true);
  assert.equal(adapter.confirmPro().reason,'model_confirmation_failed');
});

test('a Pro heading does not count while the slider is below its maximum', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button type="button" aria-haspopup="menu" aria-expanded="true">추론 수준</button></form>'+
    '<div role="menu"><div data-testid="composer-intelligence-picker-content"><button type="button">6 Pro</button>'+
    '<div id="performance" role="menuitem" tabindex="0" aria-keyshortcuts="ArrowLeft ArrowRight"><div data-model-reasoning-effort-slider><span role="slider" aria-valuenow="2" aria-valuemax="4"></span></div></div></div></div>');
  let steps=0; w.document.getElementById('performance').addEventListener('keydown',e=>{if(e.key==='ArrowRight')steps++;});
  assert.equal(adapter.choosePro().status,'waiting'); assert.equal(steps,1);
  assert.notEqual(adapter.confirmPro().status,'available');
});

test('a bare Pro badge outside the composer is not mistaken for the model button', () => {
  const {adapter}=setup('<nav><button>Pro</button></nav><form><div id="prompt-textarea"></div><button aria-haspopup="menu">추론 수준</button></form>');
  assert.equal(adapter.inspect().status,'available'); assert.equal(adapter.inspect().triggerCount,1);
});

test('steps from Instant to the last slider position and confirms from the Pro trigger label', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button type="button" id="effort" aria-haspopup="menu" aria-expanded="false">Instant</button></form>'+
    '<div role="menu" hidden><div data-testid="composer-intelligence-picker-content"><button type="button">6 Pro</button>'+
    '<div id="performance" role="menuitem" tabindex="0" aria-keyshortcuts="ArrowLeft ArrowRight"><div data-model-reasoning-effort-slider><span role="slider" aria-valuenow="0" aria-valuemax="3"></span></div></div></div></div>');
  const button=w.document.getElementById('effort'),menu=w.document.querySelector('[role="menu"]'),slider=w.document.querySelector('[role="slider"]');
  button.addEventListener('pointerdown',()=>{menu.hidden=false;button.textContent='추론 수준';button.setAttribute('aria-expanded','true');});
  w.document.getElementById('performance').addEventListener('keydown',e=>{if(e.key==='ArrowRight')slider.setAttribute('aria-valuenow',String(Number(slider.getAttribute('aria-valuenow'))+1));});
  menu.addEventListener('keydown',e=>{if(e.key==='Escape'){menu.hidden=true;button.textContent=slider.getAttribute('aria-valuenow')===slider.getAttribute('aria-valuemax')?'Pro':'Thinking';button.setAttribute('aria-expanded','false');}});
  const state=adapter.prepareComposer(); assert.equal(state.status,'available'); assert.equal(state.currentModel,'');
  assert.equal(adapter.openModelPicker().status,'opened');
  for (let i=0;i<3;i++) assert.equal(adapter.choosePro().status,'waiting');
  assert.equal(adapter.choosePro().status,'selected');
  assert.equal(button.textContent,'Pro');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro');
});

test('does not touch the picker when the composer button already reads 6 Pro', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button type="button" id="effort" aria-haspopup="menu" aria-expanded="false">6 Pro</button></form>');
  let presses=0; w.document.getElementById('effort').addEventListener('pointerdown',()=>presses++);
  assert.equal(adapter.prepareComposer().currentModel,'GPT-6 Pro');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro'); assert.equal(presses,0);
});

const pickerDom = (trigger, slider) => '<form><div id="prompt-textarea"></div>'+trigger+'</form>'+
  '<div role="menu"><div data-testid="composer-intelligence-picker-content"><button type="button">6 Pro</button>'+
  '<div id="performance" role="menuitem" tabindex="0" aria-keyshortcuts="ArrowLeft ArrowRight"><div data-model-reasoning-effort-slider>'+slider+'</div></div></div></div>';

test('a slider without value attributes is never treated as its last position', () => {
  const {adapter}=setup(pickerDom('<button aria-haspopup="menu" aria-expanded="true">Instant</button>','<span role="slider"></span>'));
  assert.notEqual(adapter.choosePro().status,'selected');
  const partial=setup(pickerDom('<button aria-haspopup="menu" aria-expanded="true">Instant</button>','<span role="slider" aria-valuenow="0"></span>')).adapter;
  assert.notEqual(partial.choosePro().status,'selected');
});

test('visible "6 Pro" confirms Pro even when the button has a generic accessible name', () => {
  const {adapter}=setup('<form><div id="prompt-textarea"></div><button aria-haspopup="menu" aria-label="모델 선택기">6 Pro</button></form>');
  assert.equal(adapter.confirmPro().confirmedModel,'GPT-6 Pro');
  const byName=setup('<form><div id="prompt-textarea"></div><button aria-haspopup="menu" aria-label="6 Pro"><svg></svg></button></form>').adapter;
  assert.equal(byName.confirmPro().confirmedModel,'GPT-6 Pro');
});

test('a failed confirmation reports what the button actually said', () => {
  const {adapter}=setup('<form><div id="prompt-textarea"></div><button aria-haspopup="menu" aria-label="모델 선택기">Thinking</button></form>');
  const failed=adapter.confirmPro();
  assert.equal(failed.reason,'model_confirmation_failed');
  assert.deepEqual({...failed.trigger},{text:'Thinking',ariaLabel:'모델 선택기'});
});

test('the send button is not pressed unless the composer still names Pro', () => {
  const {w,adapter}=setup('<form><div id="prompt-textarea"></div><button id="model" aria-haspopup="menu">6 Pro</button><button id="composer-submit-button">send</button></form>');
  let sends=0; w.document.getElementById('composer-submit-button').addEventListener('click',()=>sends++);
  w.document.getElementById('model').textContent='Instant';
  const blocked=adapter.clickSend(); assert.equal(blocked.status,'not_sent'); assert.equal(blocked.reason,'model_confirmation_failed'); assert.equal(sends,0);
  w.document.getElementById('model').textContent='6 Pro';
  assert.equal(adapter.clickSend().status,'clicked'); assert.equal(sends,1);
});

test('re-injecting the adapter keeps its state instead of registering a new copy', () => {
  const {w,adapter}=setup('<div id="prompt-textarea"></div>');
  w.eval(source);
  assert.equal(w.MCProWeb,adapter);
});

const modeDom = (chatAttrs, workAttrs, trigger) => '<header><div role="tablist"><button role="tab" id="chat-mode" '+chatAttrs+'>Chat</button><button role="tab" id="work-mode" '+workAttrs+'>Work</button></div></header>'+
  '<form><div id="prompt-textarea"></div><button id="effort" aria-haspopup="menu">'+trigger+'</button></form>';

test('Work mode is switched to Chat before Pro is chosen', () => {
  const {w,adapter}=setup(modeDom('aria-selected="false"','aria-selected="true"','Extra High'));
  const chat=w.document.getElementById('chat-mode'),work=w.document.getElementById('work-mode'),effort=w.document.getElementById('effort');
  let presses=0; chat.addEventListener('click',()=>{presses++;chat.setAttribute('aria-selected','true');work.setAttribute('aria-selected','false');effort.textContent='Instant';});
  const first=adapter.prepareComposer(); assert.equal(first.status,'waiting'); assert.equal(first.reason,'chat_mode_switching'); assert.equal(presses,1);
  const next=adapter.prepareComposer(); assert.equal(next.status,'available'); assert.equal(next.currentModel,''); assert.equal(presses,1);
});

test('the mode switch is pressed once while the page is still changing', () => {
  const {w,adapter}=setup(modeDom('aria-selected="false"','aria-selected="true"','Extra High'));
  let presses=0; w.document.getElementById('chat-mode').addEventListener('click',()=>presses++);
  assert.equal(adapter.prepareComposer().reason,'chat_mode_switching');
  assert.equal(adapter.prepareComposer().reason,'chat_mode_switching'); assert.equal(presses,1);
});

test('Chat mode and pages without the toggle are left alone', () => {
  let {w,adapter}=setup(modeDom('aria-selected="true"','aria-selected="false"','Instant'));
  let presses=0; w.document.getElementById('chat-mode').addEventListener('click',()=>presses++);
  assert.equal(adapter.prepareComposer().status,'available'); assert.equal(presses,0);
  ({w,adapter}=setup('<nav><button>Chat</button></nav><form><div id="prompt-textarea"></div><button aria-haspopup="menu">Extra High</button></form>'));
  assert.equal(adapter.prepareComposer().status,'available');
});

test('a work tier switches to Chat even when the toggle exposes no selected state', () => {
  const {w,adapter}=setup(modeDom('','','Extra High'));
  let presses=0; w.document.getElementById('chat-mode').addEventListener('click',()=>presses++);
  assert.equal(adapter.prepareComposer().reason,'chat_mode_switching'); assert.equal(presses,1);
});
