const {test,afterEach}=require('node:test');
const assert=require('node:assert/strict');
const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const script=fs.readFileSync('app/src/main/assets/chat-web-custom.js','utf8');
const open=[];afterEach(()=>open.splice(0).forEach(d=>{d.window.__mcChatCustom?.dispose();d.window.close();}));
const fixture='<aside id="stage-slideover-sidebar"><div class="row"><a href="/" aria-label="ChatGPT"><svg></svg></a><button aria-label="Search chats">Search</button><button aria-label="Close sidebar">Close</button></div><a href="/">New chat</a><p>History unchanged</p></aside><main><textarea>draft unchanged</textarea></main>';
const header='<header><div class="segmented"><button><span>Chat</span></button><button><span>Work</span></button></div><button>Share</button></header>';
function setup(html=fixture,url='https://chatgpt.com/') { const d=new JSDOM(html,{url,runScripts:'outside-only',pretendToBeVisual:true});open.push(d);d.window.eval(script);return d.window; }
const tick=()=>new Promise(resolve=>setTimeout(resolve,180));
test('adds only a mode switch beside sidebar logo and preserves web content',()=>{
 const w=setup(),d=w.document,group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(group.previousElementSibling.getAttribute('aria-label'),'ChatGPT');
 assert.equal(group.querySelector('a').href,'mobilecodex://mode/codex');
 assert.equal(d.querySelector('textarea').value,'draft unchanged');
 assert.match(d.querySelector('aside').textContent,/History unchanged/);
 assert.equal(d.querySelector('aside').getAttribute('style'),null);
 assert.equal(d.querySelector('main').getAttribute('style'),null);
 w.eval(script);assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
});
test('repairs switch after SPA sidebar remount and does not touch message links',async()=>{
 const w=setup('<main data-message-author-role="assistant"><a href="/"><svg></svg></a></main>');
 assert.equal(w.document.getElementById('mc-chat-mode-switch'),null);
 const box=w.document.createElement('div');box.innerHTML=fixture;w.document.body.append(box);await tick();
 assert.equal(w.document.querySelectorAll('#mc-chat-mode-switch').length,1);
 box.remove();const next=w.document.createElement('div');next.innerHTML=fixture;w.document.body.append(next);await tick();
 assert.equal(w.document.querySelectorAll('#mc-chat-mode-switch').length,1);
});
test('hides official Chat/Work pair while keeping app switch and other controls',()=>{
 const w=setup(header+fixture),d=w.document;
 assert.equal(d.querySelector('.segmented').hasAttribute('data-mc-hidden-official-mode-switch'),true);
 assert.equal(w.getComputedStyle(d.querySelector('.segmented')).display,'none');
 assert.ok(d.getElementById('mc-chat-mode-switch'));
 assert.equal(d.querySelector('header > button').textContent,'Share');
 assert.equal(d.querySelector('textarea').value,'draft unchanged');
});
test('hides a newly mounted official switch without hiding unrelated or hidden pairs',async()=>{
 const w=setup('<div hidden><button>Chat</button><button>Work</button></div>'+fixture),d=w.document;
 assert.equal(d.querySelector('[hidden]').hasAttribute('data-mc-hidden-official-mode-switch'),false);
 const wrapper=d.createElement('div');wrapper.innerHTML=header;d.body.prepend(wrapper);await tick();
 assert.equal(d.querySelector('.segmented').hasAttribute('data-mc-hidden-official-mode-switch'),true);
 assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
});
test('returns an active official Work tab to Chat before hiding that pair',async()=>{
 const html=header.replace('<button><span>Chat</span>','<button aria-selected="false"><span>Chat</span>').replace('<button><span>Work</span>','<button aria-selected="true"><span>Work</span>');
 const w=setup(html+fixture),d=w.document,group=d.querySelector('.segmented');
 assert.equal(group.hasAttribute('data-mc-hidden-official-mode-switch'),false);
 const [chat,work]=group.querySelectorAll('button');
 chat.addEventListener('click',()=>{chat.setAttribute('aria-selected','true');work.setAttribute('aria-selected','false');});
 w.__mcChatCustom.refresh();await tick();
 assert.equal(group.hasAttribute('data-mc-hidden-official-mode-switch'),true);
 assert.equal(chat.getAttribute('aria-selected'),'true');
});
test('does not inject into authentication or unrelated origins',()=>{
 for(const url of ['https://auth.openai.com/','https://evil.test/']) {
  const w=setup(fixture,url);assert.equal(w.document.getElementById('mc-chat-mode-switch'),null);
 }
});
