/* Invited agents: the + menu invite sheet, the right-hand participants panel and
   the Claude Code / Antigravity account cards. The chat itself stays in app.js. */
(() => {
  'use strict';
  const AGENTS = [
    {id:'claude', provider:'claude', name:'Claude', account:'Claude Code'},
    {id:'gemini', provider:'agy', name:'Gemini', account:'Antigravity (Gemini)'},
  ];
  const EFFORTS = {none:'None', minimal:'Minimal', low:'Light', medium:'Medium', high:'High', xhigh:'Extra High', max:'Max', ultra:'Ultra'};
  const MODES = [{id:'chat', label:'대화만'}, {id:'read', label:'프로젝트 읽기'}, {id:'write', label:'프로젝트 수정'}];
  const PROVIDER_STATE = {ready:'연결됨', missing:'CLI 설치 필요', login_required:'로그인 필요', unverified:'상태 확인 불가', unavailable:'실행 확인 필요', unsupported:'업데이트 필요', unchecked:'확인 전', installing:'설치 중…', login:'로그인 진행 중…'};
  window.MobileCodexAgents = { init({call, getState, toast, show, close, closeAddMenu, closeFilePanel, openAccounts, openLinuxSettings, usageGauge, usageTime, getCharacters, characterIcon}) {
    const t = window.MobileCodexLocale?.t || (text => text);
    const $ = id => document.getElementById(id);
    const el = (tag, text, cls) => { const e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; };
    const btn = (text, fn, cls = 'secondary-button') => { const e = el('button', text, cls); e.type = 'button'; e.onclick = () => Promise.resolve().then(fn).catch(error => toast(error.message)); return e; };
    const catalog = [], expanded = new Set(), setupStates = new Map(), accountCards = new Map();
    let providerCheck = null, setupView = null, panelSignature = '', inviteSignature = '';
    const providerOf = id => AGENTS.find(a => a.id === id)?.provider || id;
    const cap = id => catalog.find(c => c.id === providerOf(id)) || {};
    const accountName = provider => AGENTS.find(a => a.provider === provider)?.account || provider;
    const agents = () => (getState().agents || []).filter(a => a.enabled !== false);
    const defaultsFor = id => {
      const models = cap(id).models || [], model = models.find(m => m.isDefault) || models[0];
      const efforts = model?.efforts || [];
      return {model:model?.id || '', effort:efforts.includes(model?.defaultEffort) ? model.defaultEffort : efforts[0] || ''};
    };
    function mergeProviders(list) {
      for (const p of list || []) { const at = catalog.findIndex(c => c.id === p.id); if (at < 0) catalog.push(p); else catalog[at] = p; }
      renderAccounts(); renderPanel(); if (!$('invite-dialog').hidden && $('invite-dialog').open) renderInvite();
    }
    async function refreshProviders(force = false) {
      if (providerCheck) return providerCheck;
      providerCheck = call('agents.providers.check', {force}).then(result => mergeProviders(result.providers)).catch(error => {
        for (const ui of accountCards.values()) { ui.status.textContent = t('상태 확인 실패'); ui.detail.textContent = error.message; }
        $('invite-status').textContent = error.message;
      }).finally(() => { providerCheck = null; renderAccounts(); });
      renderAccounts(); return providerCheck;
    }
    const setAgents = changes => call('chat.agents.set', {agents:changes});

    // ---- Selects ------------------------------------------------------------------
    function select(options, value, label) {
      const e = el('select'); e.setAttribute('aria-label', label);
      if (value && !options.some(o => o.id === value)) options = [...options, {id:value, label:value + ' · ' + t('목록 확인 필요')}];
      for (const o of options) { const opt = el('option', o.label); opt.value = o.id; e.append(opt); }
      e.addEventListener('blur', () => queueMicrotask(renderPanel));
      e.value = value || ''; return e;
    }
    function field(label, input) { const l = el('label', null, 'option-field agent-field'); l.append(el('span', label), input); return l; }
    function effortOptions(values, fallback) {
      return values.length ? values.map(id => ({id, label:EFFORTS[id] || id})) : [{id:'', label:t(fallback)}];
    }

    // ---- Participants panel ---------------------------------------------------------
    function status(id) {
      const run = getState().agentRun;
      if (run?.active?.includes(id) || run?.current === id) return run?.states?.[id] || 'responding';
      if (run?.queue?.includes(id)) return 'queued';
      return 'idle';
    }
    const STATUS_LABEL = {responding:'응답 중', working:'작업 중', waiting:'작업 차례 대기', approval:'승인 대기', queued:'차례 대기', idle:'대기'};
    /** A usable character pack by id, or null for the letter avatar. */
    const characterPack = id => id ? (getCharacters()?.packs || []).find(p => p.id === id && p.valid !== false) || null : null;
    function avatar(id, name, character = '') {
      const pack = characterPack(character);
      if (pack) { const img = characterIcon('idle', 'agent-avatar agent-avatar-image', pack); img.dataset.agent = id; return img; }
      const a = el('span', name.slice(0, 1), 'agent-avatar'); a.dataset.agent = id; a.setAttribute('aria-hidden', 'true'); return a;
    }
    function codexSettings() {
      const state = getState(), box = el('div', null, 'agent-settings');
      const models = (state.models || []).filter(m => !m.hidden).map(m => ({id:m.model || m.id, label:m.displayName || m.model || m.id}));
      const model = select(models.length ? models : [{id:'', label:t('모델 목록 조회 필요')}], $('model').value, t('Codex 모델'));
      model.disabled = !models.length;
      model.onchange = () => { $('model').value = model.value; $('model').dispatchEvent(new Event('change')); panelSignature = ''; renderPanel(); };
      const effort = el('select'); effort.setAttribute('aria-label', t('Codex 추론'));
      for (const option of $('effort').options) { const copy = el('option', option.textContent); copy.value = option.value; effort.append(copy); }
      effort.value = $('effort').value; effort.disabled = $('effort').disabled;
      effort.onchange = () => { $('effort').value = effort.value; $('effort').dispatchEvent(new Event('change')); };
      const approval = select([...$('approval-mode').options].map(o => ({id:o.value, label:o.textContent})), state.approvalMode || 'auto-review', t('Codex 승인 방식'));
      approval.disabled = !!state.busy;
      approval.onchange = () => { $('approval-mode').value = approval.value; $('approval-mode').dispatchEvent(new Event('change')); };
      box.append(field(t('모델'), model), field(t('추론'), effort), field(t('승인 방식'), approval), el('small', t('하단 모델 선택과 같은 설정입니다.'), 'muted'));
      return box;
    }
    function agentSettings(agent) {
      const box = el('div', null, 'agent-settings'), info = cap(agent.id), available = info.models || [];
      const model = select(available.length ? available.map(m => ({id:m.id, label:m.label && m.label !== m.id ? m.label + ' · ' + m.id : m.id}))
        : [{id:'', label:t(info.state === 'ready' ? '모델 목록 조회 실패' : '로그인 후 모델 조회')}], agent.model, agent.name + ' ' + t('모델'));
      model.disabled = !available.length;
      const chosen = available.find(m => m.id === (agent.model || model.value)), values = chosen?.efforts || [];
      const effort = select(effortOptions(values, agent.provider === 'agy' ? '모델별 설정에 포함' : chosen?.effortSupported === false ? '추론 강도 조절 미지원' : '추론 설정 정보 없음'), agent.effort, agent.name + ' ' + t('추론'));
      effort.disabled = !values.length;
      const mode = select(MODES.map(m => ({id:m.id, label:t(m.label)})), agent.mode || 'chat', agent.name + ' ' + t('권한'));
      const approvalOptions = [{id:'ask', label:t('승인 받기')}];
      if (agent.provider === 'claude' && info.state === 'ready' && info.nativeAutoReview && !info.autoReviewUnavailableModels?.includes(agent.model))
        approvalOptions.push({id:'auto-review', label:t('자동 검토')});
      approvalOptions.push({id:'allow-all', label:t('모두 허용')});
      const approvalValue = agent.approvalMode || 'ask', supported = approvalOptions.some(o => o.id === approvalValue);
      if (!supported) approvalOptions.unshift({id:'', label:t('승인 방식 선택')});
      const approval = select(approvalOptions, supported ? approvalValue : '', agent.name + ' ' + t('승인 방식'));
      approval.disabled = agent.mode !== 'write';
      approval.onchange = () => { if (approval.value) return setAgents([{id:agent.id, approvalMode:approval.value}]); };
      model.onchange = () => {
        const next = available.find(m => m.id === model.value), levels = next?.efforts || [];
        return setAgents([{id:agent.id, model:model.value, effort:levels.includes(agent.effort) ? agent.effort : levels.includes(next?.defaultEffort) ? next.defaultEffort : levels[0] || ''}]);
      };
      effort.onchange = () => setAgents([{id:agent.id, effort:effort.value}]);
      mode.onchange = () => { approval.disabled = mode.value !== 'write'; return setAgents([{id:agent.id, mode:mode.value}]); };
      const remove = btn(t('대화에서 내보내기'), () => setAgents([{id:agent.id, enabled:false}]), 'secondary-button agent-remove');
      const packs = (getCharacters()?.packs || []).filter(p => p.valid !== false);
      const character = select([{id:'', label:t('글자 아이콘')}, ...packs.map(p => ({id:p.id, label:p.name || p.id}))], agent.character || '', agent.name + ' ' + t('캐릭터'));
      character.onchange = () => setAgents([{id:agent.id, character:character.value}]);
      box.append(field(t('모델'), model), field(t('추론'), effort), field(t('권한'), mode), field(t('승인 방식'), approval), field(t('캐릭터'), character));
      if (info.state && info.state !== 'ready') box.append(el('p', t(PROVIDER_STATE[info.state] || '확인 전') + (info.reason ? ' · ' + info.reason : ''), 'agent-warning'), btn(t('계정 연결'), openAccounts));
      box.append(remove);
      return box;
    }
    function card(id, name, detail, settings, character = '') {
      const section = el('section', null, 'agent-card'), current = status(id);
      section.dataset.agent = id; section.dataset.status = current;
      const row = el('button', null, 'agent-row'); row.type = 'button'; row.setAttribute('aria-expanded', String(expanded.has(id)));
      const copy = el('span', null, 'agent-copy'); copy.append(el('strong', name), el('small', [t(STATUS_LABEL[current]), detail].filter(Boolean).join(' · ')));
      const chevron = document.createElementNS('http://www.w3.org/2000/svg', 'svg'), use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      use.setAttribute('href', '#i-down'); chevron.append(use); chevron.setAttribute('class', 'agent-chevron'); chevron.setAttribute('aria-hidden', 'true');
      row.append(avatar(id, name, character), copy, chevron);
      row.onclick = () => { if (expanded.has(id)) expanded.delete(id); else expanded.add(id); panelSignature = ''; renderPanel(); };
      section.append(row); if (expanded.has(id)) section.append(settings());
      return section;
    }
    function renderAvatars() {
      const target = $('agents-avatars'); if (!target) return;
      target.replaceChildren(avatar('codex', 'Codex'), ...agents().map(a => avatar(a.id, a.name, a.character)));
      $('agents-toggle').classList.toggle('grouped', agents().length > 0);
    }
    function renderPanel() {
      renderAvatars();
      const panel = $('agents-panel'); if (!panel || panel.hidden) return;
      const state = getState();
      const signature = JSON.stringify([state.threadId, state.agents, state.agentRun, state.approvalMode, state.busy, catalog, state.models?.length, $('model').value, $('effort').value, [...expanded], window.MobileCodexLocale?.language?.()]);
      // Never rebuild a select the user is operating.
      if (signature === panelSignature || (panel.contains(document.activeElement) && document.activeElement.tagName === 'SELECT')) return;
      panelSignature = signature;
      const modelName = id => (state.models || []).find(m => (m.model || m.id) === id)?.displayName || id;
      const list = [card('codex', 'Codex', modelName($('model').value), codexSettings)];
      for (const agent of agents()) list.push(card(agent.id, agent.name, agent.model || t(PROVIDER_STATE[cap(agent.id).state] || '확인 전'), () => agentSettings(agent), agent.character));
      $('agents-list').replaceChildren(...list);
      $('agents-invite').disabled = AGENTS.every(a => agents().some(x => x.id === a.id));
    }
    function setPanelOpen(open) {
      $('agents-panel').hidden = !open; $('agents-toggle').setAttribute('aria-expanded', String(open));
      if (open) { closeFilePanel(); panelSignature = ''; renderPanel(); if (!catalog.length) refreshProviders(); }
    }

    // ---- Invite sheet -----------------------------------------------------------------
    const picks = new Map();
    function renderInvite() {
      const joined = new Set(agents().map(a => a.id)), workspace = getState().workspace;
      const signature = JSON.stringify([catalog, [...joined], [...picks], workspace?.selected, !!providerCheck]);
      if (signature === inviteSignature) return; inviteSignature = signature;
      const rows = AGENTS.map(agent => {
        const info = cap(agent.id), ready = info.state === 'ready', inside = joined.has(agent.id), pick = picks.get(agent.id);
        const row = el('div', null, 'invite-row'); row.dataset.agent = agent.id;
        const check = el('input'); check.type = 'checkbox'; check.id = 'invite-' + agent.id; check.checked = inside || !!pick; check.disabled = inside || !ready;
        check.onchange = () => { if (check.checked) picks.set(agent.id, {mode:workspace?.selected ? 'read' : 'chat'}); else picks.delete(agent.id); renderInvite(); };
        const label = el('label', null, 'invite-label'); label.htmlFor = check.id;
        const copy = el('span', null, 'agent-copy');
        copy.append(el('strong', agent.name), el('small', inside ? t('대화에 참여 중') : ready ? (defaultsFor(agent.id).model || t('모델 목록 조회 필요')) : t(providerCheck && !info.state ? '상태 확인 중…' : PROVIDER_STATE[info.state] || '확인 전')));
        label.append(check, avatar(agent.id, agent.name), copy); row.append(label);
        if (!ready && !inside) row.append(btn(t('계정 연결'), () => { close('invite-dialog'); openAccounts(); }, 'secondary-button invite-connect'));
        if (pick) {
          const mode = select(MODES.map(m => ({id:m.id, label:t(m.label)})), pick.mode, agent.name + ' ' + t('권한'));
          mode.onchange = () => { pick.mode = mode.value; };
          row.append(field(t('권한'), mode));
          if (pick.mode !== 'chat' && !workspace?.selected) row.append(el('p', t('작업 폴더를 선택해야 파일 권한을 쓸 수 있습니다.'), 'agent-warning'));
        }
        return row;
      });
      $('invite-list').replaceChildren(...rows);
      $('invite-confirm').disabled = !picks.size;
      if (!providerCheck) $('invite-status').textContent = picks.size ? '' : t('함께 대화할 에이전트를 1~2명 선택해 주세요.');
    }
    async function openInvite() {
      closeAddMenu(); picks.clear(); inviteSignature = ''; $('invite-status').textContent = t('연결 상태를 확인하는 중…');
      show('invite-dialog'); renderInvite();
      await refreshProviders(); inviteSignature = ''; renderInvite();
    }
    async function confirmInvite() {
      const changes = [...picks].map(([id, pick]) => ({id, ...defaultsFor(id), mode:pick.mode, enabled:true}));
      if (!changes.length) return;
      $('invite-confirm').disabled = true;
      try { await setAgents(changes); close('invite-dialog'); toast(t('{names} 님을 대화에 초대했습니다.', {names:changes.map(c => AGENTS.find(a => a.id === c.id).name).join(', ')})); }
      finally { $('invite-confirm').disabled = !picks.size; }
    }

    // ---- Account cards and CLI setup (Settings → Account) ------------------------------
    const providerIds = ['claude', 'agy'];
    function setupState(id) { if (!setupStates.has(id)) setupStates.set(id, {running:false, text:'', action:'login'}); return setupStates.get(id); }
    /** Claude's /usage rows are English sentences; name the common ones in the app's language. */
    function usageLabel(label) {
      if (/^current session$/i.test(label)) return t('현재 세션');
      const week = label.match(/^current week \((.+)\)$/i);
      if (week) return t('이번 주') + ' · ' + (/^all models$/i.test(week[1]) ? t('전체 모델') : week[1]);
      return label;
    }
    /** Usage limits a provider reported while checking its status (both CLIs print them for /usage). */
    function usageRows(info) {
      if (info.state !== 'ready' || !Array.isArray(info.usage)) return [];
      return info.usage.filter(u => Number.isFinite(u.remainingPercent)).map(u => {
        const seconds = Math.floor(Date.parse(u.resetsAt) / 1000), resetTime = Number.isSafeInteger(seconds) ? usageTime(seconds) : '';
        // Claude's session is its 5-hour window.
        const limit = String(u.limit || ''), duration = /week/i.test(limit) ? t('주간 한도') : /session|5.?hour|five.?hour/i.test(limit) ? t('5시간 한도') : /day|daily/i.test(limit) ? t('일간 한도') : t('사용 한도');
        return {label:usageLabel(String(u.label || '')), remaining:Math.max(0, Math.min(100, u.remainingPercent)), resetTime,
          reset:u.resetText ? t('재설정 {time}', {time:String(u.resetText)}) : t('초기화 시각 확인 불가'), duration};
      });
    }
    function renderAccounts() { for (const [id, ui] of accountCards) {
      const info = catalog.find(c => c.id === id) || {}, progress = setupState(id), name = accountName(id);
      ui.status.textContent = t(progress.running ? (progress.action === 'install' ? '설치 중…' : '로그인 진행 중…') : PROVIDER_STATE[info.state] || '확인 전');
      ui.detail.textContent = info.reason || info.version || t('설치 및 로그인 상태를 확인해 주세요.');
      // The card already names the provider; the button says only what it does.
      ui.login.textContent = t(progress.running ? '진행 보기' : info.state === 'ready' ? '다시 로그인' : '로그인');
      ui.login.setAttribute('aria-label', name + ' ' + ui.login.textContent);
      ui.install.setAttribute('aria-label', name + ' ' + t('CLI 설치 / 업데이트'));
      ui.login.disabled = !progress.running && info.state === 'missing'; ui.install.disabled = progress.running;
      ui.refresh.disabled = !!providerCheck || progress.running;
      ui.usage.replaceChildren(...usageRows(info).map(usageGauge)); ui.usage.hidden = !ui.usage.childElementCount;
    } }
    function mountAccounts() {
      const profiles = document.querySelector('.account-profiles-card'); if (!profiles) return;
      const container = el('div', null, 'provider-accounts'); container.id = 'provider-accounts'; profiles.after(container);
      for (const id of providerIds) {
        const name = accountName(id), card = el('section', null, 'devtools-card provider-account'); card.dataset.provider = id;
        const heading = el('div', null, 'card-heading'), copy = el('span'), label = el('strong', name), state = el('small');
        label.id = 'account-' + id + '-title'; state.id = 'account-' + id + '-status'; state.setAttribute('role', 'status'); copy.append(label, state);
        card.setAttribute('aria-labelledby', label.id);
        const refresh = btn(t('상태 확인'), () => refreshProviders(true)); refresh.setAttribute('aria-label', name + ' ' + t('상태 확인')); heading.append(copy, refresh);
        const detail = el('p', null, 'muted'), actions = el('div', null, 'provider-account-actions');
        const login = btn(t('로그인'), () => setup(id, 'login'), 'primary-button'), install = btn(t('CLI 설치 / 업데이트'), () => setup(id, 'install'));
        login.id = 'account-' + id + '-login'; install.id = 'account-' + id + '-install'; actions.append(login, install);
        const usage = el('div', null, 'usage-limits provider-usage'); usage.id = 'account-' + id + '-usage'; usage.hidden = true;
        card.append(heading, detail, usage, actions); container.append(card); accountCards.set(id, {status:state, detail, login, install, refresh, usage});
      }
      const prerequisite = el('div', null, 'provider-account-prerequisite');
      prerequisite.append(el('p', t('Claude Code·Antigravity는 앱 내 Linux 환경 설치 및 활성화가 필요합니다.'), 'muted'), btn(t('Linux 환경 설정'), openLinuxSettings));
      container.append(prerequisite); renderAccounts();
    }
    function setupDialog(label) {
      const d = el('dialog', null, 'agent-setup-dialog'), top = el('div', null, 'dialog-head'), h = el('h2', label);
      top.append(h, btn(t('닫기'), () => d.close())); d.append(top); d.setAttribute('aria-label', label);
      document.body.append(d); d.addEventListener('close', () => d.remove()); d.showModal(); return d;
    }
    function drawSetup() {
      if (!setupView) return;
      const {id, output, links, entry, inputButtons, stop, retry, summary, finish} = setupView, progress = setupState(id);
      output.textContent = progress.text; output.scrollTop = output.scrollHeight;
      summary.textContent = t(progress.running ? (progress.action === 'install' ? '설치 중 질문이 나오면 아래에서 응답해 주세요.' : '브라우저에서 로그인하고 필요한 코드를 입력해 주세요. 첫 설정까지 마치면 \'로그인 마침\'을 눌러 주세요.') : '작업이 종료되었습니다. 계정에서 연결 상태를 확인해 주세요.');
      entry.disabled = !progress.running; for (const button of inputButtons) button.disabled = !progress.running; stop.disabled = !progress.running; retry.hidden = progress.running;
      // agy stays open as an agent after signing in; finishing closes it and rechecks the account.
      finish.hidden = !progress.running || progress.action !== 'login';
      // Keep auth URLs in memory only; opening one requires an explicit user click.
      const urls = progress.running && progress.action === 'login' ? signInUrls(progress.text) : [];
      links.replaceChildren(...urls.slice(-2).map(url => {
        const row = el('div', null, 'provider-login-link');
        const copy = btn(t('주소 복사'), async () => { await call('ui.copyCode', {code:url}); toast(t('로그인 주소를 복사했습니다.')); });
        row.append(btn(t('브라우저에서 로그인'), () => call('ui.providerLoginBrowser', {url}), 'primary-button'), copy, el('small', new URL(url).host, 'muted'));
        return row;
      }));
    }
    /** Sign-in URLs in CLI output. A URL that a terminal wrapped onto the following lines is joined back. */
    function signInUrls(text) {
      const lines = String(text).replace(/\r/g, '').split('\n'), found = [];
      const separator = line => /^\s*[-─━═_]{3,}\s*$/.test(line);
      for (let i = 0; i < lines.length; i++) {
        if (separator(lines[i])) {
          let end = i + 1;
          while (end < lines.length && !separator(lines[end])) end++;
          // agy wraps its URL, including blank lines, between horizontal rules.
          // Wait for the closing rule so a partial streaming chunk cannot be opened.
          if (end === lines.length) break;
          const url = lines.slice(i + 1, end).join('').replace(/\s/g, '');
          if (/^https:\/\/[^<>"'\x1b]+$/.test(url)) {
            try { if (new URL(url).protocol === 'https:') found.push(url); } catch {}
            i = end;
          } else i = end - 1; // Adjacent rules or a heading may precede the URL block.
          continue;
        }
        const match = lines[i].match(/https:\/\/[^\s<>"'\x1b]+/); if (!match) continue;
        let part = match[0], url = part;
        // Join only while the URL ran to the end of its line and the next line holds URL characters alone.
        while (lines[i].trimEnd().endsWith(part) && /^[A-Za-z0-9\-._~:/?#[\]@!$&()*+,;=%]+$/.test(lines[i + 1] || '')) { part = lines[++i]; url += part; }
        try { if (new URL(url).protocol === 'https:') found.push(url); } catch {}
      }
      return [...new Set(found)];
    }
    async function startSetup(id, action) {
      const progress = setupState(id); if (progress.running) return;
      Object.assign(progress, {action, running:true, text:t(action === 'install' ? 'CLI 설치를 시작합니다.' : '로그인을 시작합니다.') + '\n'}); renderAccounts(); drawSetup();
      try { await call('agents.provider.' + action, {provider:id}); }
      catch (error) { progress.running = false; progress.text += '\n' + error.message; renderAccounts(); drawSetup(); }
    }
    async function setup(id, action) {
      if (setupView) setupView.dialog.close();
      const progress = setupState(id), activeAction = progress.running ? progress.action : action, name = accountName(id);
      const d = setupDialog(t(activeAction === 'install' ? '{provider} 설치' : '{provider} 로그인', {provider:name}));
      const output = el('pre', null, 'agent-terminal'), links = el('div', null, 'provider-login-links'), summary = el('p', null, 'muted');
      output.id = 'agent-setup-output'; output.setAttribute('aria-live', 'polite'); links.id = 'agent-login-links';
      const entry = el('input'); entry.type = activeAction === 'install' ? 'text' : 'password'; entry.autocomplete = 'off'; entry.autocapitalize = 'off'; entry.spellcheck = false;
      entry.placeholder = t(activeAction === 'install' ? '예: y 또는 n' : '로그인 코드 또는 터미널 입력');
      const send = text => call('agents.provider.input', {provider:id, text});
      const submit = async () => { const text = entry.value; await send(text.replace(/[\r\n]+$/, '') + '\n'); entry.value = ''; };
      const sendInput = btn(t('입력 전송'), submit), enter = btn('Enter', () => send('\n')), stop = btn(t('취소 / 종료'), () => call('agents.provider.stop', {provider:id})), retry = btn(t('다시 시도'), () => startSetup(id, activeAction));
      const yes = btn('y', () => send('y\n')), no = btn('n', () => send('n\n'));
      const up = btn('↑', () => send('\x1b[A')), down = btn('↓', () => send('\x1b[B'));
      up.setAttribute('aria-label', t('이전 항목')); down.setAttribute('aria-label', t('다음 항목'));
      const tab = btn('Tab', () => send('\t')), escape = btn('Esc', () => send('\x1b'));
      const quickInputs = el('div', null, 'provider-terminal-keys'); quickInputs.append(yes, no, up, down, tab, enter, escape); yes.hidden = no.hidden = activeAction !== 'install';
      entry.onkeydown = e => { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); submit().catch(error => toast(error.message)); } };
      const inputField = el('label', null, 'option-field'); inputField.append(el('span', t(activeAction === 'install' ? '설치 입력' : '로그인 입력')), entry);
      const finish = btn(t('로그인 마침'), () => call('agents.provider.stop', {provider:id}), 'primary-button agent-finish');
      d.append(summary, links, output, quickInputs, inputField, sendInput, finish, stop, retry, btn(t('Linux 환경 설정'), () => { d.close(); openLinuxSettings(); }));
      const view = {id, dialog:d, output, links, entry, inputButtons:[sendInput, enter, yes, no, up, down, tab, escape], stop, retry, summary, finish}; setupView = view;
      d.addEventListener('close', () => { entry.value = ''; if (setupView === view) setupView = null; });
      drawSetup(); if (!progress.running) await startSetup(id, action);
    }

    // ---- Wiring -------------------------------------------------------------------------------
    $('composer-invite').addEventListener('click', () => openInvite().catch(error => toast(error.message)));
    $('agents-invite').addEventListener('click', () => openInvite().catch(error => toast(error.message)));
    $('invite-confirm').addEventListener('click', () => confirmInvite().catch(error => toast(error.message)));
    $('agents-toggle').addEventListener('click', () => setPanelOpen($('agents-panel').hidden));
    $('agents-close').addEventListener('click', () => setPanelOpen(false));
    $('files-toggle').addEventListener('click', () => { if (!$('agents-panel').hidden) setPanelOpen(false); }, true);
    $('agents-panel').addEventListener('keydown', e => { if (e.key === 'Escape') { setPanelOpen(false); $('agents-toggle').focus(); } });
    mountAccounts();
    mergeProviders(getState().agentProviders);

    function event(name, data) {
      if (name === 'agents.error') toast(data.message);
      if (name === 'agents.providers') mergeProviders(data.providers);
      if (name === 'agents.setup' && providerIds.includes(data.provider)) {
        const progress = setupState(data.provider);
        if (data.kind === 'started') { progress.running = true; progress.action = data.action; }
        if (data.kind === 'exit') { progress.running = false; refreshProviders(); }
        progress.text = (progress.text + (data.text || (data.kind === 'exit' ? '\n' + t('종료 코드: ') + data.code : ''))).slice(-24000);
        renderAccounts(); if (setupView?.id === data.provider) drawSetup();
      }
    }
    /** Participants in a mention autocomplete: everyone in this chat, then @all. */
    function mentionChoices(query) {
      if (!agents().length) return [];
      const people = [{id:'codex', name:'Codex'}, ...agents().map(a => ({id:a.id, name:a.name})), {id:'all', name:t('모두')}];
      const q = String(query || '').toLowerCase();
      return people.filter(p => !q || p.id.startsWith(q) || p.name.toLowerCase().startsWith(q));
    }
    function render() {
      if (getState().agentProviders?.length) for (const p of getState().agentProviders) if (!catalog.some(c => c.id === p.id && JSON.stringify(c) === JSON.stringify(p))) { mergeProviders(getState().agentProviders); break; }
      renderPanel();
    }
    function localize() { document.getElementById('provider-accounts')?.remove(); accountCards.clear(); mountAccounts(); panelSignature = ''; inviteSignature = ''; renderPanel(); }
    return {event, render, refreshProviders, localize, mentionChoices, grouped:() => agents().length > 0, closePanel:() => setPanelOpen(false)};
  }};
})();
