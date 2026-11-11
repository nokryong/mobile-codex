/* Native bridge fixtures only; production relay E2E is a separate release gate. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {createReviewServer}=require('./ui_review_server.cjs');
async function fixture(){
 const fx=window.__reportFx={calls:[],attempts:0,hold:false,available:false}, original=window.Native.postMessage.bind(window.Native);
 window.Native.postMessage=raw=>{
  const request=JSON.parse(raw),{id,action}=request;if(!action.startsWith('reports.'))return original(raw);
  fx.calls.push(request);const reply=value=>window.mobileCodexEvent('response',{id,result:value});
  if(action==='reports.status')return reply({available:fx.available});
  if(action==='reports.preview'&&!fx.available)return reply({status:'unavailable'});
  if(action==='reports.preview')return reply({status:'preview',reportId:'synthetic-preview',payload:{schema:1,reportId:'synthetic-preview',appVersion:'0.3.6',errorCode:'initialize_timeout',operation:'login',stage:'initialize_sent',elapsedMs:60000,processAlive:true,stdoutBytes:0,stdoutLines:0,androidApi:35,manufacturer:'samsung',deviceClass:'phone',ramClass:'le8gb',pageSize:'16k'}});
  if(action==='reports.cancel'){fx.finish?.({status:'cancelled'});return reply({status:'cancelled'});}
  if(action==='reports.send'){
   if(fx.hold){fx.finish=reply;return;}
   fx.attempts++;if(fx.attempts>1){fx.available=false;window.mobileCodexEvent('reports.available',{available:false});}return reply(fx.attempts===1?{status:'failed',code:'send_unconfirmed'}:{status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/15',grouped:true});
  }
 };
 window.mobileCodexEvent('reports.available',{available:false});
}
(async()=>{
 const server=createReviewServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true});
 try{
  for(const [width,language,theme]of [[320,'ko','light'],[393,'en','dark'],[1280,'en','light']]){
   const page=await browser.newPage({viewport:{width,height:844}}),errors=[];page.on('pageerror',error=>errors.push(error.message));
   await page.goto('http://127.0.0.1:'+server.address().port+'/app?language='+language+'&theme='+theme);await page.waitForFunction(()=>window.__uiReviewReady);
   await page.evaluate(fixture);
   const hiddenLaunchers=()=>page.evaluate(()=>['report-open','report-login-open','report-error-open'].every(id=>document.getElementById(id).hidden));
   assert.equal(await hiddenLaunchers(),true);
   assert.ok(await page.evaluate(()=>window.__uiReviewCalls.some(call=>call.action==='reports.status')));
   await page.evaluate(()=>{window.__reportFx.available=true;window.mobileCodexEvent('reports.available',{available:true});});
   assert.equal(await hiddenLaunchers(),false);
   await page.locator('#report-error-open').click();await page.waitForFunction(()=>document.getElementById('report-payload').textContent.includes('initialize_timeout'));
   assert.equal(await page.evaluate(()=>window.__reportFx.calls.filter(call=>call.action==='reports.send').length),0);
   assert.equal(await page.locator('#report-dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);
   assert.equal(await page.locator('#report-dialog').evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth+1&&r.top>=0&&r.bottom<=innerHeight+1;}),true);
   assert.equal(await page.locator('#report-dialog').textContent().then(text=>/공개 GitHub|public GitHub/.test(text)),true);
   await page.locator('#report-send').click();await page.waitForFunction(()=>!document.getElementById('report-send').disabled);
   await page.locator('#report-send').click();await page.waitForFunction(()=>!document.getElementById('report-result').hidden);
   assert.equal(await page.evaluate(()=>new Set(window.__reportFx.calls.filter(call=>call.action==='reports.send').map(call=>call.args.reportId)).size),1);
   assert.equal(await hiddenLaunchers(),true);
   assert.equal(await page.locator('#report-payload').textContent(),'');
   await page.locator('#report-cancel').click();assert.equal(await page.locator('#report-dialog').evaluate(el=>el.open),false);
   await page.evaluate(()=>{window.__reportFx.hold=true;window.__reportFx.available=true;window.mobileCodexEvent('reports.available',{available:true});});await page.locator('#report-error-open').click();await page.locator('#report-send').click();assert.equal(await page.locator('#report-send').isDisabled(),true);
   await page.locator('#report-cancel').click();await page.waitForFunction(()=>document.getElementById('report-status').textContent.includes('cancelled')||document.getElementById('report-status').textContent.includes('취소했습니다'));
   assert.equal(await page.locator('#report-send').isDisabled(),true);
   await page.locator('#report-cancel').click();await page.locator('#report-error-open').click();
   await page.waitForFunction(()=>!document.getElementById('report-send').disabled);
   await page.evaluate(()=>{window.__reportFx.available=false;window.mobileCodexEvent('reports.available',{available:false,previewInvalidated:true});});
   assert.equal(await hiddenLaunchers(),true);assert.equal(await page.locator('#report-send').isDisabled(),true);
   assert.equal(await page.locator('#report-payload').textContent(),'');
   assert.ok(await page.evaluate(()=>window.__reportFx.calls.some(call=>call.action==='reports.cancel')));
   assert.deepEqual(errors,[]);await page.close();
  }
  console.log('Diagnostic report browser flow passed: consent, exact preview, offline/retry, cancellation, safe result links; mobile/desktop and Korean/English.');
 }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
