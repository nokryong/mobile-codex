/* Packaged UI only. Native.postMessage is the sole transport; no remote scripts. */
(() => {
  'use strict';
  const L = window.MobileCodexLocale, t = L.t;
  const $ = id => document.getElementById(id), C = window.UiCore;
  const pending = new Map(), requestQueue = new Map();
  const dialogs = [], frame = fn => (window.requestAnimationFrame || (cb => setTimeout(cb, 0)))(fn);
  let following = true, draftScope = '', sending = null, configOriginal = '', sidebarFocus = null, openedImage = null;
  const imageReads = new Map();
  let viewerImages = [], viewerIndex = 0, viewerGroup = null;
  let seq = 0, state = {messages: [], sessions: [], projects: [], models: [], accounts: [], account: {}, rateLimits: {}, workspace: {}}, folder = '', openedFile = null, login = null, inputResolve = null, toastTimer, fileSeq = 0, modelKey = '', displayedRequest = null, projectMenuFocus = null, projectMenuActionClosing = false;
  let draftContext = {attachments: [], mentions: [], skills: []}, draftOptions = {model:'', effort:''}, autocomplete = {items: [], index: -1, token: '', type: '', version: 0};
  const handledReceipts = new Set(), handledVoiceReceipts = new Set();
  let dictationState = {phase:'idle'}, dictationTimer = null;
  let voiceStarting = false, voiceActive = false, voiceRecoveryGeneration = 0;
  let chatIconsEnabled = localStorage.getItem('chat-icons') !== 'off', activityIcon = 'thinking';
  let chatMode = localStorage.getItem('conversation-mode') === 'chat', chatBusy = false;
  let chatMessages = [];
  try { const saved = JSON.parse(localStorage.getItem('chat-web-messages') || '[]'); if (Array.isArray(saved)) chatMessages = saved.slice(-100); } catch {}
  let chatModel = localStorage.getItem('chat-web-model') || '';
  let characterState = {folderName:'', folderConfigured:false, selectedPackId:'builtin', packs:[{id:'builtin',name:'Builtin',valid:true,icons:{}}]};
  let instructionsOriginal = '', instructionsLoaded = false, instructionsSaving = false, devtoolsCheckResult = null, devtoolsCheckSummary = '', devtoolsChecking = false, usageLoading = false, accountActionStatus = {text:'', error:false};
  let resetCredits = null, resetCreditBusy = false, resetCreditScope = '', resetCreditIdempotencyKey = '', resetCreditSelectedId = '', usageGeneration = 0;
  const toolCache = [null, null, null];
  let updateState = {}, updateSourceDirty = false, updateRequestPending = false;
  function call(action, args = {}) {
    return new Promise((resolve, reject) => {
      if (!window.Native) return reject(new Error(t('Android 앱에서 실행해 주세요.')));
      const id = String(++seq);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(t('요청 시간이 초과되었습니다. 상태를 확인하고 다시 시도해 주세요.'))); }, ['files.mutate','recovery.restore','updates.install','chat.web.send'].includes(action) ? 610000 : 150000);
      pending.set(id, {resolve, reject, timer});
      window.Native.postMessage(JSON.stringify({id, action, args}));
    });
  }
  const rpc = (method, params = {}) => call('rpc', {method, params});
  const on = (id, fn, event = 'click') => $(id).addEventListener(event, e => { if (event === 'submit') e.preventDefault(); Promise.resolve().then(() => fn(e)).catch(error => toast(error.message)); });
  function node(tag, text, cls) { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
  function button(text, fn, cls = 'secondary-button') { const b = node('button', text, cls); b.type = 'button'; b.addEventListener('click', () => { b.disabled = true; Promise.resolve().then(fn).catch(e => toast(e.message)).finally(() => { b.disabled = false; if (b.dataset.restoreFocus === 'true') b.focus(); }); }); return b; }
  function icon(name) { const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); const use = document.createElementNS(svg.namespaceURI, 'use'); use.setAttribute('href', '#i-' + name); svg.append(use); return svg; }
  function toast(message) { $('toast').textContent = message || t('오류가 발생했습니다.'); $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 5500); }
  function ensureNotificationSettings() {
    const toggle = $('chat-icons-toggle')?.closest('.settings-row');
    if (!toggle) return;
    const make = (id, title, description) => { const row = node('div', null, 'settings-row'), copy = node('span'), strong = node('strong', title), small = node('small', description), label = node('label', null, 'toggle-switch'), input = node('input'); input.id = id; input.type = 'checkbox'; input.setAttribute('role', 'switch'); input.checked = localStorage.getItem(id) !== 'off'; input.addEventListener('change', () => { try { localStorage.setItem(id, input.checked ? 'on' : 'off'); } catch {} call('notifications.configure', {enabled: $('notifications-toggle')?.checked !== false, vibration: $('notification-vibration-toggle')?.checked !== false}).catch(error => toast(error.message)); }); label.append(input, node('span', null, 'toggle-track')); copy.append(strong, small); row.append(copy, label); return row; };
    const parent = toggle.parentElement;
    if (!$('notifications-toggle')) {
      const notifications = make('notifications-toggle', t('작업 알림'), t('답변 완료와 승인 요청을 Android 상단 알림으로 알려줍니다.'));
      const vibration = make('notification-vibration-toggle', t('알림 진동'), t('작업 알림이 도착할 때 진동합니다.'));
      const permission = node('div', null, 'settings-row notification-permission-row');
      const copy = node('span'), title = node('strong', t('Android 알림 권한')), help = node('small', t('시스템 권한이 꺼져 있으면 상단 알림을 받을 수 없습니다.'));
      const open = button(t('Android 알림 설정'), async () => { await call('notifications.openSettings'); setTimeout(refreshNotificationSettings, 250); }, 'secondary-button');
      open.id = 'notification-settings-button'; copy.append(title, help); permission.append(copy, open);
      parent.insertBefore(permission, toggle); parent.insertBefore(vibration, permission); parent.insertBefore(notifications, vibration);
    }
    function apply(value) {
      if (!value) return;
      $('notifications-toggle').checked = value.enabled !== false;
      $('notification-vibration-toggle').checked = value.vibration !== false;
      const permission = document.querySelector('.notification-permission-row');
      if (permission) permission.hidden = value.permission !== false;
    }
    function refreshNotificationSettings() { return call('notifications.state').then(apply).catch(() => {}); }
    window.refreshNotificationSettings = refreshNotificationSettings;
    refreshNotificationSettings();
  }
  function ensureChatWebProbeUi() {
    const panel = document.querySelector('[data-settings-panel="advanced"]');
    if (!panel || $('chat-web-probe')) return;
    const section = node('section', null, 'character-pack-card');
    section.append(node('h4', t('일반 Chat 전송 실험')),
      node('p', t('폰 안의 WebView에서 공식 ChatGPT 페이지를 엽니다. 로그인과 검증은 직접 완료해야 합니다. 앱 입력창에서 보내는 경로를 시험하며, 동작은 아직 검증되지 않았습니다.'), 'muted'));
    const open = button(t('전송 실험 열기'), () => call('ui.chatWebProbe'));
    open.id = 'chat-web-probe'; section.append(open); panel.append(section);
  }
  function characterPack(value) { const packs = Array.isArray(value?.packs) ? value.packs : characterState.packs; return packs.find(pack => pack.id === (value?.selectedPackId || characterState.selectedPackId)) || packs.find(pack => pack.id === 'builtin') || packs[0]; }
  function characterVisualKey() { const pack = characterPack(); return characterState.selectedPackId + ':' + JSON.stringify(pack?.icons || {}); }
  function characterErrorText(error) { const text = String(error || ''); for (const prefix of ['아이콘이 없습니다: ', '지원하지 않는 이미지입니다: ']) if (text.startsWith(prefix)) return t(prefix) + text.slice(prefix.length); return t(text); }
  function characterPackName(pack) { return pack?.id === 'builtin' ? t('기본 캐릭터') : (pack?.name || pack?.id || ''); }
  function characterIconUrl(name, pack = characterPack()) { return pack?.icons?.[name] || C.chatIconUrl(name) || C.chatIconUrl('explaining'); }
  function setCharacterState(value) { const next = value?.characters || value; if (!next || !Array.isArray(next.packs)) return; characterState = {...characterState, ...next}; renderCharacterPacks(); }
  function characterIcon(name, cls = '', pack = null) {
    const img = node('img', null, 'chat-character ' + cls); img.src = characterIconUrl(name, pack || undefined); img.alt = ''; img.setAttribute('aria-hidden', 'true'); img.width = 96; img.height = 96; img.loading = 'lazy'; img.decoding = 'async';
    img.addEventListener('error', () => { if (img.dataset.fallback) { img.hidden = true; return; } img.dataset.fallback = 'builtin'; img.src = C.chatIconUrl(name) || C.chatIconUrl('explaining'); });
    return img;
  }
  function ensureCharacterPackUi() {
    if ($('character-pack-card')) return;
    const toggle = $('chat-icons-toggle')?.closest('.settings-row'); if (!toggle) return;
    const card = node('section', null, 'character-pack-card'); card.id = 'character-pack-card';
    const head = node('div', null, 'settings-row'), copy = node('span'), folderLabel = node('small', '', 'character-pack-folder'); folderLabel.id = 'character-pack-folder'; copy.append(node('strong', t('캐릭터 팩')), folderLabel); const refresh = button(t('새로고침'), refreshCharacterPacks, 'secondary-button'); refresh.id = 'character-pack-refresh'; refresh.dataset.restoreFocus = 'true'; refresh.setAttribute('aria-label', t('캐릭터 팩 새로고침')); head.append(copy, refresh);
    const actions = node('div', null, 'character-pack-actions'); const choose = button(t('폴더 선택'), chooseCharacterPackFolder, 'secondary-button'); choose.id = 'character-pack-folder-button'; const change = button(t('폴더 변경'), chooseCharacterPackFolder, 'secondary-button'); change.id = 'character-pack-change'; actions.append(choose, change);
    const status = node('p', '', 'muted'); status.id = 'character-pack-status'; status.setAttribute('role','status'); const list = node('div', null, 'character-pack-list'); list.id = 'character-pack-list'; card.append(head, actions, status, list); toggle.after(card);
  }
  function renderCharacterPacks() {
    ensureCharacterPackUi(); const card = $('character-pack-card'); if (!card) return;
    const folder = $('character-pack-folder'), status = $('character-pack-status'), list = $('character-pack-list'); folder.textContent = characterState.folderName || t('기본 캐릭터'); status.textContent = characterErrorText(characterState.error) || (characterState.loading ? t('캐릭터 팩을 불러오는 중…') : (characterState.folderConfigured ? t('캐릭터 팩 폴더가 연결되었습니다.') : '')); $('character-pack-change').hidden = !characterState.folderConfigured; $('character-pack-folder-button').hidden = characterState.folderConfigured; $('character-pack-folder-button').disabled = !!characterState.loading; $('character-pack-refresh').disabled = !!characterState.loading; $('character-pack-change').disabled = !!characterState.loading;
    list.replaceChildren(); const packs = characterState.packs || []; if (characterState.folderConfigured && packs.length <= 1) { list.append(node('p', t('폴더 안에 팩 폴더와 mapping.json, 32개 PNG를 넣어 주세요.'), 'empty-note')); }
    for (const pack of packs) { const row = button('', () => selectCharacterPack(pack.id), 'character-pack-option'); row.dataset.packId = pack.id; row.disabled = !pack.valid || !!characterState.loading; const displayName = characterPackName(pack); row.setAttribute('aria-label', t('캐릭터 팩 선택') + ': ' + displayName); row.setAttribute('aria-pressed', String(pack.id === characterState.selectedPackId)); const preview = node('span', null, 'character-pack-preview'); for (const name of C.chatIconNames().slice(0, 3)) { const image = characterIcon(name, 'character-pack-icon', pack); image.classList.remove('chat-character'); preview.append(image); } const info = node('span', null, 'character-pack-info'); info.append(node('strong', displayName), node('small', pack.valid ? t('32개 상태') : (characterErrorText(pack.error) || t('사용할 수 없음')))); row.append(preview, info, node('span', pack.id === characterState.selectedPackId ? '✓' : '', 'character-pack-check')); list.append(row); }
  }
  let characterListRequest = null;
  async function loadCharacterPacks() {
    if (characterListRequest || characterState.loading) return characterListRequest;
    characterListRequest = characterRequest('characters.list').finally(() => { characterListRequest = null; });
    return characterListRequest;
  }
  async function characterRequest(action, args) {
    if (characterState.loading) return;
    const focusedPack = document.activeElement?.dataset?.packId || '';
    characterState = {...characterState, loading:true, error:''}; renderCharacterPacks();
    try {
      const result = await call(action, args);
      if (!result?.cancelled) { setCharacterState(result); renderDraftContext(); drawStatusIcons(); drawMessages(); }
    } catch (error) { characterState = {...characterState, error:error.message}; }
    finally { characterState = {...characterState, loading:false}; renderCharacterPacks(); const row = focusedPack && document.querySelector(`[data-pack-id="${CSS.escape(focusedPack)}"]`); if (row) row.focus(); }
  }
  async function chooseCharacterPackFolder() { return characterRequest('characters.chooseFolder'); }
  async function refreshCharacterPacks() { return characterRequest('characters.refresh'); }
  async function selectCharacterPack(id) { return characterRequest('characters.select', {id}); }
  function show(id) {
    sidebar(false);
    if (!$(id).open) { dialogs.push(id); $(id).showModal(); }
  }
  function close(id) { $(id).close(); }
  function dismiss(id) {
    const dirty = (id === 'editor-dialog' && openedFile && $('editor').value !== openedFile.content)
      || (id === 'config-dialog' && $('config-editor').value !== configOriginal)
      || (id === 'settings-dialog' && instructionsLoaded && $('instructions-editor').value !== instructionsOriginal);
    if (dirty && !confirm(t('저장하지 않은 변경 사항을 닫을까요?'))) return;
    close(id);
  }
  function wireSheetGestures() {
    const mobile = matchMedia('(max-width:760px)');
    document.querySelectorAll('dialog .sheet-grip').forEach(grip => {
      const sheet = grip.closest('dialog');
      let drag = null, suppressClickUntil = 0;
      function reset() {
        const previous = drag; drag = null;
        sheet.classList.remove('sheet-dragging'); sheet.style.removeProperty('--sheet-offset');
        if (previous && grip.hasPointerCapture?.(previous.id)) grip.releasePointerCapture(previous.id);
      }
      grip.addEventListener('pointerdown', e => {
        if (!mobile.matches || !sheet.open || e.isPrimary === false || e.button !== 0) return;
        suppressClickUntil = 0;
        sheet.getAnimations?.().forEach(animation => animation.finish());
        drag = {id:e.pointerId, x:e.clientX, y:e.clientY, dx:0, dy:0};
        grip.setPointerCapture?.(e.pointerId); sheet.classList.add('sheet-dragging');
      });
      grip.addEventListener('pointermove', e => {
        if (!drag || drag.id !== e.pointerId) return;
        drag.dx = e.clientX - drag.x; drag.dy = Math.max(0, e.clientY - drag.y);
        const offset = Math.abs(drag.dx) > drag.dy ? 0 : drag.dy;
        sheet.style.setProperty('--sheet-offset', Math.min(offset, sheet.clientHeight * .85 || offset) + 'px');
      });
      grip.addEventListener('pointerup', e => {
        if (!drag || drag.id !== e.pointerId) return;
        const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
        const threshold = Math.min(112, Math.max(72, sheet.clientHeight * .22));
        const moved = Math.hypot(dx, dy) > 8;
        if (moved) suppressClickUntil = performance.now() + 500;
        reset();
        // Reuse the close path so unsaved instructions still require confirmation.
        if (mobile.matches && sheet.open && dy >= threshold && dy > Math.abs(dx) * 1.25) dismiss(sheet.id);
      });
      grip.addEventListener('pointercancel', () => { suppressClickUntil = performance.now() + 500; reset(); });
      grip.addEventListener('lostpointercapture', reset);
      grip.addEventListener('click', e => {
        // A drag is followed by a synthetic click on some WebViews; keep a short drag open.
        if (e.detail > 0 && performance.now() < suppressClickUntil) { e.preventDefault(); e.stopImmediatePropagation(); }
      }, true);
      sheet.addEventListener('close', reset);
      window.addEventListener('resize', reset);
      window.addEventListener('blur', reset);
    });
  }
  function sidebar(open) {
    const wasOpen = document.body.classList.contains('sidebar-open');
    document.body.classList.toggle('sidebar-open', open); $('scrim').hidden = !open;
    if (matchMedia('(max-width:760px)').matches) {
      $('sidebar').inert = !open; document.querySelector('main').inert = open;
      if (open && !wasOpen) { sidebarFocus = document.activeElement; $('new-chat').focus(); }
      else if (!open && wasOpen) sidebarFocus?.focus({preventScroll:true});
    }
    document.querySelectorAll('.sidebar-toggle').forEach(b => b.setAttribute('aria-expanded', String(open)));
  }
  function setTheme() {
    const choice = $('theme').value;
    try { localStorage.setItem('theme', choice); } catch {}
    document.documentElement.dataset.theme = choice === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : choice;
    call('ui.theme', {theme:choice}).catch(() => {});
  }
  function contextScope(scope = draftScope) { return scope ? scope + ':context' : ''; }
  function optionsScope(scope = draftScope) { return scope ? scope + ':options' : ''; }
  function restoreOptions(scope) { try { const value = JSON.parse(localStorage.getItem(optionsScope(scope)) || '{}'); return {model:value.model || '', effort:value.effort || ''}; } catch { return {model:'', effort:''}; } }
  function saveOptions() { if (!draftScope) return; draftOptions = {model:$('model').value, effort:$('effort').value}; try { if (draftOptions.model || draftOptions.effort) localStorage.setItem(optionsScope(), JSON.stringify(draftOptions)); else localStorage.removeItem(optionsScope()); } catch {} }
  function saveDraft() {
    if (chatMode) { try { if ($('prompt').value) localStorage.setItem('chat-web-draft', $('prompt').value); else localStorage.removeItem('chat-web-draft'); } catch {} return; }
    if (!draftScope) return;
    try {
      if ($('prompt').value) localStorage.setItem(draftScope, $('prompt').value); else localStorage.removeItem(draftScope);
      const context = {attachments:draftContext.attachments || [], mentions:draftContext.mentions || [], skills:draftContext.skills || []};
      if (context.attachments.length || context.mentions.length || context.skills.length) localStorage.setItem(contextScope(), JSON.stringify(context)); else localStorage.removeItem(contextScope());
    } catch {}
  }
  function restoreContext(scope) {
    try { const value = JSON.parse(localStorage.getItem(contextScope(scope)) || '{}'); return {attachments:Array.isArray(value.attachments) ? value.attachments : [], mentions:Array.isArray(value.mentions) ? value.mentions : [], skills:Array.isArray(value.skills) ? value.skills : []}; }
    catch { return {attachments:[], mentions:[], skills:[]}; }
  }
  function draftKeyFor(scope = draftScope) { return scope.replace(/^draft:/, ''); }
  function removeContext(type, value) {
    draftContext[type] = (draftContext[type] || []).filter(item => item.id !== value && item.path !== value && item.name !== value); renderDraftContext(); saveDraft(); updateSend();
  }
  function renderDraftContext() {
    const target = $('draft-context'); target.replaceChildren();
    const items = [
      ...(draftContext.attachments || []).map(item => ({type:'attachments', value:item, label:item.name || t('첨부 파일')})),
      ...(draftContext.mentions || []).map(item => ({type:'mentions', value:item, label:'@' + (item.name || item.path)})),
      ...(draftContext.skills || []).map(item => ({type:'skills', value:item, label:'$' + item.name}))
    ];
    for (const item of items) {
      const chip = node('span', null, 'context-chip'), image = item.value.image || item.value;
      if (item.type === 'attachments' && C.safeImageUrl(image.url)) { const preview = button('', () => previewImage(image), 'context-image'); preview.setAttribute('aria-label', item.label + t(' 미리 보기')); const img = node('img'); img.src = image.url; img.alt = item.label; preview.append(img); chip.append(preview); }
      chip.append(node('span', item.label)); const remove = button('×', () => removeContext(item.type, item.value.id || item.value.path || item.value.name), 'chip-remove'); remove.setAttribute('aria-label', item.label + t(' 제거')); chip.append(remove); target.append(chip);
    }
    target.hidden = !items.length;
  }
  function setDraftScope(next) {
    const workspaceKey = Object.prototype.hasOwnProperty.call(next.workspace || {}, 'key') ? next.workspace.key : (next.cwd || '');
    const scope = 'draft:' + C.draftKey(workspaceKey || '', next.threadId || 'new');
    if (scope === draftScope) return;
    saveDraft();
    const createdBySend = sending && !state.threadId && next.threadId && state.cwd === next.cwd && state.workspace?.key === next.workspace?.key;
    draftScope = scope; hideAutocomplete();
    if (createdBySend) { sending.scopes.add(scope); saveDraft(); saveOptions(); }
    else { try { $('prompt').value = localStorage.getItem(scope) || ''; } catch { $('prompt').value = ''; } draftContext = restoreContext(scope); renderDraftContext(); }
    if (!createdBySend) draftOptions = restoreOptions(scope);
    if ([...$('model').options].some(o => o.value === draftOptions.model)) $('model').value = draftOptions.model;
    efforts(); if ([...$('effort').options].some(o => o.value === draftOptions.effort)) $('effort').value = draftOptions.effort;
    sizeComposer();
  }
  function updateSend() { $('voice-input').disabled = (!chatMode && !draftScope) || voiceStarting || voiceActive || !!sending || chatBusy; $('voice-input').setAttribute('aria-busy', String(voiceStarting || voiceActive)); $('send').disabled = chatMode ? (!$('prompt').value.trim() || chatBusy || voiceStarting || voiceActive) : ((!$('prompt').value.trim() && !(draftContext.attachments || []).length) || !!sending || voiceStarting || voiceActive); $('send').setAttribute('aria-label', !chatMode && state.busy ? t('진행 중인 작업에 추가 지시') : t('메시지 보내기')); $('send').title = !chatMode && state.busy ? t('추가 지시') : t('보내기'); }
  function scrollLatest() { following = true; $('chat-scroll').scrollTop = $('chat-scroll').scrollHeight; $('jump-latest').hidden = true; }
  function sizeComposer() {
    const field = $('prompt'), cap = Math.max(60, Math.min(160, window.innerHeight * .24));
    field.style.height = '0px';
    field.style.height = Math.max(36, Math.min(field.scrollHeight, cap)) + 'px';
    field.style.overflowY = field.scrollHeight > cap ? 'auto' : 'hidden';
    updateSend();
    if (following) scrollLatest();
  }
  function viewportChanged() {
    // Native already excludes the IME. min() also handles older visual-viewport-only resizing.
    const visual = window.visualViewport, height = visual && visual.scale === 1 ? Math.min(window.innerHeight, visual.height) : window.innerHeight;
    document.documentElement.style.setProperty('--app-height', height + 'px');
    sizeComposer();
    if (following) frame(scrollLatest);
    if (!matchMedia('(max-width:760px)').matches) {
      document.body.classList.remove('sidebar-open'); $('scrim').hidden = true;
      $('sidebar').inert = false; document.querySelector('main').inert = false;
    } else $('sidebar').inert = !document.body.classList.contains('sidebar-open');
  }
  function optionsSummary() {
    const model = $('model').selectedOptions[0]?.textContent || t('기본 모델');
    const effort = $('effort').selectedOptions[0]?.textContent || t('기본');
    const approval = $('approval-mode').selectedOptions[0]?.textContent || t('자동 검토');
    $('model-summary').textContent = model;
    $('composer-options').setAttribute('aria-label', t('작업 설정: ') + model + t(', 추론 ') + effort + t(', 승인 ') + approval);
    $('composer-options').title = model + ' · ' + effort;
    $('approval-mode').title = t('승인 방식') + ': ' + approval;
    $('permission-help').textContent = state.busy ? t('작업이 끝나면 권한을 변경할 수 있습니다.') : ({'read-only':t('파일을 읽고 검토합니다.'), 'workspace-write':t('선택한 프로젝트의 파일을 수정할 수 있습니다.'), 'danger-full-access':t('앱에 허용된 기기 파일과 명령에 접근할 수 있습니다.')}[$('permissions').value] || '');
    const current = state.models.find(m => (m.model || m.id) === $('model').value) || state.models.find(m => m.isDefault);
    $('model-help').textContent = current?.description || (current ? t('연결된 계정에서 사용할 수 있는 모델입니다.') : t('기본 모델을 사용합니다.'));
  }
  function syncPermissionControls(mode, disabled = !!state.busy) {
    const value = ['read-only','workspace-write','danger-full-access'].includes(mode) ? mode : 'workspace-write';
    $('permissions').value = value; $('permissions').disabled = disabled;
    document.querySelectorAll('input[name="permission"]').forEach(r => { r.checked = r.value === value; r.disabled = disabled; });
  }
  async function setApprovalMode(mode) {
    const previous = state.approvalMode || 'auto-review';
    $('approval-mode').disabled = true;
    try { await call('approvals.set', {mode}); state.approvalMode = mode; }
    catch (error) { $('approval-mode').value = previous; throw error; }
    finally { $('approval-mode').value = state.approvalMode || previous; $('approval-mode').disabled = !!state.busy; optionsSummary(); }
  }
  async function setPermissionMode(mode) {
    const previous = state.permissions || 'workspace-write';
    syncPermissionControls(mode, true); optionsSummary();
    try { await call('permissions.set', {mode}); state.permissions = mode; }
    catch (error) { syncPermissionControls(previous, !!state.busy); throw error; }
    finally { syncPermissionControls(state.permissions, !!state.busy); optionsSummary(); }
  }
  async function insertToken(token, item) {
    const scope = draftScope, field = $('prompt'), start = field.selectionStart, end = field.selectionEnd, original = field.value, selectedToken = (item.kind === 'skill' ? '$' : '@') + item.name, before = original.slice(0, start).replace(/(?:^|\s)[@$][^\s@#$]*$/, m => m.slice(0, m.lastIndexOf(token)) + selectedToken);
    let mention = null;
    if (item.kind === 'mention' && !draftContext.mentions.some(x => x.path === item.path)) {
      mention = item.path.startsWith('app://') ? {name:item.name, path:item.path} : await call('files.mention', {path:item.path});
    }
    if (scope !== draftScope || field.value !== original) return;
    field.value = before + ' ' + original.slice(end); field.selectionStart = field.selectionEnd = before.length + 1;
    if (item.kind === 'skill' && !draftContext.skills.some(x => x.path === item.path || x.name === item.name)) draftContext.skills.push({name:item.name, path:item.path});
    if (mention) draftContext.mentions.push({name:mention.name || item.name, path:mention.path || item.path});
    hideAutocomplete(); renderDraftContext(); saveDraft(); sizeComposer(); field.focus();
  }
  function hideAutocomplete() {
    autocomplete = {...autocomplete, version:autocomplete.version + 1, items:[], index:-1};
    $('autocomplete').hidden = true; $('autocomplete').replaceChildren(); $('prompt').setAttribute('aria-expanded', 'false');
  }
  function chooseAutocomplete(item) {
    if (item.run) return item.run();
    return insertToken(autocomplete.token, item);
  }
  function drawAutocomplete(items, messages = []) {
    const target = $('autocomplete'); target.replaceChildren(); autocomplete.items = items; autocomplete.index = items.length ? 0 : -1;
    target.append(node('div', autocomplete.type === '$' ? t('스킬') : t('파일 및 앱'), 'autocomplete-heading'));
    for (const [index, item] of items.entries()) {
      const row = button('', () => chooseAutocomplete(item), 'autocomplete-item'); row.setAttribute('role', 'option');
      row.id = 'context-option-' + index; row.dataset.index = String(index);
      row.append(node('strong', item.label || item.name), node('small', item.detail || item.path || ''));
      target.append(row);
    }
    for (const message of messages) { const status = node('p', message, 'autocomplete-status'); status.setAttribute('role', 'status'); target.append(status); }
    target.hidden = false; $('prompt').setAttribute('aria-expanded', 'true'); updateAutocompleteActive();
  }
  function updateAutocompleteActive() {
    $('autocomplete').querySelectorAll('.autocomplete-item').forEach((el, i) => el.setAttribute('aria-selected', String(i === autocomplete.index)));
    if (autocomplete.index >= 0) $('prompt').setAttribute('aria-activedescendant', 'context-option-' + autocomplete.index);
    else $('prompt').removeAttribute('aria-activedescendant');
  }
  async function queryAutocomplete(directory = '') {
    const field = $('prompt'), before = field.value.slice(0, field.selectionStart), match = before.match(/(?:^|\s)([@$])([^\s@#$]*)$/);
    if (!match) return hideAutocomplete();
    const type = match[1], query = directory ? '' : match[2], version = ++autocomplete.version, scope = draftScope;
    autocomplete.type = type; autocomplete.token = type + query;
    const current = () => version === autocomplete.version && scope === draftScope;
    const retry = {label:t('다시 불러오기'), detail:t('사용 가능한 목록을 새로 확인합니다.'), run:() => queryAutocomplete(directory)};
    drawAutocomplete([], [t('사용 가능한 ') + (type === '$' ? t('스킬') : t('파일 및 앱')) + t('을 불러오는 중…')]);
    if (type === '$') {
      try {
        const result = await rpc('skills/list', {cwds:state.cwd ? [state.cwd] : [], forceReload:true});
        if (!current()) return;
        const items = [], seen = new Set(), messages = [];
        for (const group of result.data || []) {
          for (const error of group.errors || []) messages.push(error.message || t('일부 스킬을 읽지 못했습니다.'));
          for (const skill of group.skills || []) {
            if (skill.enabled === false || !skill.name || !skill.path || seen.has(skill.path)) continue;
            if (query && !(skill.name + ' ' + (skill.description || '')).toLowerCase().includes(query.toLowerCase())) continue;
            seen.add(skill.path); items.push({kind:'skill', name:skill.name, path:skill.path, label:'$' + skill.name, detail:skill.description || skill.path});
          }
        }
        if (!items.length) messages.push(query ? t('일치하는 스킬이 없습니다.') : t('사용 가능한 스킬이 없습니다. 스킬 폴더를 가져와 추가하세요.'));
        drawAutocomplete([...items, {label:t('스킬 가져오기'), detail:t('SKILL.md가 들어 있는 폴더 선택'), run:() => { hideAutocomplete(); $('skill-import').click(); }}], messages);
      } catch (error) {
        if (current()) drawAutocomplete([retry], [t('스킬 목록을 불러오지 못했습니다. ') + error.message]);
      }
      return;
    }
    const sections = {files:[], apps:[]}, messages = {files:t('파일을 불러오는 중…'), apps:t('앱을 불러오는 중…')};
    const render = () => { if (current()) drawAutocomplete([...sections.files, ...sections.apps], Object.values(messages).filter(Boolean)); };
    // Render each source when ready: a slow app connection must not hide local files.
    const filesRequest = state.workspace?.selected
      ? call(query ? 'files.search' : 'files.list', query ? {query} : {path:directory})
      : Promise.resolve({entries:[]});
    filesRequest.then(result => {
      if (!current()) return;
      sections.files = (result.entries || []).filter(x => x.path).map(x => x.directory
        ? {label:x.name + '/', detail:t('폴더 열기'), run:() => queryAutocomplete(x.path)}
        : {kind:'mention', name:x.name || C.basename(x.path), path:x.path, label:'@' + (x.name || C.basename(x.path)), detail:t('파일 · ') + x.path});
      if (directory && !query) sections.files.unshift({label:t('상위 폴더'), detail:directory, run:() => queryAutocomplete(directory.split('/').slice(0,-1).join('/'))});
      messages.files = !state.workspace?.selected ? t('프로젝트를 선택하면 파일 목록도 표시됩니다.') : sections.files.length ? '' : t('일치하는 파일이 없습니다.');
      render();
    }).catch(error => { if (current()) { messages.files = t('파일 목록을 불러오지 못했습니다. ') + error.message; sections.files = [retry]; render(); } });
    rpc('app/list', {}).then(result => {
      if (!current()) return;
      sections.apps = (result.data || result.apps || []).filter(x => x.isAccessible && x.isEnabled && (!query || (x.name || x.id || '').toLowerCase().includes(query.toLowerCase())))
        .map(x => ({kind:'mention', name:x.name || x.id, path:'app://' + x.id, label:'@' + (x.name || x.id), detail:t('연결된 앱')}));
      messages.apps = sections.apps.length ? '' : t('사용 가능한 연결 앱이 없습니다.'); render();
    }).catch(error => { if (current()) { messages.apps = t('앱 목록을 불러오지 못했습니다. ') + error.message; if (!sections.files.includes(retry)) sections.apps = [retry]; render(); } });
  }
  async function chooseAttachment() {
    const key = draftKeyFor(), result = await call('attachments.pick', {draftKey:key});
    acceptPickedAttachments(result, key);
  }
  function acceptPickedAttachments(result, receiptKey) {
    if (!result) return;
    const key = result.draftKey || receiptKey || draftKeyFor(), receipt = result.receiptId || '';
    if (receipt && handledReceipts.has(receipt)) return;
    const scope = 'draft:' + key, context = scope === draftScope ? draftContext : restoreContext(scope), ids = new Set(context.attachments.map(x => x.id));
    context.attachments.push(...(result.attachments || []).filter(x => x?.id && !ids.has(x.id)));
    try { if (context.attachments.length || context.mentions.length || context.skills.length) localStorage.setItem(contextScope(scope), JSON.stringify(context)); }
    catch { toast(t('첨부 초안을 저장할 공간이 부족합니다. 첨부를 줄인 뒤 다시 시도해 주세요.')); return; }
    if (scope === draftScope) { draftContext = context; renderDraftContext(); updateSend(); }
    if (result.errors?.length && !result.cancelled) toast(result.errors.join('\n'));
    if (receipt) { handledReceipts.add(receipt); call('attachments.ack', {receiptId:receipt}).catch(() => {}); }
  }
  async function startVoiceInput() {
    if (voiceStarting || voiceActive || !draftScope || sending) return;
    const field = $('prompt'); saveDraft(); hideAutocomplete();
    const draft = {scope:draftKeyFor(), original:field.value, start:field.selectionStart, end:field.selectionEnd};
    field.blur(); voiceStarting = true; updateSend();
    try { await call('voice.start', draft); }
    finally { voiceStarting = false; updateSend(); await recoverVoiceInput(); }
  }
  function drawDictation(value) {
    dictationState = value || {phase:'idle'};
    const phase = dictationState.phase || 'idle', active = phase !== 'idle';
    $('dictation').hidden = !active;
    $('prompt').readOnly = active;
    $('dictation-status').textContent = ({permission:t('마이크 권한 확인 중'), starting:t('마이크 준비 중…'), listening:t('듣고 있어요…'), transcribing:t('음성을 텍스트로 바꾸는 중…')})[phase] || '';
    $('dictation-preview').textContent = dictationState.partial || '';
    $('dictation-level').value = Number(dictationState.level) || 0;
    $('dictation-done').disabled = phase !== 'listening';
    clearInterval(dictationTimer); dictationTimer = null;
    const base = Date.now(), elapsed = Number(dictationState.elapsedMs) || 0;
    const tick = () => { const seconds = Math.floor((elapsed + (phase === 'listening' ? Date.now() - base : 0)) / 1000); $('dictation-time').textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2,'0'); };
    tick(); if (phase === 'listening') dictationTimer = setInterval(tick, 1000);
    voiceActive = active; updateSend(); viewportChanged();
  }
  function mergeVoiceText(current, result) {
    if (!result.text) return current;
    if (current === result.original) {
      const start = Math.max(0, Math.min(current.length, Number.isInteger(result.start) ? result.start : current.length));
      const end = Math.max(start, Math.min(current.length, Number.isInteger(result.end) ? result.end : start));
      return current.slice(0, start) + result.text + current.slice(end);
    }
    return current + (!current || /\s$/.test(current) ? '' : '\n') + result.text;
  }
  function acceptVoiceReceipt(result) {
    if (!result || result.origin !== 'main' || !result.receiptId || !result.scope || handledVoiceReceipts.has(result.receiptId)) return;
    const scope = 'draft:' + result.scope, marker = 'voice-receipt:' + result.receiptId;
    try {
      let journal = JSON.parse(localStorage.getItem(marker) || 'null');
      if (!journal?.done) {
        const current = scope === draftScope ? $('prompt').value : localStorage.getItem(scope) || '';
        if (!journal) {
          journal = {before:current, after:mergeVoiceText(current, result)};
          localStorage.setItem(marker, JSON.stringify(journal));
        }
        // A process restart between saving the draft and its completion marker must not append twice.
        const merged = current === journal.before || current === journal.after ? journal.after : mergeVoiceText(current, {...result, original:null});
        localStorage.setItem(scope, merged);
        localStorage.setItem(marker, JSON.stringify({done:true}));
        if (scope === draftScope) { $('prompt').value = merged; sizeComposer(); }
        if (result.error) toast(result.error);
        else if (result.text) toast(scope === draftScope ? t('음성 초안을 확인한 뒤 보내세요.') : t('원래 대화의 음성 초안을 저장했습니다.'));
      }
      handledVoiceReceipts.add(result.receiptId);
      call('voice.ack', {receiptId:result.receiptId}).then(() => localStorage.removeItem(marker)).catch(() => {});
    } catch { toast(t('음성 초안을 저장하지 못했습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요.')); }
  }
  async function recoverVoiceInput() {
    const generation = ++voiceRecoveryGeneration;
    try {
      const result = await call('voice.recover');
      if (generation !== voiceRecoveryGeneration) return;
      if (result.dictation) drawDictation(result.dictation);
      voiceActive = !!result.active; updateSend();
      for (const receipt of result.receipts || []) acceptVoiceReceipt(receipt);
    } catch { /* Receipts remain native until the next resume or startup. */ }
  }
  function drawUpdates(value) {
    if (!value || (Number.isFinite(value.revision) && value.revision < (updateState.revision || 0))) return;
    updateState = value;
    const busy = !!value.busy || updateRequestPending, release = value.candidate;
    if (value.versionName) { $('update-current').textContent = t('현재 ') + value.versionName + t(' · 빌드 ') + value.versionCode; $('app-version').textContent = value.versionName; }
    if (!updateSourceDirty) { if (value.repository) $('update-repository').value = value.repository; if (typeof value.prereleases === 'boolean') $('update-prereleases').checked = value.prereleases; }
    $('update-repository').disabled = busy; $('update-prereleases').disabled = busy; $('update-check').disabled = busy;
    $('update-status').textContent = value.message || t('업데이트 확인을 누르면 공개 릴리스를 조회합니다.');
    $('update-cancel').hidden = !value.canCancel; $('update-cancel').disabled = updateRequestPending;
    $('update-progress').hidden = !['downloading','verifying'].includes(value.status);
    const percent = release?.size ? Math.max(0, Math.min(100, 100 * (value.received || 0) / release.size)) : 0;
    $('update-progress').value = percent;
    $('update-release').hidden = !release;
    $('update-version').textContent = release ? t('공개 릴리스 ') + release.versionName : '';
    $('update-size').textContent = release ? (release.size / 1048576).toFixed(1) + ' MiB' + (value.status === 'downloading' ? ' · ' + Math.floor(percent) + '%' : '') : '';
    $('update-notes').textContent = release?.notes || '';
    $('update-download').hidden = !value.available || !!value.ready; $('update-download').disabled = busy || updateSourceDirty;
    $('update-permission').hidden = !value.ready || !!value.canInstall; $('update-permission').disabled = busy;
    $('update-install').hidden = !value.ready; $('update-install').disabled = busy || updateSourceDirty || !value.canInstall || state.busy;
    $('update-clear').hidden = !value.ready && !value.hasDownload; $('update-clear').disabled = busy;
  }
  async function loadUpdates() { try { drawUpdates(await call('updates.state')); } catch(error) { $('update-status').textContent = error.message; } }
  async function updateAction(action) {
    if (updateRequestPending) return;
    updateRequestPending = true; drawUpdates(updateState);
    try {
      if (action === 'check' && updateSourceDirty) {
        const result = await call('updates.configure', {repository:$('update-repository').value.trim(), prereleases:$('update-prereleases').checked});
        updateSourceDirty = false; drawUpdates(result);
      }
      const result = await call('updates.' + action, ['download','install'].includes(action) ? {sha256:updateState.candidate?.sha256 || ''} : {});
      if (result.status) drawUpdates(result);
    } finally { updateRequestPending = false; drawUpdates(updateState); }
  }
  function inline(target, text) {
    // Text nodes only, including untrusted Markdown. Safe links open in external browser.
    const pattern = /(`+)([^`\n]+?)\1|\*\*([^*\n]+)\*\*|\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g;
    let position = 0;
    for (const m of text.matchAll(pattern)) {
      target.append(document.createTextNode(text.slice(position, m.index)));
      if (m[2] != null) target.append(node('code', m[2]));
      else if (m[3] != null) target.append(node('strong', m[3]));
      else if (m[4] != null && m[5] != null) {
        const link = button(m[4], () => call('ui.openLink', {url: m[5]}), 'inline-link');
        link.title = m[5];
        target.append(link);
      }
      position = m.index + m[0].length;
    }
    target.append(document.createTextNode(text.slice(position)));
  }
  function imageCard(attachment, label = '', gallery = [attachment], group = null) {
    const figure = node('figure', null, 'image-card');
    if (!C.safeImageUrl(attachment.url)) { figure.append(node('p', t('이미지 주소를 읽을 수 없습니다.'), 'muted')); return figure; }
    const open = button('', () => previewImage(attachment, gallery, group), 'image-open');
    open.setAttribute('aria-label', (label || attachment.name || t('생성 이미지')) + t(' 크게 보기'));
    const img = node('img'); img.src = attachment.url; img.alt = label || attachment.name || t('생성 이미지'); img.loading = 'lazy'; img.decoding = 'async';
    if (attachment.width && attachment.height) { img.width = attachment.width; img.height = attachment.height; }
    const error = node('p', t('이미지를 불러오지 못했습니다.'), 'muted'); error.hidden = true;
    img.addEventListener('load', () => { if (following) scrollLatest(); });
    img.addEventListener('error', () => { error.hidden = false; });
    open.append(img); figure.append(open, error);
    const caption = node('figcaption'); caption.append(node('span', attachment.name || label || t('이미지')));
    if (attachment.id) caption.append(button(t('저장'), () => call('images.export', {id:attachment.id, name:attachment.name})));
    figure.append(caption); return figure;
  }
  function previewImage(attachment, gallery = [attachment], group = null) {
    if (!C.safeImageUrl(attachment.url)) return;
    viewerImages = gallery.filter(a => a && C.safeImageUrl(a.url)); viewerIndex = Math.max(0, viewerImages.indexOf(attachment)); viewerGroup = group;
    updateViewer(); show('image-dialog');
  }
  function updateViewer(reset = true) {
    const attachment = viewerImages[viewerIndex]; if (!attachment) return; openedImage = attachment;
    $('image-title').textContent = attachment.name || t('이미지'); $('image-preview').src = attachment.url;
    $('image-preview').alt = attachment.name || t('이미지'); $('image-save').hidden = !attachment.id;
    $('image-counter').textContent = (viewerIndex + 1) + ' / ' + viewerImages.length;
    $('image-navigation').hidden = viewerImages.length < 2;
    $('image-prev').disabled = viewerIndex === 0; $('image-next').disabled = viewerIndex === viewerImages.length - 1;
    $('image-thumbnails').replaceChildren(); $('image-thumbnails').hidden = viewerImages.length < 2;
    viewerImages.forEach((item, i) => {
      const b = button('', () => { viewerIndex = i; updateViewer(); }, 'image-thumbnail');
      b.setAttribute('aria-label', (i+1) + t('번째 이미지')); b.setAttribute('aria-current', String(i === viewerIndex));
      const img = node('img'); img.src = item.url; img.alt = ''; img.loading = 'lazy'; b.append(img); $('image-thumbnails').append(b);
    });
    if (reset) { $('image-stage').classList.remove('zoomed'); $('image-stage').scrollTop = 0; $('image-stage').scrollLeft = 0; $('image-zoom').setAttribute('aria-pressed', 'false'); }
  }
  function changeImage(delta) {
    const next = viewerIndex + delta;
    if (next < 0 || next >= viewerImages.length) return;
    viewerIndex = next; updateViewer();
  }
  function imageGallery(attachments, group) {
    const gallery = node('div', null, 'image-gallery'); gallery.classList.toggle('multiple', attachments.length > 1);
    gallery.setAttribute('aria-label', t('이미지 ') + attachments.length + t('장'));
    for (const attachment of attachments) gallery.append(imageCard(attachment, '', attachments, group));
    if (viewerGroup === group && $('image-dialog').open) {
      viewerImages = attachments.filter(a => C.safeImageUrl(a.url));
      viewerIndex = Math.max(0, viewerImages.findIndex(a => a.id === openedImage?.id)); updateViewer(false);
    }
    return gallery;
  }
  function sentAttachments(attachments) {
    const wrap = node('div', null, 'sent-attachments');
    for (const attachment of attachments || []) {
      if (attachment.image?.url || attachment.url) wrap.append(imageCard(attachment.image || attachment, attachment.name));
      else {
        const b = button(attachment.name || t('첨부 파일'), () => call('attachments.export', {id:attachment.id, name:attachment.name}), 'attachment-download');
        b.prepend(icon('file')); wrap.append(b);
      }
    }
    return wrap;
  }
  function imageReference(target, path, label) {
    if (!C.localImagePath(path)) { target.append(node('span', label || path)); return; }
    let gallery = target.lastElementChild;
    if (!gallery?.classList.contains('markdown-gallery')) { gallery = node('div', null, 'image-gallery markdown-gallery'); gallery.attachments = []; target.append(gallery); }
    const index = gallery.attachments.length; gallery.attachments.push(null); gallery.classList.toggle('multiple', gallery.attachments.length > 1);
    const placeholder = node('div', t('이미지 불러오는 중…'), 'image-placeholder'); gallery.append(placeholder);
    const key = (state.workspace.key || state.cwd || '') + '\n' + path;
    if (!imageReads.has(key)) {
      const request = call('images.read', {path}); imageReads.set(key, request);
      request.catch(() => imageReads.delete(key));
    }
    imageReads.get(key).then(attachment => {
      if (placeholder.isConnected) { gallery.attachments[index] = attachment; placeholder.replaceWith(imageCard(attachment, label, gallery.attachments)); }
    }).catch(e => { if (placeholder.isConnected) placeholder.textContent = t('이미지를 불러오지 못했습니다: ') + e.message; });
  }
  async function copyText(text, btn, normalText = t('복사'), copiedText = t('복사됨!')) {
    const finish = () => {
      btn.textContent = copiedText;
      setTimeout(() => {
        if (btn.isConnected) {
          btn.textContent = normalText;
          btn.prepend(icon('copy'));
        }
      }, 2000);
    };
    try {
      let copied = false;
      if (navigator.clipboard?.writeText) {
        try { await navigator.clipboard.writeText(text); copied = true; } catch { /* Try the native clipboard below. */ }
      }
      if (!copied && window.Native) {
        await call('ui.copyCode', {code: text}); copied = true;
      } else if (!copied && document.execCommand) {
        const ta = document.createElement('textarea');
        const focused = document.activeElement;
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        try { copied = document.execCommand('copy'); }
        finally { ta.remove(); focused?.focus(); }
      }
      if (!copied) throw new Error('Clipboard unavailable');
      finish();
    } catch {
      toast(t('클립보드에 복사하지 못했습니다.'));
    }
  }
  function codeCard(lang, codeText) {
    const card = node('div', null, 'code-card');
    const head = node('div', null, 'code-head');
    const langSpan = node('span', (lang || 'code').trim().toLowerCase(), 'code-lang');
    const copy = button(t('복사'), () => copyText(codeText, copy), 'code-copy-btn');
    copy.setAttribute('aria-label', t('코드 복사'));
    copy.prepend(icon('copy'));
    head.append(langSpan, copy);
    const pre = node('pre');
    pre.append(node('code', codeText));
    card.append(head, pre);
    return card;
  }
  function parseMarkdownBlocks(text) {
    const blocks = [];
    const codeFence = /```([^\n]*)\n([\s\S]*?)(?:```|$)/g;
    let lastIndex = 0, match;
    while ((match = codeFence.exec(text)) !== null) {
      if (match.index > lastIndex) {
        blocks.push({type:'prose', content: text.slice(lastIndex, match.index)});
      }
      blocks.push({type:'code', lang: match[1], content: match[2]});
      lastIndex = codeFence.lastIndex;
    }
    if (lastIndex < text.length) {
      blocks.push({type:'prose', content: text.slice(lastIndex)});
    }
    return blocks;
  }
  function tableCells(line) {
    const cells = [''];
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (ch === '\\' && (line[i + 1] === '|' || line[i + 1] === '\\')) cells[cells.length - 1] += line[++i];
      else if (ch === '|') cells.push('');
      else cells[cells.length - 1] += ch;
    }
    return cells.slice(1, -1);
  }
  function renderProseLines(target, content) {
    let paragraph = null, list = null;
    const lines = content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (!line.trim()) { paragraph = null; list = null; continue; }
      const image = line.match(/^\s*!\[([^\]]*)\]\((?:<([^>]+)>|([^\n]+))\)\s*$/);
      if (image) { imageReference(target, image[2] || image[3], image[1]); paragraph = null; list = null; continue; }
      const isTableRow = /^\s*\|(.+)\|\s*$/.test(line);
      const nextLine = lines[i + 1] || '';
      const isDelimiter = /^\s*\|(?:\s*:?-+:?\s*\|)+\s*$/.test(nextLine);
      if (isTableRow && isDelimiter) {
        paragraph = null; list = null;
        const wrap = node('div', null, 'table-wrap');
        const table = node('table', null, 'markdown-table');
        const thead = node('thead'), headerRow = node('tr');
        const headerCells = tableCells(line);
        for (const cell of headerCells) {
          const th = node('th'); inline(th, cell.trim()); headerRow.append(th);
        }
        thead.append(headerRow); table.append(thead);
        i++;
        const tbody = node('tbody');
        while (i + 1 < lines.length && /^\s*\|(.+)\|\s*$/.test(lines[i + 1])) {
          i++;
          const tr = node('tr');
          const cells = tableCells(lines[i]);
          for (let c = 0; c < headerCells.length; c++) {
            const td = node('td'); inline(td, (cells[c] || '').trim()); tr.append(td);
          }
          tbody.append(tr);
        }
        table.append(tbody); wrap.append(table);
        target.append(wrap);
        continue;
      }
      if (/^\s*(?:---+|\*\*\*+|___+)\s*$/.test(line)) {
        target.append(node('hr', null, 'prose-hr'));
        paragraph = null; list = null;
        continue;
      }
      const item = line.match(/^\s*(?:[-*+] |\d+[.)] )(.*)$/), heading = line.match(/^#{1,6} (.*)$/);
      if (item) {
        const tag = /^\s*\d/.test(line) ? 'ol' : 'ul';
        if (!list || list.tagName.toLowerCase() !== tag) { list = node(tag); target.append(list); }
        const li = node('li'); inline(li, item[1]); list.append(li); paragraph = null;
      } else if (heading || line.startsWith('> ')) {
        const block = node(heading ? 'h3' : 'blockquote'); inline(block, heading ? heading[1] : line.slice(2)); target.append(block); paragraph = null; list = null;
      } else {
        list = null;
        if (!paragraph) { paragraph = node('p', null, 'prose-line'); target.append(paragraph); }
        else paragraph.append(document.createTextNode('\n'));
        inline(paragraph, line);
      }
    }
  }
  function prose(target, text) {
    target.replaceChildren();
    // Deliberately construct text nodes: model output and file names cannot inject HTML.
    const blocks = parseMarkdownBlocks(text);
    for (const block of blocks) {
      if (block.type === 'code') target.append(codeCard(block.lang, block.content));
      else renderProseLines(target, block.content);
    }
  }
  function drawStatusIcons() {
    for (const [id, name] of [['welcome-character','greeting'], ['activity-character',activityIcon]]) {
      const host = $(id); host.hidden = !chatIconsEnabled;
      if (!chatIconsEnabled) host.replaceChildren();
      else if (host.dataset.icon !== name || host.dataset.pack !== characterVisualKey() || !host.firstChild) { host.replaceChildren(characterIcon(name)); host.dataset.icon = name; host.dataset.pack = characterVisualKey(); }
    }
  }
  function setChatIcons() {
    chatIconsEnabled = $('chat-icons-toggle').checked;
    try { localStorage.setItem('chat-icons', chatIconsEnabled ? 'on' : 'off'); } catch { toast(t('아이콘 설정을 저장하지 못했습니다.')); }
    drawStatusIcons(); drawMessages();
  }
  function drawMessages() {
    const messages = chatMode ? chatMessages : (state.messages || []);
    const elements = new Map(Array.from($('messages').children).map(n => [n.dataset.id, n]));
    const keep = new Set();
    C.groupImageMessages(messages).forEach(m => {
      keep.add(m.id); let el = elements.get(m.id);
      if (!el) { el = node('article', null, 'message ' + (m.role === 'user' ? 'user' : 'assistant')); el.dataset.id = m.id; $('messages').append(el); }
      const iconName = m.imageStatus === 'generating' ? 'working' : (chatMode ? chatBusy : state.busy) && m.id === messages[messages.length - 1]?.id ? 'thinking' : C.messageIcon(m);
      const signature = JSON.stringify([m.text, m.images, m.attachments, m.imageStatus, m.imageError, L.language(), chatIconsEnabled, iconName, characterVisualKey()]);
      if (el.dataset.signature !== signature) {
        el.dataset.signature = signature;
        if (m.role === 'user') { el.textContent = m.text; if (m.attachments?.length) el.append(sentAttachments(m.attachments)); }
        else {
          const previousIcon = el.querySelector('.chat-character');
          prose(el, m.text || '');
           if (chatIconsEnabled) el.prepend(previousIcon?.getAttribute('src') === characterIconUrl(iconName) ? previousIcon : characterIcon(iconName));
          if (m.images?.length) el.append(imageGallery(m.images, m.id));
          if (m.imageStatus === 'generating') el.append(node('p', t('이미지 생성 중…'), 'image-placeholder'));
          if (m.imageError) el.append(node('p', m.imageError, 'image-error'));
          if (m.text?.trim() && m.imageStatus !== 'generating') {
            const actions = node('div', null, 'message-actions');
            const copy = button(t('복사'), () => copyText(m.text, copy), 'msg-copy-btn');
            copy.setAttribute('aria-label', t('메시지 복사'));
            copy.prepend(icon('copy'));
            actions.append(copy);
            el.append(actions);
          }
        }
      }
    });
    for (const [id, el] of elements) if (!keep.has(id)) el.remove();
    if (following) scrollLatest();
    else $('jump-latest').hidden = !messages.length;
  }
  function efforts() {
    const selected = state.models.find(m => (m.model || m.id) === $('model').value) || state.models.find(m => m.isDefault);
    const previous = $('effort').value; $('effort').replaceChildren(new Option(t('기본'), ''));
    for (const e of selected?.supportedReasoningEfforts || []) $('effort').add(new Option(e.reasoningEffort, e.reasoningEffort));
    if ([...$('effort').options].some(o => o.value === previous)) $('effort').value = previous;
    optionsSummary();
  }
  function renderModelList() {
    const target = $('model-list'); target.replaceChildren();
    const choices = [{id:'', label:t('기본 모델'), description:t('기본 모델을 사용합니다.')}, ...state.models.map(m => ({id:m.model || m.id, label:m.displayName || m.model || m.id, description:m.description || m.model || m.id}))];
    for (const choice of choices) {
      const label = node('label', null, 'model-choice'); const input = node('input'); input.type = 'radio'; input.name = 'model-choice'; input.value = choice.id; input.checked = $('model').value === choice.id;
      input.addEventListener('change', () => { $('model').value = choice.id; $('model').dispatchEvent(new Event('change')); renderModelList(); });
      const text = node('span'); text.append(node('strong', choice.label), node('small', choice.description)); label.append(input, text); target.append(label);
    }
  }
  function renderDevtools() {
    const info = state.devtools;
    const versions = $('devtools-versions'); versions.replaceChildren();
    if (!info || typeof info !== 'object') {
      $('devtools-status').textContent = t('개발 도구 정보를 제공하지 않는 앱 버전입니다. 실행 확인으로 현재 상태를 점검할 수 있습니다.');
      $('terminal-tools-note').textContent = t('현재 프로젝트 폴더에서 셸 명령을 실행합니다. 개발 도구 상태는 설정 > 도구에서 확인하세요.');
    } else {
      if (info.error) $('devtools-status').textContent = t('준비 오류: ') + info.error;
      else if (!info.bundled) $('devtools-status').textContent = t('번들 개발 도구가 없습니다.');
      else if (info.prepared) $('devtools-status').textContent = t('번들 준비 완료. 아래 실행 확인으로 현재 상태를 검증하세요.');
      else $('devtools-status').textContent = t('번들 개발 도구가 준비 중이거나 아직 확인되지 않았습니다.');
      for (const tool of Array.isArray(info.tools) ? info.tools : []) {
        const row = node('span', (tool?.name || t('도구')) + (tool?.version ? ' ' + tool.version : t(' 버전 확인 전')), 'devtools-version');
        versions.append(row);
      }
      const names = (Array.isArray(info.tools) ? info.tools.map(tool => tool?.name).filter(Boolean) : []).join(' · ');
      $('terminal-tools-note').textContent = info.bundled
        ? t('현재 프로젝트 폴더에서 {tools} 명령을 실행합니다.', {tools:names || 'Python · Node.js · Git · npm · pip'})
        : t('현재 프로젝트 폴더에서 셸 명령을 실행합니다. 번들 개발 도구는 사용할 수 없습니다.');
    }
    if (devtoolsCheckSummary) $('devtools-status').textContent = devtoolsCheckSummary;
    const output = $('devtools-output');
    output.hidden = !devtoolsCheckResult;
    output.textContent = devtoolsCheckResult || '';
    $('devtools-check').disabled = devtoolsChecking;
    $('devtools-check').textContent = devtoolsChecking ? t('실행 확인 중…') : t('도구 실행 확인');
  }
  async function checkDevtools() {
    devtoolsChecking = true; renderDevtools();
    try {
      const result = await call('devtools.check');
      const checks = Array.isArray(result?.checks) ? result.checks : [];
      devtoolsCheckResult = checks.length
        ? checks.map(check => `${check?.ok ? '✓' : '✕'} ${check?.name || t('도구')}\n${check?.output || ''}`).join('\n\n')
        : (result?.ok ? t('도구 실행 확인이 완료되었습니다.') : t('실행 결과를 받지 못했습니다. 다시 시도해 주세요.'));
      devtoolsCheckSummary = result?.ok ? t('도구 실행 확인을 완료했습니다.') : t('일부 도구를 실행하지 못했습니다. 출력에서 원인을 확인한 뒤 다시 시도해 주세요.');
    } catch (error) {
      devtoolsCheckResult = t('실행 확인 실패\n') + (error?.message || t('알 수 없는 오류'));
      devtoolsCheckSummary = t('실행 확인에 실패했습니다. 다시 시도할 수 있습니다.');
    } finally {
      devtoolsChecking = false; renderDevtools();
    }
  }
  async function selectProject(key) {
    await call('projects.select', {key:key || ''}); sidebar(false);
  }
  function persistChatMessages() {
    chatMessages = chatMessages.slice(-100);
    try { localStorage.setItem('chat-web-messages', JSON.stringify(chatMessages)); } catch { toast('Chat 대화의 기기 내 표시 기록을 저장하지 못했습니다.'); }
  }
  function renderChatMode() {
    document.body.classList.toggle('chat-mode', chatMode);
    $('mode-chat').setAttribute('aria-pressed', String(chatMode));
    $('mode-codex').setAttribute('aria-pressed', String(!chatMode));
    $('header-project').textContent = chatMode ? 'Chat' : $('header-project').textContent;
    $('header-title').textContent = chatMode ? 'ChatGPT' : $('header-title').textContent;
    $('welcome').hidden = chatMessages.length > 0;
    $('welcome-title').textContent = '무엇이든 물어보세요';
    $('welcome-description').textContent = '일반 ChatGPT 대화입니다. 아래 입력창에서 보내고 답변을 받습니다.';
    $('prompt').placeholder = 'ChatGPT에게 물어보세요';
    $('prompt').setAttribute('aria-label', 'ChatGPT에게 질문');
    $('chat-model-summary').textContent = chatModel || 'Chat 모델 설정';
    $('activity').hidden = !chatBusy;
    $('activity-text').textContent = 'ChatGPT 답변 기다리는 중';
    $('stop').hidden = true;
    $('file-panel').hidden = true;
    hideAutocomplete();
    renderDraftContext();
    drawMessages(); updateSend(); sizeComposer();
  }
  function switchMode(mode) {
    const next = mode === 'chat';
    if (chatMode === next) return;
    saveDraft();
    chatMode = next;
    try { localStorage.setItem('conversation-mode', chatMode ? 'chat' : 'codex'); } catch {}
    $('messages').replaceChildren(); following = true;
    if (chatMode) {
      try { $('prompt').value = localStorage.getItem('chat-web-draft') || ''; } catch { $('prompt').value = ''; }
      renderChatMode();
      call('chat.web.prepare').catch(error => toast(error.message));
    } else {
      render(state);
      try { $('prompt').value = localStorage.getItem(draftScope) || ''; } catch { $('prompt').value = ''; }
      $('welcome-title').textContent = '어떤 작업을 할까요?';
      $('welcome-description').textContent = '아이디어부터 코드, 기기의 파일까지. Codex와 함께 작업하세요.';
      $('prompt').placeholder = '무엇이든 요청하세요. @ 파일·앱, $ 스킬';
      $('prompt').setAttribute('aria-label', 'Codex에게 요청');
      sizeComposer();
    }
    sidebar(false);
  }
  async function newChat(workspaceKey) {
    if (chatMode) {
      if (chatBusy) throw new Error('ChatGPT 답변을 기다리는 중입니다.');
      await call('chat.web.new'); chatMessages = []; persistChatMessages();
      $('prompt').value = ''; saveDraft(); renderChatMode(); sidebar(false); return;
    }
    await call('chat.new', {workspaceKey:workspaceKey || ''}); sidebar(false);
  }
  async function removeProject(project) {
    if (state.busy || !confirm(t('“{name}”을 프로젝트 목록에서 제거할까요?\n\n폴더와 파일은 삭제되지 않습니다. 이 프로젝트의 기존 대화는 “연결 해제된 프로젝트”에 남아 폴더를 다시 연결할 수 있습니다.', {name:project.name || t('프로젝트')}))) return;
    await call('projects.remove', {key:project.key});
    toast(t('프로젝트 목록에서 제거했습니다. 폴더와 파일은 그대로 있습니다.'));
  }
  async function renameProject(project) {
    if (state.busy) return;
    const name = await input(t('프로젝트 이름 변경'), t('새 프로젝트 표시 이름을 입력하세요.'), project.name || '');
    if (!name || !name.trim() || name.trim() === project.name) return;
    const result = await call('projects.rename', {key:project.key, name:name.trim()});
    const projects = (state.projects || []).map(value => value.key === project.key ? {...value, name:result.name || name.trim()} : value);
    const workspace = state.workspace?.key === project.key ? {...state.workspace, name:result.name || name.trim()} : state.workspace;
    render({...state, projects, workspace});
    toast(t('프로젝트 이름을 변경했습니다.'));
  }
  function projectAction(project, trigger) {
    projectMenuFocus = trigger;
    $('project-actions-title').textContent = project.name || t('프로젝트');
    const action = (label, fn, cls) => { const item = button(label, async () => { projectMenuActionClosing = true; close('project-actions-dialog'); await fn(); }, cls); item.disabled = !!state.busy; return item; };
    const actions = [
      action(t('새 대화'), () => newChat(project.key)),
      action(t('이름 변경'), () => renameProject(project))
    ];
    if (project.available === false) actions.push(action(t('폴더 다시 연결'), () => pickFolder(project.key)));
    actions.push(action(t('프로젝트 목록에서 제거'), () => removeProject(project), 'secondary-button danger'));
    $('project-actions').replaceChildren(...actions);
    show('project-actions-dialog');
  }
  function sessionAction(session) {
    $('session-actions-title').textContent = session.title || t('대화 관리');
    const action = (label, fn, cls) => button(label, async () => { close('session-actions-dialog'); await fn(); }, cls);
    $('session-actions').replaceChildren(
      action(t('이름 변경'), async () => {
        const title = await input(t('대화 이름 변경'), t('새 제목을 입력하세요.'), session.title || '');
        if (title && title.trim()) {
          await call('chat.rename', {id: session.id, title: title.trim()});
          toast(t('대화 이름을 변경했습니다.'));
        }
      }),
      action(t('삭제'), async () => {
        if (confirm('"' + (session.title || t('대화')) + t('" 대화를 삭제할까요?'))) {
          const result = await call('chat.delete', {id: session.id});
          toast(result.deletionPending ? t('삭제 요청을 저장했습니다. Codex에 연결되면 원본 기록도 삭제합니다.') : t('대화를 삭제했습니다.'));
        }
      }, 'secondary-button danger')
    );
    show('session-actions-dialog');
  }
  function sessionRow(session) {
    const active = session.id === state.threadId;
    const row = node('div', null, 'session-row' + (active ? ' active' : ''));
    const b = button('', async () => { await call('chat.resume', {id:session.id}); sidebar(false); nextRequest(); }, 'session' + (active ? ' active' : ''));
    b.append(node('span', session.title || t('제목 없는 대화'), 'session-title'));
    if (session.approvalPending) b.append(node('span', '!', 'session-approval-indicator'));
    else if (session.busy) b.append(node('span', '', 'session-progress-indicator'));
    b.title = session.workspace || t('일반 대화');
    const menu = button('', () => sessionAction(session), 'icon-button session-more');
    menu.setAttribute('aria-label', (session.title || t('대화')) + t(' 관리'));
    menu.append(icon('more'));
    row.append(b, menu);
    return row;
  }
  function renderProjects() {
    const target = $('projects'); target.replaceChildren();
    const projects = state.projects || [];
    for (const project of projects) {
      const expandedKey = 'project-expanded:' + project.key, expanded = localStorage.getItem(expandedKey) !== 'false';
      const section = node('section', null, 'project-tree' + (project.selected ? ' selected' : ''));
      const row = node('div', null, 'project-row');
      const toggle = button('', () => { const expandedNow = section.classList.toggle('collapsed') === false; localStorage.setItem(expandedKey, String(expandedNow)); toggle.setAttribute('aria-expanded', String(expandedNow)); }, 'tree-toggle'); toggle.setAttribute('aria-label', project.name + t(' 대화 펼치기')); toggle.setAttribute('aria-expanded', String(expanded)); toggle.append(icon('down'));
      const select = button('', () => selectProject(project.key), 'project-button'); select.append(icon('folder'), node('span', project.name || t('이름 없는 프로젝트'))); select.setAttribute('aria-current', String(!!project.selected));
       const menu = button('', () => projectAction(project, menu), 'icon-button project-more'); menu.disabled = !!state.busy; menu.setAttribute('aria-label', project.name + t(' 메뉴')); menu.dataset.projectMenuKey = project.key; menu.append(icon('more'));
       const projectNew = button('', () => newChat(project.key), 'icon-button project-new'); projectNew.setAttribute('aria-label', project.name + t(' 새 대화')); projectNew.append(icon('plus'));
       row.append(toggle, select, projectNew, menu); section.append(row);
      const children = node('div', null, 'project-sessions');
      if (project.available === false) { children.append(node('p', t('폴더 접근을 다시 연결해야 합니다.'), 'sidebar-empty'), button(t('폴더 다시 연결'), () => pickFolder(project.key), 'new-thread')); }
      for (const session of (state.sessions || []).filter(s => s.workspaceKey === project.key)) children.append(sessionRow(session));
      section.append(children); if (!expanded) section.classList.add('collapsed'); target.append(section);
    }
    const detached = (state.sessions || []).filter(s => s.workspaceKey && !projects.some(p => p.key === s.workspaceKey));
    if (detached.length) {
      const section = node('section', null, 'project-tree detached-projects'); section.append(node('div', t('연결 해제된 프로젝트'), 'section-title'));
      const children = node('div', null, 'project-sessions');
      children.append(node('p', t('폴더와 파일은 남아 있습니다. 대화를 열면 읽기 전용으로 보존되며, 새 작업은 폴더를 다시 연결한 뒤 시작할 수 있습니다.'), 'sidebar-empty'));
      for (const session of detached) {
        const row = node('div', null, 'detached-session'); const reconnect = button(t('폴더 다시 연결'), () => pickFolder(session.workspaceKey), 'new-thread');
        row.append(sessionRow(session), reconnect); children.append(row);
      }
      section.append(children); target.append(section);
    }
    if (!projects.length) target.append(button(t('작업 폴더 선택'), pickFolder, 'project-button'));
  }
  function renderPhone() {
    const phone = state.phone || {};
    $('phone-status').textContent = phone.status || t('접근성 설정에서 Mobile Codex 휴대폰 제어를 켜 주세요.');
    $('phone-enable').disabled = !phone.connected || !!phone.enabled; $('floating-chat').disabled = !phone.connected;
    $('phone-enable').hidden = !!phone.enabled;
    $('phone-disable').hidden = !phone.enabled;
    $('phone-stop-banner').hidden = !phone.enabled;
    $('phone-thread-note').hidden = state.phoneToolsAvailable !== false;
    $('phone-screenshot-note').textContent = phone.screenshotsSupported === false ? t('Android 10에서는 화면 요소를 읽어 조작합니다. 스크린샷은 Android 11 이상에서 지원합니다.') : t('화면 위의 중지 버튼으로 즉시 끌 수 있습니다. 앱 프로세스가 다시 시작되면 제어는 꺼집니다.');
  }
  async function enablePhone() {
    const result = await call('ui.phoneEnable');
    if (!result.cancelled) { state.phone = result; renderPhone(); }
  }
  async function stopPhone() {
    await call('phone.stop');
    state.phone = {...state.phone, enabled:false, status:t('휴대폰 제어 꺼짐')}; renderPhone();
  }
  function render(next) {
    if (chatMode) { state = next; state.messages ||= []; state.models ||= []; state.projects ||= []; state.accounts ||= []; state.workspace ||= {}; state.account ||= {}; state.rateLimits ||= {}; setCharacterState(state.characters); return; }
    const previousAccountScope = accountScope(state.account), nextAccountScope = accountScope(next.account);
    if (previousAccountScope !== nextAccountScope) { usageGeneration++; resetCredits = null; resetCreditBusy = false; resetCreditIdempotencyKey = ''; resetCreditSelectedId = ''; resetCreditScope = nextAccountScope; }
    const changedThread = state.threadId !== next.threadId;
    setDraftScope(next); state = next;
    state.models ||= []; state.messages ||= []; state.projects ||= []; state.accounts ||= []; state.workspace ||= {}; state.account ||= {}; state.rateLimits ||= {}; setCharacterState(state.characters);
    const logged = C.isLoggedIn(state.account), selected = state.workspace.selected;
    const name = selected ? state.workspace.name : t('일반 대화');
    $('project-label').textContent = name; $('context-folder').textContent = name; $('header-project').textContent = name;
    $('header-title').textContent = (state.sessions || []).find(s => s.id === state.threadId)?.title || t('새 대화');
    $('welcome').hidden = state.messages.length > 0; $('onboarding').hidden = logged; $('suggestions').hidden = !logged;
    const generalPrompts = [t('작업을 계획하고 필요한 정보를 정리해줘.'),t('아이디어를 구조화하고 다음 단계를 제안해줘.'),t('무엇을 도와줄 수 있는지 알려줘.')];
    document.querySelectorAll('[data-prompt]').forEach((button, i) => { button.dataset.prompt = selected ? [t('이 폴더에 어떤 파일이 있는지 살펴보고 정리해줘.'),t('이 프로젝트를 살펴보고 실행 방법과 개선할 부분을 알려줘.'),t('이 프로젝트의 변경 사항을 검토하고 버그가 있는지 찾아줘.')][i] : generalPrompts[i]; if (button.lastChild?.nodeType === Node.TEXT_NODE) button.lastChild.textContent = selected ? [t('폴더 살펴보기'),t('프로젝트 이해하기'),t('변경 사항 검토')][i] : [t('작업 계획하기'),t('아이디어 정리하기'),t('도움말 보기')][i]; });
    $('login-step').textContent = logged ? '✓' : '1'; $('login-step').classList.toggle('done', logged);
    $('folder-step').textContent = selected ? '✓' : '2'; $('folder-step').classList.toggle('done', !!selected);
    renderQuota(); renderAccounts(); renderResetCredits(resetCredits);
    $('settings-status').textContent = state.status; $('settings-indicator').textContent = state.ready ? t('연결됨') : t('연결 안 됨'); $('settings-indicator').classList.toggle('online', !!state.ready);
    const pendingDeletes = Number(state.pendingDeletionCount) || 0;
    $('pending-deletions').hidden = pendingDeletes === 0;
    $('pending-deletions').textContent = pendingDeletes ? t('원본 삭제 대기 {count}건. 아래 ‘Codex 시작 / 다시 연결’을 누르면 다시 시도합니다.', {count:pendingDeletes}) : '';
    $('send').hidden = false; $('stop').hidden = !state.busy; $('activity').hidden = !state.busy;
    if (!state.busy) activityIcon = 'thinking';
    drawStatusIcons();
    syncPermissionControls(state.permissions, !!state.busy);
    $('approval-mode').value = state.approvalMode || 'auto-review'; $('approval-mode').disabled = !!state.busy;
    $('terminal-cwd').textContent = state.cwd || '';
    $('storage-status').textContent = state.directWorkspace ? t('선택한 폴더에서 셸 명령을 실행합니다.') : t('폴더의 셸 접근은 기기 파일 권한이 필요합니다. 문서 제공자 폴더는 파일 도구로 접근합니다.');
    $('storage-access').textContent = state.allFilesAccess ? t('기기 파일 접근 설정') : t('기기 파일 접근 허용');
    renderDevtools();
    renderPhone();
    if (changesScope !== reviewScope()) { changesGeneration++; changesScope = reviewScope(); selectedChange = null; $('change-preview').hidden = true; $('change-restore').disabled = true; $('changes-list').replaceChildren(); if ($('changes-dialog').open) $('changes-status').textContent = t('대화 또는 프로젝트가 바뀌었습니다. 새로고침해 주세요.'); }
    $('changes-turn-diff').textContent = state.turnDiff || t('이 대화에 기록된 작업 diff가 없습니다.');
    if (selectedChange) $('change-restore').disabled = restoringChange || !selectedChange.data.canRestore || !!state.busy || state.permissions === 'read-only';
    renderProjects();
    if ($('project-actions-dialog')?.open) $('project-actions').querySelectorAll('button').forEach(button => { button.disabled = !!state.busy; });
    $('sessions').replaceChildren();
    for (const session of (state.sessions || []).filter(s => !(s.workspaceKey || s.workspace))) $('sessions').append(sessionRow(session));
    if (!state.sessions?.length) $('sessions').append(node('p', t('대화를 시작하면 여기에 표시됩니다.'), 'sidebar-empty'));
    const key = JSON.stringify(state.models);
    if (key !== modelKey) { modelKey = key; const value = draftOptions.model || $('model').value; $('model').replaceChildren(new Option(t('기본 모델'), '')); for (const m of state.models) $('model').add(new Option(m.displayName || m.model || m.id, m.model || m.id)); if ([...$('model').options].some(o => o.value === value)) $('model').value = value; efforts(); if ([...$('effort').options].some(o => o.value === draftOptions.effort)) $('effort').value = draftOptions.effort; }
    renderModelList();
    if (changedThread) { following = true; $('event-log').replaceChildren(); $('messages').replaceChildren(); }
    updateSend(); optionsSummary(); drawUpdates(updateState);
    drawMessages();
    // A request can arrive while another conversation is visible. Once the
    // selected session is restored, show only that session's pending request.
    nextRequest();
    if (logged && $('login-dialog').open) { login = null; close('login-dialog'); }
  }
  function logEvent(title, data) {
    const detail = node('details', null, 'event-card'); detail.append(node('summary', title), node('pre', typeof data === 'string' ? data : JSON.stringify(data, null, 2), 'console'));
    $('event-log').append(detail);
    while ($('event-log').children.length > 150) $('event-log').firstChild.remove();
  }
  async function startLogin(add = false) {
    if (C.isLoggedIn(state.account) && !add) return openAccountSettings();
    show('login-dialog'); $('device-code').textContent = t('연결 준비 중'); $('open-login').disabled = true;
    login = await call(add ? 'auth.add' : 'auth.login');
    if (!C.safeLoginUrl(login.verificationUrl)) throw new Error(t('잘못된 로그인 응답입니다.'));
    $('device-code').textContent = login.userCode; $('open-login').disabled = false;
  }
  async function pickFolder(projectKey = '') { const result = await call('files.pick', projectKey ? {projectKey} : {}); if (!result.cancelled) { folder = ''; if (!$('file-panel').hidden) await listFiles(); } }
  async function listFiles(query = '') {
    if (!state.workspace.selected) { $('file-list').replaceChildren(button(t('작업 폴더 선택'), pickFolder)); return; }
    const version = ++fileSeq; $('file-list').replaceChildren(node('p', t('불러오는 중…'), 'empty-note'));
    const result = await call(query ? 'files.search' : 'files.list', query ? {query} : {path: folder});
    if (version !== fileSeq) return;
    $('breadcrumbs').textContent = query ? t('검색: ') + query : state.workspace.name + (folder ? ' / ' + folder : '');
    $('file-up').disabled = !folder || !!query; $('file-list').replaceChildren();
    for (const entry of result.entries || []) {
      const row = node('div', null, 'file-entry');
      const b = button('', async () => { if (entry.directory) { folder = entry.path; $('file-query').value = ''; await listFiles(); } else await openFile(entry.path); }, 'file-row');
      b.append(icon(entry.directory ? 'folder' : 'file'), node('span', entry.name), node('small', entry.directory ? '' : C.size(entry.size))); row.append(b);
      const menu = button('⋯', () => fileAction(entry), 'icon-button'); menu.setAttribute('aria-label', entry.name + t(' 관리')); row.append(menu); $('file-list').append(row);
    }
    if (!result.entries?.length) $('file-list').append(node('p', t('항목이 없습니다.'), 'empty-note'));
    if (result.truncated) $('file-list').append(node('p', t('일부 검색 결과입니다. 더 구체적인 이름으로 검색하세요.'), 'empty-note'));
  }
  async function openFile(path) {
    if (/\.(png|jpe?g|webp|gif|bmp|heic|heif|avif)$/i.test(path)) { previewImage(await call('images.read', {path})); return; }
    openedFile = await call('files.read', {path}); $('editor-title').textContent = path; $('editor').value = openedFile.content; show('editor-dialog');
  }
  function input(title, description, value = '') {
    if (inputResolve) inputResolve(null);
    $('input-title').textContent = title; $('input-description').textContent = description; $('input-value').value = value; show('input-dialog'); $('input-value').focus();
    return new Promise(resolve => inputResolve = resolve);
  }
  async function mutate(operation, args) { const result = await call('files.mutate', {operation, arguments: args}); toast(t('적용했습니다.')); if (!$('file-panel').hidden) await listFiles(); return result; }
  function fileAction(entry) {
    $('file-actions-title').textContent = entry.name;
    const action = (label, fn, cls) => button(label, async () => { close('file-actions-dialog'); await fn(); }, cls);
    $('file-actions').replaceChildren(
      action(t('이름 변경'), async () => { const name = await input(t('이름 변경'), t('새 이름'), entry.name); if (name) await mutate('mobile_rename', {path:entry.path, name}); }),
      action(t('이동'), async () => { const destination = await input(t('이동'), t('작업 폴더 기준 대상 폴더 경로 (루트는 빈칸)')); if (destination !== null) await mutate('mobile_move', {path:entry.path, destination}); }),
      action(t('삭제'), () => mutate('mobile_delete', {path:entry.path}), 'secondary-button danger')
    );
    show('file-actions-dialog');
  }
  async function loadInstructions() {
    if (instructionsSaving) return;
    if (instructionsLoaded && $('instructions-editor').value !== instructionsOriginal && !confirm(t('저장하지 않은 지침을 다시 불러올까요?'))) return;
    $('instructions-status').textContent = t('지침을 불러오는 중…');
    $('instructions-editor').disabled = true; $('instructions-save').disabled = true;
    try {
      const result = await call('instructions.read');
      instructionsOriginal = result.content || ''; $('instructions-editor').value = instructionsOriginal;
      instructionsLoaded = true; $('instructions-path').textContent = t('저장 위치: ') + (result.activePath || result.path || '.codex/AGENTS.md');
      $('instructions-status').textContent = result.notice || t('저장된 지침을 편집할 수 있습니다.');
    } catch (error) { $('instructions-status').textContent = t('지침을 불러오지 못했습니다. ') + error.message; }
    finally { $('instructions-editor').disabled = !instructionsLoaded; $('instructions-save').disabled = !instructionsLoaded; }
  }
  async function saveInstructions() {
    if (!instructionsLoaded || instructionsSaving) return;
    const content = $('instructions-editor').value; instructionsSaving = true;
    $('instructions-save').disabled = true; $('instructions-status').textContent = t('저장 중…');
    try {
      const result = await call('instructions.save', {content});
      instructionsOriginal = typeof result.content === 'string' ? result.content : content;
      if ($('instructions-editor').value === content) $('instructions-editor').value = instructionsOriginal;
      if (result.activePath || result.path) $('instructions-path').textContent = t('저장 위치: ') + (result.activePath || result.path);
      $('instructions-status').textContent = t('저장했습니다. 다음 요청부터 적용됩니다.') + (result.notice ? ' ' + result.notice : '');
    } catch (error) { $('instructions-status').textContent = t('저장하지 못했습니다. 작성 내용은 그대로 남아 있습니다. ') + error.message; }
    finally { instructionsSaving = false; $('instructions-save').disabled = false; }
  }
  async function config() { const result = await call('config.read'); configOriginal = result.content; $('config-editor').value = result.content; show('config-dialog'); }
  let changesGeneration = 0, changesScope = '', selectedChange = null, restoringChange = false;
  const reviewScope = () => (state.workspace?.key || '') + '\0' + (state.threadId || '');
  const reviewArgs = extra => ({workspaceKey:state.workspace?.key || '', ...extra});
  function showFileDiff(before, after) {
    const a = String(before || '').split('\n'), b = String(after || '').split('\n');
    let first=0, last=0;
    while(first<a.length && first<b.length && a[first]===b[first]) first++;
    while(last<a.length-first && last<b.length-first && a[a.length-1-last]===b[b.length-1-last]) last++;
    const lines = [];
    for(let i=Math.max(0,first-3);i<first;i++) lines.push([' ',a[i]]);
    for(let i=first;i<a.length-last;i++) lines.push(['-',a[i]]);
    for(let i=first;i<b.length-last;i++) lines.push(['+',b[i]]);
    for(let i=0;i<Math.min(last,3);i++) lines.push([' ',a[a.length-last+i]]);
    const box=$('change-diff'); box.replaceChildren();
    for(const [sign,text] of lines.slice(0,2000)) box.append(node('span',sign+' '+text+'\n',sign==='-'?'diff-removed':sign==='+'?'diff-added':'diff-context'));
    if(lines.length>2000) box.append(node('span',t('\n… 미리보기 2,000줄 이후 생략. 파일 탐색기에서 전체 내용을 확인하세요.')));
    if(first===a.length && first===b.length) box.textContent=t('내용 차이가 없습니다.');
  }
  async function previewChange(kind, value) {
    const generation=++changesGeneration, scope=reviewScope(), args=reviewArgs(kind==='git'?{path:value}:{id:value});
    selectedChange=null; $('change-restore').disabled=true; $('changes-status').textContent=t('파일 내용을 확인하는 중…');
    const action=kind==='git'?'changes.preview':kind==='backup'?'changes.backupPreview':'recovery.preview';
    try {
      const data=await call(action,args); if(generation!==changesGeneration || scope!==reviewScope())return;
      selectedChange={kind,data,workspaceKey:args.workspaceKey};
      $('change-path').textContent=data.path; $('change-note').textContent=data.note || '';
      if (data.previewLimited) $('change-diff').textContent = t('텍스트 전체를 표시할 수 없는 파일입니다.\n이전: ') + data.before + '\nSHA-256: ' + data.beforeSha256 + t('\n현재: ') + data.after + '\nSHA-256: ' + data.afterSha256;
      else showFileDiff(data.before,data.after);
      $('change-preview').hidden=false;
      $('change-restore').textContent=data.actionLabel || t('복원'); $('change-restore').disabled=!data.canRestore || state.busy || state.permissions==='read-only';
      $('changes-status').textContent=(data.beforeExists===false?t('이전 파일 없음 · '):'')+(data.afterExists===false?t('현재 파일 없음'):'');
    } catch(error) { if(generation===changesGeneration) $('changes-status').textContent=error.message; }
  }
  async function loadChanges(history=false) {
    const generation=++changesGeneration, scope=reviewScope(), args=reviewArgs({}); changesScope=scope;
    selectedChange=null; $('change-preview').hidden=true; $('change-restore').disabled=true;
    show('changes-dialog'); sidebar(false); $('changes-list').replaceChildren(); $('changes-status').textContent=t('불러오는 중…');
    try {
      const result=await call(history?'changes.history':'changes.list',args);
      if(generation!==changesGeneration || scope!==reviewScope())return;
      $('changes-status').textContent=result.note || (history?t('파일 복원 직전의 사본입니다. 이후 수정된 파일은 자동으로 덮어쓰지 않습니다.'):'');
      for(const entry of result.entries || []) {
        const row=node('div',null,'recovery-item'), desc=node('div'); desc.append(node('strong',entry.path),node('small',history?new Date(entry.timestamp).toLocaleString(L.language()):entry.status));
        row.append(desc,button(t('비교'),()=>previewChange(history?'backup':'git',history?entry.id:entry.path))); $('changes-list').append(row);
      }
      if(!result.entries?.length) $('changes-list').append(node('p',history?t('복원 사본이 없습니다.'):t('현재 Git 변경 사항이 없습니다.'),'empty-note'));
    } catch(error) { if(generation===changesGeneration) $('changes-status').textContent=error.message+t(' 문서 제공자 파일은 복구 사본에서 확인할 수 있습니다.'); }
  }
  async function restoreChange() {
    const selected=selectedChange; if(!selected || !selected.data.canRestore || restoringChange)return;
    if(selected.workspaceKey!==(state.workspace?.key || ''))throw new Error(t('프로젝트가 바뀌었습니다.'));
    if(!confirm(selected.data.path+'\n'+(selected.data.actionLabel || t('복원'))+t('할까요?')))return;
    restoringChange=true; $('change-restore').disabled=true;
    try {
      const args={workspaceKey:selected.workspaceKey};
      if(selected.kind==='saf')args.id=selected.data.id;else args.token=selected.data.token;
      await call(selected.kind==='saf'?'recovery.restore':'changes.restore',args);
      toast(t('파일 내용을 복원했습니다.'));
      if(selected.workspaceKey===(state.workspace?.key || '')) { if(selected.kind==='saf'){close('changes-dialog');await recovery();}else await loadChanges(); if(!$('file-panel').hidden)await listFiles(); }
    } finally { restoringChange=false; if(selectedChange===selected)$('change-restore').disabled=false; }
  }
  async function recovery() {
    show('recovery-dialog'); const {entries} = await call('recovery.list'); $('recovery-list').replaceChildren();
    for (const entry of entries || []) { const row = node('div', null, 'recovery-item'), desc = node('div'); desc.append(node('strong', entry.path), node('small', new Date(entry.timestamp).toLocaleString(L.language()))); row.append(desc, button(t('비교 / 복원'), async () => { close('recovery-dialog'); show('changes-dialog'); await previewChange('saf',entry.id); }), button(t('다른 위치에 저장'), () => call('recovery.export', {id: entry.id, name: C.basename(entry.path)}))); $('recovery-list').append(row); }
    if (!entries?.length) $('recovery-list').append(node('p', t('저장된 복구 사본이 없습니다.'), 'empty-note'));
  }
  let toolsScope = "", toolsGeneration = 0;
  const currentToolsScope = () => JSON.stringify([state.cwd, state.threadId, state.account]);
  function usageTime(value) { return Number.isFinite(value) ? new Date(value * 1000).toLocaleString(L.language()) : t('알 수 없음'); }
  function usageDuration(value) { return Number.isFinite(value) ? (value >= 60 ? Math.round(value / 60) + t('시간') : value + t('분')) : t('기간 정보 없음'); }
  function usageRows(result) {
    const buckets = result?.rateLimitsByLimitId && Object.keys(result.rateLimitsByLimitId).length ? Object.entries(result.rateLimitsByLimitId) : [['codex', result?.rateLimits]];
    return buckets.filter(([, value]) => value && (value.primary || value.secondary)).flatMap(([key, value]) => [[t('기본'), value.primary], [t('보조'), value.secondary]].filter(([, window]) => window && Number.isFinite(window.usedPercent)).map(([label, window]) => {
      const remaining = Math.max(0, Math.min(100, 100 - window.usedPercent));
      return {label:(value.limitName || value.normalModelSlug || key) + ' · ' + label, used:window.usedPercent, remaining,
        duration:usageDuration(window.windowDurationMins), reset:usageTime(window.resetsAt),
        text:t('사용 ') + window.usedPercent + t('% · 남은 ') + remaining + '% · ' + usageDuration(window.windowDurationMins) + t(' · 재설정 ') + usageTime(window.resetsAt)};
    }));
  }
  function quotaRing(remaining, label, large = false) {
    const ring = node('span', null, 'quota-ring'); ring.style.setProperty('--remaining', String(remaining)); ring.setAttribute('role','img'); ring.setAttribute('aria-label', label);
    ring.append(node('span', large ? Math.round(remaining) + '%' : '')); return ring;
  }
  function renderQuota() {
    const logged = C.isLoggedIn(state.account), rows = usageRows(state.rateLimits), row = rows[0];
    const ring = $('quota-ring'), value = $('quota-ring-value'), percent = $('quota-percent'), caption = $('quota-caption');
    if (!logged) {
      ring.style.setProperty('--remaining','0'); ring.setAttribute('aria-label',t('사용 한도 정보 없음')); value.textContent=''; percent.textContent=t('계정 연결'); caption.textContent=t('ChatGPT로 로그인'); return;
    }
    if (!row) {
      ring.style.setProperty('--remaining','0'); ring.setAttribute('aria-label',t('사용 한도 정보 없음')); value.textContent=''; percent.textContent='–%'; caption.textContent=state.account.email || state.account.planType || t('한도 조회 전'); return;
    }
    const remaining = Math.round(row.remaining);
    ring.style.setProperty('--remaining',String(remaining)); ring.setAttribute('aria-label',t('남은 한도 ') + remaining + '%'); value.textContent='';
    percent.textContent=remaining + '%'; caption.textContent=t('남은 한도') + ' · ' + row.duration;
  }
  function renderUsage(rows) {
    const target = $('usage-limits'); target.replaceChildren();
    for (const row of rows) {
      const card = node('div', null, 'usage-gauge'), copy = node('span', null, 'usage-gauge-copy');
      copy.append(node('strong', row.label), node('small', t('남은 ') + Math.round(row.remaining) + '% · ' + row.duration + '\n' + t('재설정 ') + row.reset));
      card.append(quotaRing(row.remaining, row.label + ' ' + t('남은 ') + Math.round(row.remaining) + '%', true), copy); target.append(card);
    }
  }
  function accountScope(account = state.account) { return JSON.stringify(account || {}); }
  function resetResetCreditRequest(scope = accountScope()) {
    if (resetCreditScope !== scope) { resetCreditScope = scope; resetCreditIdempotencyKey = ''; resetCreditSelectedId = ''; }
  }
  function earliestResetCredit(data) {
    const count = data?.availableCount, credits = data?.credits;
    // The server can omit or cap details. In that case no client-side choice
    // can guarantee that a hidden credit does not expire sooner.
    if (!Number.isInteger(count) || count <= 0 || !Array.isArray(credits)) return null;
    const available = credits.filter(credit => credit?.status === 'available' && credit.resetType === 'codexRateLimits');
    if (available.length !== count || available.some(credit =>
      typeof credit.id !== 'string' || !credit.id ||
      !Object.prototype.hasOwnProperty.call(credit, 'expiresAt') ||
      (credit.expiresAt !== null && !Number.isSafeInteger(credit.expiresAt)))) return null;
    return available.slice().sort((a, b) =>
      (a.expiresAt ?? Infinity) - (b.expiresAt ?? Infinity) ||
      (a.grantedAt ?? Infinity) - (b.grantedAt ?? Infinity) || a.id.localeCompare(b.id))[0];
  }
  function renderResetCredits(data = resetCredits, status = '') {
    const target = $('reset-credits'); if (!target) return;
    target.replaceChildren(); target.hidden = !C.isLoggedIn(state.account);
    if (target.hidden) return;
    const available = Number.isInteger(data?.availableCount) && data.availableCount >= 0 ? data.availableCount : null;
    const head = node('div', null, 'reset-credits-head'), copy = node('span', null, 'reset-credits-copy');
    const title = node('strong', t('사용 한도 초기화권')); title.id = 'reset-credits-title';
    const choice = earliestResetCredit(data), canRetry = !!(resetCreditIdempotencyKey && resetCreditSelectedId);
    copy.append(title, node('small', available === null ? t('초기화권 정보를 사용할 수 없습니다.') : (available ? t('{count}개 사용 가능', {count:available}) : t('사용 가능한 초기화권이 없습니다.'))));
    if (available > 0 && !choice && !canRetry) copy.append(node('small', t('만료일 전체를 확인할 수 없어 사용을 보류합니다.')));
    else if (choice?.expiresAt != null && !canRetry) copy.append(node('small', t('가장 먼저 만료: ') + usageTime(choice.expiresAt)));
    head.append(copy);
    if (available > 0) {
      const use = button(t('1개 사용'), consumeResetCredit, 'secondary-button'); use.id = 'reset-credit-use'; use.disabled = resetCreditBusy || (!choice && !canRetry); head.append(use);
    }
    target.append(head);
    if (status) { const message = node('p', status.text || status, 'reset-credits-status' + (status.error ? ' error' : '')); message.id = 'reset-credits-status'; message.setAttribute('role','status'); target.append(message); }
  }
  async function consumeResetCredit() {
    if (resetCreditBusy || !Number.isInteger(resetCredits?.availableCount) || resetCredits.availableCount <= 0 || !C.isLoggedIn(state.account)) return;
    const choice = earliestResetCredit(resetCredits);
    if (!choice && !resetCreditSelectedId) return;
    if (!confirm(t('사용 한도 초기화권 1개를 사용할까요?'))) return;
    const scope = accountScope(); resetResetCreditRequest(scope); resetCreditBusy = true; renderResetCredits(resetCredits, {text:t('초기화권을 사용하는 중…')});
    if (!resetCreditIdempotencyKey) {
      resetCreditIdempotencyKey = (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`);
      resetCreditSelectedId = choice.id;
    }
    const key = resetCreditIdempotencyKey, creditId = resetCreditSelectedId, generation = usageGeneration;
    try {
      const params = {idempotencyKey:key, creditId};
      const result = await rpc('account/rateLimitResetCredit/consume', params);
      if (generation !== usageGeneration || scope !== accountScope()) return;
      const outcome = result?.outcome || result?.status;
      if (!['reset','alreadyRedeemed','noCredit','nothingToReset'].includes(outcome)) throw new Error(t('초기화권 처리 결과를 확인할 수 없습니다.'));
      resetCreditIdempotencyKey = ''; resetCreditSelectedId = '';
      const message = ['reset','alreadyRedeemed'].includes(outcome) ? t('사용 한도를 초기화했습니다.') : outcome === 'noCredit' ? t('사용 가능한 초기화권이 없습니다.') : t('현재 초기화할 사용 한도가 없습니다.');
      if (['reset','alreadyRedeemed','noCredit'].includes(outcome)) resetCredits = null;
      resetCreditBusy = false; renderResetCredits(resetCredits, {text:message});
      if (['reset','noCredit','nothingToReset','alreadyRedeemed'].includes(outcome)) {
        await loadUsage();
        if (scope === accountScope()) renderResetCredits(resetCredits, {text:message});
      }
    } catch (error) {
      if (generation === usageGeneration && scope === accountScope()) { resetCreditBusy = false; renderResetCredits(resetCredits, {text:t('초기화권을 사용하지 못했습니다. 다시 시도해 주세요.'), error:true}); }
    }
    finally { if (generation === usageGeneration && scope === accountScope()) resetCreditBusy = false; }
  }
  function renderAccounts() {
    const target = $('accounts-list'); if (!target) return; target.replaceChildren();
    const status = node('p', accountActionStatus.text, 'muted account-action-status' + (accountActionStatus.error ? ' error' : ''));
    status.id = 'accounts-status'; status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite'); status.hidden = !accountActionStatus.text; target.append(status);
    const profiles = Array.isArray(state.accounts) ? state.accounts : [];
    for (const profile of profiles) {
      const row = node('div', null, 'account-profile' + (profile.active ? ' active' : ''));
      const mark = node('span', profile.active ? '✓' : (profile.email || 'C').charAt(0).toUpperCase(), 'account-profile-mark');
      const copy = node('span', null, 'account-profile-copy'); copy.append(node('strong', profile.email || profile.label || t('ChatGPT 계정')), node('small', profile.needsLogin ? t('다시 로그인이 필요합니다') : (profile.planType || (profile.active ? t('현재 사용 중') : t('등록된 계정')))));
      const actions = node('span', null, 'account-profile-actions');
      if (profile.needsLogin) actions.append(button(t('다시 로그인'), () => startLogin(true), 'secondary-button'));
      else if (profile.active) actions.append(node('span', t('사용 중'), 'status-pill online'));
      else {
        actions.append(button(t('전환'), async () => {
          accountActionStatus = {text:t('계정 전환 중…'), error:false}; renderAccounts();
          try {
            const result = await call('auth.switch', {key:profile.key});
            if (result && Array.isArray(result.accounts)) { state = result; render(result); }
            const active = (state.accounts || []).find(value => value.active) || profile;
            accountActionStatus = {text:t('계정을 전환했습니다.') + (active.email ? ' · ' + active.email : ''), error:false}; renderAccounts();
          } catch (error) {
            // The settings dialog is a top-layer modal; the global toast is
            // hidden behind it. Refresh the authoritative state first so a
            // revoked target can expose its needsLogin marker inline.
            try { const latest = await call('state'); if (latest) { state = latest; render(latest); } } catch (_) {}
            accountActionStatus = {text:t('계정 전환에 실패했습니다. ') + (error?.message || t('오류가 발생했습니다.')), error:true}; renderAccounts();
          }
        }, 'secondary-button'));
        actions.append(button(t('삭제'), async () => { if (!confirm((profile.email || profile.label) + '\n' + t('이 기기에 저장된 계정을 삭제할까요?'))) return; await call('auth.remove',{key:profile.key}); }, 'secondary-button subtle-danger'));
      }
      row.append(mark,copy,actions); target.append(row);
    }
    if (!profiles.length) target.append(node('p',t('등록된 계정이 없습니다.'),'empty-note'));
  }
  function selectSettingsTab(name) {
    document.querySelectorAll('[data-settings-tab]').forEach(button => button.classList.toggle('active', button.dataset.settingsTab === name));
    document.querySelectorAll('[data-settings-panel]').forEach(panel => panel.hidden = panel.dataset.settingsPanel !== name);
  }
  function openAccountSettings() { selectSettingsTab('account'); show('settings-dialog'); sidebar(false); if (C.isLoggedIn(state.account)) loadUsage(); }
  async function loadUsage() {
    if (usageLoading || resetCreditBusy) return;
    const status = $('usage-status'), target = $('usage-limits');
    if (!C.isLoggedIn(state.account)) { status.textContent = t('로그인 후 사용 한도를 조회할 수 있습니다.'); target.replaceChildren(); resetCredits = null; renderResetCredits(); return; }
    const scope = accountScope(), generation = ++usageGeneration; resetResetCreditRequest(scope);
    usageLoading = true; $('usage-refresh').disabled = true; status.textContent = t('사용 한도를 조회하는 중…');
    try {
      const result = await rpc('account/rateLimits/read', {}), rows = usageRows(result);
      if (generation !== usageGeneration || accountScope() !== scope) { target.replaceChildren(); status.textContent = t('계정이 바뀌었습니다. 다시 조회해 주세요.'); return; }
      resetCredits = result?.rateLimitResetCredits ?? null; state.rateLimits = result || {}; renderUsage(rows); renderQuota(); renderResetCredits(resetCredits);
      status.textContent = rows.length ? t('현재 계정의 Codex 사용 한도입니다.') : t('사용 한도 정보가 제공되지 않았습니다.');
    } catch (error) { target.replaceChildren(); status.textContent = t('사용 한도를 조회할 수 없습니다. ') + error.message; }
    finally { usageLoading = false; $('usage-refresh').disabled = false; }
  }
  async function loadTools(force = false) {
    show('tools-dialog'); const target = $('tools-list');
    const scope = currentToolsScope(), generation = ++toolsGeneration;
    if (toolsScope !== scope) { toolCache.fill(null); toolsScope = scope; }
    if (!toolCache.some(Boolean)) target.replaceChildren(node('p', t('불러오는 중…'), 'muted'));
    const cwds = state.cwd ? [state.cwd] : [];
    const results = await Promise.allSettled([rpc('plugin/list', {cwds, ...(force ? {forceRefetch:true} : {})}), rpc('skills/list', {cwds, ...(force ? {forceReload:true} : {})}), rpc('mcpServerStatus/list', {limit: 100, ...(state.threadId ? {threadId: state.threadId} : {})})]);
    if (generation !== toolsGeneration || scope !== currentToolsScope()) return;
    target.replaceChildren();
    const card = (name, description, parent = target) => { const c = node('div', null, 'tool-card'); c.append(node('strong', name), node('p', description || '', 'muted')); parent.append(c); return c; };
    [t('플러그인'), t('스킬'), t('MCP 서버')].forEach((title, i) => {
      target.append(node('h3', title));
      const result = results[i];
      if (result.status === 'fulfilled') toolCache[i] = result.value;
      const data = result.status === 'fulfilled' ? result.value : toolCache[i];
      if (result.status === 'rejected') { card(t('불러오지 못했습니다'), result.reason.message + (data ? t(' 이전 목록을 표시합니다.') : '')); if (!data) return; }
      if (i === 0) {
        for (const market of data.marketplaces || []) {
          const heading = node('p', market.interface?.displayName || market.name, 'muted'); target.append(heading);
          for (const plugin of market.plugins || []) {
            const c = card(plugin.interface?.displayName || plugin.name, plugin.interface?.shortDescription || plugin.id);
            const params = {pluginName: plugin.name, ...(market.path ? {marketplacePath: market.path} : {remoteMarketplaceName: market.name})};
            c.append(button(t('상세'), async () => { const detail = await rpc('plugin/read', params); logEvent(t('플러그인: ') + plugin.name, detail.plugin); show('activity-dialog'); }));
            if (plugin.installed) {
              c.append(button(plugin.enabled ? t('비활성화') : t('활성화'), async () => { await rpc('config/value/write', {keyPath: 'plugins.' + JSON.stringify(plugin.id) + '.enabled', value: !plugin.enabled, mergeStrategy: 'replace'}); await loadTools(); }));
              c.append(button(t('제거'), async () => { if (!confirm(plugin.name + t(' 플러그인을 제거할까요?'))) return; await rpc('plugin/uninstall', {pluginId: plugin.id}); await loadTools(); }));
            } else c.append(button(t('설치'), async () => {
              const detail = await rpc('plugin/read', params);
              const summary = detail.plugin;
              if (!confirm(plugin.name + t(' 설치\n\n') + (summary.description || '') + t('\n\n스킬 ') + (summary.skills?.length || 0) + t('개 · MCP ') + (summary.mcpServers?.length || 0) + t('개 · 훅 ') + (summary.hooks?.length || 0) + t('개'))) return;
              const result = await rpc('plugin/install', params);
              for (const app of result.appsNeedingAuth || []) if (app.installUrl) { const c = card(app.name + t(' 계정 연결'), t('플러그인을 사용하려면 계정 연결이 필요합니다.')); c.append(button(t('연결'), () => call('ui.externalBrowser', {url: app.installUrl}))); }
              toast(t('플러그인을 설치했습니다.')); if (!result.appsNeedingAuth?.length) await loadTools();
            }));
          }
        }
        for (const e of data.marketplaceLoadErrors || []) card(t('마켓플레이스 오류'), e.message);
        if (!data.marketplaces?.length) card(t('등록된 플러그인이 없습니다'), t('마켓플레이스를 추가하거나 계정 연결 상태를 확인하세요.'));
      } else if (i === 1) {
        let count = 0;
        for (const entry of data.data || []) {
          for (const skill of entry.skills || []) {
            count++; const c = card(skill.interface?.displayName || skill.name, skill.description);
            c.append(node('p', skill.path, 'muted'), button(skill.enabled ? t('비활성화') : t('활성화'), async () => { await rpc('skills/config/write', {path: skill.path, enabled: !skill.enabled}); await loadTools(); }), button(t('대화에 사용'), () => { $('prompt').value += '$' + skill.name + ' '; close('tools-dialog'); $('prompt').focus(); }));
          }
          for (const e of entry.errors || []) card(t('스킬 로드 오류'), e.message);
        }
        if (!count) card(t('설치된 스킬이 없습니다'), t('SKILL.md가 들어 있는 스킬 폴더를 가져오거나 Codex에게 스킬 생성을 요청하세요.'));
      } else {
        for (const server of data.data || []) {
          const c = card(server.name, (server.runtimeStatus?.status || server.authStatus) + t(' · 도구 ') + Object.keys(server.tools || {}).length + t('개'));
          if (server.toolsError) c.append(node('p', server.toolsError, 'muted'));
          c.append(button(t('도구 보기'), () => { logEvent('MCP: ' + server.name, server); show('activity-dialog'); }), button(t('로그인'), async () => { const r = await rpc('mcpServer/oauth/login', {name: server.name}); await call('ui.externalBrowser', {url: r.authorizationUrl}); }));
        }
        if (!data.data?.length) card(t('설정된 MCP 서버가 없습니다'), t('서버 설정에서 HTTP 또는 stdio 서버를 추가할 수 있습니다.'));
        if (data.nextCursor) target.append(node('p', t('표시된 서버: 처음 100개'), 'muted'));
      }
    });
  }
  function elicitationForm(schema, target) {
    const fields = [];
    for (const [key, spec] of Object.entries(schema.properties || {})) {
      const box = node('div', null, 'question'), label = node('label', spec.title || key);
      const type = Array.isArray(spec.type) ? spec.type.find(t => t !== 'null') : spec.type;
      const choices = spec.enum || spec.oneOf?.map(o => o.const);
      let field, read;
      if (type === 'object') {
        box.append(label); read = elicitationForm(spec, box);
      } else {
        if (choices) {
          field = node('select'); field.add(new Option(t('선택하세요'), ''));
          choices.forEach((value, i) => field.add(new Option(spec.enumNames?.[i] || spec.oneOf?.[i]?.title || String(value), String(i))));
          if (spec.default !== undefined) field.value = String(choices.indexOf(spec.default));
          read = () => field.value === '' ? undefined : choices[Number(field.value)];
        } else if (type === 'boolean') {
          field = node('input'); field.type = 'checkbox'; field.checked = !!spec.default; read = () => field.checked;
        } else if (type === 'array') {
          const values = spec.items?.enum;
          if (values) {
            field = node('select'); field.multiple = true; values.forEach((value, i) => field.add(new Option(spec.items.enumNames?.[i] || String(value), String(i))));
            for (const option of field.options) option.selected = (spec.default || []).includes(values[Number(option.value)]);
            read = () => [...field.selectedOptions].map(o => values[Number(o.value)]);
          } else {
            field = node('textarea'); field.placeholder = t('항목마다 한 줄씩 입력하세요'); field.value = (spec.default || []).join('\n');
            read = () => field.value.split('\n').filter(value => value.length > 0);
          }
        } else {
          field = node('input'); field.type = ['number', 'integer'].includes(type) ? 'number' : spec.format === 'email' ? 'email' : 'text';
          field.value = spec.default ?? ''; field.step = type === 'integer' ? '1' : 'any';
          if (spec.minimum !== undefined) field.min = spec.minimum;
          if (spec.maximum !== undefined) field.max = spec.maximum;
          if (spec.minLength !== undefined) field.minLength = spec.minLength;
          if (spec.maxLength !== undefined) field.maxLength = spec.maxLength;
          read = () => field.value === '' ? undefined : field.type === 'number' ? Number(field.value) : field.value;
        }
        field.required = (schema.required || []).includes(key) && type !== 'boolean';
        field.setAttribute('aria-label', spec.title || key); label.append(field); box.append(label);
      }
      if (spec.description) box.append(node('p', spec.description, 'muted'));
      target.append(box); fields.push({key, field, read});
    }
    return () => {
      const entries = [];
      for (const entry of fields) {
        if (entry.field && !entry.field.reportValidity()) throw new Error(t('입력 내용을 확인해 주세요.'));
        const value = entry.read(); if (value !== undefined) entries.push([entry.key, value]);
      }
      return Object.fromEntries(entries);
    };
  }
  function requestThread(req) { return String(req?.params?.threadId || ''); }
  function closeBackgroundRequest() {
    if (!displayedRequest) return;
    const req = requestQueue.get(displayedRequest), thread = requestThread(req);
    if (thread && thread !== state.threadId) {
      displayedRequest = null;
      if ($('request-dialog').open) close('request-dialog');
    }
  }
  function nextRequest() {
    closeBackgroundRequest();
    if (displayedRequest || !requestQueue.size) return;
    let entry;
    for (const candidate of requestQueue.entries()) {
      const thread = requestThread(candidate[1]);
      if (!thread || thread === state.threadId) { entry = candidate; break; }
    }
    if (!entry) return;
    const [key, req] = entry; displayedRequest = key;
    $('request-title').textContent = req.method.includes('requestUserInput') ? t('Codex 질문') : req.method.includes('requestApproval') ? t('작업 승인') : t('Codex 요청');
    $('request-reason').textContent = req.params.reason || req.params.message || req.method;
    $('request-detail').textContent = JSON.stringify(req.params, null, 2); $('request-fields').replaceChildren(); $('request-actions').replaceChildren();
    const complete = async result => { result = await result; await call('rpc.respond', {key, result}); requestQueue.delete(key); displayedRequest = null; close('request-dialog'); nextRequest(); };
    const add = (text, result, cls) => $('request-actions').append(button(text, () => complete(typeof result === 'function' ? result() : result), cls));
    if (req.method === 'item/commandExecution/requestApproval' || req.method === 'item/fileChange/requestApproval') {
      add(t('거절'), {decision: 'decline'}); add(t('이번 대화에 허용'), {decision: 'acceptForSession'}); add(t('허용'), {decision: 'accept'}, 'primary-button');
    } else if (req.method === 'item/permissions/requestApproval') {
      add(t('거절'), {permissions: {}, scope: 'turn'}); add(t('허용'), {permissions: req.params.permissions, scope: 'turn'}, 'primary-button');
    } else if (req.method === 'item/tool/requestUserInput') {
      const answers = [];
      for (const question of req.params.questions || []) {
        const wrap = node('div', null, 'question'), label = node('label', question.question), field = node('input'); field.type = question.isSecret ? 'password' : 'text'; field.setAttribute('aria-label', question.question); label.append(field); wrap.append(label);
        if (question.options?.length) { const select = node('select'); select.setAttribute('aria-label', question.header || question.question); select.add(new Option(t('선택 또는 직접 입력'), '')); for (const o of question.options) select.add(new Option(o.label + (o.description ? ' — ' + o.description : ''), o.label)); select.addEventListener('change', () => field.value = select.value); wrap.append(select); }
        answers.push([question.id, field]); $('request-fields').append(wrap);
      }
      add(t('답변 보내기'), () => ({answers: Object.fromEntries(answers.map(([id, field]) => [id, {answers: [field.value]}]))}), 'primary-button');
    } else if (req.method === 'mcpServer/elicitation/request') {
      add(t('취소'), {action: 'decline'});
      if (req.params.mode === 'url') {
        $('request-fields').append(button(t('연결 페이지 열기'), () => call('ui.externalBrowser', {url: req.params.url})));
        add(t('완료'), {action: 'accept'}, 'primary-button');
      } else {
        const read = elicitationForm(req.params.requestedSchema || {}, $('request-fields'));
        add(t('전송'), () => ({action: 'accept', content: read()}), 'primary-button');
      }
    } else {
      const field = node('textarea'); field.setAttribute('aria-label', t('프로토콜 응답 JSON')); field.value = '{}'; $('request-fields').append(node('p', t('확장 프로토콜 요청입니다. 응답 JSON을 입력할 수 있습니다.'), 'muted'), field);
      $('request-actions').append(button(t('작업 중지'), async () => { await call('chat.stop'); requestQueue.delete(key); displayedRequest = null; close('request-dialog'); nextRequest(); })); add(t('응답 전송'), () => JSON.parse(field.value), 'primary-button');
    }
    show('request-dialog');
  }
  window.mobileCodexEvent = (name, data) => {
    if (name === 'response') { const p = pending.get(data.id); if (p) { clearTimeout(p.timer); pending.delete(data.id); data.error ? p.reject(new Error(data.error)) : p.resolve(data.result || {}); } return; }
    if (name === 'state') render(data);
    else if (name === 'error') { if (!data.threadId || data.threadId === state.threadId) toast(data.message); }
    else if (name === 'notice') toast(data.message);
    else if (name === 'message.delta') { if (data.threadId && data.threadId !== state.threadId) return; C.appendDelta(state.messages, data.id, data.delta); if (!chatMode) drawMessages(); }
    else if (name === 'tool') { if (data.threadId && data.threadId !== state.threadId) return; activityIcon = /read|list|search/.test(data.name) ? 'inspecting' : 'working'; if (!chatMode) { drawStatusIcons(); $('activity-text').textContent = data.name.replace('mobile_', '') + ' · ' + data.path; } logEvent(data.name, data); }
    else if (name === 'agent.event') { if (data.threadId && data.threadId !== state.threadId) return; if (!data.method.endsWith('/delta')) logEvent(data.method, data.params); if (data.method === 'turn/diff/updated') logEvent(t('변경 사항'), data.params.diff); }
    else if (name === 'files.changed' && (!data.threadId || data.threadId === state.threadId) && !$('file-panel').hidden) listFiles().catch(e => toast(e.message));
    else if (name === 'updates.changed') drawUpdates(data);
    else if (name === 'voice.state') drawDictation(data);
    else if (name === 'voice.changed') recoverVoiceInput();
    else if (name === 'attachments.picked') acceptPickedAttachments(data, data.draftKey || '');
    else if (name === 'login.completed') { if (data.success) { login = null; close('login-dialog'); toast(t('ChatGPT 계정을 연결했습니다.')); } else $('login-help').textContent = data.error || t('로그인이 취소되었습니다. 다시 연결해 주세요.'); }
    else if (name === 'terminal.output') { $('terminal-output').textContent += data.text; if ($('terminal-output').textContent.length > 1000000) $('terminal-output').textContent = $('terminal-output').textContent.slice(-1000000); $('terminal-output').scrollTop = $('terminal-output').scrollHeight; }
    else if (name === 'terminal.exit') $('terminal-output').textContent += t('\n[종료 코드 ') + data.code + ']\n';
    else if (name === 'server.request') { requestQueue.set(data.key, data); nextRequest(); }
    else if (name === 'server.resolved') { requestQueue.delete(data.key); if (displayedRequest === data.key) { displayedRequest = null; close('request-dialog'); } nextRequest(); }
    else if (name === 'viewport') { document.body.classList.toggle('keyboard-open', !!data.keyboardVisible); viewportChanged(); }
    else if (name === 'theme') document.documentElement.dataset.theme = data.theme === 'dark' ? 'dark' : 'light';
    else if (name === 'notification.open') { const resume = data.threadId ? call('chat.resume', {id:data.threadId}) : Promise.resolve(); resume.then(() => nextRequest()).catch(error => toast(error.message)); }
    else if (name === 'notifications.changed') { window.refreshNotificationSettings?.(); }
    else if (name === 'back') window.mobileCodexBack();
  };
  window.mobileCodexBack = () => {
    if (dictationState.phase !== 'idle') { call('voice.cancel').catch(error => toast(error.message)); return true; }
    const id = dialogs.at(-1);
    if (id) { if (id !== 'request-dialog') dismiss(id); return true; }
    if (document.body.classList.contains('sidebar-open')) { sidebar(false); return true; }
    if (!$('file-panel').hidden) { $('file-panel').hidden = true; return true; }
    saveDraft(); return false;
  };
  document.querySelectorAll('dialog').forEach(d => {
    d.addEventListener('close', () => {
      const i = dialogs.indexOf(d.id); if (i !== -1) dialogs.splice(i, 1);
      const actionClosing = projectMenuActionClosing;
      if (d.id === 'project-actions-dialog' && projectMenuFocus && !projectMenuActionClosing) {
        const trigger = projectMenuFocus;
        projectMenuFocus = null;
        if (matchMedia('(max-width:760px)').matches && !document.body.classList.contains('sidebar-open')) sidebar(true);
        const replacement = trigger.isConnected ? trigger : [...document.querySelectorAll('[data-project-menu-key]')].find(button => button.dataset.projectMenuKey === trigger.dataset.projectMenuKey);
        replacement?.focus({preventScroll:true});
      }
      if (d.id === 'project-actions-dialog') { projectMenuActionClosing = false; if (actionClosing) projectMenuFocus = null; }
    });
    d.addEventListener('cancel', e => { e.preventDefault(); if (d.id !== 'request-dialog') dismiss(d.id); });
  });
  wireSheetGestures();
  document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => dismiss(b.dataset.close)));
  document.querySelectorAll('.sidebar-toggle').forEach(b => b.addEventListener('click', () => { if (matchMedia('(max-width:760px)').matches) sidebar(!document.body.classList.contains('sidebar-open')); else document.body.classList.toggle('sidebar-collapsed'); }));
  document.querySelectorAll('[data-prompt]').forEach(b => b.addEventListener('click', () => { $('prompt').value = b.dataset.prompt; saveDraft(); sizeComposer(); $('prompt').focus(); }));
  on('mode-chat', () => switchMode('chat'));
  on('mode-codex', () => switchMode('codex'));
  on('chat-model-settings', () => call('ui.chatWebProbe'));
  on('scrim', () => sidebar(false)); on('new-chat', async () => newChat(''));
  ['add-project', 'choose-folder', 'composer-folder'].forEach(id => on(id, () => pickFolder()));
   const closeToolMenu = () => { if ($('tool-menu-dialog').open) close('tool-menu-dialog'); };
   ['show-files', 'files-toggle'].forEach(id => on(id, async () => { closeToolMenu(); $('file-panel').hidden = !$('file-panel').hidden; sidebar(false); if (!$('file-panel').hidden) await listFiles(); }));
  on('file-close', () => $('file-panel').hidden = true); on('file-up', async () => { folder = C.parent(folder); await listFiles(); });
  let searchTimer; on('file-query', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => listFiles($('file-query').value.trim()).catch(e => toast(e.message)), 300); }, 'input');
  ['file-new', 'folder-new'].forEach(id => on(id, async () => { const name = await input(id === 'file-new' ? t('새 파일') : t('새 폴더'), t('이름을 입력하세요.')); if (name) await mutate(id === 'file-new' ? 'mobile_create' : 'mobile_mkdir', {path: C.join(folder, name), content: ''}); }));
  on('show-changes', () => { closeToolMenu(); return loadChanges(); }); on('changes-refresh', () => loadChanges()); on('changes-history', () => loadChanges(true)); on('change-restore', restoreChange);
  on('editor-save', async () => { const result = await mutate('mobile_write', {path: openedFile.path, expectedSha256: openedFile.sha256, content: $('editor').value}); openedFile = await call('files.read', {path: result.path || openedFile.path}); });
  on('editor-delete', async () => { await mutate('mobile_delete', {path: openedFile.path}); close('editor-dialog'); });
  on('editor-rename', async () => { const name = await input(t('이름 변경'), t('새 파일 이름'), C.basename(openedFile.path)); if (name) { await mutate('mobile_rename', {path: openedFile.path, name}); await openFile(C.join(C.parent(openedFile.path), name)); } });
  on('editor-move', async () => { const destination = await input(t('이동'), t('대상 폴더 경로 (루트는 빈칸)')); if (destination !== null) { await mutate('mobile_move', {path: openedFile.path, destination}); await openFile(C.join(destination, C.basename(openedFile.path))); } });
  on('input-confirm', () => { const r = inputResolve; inputResolve = null; const value = $('input-value').value; close('input-dialog'); if (r) r(value); });
  $('input-dialog').addEventListener('close', () => { if (inputResolve) { const r = inputResolve; inputResolve = null; r(null); } });
  on('input-value', e => { if (e.key === 'Enter' && !e.isComposing && e.keyCode !== 229) $('input-confirm').click(); }, 'keydown');
  on('composer', async () => {
    const value = $('prompt').value, text = value.trim();
    if (chatMode) {
      if (!text || chatBusy) return;
      const id = 'chat-' + Date.now() + '-' + Math.random().toString(36).slice(2);
      chatBusy = true;
      chatMessages.push({id, role:'user', text});
      $('welcome').hidden = true; $('activity').hidden = false;
      drawMessages(); updateSend(); scrollLatest();
      try {
        const result = await call('chat.web.send', {text});
        if (!result.reply || !result.conversationId) throw new Error('ChatGPT 답변과 대화 저장을 확인하지 못했습니다.');
        chatMessages.push({id:id + '-reply', role:'assistant', text:result.reply, model:result.model || ''});
        chatModel = result.model || chatModel;
        $('chat-model-summary').textContent = chatModel || 'Chat 모델 설정';
        try { localStorage.setItem('chat-web-model', chatModel); } catch {}
        persistChatMessages();
        if (chatMode && $('prompt').value === value) { $('prompt').value = ''; saveDraft(); }
      } catch (error) {
        chatMessages.push({id:id + '-error', role:'assistant', text:'전송 또는 답변 저장 확인에 실패했습니다: ' + error.message});
        persistChatMessages();
        throw error;
      } finally {
        chatBusy = false;
        if (chatMode) { $('activity').hidden = true; drawMessages(); sizeComposer(); }
      }
      return;
    }
    if ((!text && !draftContext.attachments.length) || sending || voiceStarting || voiceActive) return;
    const steer = !!state.busy, expectedTurnId = state.turnId || '', expectedThreadId = state.threadId || '', workspaceKey = state.workspace?.key || '';
    const submitted = {value, context:JSON.parse(JSON.stringify(draftContext)), scopes:new Set([draftScope])}; sending = submitted;
    saveDraft(); updateSend(); scrollLatest();
    try {
      await call(steer ? 'chat.steer' : 'chat.send', {text, expectedTurnId, expectedThreadId, workspaceKey, model:$('model').value, effort:$('effort').value, attachments:submitted.context.attachments.map(x => x.id), skills:submitted.context.skills, mentions:submitted.context.mentions});
      // Keep text typed during the request, or drafts from a different conversation.
      if (submitted.scopes.has(draftScope) && $('prompt').value === value) {
        $('prompt').value = '';
        for (const type of ['attachments','mentions','skills']) {
          const sentIds = new Set(submitted.context[type].map(x => x.id || x.path));
          draftContext[type] = draftContext[type].filter(x => !sentIds.has(x.id || x.path));
        }
        renderDraftContext();
      }
      for (const scope of submitted.scopes) {
        try { if (localStorage.getItem(scope) === value) localStorage.removeItem(scope); if (localStorage.getItem(contextScope(scope)) === JSON.stringify(submitted.context)) localStorage.removeItem(contextScope(scope)); } catch {}
      }
      saveDraft();
    } finally { sending = null; sizeComposer(); }
  }, 'submit');
  // Physical keyboards: Ctrl/Cmd+Enter sends, Enter remains useful on touch keyboards.
  $('prompt').addEventListener('keydown', e => {
    if (!e.isComposing && e.keyCode !== 229 && !$('autocomplete').hidden && ['ArrowDown','ArrowUp','Enter','Escape'].includes(e.key)) {
      e.preventDefault();
      if (e.key === 'Escape') hideAutocomplete();
      else if (e.key === 'Enter') { const item = autocomplete.items[autocomplete.index]; if (item) Promise.resolve(chooseAutocomplete(item)).catch(error => toast(error.message)); }
      else if (autocomplete.items.length) { autocomplete.index = (autocomplete.index + (e.key === 'ArrowDown' ? 1 : -1) + autocomplete.items.length) % autocomplete.items.length; updateAutocompleteActive(); }
      return;
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.isComposing && e.keyCode !== 229) { e.preventDefault(); $('composer').requestSubmit(); }
  });
  let autocompleteTimer; $('prompt').addEventListener('input', () => { saveDraft(); sizeComposer(); clearTimeout(autocompleteTimer); hideAutocomplete(); if (chatMode) return; if (/[@$]$/.test($('prompt').value.slice(0, $('prompt').selectionStart))) queryAutocomplete(); else autocompleteTimer = setTimeout(queryAutocomplete, 120); });
  $('prompt').addEventListener('focus', () => { if (following) frame(scrollLatest); });
  $('composer').addEventListener('focusin', () => $('composer').classList.add('composer-expanded'));
  $('composer').addEventListener('focusout', () => frame(() => { if (! $('composer').contains(document.activeElement)) $('composer').classList.remove('composer-expanded'); }));
  $('composer').addEventListener('click', e => { if (! $('composer').classList.contains('composer-expanded') && e.target === $('composer')) $('prompt').focus(); });
  $('prompt').addEventListener('click', () => { if (!chatMode) queryAutocomplete(); });
  $('chat-scroll').addEventListener('scroll', () => {
    const area = $('chat-scroll'); following = area.scrollHeight - area.scrollTop - area.clientHeight < 80;
    $('jump-latest').hidden = following || !(chatMode ? chatMessages.length : state.messages.length);
  }, {passive:true});
  on('voice-input', startVoiceInput);
  on('dictation-done', () => call('voice.stop'));
  on('dictation-cancel', () => call('voice.cancel'));
  on('jump-latest', scrollLatest); on('composer-options', () => { optionsSummary(); show('options-dialog'); });
  on('image-save', () => openedImage && call('images.export', {id:openedImage.id, name:openedImage.name}));
  on('image-zoom', () => { const zoomed = $('image-stage').classList.toggle('zoomed'); $('image-zoom').setAttribute('aria-pressed', String(zoomed)); });
  on('image-prev', () => changeImage(-1)); on('image-next', () => changeImage(1));
  let imageTouch = null;
  $('image-stage').addEventListener('touchstart', e => { imageTouch = e.touches.length === 1 ? {x:e.touches[0].clientX,y:e.touches[0].clientY,time:Date.now()} : null; }, {passive:true});
  $('image-stage').addEventListener('touchend', e => {
    if (imageTouch && !$('image-stage').classList.contains('zoomed') && e.changedTouches.length === 1) {
      const dx = e.changedTouches[0].clientX - imageTouch.x, dy = e.changedTouches[0].clientY - imageTouch.y;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5 && Date.now() - imageTouch.time < 800) changeImage(dx < 0 ? 1 : -1);
    }
    imageTouch = null;
  }, {passive:true});
  $('image-stage').addEventListener('touchcancel', () => imageTouch = null, {passive:true});
  on('stop', () => call('chat.stop')); on('model', () => { efforts(); saveOptions(); }, 'change'); on('effort', () => { optionsSummary(); saveOptions(); }, 'change'); on('add-attachment', chooseAttachment);
  on('permissions', () => setPermissionMode($('permissions').value), 'change');
  on('approval-mode', () => setApprovalMode($('approval-mode').value), 'change');
  document.querySelectorAll('input[name="permission"]').forEach(r => r.addEventListener('change', () => setPermissionMode(r.value).catch(error => toast(error.message))));
  ensureCharacterPackUi(); ensureNotificationSettings(); ensureChatWebProbeUi();
  if (chatMode) { chatMode = false; switchMode('chat'); }
  on('connect', () => startLogin(false)); on('account-button', openAccountSettings); on('settings', () => { ensureCharacterPackUi(); ensureNotificationSettings(); show('settings-dialog'); sidebar(false); if (!characterState.folderConfigured && characterState.packs.length <= 1) loadCharacterPacks(); });
  document.querySelectorAll('[data-settings-tab]').forEach(tab => tab.addEventListener('click', () => {
    const selected = tab.dataset.settingsTab; selectSettingsTab(selected);
    if (selected === 'personal' && !instructionsLoaded) loadInstructions();
    if (selected === 'account') loadUsage();
    if (selected === 'updates') loadUpdates();
  }));
  on('device-code', async () => { if (login) { await call('ui.copyCode', {code: login.userCode}); toast(t('코드를 복사했습니다.')); } });
  on('open-login', () => login && call('ui.loginBrowser', {url: login.verificationUrl}));
  $('login-dialog').addEventListener('close', () => { if (login?.loginId) call('auth.cancel', {loginId: login.loginId}).catch(() => {}); login = null; });
  on('account-add', () => startLogin(true));
  on('restart', async () => { await call('runtime.stop'); await call('runtime.start'); }); on('engine-stop', () => call('runtime.stop')); on('logout', async () => { if (!C.isLoggedIn(state.account)) return startLogin(false); if (!confirm(t('현재 계정을 이 기기에서 로그아웃할까요?'))) return; await call('auth.logout'); });
  on('usage-refresh', loadUsage);
  ['check','download','cancel','clear','permission','install'].forEach(action => on('update-' + action, () => updateAction(action)));
  $('update-repository').addEventListener('input', () => { updateSourceDirty = true; drawUpdates(updateState); });
  $('update-prereleases').addEventListener('change', () => { updateSourceDirty = true; drawUpdates(updateState); });
  $('language').value = L.choice();
  on('language', async () => {
    const choice = $('language').value;
    try { await call('ui.locale', {language:choice}); } catch (error) { $('language').value = L.choice(); throw error; }
    saveDraft(); saveOptions(); L.set(choice); modelKey = ''; render(await call('state')); renderDraftContext(); drawDictation(dictationState); await loadUpdates();
    if (!$('file-panel').hidden) await listFiles();
    if ($('tools-dialog').open) await loadTools(false);
  }, 'change');
  on('chat-icons-toggle', setChatIcons, 'change');
  $('chat-icons-toggle').checked = chatIconsEnabled; drawStatusIcons();
  on('instructions-reload', loadInstructions); on('instructions-save', saveInstructions);
  $('settings-dialog').addEventListener('cancel', event => { event.preventDefault(); dismiss('settings-dialog'); });
  on('storage-access', () => call('ui.storageAccess')); on('theme', setTheme, 'change');
  on('devtools-check', checkDevtools);
  on('floating-chat', () => call('ui.floatingChat')); on('phone-settings', () => call('ui.phoneSettings')); on('phone-enable', enablePhone);
  on('phone-disable', stopPhone); on('phone-stop-banner', stopPhone);
  ['edit-config', 'tools-config'].forEach(id => on(id, config)); on('config-save', async () => { await call('config.save', {content: $('config-editor').value}); configOriginal = $('config-editor').value; await call('runtime.start'); close('config-dialog'); toast(t('설정을 적용했습니다.')); });
  on('show-recovery', () => { closeToolMenu(); return recovery(); }); on('show-terminal', () => { closeToolMenu(); show('terminal-dialog'); sidebar(false); }); on('terminal-stop', () => call('terminal.stop'));
  on('terminal-form', async e => { e.preventDefault(); const command = $('terminal-command').value; if (command.trim()) { await call('terminal.run', {command}); $('terminal-output').textContent += '$ ' + command + '\n'; $('terminal-command').value = ''; } }, 'submit');
  on('show-tools', () => show('tool-menu-dialog')); on('show-tools-panel', () => { closeToolMenu(); return loadTools(false); }); on('refresh-tools', () => loadTools(true));
  on('marketplace-add', async () => { const source = await input(t('마켓플레이스 추가'), t('Git URL 또는 기기의 로컬 경로')); if (source) { const result = await rpc('marketplace/add', {source}); toast(result.marketplaceName + t(' 추가 완료')); await loadTools(); } });
  on('skill-import', async () => { const r = await call('skills.import'); if (!r.cancelled) { toast(t('스킬 폴더를 가져왔습니다.')); await loadTools(); } });
  $('request-dialog').addEventListener('cancel', e => e.preventDefault());
  $('theme').value = localStorage.getItem('theme') || 'system'; setTheme(); matchMedia('(prefers-color-scheme: dark)').addEventListener('change', setTheme);
  window.addEventListener('resize', viewportChanged);
  window.visualViewport?.addEventListener('resize', viewportChanged);
  window.addEventListener('pagehide', saveDraft);
  document.addEventListener('visibilitychange', () => { if (document.hidden) saveDraft(); else recoverVoiceInput(); });
  document.addEventListener('keydown', e => {
    if (dialogs.at(-1) === 'image-dialog' && !$('image-stage').classList.contains('zoomed') && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { e.preventDefault(); changeImage(e.key === 'ArrowRight' ? 1 : -1); }
    if (e.key === 'Escape' && !dialogs.length) window.mobileCodexBack();
    if (e.key !== 'Tab' || !document.body.classList.contains('sidebar-open')) return;
    const controls = [...$('sidebar').querySelectorAll('button:not(:disabled)')], first = controls[0], last = controls.at(-1);
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  });
  const resetCreditPanel = $('reset-credits');
  $('usage-limits').after(resetCreditPanel);
  viewportChanged();
  call('state').then(async initial => { render(initial); loadCharacterPacks(); recoverVoiceInput(); loadUpdates(); try { acceptPickedAttachments(await call('attachments.recover'), ''); } catch {} }).catch(e => toast(e.message));
})();
