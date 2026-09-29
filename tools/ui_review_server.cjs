#!/usr/bin/env node
/* Loopback-only UI review server. It exposes packaged web assets, never app data. */
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

const root = path.resolve(__dirname, '..');
const web = path.join(root, 'app/src/main/assets/web');
const fixture = path.join(__dirname, 'ui_review_fixture.js');
const types = new Map([
  ['.css','text/css; charset=utf-8'], ['.js','application/javascript; charset=utf-8'],
  ['.html','text/html; charset=utf-8'], ['.png','image/png'], ['.jpg','image/jpeg'],
  ['.jpeg','image/jpeg'], ['.webp','image/webp'], ['.gif','image/gif'], ['.svg','image/svg+xml']
]);
const csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";
const reviewCsp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";
const reportPath = path.join(root, 'artifacts/ui-review/report.json');

function assetAllowlist(dir = web, prefix = '') {
  const allowed = new Set();
  for (const item of fs.readdirSync(dir, {withFileTypes:true})) {
    const relative = prefix + item.name;
    if (item.isDirectory()) for (const nested of assetAllowlist(path.join(dir, item.name), relative + '/')) allowed.add(nested);
    else if (item.isFile() && types.has(path.extname(item.name).toLowerCase())) allowed.add(relative);
  }
  return allowed;
}
const assets = assetAllowlist();

function send(res, status, body = '', type = 'text/plain; charset=utf-8', method = 'GET', policy = csp) {
  res.writeHead(status, {'Content-Type':type, 'Content-Security-Policy':policy, 'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff'});
  if (method !== 'HEAD') res.end(body); else res.end();
}
function safeAsset(urlPath) {
  let decoded;
  try { decoded = decodeURIComponent(urlPath.replace(/^\/assets\//, '')); } catch { return null; }
  if (!decoded || decoded.includes('\\') || decoded.split('/').some(part => !part || part === '.' || part === '..') || !assets.has(decoded)) return null;
  const file = path.resolve(web, decoded);
  return file.startsWith(web + path.sep) && fs.statSync(file).isFile() ? {file, relative:decoded} : null;
}
function gitAsset(relative) {
  if (!assets.has(relative)) return null;
  try { return execFileSync('git', ['show', 'HEAD:app/src/main/assets/web/' + relative], {cwd:root, encoding:null, stdio:['ignore','pipe','ignore']}); }
  catch { return null; }
}
function inventory() {
  const index = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
  const dialogs = [...index.matchAll(/<dialog\s+id="([^"]+)"/g)].map(match => match[1]);
  const tabs = [...index.matchAll(/data-settings-tab="([^"]+)"/g)].map(match => match[1]);
  const surfaces = ['main','welcome','files','options','tool-menu','composer-expanded','dictation','autocomplete','images','loading','error','sync-pending','sync-error','sync-preview','login', ...tabs.map(tab => 'settings-' + tab), ...dialogs.filter(id => !['options-dialog','tool-menu-dialog','login-dialog','settings-dialog'].includes(id))];
  return {dialogs, tabs:[...new Set(tabs)], surfaces:[...new Set(surfaces)]};
}
function reviewPage() {
  const data = JSON.stringify(inventory()).replace(/</g, '\\u003c');
  return '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="' + reviewCsp + '"><link rel="stylesheet" href="/fixture.css"><title>Mobile Codex UI review</title></head><body data-inventory=\'' + data + '\'><main><h1>Mobile Codex UI review</h1><p>로컬 fixture입니다. 계정, 토큰, 네트워크 요청을 사용하지 않습니다.</p><form id="ui-review-controls"><label>화면 <select name="surface"></select></label><label>테마 <select name="theme"><option value="light">light</option><option value="dark">dark</option></select></label><label>언어 <select name="language"><option value="ko">ko</option><option value="en">en</option></select></label><label>글자 <select name="textSize"><option value="100">100%</option><option value="150">150%</option></select></label><label>화면 크기 <select name="viewport"><option value="320x568">320 × 568</option><option value="393x852" selected>393 × 852</option><option value="800x1100">800 × 1100</option><option value="1280x900">1280 × 900</option></select></label><button id="ui-review-run" type="button">전체 행렬 검사</button></form><pre id="ui-review-report" aria-live="polite">준비됨</pre><iframe id="ui-review-frame" title="Mobile Codex preview"></iframe></main><script src="/fixture.js" defer></script></body></html>';
}
function galleryPage(url) {
  const theme = url.searchParams.get('theme') === 'dark' ? 'dark' : 'light';
  const textSize = url.searchParams.get('textSize') === '150' ? '150' : '100';
  const requestedWidth = Number(url.searchParams.get('width'));
  const width = [320,393,800,1280].includes(requestedWidth) ? requestedWidth : 320;
  const esc = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const all = inventory().surfaces, page = Number(url.searchParams.get('page') || 0);
  const start = Number.isInteger(page) && page >= 0 ? page * 3 : 0;
  const next = new URLSearchParams(url.searchParams); next.set('page', String((page + 1) % Math.ceil(all.length / 3)));
  const cards = all.slice(start, start + 3).map(surface => {
    const src = '/app?surface=' + encodeURIComponent(surface) + '&theme=' + theme + '&language=ko&textSize=' + textSize + '&gallery=1';
    return '<article class="gallery-card"><h2>' + esc(surface) + '</h2><iframe title="' + esc(surface) + ' preview" src="' + src + '" width="' + width + '" height="568"></iframe></article>';
  }).join('');
  return '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="' + reviewCsp + '"><link rel="stylesheet" href="/gallery.css"><title>Mobile Codex UI gallery</title></head><body><header><h1>Mobile Codex UI gallery</h1><p>읽기 전용 fixture · ' + width + ' × 568 · ' + theme + ' · ' + textSize + '% · ' + (page + 1) + '/' + Math.ceil(all.length / 3) + '</p><a href="/gallery?' + esc(next.toString()) + '">다음 화면</a></header><main>' + cards + '</main></body></html>';
}
function injectedApp(before = false) {
  let html = before ? gitAsset('index.html')?.toString('utf8') : fs.readFileSync(path.join(web, 'index.html'), 'utf8');
  if (!html) return null;
  const prefix = before ? '/before-assets/' : '/assets/';
  html = html.replace('<script src="ui-core.js" defer></script>', '<script src="/fixture.js"></script><script src="ui-core.js" defer></script>');
  return html.replace(/\b(href|src)="([^"]+)"/g, (whole, attribute, value) => {
    if (value.startsWith('/') || value.startsWith('#') || /^[a-z][a-z0-9+.-]*:/i.test(value)) return whole;
    return attribute + '="' + prefix + value + '"';
  });
}
const reviewCss = `:root{color-scheme:light dark;font:15px system-ui,sans-serif}body{margin:0;background:#f5f5f5;color:#171717}main{max-width:1320px;margin:auto;padding:20px}h1{font-size:20px;margin:0 0 6px}p{margin:0 0 14px;color:#555}form{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:14px}label{display:grid;gap:4px;font-size:12px;font-weight:600}select,button{min-height:36px;padding:4px 8px}#ui-review-report{display:block;max-height:180px;overflow:auto;white-space:pre-wrap;margin:0 0 14px;padding:10px;background:#fff;border:1px solid #bbb;font-size:12px}iframe{display:block;width:393px;height:852px;min-height:420px;border:1px solid #bbb;background:#fff;transition:width .15s}`;
const galleryCss = `:root{color-scheme:light dark;font:15px system-ui,sans-serif}body{margin:0;background:#f5f5f5;color:#171717}header{max-width:1320px;margin:auto;padding:20px 20px 0}h1{font-size:20px;margin:0 0 6px}p{margin:0;color:#555}main{display:grid;grid-template-columns:repeat(3,minmax(320px,1fr));gap:18px;align-items:start;padding:20px;overflow:auto}.gallery-card{width:max-content;max-width:100%;padding:10px;background:#fff;border:1px solid #bbb}.gallery-card h2{font-size:14px;margin:0 0 8px}.gallery-card iframe{display:block;border:1px solid #bbb;background:#fff}@media(max-width:1100px){main{grid-template-columns:repeat(2,minmax(320px,1fr))}}@media(max-width:720px){main{grid-template-columns:1fr}.gallery-card{overflow:auto}}`;

function createReviewServer() {
  return http.createServer((req, res) => {
    const method = req.method || 'GET';
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (method === 'POST' && url.pathname === '/report') {
      const remote = req.socket.remoteAddress || '', origin = req.headers.origin || '';
      if (!['127.0.0.1','::1','::ffff:127.0.0.1'].includes(remote) || req.headers['x-ui-review-fixture'] !== '1' || !/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)) return send(res, 403, 'Forbidden', 'text/plain; charset=utf-8', method, reviewCsp);
      let size = 0, body = '';
      req.setEncoding('utf8'); req.on('data', chunk => { size += Buffer.byteLength(chunk); if (size <= 524288) body += chunk; });
      req.on('end', () => {
        if (size > 524288) return send(res, 413, 'Report too large', 'text/plain; charset=utf-8', method, reviewCsp);
        try {
          const report = JSON.parse(body);
          if (!report || typeof report !== 'object' || !Array.isArray(report.results) || report.results.length > 1000) throw Error('Invalid report');
          fs.mkdirSync(path.dirname(reportPath), {recursive:true}); fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
          send(res, 201, JSON.stringify({saved:true,results:report.results.length}), 'application/json; charset=utf-8', method, reviewCsp);
        } catch { send(res, 400, 'Invalid report', 'text/plain; charset=utf-8', method, reviewCsp); }
      });
      return;
    }
    if (!['GET','HEAD'].includes(method)) return send(res, 405, 'Method not allowed', 'text/plain; charset=utf-8', method);
    if (url.pathname === '/' || url.pathname === '/review') return send(res, 200, reviewPage(), 'text/html; charset=utf-8', method, reviewCsp);
    if (url.pathname === '/gallery') return send(res, 200, galleryPage(url), 'text/html; charset=utf-8', method, reviewCsp);
    if (url.pathname === '/inventory') return send(res, 200, JSON.stringify(inventory()), 'application/json; charset=utf-8', method);
    if (url.pathname === '/report') return fs.existsSync(reportPath) ? send(res, 200, fs.readFileSync(reportPath), 'application/json; charset=utf-8', method, reviewCsp) : send(res, 404, 'No report', 'text/plain; charset=utf-8', method, reviewCsp);
    if (url.pathname === '/fixture.js') return send(res, 200, fs.readFileSync(fixture), 'application/javascript; charset=utf-8', method);
    if (url.pathname === '/fixture.css') return send(res, 200, reviewCss, 'text/css; charset=utf-8', method);
    if (url.pathname === '/gallery.css') return send(res, 200, galleryCss, 'text/css; charset=utf-8', method);
    if (url.pathname === '/app' || url.pathname === '/before') {
      const html = injectedApp(url.pathname === '/before');
      return html ? send(res, 200, html, 'text/html; charset=utf-8', method) : send(res, 404, 'No prior revision available', 'text/plain; charset=utf-8', method);
    }
    if (url.pathname.startsWith('/before-assets/')) {
      const relativePath = '/assets/' + url.pathname.slice('/before-assets/'.length), asset = safeAsset(relativePath), body = asset && gitAsset(asset.relative);
      return body ? send(res, 200, body, types.get(path.extname(asset.file).toLowerCase()) || 'application/octet-stream', method) : send(res, 404, 'Not found', 'text/plain; charset=utf-8', method);
    }
    if (!url.pathname.startsWith('/assets/')) return send(res, 404, 'Not found', 'text/plain; charset=utf-8', method);
    const asset = safeAsset(url.pathname); if (!asset) return send(res, 404, 'Not found', 'text/plain; charset=utf-8', method);
    return send(res, 200, fs.readFileSync(asset.file), types.get(path.extname(asset.file).toLowerCase()) || 'application/octet-stream', method);
  });
}
function listen(port = Number(process.env.UI_REVIEW_PORT || 8765)) {
  const server = createReviewServer();
  server.listen(port, '127.0.0.1', () => console.log('UI review: http://127.0.0.1:' + server.address().port + '/review'));
  return server;
}
if (require.main === module) listen();
module.exports = {createReviewServer, inventory, safeAsset, web};
