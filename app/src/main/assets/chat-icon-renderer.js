/*
 * ChatGPT assistant-message icon renderer.
 *
 * Artwork source bucket: mytaskmanager-cf059.appspot.com.  The Android
 * WebViewClient loads the fixed Firebase images with a bundled fallback from the same-origin path
 * below, so this asset needs no Firebase configuration or cross-origin fetch.
 */
(function () {
  'use strict';

  if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com' || window.top !== window) return;
  const GLOBAL_NAME = 'MCChatIconRenderer';
  if (window[GLOBAL_NAME]) {
    window[GLOBAL_NAME].start();
    return;
  }

  const ICON_SIZE = 128;
  const ASSET_BASE = 'https://chatgpt.com/__mobile_codex_icons__/';
  const ICON_ROWS = [
    ['대기중', '01-idle.png'], ['접수', '02-acknowledged.png'], ['생각중', '03-thinking.png'], ['작업중', '04-working.png'],
    ['주인님?', '05-question.png'], ['해냈다', '06-done.png'], ['왜안됨', '07-blocked.png'], ['계획대로', '08-smug.png'],
    ['안녕', '09-greeting.png'], ['어디보자', '10-inspecting.png'], ['잘보세요', '11-explaining.png'], ['찾았다', '12-discovery.png'],
    ['잠깐', '13-caution.png'], ['미안해요', '14-sorry.png'], ['좋았어', '15-happy.png'], ['진짜?', '16-skeptical.png'],
    ['어흐~', '17-eohu.png'], ['우헤헤', '18-uhehe.png'], ['븅신', '19-insult.png'], ['펀치', '20-punch.png'],
    ['조금애매함', '21-uncertain.png'], ['아닌데?', '22-disagree.png'], ['그건안돼', '23-not-allowed.png'], ['파일줘봐', '24-file-request.png'],
    ['검토완료', '25-reviewed.png'], ['출처있음', '26-source.png'], ['수정완료', '27-fixed.png'], ['ㄹㅇㅋㅋ', '28-lol.png'],
    ['정말이지', '29-good-grief.png'], ['윙크', '30-wink.png'], ['하트', '31-heart.png'], ['잘자', '32-sleep.png']
  ];
  const CATEGORY_ROWS = {
    질문: ['주인님?'], 확인: ['검토완료'], 검색: ['어디보자'], 완료: ['해냈다'], 긍정: ['좋았어'],
    부정: ['아닌데?', '그건안돼'], 웃기: ['우헤헤', 'ㄹㅇㅋㅋ'], 사랑: ['하트', '윙크', '어흐~'], 화남: ['펀치'],
    당황: ['왜안됨'], 잠: ['잘자'], 미안: ['미안해요'], 요청: ['파일줘봐'], 작업중: ['작업중'],
    자랑: ['계획대로'], 오류: ['왜안됨'], 응원: ['하트'], 인사: ['안녕'], 대기: ['대기중'], 생각: ['생각중'],
    설명: ['잘보세요'], 발견: ['찾았다'], 주의: ['잠깐'], 의심: ['진짜?'], 애매: ['조금애매함'], 근거: ['출처있음']
  };
  const CATEGORY_ALIASES = {
    의문: '질문', 궁금: '질문', 묻기: '질문', 검토: '확인', 확인하기: '확인', 찾기: '검색', 조사: '검색',
    성공: '완료', 끝: '완료', 좋아: '긍정', 찬성: '긍정', 추천: '긍정', 싫어: '부정', 반대: '부정', 거절: '부정',
    웃음: '웃기', 웃는행위: '웃기', 웃겨: '웃기', 애정: '사랑', 귀여움: '사랑', 분노: '화남', 공격: '화남',
    화내기: '화남', 혼란: '당황', 당황함: '당황', 졸림: '잠', 피곤: '잠', 수면: '잠', 사과: '미안',
    부탁: '요청', 주세요: '요청', 로딩: '작업중', 진행중: '작업중', 실패: '오류', 문제: '오류',
    뽐내기: '자랑', 잘난척: '자랑', 격려: '응원', 힘내: '응원'
  };
  const TOKEN = /\[\[\s*icon\s*:\s*([^\]\r\n]+?)\s*\]\]|\[\s*([0-9]{2}[^\]\r\n]+?)\s*\]/g;
  const STYLE_ID = 'mc-chat-icon-renderer-style';
  const OWNED = '[data-mc-chat-icon-owned="true"]';
  const SKIP = 'script,style,noscript,template,textarea,input,select,option,optgroup,button,output,code,pre,[contenteditable]:not([contenteditable="false"]),[role="textbox"]';
  const normalize = (value) => String(value || '').replace(/[\u200B-\u200D\uFEFF]/g, '').replace(/\s+/g, '').trim().toLowerCase();
  const icons = Object.create(null), aliases = Object.create(null), categories = Object.create(null), categoryLookup = Object.create(null);
  const lastCategoryChoice = new Map();
  let observer = null, scheduled = false;
  const pendingRoots = new Set();

  for (const [name, file] of ICON_ROWS) {
    icons[name] = {name, file};
    aliases[normalize(name)] = name;
    aliases[normalize(file.replace(/\.png$/i, ''))] = name;
  }
  for (const [category, names] of Object.entries(CATEGORY_ROWS)) {
    const key = normalize(category);
    categories[key] = names.filter((name) => icons[name]);
    categoryLookup[key] = key;
  }
  for (const [alias, category] of Object.entries(CATEGORY_ALIASES)) categoryLookup[normalize(alias)] = normalize(category);

  function resolve(value) {
    const key = normalize(value);
    if (aliases[key]) return aliases[key];
    const category = categoryLookup[key], choices = categories[category] || [];
    if (!choices.length) return '';
    const previous = lastCategoryChoice.get(category);
    const pool = choices.length > 1 ? choices.filter((name) => name !== previous) : choices;
    const selected = pool[Math.floor(Math.random() * pool.length)];
    lastCategoryChoice.set(category, selected);
    return selected;
  }

  function hasToken(text) { TOKEN.lastIndex = 0; const found = !!text && TOKEN.test(text); TOKEN.lastIndex = 0; return found; }
  function assistantRootFor(node) {
    const element = node && (node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement);
    return element && (element.matches('[data-message-author-role="assistant"]') ? element : element.closest('[data-message-author-role="assistant"]'));
  }
  function skipText(node) {
    const parent = node && node.parentElement;
    return !parent || parent.isContentEditable || !!parent.closest(SKIP + ',' + OWNED);
  }
  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.dataset.mcChatIconOwned = 'true';
    style.textContent = '.mc-chat-icon{display:inline-flex;align-items:center;justify-content:center;margin:0 4px;vertical-align:middle;line-height:1}.mc-chat-icon>img{display:inline-block;width:' + ICON_SIZE + 'px;height:' + ICON_SIZE + 'px;max-width:' + ICON_SIZE + 'px;max-height:' + ICON_SIZE + 'px;margin:0;vertical-align:middle;object-fit:contain;background:transparent;border:0;border-radius:0;box-shadow:none}.mc-chat-icon--error{display:inline;margin:0;white-space:pre-wrap}';
    (document.head || document.documentElement).appendChild(style);
  }
  function makeIcon(name, raw) {
    const wrapper = document.createElement('span'), image = document.createElement('img');
    wrapper.className = 'mc-chat-icon';
    wrapper.dataset.mcChatIconOwned = 'true';
    wrapper.dataset.mcChatIconToken = raw;
    image.alt = name; image.title = name; image.loading = 'lazy'; image.decoding = 'async';
    image.addEventListener('error', () => {
      if (wrapper.dataset.mcChatIconLoadError) return;
      wrapper.dataset.mcChatIconLoadError = 'true';
      wrapper.classList.add('mc-chat-icon--error');
      wrapper.title = name + ': image failed to load';
      wrapper.replaceChildren(document.createTextNode(raw));
    }, {once: true});
    image.src = ASSET_BASE + encodeURIComponent(icons[name].file);
    wrapper.appendChild(image);
    return wrapper;
  }
  function replaceText(node) {
    if (skipText(node)) return false;
    const text = node.nodeValue || '';
    if (!hasToken(text)) return false;
    const fragment = document.createDocumentFragment();
    let cursor = 0, changed = false, match;
    TOKEN.lastIndex = 0;
    while ((match = TOKEN.exec(text))) {
      const raw = match[0], name = resolve(match[1] || match[2]);
      if (match.index > cursor) fragment.appendChild(document.createTextNode(text.slice(cursor, match.index)));
      fragment.appendChild(name ? makeIcon(name, raw) : document.createTextNode(raw));
      changed ||= !!name;
      cursor = match.index + raw.length;
    }
    TOKEN.lastIndex = 0;
    if (!changed || !node.parentNode) return false;
    if (cursor < text.length) fragment.appendChild(document.createTextNode(text.slice(cursor)));
    node.parentNode.replaceChild(fragment, node);
    return true;
  }
  function scan(root) {
    if (!root || !root.isConnected || !root.matches('[data-message-author-role="assistant"]')) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {acceptNode(node) {
      return !skipText(node) && hasToken(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    }});
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(replaceText);
  }
  function scanAll() { document.querySelectorAll('[data-message-author-role="assistant"]').forEach(scan); }
  function queue(root) { if (root && root.isConnected) pendingRoots.add(root); }
  function processQueue() {
    scheduled = false;
    const roots = [...pendingRoots]; pendingRoots.clear();
    roots.filter((root) => !roots.some((other) => other !== root && other.contains(root))).forEach(scan);
  }
  function schedule() {
    if (!scheduled) { scheduled = true; (window.queueMicrotask || ((fn) => Promise.resolve().then(fn)))(processQueue); }
  }
  function mutations(records) {
    let relevant = false;
    for (const record of records) {
      if (record.type === 'characterData') {
        const root = assistantRootFor(record.target);
        if (root) { queue(root); relevant = true; }
        continue;
      }
      for (const node of record.addedNodes) {
        const root = assistantRootFor(node);
        if (root) { queue(root); relevant = true; }
        if (node.nodeType === Node.ELEMENT_NODE) node.querySelectorAll('[data-message-author-role="assistant"]').forEach((child) => { queue(child); relevant = true; });
      }
    }
    if (relevant) schedule();
  }
  function start() {
    if (!document.body) { document.addEventListener('DOMContentLoaded', start, {once: true}); return; }
    ensureStyle(); scanAll();
    if (!observer) { observer = new MutationObserver(mutations); observer.observe(document.body, {childList: true, subtree: true, characterData: true}); }
  }
  function stop() { if (observer) observer.disconnect(); observer = null; pendingRoots.clear(); scheduled = false; }

  window[GLOBAL_NAME] = Object.freeze({start, stop, resolve, iconSize: ICON_SIZE, assetBase: ASSET_BASE});
  start();
})();
