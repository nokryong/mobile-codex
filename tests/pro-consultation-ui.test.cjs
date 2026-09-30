const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');

const webRoot = path.join(__dirname, '../app/src/main/assets/web');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(resolve => setTimeout(resolve, 10));
const codexModel = {
  id:'codex-model', model:'codex-model', displayName:'Codex model', isDefault:true,
  supportedReasoningEfforts:[{reasoningEffort:'medium'}, {reasoningEffort:'high'}],
  serviceTiers:[{id:'priority', name:'Fast'}]
};
const profile = (key, email = 'me@example.test') => ({
  account:{type:'chatgpt', email}, accounts:[{key, email, active:true}]
});

function setup(overrides = {}, options = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8'), {
    url:'https://appassets.androidplatform.net/index.html', runScripts:'outside-only'
  });
  opened.push(dom);
  const w = dom.window, calls = [];
  const snapshot = {
    ready:true, busy:false, proBusy:false, permissions:'workspace-write', approvalMode:'allow-all',
    status:'연결됨', threadId:'thread-a', cwd:'/test/project',
    workspace:{selected:true, key:'project-a', name:'Project A', available:true},
    models:[codexModel], messages:[], sessions:[], ...profile('profile-a'), ...options.snapshot
  };
  w.matchMedia = () => ({matches:false, addEventListener(){}});
  w.HTMLDialogElement.prototype.showModal = function(){this.setAttribute('open', '');};
  w.HTMLDialogElement.prototype.close = function(){this.removeAttribute('open');this.dispatchEvent(new w.Event('close'));};
  w.confirm = () => true;
  for (const [key, value] of Object.entries(options.storage || {})) w.localStorage.setItem(key, value);
  w.Native = {
    locale:() => JSON.stringify({choice:'ko', systemLanguage:'ko'}),
    textSize:() => JSON.stringify({percent:100}),
    postMessage(raw) {
      const message = JSON.parse(raw); calls.push(message);
      queueMicrotask(async () => {
        try {
          const result = overrides[message.action] ? await overrides[message.action](message)
            : message.action === 'state' ? snapshot
              : message.action === 'rpc' ? {data:[], marketplaces:[]} : {};
          w.mobileCodexEvent('response', {id:message.id, result});
        } catch (error) { w.mobileCodexEvent('response', {id:message.id, error:error.message}); }
      });
    }
  };
  for (const file of ['translations.js', 'locale.js', 'ui-core.js', 'app.js'])
    w.eval(fs.readFileSync(path.join(webRoot, file), 'utf8'));
  return {w, calls, snapshot};
}

function type(w, text) {
  const field = w.document.getElementById('prompt');
  field.value = text; field.dispatchEvent(new w.Event('input')); return field;
}
function submit(w) {
  w.document.getElementById('composer').dispatchEvent(new w.Event('submit', {cancelable:true}));
}
function key(w, name) {
  w.document.activeElement.dispatchEvent(new w.KeyboardEvent('keydown', {key:name, bubbles:true, cancelable:true}));
}
function selectConsultation(w) {
  w.document.getElementById('add-attachment').click();
  w.document.getElementById('composer-consult-pro').click();
}
function chip(w) { return w.document.querySelector('.pro-consultation-chip'); }
function chatCalls(calls) { return calls.filter(call => /^chat\.(send|steer|pro\.)/.test(call.action)); }
function storageOf(w) { return Object.fromEntries(Object.keys(w.localStorage).map(key => [key, w.localStorage.getItem(key)])); }

test('the plus menu supports keyboard selection and Escape without sending or picking a file', async () => {
  const {w, calls} = setup(); await tick();
  const d = w.document, trigger = d.getElementById('add-attachment'), menu = d.getElementById('composer-add-menu');
  assert.equal(menu.getAttribute('role'), 'menu');
  assert.equal(menu.hidden, true); assert.equal(trigger.getAttribute('aria-expanded'), 'false');
  trigger.focus(); trigger.click();
  assert.equal(menu.hidden, false); assert.equal(trigger.getAttribute('aria-expanded'), 'true');
  assert.equal(d.activeElement, d.getElementById('composer-attach'));
  key(w, 'ArrowDown'); assert.equal(d.activeElement, d.getElementById('composer-consult-pro'));
  key(w, 'Enter'); await tick();
  assert.match(chip(w).textContent, /Pro자문/); assert.equal(menu.hidden, true);
  assert.equal(chatCalls(calls).length, 0); assert.equal(calls.some(call => call.action === 'attachments.pick'), false);
  trigger.focus(); trigger.click(); key(w, 'Escape');
  assert.equal(menu.hidden, true); assert.equal(d.activeElement, trigger);
  assert.equal(trigger.getAttribute('aria-expanded'), 'false');
});

test('file attachment remains a separate plus-menu action', async () => {
  const {w, calls} = setup({'attachments.pick':() => ({receiptId:'file-receipt', attachments:[{id:'file', name:'notes.txt'}]})});
  await tick(); const d = w.document;
  d.getElementById('add-attachment').click(); await tick();
  assert.equal(calls.some(call => call.action === 'attachments.pick'), false, 'Opening the menu never starts the native picker');
  d.getElementById('composer-attach').click(); await tick();
  assert.equal(calls.filter(call => call.action === 'attachments.pick').length, 1);
  assert.match(d.getElementById('draft-context').textContent, /notes\.txt/);
  assert.equal(chip(w), null); assert.equal(chatCalls(calls).length, 0);
});

test('consultation selection preserves Codex model, reasoning, Fast and native permissions', async () => {
  const {w, calls, snapshot} = setup(); await tick(); const d = w.document;
  w.mobileCodexEvent('state', {...snapshot, permissions:'danger-full-access', approvalMode:'allow-all'});
  d.getElementById('model').value = 'codex-model'; d.getElementById('model').dispatchEvent(new w.Event('change'));
  d.getElementById('effort').value = 'high'; d.getElementById('effort').dispatchEvent(new w.Event('change'));
  d.getElementById('fast-mode').click();
  const baseline = calls.length;
  selectConsultation(w); await tick();
  assert.equal(d.getElementById('model').value, 'codex-model'); assert.equal(d.getElementById('effort').value, 'high');
  assert.equal(d.getElementById('fast-mode').getAttribute('aria-pressed'), 'true');
  assert.equal(d.getElementById('permissions').value, 'danger-full-access');
  assert.equal(d.getElementById('permissions').disabled, false); assert.equal(d.getElementById('effort').disabled, false);
  assert.equal(calls.slice(baseline).some(call => /^(chat\.|permissions\.|approvals\.|config\.)/.test(call.action)), false);
  chip(w).querySelector('button').click(); await tick(); assert.equal(chip(w), null);
  assert.equal(d.getElementById('fast-mode').getAttribute('aria-pressed'), 'true');
});

test('a selected consultation sends exactly one flagged Codex request and consumes its flag on success', async () => {
  let finish;
  const {w, calls} = setup({'chat.send':() => new Promise(resolve => {finish = resolve;})}); await tick(); const d = w.document;
  d.getElementById('model').value = 'codex-model'; d.getElementById('model').dispatchEvent(new w.Event('change'));
  d.getElementById('effort').value = 'high'; d.getElementById('effort').dispatchEvent(new w.Event('change'));
  d.getElementById('fast-mode').click();
  selectConsultation(w); type(w, '이 변경을 Pro 검토 후 적용해 줘'); submit(w); await tick(); submit(w); await tick();
  const sent = chatCalls(calls); assert.equal(sent.length, 1); assert.equal(sent[0].action, 'chat.send');
  assert.equal(sent[0].args.consultPro, true); assert.equal(sent[0].args.model, 'codex-model');
  assert.equal(sent[0].args.effort, 'high'); assert.equal(sent[0].args.fastMode, true);
  assert.equal(sent[0].args.expectedThreadId, 'thread-a'); assert.equal(sent[0].args.workspaceKey, 'project-a');
  d.getElementById('add-attachment').click();
  assert.equal(d.getElementById('composer-consult-pro').disabled, true, 'Selection stays stable during the submitted request');
  finish({}); await tick();
  assert.equal(d.getElementById('prompt').value, ''); assert.equal(chip(w), null);
  type(w, '다음 일반 요청'); submit(w); await tick();
  assert.equal(chatCalls(calls).at(-1).args.consultPro, false);
  finish({}); await tick();
  assert.equal(calls.some(call => call.action === 'chat.pro.send'), false);
});

test('consultation can be selected while Codex works and follows the exact steering target', async () => {
  const {w, calls, snapshot} = setup(); await tick();
  w.mobileCodexEvent('state', {...snapshot, busy:true, turnId:'running-turn'});
  selectConsultation(w); type(w, 'Pro에게 설계를 검토받고 계속해');
  assert.equal(w.document.getElementById('send').disabled, false);
  submit(w); await tick();
  const sent = chatCalls(calls); assert.equal(sent.length, 1); assert.equal(sent[0].action, 'chat.steer');
  assert.equal(sent[0].args.consultPro, true); assert.equal(sent[0].args.expectedTurnId, 'running-turn');
  assert.equal(sent[0].args.expectedThreadId, 'thread-a'); assert.equal(sent[0].args.workspaceKey, 'project-a');
});

test('active Pro work blocks another consultation but a pending chip can be removed for plain steering', async () => {
  const {w, calls, snapshot} = setup(); await tick(); selectConsultation(w); type(w, '이 추가 지시를 계속해');
  w.mobileCodexEvent('state', {...snapshot, busy:true, proBusy:true, turnId:'running-turn'});
  const d = w.document;
  d.getElementById('add-attachment').click();
  assert.equal(d.getElementById('composer-consult-pro').disabled, true);
  d.getElementById('composer-consult-pro').click();
  assert.equal(chatCalls(calls).length, 0);
  assert.equal(d.getElementById('send').disabled, true, 'The flagged draft cannot submit a duplicate consultation');
  const remove = chip(w).querySelector('button'); assert.equal(remove.disabled, false); remove.click(); await tick();
  assert.equal(chip(w), null); assert.equal(d.getElementById('send').disabled, false);
  submit(w); await tick();
  const sent = chatCalls(calls); assert.equal(sent.length, 1); assert.equal(sent[0].action, 'chat.steer');
  assert.equal(sent[0].args.consultPro, false); assert.equal(sent[0].args.expectedTurnId, 'running-turn');
});

test('an unavailable consultation blocks selection and flagged sends while preserving removable drafts and plain requests', async () => {
  for (const pendingConsultation of [false, true]) {
    const {w, calls, snapshot} = setup(); await tick();
    if (pendingConsultation) selectConsultation(w);
    w.mobileCodexEvent('state', {...snapshot, consultProAvailable:false});
    const d = w.document; type(w, '이 일반 요청은 계속할 수 있어야 해');
    d.getElementById('add-attachment').click();
    assert.equal(d.getElementById('composer-consult-pro').disabled, true);
    d.getElementById('composer-consult-pro').click();
    assert.equal(!!chip(w), pendingConsultation); assert.equal(chatCalls(calls).length, 0);
    if (pendingConsultation) {
      assert.equal(d.getElementById('send').disabled, true);
      submit(w); await tick(); assert.equal(chatCalls(calls).length, 0, 'Keyboard submission cannot bypass consultation availability');
      const remove = chip(w).querySelector('button'); assert.equal(remove.disabled, false); remove.click();
    }
    assert.equal(chip(w), null); assert.equal(d.getElementById('send').disabled, false);
    submit(w); await tick();
    const sent = chatCalls(calls); assert.equal(sent.length, 1); assert.equal(sent[0].action, 'chat.send');
    assert.equal(sent[0].args.consultPro, false);
  }
});

test('failed sends and a reload retain both consultation intent and the draft for retry', async () => {
  const {w, calls, snapshot} = setup({'chat.send':() => {throw new Error('offline');}}); await tick();
  selectConsultation(w); type(w, '작성하던 검토 요청'); submit(w); await tick();
  assert.equal(w.document.getElementById('prompt').value, '작성하던 검토 요청');
  assert.ok(chip(w)); assert.match(w.document.getElementById('toast').textContent, /offline/);
  assert.equal(chatCalls(calls)[0].args.consultPro, true);
  const restored = setup({}, {snapshot, storage:storageOf(w)}); await tick();
  assert.equal(restored.w.document.getElementById('prompt').value, '작성하던 검토 요청'); assert.ok(chip(restored.w));
  submit(restored.w); await tick(); assert.equal(chatCalls(restored.calls)[0].args.consultPro, true);
  assert.equal(chip(restored.w), null);
});

test('consultation drafts are isolated by workspace, thread and stable account identity', async () => {
  const {w, snapshot} = setup(); await tick(); selectConsultation(w); type(w, 'A 검토 초안');
  const otherProject = {...snapshot, workspace:{...snapshot.workspace, key:'project-b', name:'Project B'}, cwd:'/test/other'};
  w.mobileCodexEvent('state', otherProject); assert.equal(chip(w), null);
  w.mobileCodexEvent('state', {...snapshot, threadId:'thread-b'}); assert.equal(chip(w), null);
  w.mobileCodexEvent('state', {...snapshot, ...profile('profile-b')}); assert.equal(chip(w), null, 'Same-email saved accounts remain distinct');
  selectConsultation(w); chip(w).querySelector('button').click();
  w.mobileCodexEvent('state', snapshot); assert.ok(chip(w));
  w.mobileCodexEvent('state', {...snapshot, account:{...snapshot.account, planType:'Pro'}});
  assert.ok(chip(w), 'Refreshing account metadata does not change the saved-profile identity');
  assert.equal(w.document.getElementById('prompt').value, 'A 검토 초안');
});

test('success consumes the submitted consultation while preserving text typed during the request', async () => {
  let finish;
  const {w, calls} = setup({'chat.send':() => new Promise(resolve => {finish = resolve;})}); await tick();
  selectConsultation(w); type(w, '보낼 검토 요청'); submit(w); await tick();
  type(w, '다음 요청 작성 중'); finish({}); await tick();
  assert.equal(w.document.getElementById('prompt').value, '다음 요청 작성 중'); assert.equal(chip(w), null);
  submit(w); await tick(); assert.equal(chatCalls(calls).at(-1).args.consultPro, false); finish({}); await tick();
});

test('a first send transfers consultation intent into the created thread and consumes both draft scopes', async () => {
  let finish;
  const {w, snapshot} = setup({'chat.send':() => new Promise(resolve => {finish = resolve;})}, {snapshot:{threadId:''}});
  await tick(); selectConsultation(w); type(w, '처음 보낼 자문 요청'); submit(w); await tick();
  const created = {...snapshot, threadId:'created-thread', busy:true, turnId:'first-turn'};
  w.mobileCodexEvent('state', created);
  assert.ok(chip(w), 'The request-created thread retains the pending consultation until acceptance');
  finish({}); await tick();
  assert.equal(chip(w), null); assert.equal(w.document.getElementById('prompt').value, '');
  w.mobileCodexEvent('state', snapshot);
  assert.equal(chip(w), null, 'The original new-thread draft cannot restore the submitted consultation');
  assert.equal(w.document.getElementById('prompt').value, '');
  const reopened = setup({}, {snapshot:{...created, busy:false}, storage:storageOf(w)}); await tick();
  assert.equal(chip(reopened.w), null, 'Reloading the created thread cannot restore the submitted consultation');
  assert.equal(reopened.w.document.getElementById('prompt').value, '');
});

test('a removed project migrates consultation intent into the general draft only for the same account', async () => {
  for (const changedAccount of [false, true]) {
    const {w, snapshot} = setup(); await tick(); selectConsultation(w); type(w, '삭제된 프로젝트의 자문 초안');
    const general = {
      ...snapshot, workspace:{selected:false, key:'', name:'일반'}, cwd:'/test/general',
      ...(changedAccount ? profile('profile-b') : {})
    };
    w.mobileCodexEvent('state', general);
    assert.equal(!!chip(w), !changedAccount, 'Consultation intent migrates within its originating account only');
    if (!changedAccount) {
      assert.equal(w.document.getElementById('prompt').value, '삭제된 프로젝트의 자문 초안');
      const restored = setup({}, {snapshot:general, storage:storageOf(w)}); await tick();
      assert.ok(chip(restored.w), 'Migration persists the consultation flag in the general draft');
    }
    w.mobileCodexEvent('state', snapshot);
    assert.ok(chip(w), 'The original project draft retains its own consultation intent');
  }
});

test('project removal preserves an existing plain general draft without attaching unrelated consultation intent', async () => {
  const generalScope = 'draft:' + JSON.stringify(['', 'thread-a']);
  const {w, snapshot} = setup({}, {storage:{[generalScope]:'이미 작성하던 일반 요청'}}); await tick();
  selectConsultation(w); type(w, '프로젝트에서만 검토받을 요청');
  w.mobileCodexEvent('state', {...snapshot, workspace:{selected:false, key:'', name:'일반'}, cwd:'/test/general'});
  assert.equal(w.document.getElementById('prompt').value, '이미 작성하던 일반 요청');
  assert.equal(chip(w), null, 'An unrelated retained draft must not acquire authorization for a Pro consultation');
  w.mobileCodexEvent('state', snapshot);
  assert.ok(chip(w)); assert.equal(w.document.getElementById('prompt').value, '프로젝트에서만 검토받을 요청');
});

test('a late send acknowledgement clears only the originating account consultation', async () => {
  let finish;
  const {w, snapshot} = setup({'chat.send':() => new Promise(resolve => {finish = resolve;})}); await tick();
  const otherAccount = {...snapshot, ...profile('profile-b')};
  w.mobileCodexEvent('state', otherAccount); selectConsultation(w);
  w.mobileCodexEvent('state', snapshot); selectConsultation(w); type(w, 'A에게 보낼 검토 요청'); submit(w); await tick();
  w.mobileCodexEvent('state', otherAccount); assert.ok(chip(w));
  finish({}); await tick(); assert.ok(chip(w), 'Account B did not submit its consultation');
  w.mobileCodexEvent('state', snapshot); assert.equal(chip(w), null, 'Account A acknowledgement consumes only its submitted flag');
});

test('legacy synthetic model preferences fall back to the runtime default and never appear in the picker', async () => {
  const scope = 'draft:' + JSON.stringify(['project-a', 'thread-a']);
  const {w, calls} = setup({}, {storage:{[scope]:'기존 초안', [scope + ':options']:JSON.stringify({model:'chatgpt-web:gpt-6-pro', effort:'pro-auto', fastMode:true})}});
  await tick(); const d = w.document;
  assert.equal(d.getElementById('model').value, ''); assert.equal(d.getElementById('effort').disabled, false);
  assert.equal(d.querySelector('#model-list input[value="chatgpt-web:gpt-6-pro"]'), null);
  assert.equal([...d.getElementById('model').options].some(option => option.value === 'chatgpt-web:gpt-6-pro'), false);
  assert.equal(chip(w), null, 'Legacy model selection does not silently authorize a consultation');
  submit(w); await tick(); const sent = chatCalls(calls)[0];
  assert.equal(sent.action, 'chat.send'); assert.equal(sent.args.model, ''); assert.equal(sent.args.consultPro, false);
});

test('consultation answers have a safe reply card, optional prompt details and reply-only copy', async () => {
  const copied = [];
  const {w, snapshot} = setup(); await tick();
  Object.defineProperty(w.navigator, 'clipboard', {value:{writeText:async text => {copied.push(text);}}});
  const answer = {
    id:'consult', kind:'proConsultation', role:'assistant', status:'completed',
    text:'이 구현의 경계를 확인했습니다. <img src=x onerror=alert(1)>', prompt:'비공개 검토 질문 <script>window.hacked=true</script>', createdAt:Date.UTC(2026,8,29,12,34)
  };
  w.mobileCodexEvent('state', {...snapshot, messages:[answer]});
  const d = w.document, card = d.querySelector('[data-id="consult"]');
  assert.match(card.textContent, /Pro 검토 답변/); assert.match(card.textContent, /구현의 경계/);
  assert.equal(card.querySelectorAll('script, img:not(.chat-character)').length, 0);
  const details = card.querySelector('details'); assert.ok(details); assert.equal(details.open, false);
  assert.match(details.textContent, /비공개 검토 질문/);
  card.querySelector('.msg-copy-btn').click(); await tick(); assert.deepEqual(copied, [answer.text]);
  w.mobileCodexEvent('state', {...snapshot, messages:[{...answer, prompt:''}]});
  assert.equal(d.querySelector('[data-id="consult"]'), card, 'The same message keeps its identity');
  assert.equal(card.querySelector('details'), null, 'Removing prompt metadata removes stale details');
});

test('legacy Pro labels remain readable beside the new consultation answer card', async () => {
  const {w, snapshot} = setup(); await tick();
  w.mobileCodexEvent('state', {...snapshot, messages:[
    {id:'legacy-user', role:'user', text:'기존 검토', backend:'chatgpt-web', source:'ChatGPT Pro', displayModel:'GPT-6-Pro', status:'uncertain'},
    {id:'legacy-answer', role:'assistant', text:'기존 답변', backend:'chatgpt-web', source:'ChatGPT Pro', status:'completed'},
    {id:'new-answer', kind:'proConsultation', role:'assistant', text:'새 검토 답변', status:'completed'}
  ]});
  const d = w.document;
  assert.match(d.querySelector('[data-id="legacy-user"]').textContent, /GPT-6-Pro/);
  assert.match(d.querySelector('[data-id="legacy-user"]').textContent, /다시 보내기 전에/);
  assert.match(d.querySelector('[data-id="legacy-answer"]').textContent, /ChatGPT Pro/);
  assert.match(d.querySelector('[data-id="new-answer"]').textContent, /Pro 검토 답변/);
});

test('an unavailable consultation explains the native reason in the add menu', async () => {
  const {w, snapshot} = setup(); await tick();
  const d = w.document, reason = 'Pro 문의 MCP 서버(mobile_codex_pro)가 시작되지 않았습니다.';
  w.mobileCodexEvent('state', {...snapshot, consultProAvailable:false, consultProUnavailableReason:reason});
  d.getElementById('add-attachment').click();
  const note = d.getElementById('composer-consult-note');
  assert.equal(note.hidden, false); assert.equal(note.textContent, reason);
  assert.equal(d.getElementById('composer-consult-pro').title, reason);
  w.mobileCodexEvent('state', {...snapshot, consultProAvailable:false});
  assert.equal(note.textContent, '현재 Pro 자문을 사용할 수 없습니다.');
});
