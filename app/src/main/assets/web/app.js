/* Packaged UI only. Native.postMessage is the sole transport; no remote scripts. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id), C = window.UiCore;
  const pending = new Map(), requestQueue = new Map();
  const dialogs = [], frame = fn => (window.requestAnimationFrame || (cb => setTimeout(cb, 0)))(fn);
  let following = true, draftScope = '', sending = null, configOriginal = '', sidebarFocus = null, openedImage = null;
  const imageReads = new Map();
  let viewerImages = [], viewerIndex = 0, viewerGroup = null;
  let seq = 0, state = {messages: [], sessions: [], projects: [], models: [], account: {}, workspace: {}}, folder = '', openedFile = null, login = null, inputResolve = null, toastTimer, fileSeq = 0, modelKey = '', displayedRequest = null;
  let draftContext = {attachments: [], mentions: [], skills: []}, draftOptions = {model:'', effort:''}, autocomplete = {items: [], index: -1, token: '', type: '', version: 0};
  const handledReceipts = new Set();
  let chatIconsEnabled = localStorage.getItem('chat-icons') !== 'off', activityIcon = 'thinking';
  let instructionsOriginal = '', instructionsLoaded = false, instructionsSaving = false, devtoolsCheckResult = null, devtoolsCheckSummary = '', devtoolsChecking = false, usageLoading = false;
  const toolCache = [null, null, null];
  function call(action, args = {}) {
    return new Promise((resolve, reject) => {
      if (!window.Native) return reject(new Error('Android 앱에서 실행해 주세요.'));
      const id = String(++seq);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('요청 시간이 초과되었습니다. 상태를 확인하고 다시 시도해 주세요.')); }, action === 'files.mutate' ? 610000 : 150000);
      pending.set(id, {resolve, reject, timer});
      window.Native.postMessage(JSON.stringify({id, action, args}));
    });
  }
  const rpc = (method, params = {}) => call('rpc', {method, params});
  const on = (id, fn, event = 'click') => $(id).addEventListener(event, e => { if (event === 'submit') e.preventDefault(); Promise.resolve().then(() => fn(e)).catch(error => toast(error.message)); });
  function node(tag, text, cls) { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
  function button(text, fn, cls = 'secondary-button') { const b = node('button', text, cls); b.type = 'button'; b.addEventListener('click', () => { b.disabled = true; Promise.resolve().then(fn).catch(e => toast(e.message)).finally(() => b.disabled = false); }); return b; }
  function icon(name) { const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); const use = document.createElementNS(svg.namespaceURI, 'use'); use.setAttribute('href', '#i-' + name); svg.append(use); return svg; }
  function toast(message) { $('toast').textContent = message || '오류가 발생했습니다.'; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 5500); }
  function show(id) {
    sidebar(false);
    if (!$(id).open) { dialogs.push(id); $(id).showModal(); }
  }
  function close(id) { $(id).close(); }
  function dismiss(id) {
    const dirty = (id === 'editor-dialog' && openedFile && $('editor').value !== openedFile.content)
      || (id === 'config-dialog' && $('config-editor').value !== configOriginal)
      || (id === 'settings-dialog' && instructionsLoaded && $('instructions-editor').value !== instructionsOriginal);
    if (dirty && !confirm('저장하지 않은 변경 사항을 닫을까요?')) return;
    close(id);
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
      ...(draftContext.attachments || []).map(item => ({type:'attachments', value:item, label:item.name || '첨부 파일'})),
      ...(draftContext.mentions || []).map(item => ({type:'mentions', value:item, label:'@' + (item.name || item.path)})),
      ...(draftContext.skills || []).map(item => ({type:'skills', value:item, label:'$' + item.name}))
    ];
    for (const item of items) {
      const chip = node('span', null, 'context-chip'), image = item.value.image || item.value;
      if (item.type === 'attachments' && C.safeImageUrl(image.url)) { const preview = button('', () => previewImage(image), 'context-image'); preview.setAttribute('aria-label', item.label + ' 미리 보기'); const img = node('img'); img.src = image.url; img.alt = item.label; preview.append(img); chip.append(preview); }
      chip.append(node('span', item.label)); const remove = button('×', () => removeContext(item.type, item.value.id || item.value.path || item.value.name), 'chip-remove'); remove.setAttribute('aria-label', item.label + ' 제거'); chip.append(remove); target.append(chip);
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
  function updateSend() { $('send').disabled = (! $('prompt').value.trim() && !(draftContext.attachments || []).length) || !!sending || !!state.busy; }
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
    const model = $('model').selectedOptions[0]?.textContent || '기본 모델';
    const effort = $('effort').selectedOptions[0]?.textContent || '기본';
    $('model-summary').textContent = model;
    $('composer-options').setAttribute('aria-label', '작업 설정: ' + model + ', 추론 ' + effort);
    $('composer-options').title = model + ' · ' + effort;
    $('permission-help').textContent = state.busy ? '작업이 끝나면 권한을 변경할 수 있습니다.' : ({'read-only':'파일을 읽고 검토합니다.', 'workspace-write':'선택한 프로젝트의 파일을 수정할 수 있습니다.', 'danger-full-access':'앱에 허용된 기기 파일과 명령에 접근할 수 있습니다.'}[$('permissions').value] || '');
    const current = state.models.find(m => (m.model || m.id) === $('model').value) || state.models.find(m => m.isDefault);
    $('model-help').textContent = current?.description || (current ? '연결된 계정에서 사용할 수 있는 모델입니다.' : '기본 모델을 사용합니다.');
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
    target.append(node('div', autocomplete.type === '$' ? '스킬' : '파일 및 앱', 'autocomplete-heading'));
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
    const retry = {label:'다시 불러오기', detail:'사용 가능한 목록을 새로 확인합니다.', run:() => queryAutocomplete(directory)};
    drawAutocomplete([], ['사용 가능한 ' + (type === '$' ? '스킬' : '파일 및 앱') + '을 불러오는 중…']);
    if (type === '$') {
      try {
        const result = await rpc('skills/list', {cwds:state.cwd ? [state.cwd] : [], forceReload:true});
        if (!current()) return;
        const items = [], seen = new Set(), messages = [];
        for (const group of result.data || []) {
          for (const error of group.errors || []) messages.push(error.message || '일부 스킬을 읽지 못했습니다.');
          for (const skill of group.skills || []) {
            if (skill.enabled === false || !skill.name || !skill.path || seen.has(skill.path)) continue;
            if (query && !(skill.name + ' ' + (skill.description || '')).toLowerCase().includes(query.toLowerCase())) continue;
            seen.add(skill.path); items.push({kind:'skill', name:skill.name, path:skill.path, label:'$' + skill.name, detail:skill.description || skill.path});
          }
        }
        if (!items.length) messages.push(query ? '일치하는 스킬이 없습니다.' : '사용 가능한 스킬이 없습니다. 스킬 폴더를 가져와 추가하세요.');
        drawAutocomplete([...items, {label:'스킬 가져오기', detail:'SKILL.md가 들어 있는 폴더 선택', run:() => { hideAutocomplete(); $('skill-import').click(); }}], messages);
      } catch (error) {
        if (current()) drawAutocomplete([retry], ['스킬 목록을 불러오지 못했습니다. ' + error.message]);
      }
      return;
    }
    const sections = {files:[], apps:[]}, messages = {files:'파일을 불러오는 중…', apps:'앱을 불러오는 중…'};
    const render = () => { if (current()) drawAutocomplete([...sections.files, ...sections.apps], Object.values(messages).filter(Boolean)); };
    // Render each source when ready: a slow app connection must not hide local files.
    const filesRequest = state.workspace?.selected
      ? call(query ? 'files.search' : 'files.list', query ? {query} : {path:directory})
      : Promise.resolve({entries:[]});
    filesRequest.then(result => {
      if (!current()) return;
      sections.files = (result.entries || []).filter(x => x.path).map(x => x.directory
        ? {label:x.name + '/', detail:'폴더 열기', run:() => queryAutocomplete(x.path)}
        : {kind:'mention', name:x.name || C.basename(x.path), path:x.path, label:'@' + (x.name || C.basename(x.path)), detail:'파일 · ' + x.path});
      if (directory && !query) sections.files.unshift({label:'상위 폴더', detail:directory, run:() => queryAutocomplete(directory.split('/').slice(0,-1).join('/'))});
      messages.files = !state.workspace?.selected ? '프로젝트를 선택하면 파일 목록도 표시됩니다.' : sections.files.length ? '' : '일치하는 파일이 없습니다.';
      render();
    }).catch(error => { if (current()) { messages.files = '파일 목록을 불러오지 못했습니다. ' + error.message; sections.files = [retry]; render(); } });
    rpc('app/list', {}).then(result => {
      if (!current()) return;
      sections.apps = (result.data || result.apps || []).filter(x => x.isAccessible && x.isEnabled && (!query || (x.name || x.id || '').toLowerCase().includes(query.toLowerCase())))
        .map(x => ({kind:'mention', name:x.name || x.id, path:'app://' + x.id, label:'@' + (x.name || x.id), detail:'연결된 앱'}));
      messages.apps = sections.apps.length ? '' : '사용 가능한 연결 앱이 없습니다.'; render();
    }).catch(error => { if (current()) { messages.apps = '앱 목록을 불러오지 못했습니다. ' + error.message; if (!sections.files.includes(retry)) sections.apps = [retry]; render(); } });
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
    catch { toast('첨부 초안을 저장할 공간이 부족합니다. 첨부를 줄인 뒤 다시 시도해 주세요.'); return; }
    if (scope === draftScope) { draftContext = context; renderDraftContext(); updateSend(); }
    if (result.errors?.length && !result.cancelled) toast(result.errors.join('\n'));
    if (receipt) { handledReceipts.add(receipt); call('attachments.ack', {receiptId:receipt}).catch(() => {}); }
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
    if (!C.safeImageUrl(attachment.url)) { figure.append(node('p', '이미지 주소를 읽을 수 없습니다.', 'muted')); return figure; }
    const open = button('', () => previewImage(attachment, gallery, group), 'image-open');
    open.setAttribute('aria-label', (label || attachment.name || '생성 이미지') + ' 크게 보기');
    const img = node('img'); img.src = attachment.url; img.alt = label || attachment.name || '생성 이미지'; img.loading = 'lazy'; img.decoding = 'async';
    if (attachment.width && attachment.height) { img.width = attachment.width; img.height = attachment.height; }
    const error = node('p', '이미지를 불러오지 못했습니다.', 'muted'); error.hidden = true;
    img.addEventListener('load', () => { if (following) scrollLatest(); });
    img.addEventListener('error', () => { error.hidden = false; });
    open.append(img); figure.append(open, error);
    const caption = node('figcaption'); caption.append(node('span', attachment.name || label || '이미지'));
    if (attachment.id) caption.append(button('저장', () => call('images.export', {id:attachment.id, name:attachment.name})));
    figure.append(caption); return figure;
  }
  function previewImage(attachment, gallery = [attachment], group = null) {
    if (!C.safeImageUrl(attachment.url)) return;
    viewerImages = gallery.filter(a => a && C.safeImageUrl(a.url)); viewerIndex = Math.max(0, viewerImages.indexOf(attachment)); viewerGroup = group;
    updateViewer(); show('image-dialog');
  }
  function updateViewer(reset = true) {
    const attachment = viewerImages[viewerIndex]; if (!attachment) return; openedImage = attachment;
    $('image-title').textContent = attachment.name || '이미지'; $('image-preview').src = attachment.url;
    $('image-preview').alt = attachment.name || '이미지'; $('image-save').hidden = !attachment.id;
    $('image-counter').textContent = (viewerIndex + 1) + ' / ' + viewerImages.length;
    $('image-navigation').hidden = viewerImages.length < 2;
    $('image-prev').disabled = viewerIndex === 0; $('image-next').disabled = viewerIndex === viewerImages.length - 1;
    $('image-thumbnails').replaceChildren(); $('image-thumbnails').hidden = viewerImages.length < 2;
    viewerImages.forEach((item, i) => {
      const b = button('', () => { viewerIndex = i; updateViewer(); }, 'image-thumbnail');
      b.setAttribute('aria-label', (i+1) + '번째 이미지'); b.setAttribute('aria-current', String(i === viewerIndex));
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
    gallery.setAttribute('aria-label', '이미지 ' + attachments.length + '장');
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
        const b = button(attachment.name || '첨부 파일', () => call('attachments.export', {id:attachment.id, name:attachment.name}), 'attachment-download');
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
    const placeholder = node('div', '이미지 불러오는 중…', 'image-placeholder'); gallery.append(placeholder);
    const key = (state.workspace.key || state.cwd || '') + '\n' + path;
    if (!imageReads.has(key)) {
      const request = call('images.read', {path}); imageReads.set(key, request);
      request.catch(() => imageReads.delete(key));
    }
    imageReads.get(key).then(attachment => {
      if (placeholder.isConnected) { gallery.attachments[index] = attachment; placeholder.replaceWith(imageCard(attachment, label, gallery.attachments)); }
    }).catch(e => { if (placeholder.isConnected) placeholder.textContent = '이미지를 불러오지 못했습니다: ' + e.message; });
  }
  async function copyText(text, btn, normalText = '복사', copiedText = '복사됨!') {
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
      toast('클립보드에 복사하지 못했습니다.');
    }
  }
  function codeCard(lang, codeText) {
    const card = node('div', null, 'code-card');
    const head = node('div', null, 'code-head');
    const langSpan = node('span', (lang || 'code').trim().toLowerCase(), 'code-lang');
    const copy = button('복사', () => copyText(codeText, copy), 'code-copy-btn');
    copy.setAttribute('aria-label', '코드 복사');
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
  function characterIcon(name, cls = '') {
    const img = node('img', null, 'chat-character ' + cls); img.src = C.chatIconUrl(name) || C.chatIconUrl('explaining');
    img.alt = ''; img.setAttribute('aria-hidden', 'true'); img.width = 96; img.height = 96; img.loading = 'lazy'; img.decoding = 'async';
    return img;
  }
  function drawStatusIcons() {
    for (const [id, name] of [['welcome-character','greeting'], ['activity-character',activityIcon]]) {
      const host = $(id); host.hidden = !chatIconsEnabled;
      if (!chatIconsEnabled) host.replaceChildren();
      else if (host.dataset.icon !== name || !host.firstChild) { host.replaceChildren(characterIcon(name)); host.dataset.icon = name; }
    }
  }
  function setChatIcons() {
    chatIconsEnabled = $('chat-icons-toggle').checked;
    try { localStorage.setItem('chat-icons', chatIconsEnabled ? 'on' : 'off'); } catch { toast('아이콘 설정을 저장하지 못했습니다.'); }
    drawStatusIcons(); drawMessages();
  }
  function drawMessages() {
    const elements = new Map(Array.from($('messages').children).map(n => [n.dataset.id, n]));
    const keep = new Set();
    C.groupImageMessages(state.messages || []).forEach(m => {
      keep.add(m.id); let el = elements.get(m.id);
      if (!el) { el = node('article', null, 'message ' + (m.role === 'user' ? 'user' : 'assistant')); el.dataset.id = m.id; $('messages').append(el); }
      const iconName = m.imageStatus === 'generating' ? 'working' : state.busy && m.id === state.messages[state.messages.length - 1]?.id ? 'thinking' : C.messageIcon(m);
      const signature = JSON.stringify([m.text, m.images, m.attachments, m.imageStatus, m.imageError, chatIconsEnabled, iconName]);
      if (el.dataset.signature !== signature) {
        el.dataset.signature = signature;
        if (m.role === 'user') { el.textContent = m.text; if (m.attachments?.length) el.append(sentAttachments(m.attachments)); }
        else {
          const previousIcon = el.querySelector('.chat-character');
          prose(el, m.text || '');
          if (chatIconsEnabled) el.prepend(previousIcon?.getAttribute('src') === C.chatIconUrl(iconName) ? previousIcon : characterIcon(iconName));
          if (m.images?.length) el.append(imageGallery(m.images, m.id));
          if (m.imageStatus === 'generating') el.append(node('p', '이미지 생성 중…', 'image-placeholder'));
          if (m.imageError) el.append(node('p', m.imageError, 'image-error'));
          if (m.text?.trim() && m.imageStatus !== 'generating') {
            const actions = node('div', null, 'message-actions');
            const copy = button('복사', () => copyText(m.text, copy), 'msg-copy-btn');
            copy.setAttribute('aria-label', '메시지 복사');
            copy.prepend(icon('copy'));
            actions.append(copy);
            el.append(actions);
          }
        }
      }
    });
    for (const [id, el] of elements) if (!keep.has(id)) el.remove();
    if (following) scrollLatest();
    else $('jump-latest').hidden = !state.messages.length;
  }
  function efforts() {
    const selected = state.models.find(m => (m.model || m.id) === $('model').value) || state.models.find(m => m.isDefault);
    const previous = $('effort').value; $('effort').replaceChildren(new Option('기본', ''));
    for (const e of selected?.supportedReasoningEfforts || []) $('effort').add(new Option(e.reasoningEffort, e.reasoningEffort));
    if ([...$('effort').options].some(o => o.value === previous)) $('effort').value = previous;
    optionsSummary();
  }
  function renderModelList() {
    const target = $('model-list'); target.replaceChildren();
    const choices = [{id:'', label:'기본 모델', description:'기본 모델을 사용합니다.'}, ...state.models.map(m => ({id:m.model || m.id, label:m.displayName || m.model || m.id, description:m.description || m.model || m.id}))];
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
      $('devtools-status').textContent = '개발 도구 정보를 제공하지 않는 앱 버전입니다. 실행 확인으로 현재 상태를 점검할 수 있습니다.';
      $('terminal-tools-note').textContent = '현재 프로젝트 폴더에서 셸 명령을 실행합니다. 개발 도구 상태는 설정 > 도구에서 확인하세요.';
    } else {
      if (info.error) $('devtools-status').textContent = '준비 오류: ' + info.error;
      else if (!info.bundled) $('devtools-status').textContent = '번들 개발 도구가 없습니다.';
      else if (info.prepared) $('devtools-status').textContent = '번들 준비 완료. 아래 실행 확인으로 현재 상태를 검증하세요.';
      else $('devtools-status').textContent = '번들 개발 도구가 준비 중이거나 아직 확인되지 않았습니다.';
      for (const tool of Array.isArray(info.tools) ? info.tools : []) {
        const row = node('span', (tool?.name || '도구') + (tool?.version ? ' ' + tool.version : ' 버전 확인 전'), 'devtools-version');
        versions.append(row);
      }
      const names = (Array.isArray(info.tools) ? info.tools.map(tool => tool?.name).filter(Boolean) : []).join(' · ');
      $('terminal-tools-note').textContent = info.bundled
        ? `현재 프로젝트 폴더에서 ${names || 'Python · Node.js · Git · npm · pip'} 명령을 실행합니다.`
        : '현재 프로젝트 폴더에서 셸 명령을 실행합니다. 번들 개발 도구는 사용할 수 없습니다.';
    }
    if (devtoolsCheckSummary) $('devtools-status').textContent = devtoolsCheckSummary;
    const output = $('devtools-output');
    output.hidden = !devtoolsCheckResult;
    output.textContent = devtoolsCheckResult || '';
    $('devtools-check').disabled = devtoolsChecking;
    $('devtools-check').textContent = devtoolsChecking ? '실행 확인 중…' : '도구 실행 확인';
  }
  async function checkDevtools() {
    devtoolsChecking = true; renderDevtools();
    try {
      const result = await call('devtools.check');
      const checks = Array.isArray(result?.checks) ? result.checks : [];
      devtoolsCheckResult = checks.length
        ? checks.map(check => `${check?.ok ? '✓' : '✕'} ${check?.name || '도구'}\n${check?.output || ''}`).join('\n\n')
        : (result?.ok ? '도구 실행 확인이 완료되었습니다.' : '실행 결과를 받지 못했습니다. 다시 시도해 주세요.');
      devtoolsCheckSummary = result?.ok ? '도구 실행 확인을 완료했습니다.' : '일부 도구를 실행하지 못했습니다. 출력에서 원인을 확인한 뒤 다시 시도해 주세요.';
    } catch (error) {
      devtoolsCheckResult = '실행 확인 실패\n' + (error?.message || '알 수 없는 오류');
      devtoolsCheckSummary = '실행 확인에 실패했습니다. 다시 시도할 수 있습니다.';
    } finally {
      devtoolsChecking = false; renderDevtools();
    }
  }
  async function selectProject(key) {
    await call('projects.select', {key:key || ''}); sidebar(false);
  }
  async function newChat(workspaceKey) {
    await call('chat.new', {workspaceKey:workspaceKey || ''}); sidebar(false);
  }
  async function removeProject(project) {
    if (!confirm('“' + (project.name || '프로젝트') + '”을 프로젝트 목록에서 제거할까요?\n\n폴더와 파일은 삭제되지 않습니다. 이 프로젝트의 기존 대화는 “연결 해제된 프로젝트”에 남아 폴더를 다시 연결할 수 있습니다.')) return;
    await call('projects.remove', {key:project.key});
    toast('프로젝트 목록에서 제거했습니다. 폴더와 파일은 그대로 있습니다.');
  }
  function sessionAction(session) {
    $('session-actions-title').textContent = session.title || '대화 관리';
    const action = (label, fn, cls) => button(label, async () => { close('session-actions-dialog'); await fn(); }, cls);
    $('session-actions').replaceChildren(
      action('이름 변경', async () => {
        const title = await input('대화 이름 변경', '새 제목을 입력하세요.', session.title || '');
        if (title && title.trim()) {
          await call('chat.rename', {id: session.id, title: title.trim()});
          toast('대화 이름을 변경했습니다.');
        }
      }),
      action('삭제', async () => {
        if (confirm('"' + (session.title || '대화') + '" 대화를 삭제할까요?')) {
          const result = await call('chat.delete', {id: session.id});
          toast(result.deletionPending ? '삭제 요청을 저장했습니다. Codex에 연결되면 원본 기록도 삭제합니다.' : '대화를 삭제했습니다.');
        }
      }, 'secondary-button danger')
    );
    show('session-actions-dialog');
  }
  function sessionRow(session) {
    const active = session.id === state.threadId;
    const row = node('div', null, 'session-row' + (active ? ' active' : ''));
    const b = button(session.title || '제목 없는 대화', async () => { await call('chat.resume', {id:session.id}); sidebar(false); }, 'session' + (active ? ' active' : ''));
    b.title = session.workspace || '일반 대화';
    const menu = button('', () => sessionAction(session), 'icon-button session-more');
    menu.setAttribute('aria-label', (session.title || '대화') + ' 관리');
    menu.append(icon('more'));
    row.append(b, menu);
    return row;
  }
  function renderProjects() {
    const target = $('projects'); target.replaceChildren();
    const projects = state.projects || [];
    const general = node('div', null, 'general-project' + (!state.workspace.selected ? ' selected' : ''));
    const generalButton = button('', () => selectProject(''), 'project-button'); generalButton.append(icon('code'), node('span', '일반 대화')); generalButton.setAttribute('aria-current', String(!state.workspace.selected));
    const generalNew = button('', () => newChat(''), 'icon-button project-new'); generalNew.setAttribute('aria-label', '일반 새 대화'); generalNew.append(icon('plus')); general.append(generalButton, generalNew); target.append(general);
    for (const project of projects) {
      const expandedKey = 'project-expanded:' + project.key, expanded = localStorage.getItem(expandedKey) !== 'false';
      const section = node('section', null, 'project-tree' + (project.selected ? ' selected' : ''));
      const row = node('div', null, 'project-row');
      const toggle = button('', () => { const expandedNow = section.classList.toggle('collapsed') === false; localStorage.setItem(expandedKey, String(expandedNow)); toggle.setAttribute('aria-expanded', String(expandedNow)); }, 'tree-toggle'); toggle.setAttribute('aria-label', project.name + ' 대화 펼치기'); toggle.setAttribute('aria-expanded', String(expanded)); toggle.append(icon('down'));
      const select = button('', () => selectProject(project.key), 'project-button'); select.append(icon('folder'), node('span', project.name || '이름 없는 프로젝트')); select.setAttribute('aria-current', String(!!project.selected));
      const more = button('', () => newChat(project.key), 'icon-button project-new'); more.setAttribute('aria-label', project.name + '에서 새 대화'); more.append(icon('plus'));
      const menu = button('', () => removeProject(project), 'icon-button project-more'); menu.setAttribute('aria-label', project.name + ' 메뉴'); menu.append(icon('more'));
      row.append(toggle, select, more, menu); section.append(row);
      const children = node('div', null, 'project-sessions');
      if (project.available === false) { children.append(node('p', '폴더 접근을 다시 연결해야 합니다.', 'sidebar-empty'), button('폴더 다시 연결', () => pickFolder(project.key), 'new-thread')); }
      else children.append(button('새 대화', () => newChat(project.key), 'new-thread'));
      for (const session of (state.sessions || []).filter(s => s.workspaceKey === project.key)) children.append(sessionRow(session));
      section.append(children); if (!expanded) section.classList.add('collapsed'); target.append(section);
    }
    const detached = (state.sessions || []).filter(s => s.workspaceKey && !projects.some(p => p.key === s.workspaceKey));
    if (detached.length) {
      const section = node('section', null, 'project-tree detached-projects'); section.append(node('div', '연결 해제된 프로젝트', 'section-title'));
      const children = node('div', null, 'project-sessions');
      children.append(node('p', '폴더와 파일은 남아 있습니다. 대화를 열면 읽기 전용으로 보존되며, 새 작업은 폴더를 다시 연결한 뒤 시작할 수 있습니다.', 'sidebar-empty'));
      for (const session of detached) {
        const row = node('div', null, 'detached-session'); const reconnect = button('폴더 다시 연결', () => pickFolder(session.workspaceKey), 'new-thread');
        row.append(sessionRow(session), reconnect); children.append(row);
      }
      section.append(children); target.append(section);
    }
    if (!projects.length) target.append(button('작업 폴더 선택', pickFolder, 'project-button'));
  }
  function render(next) {
    const changedThread = state.threadId !== next.threadId;
    setDraftScope(next); state = next;
    state.models ||= []; state.messages ||= []; state.projects ||= []; state.workspace ||= {}; state.account ||= {};
    const logged = C.isLoggedIn(state.account), selected = state.workspace.selected;
    const name = selected ? state.workspace.name : '일반 대화';
    $('project-label').textContent = name; $('context-folder').textContent = name; $('header-project').textContent = name;
    $('header-title').textContent = (state.sessions || []).find(s => s.id === state.threadId)?.title || '새 대화';
    $('welcome').hidden = state.messages.length > 0; $('onboarding').hidden = logged; $('suggestions').hidden = !logged;
    const generalPrompts = ['작업을 계획하고 필요한 정보를 정리해줘.','아이디어를 구조화하고 다음 단계를 제안해줘.','무엇을 도와줄 수 있는지 알려줘.'];
    document.querySelectorAll('[data-prompt]').forEach((button, i) => { button.dataset.projectPrompt ||= button.dataset.prompt; button.dataset.prompt = selected ? button.dataset.projectPrompt : generalPrompts[i]; if (button.lastChild?.nodeType === Node.TEXT_NODE) button.lastChild.textContent = selected ? ['폴더 살펴보기','프로젝트 이해하기','변경 사항 검토'][i] : ['작업 계획하기','아이디어 정리하기','도움말 보기'][i]; });
    $('login-step').textContent = logged ? '✓' : '1'; $('login-step').classList.toggle('done', logged);
    $('folder-step').textContent = selected ? '✓' : '2'; $('folder-step').classList.toggle('done', !!selected);
    $('account-name').textContent = state.account.email || (logged ? 'ChatGPT' : '계정 연결');
    $('account-plan').textContent = state.account.planType || (logged ? '연결된 계정' : 'ChatGPT로 로그인');
    $('avatar').textContent = (state.account.email || 'M').charAt(0).toUpperCase();
    $('connection-dot').classList.toggle('online', !!state.ready);
    $('runtime-status').textContent = state.status; $('settings-status').textContent = state.status; $('settings-indicator').textContent = state.ready ? '연결됨' : '연결 안 됨'; $('settings-indicator').classList.toggle('online', !!state.ready);
    const pendingDeletes = Number(state.pendingDeletionCount) || 0;
    $('pending-deletions').hidden = pendingDeletes === 0;
    $('pending-deletions').textContent = pendingDeletes ? `원본 삭제 대기 ${pendingDeletes}건. 아래 ‘Codex 시작 / 다시 연결’을 누르면 다시 시도합니다.` : '';
    $('send').hidden = state.busy; $('stop').hidden = !state.busy; $('activity').hidden = !state.busy;
    if (!state.busy) activityIcon = 'thinking';
    drawStatusIcons();
    $('permissions').value = state.permissions || 'workspace-write'; $('permissions').disabled = !!state.busy;
    document.querySelectorAll('input[name="permission"]').forEach(r => { r.checked = r.value === $('permissions').value; r.disabled = !!state.busy; });
    $('terminal-cwd').textContent = state.cwd || '';
    $('storage-status').textContent = state.directWorkspace ? '선택한 폴더에서 셸 명령을 실행합니다.' : '폴더의 셸 접근은 기기 파일 권한이 필요합니다. 문서 제공자 폴더는 파일 도구로 접근합니다.';
    $('storage-access').textContent = state.allFilesAccess ? '기기 파일 접근 설정' : '기기 파일 접근 허용';
    renderDevtools();
    renderProjects();
    $('sessions').replaceChildren();
    for (const session of (state.sessions || []).filter(s => !(s.workspaceKey || s.workspace))) $('sessions').append(sessionRow(session));
    if (!state.sessions?.length) $('sessions').append(node('p', '대화를 시작하면 여기에 표시됩니다.', 'sidebar-empty'));
    const key = JSON.stringify(state.models);
    if (key !== modelKey) { modelKey = key; const value = draftOptions.model || $('model').value; $('model').replaceChildren(new Option('기본 모델', '')); for (const m of state.models) $('model').add(new Option(m.displayName || m.model || m.id, m.model || m.id)); if ([...$('model').options].some(o => o.value === value)) $('model').value = value; efforts(); if ([...$('effort').options].some(o => o.value === draftOptions.effort)) $('effort').value = draftOptions.effort; }
    renderModelList();
    if (changedThread) { following = true; $('event-log').replaceChildren(); $('messages').replaceChildren(); }
    updateSend(); optionsSummary();
    drawMessages();
    if (logged && $('login-dialog').open) { login = null; close('login-dialog'); }
  }
  function logEvent(title, data) {
    const detail = node('details', null, 'event-card'); detail.append(node('summary', title), node('pre', typeof data === 'string' ? data : JSON.stringify(data, null, 2), 'console'));
    $('event-log').append(detail);
    while ($('event-log').children.length > 150) $('event-log').firstChild.remove();
  }
  async function startLogin() {
    if (C.isLoggedIn(state.account)) return show('settings-dialog');
    show('login-dialog'); $('device-code').textContent = '연결 준비 중'; $('open-login').disabled = true;
    login = await call('auth.login');
    if (!C.safeLoginUrl(login.verificationUrl)) throw new Error('잘못된 로그인 응답입니다.');
    $('device-code').textContent = login.userCode; $('open-login').disabled = false;
  }
  async function pickFolder(projectKey = '') { const result = await call('files.pick', projectKey ? {projectKey} : {}); if (!result.cancelled) { folder = ''; if (!$('file-panel').hidden) await listFiles(); } }
  async function listFiles(query = '') {
    if (!state.workspace.selected) { $('file-list').replaceChildren(button('작업 폴더 선택', pickFolder)); return; }
    const version = ++fileSeq; $('file-list').replaceChildren(node('p', '불러오는 중…', 'empty-note'));
    const result = await call(query ? 'files.search' : 'files.list', query ? {query} : {path: folder});
    if (version !== fileSeq) return;
    $('breadcrumbs').textContent = query ? '검색: ' + query : state.workspace.name + (folder ? ' / ' + folder : '');
    $('file-up').disabled = !folder || !!query; $('file-list').replaceChildren();
    for (const entry of result.entries || []) {
      const row = node('div', null, 'file-entry');
      const b = button('', async () => { if (entry.directory) { folder = entry.path; $('file-query').value = ''; await listFiles(); } else await openFile(entry.path); }, 'file-row');
      b.append(icon(entry.directory ? 'folder' : 'file'), node('span', entry.name), node('small', entry.directory ? '' : C.size(entry.size))); row.append(b);
      const menu = button('⋯', () => fileAction(entry), 'icon-button'); menu.setAttribute('aria-label', entry.name + ' 관리'); row.append(menu); $('file-list').append(row);
    }
    if (!result.entries?.length) $('file-list').append(node('p', '항목이 없습니다.', 'empty-note'));
    if (result.truncated) $('file-list').append(node('p', '일부 검색 결과입니다. 더 구체적인 이름으로 검색하세요.', 'empty-note'));
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
  async function mutate(operation, args) { const result = await call('files.mutate', {operation, arguments: args}); toast('적용했습니다.'); if (!$('file-panel').hidden) await listFiles(); return result; }
  function fileAction(entry) {
    $('file-actions-title').textContent = entry.name;
    const action = (label, fn, cls) => button(label, async () => { close('file-actions-dialog'); await fn(); }, cls);
    $('file-actions').replaceChildren(
      action('이름 변경', async () => { const name = await input('이름 변경', '새 이름', entry.name); if (name) await mutate('mobile_rename', {path:entry.path, name}); }),
      action('이동', async () => { const destination = await input('이동', '작업 폴더 기준 대상 폴더 경로 (루트는 빈칸)'); if (destination !== null) await mutate('mobile_move', {path:entry.path, destination}); }),
      action('삭제', () => mutate('mobile_delete', {path:entry.path}), 'secondary-button danger')
    );
    show('file-actions-dialog');
  }
  async function loadInstructions() {
    if (instructionsSaving) return;
    if (instructionsLoaded && $('instructions-editor').value !== instructionsOriginal && !confirm('저장하지 않은 지침을 다시 불러올까요?')) return;
    $('instructions-status').textContent = '지침을 불러오는 중…';
    $('instructions-editor').disabled = true; $('instructions-save').disabled = true;
    try {
      const result = await call('instructions.read');
      instructionsOriginal = result.content || ''; $('instructions-editor').value = instructionsOriginal;
      instructionsLoaded = true; $('instructions-path').textContent = '저장 위치: ' + (result.activePath || result.path || '.codex/AGENTS.md');
      $('instructions-status').textContent = result.notice || '저장된 지침을 편집할 수 있습니다.';
    } catch (error) { $('instructions-status').textContent = '지침을 불러오지 못했습니다. ' + error.message; }
    finally { $('instructions-editor').disabled = !instructionsLoaded; $('instructions-save').disabled = !instructionsLoaded; }
  }
  async function saveInstructions() {
    if (!instructionsLoaded || instructionsSaving) return;
    const content = $('instructions-editor').value; instructionsSaving = true;
    $('instructions-save').disabled = true; $('instructions-status').textContent = '저장 중…';
    try {
      const result = await call('instructions.save', {content});
      instructionsOriginal = typeof result.content === 'string' ? result.content : content;
      if ($('instructions-editor').value === content) $('instructions-editor').value = instructionsOriginal;
      if (result.activePath || result.path) $('instructions-path').textContent = '저장 위치: ' + (result.activePath || result.path);
      $('instructions-status').textContent = '저장했습니다. 다음 요청부터 적용됩니다.' + (result.notice ? ' ' + result.notice : '');
    } catch (error) { $('instructions-status').textContent = '저장하지 못했습니다. 작성 내용은 그대로 남아 있습니다. ' + error.message; }
    finally { instructionsSaving = false; $('instructions-save').disabled = false; }
  }
  async function config() { const result = await call('config.read'); configOriginal = result.content; $('config-editor').value = result.content; show('config-dialog'); }
  async function recovery() {
    show('recovery-dialog'); const {entries} = await call('recovery.list'); $('recovery-list').replaceChildren();
    for (const entry of entries || []) { const row = node('div', null, 'recovery-item'), desc = node('div'); desc.append(node('strong', entry.path), node('small', new Date(entry.timestamp).toLocaleString())); row.append(desc, button('다른 위치에 저장', () => call('recovery.export', {id: entry.id, name: C.basename(entry.path)}))); $('recovery-list').append(row); }
    if (!entries?.length) $('recovery-list').append(node('p', '저장된 복구 사본이 없습니다.', 'empty-note'));
  }
  let toolsScope = "", toolsGeneration = 0;
  const currentToolsScope = () => JSON.stringify([state.cwd, state.threadId, state.account]);
  function usageTime(value) { return Number.isFinite(value) ? new Date(value * 1000).toLocaleString() : '알 수 없음'; }
  function usageDuration(value) { return Number.isFinite(value) ? (value >= 60 ? Math.round(value / 60) + '시간' : value + '분') : '기간 정보 없음'; }
  function usageRows(result) {
    const buckets = result?.rateLimitsByLimitId && Object.keys(result.rateLimitsByLimitId).length ? Object.entries(result.rateLimitsByLimitId) : [['codex', result?.rateLimits]];
    return buckets.filter(([, value]) => value && (value.primary || value.secondary)).flatMap(([key, value]) => [['기본', value.primary], ['보조', value.secondary]].filter(([, window]) => window && Number.isFinite(window.usedPercent)).map(([label, window]) => {
      const remaining = Math.max(0, Math.min(100, 100 - window.usedPercent));
      return {label:(value.limitName || value.normalModelSlug || key) + ' · ' + label, text:'사용 ' + window.usedPercent + '% · 남은 ' + remaining + '% · ' + usageDuration(window.windowDurationMins) + ' · 재설정 ' + usageTime(window.resetsAt)};
    }));
  }
  async function loadUsage() {
    if (usageLoading) return;
    const status = $('usage-status'), target = $('usage-limits');
    if (!C.isLoggedIn(state.account)) { status.textContent = '로그인 후 사용 한도를 조회할 수 있습니다.'; target.replaceChildren(); return; }
    const accountScope = JSON.stringify(state.account);
    usageLoading = true; $('usage-refresh').disabled = true; status.textContent = '사용 한도를 조회하는 중…';
    try {
      const result = await rpc('account/rateLimits/read', {}), rows = usageRows(result);
      if (JSON.stringify(state.account) !== accountScope) { target.replaceChildren(); status.textContent = '계정이 바뀌었습니다. 다시 조회해 주세요.'; return; }
      target.replaceChildren(...rows.map(row => { const item = node('p', null, 'muted'); item.append(node('strong', row.label), document.createTextNode(' ' + row.text)); return item; }));
      status.textContent = rows.length ? '현재 계정의 Codex 사용 한도입니다.' : '사용 한도 정보가 제공되지 않았습니다.';
    } catch (error) { target.replaceChildren(); status.textContent = '사용 한도를 조회할 수 없습니다. ' + error.message; }
    finally { usageLoading = false; $('usage-refresh').disabled = false; }
  }
  async function loadTools(force = false) {
    show('tools-dialog'); const target = $('tools-list');
    const scope = currentToolsScope(), generation = ++toolsGeneration;
    if (toolsScope !== scope) { toolCache.fill(null); toolsScope = scope; }
    if (!toolCache.some(Boolean)) target.replaceChildren(node('p', '불러오는 중…', 'muted'));
    const cwds = state.cwd ? [state.cwd] : [];
    const results = await Promise.allSettled([rpc('plugin/list', {cwds, ...(force ? {forceRefetch:true} : {})}), rpc('skills/list', {cwds, ...(force ? {forceReload:true} : {})}), rpc('mcpServerStatus/list', {limit: 100, ...(state.threadId ? {threadId: state.threadId} : {})})]);
    if (generation !== toolsGeneration || scope !== currentToolsScope()) return;
    target.replaceChildren();
    const card = (name, description, parent = target) => { const c = node('div', null, 'tool-card'); c.append(node('strong', name), node('p', description || '', 'muted')); parent.append(c); return c; };
    ['플러그인', '스킬', 'MCP 서버'].forEach((title, i) => {
      target.append(node('h3', title));
      const result = results[i];
      if (result.status === 'fulfilled') toolCache[i] = result.value;
      const data = result.status === 'fulfilled' ? result.value : toolCache[i];
      if (result.status === 'rejected') { card('불러오지 못했습니다', result.reason.message + (data ? ' 이전 목록을 표시합니다.' : '')); if (!data) return; }
      if (i === 0) {
        for (const market of data.marketplaces || []) {
          const heading = node('p', market.interface?.displayName || market.name, 'muted'); target.append(heading);
          for (const plugin of market.plugins || []) {
            const c = card(plugin.interface?.displayName || plugin.name, plugin.interface?.shortDescription || plugin.id);
            const params = {pluginName: plugin.name, ...(market.path ? {marketplacePath: market.path} : {remoteMarketplaceName: market.name})};
            c.append(button('상세', async () => { const detail = await rpc('plugin/read', params); logEvent('플러그인: ' + plugin.name, detail.plugin); show('activity-dialog'); }));
            if (plugin.installed) {
              c.append(button(plugin.enabled ? '비활성화' : '활성화', async () => { await rpc('config/value/write', {keyPath: 'plugins.' + JSON.stringify(plugin.id) + '.enabled', value: !plugin.enabled, mergeStrategy: 'replace'}); await loadTools(); }));
              c.append(button('제거', async () => { if (!confirm(plugin.name + ' 플러그인을 제거할까요?')) return; await rpc('plugin/uninstall', {pluginId: plugin.id}); await loadTools(); }));
            } else c.append(button('설치', async () => {
              const detail = await rpc('plugin/read', params);
              const summary = detail.plugin;
              if (!confirm(plugin.name + ' 설치\n\n' + (summary.description || '') + '\n\n스킬 ' + (summary.skills?.length || 0) + '개 · MCP ' + (summary.mcpServers?.length || 0) + '개 · 훅 ' + (summary.hooks?.length || 0) + '개')) return;
              const result = await rpc('plugin/install', params);
              for (const app of result.appsNeedingAuth || []) if (app.installUrl) { const c = card(app.name + ' 계정 연결', '플러그인을 사용하려면 계정 연결이 필요합니다.'); c.append(button('연결', () => call('ui.externalBrowser', {url: app.installUrl}))); }
              toast('플러그인을 설치했습니다.'); if (!result.appsNeedingAuth?.length) await loadTools();
            }));
          }
        }
        for (const e of data.marketplaceLoadErrors || []) card('마켓플레이스 오류', e.message);
        if (!data.marketplaces?.length) card('등록된 플러그인이 없습니다', '마켓플레이스를 추가하거나 계정 연결 상태를 확인하세요.');
      } else if (i === 1) {
        let count = 0;
        for (const entry of data.data || []) {
          for (const skill of entry.skills || []) {
            count++; const c = card(skill.interface?.displayName || skill.name, skill.description);
            c.append(node('p', skill.path, 'muted'), button(skill.enabled ? '비활성화' : '활성화', async () => { await rpc('skills/config/write', {path: skill.path, enabled: !skill.enabled}); await loadTools(); }), button('대화에 사용', () => { $('prompt').value += '$' + skill.name + ' '; close('tools-dialog'); $('prompt').focus(); }));
          }
          for (const e of entry.errors || []) card('스킬 로드 오류', e.message);
        }
        if (!count) card('설치된 스킬이 없습니다', 'SKILL.md가 들어 있는 스킬 폴더를 가져오거나 Codex에게 스킬 생성을 요청하세요.');
      } else {
        for (const server of data.data || []) {
          const c = card(server.name, (server.runtimeStatus?.status || server.authStatus) + ' · 도구 ' + Object.keys(server.tools || {}).length + '개');
          if (server.toolsError) c.append(node('p', server.toolsError, 'muted'));
          c.append(button('도구 보기', () => { logEvent('MCP: ' + server.name, server); show('activity-dialog'); }), button('로그인', async () => { const r = await rpc('mcpServer/oauth/login', {name: server.name}); await call('ui.externalBrowser', {url: r.authorizationUrl}); }));
        }
        if (!data.data?.length) card('설정된 MCP 서버가 없습니다', '서버 설정에서 HTTP 또는 stdio 서버를 추가할 수 있습니다.');
        if (data.nextCursor) target.append(node('p', '표시된 서버: 처음 100개', 'muted'));
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
          field = node('select'); field.add(new Option('선택하세요', ''));
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
            field = node('textarea'); field.placeholder = '항목마다 한 줄씩 입력하세요'; field.value = (spec.default || []).join('\n');
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
        if (entry.field && !entry.field.reportValidity()) throw new Error('입력 내용을 확인해 주세요.');
        const value = entry.read(); if (value !== undefined) entries.push([entry.key, value]);
      }
      return Object.fromEntries(entries);
    };
  }
  function nextRequest() {
    if (displayedRequest || !requestQueue.size) return;
    const [key, req] = requestQueue.entries().next().value; displayedRequest = key;
    $('request-title').textContent = req.method.includes('requestUserInput') ? 'Codex 질문' : req.method.includes('requestApproval') ? '작업 승인' : 'Codex 요청';
    $('request-reason').textContent = req.params.reason || req.params.message || req.method;
    $('request-detail').textContent = JSON.stringify(req.params, null, 2); $('request-fields').replaceChildren(); $('request-actions').replaceChildren();
    const complete = async result => { result = await result; await call('rpc.respond', {key, result}); requestQueue.delete(key); displayedRequest = null; close('request-dialog'); nextRequest(); };
    const add = (text, result, cls) => $('request-actions').append(button(text, () => complete(typeof result === 'function' ? result() : result), cls));
    if (req.method === 'item/commandExecution/requestApproval' || req.method === 'item/fileChange/requestApproval') {
      add('거절', {decision: 'decline'}); add('이번 대화에 허용', {decision: 'acceptForSession'}); add('허용', {decision: 'accept'}, 'primary-button');
    } else if (req.method === 'item/permissions/requestApproval') {
      add('거절', {permissions: {}, scope: 'turn'}); add('허용', {permissions: req.params.permissions, scope: 'turn'}, 'primary-button');
    } else if (req.method === 'item/tool/requestUserInput') {
      const answers = [];
      for (const question of req.params.questions || []) {
        const wrap = node('div', null, 'question'), label = node('label', question.question), field = node('input'); field.type = question.isSecret ? 'password' : 'text'; field.setAttribute('aria-label', question.question); label.append(field); wrap.append(label);
        if (question.options?.length) { const select = node('select'); select.setAttribute('aria-label', question.header || question.question); select.add(new Option('선택 또는 직접 입력', '')); for (const o of question.options) select.add(new Option(o.label + (o.description ? ' — ' + o.description : ''), o.label)); select.addEventListener('change', () => field.value = select.value); wrap.append(select); }
        answers.push([question.id, field]); $('request-fields').append(wrap);
      }
      add('답변 보내기', () => ({answers: Object.fromEntries(answers.map(([id, field]) => [id, {answers: [field.value]}]))}), 'primary-button');
    } else if (req.method === 'mcpServer/elicitation/request') {
      add('취소', {action: 'decline'});
      if (req.params.mode === 'url') {
        $('request-fields').append(button('연결 페이지 열기', () => call('ui.externalBrowser', {url: req.params.url})));
        add('완료', {action: 'accept'}, 'primary-button');
      } else {
        const read = elicitationForm(req.params.requestedSchema || {}, $('request-fields'));
        add('전송', () => ({action: 'accept', content: read()}), 'primary-button');
      }
    } else {
      const field = node('textarea'); field.setAttribute('aria-label', '프로토콜 응답 JSON'); field.value = '{}'; $('request-fields').append(node('p', '확장 프로토콜 요청입니다. 응답 JSON을 입력할 수 있습니다.', 'muted'), field);
      $('request-actions').append(button('작업 중지', async () => { await call('chat.stop'); requestQueue.delete(key); displayedRequest = null; close('request-dialog'); nextRequest(); })); add('응답 전송', () => JSON.parse(field.value), 'primary-button');
    }
    show('request-dialog');
  }
  window.mobileCodexEvent = (name, data) => {
    if (name === 'response') { const p = pending.get(data.id); if (p) { clearTimeout(p.timer); pending.delete(data.id); data.error ? p.reject(new Error(data.error)) : p.resolve(data.result || {}); } return; }
    if (name === 'state') render(data);
    else if (name === 'error' || name === 'notice') toast(data.message);
    else if (name === 'message.delta') { C.appendDelta(state.messages, data.id, data.delta); drawMessages(); }
    else if (name === 'tool') { activityIcon = /read|list|search/.test(data.name) ? 'inspecting' : 'working'; drawStatusIcons(); $('activity-text').textContent = data.name.replace('mobile_', '') + ' · ' + data.path; logEvent(data.name, data); }
    else if (name === 'agent.event') { if (!data.method.endsWith('/delta')) logEvent(data.method, data.params); if (data.method === 'turn/diff/updated') logEvent('변경 사항', data.params.diff); }
    else if (name === 'files.changed' && !$('file-panel').hidden) listFiles().catch(e => toast(e.message));
    else if (name === 'attachments.picked') acceptPickedAttachments(data, data.draftKey || '');
    else if (name === 'login.completed') { if (data.success) { login = null; close('login-dialog'); toast('ChatGPT 계정을 연결했습니다.'); } else $('login-help').textContent = data.error || '로그인이 취소되었습니다. 다시 연결해 주세요.'; }
    else if (name === 'terminal.output') { $('terminal-output').textContent += data.text; if ($('terminal-output').textContent.length > 1000000) $('terminal-output').textContent = $('terminal-output').textContent.slice(-1000000); $('terminal-output').scrollTop = $('terminal-output').scrollHeight; }
    else if (name === 'terminal.exit') $('terminal-output').textContent += '\n[종료 코드 ' + data.code + ']\n';
    else if (name === 'server.request') { requestQueue.set(data.key, data); nextRequest(); }
    else if (name === 'server.resolved') { requestQueue.delete(data.key); if (displayedRequest === data.key) { displayedRequest = null; close('request-dialog'); } nextRequest(); }
    else if (name === 'viewport') { document.body.classList.toggle('keyboard-open', !!data.keyboardVisible); viewportChanged(); }
    else if (name === 'theme') document.documentElement.dataset.theme = data.theme === 'dark' ? 'dark' : 'light';
    else if (name === 'back') window.mobileCodexBack();
  };
  window.mobileCodexBack = () => {
    const id = dialogs.at(-1);
    if (id) { if (id !== 'request-dialog') dismiss(id); return true; }
    if (document.body.classList.contains('sidebar-open')) { sidebar(false); return true; }
    if (!$('file-panel').hidden) { $('file-panel').hidden = true; return true; }
    saveDraft(); return false;
  };
  document.querySelectorAll('dialog').forEach(d => {
    d.addEventListener('close', () => { const i = dialogs.indexOf(d.id); if (i !== -1) dialogs.splice(i, 1); });
    d.addEventListener('cancel', e => { e.preventDefault(); if (d.id !== 'request-dialog') dismiss(d.id); });
  });
  document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => dismiss(b.dataset.close)));
  document.querySelectorAll('.sidebar-toggle').forEach(b => b.addEventListener('click', () => { if (matchMedia('(max-width:760px)').matches) sidebar(!document.body.classList.contains('sidebar-open')); else document.body.classList.toggle('sidebar-collapsed'); }));
  document.querySelectorAll('[data-prompt]').forEach(b => b.addEventListener('click', () => { $('prompt').value = b.dataset.prompt; saveDraft(); sizeComposer(); $('prompt').focus(); }));
  on('scrim', () => sidebar(false)); on('new-chat', async () => newChat(state.workspace?.key || ''));
  ['add-project', 'choose-folder', 'composer-folder'].forEach(id => on(id, () => pickFolder()));
  ['show-files', 'files-toggle'].forEach(id => on(id, async () => { $('file-panel').hidden = !$('file-panel').hidden; sidebar(false); if (!$('file-panel').hidden) await listFiles(); }));
  on('file-close', () => $('file-panel').hidden = true); on('file-up', async () => { folder = C.parent(folder); await listFiles(); });
  let searchTimer; on('file-query', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => listFiles($('file-query').value.trim()).catch(e => toast(e.message)), 300); }, 'input');
  ['file-new', 'folder-new'].forEach(id => on(id, async () => { const name = await input(id === 'file-new' ? '새 파일' : '새 폴더', '이름을 입력하세요.'); if (name) await mutate(id === 'file-new' ? 'mobile_create' : 'mobile_mkdir', {path: C.join(folder, name), content: ''}); }));
  on('editor-save', async () => { const result = await mutate('mobile_write', {path: openedFile.path, expectedSha256: openedFile.sha256, content: $('editor').value}); openedFile = await call('files.read', {path: result.path || openedFile.path}); });
  on('editor-delete', async () => { await mutate('mobile_delete', {path: openedFile.path}); close('editor-dialog'); });
  on('editor-rename', async () => { const name = await input('이름 변경', '새 파일 이름', C.basename(openedFile.path)); if (name) { await mutate('mobile_rename', {path: openedFile.path, name}); await openFile(C.join(C.parent(openedFile.path), name)); } });
  on('editor-move', async () => { const destination = await input('이동', '대상 폴더 경로 (루트는 빈칸)'); if (destination !== null) { await mutate('mobile_move', {path: openedFile.path, destination}); await openFile(C.join(destination, C.basename(openedFile.path))); } });
  on('input-confirm', () => { const r = inputResolve; inputResolve = null; const value = $('input-value').value; close('input-dialog'); if (r) r(value); });
  $('input-dialog').addEventListener('close', () => { if (inputResolve) { const r = inputResolve; inputResolve = null; r(null); } });
  on('input-value', e => { if (e.key === 'Enter' && !e.isComposing && e.keyCode !== 229) $('input-confirm').click(); }, 'keydown');
  on('composer', async () => {
    const value = $('prompt').value, text = value.trim();
    if ((!text && !draftContext.attachments.length) || state.busy || sending) return;
    const submitted = {value, context:JSON.parse(JSON.stringify(draftContext)), scopes:new Set([draftScope])}; sending = submitted;
    saveDraft(); updateSend(); scrollLatest();
    try {
      await call('chat.send', {text, model:$('model').value, effort:$('effort').value, attachments:submitted.context.attachments.map(x => x.id), skills:submitted.context.skills, mentions:submitted.context.mentions});
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
  let autocompleteTimer; $('prompt').addEventListener('input', () => { saveDraft(); sizeComposer(); clearTimeout(autocompleteTimer); hideAutocomplete(); if (/[@$]$/.test($('prompt').value.slice(0, $('prompt').selectionStart))) queryAutocomplete(); else autocompleteTimer = setTimeout(queryAutocomplete, 120); });
  $('prompt').addEventListener('focus', () => { if (following) frame(scrollLatest); });
  $('prompt').addEventListener('click', () => queryAutocomplete());
  $('chat-scroll').addEventListener('scroll', () => {
    const area = $('chat-scroll'); following = area.scrollHeight - area.scrollTop - area.clientHeight < 80;
    $('jump-latest').hidden = following || !state.messages.length;
  }, {passive:true});
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
  on('permissions', async () => { try { await call('permissions.set', {mode:$('permissions').value}); } catch (e) { $('permissions').value = state.permissions; throw e; } finally { optionsSummary(); } }, 'change');
  document.querySelectorAll('input[name="permission"]').forEach(r => r.addEventListener('change', () => { $('permissions').value = r.value; $('permissions').dispatchEvent(new Event('change')); }));
  on('connect', startLogin); on('account-button', startLogin); on('settings', () => { show('settings-dialog'); sidebar(false); });
  document.querySelectorAll('[data-settings-tab]').forEach(tab => tab.addEventListener('click', () => {
    const selected = tab.dataset.settingsTab; document.querySelectorAll('[data-settings-tab]').forEach(b => b.classList.toggle('active', b === tab));
    document.querySelectorAll('[data-settings-panel]').forEach(panel => panel.hidden = panel.dataset.settingsPanel !== selected);
    if (selected === 'personal' && !instructionsLoaded) loadInstructions();
    if (selected === 'account') loadUsage();
  }));
  on('device-code', async () => { if (login) { await call('ui.copyCode', {code: login.userCode}); toast('코드를 복사했습니다.'); } });
  on('open-login', () => login && call('ui.loginBrowser', {url: login.verificationUrl}));
  $('login-dialog').addEventListener('close', () => { if (login?.loginId) call('auth.cancel', {loginId: login.loginId}).catch(() => {}); login = null; });
  on('restart', async () => { await call('runtime.stop'); await call('runtime.start'); }); on('engine-stop', () => call('runtime.stop')); on('logout', () => call('auth.logout'));
  on('usage-refresh', loadUsage);
  on('chat-icons-toggle', setChatIcons, 'change');
  $('chat-icons-toggle').checked = chatIconsEnabled; drawStatusIcons();
  on('instructions-reload', loadInstructions); on('instructions-save', saveInstructions);
  $('settings-dialog').addEventListener('cancel', event => { event.preventDefault(); dismiss('settings-dialog'); });
  on('storage-access', () => call('ui.storageAccess')); on('theme', setTheme, 'change');
  on('devtools-check', checkDevtools);
  ['edit-config', 'tools-config'].forEach(id => on(id, config)); on('config-save', async () => { await call('config.save', {content: $('config-editor').value}); configOriginal = $('config-editor').value; await call('runtime.start'); close('config-dialog'); toast('설정을 적용했습니다.'); });
  on('show-recovery', recovery); on('show-terminal', () => { show('terminal-dialog'); sidebar(false); }); on('terminal-stop', () => call('terminal.stop'));
  on('terminal-form', async e => { e.preventDefault(); const command = $('terminal-command').value; if (command.trim()) { await call('terminal.run', {command}); $('terminal-output').textContent += '$ ' + command + '\n'; $('terminal-command').value = ''; } }, 'submit');
  on('show-tools', () => loadTools(false)); on('refresh-tools', () => loadTools(true)); on('show-activity', () => show('activity-dialog'));
  on('marketplace-add', async () => { const source = await input('마켓플레이스 추가', 'Git URL 또는 기기의 로컬 경로'); if (source) { const result = await rpc('marketplace/add', {source}); toast(result.marketplaceName + ' 추가 완료'); await loadTools(); } });
  on('skill-import', async () => { const r = await call('skills.import'); if (!r.cancelled) { toast('스킬 폴더를 가져왔습니다.'); await loadTools(); } });
  $('request-dialog').addEventListener('cancel', e => e.preventDefault());
  $('theme').value = localStorage.getItem('theme') || 'system'; setTheme(); matchMedia('(prefers-color-scheme: dark)').addEventListener('change', setTheme);
  window.addEventListener('resize', viewportChanged);
  window.visualViewport?.addEventListener('resize', viewportChanged);
  window.addEventListener('pagehide', saveDraft);
  document.addEventListener('visibilitychange', () => { if (document.hidden) saveDraft(); });
  document.addEventListener('keydown', e => {
    if (dialogs.at(-1) === 'image-dialog' && !$('image-stage').classList.contains('zoomed') && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { e.preventDefault(); changeImage(e.key === 'ArrowRight' ? 1 : -1); }
    if (e.key === 'Escape' && !dialogs.length) window.mobileCodexBack();
    if (e.key !== 'Tab' || !document.body.classList.contains('sidebar-open')) return;
    const controls = [...$('sidebar').querySelectorAll('button:not(:disabled)')], first = controls[0], last = controls.at(-1);
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  });
  viewportChanged();
  call('state').then(async initial => { render(initial); try { acceptPickedAttachments(await call('attachments.recover'), ''); } catch {} }).catch(e => toast(e.message));
})();
