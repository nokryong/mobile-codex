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
 workspace:{selected:true,key:'demo-project',name:'Very long project display name that keeps its menu visible'},projects:[{key:'demo-project',name:'Very long project display name that keeps its menu visible',available:true}],
 account:{type:'chatgpt',email:'demo@example.test'},
 sessions:[{id:'demo',workspaceKey:'demo-project',title:'A very long conversation title that must keep its action button visible'}],
 models:[{id:'gpt-example',model:'gpt-example',displayName:'Default model',isDefault:true,supportedReasoningEfforts:[{reasoningEffort:'medium',description:'Medium'}]}],
 messages:[{id:'u',role:'user',text:'Help me organize this project.'},{id:'a',role:'assistant',text:'I reviewed the project files. Here is a clear place to start.\n\n- Keep the source files in `src/`.\n- Put setup instructions in `README.md`.\n- Review the changes before running the app.\n\n```js\nconst greeting = "Hello, mobile";\nconsole.log(greeting);\n```\n\nWhat would you like to work on first?'}]
};
async function open(browser, width, height, theme='light', language='en', extra={}) {
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
  window.Native={locale:()=>JSON.stringify({choice:language,systemLanguage:language}),postMessage(raw){
   const m=JSON.parse(raw);let result={};
   if(m.action==='state') result=snapshot;
   if(m.action==='updates.state') result={versionName:'0.1.13-alpha',versionCode:14,repository:'nokryong/mobile-codex',prereleases:true};
   if(m.action==='instructions.read') result={content:'Read the relevant files before editing.',activePath:'/private/AGENTS.md'};
   if(m.action==='files.list') result={entries:[]};
   setTimeout(()=>window.mobileCodexEvent('response',{id:m.id,result}),0);
  }};
 }, {snapshot,theme,language});
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
  const ids=['add-attachment','composer-folder','composer-options','voice-input','send'];
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
 return result;
}
async function checkSidebar(page,width) {
 await page.locator('.topbar .sidebar-toggle').click();await page.waitForTimeout(180);
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
  const settings=await page.locator('[data-settings-tab]').evaluateAll(items=>items.map(el=>el.getBoundingClientRect().toJSON()));
  assert.equal(settings.length,6,'all settings tabs exist at 320px');
  assert.ok(settings.every(box=>box.width>0&&box.right<=320+1),'settings tab is clipped at 320px');
  await page.locator('#settings-dialog [data-close="settings-dialog"]').last().click();
  if(await page.evaluate(()=>document.body.classList.contains('sidebar-open'))) { await page.locator('#sidebar .sidebar-toggle').click();await page.waitForTimeout(180); }
 }
 if(width!==320 && await page.evaluate(()=>document.body.classList.contains('sidebar-open'))) { await page.locator('#sidebar .sidebar-toggle').click();await page.waitForTimeout(180); }
 return values;
}
async function checkModeSwitch(page,width,theme) {
 await page.locator('.topbar .sidebar-toggle').click();
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
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE||undefined,args:['--no-sandbox','--disable-dev-shm-usage']});
 const results=[];
 try {
  for(const [width,height,theme,language] of [[320,720,'light','en'],[393,852,'light','ko'],[393,852,'dark','en'],[800,1100,'light','en'],[1280,900,'dark','en']]){
   const {context,page,errors}=await open(browser,width,height,theme,language);
   const layout=await checkComposer(page);const sidebar=width<=393?await checkSidebar(page,width):null;
   results.push({width,height,theme,language,layout,sidebar});
   await page.screenshot({path:path.join(output,`chat-${width}-${theme}-${language}.png`)});
   await page.locator('#files-toggle').click();
   const fileHeader=await page.locator('#file-panel .panel-head').boundingBox();
   const header=await page.locator('.topbar').boundingBox();
   assert.ok(fileHeader.y>=header.y+header.height-1,'File panel hidden under header');
   if(width>760)await checkComposer(page);
   await page.locator('#file-close').click();
   if(width<=393)await checkModeSwitch(page,width,theme);
   if(width===393){
    // Exercise the actual user path into Settings, then drag its handle.
    await page.locator('.topbar .sidebar-toggle').click();await page.locator('#settings').click();
    await page.locator('#settings-dialog').waitFor({state:'visible'});await page.waitForTimeout(220);
    const sheet=await page.locator('#settings-dialog').boundingBox();assert.ok(sheet.y>=0&&sheet.y+sheet.height<=height+1,'Sheet clipped');
    await page.screenshot({path:path.join(output,`settings-${theme}-${language}.png`)});
    const grip=await page.locator('#settings-dialog .sheet-grip').boundingBox();
    await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2+150,{steps:8});await page.mouse.up();
    await page.waitForFunction(()=>!document.getElementById('settings-dialog').open);
    await page.setViewportSize({width,height:430});await page.evaluate(()=>window.mobileCodexEvent('viewport',{keyboardVisible:true}));
    await page.locator('#prompt').fill('A longer draft\nwith several lines\nthat stays above the keyboard.');
    await checkComposer(page);
    const keyboardControls=await page.evaluate(()=>({folder:document.getElementById('composer-folder').getBoundingClientRect().toJSON(),folderText:document.getElementById('context-folder').textContent,options:document.getElementById('composer-options').getBoundingClientRect().toJSON()}));
    assert.ok(keyboardControls.folder.width>=120,'Keyboard composer folder label has no meaningful space');
    assert.ok(keyboardControls.options.width>=100,'Keyboard composer model control is too narrow');
    assert.ok(keyboardControls.folderText.trim().length>=3,'Keyboard composer folder label is empty');
    await page.screenshot({path:path.join(output,`keyboard-${theme}-${language}.png`)});
   }
   assert.deepEqual(errors,[],'Browser errors');await context.close();
  }
  const {context,page}=await open(browser,393,852,'light','en',{reducedMotion:'reduce'});
  await page.locator('#prompt').focus();
  await page.waitForFunction(()=>document.getElementById('composer')?.classList.contains('composer-expanded'));
  await page.locator('#composer-options').click();
  assert.equal(await page.locator('#options-dialog').evaluate(el=>getComputedStyle(el).animationName),'none');
  assert.equal(await page.locator('#send').evaluate(el=>getComputedStyle(el).transitionDuration),'0s');
  await page.screenshot({path:path.join(output,'options-reduced-motion.png')});await context.close();
  const sourceGitDiffHash=crypto.createHash('sha256').update(execFileSync('git',['diff','--no-ext-diff'],{cwd:root,encoding:'utf8'})).digest('hex');
  fs.writeFileSync(path.join(output,'layout-results.json'),JSON.stringify({sourceTimestamp:new Date().toISOString(),sourceGitDiffHash,viewports:results,assertions:'composer bounds, Chat and Codex round-trip with distinct sidebars, sidebar long-label action columns, 44px targets, settings visibility, file panel, keyboard resize, reduced motion'},null,2));
  console.log('Browser layouts passed: 320/393/800/1280px, light/dark, English/Korean, resized keyboard, sheet drag and reduced motion.');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
