const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const root = 'app/src/main/assets/web/';
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(resolve => setTimeout(resolve, 15));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise, resolve}; };
function setup(overrides = {}) {
  const dom = new JSDOM(fs.readFileSync(root + 'index.html', 'utf8'), {url:'https://appassets.androidplatform.net/index.html', runScripts:'outside-only'});
  opened.push(dom); const w = dom.window, calls = [];
  w.matchMedia = () => ({matches:false, addEventListener(){}});
  w.HTMLDialogElement.prototype.showModal = function() { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function() { this.removeAttribute('open'); this.dispatchEvent(new w.Event('close')); };
  w.confirm = () => true;
  const snapshot = {ready:true, busy:false, permissions:'workspace-write', models:[], messages:[], sessions:[], account:{type:'chatgpt', email:'me@example.test'}, workspace:{selected:true, key:'A', name:'Project A', available:true}, cwd:'/A', threadId:'A-thread', status:'connected'};
  w.Native = {locale:() => JSON.stringify({choice:'ko', systemLanguage:'ko'}), textSize:() => JSON.stringify({percent:100}), postMessage(raw) {
    const message = JSON.parse(raw); calls.push(message);
    queueMicrotask(async () => {
      try {
        const result = overrides[message.action] ? await overrides[message.action](message) : message.action === 'state' ? snapshot : message.action === 'rpc' ? {data:[], marketplaces:[]} : {};
        w.mobileCodexEvent('response', {id:message.id, result});
      } catch (error) { w.mobileCodexEvent('response', {id:message.id, error:error.message}); }
    });
  }};
  for (const name of ['translations.js', 'locale.js', 'ui-core.js', 'app.js']) w.eval(fs.readFileSync(root + name, 'utf8'));
  return {w, d:w.document, calls, snapshot, switchTo(key, extra = {}) { w.mobileCodexEvent('state', {...snapshot, workspace:{selected:true, key, name:'Project ' + key, available:true}, cwd:'/' + key, threadId:key + '-thread', ...extra}); }};
}
async function showFile(ui, path = 'test.md') {
  ui.d.getElementById('files-toggle').click(); await tick();
  [...ui.d.querySelectorAll('.file-row')].find(row => row.textContent.includes(path)).click(); await tick();
}
for (const operation of ['rename', 'move']) test(`editor ${operation} preserves unsaved text and its original version for the next save`, async () => {
  let path = 'test.md', content = 'disk text', sha256 = 'original-hash';
  const ui = setup({
    'files.list':() => ({entries:[{name:path, path, size:9}]}),
    'files.read':() => ({path, content, sha256}),
    'files.mutate':message => {
      const args = message.args.arguments;
      if (message.args.operation === 'mobile_write') { content = args.content; sha256 = 'saved-hash'; }
      else path = operation === 'rename' ? args.name : args.destination + '/' + path;
      return {path};
    }
  });
  await tick(); await showFile(ui); const editor = ui.d.getElementById('editor'); editor.value = 'unsaved changes';
  ui.d.getElementById('editor-' + operation).click(); await tick();
  ui.d.getElementById('input-value').value = operation === 'rename' ? 'renamed.md' : 'src';
  ui.d.getElementById('input-confirm').click(); await tick(); await tick();
  assert.equal(editor.value, 'unsaved changes'); assert.equal(content, 'disk text');
  assert.equal(ui.d.getElementById('editor-title').textContent, path);
  assert.equal(ui.calls.filter(call => call.action === 'files.read').length, 1, 'relocation must not reload the buffer');
  ui.w.confirm = () => false; ui.w.mobileCodexBack(); assert.equal(ui.d.getElementById('editor-dialog').open, true, 'dirty-close guard survives relocation');
  ui.d.getElementById('editor-save').click(); await tick(); await tick();
  assert.deepEqual(ui.calls.filter(call => call.action === 'files.mutate').at(-1).args, {operation:'mobile_write', arguments:{path, expectedSha256:'original-hash', content:'unsaved changes'}, workspaceKey:'A'});
});
test('a completed rename updates the editor even when refreshing the file panel fails', async () => {
  let renamed = false; const ui = setup({
    'files.list':() => { if (renamed) throw new Error('refresh failed'); return {entries:[{name:'test.md', path:'test.md', size:1}]}; },
    'files.read':() => ({path:'test.md', content:'old', sha256:'hash'}),
    'files.mutate':() => { renamed = true; return {path:'new.md'}; }
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'unsaved text';
  ui.d.getElementById('editor-rename').click(); await tick(); ui.d.getElementById('input-value').value = 'new.md';
  ui.d.getElementById('input-confirm').click(); await tick(); await tick();
  assert.equal(ui.d.getElementById('editor-title').textContent, 'new.md');
  assert.equal(ui.d.getElementById('editor').value, 'unsaved text');
});
test('a successful rename keeps its draft at the destination when a panel refresh overlaps a project switch', async () => {
  const refreshing = deferred(); let path = 'test.md', afterRenameLists = 0;
  const ui = setup({
    'files.list':message => {
      if (message.args.workspaceKey === 'B') return {entries:[]};
      if (path === 'renamed.md' && ++afterRenameLists === 1) return refreshing.promise;
      return {entries:[{name:path, path, size:1}]};
    },
    'files.read':() => ({path, content:'disk text', sha256:'disk-hash'}),
    'files.mutate':() => { path = 'renamed.md'; return {path}; }
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'unsaved relocated draft';
  ui.d.getElementById('editor-rename').click(); await tick(); ui.d.getElementById('input-value').value = 'renamed.md';
  ui.d.getElementById('input-confirm').click(); await tick();
  assert.equal(ui.d.getElementById('editor-title').textContent, 'renamed.md');
  ui.switchTo('B'); await tick(); refreshing.resolve({entries:[{name:path, path, size:1}]}); await tick();
  assert.equal(ui.d.getElementById('editor-dialog').open, false);
  ui.switchTo('A'); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  assert.equal(ui.d.getElementById('editor-title').textContent, 'renamed.md');
  assert.equal(ui.d.getElementById('editor').value, 'unsaved relocated draft');
});
test('a rename response arriving after a project switch migrates only the original workspace draft', async () => {
  const renaming = deferred(); let path = 'test.md'; const ui = setup({
    'files.list':message => ({entries:message.args.workspaceKey === 'A' ? [{name:path, path, size:1}] : []}),
    'files.read':() => ({path, content:'disk text', sha256:'disk-hash'}), 'files.mutate':() => renaming.promise
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'late relocation draft';
  ui.d.getElementById('editor-rename').click(); await tick(); ui.d.getElementById('input-value').value = 'provider-normalized.md';
  ui.d.getElementById('input-confirm').click(); await tick(); ui.switchTo('B'); await tick();
  path = 'actual-provider-name.md'; renaming.resolve({path}); await tick();
  assert.equal(ui.d.getElementById('editor-dialog').open, false);
  ui.switchTo('A'); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  assert.equal(ui.d.getElementById('editor-title').textContent, 'actual-provider-name.md');
  assert.equal(ui.d.getElementById('editor').value, 'late relocation draft');
});
test('renaming from the file panel also moves a retained unsaved editor draft', async () => {
  let path = 'test.md'; const ui = setup({
    'files.list':message => ({entries:message.args.workspaceKey === 'A' ? [{name:path, path, size:1}] : []}),
    'files.read':() => ({path, content:'disk text', sha256:'disk-hash'}),
    'files.mutate':() => { path = 'panel-renamed.md'; return {path}; }
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'draft retained by transition';
  ui.switchTo('B'); await tick(); ui.switchTo('A'); await tick();
  ui.d.querySelector('.file-entry .icon-button').click(); await tick();
  ui.d.querySelector('#file-actions button').click(); await tick(); ui.d.getElementById('input-value').value = 'panel-renamed.md';
  ui.d.getElementById('input-confirm').click(); await tick(); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  assert.equal(ui.d.getElementById('editor-title').textContent, 'panel-renamed.md');
  assert.equal(ui.d.getElementById('editor').value, 'draft retained by transition');
});
for (const operation of ['rename', 'move']) test(`directory ${operation} migrates nested drafts without touching sibling prefixes or another workspace`, async () => {
  let directory = 'src';
  const entry = (name, path, isDirectory = true) => ({name, path, directory:isDirectory, size:isDirectory ? 0 : 1});
  const ui = setup({
    'files.list':message => {
      const scope = message.args.workspaceKey, path = message.args.path, source = scope === 'A' ? directory : 'src';
      if (!path) return {entries:[...(source.includes('/') ? [] : [entry(source, source)]), entry('src-extra', 'src-extra'), entry('out', 'out')]};
      if (path === 'out') return {entries:source.startsWith('out/') ? [entry('src', source)] : []};
      if (path === source) return {entries:[entry('nested', source + '/nested')]};
      if (path === source + '/nested' || path === 'src-extra') return {entries:[entry('notes.md', path + '/notes.md', false)]};
      return {entries:[]};
    },
    'files.read':message => ({path:message.args.path, content:'disk text', sha256:message.args.workspaceKey + '-original-hash'}),
    'files.mutate':() => { directory = operation === 'rename' ? 'lib' : 'out/src'; return {path:directory}; }
  });
  async function visit(names) {
    for (const name of names) {
      const row = [...ui.d.querySelectorAll('.file-row')].find(button => button.querySelector('span')?.textContent === name);
      assert.ok(row, 'folder/file ' + name + ' must be available'); row.click(); await tick();
    }
  }
  await tick(); ui.d.getElementById('files-toggle').click(); await tick(); await visit(['src', 'nested', 'notes.md']);
  ui.d.getElementById('editor').value = 'A nested draft'; ui.switchTo('B'); await tick(); await visit(['src', 'nested', 'notes.md']);
  ui.d.getElementById('editor').value = 'B independent draft'; ui.switchTo('A'); await tick(); await visit(['src-extra', 'notes.md']);
  ui.d.getElementById('editor').value = 'A sibling draft'; ui.switchTo('B'); await tick(); ui.switchTo('A'); await tick();
  const source = [...ui.d.querySelectorAll('.file-entry')].find(row => row.querySelector('.file-row span')?.textContent === 'src');
  source.querySelector('.icon-button').click(); await tick();
  ui.d.querySelectorAll('#file-actions button')[operation === 'rename' ? 0 : 1].click(); await tick();
  ui.d.getElementById('input-value').value = operation === 'rename' ? 'lib' : 'out';
  ui.d.getElementById('input-confirm').click(); await tick(); await tick(); await visit([...directory.split('/'), 'nested', 'notes.md']);
  assert.equal(ui.d.getElementById('editor').value, 'A nested draft');
  assert.equal(ui.d.getElementById('editor-title').textContent, directory + '/nested/notes.md');
  ui.switchTo('B'); await tick(); await visit(['src', 'nested', 'notes.md']);
  assert.equal(ui.d.getElementById('editor').value, 'B independent draft');
  ui.switchTo('A'); await tick(); await visit(['src-extra', 'notes.md']);
  assert.equal(ui.d.getElementById('editor').value, 'A sibling draft');
});
test('an old listing cannot replace the new project after a deferred response', async () => {
  const old = deferred(); const ui = setup({'files.list':message => message.args.workspaceKey === 'A' ? old.promise : {entries:[{name:'B.md', path:'B.md', size:1}]}});
  await tick(); ui.d.getElementById('files-toggle').click(); await tick(); ui.switchTo('B'); await tick();
  old.resolve({entries:[{name:'A.md', path:'A.md', size:1}]}); await tick();
  assert.match(ui.d.getElementById('file-list').textContent, /B\.md/); assert.doesNotMatch(ui.d.getElementById('file-list').textContent, /A\.md/);
  assert.equal(ui.d.getElementById('breadcrumbs').textContent, 'Project B');
  assert.deepEqual(ui.calls.filter(call => call.action === 'files.list').map(call => call.args), [{workspaceKey:'A', path:''}, {workspaceKey:'B', path:''}]);
});
test('a project change resets the directory and cancels the old search debounce', async () => {
  const ui = setup({'files.list':message => ({entries:message.args.workspaceKey === 'A' && !message.args.path ? [{directory:true, name:'src', path:'src'}] : []})});
  await tick(); ui.d.getElementById('files-toggle').click(); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  assert.equal(ui.d.getElementById('breadcrumbs').textContent, 'Project A / src');
  const query = ui.d.getElementById('file-query'); query.value = 'old-project-query'; query.dispatchEvent(new ui.w.Event('input')); await tick();
  ui.switchTo('B'); await tick(); await new Promise(resolve => setTimeout(resolve, 320));
  assert.equal(query.value, ''); assert.equal(ui.d.getElementById('file-up').disabled, true);
  assert.deepEqual(ui.calls.filter(call => call.action === 'files.list').at(-1).args, {workspaceKey:'B', path:''});
  assert.equal(ui.calls.some(call => call.action === 'files.search'), false);
});
test('a stale read cannot open an editor or replace an already opened file in the new project', async () => {
  const old = deferred(); const ui = setup({
    'files.list':() => ({entries:[{name:'test.md', path:'test.md', size:1}]}),
    'files.read':message => message.args.workspaceKey === 'A' ? old.promise : {path:'test.md', content:'B content', sha256:'B-hash'}
  });
  await tick(); await showFile(ui); ui.switchTo('B'); await tick();
  assert.equal(ui.d.getElementById('editor-dialog').open, false);
  ui.d.querySelector('.file-row').click(); await tick(); old.resolve({path:'test.md', content:'A content', sha256:'A-hash'}); await tick();
  assert.equal(ui.d.getElementById('editor').value, 'B content');
  ui.d.getElementById('editor').value = 'B edit'; ui.d.getElementById('editor-save').click(); await tick();
  assert.deepEqual(ui.calls.find(call => call.action === 'files.mutate').args, {operation:'mobile_write', arguments:{path:'test.md', content:'B edit', expectedSha256:'B-hash'}, workspaceKey:'B'});
});
test('a stale image read does not open an image viewer in a different project', async () => {
  const old = deferred(); const ui = setup({'files.list':() => ({entries:[{name:'photo.png', path:'photo.png', size:1}]}), 'images.read':() => old.promise});
  await tick(); await showFile(ui, 'photo.png'); ui.switchTo('B'); await tick();
  old.resolve({id:'a'.repeat(64), url:'/images/' + 'a'.repeat(64), name:'photo.png'}); await tick();
  assert.equal(ui.d.getElementById('image-dialog').open, false);
  assert.equal(ui.calls.find(call => call.action === 'images.read').args.workspaceKey, 'A');
});
test('a workspace switch cancels old file creation input and prevents stale-row mutations', async () => {
  const ui = setup({'files.list':() => ({entries:[{name:'same.md', path:'same.md', size:1}]})});
  await tick(); ui.d.getElementById('files-toggle').click(); await tick();
  const staleMenu = ui.d.querySelector('.file-entry .icon-button');
  ui.d.getElementById('file-new').click(); await tick(); ui.d.getElementById('input-value').value = 'wrong.md';
  ui.switchTo('B'); await tick(); assert.equal(ui.d.getElementById('input-dialog').open, false);
  ui.d.getElementById('input-confirm').click(); staleMenu.click(); await tick();
  assert.equal(ui.calls.some(call => call.action === 'files.mutate'), false);
  assert.equal(ui.d.getElementById('file-actions-dialog').open, false);
});
test('unsaved edits survive a workspace round-trip without bypassing the original expected hash', async () => {
  let currentHash = 'initial-hash'; const ui = setup({
    'files.list':() => ({entries:[{name:'test.md', path:'test.md', size:1}]}),
    'files.read':() => ({path:'test.md', content:currentHash === 'initial-hash' ? 'old' : 'external edit', sha256:currentHash})
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'my unsaved edit'; ui.switchTo('B'); await tick();
  currentHash = 'external-hash'; ui.switchTo('A'); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  assert.equal(ui.d.getElementById('editor').value, 'my unsaved edit');
  ui.d.getElementById('editor-save').click(); await tick();
  assert.equal(ui.calls.find(call => call.action === 'files.mutate').args.arguments.expectedSha256, 'initial-hash');
});
test('a save completing after a workspace switch does not read or reopen the old file in the new workspace', async () => {
  const saving = deferred(); const ui = setup({
    'files.list':() => ({entries:[{name:'test.md', path:'test.md', size:1}]}),
    'files.read':() => ({path:'test.md', content:'old', sha256:'old-hash'}), 'files.mutate':() => saving.promise
  });
  await tick(); await showFile(ui); ui.d.getElementById('editor').value = 'edit'; ui.d.getElementById('editor-save').click(); await tick();
  ui.switchTo('B'); await tick(); saving.resolve({path:'test.md'}); await tick();
  assert.equal(ui.d.getElementById('editor-dialog').open, false);
  assert.equal(ui.calls.filter(call => call.action === 'files.read').length, 1);
  assert.equal(ui.calls.find(call => call.action === 'files.mutate').args.workspaceKey, 'A');
});
test('changing conversations in the same workspace retains directory and query', async () => {
  const ui = setup({'files.list':message => ({entries:!message.args.path ? [{directory:true, name:'src', path:'src'}] : []})});
  await tick(); ui.d.getElementById('files-toggle').click(); await tick(); ui.d.querySelector('.file-row').click(); await tick();
  ui.d.getElementById('file-query').value = 'retained query'; const count = ui.calls.filter(call => call.action === 'files.list').length;
  ui.w.mobileCodexEvent('state', {...ui.snapshot, threadId:'another-A-thread'}); await tick();
  assert.equal(ui.d.getElementById('file-query').value, 'retained query');
  assert.equal(ui.d.getElementById('breadcrumbs').textContent, 'Project A / src');
  assert.equal(ui.calls.filter(call => call.action === 'files.list').length, count);
});
test('losing and restoring folder access invalidates listings and resets navigation', async () => {
  const old = deferred(); let listing = 0; const ui = setup({'files.list':() => ++listing === 1 ? old.promise : {entries:[{name:'fresh.md', path:'fresh.md', size:1}]}});
  await tick(); ui.d.getElementById('files-toggle').click(); await tick(); ui.d.getElementById('file-query').value = 'old query';
  ui.w.mobileCodexEvent('state', {...ui.snapshot, workspace:{...ui.snapshot.workspace, available:false}}); await tick();
  old.resolve({entries:[{name:'stale.md', path:'stale.md', size:1}]}); await tick();
  assert.doesNotMatch(ui.d.getElementById('file-list').textContent, /stale\.md/);
  assert.equal(ui.d.getElementById('file-query').value, '');
  ui.w.mobileCodexEvent('state', ui.snapshot); await tick();
  assert.match(ui.d.getElementById('file-list').textContent, /fresh\.md/);
  assert.equal(ui.d.getElementById('breadcrumbs').textContent, 'Project A');
});
