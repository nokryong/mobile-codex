#!/usr/bin/env node
/* Browser geometry checks for the isolated ChatGPT-web Chat/Codex switch. */
const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'app/src/main/assets/chat-web-custom.js'), 'utf8');
const output = path.join(root, 'artifacts/chat-switch-preview');
fs.mkdirSync(output, {recursive:true});

function pageHtml() {
 return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>
 *{box-sizing:border-box}body{margin:0;font:14px system-ui,sans-serif;overflow:hidden}body.font150 #mc-chat-mode-switch{font-size:19.5px}#stage-sidebar{position:fixed;inset:0 auto 0 0;width:264px;padding:8px 12px;background:#f7f7f8;transition:none}#stage-sidebar.collapsed{width:56px;padding:8px}#stage-sidebar .wordmark{height:42px;display:flex;align-items:center;font-size:18px;font-weight:650}#stage-sidebar #new-chat{display:block;width:100%;height:44px;text-align:left}#stage-sidebar.collapsed .rail-logo{width:40px;height:40px;border-radius:12px;background:#222}#wide-header{height:56px;margin-left:264px;display:flex;align-items:center;gap:8px;padding:0 8px;border-bottom:1px solid #ddd;transition:none}#stage-sidebar.collapsed+#wide-header{margin-left:56px}.sidebar-toggle{width:44px;height:44px}.official-mode{display:flex;gap:4px}.official-mode button{height:32px}.content{margin-left:264px;padding:16px}#stage-sidebar.collapsed~.content{margin-left:56px}</style>
 <aside id="stage-sidebar"><div class="wordmark">ChatGPT</div><button id="new-chat">New chat</button></aside>
 <header id="wide-header"><button class="sidebar-toggle" aria-label="Open sidebar">Menu</button><div class="official-mode"><button aria-selected="true">Chat</button><button aria-selected="false">Work</button></div><span>Conversation</span></header><main class="content"><p>Unrelated stream content</p></main><script>${source}</script>`;
}
async function verify(browser, width) {
 const context = await browser.newContext({viewport:{width,height:900},deviceScaleFactor:1});
 const page = await context.newPage(), errors=[]; page.on('pageerror', error => errors.push(error.message));
 await page.route('https://chatgpt.com/**', route => route.fulfill({contentType:'text/html',body:pageHtml()}));
 await page.goto('https://chatgpt.com/c/fixture');
 try {
  const box = async () => page.evaluate(() => {
   const r = el => el.getBoundingClientRect().toJSON(), group = document.getElementById('mc-chat-mode-switch');
   return {width:innerWidth,group:r(group),parent:group.parentElement.id,buttons:[...group.querySelectorAll('button,a')].map(r),rail:r(document.getElementById('stage-sidebar')),header:r(document.getElementById('wide-header')),official:[...document.querySelectorAll('.official-mode button')].map(button => ({text:button.textContent,selected:button.getAttribute('aria-selected')}))};
  });
  let state = await box();
  assert.equal(state.parent,'stage-sidebar',`expanded static wordmark placement at ${width}px`);
  assert.equal(state.official.map(item=>item.text).join(','),'Chat,Work');
  assert.deepEqual(state.official.map(item=>item.selected),['true','false']);
  for (const control of state.buttons) assert.ok(control.width > 0 && control.height >= 44 && control.left >= 0 && control.right <= width + 1,`expanded switch control is unreachable at ${width}px`);
  await page.evaluate(() => document.body.classList.add('font150'));
  state = await box();
  assert.ok(state.group.width >= 112 && state.group.right <= state.rail.right + 1,`150% text scale clips the expanded switch at ${width}px`);
  for (const control of state.buttons) assert.ok(control.width >= 44 && control.height >= 44 && control.right <= state.rail.right + 1,`150% text scale clips a switch target at ${width}px`);
  await page.evaluate(() => document.body.classList.remove('font150'));
  await page.screenshot({path:path.join(output,`expanded-${width}.png`)});

  await page.evaluate(() => { const side=document.getElementById('stage-sidebar'); side.classList.add('collapsed'); side.innerHTML='<div class="rail-logo" aria-label="ChatGPT"></div>'; window.__mcChatCustom.refresh(); });
  state = await box();
  assert.equal(state.parent,'wide-header',`collapsed rail must use the wide header at ${width}px`);
  assert.ok(state.group.left >= state.header.left && state.group.right <= state.header.right + 1,`fallback switch leaves wide header at ${width}px`);
  assert.ok(state.group.left >= state.rail.right - 1,`fallback switch was injected into the narrow rail at ${width}px`);
  for (const control of state.buttons) assert.ok(control.height >= 44 && control.left >= 0 && control.right <= width + 1,`collapsed switch control is unreachable at ${width}px`);
  await page.screenshot({path:path.join(output,`collapsed-${width}.png`)});

  const rotatedWidth = width === 393 ? 800 : 393;
  await page.setViewportSize({width:rotatedWidth,height:width === 393 ? 600 : 900});
  await page.evaluate(() => { const side=document.getElementById('stage-sidebar'); side.classList.remove('collapsed'); side.innerHTML='<div class="wordmark">ChatGPT</div><button id="new-chat">New chat</button>'; window.__mcChatCustom.refresh(); });
  await page.waitForFunction(() => document.getElementById('mc-chat-mode-switch')?.parentElement?.id === 'stage-sidebar');
  state = await box();
  assert.equal(state.parent,'stage-sidebar',`rotated expanded sidebar placement at ${width}px`);
  for (const control of state.buttons) assert.ok(control.height >= 44 && control.left >= 0 && control.right <= rotatedWidth + 1,`rotated switch control is unreachable from ${width}px`);

  await page.evaluate(() => { const side=document.getElementById('stage-sidebar'); side.outerHTML='<aside id="stage-sidebar"><div class="wordmark">ChatGPT</div><button id="new-chat">New chat</button></aside>'; });
  await page.waitForFunction(() => document.querySelector('#stage-sidebar #mc-chat-mode-switch'));
  assert.equal(await page.locator('#mc-chat-mode-switch').count(),1,'sidebar remount duplicates the switch');
  assert.deepEqual(errors,[],'Chat switch browser errors');
  console.log(`PASS ${width}px: static wordmark, collapsed wide-header fallback, rotation, remount, 44px controls`);
 } finally { await context.close(); }
}
(async () => {
 const browser = await chromium.launch({headless:true,executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE || undefined,args:['--no-sandbox','--disable-dev-shm-usage']});
 try { for (const width of [393,800,1280]) await verify(browser,width); }
 finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
