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
test('keeps the official Chat/Work pair visible and leaves its selection alone',()=>{
 const w=setup(header+fixture),d=w.document;
 assert.equal(d.querySelector('.segmented').hasAttribute('data-mc-hidden-official-mode-switch'),false);
 assert.notEqual(w.getComputedStyle(d.querySelector('.segmented')).display,'none');
 assert.ok(d.getElementById('mc-chat-mode-switch'));
 assert.equal(d.querySelector('header > button').textContent,'Share');
 assert.equal(d.querySelector('textarea').value,'draft unchanged');
});
test('uses the same compact switch spacing and label size as Codex',()=>{
 const w=setup(),group=w.document.getElementById('mc-chat-mode-switch');
 const groupStyle=w.getComputedStyle(group),buttonStyle=w.getComputedStyle(group.querySelector('button'));
 assert.equal(groupStyle.width,'112px');
 assert.equal(groupStyle.paddingTop,'1px');
 assert.equal(groupStyle.borderTopWidth,'0px');
 assert.equal(groupStyle.fontSize,'13px');
 assert.equal(buttonStyle.minHeight,'40px');
 assert.equal(buttonStyle.paddingTop,'0px');
});
test('does not change an active official Work tab',()=>{
 const html=header.replace('<button><span>Chat</span>','<button aria-selected="false"><span>Chat</span>').replace('<button><span>Work</span>','<button aria-selected="true"><span>Work</span>');
 const w=setup(html+fixture),d=w.document,group=d.querySelector('.segmented');
 const [chat,work]=group.querySelectorAll('button');
 assert.equal(group.hasAttribute('data-mc-hidden-official-mode-switch'),false);
 assert.equal(chat.getAttribute('aria-selected'),'false');
 assert.equal(work.getAttribute('aria-selected'),'true');
});
test('restores a Chat/Work control hidden by the previous injection',()=>{
 const old=header.replace('class="segmented"','class="segmented" data-mc-hidden-official-mode-switch');
 const w=setup(old+fixture);
 assert.equal(w.document.querySelector('.segmented').hasAttribute('data-mc-hidden-official-mode-switch'),false);
});
test('does not inject into authentication or unrelated origins',()=>{
 for(const url of ['https://auth.openai.com/','https://evil.test/']) {
  const w=setup(fixture,url);assert.equal(w.document.getElementById('mc-chat-mode-switch'),null);
 }
});
test('new text-title sidebar gets a switch above its New chat row, not in the icon rail',async()=>{
 const html='<div id="stage-sidebar"><nav><a id="rail-home" href="/"><svg></svg></a></nav><div><div><button id="chat-title">ChatGPT</button><button aria-label="검색">Search</button><button id="hide-sidebar" aria-label="사이드바 숨기기">Close</button></div><a id="new-chat" href="/">새 채팅</a></div></div><main><p>ChatGPT</p></main>';
 const dom=new JSDOM(html,{url:'https://chatgpt.com/c/test',runScripts:'outside-only',pretendToBeVisual:true});open.push(dom);
 const d=dom.window.document;
 for(const [id,top,left,width] of [['rail-home',70,8,40],['chat-title',10,100,110],['new-chat',65,100,240]]){
  const element=d.getElementById(id);element.getClientRects=()=>[{}];element.getBoundingClientRect=()=>({top,left,width,height:40,bottom:top+40,right:left+width});
 }
 d.getElementById('hide-sidebar').getClientRects=()=>[{}];
 dom.window.eval(script);
 let group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(group.nextElementSibling.id,'new-chat');
 assert.equal(group.classList.contains('mc-below-title'),true);
 assert.notEqual(group.previousElementSibling.id,'rail-home');
 let clicks=0;d.getElementById('hide-sidebar').addEventListener('click',()=>clicks++);
 assert.equal(dom.window.__mcChatCustom.closeSidebar(),true);assert.equal(clicks,1);
 group.remove();await tick();group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(group.nextElementSibling.id,'new-chat');
});

test('observed navigation wordmark mounts the switch without a clickable title or localized New chat label',async()=>{
 const html='<nav data-app-navigation-rail aria-label="App navigation"><a href="/"><svg></svg></a></nav>'+
  '<nav role="navigation" aria-label="Home"><div id="sidebar-heading"><div class="@container/navigation-header">'+
  '<span class="contents"><div><span><span class="sr-only">ChatGPT</span><svg role="img"><title>ChatGPT</title></svg></span></div></span>'+
  '<div><button id="hide" aria-label="Hide sidebar"></button></div></div><div id="new"><button>New chat</button></div></div></nav>'+
  '<main><p>ChatGPT</p><p>[[icon:안녕]]</p></main>';
 const w=setup(html),d=w.document,group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(group.parentElement.id,'sidebar-heading');
 assert.ok(group.previousElementSibling.classList.contains('@container/navigation-header'));
 assert.equal(group.nextElementSibling.id,'new');
 assert.equal(d.querySelector('[data-app-navigation-rail] #mc-chat-mode-switch'),null);
 assert.equal(d.querySelector('main').textContent,'ChatGPT[[icon:안녕]]');
 const hide=d.getElementById('hide');hide.getClientRects=()=>[{}];let clicks=0;hide.onclick=()=>clicks++;
 assert.equal(w.__mcChatCustom.closeSidebar(),true);assert.equal(clicks,1);
 const root=d.querySelector('nav[role="navigation"]');root.replaceWith(root.cloneNode(true));
 d.getElementById('mc-chat-mode-switch').remove();await tick();
 assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
 assert.ok(d.querySelector('nav[role="navigation"] #mc-chat-mode-switch'));
});

test('uses the visible navigation when mobile and hidden desktop copies coexist, including Settings',async()=>{
 const hidden='<nav aria-label="Home"><div class="@container/navigation-header">ChatGPT</div></nav>';
 const w=setup(hidden),d=w.document;
 const visible=d.createElement('nav');visible.setAttribute('aria-label','Settings');
 visible.innerHTML='<div class="@container/navigation-header">Settings</div>';
 visible.getClientRects=()=>[{}];d.body.append(visible);await tick();
 assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
 assert.ok(visible.querySelector('#mc-chat-mode-switch'));
 visible.remove();await tick();
 assert.ok(d.querySelector('nav[aria-label="Home"] #mc-chat-mode-switch'));
});
