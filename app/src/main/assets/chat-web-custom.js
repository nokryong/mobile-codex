/* Mobile Codex: add a Chat/Codex switch beside the official sidebar logo. */
(() => {
  'use strict';
  if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com' || window.top !== window) return;
  if (window.__mcChatCustom) { window.__mcChatCustom.refresh(); return; }
  const SWITCH = 'mc-chat-mode-switch', SWITCH_SPACE = 160;
  const CLOSE_SIDEBAR = 'button[aria-label*="Close sidebar" i],button[aria-label="Hide sidebar" i],button[aria-label*="사이드바 닫기"],button[aria-label*="사이드바 접기"],button[aria-label*="사이드바 숨기기"]';
  const css = `#${SWITCH}{display:inline-flex;align-items:center;gap:2px;padding:1px;border:0;border-radius:999px;background:#eeeef0;font:600 13px/1.2 system-ui,sans-serif;flex:none;margin-inline-start:8px;margin-inline-end:auto;width:max-content;min-width:112px;max-width:calc(100% - 8px);box-sizing:border-box;position:relative;z-index:1}
#${SWITCH}.mc-below-title{display:flex;margin:2px 0 6px 8px}
#${SWITCH}.mc-at-sidebar-start{display:flex;margin:0 16px 4px}
#${SWITCH}.mc-header-fallback{margin:0 8px;align-self:center}
#${SWITCH}.mc-compact-rail{display:flex;width:44px;min-width:44px;max-width:44px;padding:0;margin:4px auto;justify-content:center}
#${SWITCH} button,#${SWITCH} a{display:flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;flex:1 1 auto;padding:0 8px;border:0;border-radius:999px;font:inherit;text-decoration:none;color:#64646c;background:transparent;white-space:nowrap;cursor:pointer}
#${SWITCH} button{background:#fafafa;color:#202024;box-shadow:0 1px 4px #0001}
#${SWITCH} a:focus-visible{outline:2px solid #397cf6;outline-offset:2px}
#${SWITCH}.mc-compact-rail button{display:none}
#${SWITCH}.mc-compact-rail a{width:44px;min-width:44px;padding:0;font-size:11px}
html.dark #${SWITCH}{background:#25252b}html.dark #${SWITCH} button{background:#1b1b1f;color:#ededf0}html.dark #${SWITCH} a{color:#b0b0b9}`;
  let scheduled = false, frame = 0;
  function geometry(element) {
    const rect = element?.getBoundingClientRect?.();
    if (rect && Number.isFinite(rect.width) && Number.isFinite(rect.height) && rect.width > 0 && rect.height > 0) return rect;
    // `display:contents` has no box in real ChatGPT markup, while its visible
    // children still describe a usable sidebar region. Do not generalize this
    // to arbitrary zero-sized elements: those are hidden or clipped targets.
    if (element && getComputedStyle(element).display === 'contents') {
      const children = [...element.children].map(geometry).filter(Boolean);
      if (children.length) {
        const left = Math.min(...children.map(child => child.left)), top = Math.min(...children.map(child => child.top));
        const right = Math.max(...children.map(child => child.right)), bottom = Math.max(...children.map(child => child.bottom));
        return {left, top, right, bottom, width:right-left, height:bottom-top};
      }
    }
    return null;
  }
  function visible(element) {
    if (!element) return false;
    for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
      if (node.hidden || node.getAttribute('aria-hidden') === 'true') return false;
      const style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
    }
    const rect = geometry(element);
    if (!rect || (!element.getClientRects?.().length && getComputedStyle(element).display !== 'contents')) return false;
    return rect.right > 0 && rect.bottom > 0 && rect.left < innerWidth && rect.top < innerHeight;
  }
  function wideEnough(element) {
    const rect = geometry(element);
    return !rect || (rect.width >= SWITCH_SPACE && rect.right > 0 && rect.left < innerWidth);
  }
  function isRail(element) { return !!element?.closest?.('[data-app-navigation-rail],#stage-sidebar-tiny-bar'); }
  function sidebarHost(element) {
    // A nested .sidebar-title/.sidebar-button is not the panel. Prefer an
    // explicit panel boundary, then walk past narrow decorative wrappers.
    const panel = element?.closest?.('#stage-slideover-sidebar, #stage-sidebar, [data-testid="sidebar"], [data-testid="sidebar-container"]');
    if (panel) return panel;
    for (let node = element; node && node !== document.body; node = node.parentElement)
      if (node.matches?.('aside, nav[aria-label], [role="navigation"], [class*="sidebar" i]') && usableSidebar(node)) return node;
    return null;
  }
  function usableSidebar(root) { return visible(root) && wideEnough(root) && !isRail(root); }
  function visibilityRank(element) { return visible(element) ? 1 : 0; }
  function sidebarRoots() {
    return [...document.querySelectorAll('#stage-slideover-sidebar, #stage-sidebar, [data-testid="sidebar"], [data-testid="sidebar-container"], aside, nav[aria-label], [role="navigation"], [class*="sidebar" i]')]
      .filter(usableSidebar)
      .sort((a,b) => visibilityRank(b) - visibilityRank(a));
  }
  function findLogo() {
    // Require a home link inside a sidebar, never match a conversation's linked logo.
    for (const root of sidebarRoots()) {
      const link = [...root.querySelectorAll('a[href="/"], a[href="https://chatgpt.com/"]')]
        .find(a => a.querySelector('svg,img') && visible(a) && wideEnough(a.parentElement) && !/new chat|새 채팅/i.test(a.textContent || '') && !a.closest('[data-app-navigation-rail],[data-message-author-role]') && a.getBoundingClientRect().top < 52);
      if (link) return link;
    }
    // Some mobile variants render a dialog instead of an aside. The top home logo
    // must share a row with the sidebar close button; a new-chat link is excluded.
    for (const link of document.querySelectorAll('a[href="/"]')) {
      if (!link.querySelector('svg,img') || !visible(link) || !wideEnough(link.parentElement) || /new chat|새 채팅/i.test(link.textContent || '') || link.closest('main,[data-app-navigation-rail],[data-message-author-role]') || link.getBoundingClientRect().top >= 52) continue;
      const row = link.parentElement;
      if (row && row.querySelector('button[aria-label*="sidebar" i],button[aria-label*="사이드바"],button[aria-label*="Close" i],button[aria-label*="닫기"]')) return link;
    }
    return null;
  }
  function findNavigationPlacement() {
    // Check each navigation in visibility order. Recent web versions place the
    // title outside it; the sidebar's scroll region still identifies the panel.
    const roots = [...document.querySelectorAll('nav[aria-label]')]
      .filter(root => !root.hasAttribute('data-app-navigation-rail') && !root.closest('main') && usableSidebar(root))
      .sort((a,b) => visibilityRank(b) - visibilityRank(a));
    for (const root of roots) {
      const headers = root.querySelectorAll('[class~="@container/navigation-header"]');
      if (headers.length === 1) return {anchor:headers[0], below:true, title:headers[0], host:root};
      if (!headers.length && root.querySelectorAll('[data-app-action-sidebar-scroll]').length === 1)
        return {container:root, prepend:true, host:root};
    }
    return null;
  }
  function findTitleAndNewChat() {
    // The newer sidebar shows a text title instead of a linked logo. Keep the
    // switch in that sidebar, immediately before its own New chat action.
    const candidates = [...document.querySelectorAll('a,button,[role="button"],h1,h2,h3,[role="heading"],div,span')];
    const titles = candidates.filter(element => {
      if (element.closest('main,[data-message-author-role]') || isRail(element) || (element.textContent || '').trim() !== 'ChatGPT' || !visible(element)) return false;
      const rect = geometry(element);
      return rect.top >= 0 && rect.top < 180 && rect.left >= 0 && rect.left < innerWidth * .65;
    });
    for (const title of titles) {
      const host = sidebarHost(title);
      if (host && !usableSidebar(host)) continue;
      const rect = geometry(title);
      const newChat = candidates.find(element => {
        if (element === title || element.closest('main,[data-message-author-role]') || isRail(element) || !visible(element)) return false;
        const name = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).trim();
        if (!/^(?:새 채팅|새 대화|new chat)(?:\s+(?:새 채팅|새 대화|new chat))?$/i.test(name)) return false;
        const nextHost = sidebarHost(element), next = geometry(element);
        if (host && nextHost && host !== nextHost) return false;
        return next && next.top >= rect.bottom && next.top - rect.bottom < 180 && Math.abs(next.left - rect.left) < 100;
      });
      if (newChat) return {title, newChat};
    }
    return null;
  }
  function findWideHeaderFallback() {
    const selector = 'button[aria-label*="sidebar" i],button[aria-label*="사이드바"],button[aria-label*="navigation" i]';
    const headers = [...document.querySelectorAll('header,[role="banner"]')]
      .filter(header => !isRail(header) && visible(header) && wideEnough(header));
    for (const header of headers) {
      const toggle = [...header.querySelectorAll(selector)].find(button => visible(button));
      if (toggle) {
        let anchor = toggle;
        while (anchor.parentElement && anchor.parentElement !== header) anchor = anchor.parentElement;
        return {anchor, toggle, fallback:true, host:header};
      }
    }
    return null;
  }
  function findPanelToolbar() {
    // Landscape uses a static wordmark and may have an unlabelled navigation.
    // Its explicit sidebar + close control are stable even when the wordmark
    // is an SVG, repeated screen-reader text, or a zero-box wrapper.
    for (const panel of document.querySelectorAll('#stage-slideover-sidebar, #stage-sidebar, [data-testid="sidebar"], [data-testid="sidebar-container"]')) {
      if (!usableSidebar(panel)) continue;
      const close = [...panel.querySelectorAll(CLOSE_SIDEBAR)].find(visible);
      if (!close) continue;
      const panelRect = geometry(panel); let row = null;
      for (let node = close.parentElement; node && node !== panel; node = node.parentElement) {
        const rect = geometry(node);
        if (!rect) continue;
        if (rect.top > panelRect.top + 100 || rect.height > 112) break;
        if (visible(node) && rect.width >= SWITCH_SPACE) row = node;
      }
      if (row) return {anchor:row, below:true, title:row, host:panel};
    }
    return null;
  }
  function findCompactRail() {
    const rail = document.getElementById('stage-sidebar-tiny-bar');
    if (!visible(rail)) return null;
    const rect = geometry(rail);
    if (rect.width < 44 || rect.width >= SWITCH_SPACE) return null;
    const toggle = [...rail.querySelectorAll('button[aria-label*="sidebar" i],button[aria-label*="사이드바"]')].find(visible);
    if (!toggle) return null;
    let anchor = toggle;
    while (anchor.parentElement && anchor.parentElement !== rail) anchor = anchor.parentElement;
    return {anchor, compact:true, toggle, host:rail};
  }
  function target() {
    const newer = findTitleAndNewChat();
    if (newer) return {anchor:newer.newChat, before:true, title:newer.title, host:sidebarHost(newer.newChat) || newer.newChat.parentElement};
    const navigation = findNavigationPlacement();
    if (navigation) return navigation;
    const logo = findLogo();
    if (logo) return {anchor:logo, before:false, host:logo.parentElement};
    return findPanelToolbar() || findWideHeaderFallback() || findCompactRail();
  }
  function refresh() {
    scheduled = false;
    if (!document.getElementById('mc-chat-mode-style')) {
      const style = document.createElement('style'); style.id = 'mc-chat-mode-style'; style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
    // Undo the mistaken previous injection if this script is reloaded in place.
    document.querySelectorAll('[data-mc-hidden-official-mode-switch]').forEach(element => element.removeAttribute('data-mc-hidden-official-mode-switch'));
    const place = target();
    const existing = document.getElementById(SWITCH);
    if (!place) { existing?.remove(); return; }
    if (existing && (place.prepend ? place.container.firstElementChild === existing
      : place.before ? existing.nextElementSibling === place.anchor : existing.previousElementSibling === place.anchor)) return;
    if (existing) existing.remove();
    const group = document.createElement('div'); group.id = SWITCH; group.setAttribute('role','group'); group.setAttribute('aria-label','대화 모드');
    const chat = document.createElement('button'); chat.type = 'button'; chat.textContent = 'Chat'; chat.setAttribute('aria-pressed','true');
    const codex = document.createElement('a'); codex.href = 'mobilecodex://mode/codex'; codex.textContent = 'Codex'; codex.setAttribute('aria-label','Codex로 전환');
    group.append(chat, codex);
    if (place.prepend) { group.classList.add('mc-at-sidebar-start'); place.container.prepend(group); }
    else if (place.before) { group.classList.add('mc-below-title'); place.anchor.before(group); }
    else if (place.below) { group.classList.add('mc-below-title'); place.anchor.after(group); }
    else { if (place.fallback) group.classList.add('mc-header-fallback'); if (place.compact) group.classList.add('mc-compact-rail'); place.anchor.after(group); }
  }
  function closeSidebar() {
    const place = target(); if (!place) return false;
    const start = place.container || place.title || place.toggle || place.anchor;
    const selector = 'button[aria-label*="Close" i],button[aria-label="Hide sidebar" i],button[aria-label*="닫기"],button[aria-label*="사이드바 접기"],button[aria-label*="사이드바 숨기기"]';
    const visible = button => !!button.getClientRects().length && !button.disabled && button.getAttribute('aria-disabled') !== 'true';
    // The close toolbar may now be a sibling of the navigation. Follow its
    // explicit panel relationship before using the legacy enclosing-row path.
    const linked = [...document.querySelectorAll(selector)].find(button => visible(button)
      && (button.getAttribute('aria-controls') || '').split(/\s+/).some(id => document.getElementById(id)?.contains(start)));
    if (linked) { linked.click(); return true; }
    let row = start.parentElement, button = null;
    for (let i = 0; row && row !== document.body && i < 4 && !button; i++, row = row.parentElement)
      button = [...row.querySelectorAll(selector)].find(visible);
    if (!button) return false;
    button.click(); return true;
  }
  function scheduleRefresh() {
    if (!scheduled) { scheduled = true; frame = setTimeout(refresh, 120); }
  }
  const observer = new MutationObserver(records => {
    // Ignore streamed answer updates; sidebar changes need a rescan.
    if (records.every(record => {
      const parent = record.target.nodeType === 1 ? record.target : record.target.parentElement;
      if (!parent) return true;
      if (parent.closest('[data-message-author-role],[data-content-search-turn-key],#' + SWITCH)) return true;
      if (['class','style'].includes(record.attributeName) && !parent.matches('body,#stage-slideover-sidebar,#stage-sidebar,[data-testid="sidebar"],[data-testid="sidebar-container"],aside,nav[aria-label],header,[role="banner"]') && !parent.closest('#stage-slideover-sidebar,#stage-sidebar,[data-testid="sidebar"],[data-testid="sidebar-container"],aside,nav[aria-label],header,[role="banner"]')) return true;
      return false;
    })) return;
    scheduleRefresh(); });
  observer.observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['hidden','aria-hidden','data-state','class','style']});
  window.addEventListener('resize', scheduleRefresh);
  window.__mcChatCustom = {refresh, hasSwitch: () => !!document.getElementById(SWITCH), closeSidebar,
    dispose: () => { observer.disconnect(); clearTimeout(frame); window.removeEventListener('resize', scheduleRefresh); }};
  refresh();
})();
