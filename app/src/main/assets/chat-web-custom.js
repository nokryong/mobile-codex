/* Mobile Codex: add a Chat/Codex switch beside the official sidebar logo. */
(() => {
  'use strict';
  if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com' || window.top !== window) return;
  if (window.__mcChatCustom) { window.__mcChatCustom.refresh(); return; }
  const SWITCH = 'mc-chat-mode-switch';
  const css = `#${SWITCH}{display:inline-flex;align-items:center;gap:2px;padding:1px;border:0;border-radius:999px;background:#eeeef0;font:600 13px/1.2 system-ui,sans-serif;flex:none;margin-inline-start:8px;margin-inline-end:auto;width:112px;box-sizing:border-box;position:relative;z-index:1}
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
        .find(a => a.querySelector('svg,img') && !/new chat|새 채팅/i.test(a.textContent || '') && !a.closest('[data-message-author-role]'));
      if (link) return link;
    }
    // Some mobile variants render a dialog instead of an aside. The top home logo
    // must share a row with the sidebar close button; a new-chat link is excluded.
    for (const link of document.querySelectorAll('a[href="/"]')) {
      if (!link.querySelector('svg,img') || /new chat|새 채팅/i.test(link.textContent || '') || link.closest('main,[data-message-author-role]')) continue;
      const row = link.parentElement;
      if (row && row.querySelector('button[aria-label*="sidebar" i],button[aria-label*="사이드바"],button[aria-label*="Close" i],button[aria-label*="닫기"]')) return link;
    }
    return null;
  }
  function refresh() {
    scheduled = false;
    if (!document.getElementById('mc-chat-mode-style')) {
      const style = document.createElement('style'); style.id = 'mc-chat-mode-style'; style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
    // Undo the mistaken previous injection if this script is reloaded in place.
    document.querySelectorAll('[data-mc-hidden-official-mode-switch]').forEach(element => element.removeAttribute('data-mc-hidden-official-mode-switch'));
    const logo = findLogo();
    if (!logo) return;
    const existing = document.getElementById(SWITCH);
    if (existing && existing.previousElementSibling === logo) return;
    if (existing) existing.remove();
    const group = document.createElement('div'); group.id = SWITCH; group.setAttribute('role','group'); group.setAttribute('aria-label','대화 모드');
    const chat = document.createElement('button'); chat.type = 'button'; chat.textContent = 'Chat'; chat.setAttribute('aria-pressed','true');
    const codex = document.createElement('a'); codex.href = 'mobilecodex://mode/codex'; codex.textContent = 'Codex'; codex.setAttribute('aria-label','Codex로 전환');
    group.append(chat, codex); logo.after(group);
  }
  function closeSidebar() {
    const logo = findLogo(); if (!logo) return false;
    const row = logo.parentElement;
    const button = row.querySelector('button[aria-label*="Close" i],button[aria-label*="닫기"],button[aria-label*="사이드바 접기"]');
    if (!button || !button.getClientRects().length) return false;
    button.click(); return true;
  }
  const observer = new MutationObserver(records => {
    // Ignore streamed answer updates; sidebar changes need a rescan.
    if (records.every(record => {
      const parent = record.target.nodeType === 1 ? record.target : record.target.parentElement;
      return parent && parent.closest('[data-message-author-role],#' + SWITCH);
    })) return;
    if (!scheduled) { scheduled = true; frame = setTimeout(refresh, 120); } });
  observer.observe(document.documentElement, {childList:true, subtree:true});
  window.__mcChatCustom = {refresh, hasSwitch: () => !!document.getElementById(SWITCH), closeSidebar,
    dispose: () => { observer.disconnect(); clearTimeout(frame); }};
  refresh();
})();
