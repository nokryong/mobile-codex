const {test,afterEach}=require('node:test');
const assert=require('node:assert/strict');
const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const script=fs.readFileSync('app/src/main/assets/chat-web-custom.js','utf8');
const open=[];afterEach(()=>open.splice(0).forEach(d=>{d.window.__mcChatCustom?.dispose();d.window.close();}));
const fixture='<aside id="stage-slideover-sidebar"><div class="row"><a href="/" aria-label="ChatGPT"><svg></svg></a><button aria-label="Search chats">Search</button><button aria-label="Close sidebar">Close</button></div><a href="/">New chat</a><p>History unchanged</p></aside><main><textarea>draft unchanged</textarea></main>';
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
test('does not inject into authentication or unrelated origins',()=>{
 for(const url of ['https://auth.openai.com/','https://evil.test/']) {
  const w=setup(fixture,url);assert.equal(w.document.getElementById('mc-chat-mode-switch'),null);
 }
});
