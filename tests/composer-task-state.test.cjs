const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');

const webRoot = path.join(__dirname, '../app/src/main/assets/web');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(resolve => setTimeout(resolve, 10));

function setup(task = {}) {
  const css = ['app.css', 'visual-system.css'].map(file => fs.readFileSync(path.join(webRoot, file), 'utf8')).join('\n');
  const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8').replace('</head>', '<style>' + css + '</style></head>');
  const dom = new JSDOM(html, {url:'https://appassets.androidplatform.net/index.html', runScripts:'outside-only'});
  opened.push(dom);
  const w = dom.window, calls = [];
  const snapshot = {
    ready:true, busy:false, proBusy:false, permissions:'workspace-write', status:'Connected',
    threadId:'task', cwd:'/test/project', workspace:{selected:true,key:'project',name:'Project',available:true},
    account:{type:'chatgpt',email:'me@example.test'}, models:[], projects:[], sessions:[],
    messages:[{id:'answer',role:'assistant',text:'A message to read while the task continues.'}], ...task
  };
  w.matchMedia = () => ({matches:false, addEventListener(){}});
  w.requestAnimationFrame = callback => w.setTimeout(callback, 0);
  w.HTMLDialogElement.prototype.showModal = function(){this.setAttribute('open', '');};
  w.HTMLDialogElement.prototype.close = function(){this.removeAttribute('open');this.dispatchEvent(new w.Event('close'));};
  w.Native = {
    locale:() => JSON.stringify({choice:'en',systemLanguage:'en'}),
    postMessage(raw) {
      const message = JSON.parse(raw); calls.push(message);
      queueMicrotask(() => w.mobileCodexEvent('response', {
        id:message.id,
        result:message.action === 'state' ? snapshot : message.action === 'rpc' ? {data:[],marketplaces:[]} : {}
      }));
    }
  };
  for (const file of ['translations.js', 'locale.js', 'ui-core.js', 'app.js']) w.eval(fs.readFileSync(path.join(webRoot, file), 'utf8'));
  return {w, calls, snapshot};
}

function assertStopReachable(w) {
  const stop = w.document.getElementById('stop');
  assert.equal(stop.hidden, false, 'The active task exposes its stop button');
  assert.notEqual(w.getComputedStyle(stop).display, 'none', 'Composer CSS must keep Stop visible outside the input');
  assert.equal(stop.disabled, false, 'Stop stays actionable');
}

test('Codex and Pro tasks expose Stop on initial restore without input focus', async () => {
  for (const task of [{busy:true}, {proBusy:true}]) {
    const {w} = setup(task); await tick();
    assert.equal(w.document.activeElement, w.document.body);
    assertStopReachable(w);
  }
});

test('reading a message after leaving the composer keeps Stop visible and routes cancellation', async () => {
  for (const [task, action] of [[{busy:true}, 'chat.stop'], [{proBusy:true}, 'chat.pro.cancel']]) {
    const {w, calls} = setup(task); await tick();
    w.document.getElementById('prompt').focus();
    w.document.querySelector('.msg-copy-btn').focus();
    await tick();
    assertStopReachable(w);
    w.document.getElementById('stop').click(); await tick();
    assert.equal(calls.filter(call => call.action === action).length, 1);
  }
});

test('task transitions use the newest restored conversation state and return idle input to compact', async () => {
  const {w, snapshot} = setup({busy:true}); await tick();
  w.document.querySelector('.msg-copy-btn').focus();
  await tick();
  w.mobileCodexEvent('state', {...snapshot,busy:false,threadId:'idle'});
  assert.equal(w.document.getElementById('stop').hidden, true);
  assert.equal(w.document.getElementById('composer').classList.contains('composer-expanded'), false);
  w.mobileCodexEvent('state', {...snapshot,busy:false,proBusy:true,threadId:'pro-task'});
  assertStopReachable(w);
  w.mobileCodexEvent('state', {...snapshot,busy:false,proBusy:false,threadId:'done'});
  assert.equal(w.document.getElementById('stop').hidden, true);
  assert.equal(w.document.getElementById('composer').classList.contains('composer-expanded'), false);
});
