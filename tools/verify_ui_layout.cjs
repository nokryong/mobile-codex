#!/usr/bin/env node
/* Browser checks for the packaged UI, using a local mock of the native bridge. No account or network. */
const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const web = path.join(root, 'app/src/main/assets/web');
const output = process.env.UI_LAYOUT_OUTPUT ? path.resolve(root, process.env.UI_LAYOUT_OUTPUT) : path.join(root, 'artifacts/ui-preview');
fs.mkdirSync(output, {recursive:true});
const snapshot = {
 ready:true,busy:false,permissions:'workspace-write',status:'Connected',threadId:'demo',cwd:'/workspace/field-notes',directWorkspace:true,
 linux:{supported:true,installed:false,enabled:false,busy:false,state:'not_installed',downloadBytes:151744988,requiredFreeBytes:1620180444,availableBytes:4000000000,label:'Arch Linux ARM (aarch64)'},
 workspace:{selected:true,key:'demo-project',name:'Very long project display name that keeps its menu visible'},projects:[{key:'demo-project',name:'Very long project display name that keeps its menu visible',available:true}],
 account:{type:'chatgpt',email:'demo@example.test'},
 sessions:[{id:'demo',workspaceKey:'demo-project',title:'A very long conversation title that must keep its action button visible'}],
 models:[{id:'gpt-example',model:'gpt-example',displayName:'Default model',isDefault:true,serviceTiers:[{id:'priority',name:'Fast'}],supportedReasoningEfforts:[{reasoningEffort:'medium',description:'Medium'}]}],
 messages:[{id:'u',role:'user',text:'Help me organize this project.',createdAt:1790670840000},{id:'a',role:'assistant',text:'I reviewed the project files. Here is a clear place to start.\n\n- Keep the source files in `src/`.\n- Put setup instructions in `README.md`.\n- Review the changes before running the app.\n\n```js\nconst greeting = "Hello, mobile";\nconsole.log(greeting);\n```\n\nWhat would you like to work on first?'}]
};
async function open(browser, width, height, theme='light', language='en', extra={}, initialSnapshot=snapshot) {
 const context = await browser.newContext({viewport:{width,height},deviceScaleFactor:1,colorScheme:theme,...extra});
 const page = await context.newPage(), errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/*', route=>{
  const url=new URL(route.request().url());
  if(url.origin!=='https://appassets.androidplatform.net') return route.abort();
  const file=path.resolve(web,'.'+url.pathname);
  if(!file.startsWith(web+path.sep)||!fs.existsSync(file)) return route.fulfill({status:404,body:''});
  return route.fulfill({path:file});
 });
 await page.addInitScript(({snapshot,theme,language})=>{
  localStorage.setItem('chat-icons','off');localStorage.setItem('theme',theme);
  window.fixtureSnapshot=snapshot;
  window.fixtureCalls=[];
  window.Native={locale:()=>JSON.stringify({choice:language,systemLanguage:language}),postMessage(raw){
   const m=JSON.parse(raw);let result={};
   window.fixtureCalls.push(m);
   if(m.action==='state') result=window.fixtureSnapshot;
   if(m.action==='updates.state') result={versionName:'0.1.13-alpha',versionCode:14,repository:'nokryong/mobile-codex',prereleases:true};
   if(m.action==='instructions.read') result={content:'Read the relevant files before editing.',activePath:'/private/AGENTS.md'};
   if(m.action==='files.list') result={entries:[]};
   if(m.action==='linux.status') result=snapshot.linux;
   if(m.action==='chat.history') {
    const all=snapshot.historyFixture||[],cursor=all.findIndex(item=>item.id===m.args.beforeId),start=Math.max(0,cursor-40);
    result={threadId:snapshot.threadId,messages:all.slice(start,cursor),messageHistory:{beforeId:start?all[start].id:'',hasMore:start>0,total:all.length}};
   }
   setTimeout(()=>window.mobileCodexEvent('response',{id:m.id,result}),0);
  }};
 }, {snapshot:initialSnapshot,theme,language});
 await page.goto('https://appassets.androidplatform.net/index.html');
 await page.waitForFunction(()=>document.querySelector('#header-project')?.textContent==='Very long project display name that keeps its menu visible');
 await page.locator('#prompt').fill(language==='ko'?'다음으로 어떤 작업을 하면 될까요?':'What should we work on next?');
 await page.locator('#prompt').blur();
 return {context,page,errors};
}
async function checkComposer(page) {
 await page.locator('#prompt').focus();
 await page.waitForFunction(()=>document.getElementById('composer')?.classList.contains('composer-expanded'));
 const result=await page.evaluate(()=>{
  const ids=['add-attachment','composer-folder','fast-mode','approval-mode','composer-options','voice-input','send'];
  const bounds=id=>{const r=document.getElementById(id).getBoundingClientRect();return {id,x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};
  return {width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,controls:ids.map(bounds),composer:bounds('composer'),header:bounds('header-project'),chat:bounds('chat-scroll')};
 });
 assert.ok(result.scrollWidth<=result.width,`Horizontal overflow at ${result.width}px`);
 assert.ok(result.composer.bottom<=result.height+1,'Composer outside resized viewport');
 assert.ok(result.composer.y>result.header.bottom,'Composer overlaps header');
 assert.ok(result.chat.height>0,'Chat lost its scroll area');
 for(const button of result.controls){
  assert.ok(button.width>=43.5&&button.height>=43.5,`${button.id} touch target ${button.width}×${button.height}`);
  assert.ok(button.x>=0&&button.right<=result.width+1,`${button.id} outside viewport`);
 }
 for(let i=1;i<result.controls.length;i++) {
  const previous=result.controls[i-1], current=result.controls[i];
  if(previous.bottom>current.y+1 && current.bottom>previous.y+1) assert.ok(previous.right<=current.x+1,'Composer buttons overlap');
 }
 const fast=result.controls.find(control=>control.id==='fast-mode'),options=result.controls.find(control=>control.id==='composer-options');
 assert.equal(fast.y,options.y,'Fast belongs to the lowest composer row');
 assert.ok(fast.x<options.x,'Fast is leftmost in the lowest row');
 const dateElement=page.locator('.message-time').first();
 const date=await dateElement.count()?await dateElement.boundingBox():null;
 if(date) {
  const bubble=await page.locator('.message.user.has-date').first().boundingBox();
  assert.ok(date.x>=0&&date.x+date.width<=bubble.x+1,'User date fits beside its bubble');
 }
 return result;
}
async function checkFirstMessageBelowHeader(page,label) {
 const value=await page.evaluate(async()=>{
  const chat=document.querySelector('#chat-scroll');chat.scrollTop=0;
  await new Promise(requestAnimationFrame);
  chat.scrollTop=0;
  const header=document.querySelector('.topbar').getBoundingClientRect(), first=document.querySelector('#messages article').getBoundingClientRect(), scroll=document.querySelector('#chat-scroll').getBoundingClientRect();
  return {header:header.toJSON(),first:first.toJSON(),scroll:scroll.toJSON()};
 });
 assert.ok(value.first.top>=value.header.bottom+8,`First message is hidden below the header: ${label}`);
 assert.ok(value.first.top>=value.scroll.top+8,`First message is outside the chat scroller: ${label}`);
 return value;
}
async function checkLongMessageClearance(page,width,height) {
 await page.evaluate(()=>{
  const messages=Array.from({length:100},(_,index)=>({id:'long-header-'+index,role:index%2?'assistant':'user',text:'Long conversation message '+index,...(index%2?{}:{createdAt:1790670840000+index*60000})}));
  window.fixtureSnapshot={...window.fixtureSnapshot,messages,messageHistory:{beforeId:'',hasMore:false,total:messages.length}};
  window.mobileCodexEvent('state',window.fixtureSnapshot);
 });
 await page.waitForFunction(()=>document.querySelectorAll('#messages article').length===100);
 return checkFirstMessageBelowHeader(page,`long conversation ${width}×${height}`);
}
async function checkLazyHistory(browser,width=393,height=852,theme='light',language='ko',keyboard=false) {
 const all=Array.from({length:120},(_,i)=>({id:'history-'+i,role:i%2?'assistant':'user',text:'History message '+i+'\nSecond line of message.',...(i%2?{}:{createdAt:1790670840000+i*60000})}));
 const initial={...snapshot,messages:all.slice(-40),messageHistory:{beforeId:'history-80',hasMore:true,total:120},historyFixture:all};
 const {context,page,errors}=await open(browser,width,height,theme,language,{},initial);
 try {
  if(keyboard) await page.evaluate(()=>window.mobileCodexEvent('viewport',{keyboardVisible:true}));
  assert.equal(await page.locator('#messages article').count(),40);
  assert.equal(await page.evaluate(()=>fixtureCalls.filter(call=>call.action==='chat.history').length),0);
  // Deterministically exercise a queued layout callback after newer upward navigation.
  // Both real viewport changes and focus must recheck follow mode at callback time.
  const deferred=await page.evaluate(keyboard=>{
   const area=document.getElementById('chat-scroll'), original=window.requestAnimationFrame, results=[];
   try {
    for(const trigger of ['viewport','focus']) {
     area.scrollTop=area.scrollHeight;area.dispatchEvent(new Event('scroll'));
     const queued=[];window.requestAnimationFrame=callback=>queued.push(callback);
     if(trigger==='viewport')window.mobileCodexEvent('viewport',{keyboardVisible:keyboard});
     else document.getElementById('prompt').dispatchEvent(new Event('focus'));
     area.scrollTop=300;area.dispatchEvent(new Event('scroll'));
     const before=area.scrollTop;
     for(const callback of queued)callback(performance.now());
     results.push({trigger,before,after:area.scrollTop,callbacks:queued.length});
    }
   } finally {window.requestAnimationFrame=original;}
   area.scrollTop=area.scrollHeight;area.dispatchEvent(new Event('scroll'));
   return results;
  },keyboard);
  for(const result of deferred) {
   assert.ok(result.callbacks>0,result.trigger+' queued a layout callback');
   assert.equal(result.after,result.before,`${result.trigger} callback must preserve upward navigation at ${width}×${height}`);
  }
  await page.evaluate(()=>{
   const area=document.getElementById('chat-scroll');area.style.scrollBehavior='auto';area.scrollTop=100;
   window.fixtureAnchor={id:'history-81',top:document.querySelector('[data-id="history-81"]').getBoundingClientRect().top};
  });
  await page.waitForFunction(()=>document.querySelectorAll('#messages article').length===80);
  const delta=await page.evaluate(()=>document.querySelector('[data-id="'+fixtureAnchor.id+'"]').getBoundingClientRect().top-fixtureAnchor.top);
  assert.ok(Math.abs(delta)<2,`Prepending older messages preserves the exact visible position at ${width}×${height}${keyboard?' with keyboard':''}: ${delta}`);
  assert.equal(await page.locator('#messages article').first().getAttribute('data-id'),'history-40');
  await page.evaluate(()=>{document.getElementById('chat-scroll').scrollTop=0;});
  await page.waitForFunction(()=>document.querySelectorAll('#messages article').length===120);
  await checkFirstMessageBelowHeader(page,`lazy-prepended conversation ${width}×${height}${keyboard?' keyboard':''}`);
  assert.equal(await page.locator('#messages article').first().getAttribute('data-id'),'history-0');
  await page.evaluate(()=>{document.getElementById('chat-scroll').scrollTop=0;});await page.waitForTimeout(100);
  assert.equal(await page.evaluate(()=>fixtureCalls.filter(call=>call.action==='chat.history').length),2);
  assert.deepEqual(errors,[],'Lazy history browser errors');
  await page.screenshot({path:path.join(output,`lazy-history-${width}-${height}-${theme}-${language}${keyboard?'-keyboard':''}.png`)});
 } catch (error) {
  await page.screenshot({path:path.join(output,`lazy-history-failure-${width}-${height}${keyboard?'-keyboard':''}.png`)});
  throw error;
 } finally {await context.close();}
}
async function checkCompactComposer(page) {
 const draft=await page.locator('#prompt').inputValue();
 await page.locator('#prompt').fill('');
 await page.locator('#prompt').blur();
 await page.waitForFunction(()=>!document.getElementById('composer')?.classList.contains('composer-expanded'));
 const result=await page.evaluate(()=>{
  const box=id=>document.getElementById(id).getBoundingClientRect().toJSON();
  return {expanded:document.getElementById('composer').classList.contains('composer-expanded'),
   composer:box('composer'),prompt:box('prompt'),attach:box('add-attachment'),voice:box('voice-input'),
   promptScrollHeight:document.getElementById('prompt').scrollHeight,promptClientHeight:document.getElementById('prompt').clientHeight,
   attachVisible:getComputedStyle(document.getElementById('add-attachment')).display!=='none',
   voiceVisible:getComputedStyle(document.getElementById('voice-input')).display!=='none'};
 });
 assert.equal(result.expanded,false,'Composer returns to its compact state after blur');
 assert.ok(result.attachVisible&&result.voiceVisible,'Compact composer keeps attach and voice visible');
 assert.ok(result.attach.width>=43.5&&result.voice.width>=43.5,'Compact composer touch targets remain 44px');
 assert.ok(result.attach.right<=result.prompt.x+1,'Attach button overlaps compact prompt');
 assert.ok(result.prompt.right<=result.voice.x+1,'Voice button overlaps compact prompt');
 assert.ok(result.voice.right<=result.composer.right+1,'Voice button leaves compact composer');
 assert.ok(result.promptScrollHeight<=result.promptClientHeight+1,'Compact placeholder is clipped');
 await page.locator('#prompt').fill(draft);await page.locator('#prompt').blur();
 await page.waitForFunction(()=>!document.getElementById('composer')?.classList.contains('composer-expanded'));
 const draftHeight=await page.locator('#prompt').evaluate(el=>({scroll:el.scrollHeight,client:el.clientHeight}));
 assert.ok(draftHeight.scroll<=draftHeight.client+1,'Compact draft wraps behind the composer edge');
}
async function checkTaskStop(page) {
 const draft=await page.locator('#prompt').inputValue();
 await page.locator('#prompt').fill('');await page.locator('#prompt').blur();
 for(const proBusy of [false,true]) {
  await page.evaluate(proBusy=>window.mobileCodexEvent('state',{...window.fixtureSnapshot,busy:true,proBusy,pendingProConsultation:proBusy?{operationId:'layout-consultation'}:null}),proBusy);
  await page.locator('#stop').waitFor({state:'visible'});
  await page.locator('#prompt').focus();
  await page.locator('.msg-copy-btn').first().focus();
  await page.waitForFunction(()=>!document.getElementById('composer').contains(document.activeElement));
  const stop=await page.locator('#stop').boundingBox();
  const viewport=page.viewportSize();
  assert.ok(stop&&stop.width>=43.5&&stop.height>=43.5,'Running task keeps a 44px Stop target after input loses focus');
  assert.ok(stop.x>=0&&stop.x+stop.width<=viewport.width+1&&stop.y>=0&&stop.y+stop.height<=viewport.height+1,'Running task Stop stays inside the viewport');
  await page.locator('#stop').click();
  const action='chat.stop';
  await page.waitForFunction(action=>window.fixtureCalls.some(call=>call.action===action),action);
 }
 await page.evaluate(()=>window.mobileCodexEvent('state',{...window.fixtureSnapshot,busy:false,proBusy:false,pendingProConsultation:null}));
 await page.locator('#prompt').focus();await page.locator('#prompt').blur();
 await page.waitForFunction(()=>!document.getElementById('composer').classList.contains('composer-expanded'));
 assert.equal(await page.locator('#stop').isVisible(),false,'Completed tasks return to compact idle input');
 await page.locator('#prompt').fill(draft);await page.locator('#prompt').blur();
}
async function checkConsultationMenu(page) {
 const draft=await page.locator('#prompt').inputValue();
 const options=()=>page.evaluate(()=>({model:document.getElementById('model').value,effort:document.getElementById('effort').value,permissions:document.getElementById('permissions').value,approval:document.getElementById('approval-mode').value,fast:document.getElementById('fast-mode').getAttribute('aria-pressed')}));
 const before=await options(), callCount=await page.evaluate(()=>fixtureCalls.length);
 await page.locator('#add-attachment').click();
 await page.locator('#composer-add-menu').waitFor({state:'visible'});
 const viewport=page.viewportSize();
 for(const id of ['composer-add-menu','composer-attach','composer-consult-pro']) {
  const box=await page.locator('#'+id).boundingBox();
  assert.ok(box&&box.x>=0&&box.x+box.width<=viewport.width+1&&box.y>=0&&box.y+box.height<=viewport.height+1,`${id} fits the viewport`);
  if(id!=='composer-add-menu')assert.ok(box.width>=43.5&&box.height>=43.5,`${id} keeps a 44px target`);
 }
 await page.keyboard.press('Escape');
 assert.equal(await page.locator('#composer-add-menu').isVisible(),false);
 assert.equal(await page.locator('#add-attachment').evaluate(el=>el===document.activeElement),true);
 await page.locator('#add-attachment').click();await page.mouse.click(viewport.width-2,2);
 assert.equal(await page.locator('#composer-add-menu').isVisible(),false,'Outside clicks dismiss the menu');
 await page.locator('#add-attachment').click();await page.locator('#composer-consult-pro').click();
 const chip=page.locator('.pro-consultation-chip');await chip.waitFor({state:'visible'});
 assert.deepEqual(await options(),before,'Selecting Pro preserves Codex settings');
 assert.equal(await page.evaluate(()=>fixtureCalls.length),callCount,'Selecting Pro performs no native request');
 const remove=await chip.locator('button').boundingBox();assert.ok(remove&&remove.width>=43.5&&remove.height>=43.5,'Pro chip removal has a 44px target');
 await chip.locator('button').click();assert.equal(await chip.count(),0);
 await page.locator('#add-attachment').click();await page.locator('#composer-consult-pro').click();
 await page.locator('#prompt').fill('Review this task with Pro.');await page.locator('#send').click();
 await page.waitForFunction(()=>fixtureCalls.some(call=>call.action==='chat.send'&&call.args.consultPro===true));
 await page.waitForFunction(()=>!document.querySelector('.pro-consultation-chip'));
 assert.equal(await chip.count(),0,'Successful sends consume the one-time consultation');
 assert.equal(await page.evaluate(()=>fixtureCalls.some(call=>call.action==='chat.pro.send')),false);
 assert.deepEqual(await options(),before);
 await page.locator('#prompt').fill(draft);await page.locator('#prompt').blur();
}
async function ensureSidebarOpen(page) {
 const ready=async open=>page.waitForFunction(open=>{
  const side=document.getElementById('sidebar'),box=side.getBoundingClientRect(),mobile=matchMedia('(max-width:760px)').matches;
  return open ? (mobile ? document.body.classList.contains('sidebar-open')&&!side.inert&&box.left>=-.5&&box.right>0 : box.right>0)
    : (mobile ? !document.body.classList.contains('sidebar-open')&&side.inert&&box.right<=.5 : box.right<=.5);
 },open);
 const open=await page.locator('#sidebar').evaluate(el=>matchMedia('(max-width:760px)').matches?document.body.classList.contains('sidebar-open')&&!el.inert:el.getBoundingClientRect().right>0);
 if(!open) { await ready(false);await page.locator('.topbar .sidebar-toggle').click(); }
 await ready(true);
}
async function closeSidebar(page) {
 const mobile=await page.evaluate(()=>matchMedia('(max-width:760px)').matches);
 if(!mobile) return;
 if(await page.evaluate(()=>document.body.classList.contains('sidebar-open'))) await page.locator('#sidebar .sidebar-toggle').click();
 await page.waitForFunction(()=>{const side=document.getElementById('sidebar'),box=side.getBoundingClientRect();return !document.body.classList.contains('sidebar-open')&&side.inert&&box.right<=.5;});
}
async function closeDialog(page, id) {
 await page.locator(`#${id} .dialog-head > button[data-close]:visible`).click();
 await page.locator('#' + id).waitFor({state:'hidden'});
}
async function checkFixedSidebar(page,width,height) {
 await page.evaluate(()=>{
  const sessions=Array.from({length:96},(_,index)=>({id:'sidebar-history-'+index,title:'Long sidebar conversation '+index+' that must remain inside the navigation scroller'}));
  window.fixtureSnapshot={...window.fixtureSnapshot,sessions};
  window.mobileCodexEvent('state',window.fixtureSnapshot);
 });
 await page.waitForFunction(()=>document.querySelectorAll('#sessions .session-row').length===96);
 await ensureSidebarOpen(page);
 const before=await page.evaluate(()=>{
  const box=selector=>document.querySelector(selector).getBoundingClientRect().toJSON();
  const scroll=document.querySelector('.sidebar-scroll');
  return {top:box('.sidebar-top'),bottom:box('.sidebar-bottom'),scroll:box('.sidebar-scroll'),first:box('#sessions .session-row'),last:box('#sessions .session-row:last-child'),controls:['#show-tools','#settings','#account-button'].map(box),scrollHeight:scroll.scrollHeight,clientHeight:scroll.clientHeight,scrollTop:scroll.scrollTop};
 });
 assert.ok(before.scrollHeight>before.clientHeight,`Sidebar list does not overflow at ${width}×${height}`);
 assert.ok(before.scroll.height>0,`Sidebar navigation has no scroll region at ${width}×${height}`);
 await page.locator('.sidebar-scroll').evaluate(el=>el.scrollTop=el.scrollHeight);
 const after=await page.evaluate(()=>{
  const box=selector=>document.querySelector(selector).getBoundingClientRect().toJSON();
  const scroll=document.querySelector('.sidebar-scroll');
  return {top:box('.sidebar-top'),bottom:box('.sidebar-bottom'),scroll:box('.sidebar-scroll'),first:box('#sessions .session-row'),last:box('#sessions .session-row:last-child'),controls:['#show-tools','#settings','#account-button'].map(box),scrollTop:scroll.scrollTop};
 });
 assert.ok(after.scrollTop>0,`Sidebar navigation did not scroll at ${width}×${height}`);
 assert.ok(after.first.y<before.first.y-5,`Sidebar list did not move at ${width}×${height}`);
 assert.ok(Math.abs(after.top.y-before.top.y)<.5,`Sidebar identity moved while navigation scrolled at ${width}×${height}`);
 assert.ok(Math.abs(after.bottom.y-before.bottom.y)<.5,`Sidebar footer moved while navigation scrolled at ${width}×${height}`);
 assert.ok(after.last.bottom<=after.scroll.bottom+1,`Last sidebar conversation is unreachable at ${width}×${height}`);
 for(const [index,control] of after.controls.entries()) {
  assert.ok(control.width>=43.5&&control.height>=43.5,`Sidebar footer control ${index} loses its 44px target at ${width}×${height}`);
  assert.ok(control.top>=after.bottom.top-1&&control.bottom<=after.bottom.bottom+1,`Sidebar footer control ${index} leaves its fixed area at ${width}×${height}`);
  assert.ok(control.left>=0&&control.right<=width+1&&control.top>=0&&control.bottom<=height+1,`Sidebar footer control ${index} is unreachable at ${width}×${height}: ${JSON.stringify(control)}`);
 }
 await page.locator('#show-tools').click();await page.locator('#tool-menu-dialog').waitFor({state:'visible'});
 await closeDialog(page,'tool-menu-dialog');
 await ensureSidebarOpen(page);
 await page.locator('#settings').click();await page.locator('#settings-dialog').waitFor({state:'visible'});
 await closeDialog(page,'settings-dialog');
 await ensureSidebarOpen(page);
 await page.locator('#account-button').click();await page.locator('#settings-dialog').waitFor({state:'visible'});
 assert.equal(await page.locator('[data-settings-tab="account"]').evaluate(el=>el.classList.contains('active')),true,'Account action must remain reachable from the sidebar footer');
 await closeDialog(page,'settings-dialog');
 return {before,after};
}
async function checkSidebar(page,width) {
 await ensureSidebarOpen(page);
 const values=await page.evaluate(()=>{
  const sidebar=document.querySelector('.sidebar-scroll').getBoundingClientRect();
  const project=document.querySelector('.project-more').getBoundingClientRect();
  const session=document.querySelector('.session-more').getBoundingClientRect();
  return {sidebar:sidebar.toJSON(),project:project.toJSON(),session:session.toJSON(),settings:[...document.querySelectorAll('[data-settings-tab]')].map(el=>el.getBoundingClientRect().toJSON())};
 });
 assert.equal(Math.round(values.project.width),44,`project menu hit area at ${width}px`);
 assert.equal(Math.round(values.session.width),44,`session menu hit area at ${width}px`);
 assert.ok(values.project.right<=values.sidebar.right+1,`project menu clipped at ${width}px`);
 assert.ok(values.session.right<=values.sidebar.right+1,`session menu clipped at ${width}px`);
 await page.screenshot({path:path.join(output,`sidebar-open-${width}.png`)});
 if(width===320) {
  await page.locator('#settings').click();await page.locator('#settings-dialog').waitFor({state:'visible'});
  const names=['general','personal','account','tools','advanced','updates'];
  assert.deepEqual(await page.locator('[data-settings-tab]').evaluateAll(items=>items.map(el=>el.dataset.settingsTab)),names,'all six settings tabs exist at 320px');
  for(const name of names) {
   const tab=page.locator(`[data-settings-tab="${name}"]`);
   await tab.click();
   await page.waitForFunction(name=>{
    const tab=document.querySelector(`[data-settings-tab="${name}"]`),nav=tab.closest('nav');
    const box=tab.getBoundingClientRect(),viewport=nav.getBoundingClientRect();
    return tab.classList.contains('active') && box.left>=viewport.left-1 && box.right<=viewport.right+1;
   },name);
   assert.equal(await page.locator(`[data-settings-panel="${name}"]`).isVisible(),true,`${name} settings panel is reachable at 320px`);
   const size=await tab.boundingBox();
   assert.ok(size.width>=43.5&&size.height>=43.5,`${name} settings tab keeps its touch target`);
  }
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'settings navigation does not overflow the document');
  await closeDialog(page,'settings-dialog');
  await closeSidebar(page);
 }
 if(width!==320) await closeSidebar(page);
 return values;
}
async function checkModeSwitch(page,width,theme) {
 await ensureSidebarOpen(page);
 const toggle=await page.locator('#sidebar .mode-switch').boundingBox();
 const logo=await page.locator('#sidebar .brand').boundingBox();
 assert.ok(toggle.x>=logo.x+logo.width,'Mode switch is beside the logo');
 assert.ok(toggle.width<=180,'Mode switch stays compact');
 assert.equal(await page.locator('.topbar .mode-switch').count(),0);
 const headerBefore=await page.locator('.sidebar-top').boundingBox();
 await page.locator('.sidebar-scroll').evaluate(el=>el.scrollTop=el.scrollHeight);
 const headerAfter=await page.locator('.sidebar-top').boundingBox();
 assert.equal(headerAfter.y,headerBefore.y,'Sidebar identity stays fixed');
 await page.locator('.sidebar-scroll').evaluate(el=>el.scrollTop=0);
 await page.locator('#toggle-projects').click();
 assert.equal(await page.locator('#projects').isVisible(),false);
 await page.locator('#toggle-projects').click();
 assert.equal(await page.locator('#projects').isVisible(),true);
 await page.locator('#toggle-history').click();
 assert.equal(await page.locator('#sessions').isVisible(),false);
 await page.locator('#toggle-history').click();
 await page.screenshot({path:path.join(output,`codex-sidebar-${width}-${theme}.png`)});
 const draft=await page.locator('#prompt').inputValue();
 await page.locator('#mode-chat').click();
 await page.waitForTimeout(200);
 assert.equal(await page.locator('#prompt').inputValue(),draft,'Opening native Chat preserves Codex draft');
 assert.equal(await page.locator('body').evaluate(el=>el.classList.contains('chat-mode')),false);
}
async function checkLinuxSettings(page,width,theme,language) {
 await ensureSidebarOpen(page);
 await page.locator('#settings').click();
 await page.locator('[data-settings-tab="tools"]').click();
 const card=page.locator('#linux-environment-card');
 await card.scrollIntoViewIfNeeded();
 const bounds=await card.boundingBox();
 assert.ok(bounds.width>0&&bounds.x>=0&&bounds.x+bounds.width<=width+1,'Linux settings leave viewport');
 assert.equal(await page.locator('#linux-install').isVisible(),true);
 const button=await page.locator('#linux-install').boundingBox();
 assert.ok(button.height>=44&&button.width>=44,'Linux install touch target is too small');
 assert.equal(await page.locator('#linux-enabled').isVisible(),false,'Linux stays disabled before installation');
 await page.evaluate(info=>window.mobileCodexEvent('linux.changed',{...info,busy:true,state:'extracting',downloadedBytes:info.downloadBytes,totalBytes:info.downloadBytes}),snapshot.linux);
 assert.equal(await page.locator('#linux-environment-progress').getAttribute('value'),null,'Extraction must not show download completion as installation progress');
 assert.equal(await page.locator('#linux-cancel').isVisible(),true);
 await page.evaluate(info=>window.mobileCodexEvent('linux.changed',{...info,installed:true,state:'ready'}),snapshot.linux);
 await page.locator('#linux-enabled').scrollIntoViewIfNeeded();
 assert.equal(await page.locator('#linux-enabled').isChecked(),false,'Finishing installation must not enable Linux automatically');
 await page.screenshot({path:path.join(output,`linux-settings-${width}-${theme}-${language}.png`)});
 await closeDialog(page,'settings-dialog');
 await closeSidebar(page);
}
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE||undefined,args:['--no-sandbox','--disable-dev-shm-usage']});
 const results=[];
 try {
  for(const [width,height,theme,language,keyboard] of [[320,720,'light','en',false],[393,852,'light','ko',false],[800,1100,'light','en',false],[1280,900,'dark','en',false],[393,430,'light','ko',true]]) await checkLazyHistory(browser,width,height,theme,language,keyboard);
  for(const [width,height,theme,language] of [[320,720,'light','en'],[393,852,'light','ko'],[393,852,'dark','en'],[800,1100,'light','en'],[1280,900,'dark','en']]){
   const {context,page,errors}=await open(browser,width,height,theme,language);
   const shortHeader=await checkFirstMessageBelowHeader(page,`short conversation ${width}×${height}`);
   const longHeader=await checkLongMessageClearance(page,width,height);
   await checkCompactComposer(page);
   await checkTaskStop(page);
   await checkConsultationMenu(page);
   const layout=await checkComposer(page);const fixedSidebar=await checkFixedSidebar(page,width,height);const sidebar=width<=393?await checkSidebar(page,width):null;
   const sendsBeforeFast=await page.evaluate(()=>fixtureCalls.filter(call=>call.action==='chat.send').length);
   await page.locator('#prompt').focus();await page.locator('#fast-mode').click();
   assert.equal(await page.locator('#fast-mode').getAttribute('aria-pressed'),'true');
   assert.equal(await page.evaluate(()=>fixtureCalls.filter(call=>call.action==='chat.send').length),sendsBeforeFast,'Fast toggle never sends a request by itself');
   const result={width,height,theme,language,layout,sidebar,fixedSidebar,shortHeader,longHeader};results.push(result);
   await page.screenshot({path:path.join(output,`chat-${width}-${theme}-${language}.png`)});
   await page.locator('#files-toggle').click();
   const fileHeader=await page.locator('#file-panel .panel-head').boundingBox();
   const header=await page.locator('.topbar').boundingBox();
   assert.ok(fileHeader.y>=header.y+header.height-1,'File panel hidden under header');
   if(width>760)await checkComposer(page);
   await page.locator('#file-close').click();
   if(width<=393)await checkModeSwitch(page,width,theme);
   if(width<=393)await checkLinuxSettings(page,width,theme,language);
   if(width===393){
    // Exercise the actual user path into Settings, then drag its handle.
    await ensureSidebarOpen(page);await page.locator('#settings').click();
    await page.locator('#settings-dialog').waitFor({state:'visible'});await page.waitForTimeout(220);
    const sheet=await page.locator('#settings-dialog').boundingBox();assert.ok(sheet.y>=0&&sheet.y+sheet.height<=height+1,'Sheet clipped');
    await page.screenshot({path:path.join(output,`settings-${theme}-${language}.png`)});
    const grip=await page.locator('#settings-dialog .sheet-grip').boundingBox();
    await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2+150,{steps:8});await page.mouse.up();
    await page.waitForFunction(()=>!document.getElementById('settings-dialog').open);
    await page.setViewportSize({width,height:430});await page.evaluate(()=>window.mobileCodexEvent('viewport',{keyboardVisible:true}));
    await page.locator('#prompt').fill('A longer draft\nwith several lines\nthat stays above the keyboard.');
    await checkComposer(page);
    await checkTaskStop(page);
    await checkConsultationMenu(page);
    result.keyboardHeader=await checkFirstMessageBelowHeader(page,`keyboard conversation ${width}×430`);
    result.keyboardSidebar=await checkFixedSidebar(page,width,430);
    await page.locator('#prompt').focus();
    await page.waitForFunction(()=>document.getElementById('composer').classList.contains('composer-expanded'));
    const keyboardControls=await page.evaluate(()=>({folder:document.getElementById('composer-folder').getBoundingClientRect().toJSON(),folderText:document.getElementById('context-folder').textContent,options:document.getElementById('composer-options').getBoundingClientRect().toJSON()}));
    assert.ok(keyboardControls.folder.width>=120,'Keyboard composer folder label has no meaningful space');
    assert.ok(keyboardControls.options.width>=100,'Keyboard composer model control is too narrow');
    assert.ok(keyboardControls.folderText.trim().length>=3,'Keyboard composer folder label is empty');
    await page.screenshot({path:path.join(output,`keyboard-${theme}-${language}.png`)});
   }
   assert.deepEqual(errors,[],'Browser errors');await context.close();
  }
  for(const [width,height,theme,language] of [[393,852,'light','ko'],[1280,900,'dark','en']]) {
   const {context,page,errors}=await open(browser,width,height,theme,language,{}, {...snapshot,messages:[],sessions:[]});
   await page.locator('#prompt').fill('');await page.locator('#prompt').blur();
   assert.equal(await page.locator('#welcome').isVisible(),true,'Empty conversation shows the start screen');
   assert.equal(await page.locator('#suggestions button:visible').count(),3,'Three starting actions remain visible');
   await page.screenshot({path:path.join(output,`welcome-${width}-${theme}-${language}.png`)});
   assert.deepEqual(errors,[],'Start screen browser errors');await context.close();
  }
  const {context,page}=await open(browser,393,852,'light','en',{reducedMotion:'reduce'});
  await page.locator('#prompt').focus();
  await page.waitForFunction(()=>document.getElementById('composer')?.classList.contains('composer-expanded'));
  await page.locator('#composer-options').click();
  assert.equal(await page.locator('#options-dialog').evaluate(el=>getComputedStyle(el).animationName),'none');
  assert.equal(await page.locator('#send').evaluate(el=>getComputedStyle(el).transitionDuration),'0s');
  await page.screenshot({path:path.join(output,'options-reduced-motion.png')});await context.close();
  const sourceGitDiffHash=crypto.createHash('sha256').update(execFileSync('git',['diff','--no-ext-diff'],{cwd:root,encoding:'utf8'})).digest('hex');
  fs.writeFileSync(path.join(output,'layout-results.json'),JSON.stringify({sourceTimestamp:new Date().toISOString(),sourceGitDiffHash,viewports:results,assertions:'composer bounds, Chat and Codex round-trip with distinct sidebars, fixed sidebar identity and footer with a long navigation list, 44px targets, tools/settings/account reachability, settings visibility, file panel, keyboard resize, reduced motion'},null,2));
  console.log('Browser layouts passed: 320/393/800/1280px, light/dark, English/Korean, fixed sidebar footer under long-list and keyboard viewport, sheet drag and reduced motion.');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
