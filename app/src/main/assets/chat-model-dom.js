/* Runs only inside the authenticated ChatGPT WebView. Never reads credentials or messages. */
(function (root) {
  'use strict';
  if (root.MCChatModelDom?.diagnosticVersion === 2) return;
  const LEVELS = ['Instant', 'Medium', 'High', 'X-High', 'Pro'];
  const normalize = value => String(value || '').normalize('NFKC').replace(/\s+/g, ' ').trim();
  function level(value) {
    const text = normalize(value);
    if (/(?:매우\s*높음|X[\s-]*High)/i.test(text)) return 'X-High';
    if (/(?:즉시|빠름|Instant)/i.test(text)) return 'Instant';
    if (/(?:중간|Medium)/i.test(text)) return 'Medium';
    if (/(?:높음|High)/i.test(text)) return 'High';
    if (/(?:^|\s)Pro(?:$|\s|,)/i.test(text)) return 'Pro';
    return '';
  }
  function visible(element) {
    if (!element || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false;
    const style = root.getComputedStyle(element), box = element.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
  }
  function description(element) {
    return normalize((element.getAttribute('aria-describedby') || '').split(/\s+/)
      .map(id => id && root.document.getElementById(id)?.textContent || '').join(' '));
  }
  function settingLabel(element) {
    const labelled = (element.getAttribute('aria-labelledby') || '').split(/\s+/)
      .map(id => id && root.document.getElementById(id)?.textContent || '').join(' ');
    return normalize([element.getAttribute('aria-label'), labelled, element.textContent, description(element)].join(' '));
  }
  function ordinal(value) {
    const text = normalize(value);
    let match = text.match(/(\d+)\s*개\s*중\s*(\d+)\s*번째/);
    if (match) return {position:Number(match[2]), total:Number(match[1])};
    match = text.match(/(\d+)(?:st|nd|rd|th)?\s+of\s+(\d+)/i);
    return match ? {position:Number(match[1]), total:Number(match[2])} : null;
  }
  // Only settings controls supply text. The active element/event receiver is structural only.
  function shortSettingText(value) {
    return normalize(value).replace(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi, '[redacted-email]')
      .replace(/(?:Bearer\s+\S+|(?:token|cookie|password|authorization)\s*[:=]\s*\S+)/gi, '[redacted]')
      .replace(/[A-Za-z0-9_+\/=-]{32,}/g, '[redacted-opaque]').slice(0, 160);
  }
  function valueEvidence(element) {
    if (!element) return null;
    return {
      ariaValueText:shortSettingText(element.getAttribute('aria-valuetext')),
      displayText:shortSettingText(element.textContent),
      descriptions:(element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean).slice(0, 4)
        .map(id => shortSettingText(root.document.getElementById(id)?.textContent || ''))
    };
  }
  function safeElement(element, structuralOnly = false) {
    if (!element) return null;
    const box = element.getBoundingClientRect();
    const attr = name => element.getAttribute(name) || '';
    return {
      tag:element.tagName.toLowerCase(), role:attr('role'),
      label:structuralOnly ? undefined : level(settingLabel(element)) || (/성능|Performance/i.test(settingLabel(element)) ? 'performance' : 'unknown'),
      haspopup:attr('aria-haspopup'), expanded:attr('aria-expanded'), controls:!!attr('aria-controls'),
      checked:attr('aria-checked'), selected:attr('aria-selected'),
      min:attr('aria-valuemin'), max:attr('aria-valuemax'), now:attr('aria-valuenow'),
      valueText:structuralOnly ? undefined : level(attr('aria-valuetext') || description(element)),
      valueEvidence:structuralOnly ? undefined : valueEvidence(element),
      disabled:!!element.disabled || attr('aria-disabled') === 'true',
      visible:visible(element),
      box:{x:Math.round(box.x),y:Math.round(box.y),width:Math.round(box.width),height:Math.round(box.height)},
      focused:root.document.activeElement === element || element.contains(root.document.activeElement)
    };
  }
  function triggers() {
    const prompt = root.document.querySelector('#prompt-textarea,[data-testid="prompt-textarea"]');
    const promptBox = prompt?.getBoundingClientRect();
    const buttons = [...root.document.querySelectorAll('button,[role="button"]')].filter(button =>
      visible(button) && !button.closest('[role="menu"],[role="dialog"],[role="listbox"]'));
    const form = prompt?.closest('form');
    const nearComposer = button => {
      const box = button.getBoundingClientRect();
      return !!form?.contains(button) || !!promptBox && Math.abs(box.top - promptBox.top) <= 180
        || box.top >= root.innerHeight - 180 && box.bottom <= root.innerHeight + 20;
    };
    const named = buttons
      .filter(button => {
        const label = settingLabel(button);
        const hasPopup = !!button.getAttribute('aria-haspopup');
        if (hasPopup && (/추론\s*수준|reasoning\s*(level|effort)|performance/i.test(label) || level(label)))
          return !promptBox || nearComposer(button);
        return !!level(label) && !!promptBox && nearComposer(button);
      });
    if (named.length) return named;
    // Some ChatGPT builds render the visible model label outside the button's
    // DOM text. Only accept a unique menu trigger next to the composer.
    return buttons.filter(button => {
      if (button.getAttribute('aria-haspopup') !== 'menu') return false;
      return nearComposer(button);
    });
  }
  function type(element) {
    const role = element.getAttribute('role') || '';
    if (role === 'slider' || element.matches('input[type="range"]')) return 'slider';
    if (role === 'menuitemradio' || role === 'radio') return 'option';
    if (role === 'menuitem' && element.getAttribute('aria-haspopup') && element.getAttribute('aria-haspopup') !== 'false') return 'submenu';
    const details = description(element);
    if (role === 'menuitem' && ordinal(details) && /화살표|arrow/i.test(details)) return 'stepper';
    return 'unknown';
  }
  function relevant(element) {
    if (!visible(element)) return false;
    if (type(element) === 'slider') return true;
    const details = description(element);
    const label = normalize(element.getAttribute('aria-label') || element.textContent);
    return !!(level(label) || ordinal(details) || /^(성능|Performance)$/i.test(label));
  }
  function locate() {
    const buttons = triggers();
    const button = buttons.length === 1 ? buttons[0] : null;
    const popups = [...root.document.querySelectorAll('[role="menu"],[role="dialog"],[role="listbox"]')].filter(visible);
    const candidates = popups.map(popup => ({popup,controls:[...popup.querySelectorAll('[role="slider"],input[type="range"],[role="menuitemradio"],[role="radio"],[role="menuitem"]')].filter(relevant)}))
      .filter(entry => entry.controls.length);
    const linkedIds = new Set((button?.getAttribute('aria-controls') || '').split(/\s+/).filter(Boolean));
    if (linkedIds.size) {
      let size;
      do {
        size = linkedIds.size;
        for (const entry of candidates.filter(item => linkedIds.has(item.popup.id)))
          for (const element of entry.controls) {
            const id = element.getAttribute('aria-controls');
            if (id) linkedIds.add(id);
          }
      } while (linkedIds.size > size);
    }
    const linked = candidates.filter(entry => linkedIds.has(entry.popup.id));
    const relevantPopups = linked.length ? linked : candidates;
    // Ignore an ancestor popup when an open child contains the same control.
    const leaves = relevantPopups.filter(entry => !relevantPopups.some(other => other !== entry && entry.popup.contains(other.popup)));
    const controlledIds = new Set(leaves.flatMap(entry => entry.controls.map(element => element.getAttribute('aria-controls')).filter(Boolean)));
    const childPopups = leaves.filter(entry => controlledIds.has(entry.popup.id));
    const active = childPopups.length === 1 ? childPopups : leaves;
    const scoped = active.length === 1 ? active[0] : null;
    return {button, buttons, popup:scoped?.popup || null, controls:scoped?.controls || [],
      popupCount:popups.length, activePopupCount:active.length,
      controlCount:active.reduce((sum, entry) => sum + entry.controls.length, 0),
      ambiguous:buttons.length > 1 || active.length > 1};
  }
  function inspect() {
    const found = locate();
    const context = {triggerCount:found.buttons.length, popupCount:found.popupCount,
      activePopupCount:found.activePopupCount, controlCount:found.controlCount,
      inputObservation:inputSnapshot(), focus:safeElement(root.document.activeElement, true)};
    if (found.ambiguous) return {...context,state:'ambiguous'};
    const controls = found.controls, kinds = controls.map(type);
    const options = controls.length && kinds.every(kind => kind === 'option');
    if (controls.length > 1 && !options) return {...context,state:'ambiguous',controls:controls.slice(0, 8).map(element => safeElement(element))};
    const control = controls.length === 1 ? controls[0] : null;
    const details = control ? description(control) : '';
    const position = ordinal(details);
    const buttonLevel = found.button ? level(settingLabel(found.button)) : '';
    let current = control ? level(control.getAttribute('aria-valuetext') || details || control.textContent) : '';
    const levelFromText = current;
    let levelFromOrdinal = '', levelSource = current ? (control.getAttribute('aria-valuetext') ? 'aria-valuetext' : details ? 'description' : 'display-text') : 'unknown';
    if (control && type(control) === 'stepper' && position?.total === LEVELS.length
        && position.position >= 1 && position.position <= LEVELS.length)
      { current = levelFromOrdinal = LEVELS[position.position - 1]; levelSource = 'stepper-ordinal'; }
    if (options) {
      const checked = controls.filter(element => element.getAttribute('aria-checked') === 'true' || element.getAttribute('aria-selected') === 'true');
      if (checked.length === 1) { current = level(checked[0].getAttribute('aria-label') || checked[0].textContent); levelSource = 'checked-option'; }
    }
    if (!current && control && type(control) === 'slider') {
      const minimum = Number(control.getAttribute('aria-valuemin') ?? control.min);
      const maximum = Number(control.getAttribute('aria-valuemax') ?? control.max);
      const value = Number(control.getAttribute('aria-valuenow') ?? control.value);
      if (Number.isFinite(minimum) && Number.isFinite(maximum) && Number.isFinite(value)
          && maximum - minimum === 4 && value >= minimum && value <= maximum)
        { current = LEVELS[value - minimum] || ''; if (current) levelSource = 'slider-number'; }
    }
    return {
      ...context,
      levelFromText,levelFromOrdinal,levelSource:current ? levelSource : !found.popup && buttonLevel ? 'trigger' : 'unknown',
      levelConflict:!!levelFromText && !!levelFromOrdinal && levelFromText !== levelFromOrdinal,
      state:found.popup ? 'open' : found.button ? 'closed' : 'unavailable',
      type:options ? 'options' : control ? type(control) : 'none',
      level:current || (found.popup ? '' : buttonLevel),
      position:position?.position || 0,total:position?.total || 0,
      trigger:safeElement(found.button),control:safeElement(control),
      options:options ? controls.map(element => safeElement(element)) : [],
      focus:safeElement(root.document.activeElement, true),
      viewport:{width:root.innerWidth,height:root.innerHeight}
    };
  }
  let inputTrace = null, stopInputTrace = null;
  function inputSnapshot() { return inputTrace ? JSON.parse(JSON.stringify(inputTrace)) : null; }
  function stopObservation(operationId) {
    if (operationId != null && inputTrace?.operationId !== operationId) return inputSnapshot();
    if (stopInputTrace) stopInputTrace(); return inputSnapshot();
  }
  function observeKeys(element, operationId, sessionEpoch) {
    stopObservation();
    inputTrace = {operationId,sessionEpoch,events:[]};
    const started = root.performance.now();
    const receive = event => {
      if (!['ArrowLeft','ArrowRight','Escape'].includes(event.key)) return;
      if (inputTrace.events.length >= 12) return;
      inputTrace.events.push({type:event.type,key:event.key,trusted:event.isTrusted,
        elapsedMs:Math.round(root.performance.now() - started),
        targetInsideControl:event.target === element || element.contains(event.target),
        target:safeElement(event.target, true)});
    };
    root.document.addEventListener('keydown', receive, true);
    root.document.addEventListener('keyup', receive, true);
    const timer = root.setTimeout(() => stopObservation(), 5200);
    stopInputTrace = () => {
      root.document.removeEventListener('keydown', receive, true);
      root.document.removeEventListener('keyup', receive, true);
      root.clearTimeout(timer); stopInputTrace = null;
    };
  }
  function focus(operationId, sessionEpoch) {
    const found = locate();
    if (!found.popup || found.controls.length !== 1) return {ok:false,reason:'control_missing'};
    const element = found.controls[0];
    observeKeys(element, operationId, sessionEpoch);
    element.focus();
    return {ok:root.document.activeElement === element || element.contains(root.document.activeElement),type:type(element),activeElement:safeElement(root.document.activeElement, true)};
  }
  function choose(optionId) {
    if (!LEVELS.includes(optionId)) return {ok:false,reason:'unsupported'};
    const found = locate();
    if (!found.popup || found.ambiguous || !found.controls.length || !found.controls.every(element => type(element) === 'option'))
      return {ok:false,reason:'options_missing'};
    const matches = found.controls.filter(element => level(element.getAttribute('aria-label') || element.textContent) === optionId);
    if (matches.length > 1) return {ok:false,reason:'ambiguous-option',
      options:matches.map(element => ({id:'option-' + found.controls.indexOf(element),...safeElement(element)}))};
    if (!matches.length) return {ok:false,reason:'unsupported'};
    if (matches[0].disabled || matches[0].getAttribute('aria-disabled') === 'true')
      return {ok:false,reason:'disabled'};
    matches[0].click();
    return {ok:true};
  }
  root.MCChatModelDom = {diagnosticVersion:2,inspect,focus,choose,level,ordinal,stopObservation};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.MCChatModelDom;
})(typeof window === 'undefined' ? globalThis : window);
