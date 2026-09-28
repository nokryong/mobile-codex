/* Mobile Codex: add a Chat/Codex switch beside the official sidebar logo. */
(() => {
  'use strict';
  if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com' || window.top !== window) return;
  if (window.__mcChatCustom) { window.__mcChatCustom.refresh(); return; }
  const SWITCH = 'mc-chat-mode-switch';
  const css = `#${SWITCH}{display:inline-flex;align-items:center;gap:2px;padding:1px;border:0;border-radius:999px;background:#eeeef0;font:600 13px/1.2 system-ui,sans-serif;flex:none;margin-inline-start:8px;margin-inline-end:auto;width:112px;box-sizing:border-box;position:relative;z-index:1}
#${SWITCH}.mc-below-title{display:flex;margin:2px 0 6px 8px}
#${SWITCH}.mc-at-sidebar-start{display:flex;margin:0 16px 4px}
#${SWITCH} button,#${SWITCH} a{display:flex;align-items:center;justify-content:center;min-height:40px;min-width:0;flex:1;padding:0 8px;border:0;border-radius:999px;font:inherit;text-decoration:none;color:#64646c;background:transparent;white-space:nowrap;cursor:pointer}
#${SWITCH} button{background:#fafafa;color:#202024;box-shadow:0 1px 4px #0001}
#${SWITCH} a:focus-visible{outline:2px solid #397cf6;outline-offset:2px}
html.dark #${SWITCH}{background:#25252b}html.dark #${SWITCH} button{background:#1b1b1f;color:#ededf0}html.dark #${SWITCH} a{color:#b0b0b9}`;
  let scheduled = false, frame = 0;
  function sidebarRoots() {
    return [...document.querySelectorAll('#stage-slideover-sidebar, #stage-sidebar, [data-testid="sidebar"], [data-testid="sidebar-container"], aside, nav[aria-label]')].sort((a,b) => Number(!!b.getClientRects().length) - Number(!!a.getClientRects().length));
  }
  function findLogo() {
    // Require a home link inside a sidebar, never match a conversation's linked logo.
    for (const root of sidebarRoots()) {
      const link = [...root.querySelectorAll('a[href="/"], a[href="https://chatgpt.com/"]')]
        .find(a => a.querySelector('svg,img') && !/new chat|새 채팅/i.test(a.textContent || '') && !a.closest('[data-app-navigation-rail],[data-message-author-role]') && a.getBoundingClientRect().top < 52);
      if (link) return link;
    }
    // Some mobile variants render a dialog instead of an aside. The top home logo
    // must share a row with the sidebar close button; a new-chat link is excluded.
    for (const link of document.querySelectorAll('a[href="/"]')) {
      if (!link.querySelector('svg,img') || /new chat|새 채팅/i.test(link.textContent || '') || link.closest('main,[data-app-navigation-rail],[data-message-author-role]') || link.getBoundingClientRect().top >= 52) continue;
      const row = link.parentElement;
      if (row && row.querySelector('button[aria-label*="sidebar" i],button[aria-label*="사이드바"],button[aria-label*="Close" i],button[aria-label*="닫기"]')) return link;
    }
    return null;
  }
  function findNavigationPlacement() {
    // Check each navigation in visibility order. Recent web versions place the
    // title outside it; the sidebar's scroll region still identifies the panel.
    const roots = [...document.querySelectorAll('nav[aria-label]')]
      .filter(root => !root.hasAttribute('data-app-navigation-rail') && !root.closest('main'))
      .sort((a,b) => Number(!!b.getClientRects().length) - Number(!!a.getClientRects().length));
    for (const root of roots) {
      const headers = root.querySelectorAll('[class~="@container/navigation-header"]');
      if (headers.length === 1) return {anchor:headers[0], below:true, title:headers[0]};
      if (!headers.length && root.querySelectorAll('[data-app-action-sidebar-scroll]').length === 1)
        return {container:root, prepend:true};
    }
    return null;
  }
  function findTitleAndNewChat() {
    // The newer sidebar shows a text title instead of a linked logo. Keep the
    // switch in that sidebar, immediately before its own New chat action.
    const candidates = [...document.querySelectorAll('a,button,[role="button"]')];
    const title = candidates.find(element => {
      if (element.closest('main,[data-message-author-role]') || (element.textContent || '').trim() !== 'ChatGPT' || !element.getClientRects().length) return false;
      const rect = element.getBoundingClientRect();
      return rect.top >= 0 && rect.top < 180 && rect.left < innerWidth * .65;
    });
    if (!title) return null;
    const rect = title.getBoundingClientRect();
    const newChat = candidates.find(element => {
      if (element === title || element.closest('main') || !element.getClientRects().length) return false;
      const name = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).trim();
      if (!/^(?:새 채팅|새 대화|new chat)(?:\s+(?:새 채팅|새 대화|new chat))?$/i.test(name)) return false;
      const next = element.getBoundingClientRect();
      return next.top >= rect.bottom && next.top - rect.bottom < 180 && Math.abs(next.left - rect.left) < 100;
    });
    return newChat ? {title, newChat} : null;
  }
  function target() {
    const navigation = findNavigationPlacement();
    if (navigation) return navigation;
    const logo = findLogo();
    if (logo) return {anchor:logo, before:false};
    const newer = findTitleAndNewChat();
    return newer && {anchor:newer.newChat, before:true, title:newer.title};
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
    if (!place) return;
    const existing = document.getElementById(SWITCH);
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
    else place.anchor.after(group);
  }
  function closeSidebar() {
    const place = target(); if (!place) return false;
    const start = place.container || place.title || place.anchor;
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
      return parent && parent.closest('[data-message-author-role],[data-content-search-turn-key],#' + SWITCH);
    })) return;
    scheduleRefresh(); });
  observer.observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['hidden','aria-hidden','data-state']});
  window.addEventListener('resize', scheduleRefresh);
  window.__mcChatCustom = {refresh, hasSwitch: () => !!document.getElementById(SWITCH), closeSidebar,
    dispose: () => { observer.disconnect(); clearTimeout(frame); window.removeEventListener('resize', scheduleRefresh); }};
  refresh();
})();
