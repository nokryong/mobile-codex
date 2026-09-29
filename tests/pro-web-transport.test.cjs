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
  assert.equal(adapter.exactPro('Pro'),false);assert.equal(adapter.exactPro('GPT-6 Pro'),true);
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
