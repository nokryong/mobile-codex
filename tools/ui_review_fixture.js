/* Same-origin, deterministic Native bridge for the UI review server. No real accounts or network. */
(() => {
  'use strict';
  if (!window.matchMedia) window.matchMedia = () => ({matches:false,addEventListener(){},removeEventListener(){}});
  if (window.HTMLDialogElement && !HTMLDialogElement.prototype.showModal) HTMLDialogElement.prototype.showModal = function() { this.setAttribute('open',''); };
  if (window.HTMLDialogElement && !HTMLDialogElement.prototype.close) HTMLDialogElement.prototype.close = function() { this.removeAttribute('open'); this.dispatchEvent(new Event('close')); };
  const query = new URLSearchParams(location.search), surface = query.get('surface') || 'main';
  const language = query.get('language') || 'ko', theme = query.get('theme') || 'light';
  const textSize = Number(query.get('textSize') || 100);
  if (query.get('gallery') === '1') {
    window.setInterval = () => 0;
    // Neighboring frames may take focus; preserve this static surface for inspection.
    document.addEventListener('focusout', event => event.stopImmediatePropagation(), true);
  }
  // Each iframe owns fixture preferences and drafts; matrix/gallery cases never share state.
  const storage = new Map([['chat-icons','off'],['theme',theme]]);
  Object.defineProperty(window, 'localStorage', {value:{getItem:key => storage.get(String(key)) ?? null, setItem:(key,value) => storage.set(String(key),String(value)), removeItem:key => storage.delete(String(key)), clear:() => storage.clear(), key:index => [...storage.keys()][index] ?? null, get length(){return storage.size;}}});
  const now = 1790670840000;
  const state = {
    ready:true, busy:false, proBusy:false, permissions:'workspace-write', status:'연결됨', directWorkspace:true, allFilesAccess:true,
    threadId:'review-thread', cwd:'/review/project', turnDiff:'src/app.js\n+  fixture review change', pendingDeletionCount:1,
    account:{type:'chatgpt',email:'review@example.test',planType:'Pro'}, accounts:[{key:'review',email:'review@example.test',planType:'Pro',active:true}],
    workspace:{selected:true,key:'review-project',name:'UI review project'},
    projects:[{key:'review-project',projectId:'review-project',name:'UI review project',available:true,selected:true},{key:'second-project',projectId:'second-project',name:'Second project',available:true}],
    sessions:[{id:'review-thread',workspaceKey:'review-project',title:'A review conversation with a deliberately long title for wrapping checks'},{id:'background',workspaceKey:'second-project',title:'Background work',busy:false}],
    models:[{id:'review-model',model:'review-model',displayName:'Review model',isDefault:true,serviceTiers:[{id:'priority',name:'Fast'}],supportedReasoningEfforts:[{reasoningEffort:'medium',description:'Balanced'}]}],
    messages:[{id:'review-user',role:'user',text:'Check the complete interface at multiple text sizes.',createdAt:now},{id:'review-answer',role:'assistant',text:'Fixture content keeps dialogs, loading states, errors, long labels, and controls visible without using an account.\n\n- Review layout\n- Verify action targets\n- Confirm every close control'}],
    rateLimits:{rateLimits:{primary:{usedPercent:37,windowDurationMins:10080,resetsAt:Math.floor((now + 86400000 * 3) / 1000)},secondary:{usedPercent:18,windowDurationMins:300,resetsAt:Math.floor((now + 3600000 * 2) / 1000)}}},
    linux:{supported:true,installed:false,enabled:false,busy:false,state:'not_installed',label:'Review Linux',downloadBytes:157000000,requiredFreeBytes:400000000,availableBytes:2500000000},
    characters:{folderConfigured:false,packs:[{id:'builtin',name:'Builtin',valid:true,icons:{}}],selectedPackId:'builtin'}
  };
  let updateState = {versionName:'0.2.1',versionCode:201,repository:'nokryong/mobile-codex',prereleases:true,status:'available',available:true,candidate:{versionName:'0.2.2',size:12345678,sha256:'a'.repeat(64)}};
  const response = (id, result = {}) => setTimeout(() => window.mobileCodexEvent?.('response',{id,result}), 0);
  const rpc = (method, params) => {
    if (method === 'account/rateLimits/read') return state.rateLimits;
    if (method === 'plugin/list') return {marketplaces:[{name:'Review marketplace',plugins:[{id:'review-plugin',name:'Review plugin',enabled:true,version:'1.0.0'}]}]};
    if (method === 'skills/list') return {data:[{name:'review-skill',description:'A fixture skill',path:'/review/SKILL.md',enabled:true}]};
    if (method === 'mcpServerStatus/list') return {data:[{name:'review-mcp',enabled:true,transport:'stdio'}]};
    return {data:[]};
  };
  window.Native = {
    locale:() => JSON.stringify({choice:language,systemLanguage:language}), textSize:() => JSON.stringify({percent:[75,85,100,115,130,150].includes(textSize) ? textSize : 100}),
    postMessage(raw) {
      const message = JSON.parse(raw); window.__uiReviewCalls.push(message);
      try {
        let result = {};
        if (message.action === 'state') result = state;
        else if (message.action === 'rpc') {
          result = rpc(message.args.method, message.args.params);
        }
        else if (message.action === 'voice.recover') result = {active:surface === 'dictation',receipts:[]};
      else if (message.action === 'notifications.state') result = {enabled:true,vibration:true,permission:true};
        else if (message.action === 'linux.status') result = state.linux;
        else if (message.action === 'characters.list') result = state.characters;
        else if (message.action === 'instructions.read') result = {content:'Review only the relevant UI and preserve user work.',activePath:'/review/AGENTS.md'};
        else if (message.action === 'updates.state') result = updateState;
        else if (message.action === 'files.list') result = {entries:[{name:'README.md',path:'README.md',type:'file',size:2048},{name:'src',path:'src',type:'directory'}]};
        else if (message.action === 'recovery.list') result = {entries:[{id:'review-copy',path:'src/app.js',timestamp:now}]};
        else if (message.action === 'changes.list') result = {changes:[{id:'review-change',path:'src/app.js',summary:'Fixture change',canRestore:true}]};
        else if (message.action === 'auth.add') result = {loginId:'review-login',verificationUrl:'https://auth.openai.com/codex/device',userCode:'REVIEW-CODE'};
        else if (message.action === 'updates.check') { updateState = {...updateState,status:'available'}; result = updateState; }
        response(message.id, result);
      } catch (error) {
        setTimeout(() => window.mobileCodexEvent?.('response',{id:message.id,error:String(error?.message || error)}), 0);
      }
    }
  };
  window.__uiReviewCalls = [];
  function closeAll() { document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close()); }
  function show(id) { const dialog = document.getElementById(id); if (!dialog) throw Error('Unknown review dialog: ' + id); closeAll(); if (!dialog.open) dialog.showModal(); return dialog; }
  function fill(id, html) { const target = document.getElementById(id); if (target) target.innerHTML = html; }
  function click(selector) { const target = document.querySelector(selector); if (target) target.click(); }
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
  function applyTextZoomProxy() {
    // Desktop Chrome has no Android WebSettings.textZoom. Freeze each rendered
    // text metric and scale glyphs only; this deliberately does not page-zoom.
    if (textSize === 100 || window.__uiReviewTextZoomApplied) return;
    window.__uiReviewTextZoomApplied=true;
    const metrics=[...document.querySelectorAll('body, body *')]
      .filter(element => !element.closest('svg'))
      .map(element => [element,parseFloat(getComputedStyle(element).fontSize)]);
    for (const [element,size] of metrics) if (Number.isFinite(size) && size > 0) element.style.fontSize=(size * textSize / 100)+'px';
    document.documentElement.dataset.uiReviewTextZoom=String(textSize);
    window.dispatchEvent(new Event('resize'));
  }
  function prepareDialog(id) {
    if (id === 'file-actions-dialog') fill('file-actions','<button class="secondary-button">이름 변경</button><button class="secondary-button">다른 위치에 저장</button>');
    if (id === 'session-actions-dialog') fill('session-actions','<button class="secondary-button">이름 변경</button><button class="secondary-button subtle-danger">대화 삭제</button>');
    if (id === 'project-actions-dialog') fill('project-actions','<button class="secondary-button">프로젝트 내보내기</button><button class="secondary-button">기본 폴더</button>');
    if (id === 'project-link-dialog') { const title=document.getElementById('project-link-title'); if(title)title.textContent='프로젝트 연결'; const description=document.getElementById('project-link-description'); if(description)description.textContent='Fixture project choices'; fill('project-link-choices','<button class="secondary-button">로컬 폴더 연결</button><button class="secondary-button">프로젝트 가져오기</button>'); }
    if (id === 'login-dialog') { document.getElementById('device-code').textContent='REVIEW-CODE'; document.getElementById('open-login').disabled=false; }
    if (id === 'editor-dialog') { document.getElementById('editor-title').textContent='README.md'; document.getElementById('editor').value='# Review\nA fixture file.'; }
    if (id === 'input-dialog') { document.getElementById('input-title').textContent='Fixture input'; document.getElementById('input-description').textContent='Use this field to inspect form spacing.'; }
    if (id === 'changes-dialog') { document.getElementById('changes-status').textContent='1개 변경 사항'; fill('changes-list','<button class="file-row">src/app.js <small>수정됨</small></button>'); }
    if (id === 'recovery-dialog') fill('recovery-list','<div class="recovery-item"><div><strong>src/app.js</strong><small>Fixture recovery copy</small></div><button>비교 / 복원</button></div>');
    if (id === 'terminal-dialog') { document.getElementById('terminal-output').textContent='$ npm test\nFixture terminal output'; }
    if (id === 'tools-dialog') fill('tools-list','<section class="tool-card"><strong>Review plugin</strong><p>Loading and error states can be inspected here.</p><button class="secondary-button">상세</button></section>');
    if (id === 'config-dialog') document.getElementById('config-editor').value='model = "review"';
    if (id === 'activity-dialog') fill('event-log','<article class="activity-event"><strong>Fixture event</strong><pre>Meaningful review detail</pre></article>');
    if (id === 'request-dialog') { document.getElementById('request-title').textContent='Fixture approval'; document.getElementById('request-reason').textContent='Review approval spacing.'; fill('request-fields','<label>Reason <input class="text-input" value="Fixture value"></label>'); fill('request-actions','<button class="secondary-button" data-review-close>취소</button><button class="primary-button">허용</button>'); document.querySelector('#request-actions [data-review-close]')?.addEventListener('click',() => document.getElementById('request-dialog').close()); }
    if (id === 'image-dialog') document.getElementById('image-preview').src='data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="640" height="420"%3E%3Crect width="100%25" height="100%25" fill="%23ddd"/%3E%3Ctext x="30" y="80" font-size="32"%3EFixture image%3C/text%3E%3C/svg%3E';
    show(id);
  }
  async function showSurface(name = 'main') {
    await ready(); closeAll();
    if (name === 'main') return;
    if (name === 'welcome') { window.mobileCodexEvent('state', {...state,messages:[]}); return; }
    if (name === 'files') { click('#files-toggle'); await pause(35); return; }
    if (name === 'options') return click('#composer-options');
    if (name === 'tool-menu') return click('#show-tools');
    if (name === 'composer-expanded') {
      const prompt = document.getElementById('prompt'); prompt.value = 'A deliberately long fixture prompt that expands the composer across multiple lines for review.';
      prompt.dispatchEvent(new Event('input', {bubbles:true})); prompt.focus(); return;
    }
    if (name === 'dictation') { window.mobileCodexEvent('voice.state',{phase:'listening',partial:'Fixture dictation in progress',level:.7,elapsedMs:4200}); return; }
    if (name === 'autocomplete') {
      const prompt = document.getElementById('prompt'); prompt.value='@'; prompt.selectionStart=prompt.selectionEnd=1;
      prompt.dispatchEvent(new Event('input', {bubbles:true})); prompt.focus(); await pause(35); return;
    }
    if (name === 'images') {
      window.mobileCodexEvent('state', {...state,messages:[...state.messages,{id:'review-image',role:'assistant',text:'Fixture image result',createdAt:now,imageStatus:'generating',imageError:'Fixture image generation failed'}]}); return;
    }
    if (name === 'loading') { window.mobileCodexEvent('state', {...state,busy:true,messages:[...state.messages,{id:'review-loading',role:'assistant',text:'',createdAt:now,imageStatus:'generating'}]}); return; }
    if (name === 'error') { window.mobileCodexEvent('error',{threadId:state.threadId,message:'Fixture operation failed. Review this inline feedback.'}); return; }
    if (name === 'login') return prepareDialog('login-dialog');
    if (name.startsWith('settings-')) { click('#settings'); const tab=name.slice('settings-'.length); document.querySelectorAll('[data-settings-tab]').forEach(button => { if (button.dataset.settingsTab === tab) button.click(); }); await pause(20); return; }
    const id = name.endsWith('-dialog') ? name : name + '-dialog'; prepareDialog(id);
  }
  function geometry() {
    const rect = element => { const r=element.getBoundingClientRect(); return {id:element.id,top:r.top,left:r.left,right:r.right,bottom:r.bottom,width:r.width,height:r.height}; };
    const visible = element => { const style=getComputedStyle(element), r=element.getBoundingClientRect(); return !element.hidden && style.display !== 'none' && style.visibility !== 'hidden' && r.width > 0 && r.height > 0; };
    const prompt=document.getElementById('prompt');
    const clippedPrompt=prompt && getComputedStyle(prompt).overflowY === 'hidden' && prompt.scrollHeight > prompt.clientHeight + 1;
    return {clippedPrompt,composerExpanded:document.getElementById('composer')?.classList.contains('composer-expanded'),dictationVisible:!document.getElementById('dictation')?.hidden,ready:window.__uiReviewReady === true,viewport:{width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight},dialogs:[...document.querySelectorAll('dialog[open]')].map(rect),controls:[...document.querySelectorAll('button,input,select')].filter(visible).map(rect)};
  }
  let resolveReady; const readyPromise = new Promise(resolve => { resolveReady = resolve; });
  function ready() { return readyPromise; }
  window.UiReview = {state, surfaces:[], showSurface, geometry, ready, closeAll};
  document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('ui-review-controls')) {
      const inventory = JSON.parse(document.body.dataset.inventory || '{"surfaces":[]}');
      const form = document.getElementById('ui-review-controls'), surfaceSelect=form.elements.surface, frame=document.getElementById('ui-review-frame');
      for (const name of inventory.surfaces || []) surfaceSelect.add(new Option(name,name));
      const report=document.getElementById('ui-review-report'), run=document.getElementById('ui-review-run');
      const viewportFor = () => { const [width,height] = String(form.elements.viewport.value || '393x852').split('x').map(Number); return {width:width || 393,height:height || 852}; };
      const animationFrames = child => new Promise(resolve => child.requestAnimationFrame(() => child.requestAnimationFrame(resolve)));
      let navigation=0;
      const loadFrame = () => new Promise((resolve,reject) => {
        const data=new FormData(form), viewport=viewportFor(), query='?surface='+encodeURIComponent(data.get('surface'))+'&theme='+encodeURIComponent(data.get('theme'))+'&language='+encodeURIComponent(data.get('language'))+'&textSize='+encodeURIComponent(data.get('textSize'));
        const generation=++navigation, until=Date.now()+7000;
        let loaded=false, settling=false;
        const finish=async () => {
          if (!loaded || settling || generation !== navigation) return;
          const child=frame.contentWindow;
          let expected=false;
          try { expected=child?.location?.pathname === '/app' && child.location.search === query; } catch {}
          if (!expected || !child?.__uiReviewReady || !child.__uiReviewSurfaceReady) return;
          settling=true;
          try {
            await child.document.fonts?.ready;
            await Promise.all(child.document.getAnimations().filter(a => Number.isFinite(a.effect?.getComputedTiming().endTime)).map(a => a.finished.catch(() => {})));
            await animationFrames(child);
            // Manual changes retain the visible 150ms width transition; the batch
            // disables it below so its geometry is measured only after a stable frame.
            if (frame.style.transition !== 'none') await pause(180);
            if (generation === navigation) { clearInterval(timer); resolve(child); }
          } catch (error) { clearInterval(timer); reject(error); }
        };
        const onLoad=() => { loaded=true; finish(); };
        frame.addEventListener('load',onLoad,{once:true});
        frame.style.width=viewport.width+'px'; frame.style.height=viewport.height+'px'; frame.src='/app'+query;
        const timer=setInterval(() => {
          if (generation !== navigation) { clearInterval(timer); return reject(Error('fixture navigation replaced')); }
          const child=frame.contentWindow;
          if (Date.now() > until) { clearInterval(timer); return reject(Error(child?.__uiReviewError || 'fixture readiness timeout')); }
          finish();
        },25);
      });
      const render = () => loadFrame();
      const geometryProblems = child => { const data=child.UiReview.geometry(), problems=[]; if (!data.ready) problems.push('not ready'); if (data.clippedPrompt) problems.push('prompt text clipped'); if (data.dictationVisible && !data.composerExpanded) problems.push('dictation in compact composer'); if (data.viewport.scrollWidth > data.viewport.width + 1) problems.push('document horizontal overflow'); for (const dialog of data.dialogs) { if (dialog.left < -1 || dialog.right > data.viewport.width + 1 || dialog.top < -1 || dialog.bottom > data.viewport.height + 1) problems.push(dialog.id+' overflow '+JSON.stringify(dialog)); const node=child.document.getElementById(dialog.id); if (!node.querySelector('[data-close],[data-review-close]')) problems.push(dialog.id+' missing closer'); } for (const control of data.controls.filter(control => ['new-chat','add-attachment','send','voice-input','composer-options','settings','show-tools','account-button'].includes(control.id))) if (control.width < 43.5 || control.height < 43.5) problems.push(control.id+' under 44px'); return problems; };
      const persistReport = async results => {
        const response=await fetch('/report',{method:'POST',headers:{'content-type':'application/json','x-ui-review-fixture':'1'},body:JSON.stringify({version:1,generatedAt:new Date().toISOString(),textZoomProxy:true,results})});
        if (!response.ok) throw Error('report save failed ('+response.status+')');
        return response.json();
      };
      run.addEventListener('click', async () => {
        const lines=[], results=[], viewports=['320x568','393x852','800x1100','1280x900'], themes=['light','dark'], sizes=['100','150'];
        run.disabled=true; report.textContent='검사 시작…'; let pass=0, fail=0, reportNote=''; const previousTransition=frame.style.transition; frame.style.transition='none';
        try {
          for (const name of inventory.surfaces || []) for (const viewport of viewports) for (const reviewTheme of themes) for (const size of sizes) {
            form.elements.surface.value=name; form.elements.viewport.value=viewport; form.elements.theme.value=reviewTheme; form.elements.textSize.value=size;
            let problems=[];
            try { const child=await render(); problems=geometryProblems(child); } catch (error) { problems=[error.message]; }
            const passed=problems.length === 0, result={surface:name,viewport,theme:reviewTheme,textSize:Number(size),passed,problems}; results.push(result);
            if (passed) pass++; else { fail++; lines.push('FAIL '+name+' '+viewport+' '+reviewTheme+' '+size+'%: '+problems.join(', ')); }
            if ((pass+fail)%12===0) report.textContent='검사 중 '+(pass+fail)+'건 (Android textZoom proxy)\n'+lines.slice(-8).join('\n');
          }
          try { const saved=await persistReport(results); reportNote=' · 리포트 '+saved.results+'건 저장'; } catch (error) { reportNote=' · 리포트 저장 실패: '+error.message; }
        } finally {
          frame.style.transition=previousTransition; run.disabled=false;
          report.textContent='완료: '+pass+' 통과, '+fail+' 실패'+reportNote+'\nAndroid textZoom proxy 사용'+(lines.length?'\n'+lines.join('\n'):'\n문제 없음');
        }
      });
      form.addEventListener('change',render); render(); return;
    }
    if (window.UiCore) {
      const assetPrefix = location.pathname === '/before' ? '/before-assets/' : '/assets/';
      const names = window.UiCore.chatIconNames();
      window.UiCore.chatIconUrl = name => { const index=names.indexOf(name); return index < 0 ? null : assetPrefix+'chat-icons/'+String(index+1).padStart(2,'0')+'-'+name+'.png'; };
    }
    document.documentElement.dataset.theme=theme; localStorage.setItem('theme',theme); localStorage.setItem('chat-icons','off');
    setTimeout(() => { window.__uiReviewReady=true; window.UiReview.surfaces=[...document.querySelectorAll('dialog')].map(dialog=>dialog.id); resolveReady(); showSurface(surface).then(() => { applyTextZoomProxy(); window.__uiReviewSurfaceReady=true; }).catch(error => { window.__uiReviewError=String(error?.message || error); }); }, 30);
  });
})();
