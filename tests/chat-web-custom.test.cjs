const {test,afterEach}=require('node:test');
const assert=require('node:assert/strict');
const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const script=fs.readFileSync('app/src/main/assets/chat-web-custom.js','utf8');
const open=[];afterEach(()=>open.splice(0).forEach(d=>{d.window.__mcChatCustom?.dispose();d.window.close();}));
const fixture='<aside id="stage-slideover-sidebar"><div class="row"><a href="/" aria-label="ChatGPT"><svg></svg></a><button aria-label="Search chats">Search</button><button aria-label="Close sidebar">Close</button></div><a href="/">New chat</a><p>History unchanged</p></aside><main><textarea>draft unchanged</textarea></main>';
const header='<header><div class="segmented"><button><span>Chat</span></button><button><span>Work</span></button></div><button>Share</button></header>';
function installGeometry(w) {
 const hidden=el=>{for(let node=el;node&&node.nodeType===1;node=node.parentElement){const style=w.getComputedStyle(node);if(node.hidden||node.getAttribute('aria-hidden')==='true'||style.display==='none'||style.visibility==='hidden')return true;}return false;};
 const rect=el=>{
  if(hidden(el))return {left:0,top:0,width:0,height:0,right:0,bottom:0,x:0,y:0,toJSON(){return this;}};
  const side=el.matches?.('aside,nav[aria-label],[role="navigation"],[id*="sidebar"]')||el.closest?.('aside,nav[aria-label],[role="navigation"],[id*="sidebar"]');
  const head=el.matches?.('header,[role="banner"]')||el.closest?.('header,[role="banner"]');
  const main=el.matches?.('main')||el.closest?.('main');const name=((el.getAttribute?.('aria-label')||'')+' '+(el.textContent||'')).trim();
  let left=side?0:head?260:main?420:0,top=side?0:head?0:main?180:0,width=side?260:head?540:main?340:800,height=side?800:head?56:main?120:900;
  if(side&&!el.matches?.('aside,nav[aria-label],[role="navigation"],[id*="sidebar"]')) {left=12;width=220;height=44;top=/new chat|새 채팅/i.test(name)?64:8;}
  if(head&&!el.matches?.('header,[role="banner"]')) {left=268;width=44;height=44;top=6;}
  const value={left,top,width,height,right:left+width,bottom:top+height,x:left,y:top,toJSON(){return this;}};return value;
 };
 w.HTMLElement.prototype.getBoundingClientRect=function(){return rect(this);};
 w.HTMLElement.prototype.getClientRects=function(){const value=rect(this);return value.width&&value.height?[value]:[];};
}
function setup(html=fixture,url='https://chatgpt.com/') { const d=new JSDOM(html,{url,runScripts:'outside-only',pretendToBeVisual:true});open.push(d);installGeometry(d.window);d.window.eval(script);return d.window; }
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
 assert.equal(groupStyle.width,'max-content');assert.equal(groupStyle.minWidth,'112px');
 assert.equal(groupStyle.paddingTop,'1px');
 assert.equal(groupStyle.borderTopWidth,'0px');
 assert.equal(groupStyle.fontSize,'13px');
 assert.equal(buttonStyle.minHeight,'44px');
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
 const dom=new JSDOM(html,{url:'https://chatgpt.com/c/test',runScripts:'outside-only',pretendToBeVisual:true});open.push(dom);installGeometry(dom.window);
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

test('uses a wide header fallback for a collapsed rail and returns to a static ChatGPT wordmark after rotation',()=>{
 const html='<aside id="rail"><a href="/"><svg></svg></a><div id="offscreen-title">ChatGPT</div><button id="offscreen-new">New chat</button></aside><header id="wide-header"><button id="sidebar-toggle" aria-label="Open sidebar">Menu</button><span>Conversation</span></header>';
 const w=setup(html),d=w.document;
 const box=(id,left,top,width,height)=>{const el=d.getElementById(id),rect={left,top,width,height,right:left+width,bottom:top+height};el.getClientRects=()=>[rect];el.getBoundingClientRect=()=>rect;};
 box('rail',0,0,56,800);box('offscreen-title',-220,8,100,32);box('offscreen-new',-220,56,160,44);box('wide-header',56,0,744,56);box('sidebar-toggle',64,6,44,44);
 w.__mcChatCustom.refresh();let group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(group.parentElement.id,'wide-header');assert.equal(d.querySelector('#rail #mc-chat-mode-switch'),null);
 assert.equal(group.querySelector('a').href,'mobilecodex://mode/codex');assert.equal(w.getComputedStyle(group.querySelector('button')).minHeight,'44px');
 d.getElementById('rail').innerHTML='<div id="static-title">ChatGPT</div><button id="static-new">New chat</button>';
 box('rail',0,0,260,800);box('static-title',12,10,120,36);box('static-new',12,62,220,44);
 w.__mcChatCustom.refresh();group=d.getElementById('mc-chat-mode-switch');assert.equal(group.nextElementSibling.id,'static-new');assert.equal(group.parentElement.id,'rail');
 // A narrow, offscreen rail must never reclaim the 112px switch after rotation.
 d.getElementById('rail').innerHTML='<a href="/"><svg></svg></a>';box('rail',-56,0,56,800);w.__mcChatCustom.refresh();group=d.getElementById('mc-chat-mode-switch');assert.equal(group.parentElement.id,'wide-header');
});

test('rejects hidden ancestors and class-only collapse moves a static title out of a narrow rail',async()=>{
 const w=setup('<aside id="stage-sidebar"><div hidden><div>ChatGPT</div><button>New chat</button></div><div id="title">ChatGPT</div><button id="new">New chat</button></aside><header id="fallback"><span id="clip"><button aria-label="Open sidebar">Menu</button></span></header>'),d=w.document;
 const rect=(left,top,width,height)=>({left,top,width,height,right:left+width,bottom:top+height});let narrow=false;
 const side=d.getElementById('stage-sidebar'),title=d.getElementById('title'),next=d.getElementById('new'),head=d.getElementById('fallback'),clip=d.getElementById('clip'),toggle=clip.querySelector('button');
 side.getBoundingClientRect=()=>rect(0,0,narrow?56:260,800);side.getClientRects=()=>[side.getBoundingClientRect()];
 for(const [element,value] of [[title,rect(12,8,120,36)],[next,rect(12,60,220,44)],[head,rect(56,0,700,56)],[clip,rect(64,6,44,44)],[toggle,rect(64,6,44,44)]]) {element.getBoundingClientRect=()=>value;element.getClientRects=()=>[value];}
 w.__mcChatCustom.refresh();let group=d.getElementById('mc-chat-mode-switch');assert.equal(group.nextElementSibling.id,'new');assert.equal(d.querySelector('[hidden] #mc-chat-mode-switch'),null);
 narrow=true;side.classList.add('collapsed');await tick();group=d.getElementById('mc-chat-mode-switch');assert.equal(group.parentElement.id,'fallback');assert.equal(group.previousElementSibling.id,'clip');
});

test('uses visible children as the bounds of a display-contents sidebar',()=>{
 const w=setup('<nav id="contents-nav" aria-label="Home" style="display:contents"><div id="contents-header" class="@container/navigation-header">ChatGPT</div><div data-app-action-sidebar-scroll>History</div></nav>'),d=w.document,rect=(left,top,width,height)=>({left,top,width,height,right:left+width,bottom:top+height});
 const nav=d.getElementById('contents-nav'),head=d.getElementById('contents-header');nav.getBoundingClientRect=()=>rect(0,0,0,0);nav.getClientRects=()=>[];head.getBoundingClientRect=()=>rect(0,0,260,44);head.getClientRects=()=>[head.getBoundingClientRect()];
 w.__mcChatCustom.refresh();assert.equal(d.getElementById('mc-chat-mode-switch').previousElementSibling.id,'contents-header');
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
 const hidden='<nav aria-label="Home" hidden><div class="@container/navigation-header">ChatGPT</div></nav>';
 const w=setup(hidden),d=w.document;
 const visible=d.createElement('nav');visible.setAttribute('aria-label','Settings');
 visible.innerHTML='<div class="@container/navigation-header">Settings</div>';
 visible.getClientRects=()=>[{}];d.body.append(visible);await tick();
 assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
 assert.ok(visible.querySelector('#mc-chat-mode-switch'));
 visible.remove();d.querySelector('nav[aria-label="Home"]').hidden=false;await tick();
 assert.ok(d.querySelector('nav[aria-label="Home"] #mc-chat-mode-switch'));
});

// Observed 2026-09-28: the title/close toolbar moved outside the navigation.
// Deliberately omit all of the previous header class names and localized labels.
function separatedSidebar(id='panel') {
 return `<section id="${id}"><header><svg role="img"><title>ChatGPT</title></svg><button aria-label="Hide sidebar" aria-controls="${id}" aria-expanded="true"></button></header>`+
  '<div class="sidebar-navigation"><div class="contents"><nav role="navigation" aria-label="채팅 기록">'+
  '<div class="header-new-build"><button>새 채팅</button></div><div data-app-action-sidebar-scroll><p>History unchanged</p></div>'+
  '</nav></div></div></section>';
}
test('keeps the switch above New chat when the official title moves outside navigation',()=>{
 const w=setup(separatedSidebar()+header),d=w.document,nav=d.querySelector('nav');
 const group=d.getElementById('mc-chat-mode-switch');
 assert.ok(group);assert.equal(nav.firstElementChild,group);
 assert.equal(group.nextElementSibling.querySelector('button').textContent,'새 채팅');
 assert.equal(nav.querySelector('[data-app-action-sidebar-scroll]').textContent,'History unchanged');
 assert.ok(group.classList.contains('mc-at-sidebar-start'));
 assert.equal(d.querySelector('.segmented').textContent,'ChatWork');
 const close=d.querySelector('#panel > header button');close.getClientRects=()=>[{}];let clicked=0;close.onclick=()=>clicked++;
 assert.equal(w.__mcChatCustom.closeSidebar(),true);assert.equal(clicked,1);
});
test('moves the switch to the visible separate-title sidebar on resize and restores it after remount',async()=>{
 const w=setup(separatedSidebar('desktop')+separatedSidebar('mobile')),d=w.document;
 const desktop=d.querySelector('#desktop nav'),mobile=d.querySelector('#mobile nav');
 desktop.getClientRects=()=>[{}];mobile.getClientRects=()=>[];w.__mcChatCustom.refresh();
 assert.ok(desktop.querySelector('#mc-chat-mode-switch'));
 desktop.getClientRects=()=>[];mobile.getClientRects=()=>[{}];w.dispatchEvent(new w.Event('resize'));await tick();
 assert.ok(mobile.querySelector('#mc-chat-mode-switch'));assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
 mobile.innerHTML='<div><button>New chat</button></div><div data-app-action-sidebar-scroll>History unchanged</div>';await tick();
 assert.equal(mobile.firstElementChild.id,'mc-chat-mode-switch');
 assert.equal(d.querySelectorAll('#mc-chat-mode-switch').length,1);
});
test('does not use the icon rail, an unrelated navigation, or an ambiguous sidebar as a fallback',()=>{
 const w=setup('<nav data-app-navigation-rail aria-label="Sidebar"><div data-app-action-sidebar-scroll></div></nav>'+
  '<nav aria-label="Unrelated"><button>New chat</button></nav>'+
  '<nav aria-label="Ambiguous"><div data-app-action-sidebar-scroll></div><div data-app-action-sidebar-scroll></div></nav>'+
  '<main><nav aria-label="Message"><div data-app-action-sidebar-scroll></div></nav></main>');
 assert.equal(w.document.getElementById('mc-chat-mode-switch'),null);
});

test('landscape panel toolbar works with a duplicated SVG wordmark and an unlabelled navigation',()=>{
 const html='<div id="stage-slideover-sidebar"><div id="toolbar"><span class="sidebar-title"><svg><title>ChatGPT</title></svg><span aria-hidden="true">ChatGPT</span></span><div><button aria-label="채팅 검색"></button><button id="close" aria-label="사이드바 닫기"></button></div></div><nav><a href="/" aria-label="새 채팅"><span>새 채팅</span><kbd>Ctrl Shift O</kbd></a></nav></div>';
 const dom=new JSDOM(html,{url:'https://chatgpt.com/',runScripts:'outside-only',pretendToBeVisual:true});open.push(dom);installGeometry(dom.window);
 const d=dom.window.document,box=(el,left,top,width,height)=>{const rect={left,top,width,height,right:left+width,bottom:top+height};el.getBoundingClientRect=()=>rect;el.getClientRects=()=>[rect];};
 box(d.getElementById('stage-slideover-sidebar'),0,0,260,380);
 box(d.getElementById('toolbar'),0,0,260,50);
 box(d.querySelector('.sidebar-title'),16,14,76,22);
 box(d.querySelector('#toolbar > div'),174,8,80,40);
 box(d.getElementById('close'),214,8,40,40);
 dom.window.eval(script);
 const group=d.getElementById('mc-chat-mode-switch');assert.ok(group);
 assert.equal(group.previousElementSibling.id,'toolbar');assert.equal(group.parentElement.id,'stage-slideover-sidebar');
 assert.equal(group.nextElementSibling.tagName,'NAV');assert.equal(d.querySelector('kbd').textContent,'Ctrl Shift O');
 let clicks=0;d.getElementById('close').onclick=()=>clicks++;assert.equal(dom.window.__mcChatCustom.closeSidebar(),true);assert.equal(clicks,1);
});

test('a narrow sidebar-title class never replaces its explicit wide sidebar boundary',()=>{
 const html='<div id="stage-slideover-sidebar"><div class="sidebar-title" id="wordmark">ChatGPT</div><nav class="sidebar-actions"><a id="new-chat" href="/">새 채팅</a></nav></div>';
 const w=setup(html),d=w.document,title=d.getElementById('wordmark');
 title.getBoundingClientRect=()=>({left:16,top:14,width:76,height:22,right:92,bottom:36});title.getClientRects=()=>[title.getBoundingClientRect()];
 w.__mcChatCustom.refresh();assert.equal(d.getElementById('mc-chat-mode-switch').nextElementSibling.id,'new-chat');
});

test('the observed tiny sidebar has a 44px Codex return action instead of a clipped 112px pair',()=>{
 const w=setup('<div id="stage-sidebar-tiny-bar"><div id="rail-top"><button aria-label="사이드바 열기"><svg></svg></button></div><nav><button>New chat</button></nav></div>'),d=w.document,rail=d.getElementById('stage-sidebar-tiny-bar');
 rail.getBoundingClientRect=()=>({left:0,top:0,width:56,height:380,right:56,bottom:380});rail.getClientRects=()=>[rail.getBoundingClientRect()];
 w.__mcChatCustom.refresh();const group=d.getElementById('mc-chat-mode-switch');assert.ok(group);
 assert.equal(group.parentElement,rail);assert.equal(group.previousElementSibling.id,'rail-top');
 assert.equal(w.getComputedStyle(group).minWidth,'44px');assert.equal(w.getComputedStyle(group.querySelector('button')).display,'none');
 assert.equal(group.querySelector('a').href,'mobilecodex://mode/codex');assert.equal(w.getComputedStyle(group.querySelector('a')).minHeight,'44px');
 rail.hidden=true;w.__mcChatCustom.refresh();assert.equal(d.getElementById('mc-chat-mode-switch'),null);
});

test('the original full panel replacement mounts the compact return action',()=>{
 const w=setup('<div id="stage-slideover-sidebar"><div id="toolbar"><button aria-label="사이드바 닫기">Close</button></div><nav>History</nav></div>'),d=w.document;
 assert.equal(d.getElementById('mc-chat-mode-switch').parentElement.id,'stage-slideover-sidebar');
 d.getElementById('stage-slideover-sidebar').outerHTML='<div id="stage-sidebar-tiny-bar"><div id="rail-top"><button aria-label="사이드바 열기">Menu</button></div><nav>New</nav></div>';
 const rail=d.getElementById('stage-sidebar-tiny-bar'),rect={left:0,top:0,width:56,height:380,right:56,bottom:380};rail.getBoundingClientRect=()=>rect;rail.getClientRects=()=>[rect];
 w.__mcChatCustom.refresh();const group=d.getElementById('mc-chat-mode-switch');assert.ok(group);
 assert.equal(group.parentElement,rail);assert.equal(group.previousElementSibling.id,'rail-top');
 assert.equal(w.getComputedStyle(group).minWidth,'44px');assert.equal(w.getComputedStyle(group.querySelector('button')).display,'none');
});

test('a visible tiny rail wins during the panel-to-rail transition',()=>{
 const html='<div id="stage-slideover-sidebar"><div id="old-toolbar"><button aria-label="사이드바 닫기">Close</button></div><nav>History</nav></div><div id="stage-sidebar-tiny-bar"><div id="rail-top"><button aria-label="사이드바 열기">Menu</button></div><nav>New</nav></div><header id="wide-header"><button aria-label="Open sidebar">Menu</button></header>';
 const w=setup(html),d=w.document;
 const rect=(left,top,width,height)=>({left,top,width,height,right:left+width,bottom:top+height});
 const panel=d.getElementById('stage-slideover-sidebar'),toolbar=d.getElementById('old-toolbar'),rail=d.getElementById('stage-sidebar-tiny-bar'),railTop=d.getElementById('rail-top'),toggle=railTop.querySelector('button'),header=d.getElementById('wide-header');
 for(const [element,value] of [[panel,rect(0,0,260,380)],[toolbar,rect(0,0,260,50)],[rail,rect(0,0,56,380)],[railTop,rect(0,0,56,50)],[toggle,rect(6,3,44,44)],[header,rect(56,0,744,56)]]) {element.getBoundingClientRect=()=>value;element.getClientRects=()=>[value];}
 w.__mcChatCustom.refresh();const group=d.getElementById('mc-chat-mode-switch');assert.ok(group);
 assert.equal(group.parentElement,rail);assert.equal(group.previousElementSibling,railTop);
 assert.ok(group.classList.contains('mc-compact-rail'));assert.equal(group.querySelector('button').style.display,'');
 assert.equal(w.getComputedStyle(group.querySelector('button')).display,'none');
 panel.remove();w.__mcChatCustom.refresh();assert.equal(d.getElementById('mc-chat-mode-switch').parentElement,rail);
});
