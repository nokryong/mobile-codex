/* Browser check of the invited-agents UI on the packaged web assets.
   The review fixture plays the Engine; agent answers are explicit test fixtures. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {createReviewServer}=require('./ui_review_server.cjs');
const out=path.join(__dirname,'../artifacts/agents-browser');fs.mkdirSync(out,{recursive:true});

// Runs in the page: merges fixture agents into every Engine state and answers agent actions.
function installFixture(){
  const fx=window.__agentFx={agents:[],agentRun:null,extra:[],base:null,calls:[],
    providers:[{id:'claude',state:'ready',version:'2.1.0',nativeAutoReview:true,models:[{id:'claude-opus',label:'Opus',isDefault:true,efforts:['medium','high'],defaultEffort:'high'},{id:'claude-sonnet',label:'Sonnet',efforts:['low','medium']}]},
      {id:'agy',state:'login_required',reason:'로그인이 필요합니다.',models:[]}]};
  const emit=window.mobileCodexEvent;
  const merge=s=>({...s,agents:fx.agents,agentProviders:fx.providers,agentRun:fx.agentRun,busy:!!s.busy||!!fx.agentRun,messages:[...(s.messages||[]),...fx.extra]});
  window.mobileCodexEvent=(name,data)=>{
    if(name==='state'){fx.base=data;data=merge(data);}
    if(name==='response'&&data?.result?.models&&Array.isArray(data.result.messages)){fx.base=data.result;data={...data,result:merge(data.result)};}
    return emit(name,data);
  };
  const publish=()=>emit('state',merge(fx.base));
  const names={claude:'Claude',gemini:'Gemini'},providers={claude:'claude',gemini:'agy'};
  const original=window.Native.postMessage.bind(window.Native);
  window.Native.postMessage=raw=>{
    const m=JSON.parse(raw),reply=result=>setTimeout(()=>emit('response',{id:m.id,result}),0);
    if(!m.action.startsWith('agents.')&&!['chat.agents.set','chat.send','chat.stop','ui.providerLoginBrowser','ui.copyCode'].includes(m.action))return original(raw);
    fx.calls.push(m);const p=m.args||{};
    if(m.action==='agents.providers.check')return reply({providers:fx.providers});
    if(m.action.startsWith('agents.provider.')||m.action==='ui.providerLoginBrowser'||m.action==='ui.copyCode')return reply({ok:true});
    if(m.action==='chat.agents.set'){
      for(const c of p.agents){const at=fx.agents.findIndex(a=>a.id===c.id);
        if(at<0)fx.agents.push({id:c.id,provider:providers[c.id],name:names[c.id],model:c.model||'',effort:c.effort||'',mode:c.mode||'chat',enabled:c.enabled!==false});
        else fx.agents[at]={...fx.agents[at],...c};}
      fx.agents=fx.agents.filter(a=>a.enabled!==false);publish();return reply(merge(fx.base));
    }
    if(m.action==='chat.stop'){fx.agentRun=null;publish();return reply({ok:true});}
    // All start together. Claude's first output arrives before Codex's.
    const id='u'+fx.calls.length;
    fx.extra.push({id,role:'user',text:p.text,createdAt:Date.now()});
    fx.agentRun={active:['codex',...fx.agents.map(a=>a.id)],states:{},queue:[]};publish();reply({ok:true});
    setTimeout(()=>{fx.extra.push({id:id+'-codex',role:'assistant',text:'Codex: 좋은 질문입니다.'});publish();},130);
    let delay=40;
    for(const agent of fx.agents){
      const msg={id:id+'-'+agent.id,role:'assistant',agent:agent.id,agentName:agent.name,text:'',status:'streaming'};
      setTimeout(()=>{fx.extra.push(msg);publish();},delay);
      setTimeout(()=>emit('message.delta',{threadId:fx.base.threadId,id:msg.id,delta:agent.name+' 의견: '}),delay+30);
      setTimeout(()=>{Object.assign(msg,{text:agent.name+' 의견: 같은 질문에 답합니다.',status:'complete'});publish();},delay+60);
      delay+=100;
    }
    setTimeout(()=>{fx.agentRun=null;publish();},delay+20);
  };
}

async function main(){
  const server=createReviewServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const browser=await chromium.launch({headless:true,executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE||undefined});const results=[];
  try{for(const width of [320,390,1280]){
    const context=await browser.newContext({viewport:{width,height:900}});const page=await context.newPage();const errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.goto('http://127.0.0.1:'+server.address().port+'/app?language=ko');
    await page.waitForFunction(()=>window.mobileCodexEvent&&window.MobileCodexAgents&&document.querySelector('#agents-toggle'));
    await page.evaluate(installFixture);
    await page.evaluate(()=>window.Native.postMessage(JSON.stringify({id:'refresh',action:'state',args:{}})));
    await page.waitForFunction(()=>window.__agentFx.base);
    const calls=action=>page.evaluate(a=>window.__agentFx.calls.filter(c=>c.action===a).map(c=>c.args),action);
    const overflow=()=>page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);

    // The participants button sits in the header next to Files.
    assert.equal(await page.locator('#agents-toggle').isVisible(),true);assert.equal(await overflow(),false);
    // + → invite sheet: Claude is ready, Gemini asks for sign-in.
    await page.locator('#add-attachment').click();
    await page.getByRole('menuitem',{name:'에이전트 초대'}).click();
    await page.waitForFunction(()=>document.querySelector('#invite-dialog').open&&document.querySelector('#invite-claude')&&!document.querySelector('#invite-claude').disabled);
    assert.equal(await page.locator('#invite-gemini').isDisabled(),true);
    assert.equal(await page.locator('.invite-row[data-agent=gemini]').getByRole('button',{name:'계정 연결'}).isVisible(),true);
    assert.equal(await page.locator('#invite-confirm').isDisabled(),true);
    await page.locator('#invite-claude').check();
    assert.equal(await page.getByLabel('Claude 권한').inputValue(),'read');
    await page.screenshot({path:path.join(out,`invite-${width}.png`),animations:'disabled'});
    const sheet=await page.locator('#invite-dialog').boundingBox();assert.ok(sheet.x>=0&&sheet.x+sheet.width<=width+1);
    await page.locator('#invite-confirm').click();
    await page.waitForFunction(()=>!document.querySelector('#invite-dialog').open);
    assert.deepEqual((await calls('chat.agents.set')).at(-1),{agents:[{id:'claude',model:'claude-opus',effort:'high',mode:'read',enabled:true}]});
    assert.equal(await page.locator('#agents-avatars .agent-avatar').count(),2);
    assert.equal(await page.locator('#prompt').getAttribute('placeholder'),'모두에게 보내기 · @로 한 명만 부르기');

    // Participants panel: tap a name for its model, reasoning and permission.
    await page.locator('#agents-toggle').click();
    assert.equal(await page.locator('#agents-panel').isVisible(),true);
    assert.equal(await page.locator('.agent-card').count(),2);
    await page.locator('.agent-card[data-agent=claude] .agent-row').click();
    assert.equal(await page.getByLabel('Claude 모델').inputValue(),'claude-opus');
    await page.getByLabel('Claude 추론').selectOption('medium');
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='chat.agents.set'&&c.args.agents[0].effort==='medium'));
    await page.getByLabel('Claude 모델').selectOption('claude-sonnet');
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='chat.agents.set'&&c.args.agents[0].model==='claude-sonnet'));
    assert.deepEqual((await calls('chat.agents.set')).at(-1),{agents:[{id:'claude',model:'claude-sonnet',effort:'medium'}]});
    await page.locator('#agents-panel').getByLabel('Claude 권한').selectOption('write');
    await page.getByLabel('Claude 승인 방식').selectOption('auto-review');
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='chat.agents.set'&&c.args.agents[0].approvalMode==='auto-review'));
    assert.deepEqual(await page.getByLabel('Claude 승인 방식').locator('option').evaluateAll(items=>items.map(i=>i.value)),['ask','auto-review','allow-all']);
    // Each agent can use a character pack, like Codex; its icon replaces the letter avatar.
    await page.getByLabel('Claude 캐릭터').selectOption('builtin');
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='chat.agents.set'&&c.args.agents[0].character==='builtin'));
    await page.waitForFunction(()=>document.querySelector('.agent-card[data-agent=claude] img.agent-avatar-image')&&document.querySelector('#agents-avatars img.agent-avatar-image[data-agent=claude]'));
    await page.locator('.agent-card[data-agent=codex] .agent-row').click();
    assert.equal(await page.getByLabel('Codex 모델').inputValue(),'review-model');
    const panel=await page.locator('#agents-panel').boundingBox();assert.ok(panel.x>=0&&panel.x+panel.width<=width+1);
    await page.screenshot({path:path.join(out,`panel-${width}.png`),animations:'disabled'});
    await page.locator('#agents-close').click();assert.equal(await page.locator('#agents-panel').isVisible(),false);

    // @ autocomplete lists the participants of this chat.
    await page.locator('#prompt').fill('');await page.locator('#prompt').pressSequentially('@c');
    await page.waitForFunction(()=>[...document.querySelectorAll('.autocomplete-item strong')].some(e=>e.textContent==='@claude'));
    await page.locator('.autocomplete-item',{hasText:'@claude'}).click();
    assert.equal(await page.locator('#prompt').inputValue(),'@claude ');

    // The first stream determines feed order, regardless of which provider finishes first.
    await page.locator('#prompt').fill('개선점을 알려 주세요.');await page.locator('#send').click();
    await page.waitForFunction(()=>[...document.querySelectorAll('.message.agent-message')].some(e=>e.textContent.includes('같은 질문에 답합니다.')));
    await page.waitForFunction(()=>!window.__agentFx.agentRun);
    assert.ok(await page.locator('.message .agent-speaker',{hasText:'Codex'}).count()>=1);
    assert.equal(await page.locator('.message.agent-message .agent-speaker',{hasText:'Claude'}).count(),1);
    const speakers=await page.locator('.message .agent-speaker').allTextContents();
    assert.ok(speakers.findIndex(s=>s.includes('Claude'))<speakers.findLastIndex(s=>s.includes('Codex')));
    assert.equal(await overflow(),false);
    await page.screenshot({path:path.join(out,`chat-${width}.png`)});

    // Removing the last agent returns the chat to Codex only.
    await page.locator('#agents-toggle').click();
    const claudeRow=page.locator('.agent-card[data-agent=claude] .agent-row');
    assert.equal(await claudeRow.getAttribute('aria-expanded'),'true','expanded rows stay open across reopening');
    await page.getByRole('button',{name:'대화에서 내보내기'}).click();
    await page.waitForFunction(()=>document.querySelectorAll('#agents-avatars .agent-avatar').length===1);
    assert.equal(await page.locator('#prompt').getAttribute('placeholder'),'Codex에게 물어보기');
    await page.locator('#agents-close').click();

    // Settings → Account keeps the Claude Code / Antigravity sign-in.
    if(width<761)await page.locator('.topbar .sidebar-toggle').click();
    await page.locator('#settings').click();await page.locator('[data-settings-tab=account]').click();
    await page.waitForFunction(()=>document.querySelector('#account-claude-status')?.textContent==='연결됨');
    // The card names the provider, so its button only says what it does.
    assert.equal(await page.locator('#account-claude-login').textContent(),'다시 로그인');
    assert.equal(await page.locator('#account-claude-login').getAttribute('aria-label'),'Claude Code 다시 로그인');
    // agy reports its usage limits with /usage; the card shows them like Codex's.
    await page.evaluate(()=>{const agy=window.__agentFx.providers.find(p=>p.id==='agy');Object.assign(agy,{state:'ready',reason:'',version:'1.3.5',models:[{id:'gemini-3.8-flash-high',label:'Gemini 3.8 Flash (High)',efforts:[]}],
      usage:[{label:'Gemini Models',limit:'Weekly Limit Remaining',remainingPercent:92,resetsAt:'2026-10-21T13:00:21Z'},{label:'Claude and GPT models',limit:'Weekly Limit Remaining',remainingPercent:100,resetsAt:'2026-10-22T02:52:21Z'}]});
      window.mobileCodexEvent('agents.providers',{providers:window.__agentFx.providers});});
    await page.waitForFunction(()=>document.querySelectorAll('#account-agy-usage .usage-gauge').length===2);
    assert.match(await page.locator('#account-agy-usage').textContent(),/Gemini Models[\s\S]*남은 92% · 주간 한도/);
    // Claude's /usage rows come as English sentences and are named in the app's language.
    await page.evaluate(()=>{const claude=window.__agentFx.providers.find(p=>p.id==='claude');claude.usage=[
      {label:'Current session',limit:'session',remainingPercent:14,resetsAt:'2026-10-15T05:50:00Z',resetText:'Oct 15, 5:50am (UTC)'},
      {label:'Current week (all models)',limit:'week',remainingPercent:60,resetsAt:'',resetText:'Oct 17, 7pm (UTC)'}];
      window.mobileCodexEvent('agents.providers',{providers:window.__agentFx.providers});});
    await page.waitForFunction(()=>document.querySelectorAll('#account-claude-usage .usage-gauge').length===2);
    const claudeUsage=await page.locator('#account-claude-usage').textContent();
    assert.match(claudeUsage,/현재 세션[\s\S]*남은 14% · 5시간 한도/);
    assert.match(claudeUsage,/이번 주 · 전체 모델재설정 Oct 17, 7pm \(UTC\)[\s\S]*남은 60% · 주간 한도/);
    await page.locator('#account-agy-usage').scrollIntoViewIfNeeded();
    await page.screenshot({path:path.join(out,`accounts-${width}.png`),animations:'disabled'});
    // A check that got no answer is not a sign-out, and only the status button skips the cached result.
    await page.locator('.provider-account[data-provider=claude] .card-heading button').click();
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='agents.providers.check'&&c.args.force===true));
    await page.evaluate(()=>{const claude=window.__agentFx.providers.find(p=>p.id==='claude');Object.assign(claude,{state:'unverified',reason:'확인하지 못했습니다.'});
      window.mobileCodexEvent('agents.providers',{providers:window.__agentFx.providers});});
    await page.waitForFunction(()=>document.querySelector('#account-claude-status')?.textContent==='상태 확인 불가');
    assert.equal(await page.locator('#account-claude-login').textContent(),'로그인');
    await page.evaluate(()=>{Object.assign(window.__agentFx.providers.find(p=>p.id==='claude'),{state:'ready',reason:''});window.mobileCodexEvent('agents.providers',{providers:window.__agentFx.providers});});
    await page.locator('#account-agy-login').click();
    await page.waitForFunction(()=>document.querySelector('#agent-setup-output'));
    assert.deepEqual((await calls('agents.provider.login')).at(-1),{provider:'agy'});
    // A terminal can wrap a long sign-in URL onto the next lines; it must still open and copy whole.
    await page.evaluate(()=>window.mobileCodexEvent('agents.setup',{provider:'agy',kind:'output',text:'Open\nhttps://accounts.google.com/o/oauth2/auth?client_id=fixture&redirect_uri=https%3A%2F%2Fantigravity.google\n&state=abc123\nThen paste the code.\n'}));
    const wrapped='https://accounts.google.com/o/oauth2/auth?client_id=fixture&redirect_uri=https%3A%2F%2Fantigravity.google&state=abc123';
    await page.getByRole('button',{name:'브라우저에서 로그인'}).click();
    assert.deepEqual((await calls('ui.providerLoginBrowser')).at(-1),{url:wrapped});
    await page.getByRole('button',{name:'주소 복사'}).click();
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='ui.copyCode'));
    assert.deepEqual((await calls('ui.copyCode')).at(-1),{code:wrapped});
    await page.screenshot({path:path.join(out,`login-${width}.png`),animations:'disabled'});
    // agy stays open after signing in: the finish button ends the session for a recheck.
    await page.getByRole('button',{name:'로그인 마침'}).click();
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='agents.provider.stop'));
    assert.deepEqual((await calls('agents.provider.stop')).at(-1),{provider:'agy'});
    await page.getByLabel('로그인 입력',{exact:true}).fill('fixture-code');await page.getByLabel('로그인 입력',{exact:true}).press('Enter');
    await page.waitForFunction(()=>window.__agentFx.calls.some(c=>c.action==='agents.provider.input'));
    assert.deepEqual((await calls('agents.provider.input')).at(-1),{provider:'agy',text:'fixture-code\n'});
    await page.evaluate(()=>window.mobileCodexEvent('agents.setup',{provider:'agy',kind:'exit',code:0}));
    await page.locator('.agent-setup-dialog').getByRole('button',{name:'닫기',exact:true}).click();
    await page.locator('[data-settings-tab=general]').click();await page.locator('#language').selectOption('en');
    await page.locator('[data-settings-tab=account]').click();
    await page.waitForFunction(()=>document.querySelector('#account-agy-login')?.textContent==='Sign in again');
    assert.equal(errors.length,0,errors.join('\n'));
    await context.close();results.push({width,passed:true});
  }}finally{await browser.close();await new Promise(r=>server.close(r));}
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
