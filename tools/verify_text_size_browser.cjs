/* Layout proxy for Android WebSettings text zoom, not an Android device test. */
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const web=path.resolve(__dirname,'../app/src/main/assets/web');
const output=path.resolve(__dirname,'../artifacts/text-size-preview');
fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage'],executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE||undefined});
 try {
  for(const [width,height] of [[320,568],[393,852],[800,1100],[1024,600],[1280,900]]) {
   const page=await browser.newPage({viewport:{width,height}}),errors=[];
   page.on('pageerror',error=>errors.push(error.message));
   await page.route('**/*',route=>{
    const url=new URL(route.request().url()),file=path.resolve(web,'.'+url.pathname);
    return url.origin==='https://appassets.androidplatform.net'&&file.startsWith(web+path.sep)&&fs.existsSync(file)?route.fulfill({path:file}):route.abort();
   });
   await page.addInitScript(()=>{
    localStorage.setItem('chat-icons','off');let percent=100;
    window.Native={locale:()=>JSON.stringify({choice:'en'}),textSize:()=>JSON.stringify({percent}),postMessage(raw){
     const m=JSON.parse(raw);let result={};
     if(m.action==='state')result={ready:true,threadId:'font-test',busy:false,models:[],sessions:[],projects:[],account:{},workspace:{selected:false,key:'',name:'Codex',hasLocalFolder:true,available:true},messages:[{id:'u',role:'user',text:'Readable text on a phone and a tablet.',createdAt:1790670840000},{id:'a',role:'assistant',text:'Read this answer without changing your draft or your model.'}]};
     if(m.action==='ui.textSize') {
      percent=m.args.percent;result={percent};
      // A desktop browser has no Android textZoom. Freeze unscaled font metrics
      // once and scale glyph sizes only; do not emulate with whole-page zoom.
      window.fontMetrics ||= [...document.querySelectorAll('body,body *')].filter(el=>!el.closest('svg')).map(el=>[el,parseFloat(getComputedStyle(el).fontSize)]);
      for(const [el,size] of window.fontMetrics)el.style.fontSize=size*percent/100+'px';
     }
     setTimeout(()=>window.mobileCodexEvent('response',{id:m.id,result}),0);
    }};
   });
   await page.goto('https://appassets.androidplatform.net/index.html');
   await page.locator('[data-id="a"]').waitFor();
   await page.locator('#prompt').fill('Keep this draft');await page.locator('#prompt').blur();
   const base=await page.locator('[data-id="a"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize));
   if(width<=760)await page.locator('.topbar .sidebar-toggle').click();
   await page.locator('#settings').click();
   assert.equal(await page.locator('#text-size').inputValue(),'100','Default remains 100%');
   assert.deepEqual(await page.locator('#text-size option').evaluateAll(options=>options.map(option=>option.value)),['75','85','100','115','130','150']);
   for(const percent of [75,85,100]) {
    await page.locator('#text-size').selectOption(String(percent));
    await page.waitForFunction(value=>document.documentElement.dataset.textSize===String(value),percent);
    const actual=await page.locator('[data-id="a"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize));
    assert.ok(Math.abs(actual-base*percent/100)<.02,`${percent}% is relative to the unchanged default at ${width}`);
    assert.equal(await page.locator('#prompt').inputValue(),'Keep this draft');
   }
   await page.locator('#text-size').selectOption('150');
   await page.waitForFunction(()=>document.documentElement.dataset.textSize==='150');
   assert.equal(await page.locator('[data-id="a"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize)),base*1.5);
   await page.locator('#text-size').scrollIntoViewIfNeeded();
   const select=await page.locator('#text-size').boundingBox();
   assert.ok(select.width>=44&&select.height>=44&&select.x>=0&&select.x+select.width<=width+1,`Text size control at ${width}`);
   await page.screenshot({path:path.join(output,`settings-${width}-150.png`)});
   await page.locator('#settings-dialog .dialog-head > button[data-close]:visible').click();
   await page.locator('#settings-dialog').waitFor({state:'hidden'});
   await page.waitForFunction(()=>!document.getElementById('composer').classList.contains('composer-expanded'));
   const metrics=await page.locator('#prompt').evaluate(el=>({scroll:el.scrollHeight,client:el.clientHeight}));
   assert.ok(metrics.scroll<=metrics.client+1,`Compact enlarged draft clipped at ${width}: ${JSON.stringify(metrics)}`);
   assert.equal(await page.locator('#prompt').inputValue(),'Keep this draft');
   const doc=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));assert.ok(doc.scroll<=doc.width,`Horizontal overflow at ${width}`);
   await page.locator('#chat-scroll').evaluate(el=>el.scrollTop=0);
   const header=await page.locator('.topbar').boundingBox(),first=await page.locator('[data-id="u"]').boundingBox();
   assert.ok(first.y>=header.y+header.height,`Enlarged first message below header at ${width}`);
   await page.screenshot({path:path.join(output,`conversation-${width}-150.png`)});
   await page.locator('#prompt').focus();
   await page.waitForFunction(()=>document.getElementById('composer').classList.contains('composer-expanded'));
   await page.locator('#fast-mode').evaluate(el=>el.setAttribute('aria-pressed','true'));
   for(const theme of ['light','dark']) {
    await page.evaluate(value=>document.documentElement.dataset.theme=value,theme);
    const surfaces=await page.evaluate(()=>{
     const pseudo=selector=>{const style=getComputedStyle(document.querySelector(selector),'::before');return {width:parseFloat(style.width),height:parseFloat(style.height)};};
     return {fast:pseudo('#fast-mode'),send:pseudo('#send'),approval:pseudo('.approval-mode-picker'),model:pseudo('.composer-bottom .options-button'),fastOuter:getComputedStyle(document.getElementById('fast-mode')).backgroundColor};
    });
    assert.equal(surfaces.fastOuter,'rgba(0, 0, 0, 0)',`${theme} Fast must not paint its full 44px target`);
    for(const key of ['fast','send','approval','model'])assert.equal(surfaces[key].height,32,`${theme} ${key} visible height at ${width}`);
    for(const key of ['fast','send'])assert.equal(surfaces[key].width,32,`${theme} ${key} visible width at ${width}`);
    await page.screenshot({path:path.join(output,`composer-${width}-150-${theme}.png`)});
   }
   assert.deepEqual(errors,[]);await page.close();
  }
  console.log('PASS text-size layout proxy: unchanged 100% default; 75/85/100/150% at 320/393/800/1024/1280px; 32px composer surfaces. Native textZoom covered separately.');
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
