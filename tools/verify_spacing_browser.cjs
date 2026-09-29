#!/usr/bin/env node
/* Real-browser review of every fixture surface. Starts only a loopback review server. */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createReviewServer, inventory} = require('./ui_review_server.cjs');

let chromium;
try { ({chromium} = require('playwright')); }
catch (error) {
  console.warn('SKIP: Playwright is unavailable. Set NODE_PATH to the existing test dependency cache; no browser was installed.');
  if (process.env.UI_REVIEW_REQUIRE_BROWSER === '1') throw error;
  process.exit(0);
}
const root = path.resolve(__dirname, '..');
const output = path.resolve(root, process.env.UI_REVIEW_OUTPUT || 'artifacts/ui-review');
const viewports = [{width:320,height:568},{width:393,height:852},{width:800,height:1100},{width:1280,height:900}], themes = ['light','dark'], sizes = [100,150];
const escape = value => String(value).replace(/[^a-z0-9_-]+/gi, '-');
const overlaps = (a, b) => a.left < b.right - 1 && a.right > b.left + 1 && a.top < b.bottom - 1 && a.bottom > b.top + 1;

function startServer() {
  const server = createReviewServer();
  return new Promise((resolve, reject) => {
    server.once('error', reject); server.listen(0, '127.0.0.1', () => resolve({server, base:'http://127.0.0.1:' + server.address().port}));
  });
}
async function inspect(page, name, width) {
  const geometry = await page.evaluate(() => {
    const rect = selector => { const e=document.querySelector(selector); if(!e)return null; const r=e.getBoundingClientRect(); return {selector,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height}; };
    const visible = e => { const s=getComputedStyle(e),r=e.getBoundingClientRect(); return !e.hidden&&s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0; };
    const controls=[...document.querySelectorAll('button,input,select,textarea')].filter(visible).map(e=>{const r=e.getBoundingClientRect();return {id:e.id,tag:e.tagName,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};});
    const dialogs=[...document.querySelectorAll('dialog[open]')].map(e=>{const r=e.getBoundingClientRect();return {id:e.id,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height,closers:[...e.querySelectorAll('[data-close], [data-review-close]')].filter(visible).length};});
    const panel=rect('#file-panel'); return {scrollWidth:document.documentElement.scrollWidth,viewport:innerWidth,viewportHeight:innerHeight,dialogs,controls,panel,panelTransform:panel?getComputedStyle(document.getElementById('file-panel')).transform:'none',composer:rect('#composer'),prompt:rect('#prompt'),send:rect('#send'),attach:rect('#add-attachment'),voice:rect('#voice-input')};
  });
  const surface = await page.evaluate(() => window.UiReview.geometry());
  assert.ok(!surface.clippedPrompt, `prompt text clipped on ${name}`);
  assert.ok(!surface.dictationVisible || surface.composerExpanded, `dictation uses compact composer on ${name}`);
  assert.ok(geometry.scrollWidth <= geometry.viewport + 1, `${name} creates horizontal document overflow at ${width}px`);
  for (const dialog of geometry.dialogs) {
    assert.ok(dialog.left >= -1 && dialog.right <= geometry.viewport + 1, `${dialog.id} overflows horizontally at ${width}px`);
    assert.ok(dialog.top >= -1 && dialog.bottom <= geometry.viewportHeight + 1, `${dialog.id} overflows vertically at ${width}px`);
    assert.ok(dialog.closers > 0, `${dialog.id} has no visible close or cancel action`);
    if (width <= 760 && ['editor-dialog','config-dialog','image-dialog'].includes(dialog.id)) assert.ok(Math.abs(dialog.width - geometry.viewport) <= 1, `${dialog.id} leaves an unintended side gutter`);
  }
  const mainIds = new Set(['new-chat','add-attachment','send','voice-input','composer-options','settings','show-tools','account-button']);
  for (const control of geometry.controls.filter(control => mainIds.has(control.id))) assert.ok(control.width >= 43.5 && control.height >= 43.5, `${control.id} loses its 44px target on ${name}`);
  if (geometry.composer && geometry.prompt && geometry.send) {
    assert.ok(geometry.composer.left >= -1 && geometry.composer.right <= geometry.viewport + 1, `composer leaves viewport on ${name}`);
    assert.ok(!overlaps(geometry.prompt, geometry.send), `prompt overlaps send control on ${name}`);
    if (geometry.attach) assert.ok(!overlaps(geometry.attach, geometry.prompt), `attachment control overlaps prompt on ${name}`);
    if (geometry.voice) assert.ok(!overlaps(geometry.voice, geometry.prompt), `voice control overlaps prompt on ${name}`);
  }
  if (name === 'files' && geometry.panel) {
    assert.ok(geometry.panel.left >= -1 && geometry.panel.right <= geometry.viewport + 1, 'file panel is offset outside the viewport');
    assert.equal(geometry.panelTransform, 'none', 'file panel has an unexpected second transform offset');
  }
  return geometry;
}
async function stabilize(page, textSize) {
  await page.waitForFunction(() => window.__uiReviewReady === true && window.__uiReviewSurfaceReady === true);
  await page.evaluate(async () => {
    await document.fonts?.ready;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  if (textSize !== 100) await page.waitForFunction(value => document.documentElement.dataset.uiReviewTextZoom === String(value), textSize);
}
async function run() {
  fs.mkdirSync(output, {recursive:true});
  const {server, base} = await startServer();
  let browser;
  try {
    try { browser = await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage'],executablePath:process.env.MOBILE_CODEX_BROWSER_EXECUTABLE || undefined}); }
    catch (error) {
      console.warn('SKIP: Playwright is installed but no browser executable is available. No browser was installed.');
      if (process.env.UI_REVIEW_REQUIRE_BROWSER === '1') throw error;
      return;
    }
    const catalog = inventory(), surfaces = catalog.surfaces;
    const before = process.env.UI_REVIEW_BEFORE === '1' ? ['/before'] : [];
    for (const viewport of viewports) for (const theme of themes) for (const textSize of sizes) {
      const {width,height} = viewport;
      const context = await browser.newContext({viewport,colorScheme:theme,deviceScaleFactor:1});
      const page = await context.newPage(), errors=[];
      page.on('pageerror', error => errors.push(error.message));
      for (const name of surfaces) {
        const url = base + '/app?surface=' + encodeURIComponent(name) + '&theme=' + theme + '&language=ko&textSize=' + textSize;
        await page.goto(url, {waitUntil:'load'});
        await stabilize(page, textSize);
        await page.evaluate(async () => { await document.fonts.ready; await Promise.all(document.getAnimations().filter(a => Number.isFinite(a.effect?.getComputedTiming().endTime)).map(a => a.finished.catch(() => {}))); });
        await inspect(page, name, width);
        await page.screenshot({path:path.join(output, [escape(name),width+'x'+height,theme,textSize].join('-') + '.png'), fullPage:false});
      }
      for (const route of before) {
        await page.goto(base + route + '?surface=main&theme=' + theme + '&language=ko&textSize=' + textSize, {waitUntil:'load'});
        await stabilize(page, textSize);
        await inspect(page, 'before-main', width);
        await page.screenshot({path:path.join(output, ['before-main',width+'x'+height,theme,textSize].join('-') + '.png'), fullPage:false});
      }
      assert.deepEqual(errors, [], `Page errors at ${width}px ${theme} ${textSize}%`);
      await context.close();
    }
    console.log(`UI review passed: ${surfaces.length} surfaces across ${viewports.length * themes.length * sizes.length} viewport/theme/text-size combinations.`);
  } finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
}
run().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
