/* Isolated adapter for the official ChatGPT page. It never reads cookies, tokens, or storage. */
(function (root) {
  'use strict';
  if (root.MCProWeb?.version === 5) return;
  const d = root.document;
  const normalize = value => String(value || '').normalize('NFKC').replace(/\s+/g, ' ').trim();
  const exactPro = value => /^(?:GPT[ -]?6[ -]?Pro|6[ -]?Pro)$/i.test(normalize(value).replace(/^(?:model|모델)\s*:?\s*/i, ''));
  const visible = element => {
    if (!element || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false;
    const style = root.getComputedStyle(element), box = element.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
  };
  const label = element => normalize(element?.getAttribute?.('aria-label'))
    || normalize((element?.getAttribute?.('aria-labelledby') || '').split(/\s+/).map(id => d.getElementById(id)?.textContent || '').join(' '))
    || normalize(element?.textContent);
  const prompt = () => {
    // querySelector with comma-separated selectors uses document order, not
    // selector priority. ChatGPT's hidden fallback textarea precedes ProseMirror.
    const primary = [...d.querySelectorAll('#prompt-textarea,[data-testid="prompt-textarea"]')];
    const fallback = [...d.querySelectorAll('textarea[placeholder]')];
    return primary.find(visible) || fallback.find(visible) || primary[0] || fallback[0] || null;
  };
  const press = element => {
    // Radix menu triggers open on pointerdown, not HTMLElement.click alone.
    const Pointer = root.PointerEvent || root.MouseEvent;
    for (const type of ['pointerdown','pointerup']) element.dispatchEvent(new Pointer(type,
      {bubbles:true,cancelable:true,button:0,pointerType:'mouse',pointerId:1,isPrimary:true}));
    element.click();
  };
  const effortLabel = value => /^(?:Extra High|High|Medium|Low|Light|Standard|Extended|Heavy|Max|Maximum|추론 수준|Reasoning level|Reasoning effort|Intelligence|성능)$/i.test(normalize(value));
  const modelTriggers = () => {
    const composer = prompt()?.closest('form') || prompt()?.parentElement;
    return [...d.querySelectorAll('button,[role="button"]')].filter(element => {
      if (!visible(element) || element.getAttribute('data-testid') === 'composer-plus-btn' || element.closest('[role="menu"],[role="dialog"],[role="listbox"]')) return false;
      const text = label(element), popup = element.getAttribute('aria-haspopup');
      return exactPro(text) || ((popup === 'menu' || popup === 'listbox') && composer?.contains(element)
        && (/model|모델|gpt|pro|6/i.test(text) || effortLabel(text)));
    });
  };
  const loginVisible = () => /auth\.openai\.com$/i.test(location.host) || (!prompt() && [...d.querySelectorAll('a,button')]
    .some(element => visible(element) && /^(log in|sign up|로그인|가입)/i.test(label(element))));
  const exactProject = (element, name) => normalize(element?.textContent) === name || normalize(element?.getAttribute?.('aria-label')) === name;
  const visibleProjectTitle = name => [...d.querySelectorAll('h1,h2,[role="heading"],div,span')].some(element =>
    !element.closest('nav,[role="navigation"],[role="dialog"],dialog,[role="menu"],form,a,button,[data-sidebar-item],[data-message-author-role]')
    && normalize(element.textContent) === name && visible(element));
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
  let closingSidebar = null, navigatingProject = null;
  function dismissSidebar() {
    const controls = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && /^(?:Close sidebar|사이드바 닫기)$/i.test(label(element)));
    if (controls.length > 1) return {status:'web_changed', reason:'sidebar_close_ambiguous'};
    if (!controls.length) { closingSidebar = null; return null; }
    // Do not click again while React is removing the modal and aria-hidden.
    if (closingSidebar !== controls[0]) { closingSidebar = controls[0]; controls[0].click(); }
    return {status:'closing_sidebar', reason:'sidebar_closing'};
  }
  function inspectProject(name) {
    if (loginVisible()) return {status:'login_required'};
    // Inspect valid links even when the Projects section is collapsed. The same
    // project may have both mobile and desktop DOM copies, so deduplicate by path.
    const matches = projectMatches(name);
    if (matches.length > 1) return {status:'web_changed', reason:'project_ambiguous', projectCount:matches.length};
    if (matches.length === 1) return {status:'available', projectPath:matches[0].path,
      current:location.pathname.replace(/\/$/, '') === matches[0].path};
    const current = projectPath(location.href);
    if (current) {
      const headings = [...d.querySelectorAll('h1,h2,[role="heading"]')]
        .filter(element => !element.closest('[role="dialog"],dialog,nav,[role="navigation"]'));
      const currentNames = headings.filter(element => exactProject(element, name));
      if (currentNames.length) {
        const closing = dismissSidebar(); if (closing) return closing;
        // Mobile renders a separate title DIV and hides the desktop H1's parent.
        // The URL and semantic title identify the project; either rendered title
        // is valid. Never use a sidebar row or a conversation mention as evidence.
        if (!currentNames.some(visible) && !(visible(prompt()) && visibleProjectTitle(name)))
          return {status:'waiting', reason:'project_page_pending'};
        navigatingProject = null; return {status:'available', projectPath:current, current:true};
      }
      // onPageFinished can precede React hydration. Opening the sidebar here
      // hides the project heading and traps every following inspection.
      if (!headings.length) return {status:'waiting', reason:'project_page_pending'};
    }
    const rows = [...d.querySelectorAll('[data-sidebar-item][role="button"],button[data-sidebar-item]')]
      .filter(element => visible(element) && exactProject(element, name));
    if (rows.length > 1) return {status:'web_changed', reason:'project_ambiguous'};
    if (rows.length === 1) {
      if (navigatingProject?.element === rows[0] && navigatingProject.path === location.pathname)
        return {status:'waiting', reason:'project_navigation_pending'};
      const row = rows[0], scope = row.parentElement;
      // The current sidebar row is an accordion. Its sibling home button,
      // not the row itself or its options menu, opens the project workspace.
      const homes = [...scope.querySelectorAll('button,[role="button"],a[href]')].filter(element => visible(element)
        && /^(?:Open project home|Open project|프로젝트 홈 열기)$/i.test(label(element)));
      if (homes.length > 1) return {status:'web_changed', reason:'project_home_ambiguous'};
      const accordion = row.hasAttribute('aria-controls') || row.hasAttribute('aria-expanded');
      if (!homes.length && accordion) return {status:'waiting', reason:'project_home_pending'};
      if (homes.length && [...scope.querySelectorAll('[data-sidebar-item]')].some(element => element !== row))
        return {status:'web_changed', reason:'project_home_ambiguous'};
      const target = homes[0] || row;
      if (target.disabled || target.getAttribute('aria-disabled') === 'true') return {status:'waiting', reason:'project_home_pending'};
      navigatingProject = {element:rows[0], path:location.pathname};
      target.click(); return {status:'opening_project', reason:'project_navigation_pending'};
    }
    if (navigatingProject) return {status:'waiting', reason:'project_navigation_pending'};
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
    return controls.length === 1 ? {status:'missing', reason:'project_missing', canCreate:true}
      : {status:'unavailable', reason:controls.length ? 'new_project_ambiguous' : 'new_project_unavailable'};
  }
  function openProject(name) {
    const matches = projectMatches(name).filter(item => visible(item.element));
    if (matches.length !== 1) return {status:'web_changed', reason:'project_link_ambiguous'};
    matches[0].element.click(); return {status:'opened', projectPath:matches[0].path};
  }
  function startProjectCreation() {
    const existing = projectCreationForm();
    if (existing.dialog) return {status:'creation_opened'};
    if (existing.status === 'web_changed') return {status:existing.status, reason:existing.reason};
    const controls = [...d.querySelectorAll('button,[role="button"]')].filter(element => visible(element)
      && /^(?:New project|새 프로젝트)$/i.test(label(element)));
    if (controls.length !== 1) return {status:'unavailable', reason:'new_project_unavailable'};
    controls[0].click(); return {status:'creation_opened'};
  }
  const dialogSelector = '[role="dialog"],dialog';
  const projectTitle = value => /^(?:New project|Create (?:a )?project|새 프로젝트|프로젝트 만들기)$/i.test(normalize(value));
  const createLabel = value => /^(?:Create project|Create|프로젝트 만들기|만들기)$/i.test(normalize(value));
  const submittedProjects = new WeakMap();
  const owned = (dialog, selector) => [...dialog.querySelectorAll(selector)].filter(element => element.closest(dialogSelector) === dialog && visible(element));
  function projectCreationForm() {
    const dialogs = [...d.querySelectorAll(dialogSelector)].filter(dialog => visible(dialog) && (
      projectTitle(dialog.getAttribute('aria-label'))
      || (dialog.getAttribute('aria-labelledby') || '').split(/\s+/).some(id => projectTitle(d.getElementById(id)?.textContent))
      || owned(dialog, 'h1,h2,h3,[role="heading"]').some(heading => projectTitle(heading.textContent))));
    if (!dialogs.length) return {status:'waiting', reason:'project_dialog_pending'};
    if (dialogs.length !== 1) return {status:'web_changed', reason:'project_dialog_ambiguous'};
    const dialog = dialogs[0];
    const fields = owned(dialog, 'input:not([type]),input[type="text"]').filter(element => !element.disabled);
    if (!fields.length) return {status:'waiting', reason:'project_name_input_pending'};
    if (fields.length !== 1) return {status:'web_changed', reason:'project_name_input_ambiguous'};
    const create = owned(dialog, 'button,[role="button"]').filter(element => createLabel(label(element)));
    if (!create.length) return {status:'waiting', reason:'project_create_button_pending'};
    if (create.length !== 1) return {status:'web_changed', reason:'project_create_button_ambiguous'};
    return {status:'ready', dialog, field:fields[0], button:create[0]};
  }
  function inspectProjectCreation() {
    const {status, reason} = projectCreationForm();
    return reason ? {status, reason} : {status};
  }
  function finishProjectCreation(name) {
    const form = projectCreationForm();
    if (form.status !== 'ready') return {status:form.status, reason:form.reason};
    const {dialog, field, button} = form;
    if (submittedProjects.get(dialog) === name) return {status:'creation_submitted'};
    if (field.value !== name) {
      const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field), 'value')?.set;
      if (setter) setter.call(field, name); else field.value = name;
      field.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:name}));
      field.dispatchEvent(new Event('change', {bubbles:true}));
    }
    if (field.value !== name || button.disabled || button.getAttribute('aria-disabled') === 'true')
      return {status:'waiting', reason:'project_create_button_pending'};
    submittedProjects.set(dialog, name);
    button.click(); return {status:'creation_submitted'};
  }
  function inspect() {
    if (loginVisible()) return {status:'login_required', host:location.host, pathKind:'login'};
    const editor = prompt(), triggers = modelTriggers();
    if (!editor) return {status:'web_changed', reason:'prompt_missing', triggerCount:triggers.length};
    if (!triggers.length) return {status:'web_changed', reason:'model_trigger_missing', triggerCount:0};
    if (triggers.length !== 1) return {status:'web_changed', reason:'model_trigger_ambiguous', triggerCount:triggers.length};
    return {status:'available', currentModel:exactPro(label(triggers[0])) ? 'GPT-6 Pro' : '', triggerCount:1,
      menuOpen:triggers[0].getAttribute('aria-expanded') === 'true'};
  }
  function prepareComposer() {
    if (loginVisible()) return {status:'login_required'};
    const closing = dismissSidebar(); if (closing) return closing;
    const state = inspect();
    if (prompt() && !visible(prompt())) return {status:'waiting', reason:'composer_blocked'};
    if (state.reason === 'prompt_missing' || state.reason === 'model_trigger_missing')
      return {...state, status:'waiting'};
    return state;
  }
  function openModelPicker() {
    const state = inspect(); if (state.status !== 'available') return state;
    const trigger = modelTriggers()[0];
    if (trigger.getAttribute('aria-expanded') !== 'true') press(trigger);
    return {status:'opened'};
  }
  const sliderSteps = new WeakMap();
  function chooseSliderPro(containers) {
    const pickers = [...new Set(containers.flatMap(container => [...container.querySelectorAll('[data-testid="composer-intelligence-picker-content"]')]))].filter(visible);
    if (pickers.length !== 1) return {status:pickers.length ? 'web_changed' : 'unavailable', reason:pickers.length ? 'gpt_6_pro_ambiguous' : 'gpt_6_pro_missing'};
    const picker = pickers[0];
    const control = [...picker.querySelectorAll('[role="menuitem"][aria-keyshortcuts]')].filter(element => visible(element)
      && /ArrowLeft/.test(element.getAttribute('aria-keyshortcuts')) && /ArrowRight/.test(element.getAttribute('aria-keyshortcuts'))
      && element.querySelector('[data-model-reasoning-effort-slider]'));
    if (control.length !== 1) return {status:'unavailable', reason:'pro_slider_unavailable'};
    const modelLabel = [...picker.querySelectorAll('[role="menuitem"][aria-label]')].filter(element => visible(element)
      && /^(?:모델 선택|Select model|Choose model)$/i.test(element.getAttribute('aria-label')));
    if (modelLabel.length === 1 && exactPro(modelLabel[0].textContent)) {
      const menu = picker.closest('[role="menu"]');
      menu.dispatchEvent(new root.KeyboardEvent('keydown', {key:'Escape',code:'Escape',bubbles:true,cancelable:true}));
      return {status:'selected'};
    }
    const slider = control[0].querySelector('[role="slider"]');
    if (!slider || slider.closest('[aria-disabled="true"],[data-locked="true"]')) return {status:'unavailable',reason:'gpt_6_pro_disabled'};
    const value = Number(slider.getAttribute('aria-valuenow')), maximum = Number(slider.getAttribute('aria-valuemax'));
    if (!Number.isInteger(value) || !Number.isInteger(maximum) || maximum > 10 || value >= maximum)
      return {status:'unavailable',reason:'gpt_6_pro_missing'};
    if (sliderSteps.get(slider) !== value) {
      sliderSteps.set(slider,value); control[0].focus();
      for (const type of ['keydown','keyup']) control[0].dispatchEvent(new root.KeyboardEvent(type,
        {key:'ArrowRight',code:'ArrowRight',bubbles:true,cancelable:true}));
    }
    // Reinspect rendered model text after React updates. The slider endpoint
    // itself is NEVER evidence of Pro; only the exact model label confirms it.
    return {status:'waiting',reason:'model_selection_pending'};
  }
  function choosePro() {
    const containers = [...d.querySelectorAll('[role="menu"],[role="listbox"],[role="dialog"]')].filter(visible);
    const options = containers.flatMap(container => [...container.querySelectorAll('[role="menuitem"],[role="menuitemradio"],[role="option"],button')])
      .filter(element => visible(element) && exactPro(label(element)));
    if (!containers.length) return {status:'waiting', reason:'model_picker_pending'};
    if (!options.length) return chooseSliderPro(containers);
    if (options.length !== 1) return {status:'web_changed', reason:'gpt_6_pro_ambiguous', optionCount:options.length};
    if (options[0].disabled || options[0].getAttribute('aria-disabled') === 'true') return {status:'unavailable', reason:'gpt_6_pro_disabled'};
    press(options[0]); return {status:'selected'};
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
  root.MCProWeb = {version:5, inspectProject, openProject, startProjectCreation, finishProjectCreation, inspectProjectCreation,
    inspect, prepareComposer, openModelPicker, choosePro, confirmPro, requestFiles, insert, clickSend, observe, stop, exactPro, projectPath, conversationLocation};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.MCProWeb;
})(typeof window === 'undefined' ? globalThis : window);
