/* Real Chromium rendering and interactions with a mock Android bridge. */
const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const web = path.resolve(__dirname, '../app/src/main/assets/web');
const output = path.resolve(__dirname, '../artifacts/project-identity-preview');
fs.mkdirSync(output, {recursive:true});

(async () => {
 const browser = await chromium.launch({headless:true, executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE || undefined});
 try {
  for (const width of [320, 393, 1280]) {
   const page = await browser.newPage({viewport:{width, height:900}, colorScheme:'light'}), errors=[];
   page.on('pageerror', error => errors.push(error.message));
   await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'https://appassets.androidplatform.net') return route.abort();
    const file = path.resolve(web, '.' + url.pathname);
    return file.startsWith(web + path.sep) && fs.existsSync(file) ? route.fulfill({path:file}) : route.fulfill({status:404,body:''});
   });
   await page.addInitScript(() => {
    localStorage.setItem('chat-icons','off');
    const binding = (key, name) => ({key,name,uri:'content://provider/tree/' + 'long-folder-'.repeat(15) + key,available:true,hasLocalFolder:true});
    window.testState = {ready:false,account:{},authState:'unknown',models:[],busy:false,threadId:'ta',messages:[],
     workspace:{key:'a',selected:true,name:'Shared project',available:true},
     projects:[{key:'a',projectId:'pa',name:'Shared project',available:true,workspaceKeys:['a'],bindings:[binding('a','Folder A')]},
      {key:'b',projectId:'pb',name:'Shared project with a long name for narrow screens',available:true,workspaceKeys:['b'],bindings:[binding('b','Folder B')]}],
     sessions:[{id:'ta',workspaceKey:'a',title:'A conversation'},{id:'tb',workspaceKey:'b',title:'B conversation'}]};
    window.testCalls=[];
    window.Native={locale:()=>JSON.stringify({choice:'en',systemLanguage:'en'}),postMessage(raw) {
     const m=JSON.parse(raw); window.testCalls.push(m);
     setTimeout(() => {
      let result={};
      if (m.action==='state') result=window.testState;
      if (m.action==='projects.merge') {
       const [a,b]=window.testState.projects;
       window.testState.projects=[{...b,workspaceKeys:['a','b'],bindings:[...a.bindings,...b.bindings]}];
       result={projects:window.testState.projects};
      }
      if (m.action==='projects.prefer') {
       window.testState.projects[0].key=m.args.key;result={projects:window.testState.projects};
      }
      if (m.action==='ui.projects.importFile') result={content:'test-bundle'};
      if (m.action==='projects.import.preview') result={token:'preview',result:{addedEvents:3,summary:{linkCount:1,projects:Array.from({length:8},(_,i)=>({name:'Imported-project-' + 'long-name-'.repeat(12) + i,nameConflicts:i===0?['Desktop name','Phone name']:[]}))}}};
      if (m.action==='projects.import.apply') {
       window.testState.projects=[{key:'imported',projectId:'proj_imported',name:'Desktop name',nameConflicts:['Desktop name','Phone name'],available:false,hasLocalFolder:false}];
       result={projects:window.testState.projects};
      }
      if (m.action==='projects.rename') {
       window.testState.projects=window.testState.projects.map(p=>p.key===m.args.key?{...p,name:m.args.name,nameConflicts:[]}:p);
       result={name:m.args.name};window.mobileCodexEvent('state',window.testState);
      }
      if (m.action==='projects.export') result={format:'mobile-codex-projects',schemaVersion:1,events:[]};
      if (m.action==='ui.projects.exportFile') result={saved:true};
      window.mobileCodexEvent('response',{id:m.id,result});
     },20);
    }};
   });
   await page.goto('https://appassets.androidplatform.net/index.html');
   await page.waitForFunction(() => document.querySelectorAll('.project-more').length===2);
   const waitSidebarRestored = async (focusAdd=false) => {
    if (width >= 761) return;
    await page.waitForFunction(focusAdd => document.body.classList.contains('sidebar-open') && !document.getElementById('sidebar').inert && (!focusAdd || document.activeElement?.id === 'add-project'),focusAdd);
   };
   const waitProjectLinkClose = async (focusAdd=false) => {
    await page.waitForFunction(() => !document.getElementById('project-link-dialog').open);
    await waitSidebarRestored(focusAdd);
   };
   const openSidebar = async () => {
    if (width >= 761) return;
    await page.waitForFunction(() => !document.querySelector('dialog[open]'));
    const open = await page.evaluate(() => { const side=document.getElementById('sidebar'); return document.body.classList.contains('sidebar-open') && !side.inert; });
    if (!open) {
     await page.waitForFunction(() => { const side=document.getElementById('sidebar'),box=side.getBoundingClientRect(); return !document.body.classList.contains('sidebar-open') && side.inert && box.right <= .5; });
     await page.locator('.topbar .sidebar-toggle').click();
    }
    await page.waitForFunction(() => { const side=document.getElementById('sidebar'),box=side.getBoundingClientRect(); return document.body.classList.contains('sidebar-open') && !side.inert && box.left >= -.5 && box.right > 0; });
   };
   const closeSidebar = async () => {
    if (width >= 761) return;
    if (await page.evaluate(() => document.body.classList.contains('sidebar-open'))) await page.locator('#sidebar .sidebar-toggle').click();
    await page.waitForFunction(() => { const side=document.getElementById('sidebar'),box=side.getBoundingClientRect(); return !document.body.classList.contains('sidebar-open') && side.inert && box.right <= .5; });
   };
   const geometry = async () => {
    await page.locator('#project-link-dialog').evaluate(e => Promise.all(e.getAnimations({subtree:true}).map(a => a.finished)));
    const value = await page.evaluate(() => {
     const dialog=document.getElementById('project-link-dialog'), rect=dialog.getBoundingClientRect();
     return {width:innerWidth,scrollWidth:document.documentElement.scrollWidth,dialog:{left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom},height:innerHeight,
      choices:[...document.querySelectorAll('#project-link-choices button')].map(b => {const r=b.getBoundingClientRect();return {left:r.left,right:r.right,height:r.height,scroll:b.scrollWidth,client:b.clientWidth};})};
    });
    assert.ok(value.scrollWidth<=value.width,`page overflow at ${width}`);
    assert.ok(value.dialog.left>=0 && value.dialog.right<=value.width+1 && value.dialog.top>=0 && value.dialog.bottom<=value.height+1,`dialog outside ${width}: ${JSON.stringify(value)}`);
    for (const choice of value.choices) assert.ok(choice.height>=44 && choice.left>=0 && choice.right<=value.width+1 && choice.scroll<=choice.client+1,`choice overflow at ${width}`);
   };
   await openSidebar(); await page.locator('.project-more').first().click();
   await page.getByRole('button',{name:'Merge projects',exact:true}).click();
   await geometry();
   await page.screenshot({path:path.join(output,`merge-${width}.png`)});
   // Escape keeps the identities separate and returns focus to the project menu.
   await page.keyboard.press('Escape');
   await page.waitForFunction(() => document.activeElement?.dataset.projectMenuKey==='a');
   assert.equal(await page.locator('.project-tree').count(),2);
   await page.locator('.project-more').first().click(); await page.getByRole('button',{name:'Merge projects',exact:true}).click();
   page.once('dialog', dialog => dialog.accept());
   await page.locator('#project-link-choices button').click();
   await page.waitForFunction(() => document.querySelectorAll('.project-tree').length===1 && !document.getElementById('project-link-dialog').open);
   await waitProjectLinkClose();
   assert.equal(await page.locator('#projects .session').count(),2);
   await openSidebar(); await page.locator('.project-more').click();
   await page.locator('#project-actions').getByRole('button',{name:'Default local folder',exact:true}).click();
   await geometry(); await page.screenshot({path:path.join(output,`bindings-${width}.png`)});
   await page.locator('#project-link-choices button').first().click();
   await waitProjectLinkClose();
   await page.evaluate(() => window.mobileCodexEvent('state',{...window.testState,workspace:{key:'empty',selected:true,name:'No folder',available:false},projects:[{key:'empty',name:'No folder',projectId:'empty-id',available:false,hasLocalFolder:false}],sessions:[]}));
   await openSidebar();
   assert.ok(await page.getByRole('button',{name:'Link local folder',exact:true}).isVisible());
   assert.equal(await page.locator('#projects .sidebar-empty').count(),0);
   assert.ok(await page.locator('.project-new').isDisabled());
   await page.screenshot({path:path.join(output,`unbound-${width}.png`)});
   await page.evaluate(() => window.mobileCodexEvent('state',{...window.testState,workspace:{selected:false,key:'',projectId:'',name:'Codex',hasLocalFolder:true,available:true,defaultWorkspace:true},projects:[],sessions:[{id:'orphan',workspaceKey:'removed-binding',title:'Migrating conversation'}]}));
   await openSidebar();
   assert.equal(await page.locator('#context-folder').textContent(),'Codex');
   assert.equal(await page.locator('.detached-projects').count(),0);
   assert.equal(await page.locator('#projects .project-tree').count(),0);
   assert.equal(await page.locator('#sessions .session').count(),1);
   await closeSidebar();
   await page.locator('#files-toggle').click();
   await page.waitForFunction(() => window.testCalls.some(call => call.action==='files.list'));
   await page.screenshot({path:path.join(output,`general-codex-${width}.png`)});
   await page.locator('#file-close').click();
   await openSidebar();
   await page.locator('#add-project').click();
   await page.getByRole('button',{name:'Create project without a folder',exact:true}).click();
   await page.waitForFunction(() => document.getElementById('input-dialog').open && document.activeElement?.id==='input-value');
   await page.locator('#input-value').fill('Metadata only');
   await page.keyboard.press('Escape');await page.waitForFunction(() => !document.getElementById('input-dialog').open);
   assert.equal(await page.evaluate(() => window.testCalls.some(c => c.action==='projects.create')),false);
   await openSidebar(); await page.locator('#add-project').click(); await page.getByRole('button',{name:'Import projects',exact:true}).click();
   await page.locator('#project-link-choices button').filter({has:page.getByText('Import',{exact:true})}).waitFor();
   await geometry(); await page.screenshot({path:path.join(output,`import-preview-${width}.png`)});
   await page.keyboard.press('Escape');await waitProjectLinkClose(true);
   assert.equal(await page.evaluate(() => window.testCalls.some(c => c.action==='projects.import.apply')),false);
   await openSidebar(); await page.locator('#add-project').click(); await page.getByRole('button',{name:'Import projects',exact:true}).click();
   await page.locator('#project-link-choices button').filter({has:page.getByText('Import',{exact:true})}).click();
   await waitProjectLinkClose();
   await openSidebar(); assert.ok(await page.locator('.project-new').isDisabled());
   await page.getByRole('button',{name:'Resolve name conflict',exact:true}).click();
   await geometry(); await page.screenshot({path:path.join(output,`name-conflict-${width}.png`)});
   await page.locator('#project-link-choices button').first().click();
   await waitProjectLinkClose();
   await openSidebar(); await page.locator('.project-more').click();
   page.once('dialog',dialog=>dialog.accept()); await page.getByRole('button',{name:'Export project',exact:true}).click();
   await page.waitForFunction(() => window.testCalls.some(c => c.action==='ui.projects.exportFile'));
   assert.deepEqual(errors,[]);
   console.log(`PASS ${width}px: merge, cancel/focus, bindings, unbound, import preview/apply, conflicts, export; no overflow`);
   await page.close();
  }
 } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
