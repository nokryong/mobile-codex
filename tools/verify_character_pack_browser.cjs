#!/usr/bin/env node
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const web = path.join(root, 'app/src/main/assets/web');
const out = path.join(root, 'artifacts/character-pack');
fs.mkdirSync(out, { recursive: true });
const custom = { id: 'english', name: 'English pack', valid: true, icons: { done: '/packs/done.png', greeting: '/packs/greeting.png' } };
const base = { ready: true, busy: false, permissions: 'workspace-write', status: 'Connected', threadId: 'pack-test', cwd: '/workspace', workspace: { selected: false, name: '' }, projects: [], sessions: [], account: { type: 'chatgpt', email: 'qa@example.test' }, models: [], messages: [{ id: 'a', role: 'assistant', text: '완료했어요.' }] };
const packSnapshot = { folderName: 'Character packs', folderConfigured: true, selectedPackId: 'english', packs: [{ id: 'builtin', name: '기본 캐릭터', valid: true, icons: {} }, custom] };

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.MOBILE_CODEX_BROWSER_EXECUTABLE || undefined, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const results = [];
  try {
    for (const [width, height, theme, language] of [[1280, 900, 'light', 'en'], [393, 852, 'dark', 'ko'], [320, 430, 'light', 'ko']]) {
      const context = await browser.newContext({ viewport: { width, height } });
      const page = await context.newPage();
      await page.route('**/*', route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://appassets.androidplatform.net') return route.abort();
        if (url.pathname.startsWith('/packs/')) return route.fulfill({ path: path.join(web, 'chat-icons/06-done.png') });
        const file = path.resolve(web, '.' + url.pathname);
        return fs.existsSync(file) && file.startsWith(web + path.sep) ? route.fulfill({ path: file }) : route.fulfill({ status: 404, body: '' });
      });
      await page.addInitScript(({ state, theme: selectedTheme, language: selectedLanguage }) => {
        localStorage.setItem('theme', selectedTheme); localStorage.setItem('language', selectedLanguage); window.__qaState = state;
        window.__qaCharacters = { folderName: 'Character packs', folderConfigured: true, selectedPackId: 'english', packs: [{ id: 'builtin', name: '기본 캐릭터', valid: true, icons: {} }, { id: 'english', name: 'English pack', valid: true, icons: { done: '/packs/done.png', greeting: '/packs/greeting.png' } }] }; window.__qaRevision = 0;
        window.Native = { locale: () => JSON.stringify({ choice: selectedLanguage, systemLanguage: selectedLanguage }), postMessage(raw) { const message = JSON.parse(raw); let result = message.action === 'state' ? window.__qaState : message.action === 'characters.select' ? (window.__qaCharacters.selectedPackId = message.args.id, window.__qaCharacters) : message.action === 'characters.refresh' ? (window.__qaRevision += 1, window.__qaCharacters.packs[1].icons.done = `/packs/done-${window.__qaRevision}.png`, window.__qaCharacters) : message.action.startsWith('characters.') ? window.__qaCharacters : {}; setTimeout(() => window.mobileCodexEvent('response', { id: message.id, result }), 0); } };
      }, { state: base, theme, language });
      await page.goto('https://appassets.androidplatform.net/index.html'); await page.waitForTimeout(150);
      if (width < 760) await page.locator('.topbar .sidebar-toggle').click();
      await page.locator('#settings').click(); await page.locator('#settings-dialog').waitFor({ state: 'visible' }); await page.waitForTimeout(100);
      assert.equal(await page.locator('#character-pack-card').isVisible(), true); assert.equal(await page.locator('.character-pack-option').count(), 2);
      assert.match(await page.locator('.message .chat-character').getAttribute('src'), /packs\/done\.png$/); const cardMetrics = await page.locator('.character-pack-option').evaluateAll(rows => rows.map(row => ({ width: row.clientWidth, scrollWidth: row.scrollWidth, imageWidths: [...row.querySelectorAll('.character-pack-icon')].map(image => image.getBoundingClientRect().width) }))); assert.ok(cardMetrics.every(row => row.scrollWidth <= row.width)); assert.ok(cardMetrics.every(row => row.imageWidths.every(width => width >= 32 && width <= 40))); await page.locator('.character-pack-option').first().click(); await page.waitForTimeout(30); assert.equal(await page.locator('.character-pack-option').first().getAttribute('aria-pressed'), 'true'); await page.locator('.character-pack-option').nth(1).click(); await page.waitForTimeout(30); assert.equal(await page.locator('.character-pack-option').nth(1).getAttribute('aria-pressed'), 'true'); await page.locator('#character-pack-refresh').click(); await page.waitForTimeout(30); assert.match(await page.locator('.message .chat-character').getAttribute('src'), /packs\/done-1\.png$/); assert.equal(await page.locator('#character-pack-refresh').evaluate(button => document.activeElement === button), true);
      await page.screenshot({ path: path.join(out, `selected-${width}-${theme}-${language}.png`) });
      await page.evaluate(state => window.mobileCodexEvent('state', { ...state, characters: { folderName: '', folderConfigured: false, selectedPackId: 'builtin', packs: [{ id: 'builtin', name: 'Builtin', valid: true, icons: {} }] } }), base); await page.waitForTimeout(30);
      assert.equal(await page.locator('.character-pack-option').count(), 1); await page.screenshot({ path: path.join(out, `empty-${width}-${theme}-${language}.png`) });
      await page.evaluate(state => window.mobileCodexEvent('state', { ...state, characters: { folderName: 'Broken', folderConfigured: true, selectedPackId: 'builtin', error: 'mapping.json is invalid', packs: [{ id: 'builtin', name: 'Builtin', valid: true, icons: {} }, { id: 'broken', name: 'Broken pack', valid: false, error: 'mapping.json is invalid', icons: {} }] } }), base); await page.waitForTimeout(30);
      assert.match(await page.locator('#character-pack-status').textContent(), /invalid/i); assert.equal(await page.locator('.character-pack-option').nth(1).isDisabled(), true); await page.locator('.character-pack-option').nth(1).scrollIntoViewIfNeeded(); assert.match(await page.locator('.character-pack-option').nth(1).textContent(), /invalid/i); const invalidMetrics = await page.locator('.character-pack-option').nth(1).evaluate(row => ({ width: row.clientWidth, scrollWidth: row.scrollWidth, images: [...row.querySelectorAll('.character-pack-icon')].map(image => { const imageBox = image.getBoundingClientRect(); const previewBox = image.parentElement.getBoundingClientRect(); return imageBox.left >= previewBox.left && imageBox.right <= previewBox.right && imageBox.top >= previewBox.top && imageBox.bottom <= previewBox.bottom; }) })); assert.ok(invalidMetrics.scrollWidth <= invalidMetrics.width); assert.ok(invalidMetrics.images.every(Boolean)); await page.screenshot({ path: path.join(out, `invalid-${width}-${theme}-${language}.png`) });
      if (width === 393) { await page.locator('#chat-icons-toggle').uncheck(); assert.equal(await page.locator('.message .chat-character').count(), 0); await page.screenshot({ path: path.join(out, 'off-393-ko.png') }); }
      results.push({ width, height, theme, language, selected: true, empty: true, invalid: true, off: width === 393 }); await context.close();
    }
    fs.writeFileSync(path.join(out, 'browser-results.json'), JSON.stringify({ timestamp: new Date().toISOString(), results, assertions: 'native characters.list mock; selected mapping served with HTTP 200, empty folder, invalid manifest disabled, icons off' }, null, 2)); console.log(JSON.stringify(results));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
