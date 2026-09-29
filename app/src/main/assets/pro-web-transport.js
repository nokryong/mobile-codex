/* Isolated adapter for the official ChatGPT page. It never reads cookies, tokens, or storage. */
(function (root) {
  'use strict';
  if (root.MCProWeb?.version === 1) return;
  const d = root.document;
  const normalize = value => String(value || '').normalize('NFKC').replace(/\s+/g, ' ').trim();
  const exactPro = value => /^(?:GPT[ -]?6[ -]?Pro|6[ -]?Pro)$/i.test(normalize(value).replace(/^(?:model|모델)\s*:?\s*/i, ''));
  const visible = element => {
    if (!element || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false;
    const style = root.getComputedStyle(element), box = element.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
  };
  const label = element => normalize([
    element?.getAttribute?.('aria-label'),
    (element?.getAttribute?.('aria-labelledby') || '').split(/\s+/).map(id => d.getElementById(id)?.textContent || '').join(' '),
    element?.textContent
  ].join(' '));
  const prompt = () => d.querySelector('#prompt-textarea,[data-testid="prompt-textarea"],textarea[placeholder]');
  const modelTriggers = () => {
    const composer = prompt()?.closest('form') || prompt()?.parentElement;
    return [...d.querySelectorAll('button,[role="button"]')].filter(element => {
      if (!visible(element) || element.getAttribute('data-testid') === 'composer-plus-btn' || element.closest('[role="menu"],[role="dialog"],[role="listbox"]')) return false;
      const text = label(element), popup = element.getAttribute('aria-haspopup');
      return exactPro(text) || ((popup === 'menu' || popup === 'listbox') && composer?.contains(element)
        && /model|모델|gpt|pro|6/i.test(text));
    });
  };
  const loginVisible = () => /auth\.openai\.com$/i.test(location.host) || (!prompt() && [...d.querySelectorAll('a,button')]
    .some(element => visible(element) && /^(log in|sign up|로그인|가입)/i.test(label(element))));
  const exactProject = (element, name) => normalize(element?.textContent) === name || normalize(element?.getAttribute?.('aria-label')) === name;
  const projectPath = href => {
    try {
      const url = new URL(href, location.origin);
      return url.origin === location.origin && (/^\/g\/g-p-[A-Za-z0-9_-]+\/project\/?$/.test(url.pathname)
        || /^\/projects\/[A-Za-z0-9_-]+\/?$/.test(url.pathname)) ? url.pathname.replace(/\/$/, '') : '';
    } catch (_) { return ''; }
  };
  const projectMatches = name => {
    const byPath = new Map();
    for (const element of d.querySelectorAll('a[href]')) {
      const path = exactProject(element, name) && projectPath(element.href);
      if (path && !byPath.has(path)) byPath.set(path, {element, path});
    }
    return [...byPath.values()];
  };
  function inspectProject(name) {
    if (loginVisible()) return {status:'login_required'};
    // Inspect valid links even when the Projects section is collapsed. The same
    // project may have both mobile and desktop DOM copies, so deduplicate by path.
    const matches = projectMatches(name);
    if (matches.length > 1) return {status:'web_changed', reason:'project_ambiguous', projectCount:matches.length};
    if (matches.length === 1) return {status:'available', projectPath:matches[0].path,
      current:location.pathname.replace(/\/$/, '') === matches[0].path};
    const current = projectPath(location.href);
    const currentNames = [...d.querySelectorAll('h1,h2,[role="heading"]')].filter(element => visible(element) && exactProject(element, name));
    if (current && currentNames.length === 1) return {status:'available', projectPath:current, current:true};
    const sidebar = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && /^(?:Open sidebar|사이드바 열기|메뉴 열기)$/i.test(label(element)));
    if (sidebar.length === 1) { sidebar[0].click(); return {status:'opening_sidebar'}; }
    const collapsed = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && element.getAttribute('aria-expanded') === 'false' && /^(?:Projects|프로젝트)$/i.test(label(element)));
    if (collapsed.length > 1) return {status:'web_changed', reason:'projects_section_ambiguous'};
    if (collapsed.length === 1) {
      if (collapsed[0].dataset.mobileCodexExpanded === '1') return {status:'unavailable', reason:'projects_section_unavailable'};
      collapsed[0].dataset.mobileCodexExpanded = '1'; collapsed[0].click(); return {status:'opening_projects'};
    }
    const controls = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && /^(?:New project|새 프로젝트)$/i.test(label(element)));
    return controls.length === 1 ? {status:'missing', canCreate:true}
      : {status:'unavailable', reason:controls.length ? 'new_project_ambiguous' : 'new_project_unavailable'};
  }
  function openProject(name) {
    const matches = projectMatches(name).filter(item => visible(item.element));
    if (matches.length !== 1) return {status:'web_changed', reason:'project_link_ambiguous'};
    matches[0].element.click(); return {status:'opened', projectPath:matches[0].path};
  }
  function startProjectCreation() {
    const controls = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && /^(?:New project|새 프로젝트)$/i.test(label(element)));
    if (controls.length !== 1) return {status:'unavailable', reason:'new_project_unavailable'};
    controls[0].click(); return {status:'creation_opened'};
  }
  function finishProjectCreation(name) {
    const dialogs = [...d.querySelectorAll('[role="dialog"],dialog')].filter(visible);
    if (dialogs.length !== 1) return {status:'web_changed', reason:'project_dialog_ambiguous'};
    const dialog = dialogs[0];
    const fields = [...dialog.querySelectorAll('input:not([type]),input[type="text"]')].filter(element => visible(element) && !element.disabled);
    if (fields.length !== 1) return {status:'web_changed', reason:'project_name_input_ambiguous'};
    const field = fields[0], setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field), 'value')?.set;
    if (setter) setter.call(field, name); else field.value = name;
    field.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:name}));
    field.dispatchEvent(new Event('change', {bubbles:true}));
    const create = [...dialog.querySelectorAll('button,[role="button"]')].filter(element => visible(element) && !element.disabled
      && /^(?:Create project|Create|프로젝트 만들기|만들기)$/i.test(label(element)));
    if (create.length !== 1) return {status:'web_changed', reason:'project_create_button_ambiguous'};
    create[0].click(); return {status:'creation_submitted'};
  }
  function inspect() {
    if (loginVisible()) return {status:'login_required', host:location.host, pathKind:'login'};
    const editor = prompt(), triggers = modelTriggers();
    if (!editor) return {status:'web_changed', reason:'prompt_missing', triggerCount:triggers.length};
    if (triggers.length !== 1) return {status:'web_changed', reason:'model_trigger_ambiguous', triggerCount:triggers.length};
    return {status:'available', currentModel:exactPro(label(triggers[0])) ? 'GPT-6 Pro' : '', triggerCount:1,
      menuOpen:triggers[0].getAttribute('aria-expanded') === 'true'};
  }
  function openModelPicker() {
    const state = inspect(); if (state.status !== 'available') return state;
    const trigger = modelTriggers()[0]; trigger.click();
    return {status:'opened'};
  }
  function choosePro() {
    const containers = [...d.querySelectorAll('[role="menu"],[role="listbox"],[role="dialog"]')].filter(visible);
    const options = containers.flatMap(container => [...container.querySelectorAll('[role="menuitem"],[role="menuitemradio"],[role="option"],button')])
      .filter(element => visible(element) && exactPro(label(element)));
    if (!containers.length) return {status:'web_changed', reason:'model_picker_missing'};
    if (!options.length) return {status:'unavailable', reason:'gpt_6_pro_missing'};
    if (options.length !== 1) return {status:'web_changed', reason:'gpt_6_pro_ambiguous', optionCount:options.length};
    if (options[0].disabled || options[0].getAttribute('aria-disabled') === 'true') return {status:'unavailable', reason:'gpt_6_pro_disabled'};
    options[0].click(); return {status:'selected'};
  }
  function confirmPro() {
    const state = inspect();
    if (state.status !== 'available') return state;
    return state.currentModel === 'GPT-6 Pro' ? {status:'available', confirmedModel:'GPT-6 Pro'}
      : {status:'web_changed', reason:'model_confirmation_failed'};
  }
  function requestFiles() {
    const input = [...d.querySelectorAll('input[type="file"]')].find(element => !element.disabled);
    if (!input) return {status:'web_changed', reason:'file_input_missing'};
    input.click(); return {status:'chooser_requested'};
  }
  function assistantMessages() {
    return [...d.querySelectorAll('[data-message-author-role="assistant"]')].map((element, index) => ({
      id:element.getAttribute('data-message-id') || element.closest('[data-message-id]')?.getAttribute('data-message-id') || '',
      index, text:normalize(element.innerText || element.textContent || '')
    }));
  }
  function conversationLocation() {
    const path = (location.pathname || '').replace(/\/$/, '');
    const match = path.match(/^\/c\/([A-Za-z0-9_-]{1,200})$/)
      || path.match(/^\/g\/g-p-[A-Za-z0-9_-]+\/c\/([A-Za-z0-9_-]{1,200})$/)
      || path.match(/^\/projects\/[A-Za-z0-9_-]+\/c\/([A-Za-z0-9_-]{1,200})$/);
    return match ? {id:match[1], path} : {id:'', path:''};
  }
  function insert(text) {
    const editor = prompt(); if (!editor) return {status:'web_changed', reason:'prompt_missing'};
    const current = normalize('value' in editor ? editor.value : editor.textContent);
    if (current) return {status:'not_sent', reason:'prompt_not_empty'};
    editor.focus();
    if ('value' in editor) {
      const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(editor), 'value')?.set;
      if (setter) setter.call(editor, text); else editor.value = text;
      editor.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:text}));
    } else {
      d.execCommand('insertText', false, text);
      if (!normalize(editor.textContent)) { editor.textContent = text; editor.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:text})); }
    }
    const assistants = assistantMessages();
    return normalize('value' in editor ? editor.value : editor.textContent)
      ? {status:'inserted', assistantCount:assistants.length, lastAssistantId:assistants.at(-1)?.id || ''}
      : {status:'web_changed', reason:'prompt_insert_failed'};
  }
  function clickSend() {
    const button = d.querySelector('#composer-submit-button,[data-testid="send-button"]');
    if (!button) return {status:'web_changed', reason:'send_button_missing'};
    if (button.disabled || button.getAttribute('aria-disabled') === 'true') return {status:'not_sent', reason:'send_button_disabled'};
    button.click(); return {status:'clicked'};
  }
  function observe(baselineCount, baselineId) {
    const messages = assistantMessages();
    let candidate = messages.find((message, index) => index >= baselineCount && (!baselineId || message.id !== baselineId));
    if (!candidate && messages.length > baselineCount) candidate = messages.at(-1);
    const stopping = !!d.querySelector('[data-testid="stop-button"],button[aria-label*="Stop" i],button[aria-label*="중지"]');
    const conversation = conversationLocation();
    return {status:candidate?.text ? 'observed' : 'waiting', reply:candidate?.text || '',
      remoteMessageId:candidate?.id || '', streaming:stopping, conversationId:conversation.id, conversationPath:conversation.path};
  }
  function stop() {
    const button = d.querySelector('[data-testid="stop-button"],button[aria-label*="Stop" i],button[aria-label*="중지"]');
    if (button && visible(button)) { button.click(); return {status:'stop_requested'}; }
    return {status:'not_running'};
  }
  root.MCProWeb = {version:1, inspectProject, openProject, startProjectCreation, finishProjectCreation,
    inspect, openModelPicker, choosePro, confirmPro, requestFiles, insert, clickSend, observe, stop, exactPro, projectPath, conversationLocation};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.MCProWeb;
})(typeof window === 'undefined' ? globalThis : window);
