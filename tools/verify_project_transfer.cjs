const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname,'..'), out = path.join(root,'build/project-transfer-interop');
fs.mkdirSync(out,{recursive:true});
const jdk = process.env.JAVA_HOME, json = process.env.JSON_JAR;
if (!jdk || !json) throw new Error('Set JAVA_HOME (JDK 17+) and JSON_JAR (org.json jar).');
const exe = name => path.join(jdk,'bin',name + (process.platform === 'win32' ? '.exe' : ''));
const cp = [out,json].join(path.delimiter);
function execute(command,args) { const result = spawnSync(command,args,{cwd:root,encoding:'utf8',windowsHide:true,maxBuffer:4*1024*1024}); assert.equal(result.status,0,result.stderr); return result.stdout.trim(); }
const core = 'app/src/main/java/dev/mobilecodex/app/core/';
execute(exe('javac'),['-encoding','UTF-8','-cp',json,'-d',out,...['Json.java','Texts.java','WorkspacePath.java','Utf8Files.java','ProjectRegistry.java','sync/StrictJson.java','sync/PortableProjects.java','sync/ProjectIdentities.java'].map(f=>core+f),'tools/ProjectTransferHarness.java']);
const temp = fs.mkdtempSync(path.join(os.tmpdir(),'project-interop-'));
try {
  const phone = path.join(temp,'phone.json'), desktop = path.join(temp,'desktop'), folder = path.join(temp,'code'); fs.mkdirSync(folder); fs.writeFileSync(path.join(folder,'source.txt'),'unchanged');
  const java = (...args) => JSON.parse(execute(exe('java'),['-Dfile.encoding=UTF-8','-cp',cp,'ProjectTransferHarness',...args]));
  const cli = (...args) => JSON.parse(execute(process.execPath,['packages/desktop-companion/cli.cjs','--home',desktop,...args]));
  const original = java('init',phone)[0], id = original.projectId; cli('init');
  const a = path.join(temp,'a.json'); java('export',phone,a); assert.equal(cli('import',a).projects[0].projectId,id);
  cli('projects','bind',id,folder); cli('projects','rename',id,'Desktop'); java('rename',phone,'Phone');
  const b = path.join(temp,'b.json'); cli('export',b,id); const conflict = java('import',phone,b)[0]; assert.deepEqual(conflict.nameConflicts,['Desktop','Phone']);
  assert.equal(conflict.bindings[0].uri,original.bindings[0].uri); assert.equal(conflict.bindings[0].bindingId,original.bindings[0].bindingId);
  const c = path.join(temp,'c.json'); java('export',phone,c); assert.equal(cli('import',c).conflictCount,1);
  java('rename',phone,'Resolved'); const d = path.join(temp,'d.json'); java('export',phone,d);
  const resolved = cli('import',d); assert.equal(resolved.projects[0].name,'Resolved'); assert.equal(resolved.conflictCount,0); assert.equal(cli('import',d).addedEvents,0);
  assert.equal(resolved.projects[0].bindings[0].localPath,fs.realpathSync(folder)); assert.equal(fs.readFileSync(path.join(folder,'source.txt'),'utf8'),'unchanged');
  console.log('PASS Android Java ↔ desktop CLI: stable identity/bindings, concurrent rename conflict, resolution, replay, source preservation');
} finally {fs.rmSync(temp,{recursive:true,force:true});}
