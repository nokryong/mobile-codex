const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), {spawnSync} = require('node:child_process');
const p = require('../packages/desktop-companion/projects.cjs');
const cli = path.resolve(__dirname,'../packages/desktop-companion/cli.cjs');
function temp(t) {const folder = fs.mkdtempSync(path.join(os.tmpdir(),'mobile-projects-')); t.after(() => fs.rmSync(folder,{recursive:true,force:true})); return folder;}
function command(home,...args) {
  const result = spawnSync(process.execPath,[cli,'--home',home,...args],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,result.stderr); return JSON.parse(result.stdout);
}
function rejected(home,...args) { const result = spawnSync(process.execPath,[cli,'--home',home,...args],{encoding:'utf8',windowsHide:true}); assert.notEqual(result.status,0); return result.stderr; }
function base() {return p.add(p.empty(),'created','proj_a','Original','device_phone');}
test('project events converge without dropping concurrent names', () => {
  const original = base(), a = p.add(original,'renamed','proj_a','Phone','device_phone'), b = p.add(original,'renamed','proj_a','Desktop','device_desktop');
  const both = p.union(a,b); assert.deepEqual(both,p.union(b,a)); assert.deepEqual(both,p.union(both,a));
  assert.deepEqual(p.reduce(both).projects[0].nameConflicts,['Desktop','Phone']);
  const resolved = p.add(both,'renamed','proj_a','Chosen','device_phone');
  assert.equal(p.reduce(p.union(resolved,b)).conflictCount,0); assert.equal(p.reduce(resolved).projects[0].name,'Chosen');
});
test('shared fixture and emoji use the same portable protocol', () => {
  const fixture = p.parse(fs.readFileSync(path.join(__dirname,'fixtures/sync/projects-v1.json'),'utf8'));
  assert.deepEqual(p.reduce(fixture).projects,[{projectId:'proj_a',name:'Desktop name',nameConflicts:['Desktop name','모바일 코덱스']}]);
  assert.equal(p.reduce(p.add(fixture,'renamed','proj_a','Code 🧪','device_phone')).projects[0].name,'Code 🧪');
});
test('malformed, forged, secret-bearing and oversized bundles are rejected', () => {
  const raw = JSON.stringify(base());
  for (const bad of [raw.replace('"schemaVersion":1','"schemaVersion":1,"schemaVersion":1'),raw.replace('"schemaVersion":1','"schemaVersion":1,"schema\\u0056ersion":1'),raw + 'x',"{'format':1}",raw.slice(0,-1)+',}', ' '.repeat(p.MAX_BYTES + 1),'['.repeat(20)+'0'+']'.repeat(20)]) assert.throws(() => p.parse(bad));
  for (const field of ['auth','localPath','messages','cwd']) assert.throws(() => p.read({...base(),[field]:'private'},true));
  const secret = p.add(p.empty(),'created','proj_secret','password=private','device_a'); assert.throws(() => p.select(secret,['proj_secret']));
  const forged = structuredClone(base()); forged.events[0].localPath = '/secret'; assert.throws(() => p.read(forged,true));
  const duplicate = base(); duplicate.events.push(duplicate.events[0]); assert.throws(() => p.read(duplicate));
  const original = base(), changed = structuredClone(original); changed.events[0].name = 'Forged'; assert.throws(() => p.union(original,p.read(changed)));
  const cycle = base(); cycle.events.push({id:'evt_cycle',kind:'renamed',projectId:'proj_a',deviceId:'device_x',parents:['evt_cycle'],name:'Bad'}); assert.throws(() => p.read(cycle));
});
test('real CLI round trip preserves local paths, source bytes and identity', t => {
  const root = temp(t), a = path.join(root,'phone-like'), b = path.join(root,'desktop'), folderA = path.join(root,'source-a'), folderB = path.join(root,'source-b');
  fs.mkdirSync(folderA); fs.mkdirSync(folderB); fs.writeFileSync(path.join(folderA,'source.txt'),'A untouched'); fs.writeFileSync(path.join(folderB,'source.txt'),'B untouched');
  const init = command(a,'init'); assert.equal(command(a,'init').deviceId,init.deviceId); command(b,'init');
  const id = command(a,'projects','add','Mobile Codex').projects[0].projectId;
  command(a,'projects','bind',id,folderA); const out = path.join(root,'a.json'); command(a,'export',out,id);
  const raw = fs.readFileSync(out,'utf8'); assert.doesNotMatch(raw,/localPath|bindingId|source-a|auth|messages/);
  const state = path.join(b,'projects.json'), before = fs.readFileSync(state,'utf8');
  assert.equal(command(b,'import',out,'--dry-run').projects[0].projectId,id); assert.equal(fs.readFileSync(state,'utf8'),before);
  assert.equal(command(b,'import',out).projects[0].bindings.length,0); assert.equal(command(b,'import',out).addedEvents,0);
  command(b,'projects','bind',id,folderB); command(b,'projects','rename',id,'Shared name'); const back = path.join(root,'b.json'); command(b,'export',back,id);
  const result = command(a,'import',back); assert.equal(result.projects[0].name,'Shared name'); assert.equal(result.projects[0].bindings[0].localPath,fs.realpathSync(folderA));
  assert.equal(command(b,'status').projects[0].bindings[0].localPath,fs.realpathSync(folderB));
  assert.equal(fs.readFileSync(path.join(folderA,'source.txt'),'utf8'),'A untouched'); assert.equal(fs.readFileSync(path.join(folderB,'source.txt'),'utf8'),'B untouched');
  assert.equal(command(a,'status').deviceId,init.deviceId);
});
test('same names remain separate until an explicit merge; repeat import keeps bindings', t => {
  const root = temp(t), home = path.join(root,'state'); command(home,'init');
  const a = command(home,'projects','add','same').projects[0].projectId;
  const b = command(home,'projects','add','same').projects.find(x => x.projectId !== a).projectId;
  const folder = path.join(root,'code'); fs.mkdirSync(folder); command(home,'projects','bind',a,folder);
  assert.match(rejected(home,'projects','bind',b,folder),/already bound/);
  assert.equal(command(home,'projects','list').projects.length,2);
  const merged = command(home,'projects','merge',a,b); assert.equal(merged.projects.length,1); assert.equal(merged.projects[0].bindings.length,1);
  assert.equal(command(home,'projects','merge',a,b).eventCount,merged.eventCount);
});
test('failure, lock contention, invalid UTF-8 and duplicate export do not overwrite state', t => {
  const root = temp(t), home = path.join(root,'state'); command(home,'init'); const id = command(home,'projects','add','Test').projects[0].projectId;
  const file = path.join(root,'bad.json'), state = path.join(home,'projects.json'), before = fs.readFileSync(state,'utf8');
  fs.writeFileSync(file,JSON.stringify({...p.empty(),auth:'not-importable'})); rejected(home,'import',file); assert.equal(fs.readFileSync(state,'utf8'),before);
  fs.writeFileSync(file,Buffer.from([0xc0,0xaf])); rejected(home,'import',file); assert.equal(fs.readFileSync(state,'utf8'),before);
  const lock = path.join(home,'.writer-lock'); fs.mkdirSync(lock); assert.match(rejected(home,'projects','add','Lost'),/writer lock/); fs.rmdirSync(lock); assert.equal(fs.readFileSync(state,'utf8'),before);
  rejected(home,'export',state,id); assert.equal(fs.readFileSync(state,'utf8'),before);
  const exported = path.join(root,'out.json'); command(home,'export',exported,id); const bytes = fs.readFileSync(exported,'utf8'); rejected(home,'export',exported,id); assert.equal(fs.readFileSync(exported,'utf8'),bytes);
});
