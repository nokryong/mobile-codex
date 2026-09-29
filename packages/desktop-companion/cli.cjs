#!/usr/bin/env node
'use strict';
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const p = require('./projects.cjs');
const github = require('./github-sync.cjs');
const HELP = `Mobile Codex desktop companion (Node.js 20+)
  node packages/desktop-companion/cli.cjs [--home <directory>] <command>
  init
  status
  projects list
  projects add <name>
  projects bind <projectId> <existing-folder>
  projects rename <projectId> <name>
  projects merge <sourceId> <targetId>
  export <new-json-file> <projectId> [projectId...]
  import <json-file> [--dry-run]
  sync connect <owner/repository>
  sync status
  sync push <projectId> [projectId...]
  sync pull [--dry-run]
Default state: ~/.mobile-codex/projects.json
Exchanges project identities, names, and merge history. No conversations or source files.
`;
const error = message => {throw new Error(message);};
function readFile(file, limit) {
  const fd = fs.openSync(file,'r');
  try {
    if (!fs.fstatSync(fd).isFile() || fs.fstatSync(fd).size > limit) error('Invalid or oversized file');
    const buffer = Buffer.alloc(limit + 1); let total = 0, n;
    while ((n = fs.readSync(fd,buffer,total,buffer.length - total,null)) > 0) {
      total += n; if (total > limit) error('File exceeds size limit');
    }
    return new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(buffer.subarray(0,total));
  } finally {fs.closeSync(fd);}
}
function validateState(state) {
  if (Buffer.byteLength(JSON.stringify(state), 'utf8') > 2*p.MAX_BYTES) error('Local project state is full');
  p.fields(state,['schemaVersion','deviceId','history','bindings']);
  if (state.schemaVersion !== 1 || !Array.isArray(state.bindings)) error('Invalid local state');
  p.identifier(state.deviceId,'device'); state.history = p.read(state.history);
  const {canonical} = p.reduce(state.history), paths = new Set(), ids = new Set();
  for (const b of state.bindings) {
    p.fields(b,['bindingId','projectId','localPath']); p.identifier(b.bindingId,'binding'); canonical(b.projectId);
    if (typeof b.localPath !== 'string' || !path.isAbsolute(b.localPath) || paths.has(b.localPath) || ids.has(b.bindingId)) error('Invalid local binding');
    paths.add(b.localPath); ids.add(b.bindingId);
  }
  return state;
}
function summary(state) {
  const model = p.reduce(state.history);
  return {deviceId:state.deviceId,eventCount:model.eventCount,linkCount:model.linkCount,conflictCount:model.conflictCount,
    projects:model.projects.map(project => ({...project,bindings:state.bindings.filter(b => model.canonical(b.projectId) === project.projectId)}))};
}
function atomicJson(stateFile,state) {
  const temporary = stateFile + '.' + p.newId('tmp'); let fd;
  try {
    fd = fs.openSync(temporary,'wx',0o600); fs.writeFileSync(fd,JSON.stringify(state)); fs.fsyncSync(fd); fs.closeSync(fd); fd = undefined;
    fs.renameSync(temporary,stateFile);
  } finally {if (fd !== undefined) fs.closeSync(fd); if (fs.existsSync(temporary)) fs.unlinkSync(temporary);}
}
function store(stateFile,state) {validateState(state); atomicJson(stateFile,state);}
function run(argv) {
  if (!argv.length || argv.includes('--help') || argv.includes('-h')) return HELP;
  let home = path.join(os.homedir(),'.mobile-codex');
  if (argv[0] === '--home') {if (!argv[1]) error('Missing home directory'); home = path.resolve(argv[1]); argv = argv.slice(2);}
  const [cmd,...args] = argv;
  const stateFile = path.join(home,'projects.json'), lock = path.join(home,'.writer-lock');
  if (cmd === 'init') {
    if (args.length) error('init takes no arguments');
    fs.mkdirSync(home,{recursive:true,mode:0o700});
  }
  if (!fs.existsSync(home)) error('Run init first');
  if (fs.lstatSync(home).isSymbolicLink() || !fs.statSync(home).isDirectory()) error('State directory must be a real directory');
  if (fs.existsSync(stateFile) && fs.lstatSync(stateFile).isSymbolicLink()) error('State file must not be a symbolic link');
  // Even read/preview commands take the lock to see one coherent snapshot.
  let locked = false;
  try {
    try {fs.mkdirSync(lock); locked = true;} catch (e) {if (e.code === 'EEXIST') error('Another companion command holds the writer lock'); throw e;}
    if (cmd === 'init') {
      if (!fs.existsSync(stateFile)) store(stateFile,{schemaVersion:1,deviceId:p.newId('device'),history:p.empty(),bindings:[]});
      return summary(validateState(JSON.parse(readFile(stateFile,2*p.MAX_BYTES))));
    }
    const state = validateState(JSON.parse(readFile(stateFile,2*p.MAX_BYTES)));
    if (cmd === 'sync') {
      const configFile = path.join(home,'sync.json'), remote = github.client();
      if (fs.existsSync(configFile) && fs.lstatSync(configFile).isSymbolicLink()) error('Sync config must not be a symbolic link');
      if (args[0] === 'connect' && args.length === 2) {
        const config = remote.connect(args[1]); atomicJson(configFile,config);
        return {repository:config.repository,url:'https://github.com/' + config.repository,branch:config.branch};
      }
      const config = github.validateConfig(JSON.parse(readFile(configFile,16384)));
      if (args[0] === 'push' && args.length > 1) return {repository:config.repository,...remote.push(config,state.history,args.slice(1))};
      if (args[0] === 'status' && args.length === 1) {
        const result = remote.pull(config), model = p.reduce(result.bundle);
        return {repository:config.repository,branch:config.branch,eventCount:model.eventCount,projects:model.projects,initialized:!!result.sha};
      }
      if (args[0] === 'pull' && (args.length === 1 || args.length === 2 && args[1] === '--dry-run')) {
        const incoming = remote.pull(config), before = state.history.events.length;
        state.history = p.union(state.history,incoming.bundle);
        const result = {...summary(state),repository:config.repository,addedEvents:state.history.events.length-before,dryRun:args[1] === '--dry-run'};
        if (!result.dryRun) store(stateFile,state); return result;
      }
      error('Usage: sync connect|status|push|pull; use --help');
    }
    if (cmd === 'status' && !args.length || cmd === 'projects' && args[0] === 'list' && args.length === 1) return summary(state);
    if (cmd === 'import') {
      if (args.length < 1 || args.length > 2 || args.length === 2 && args[1] !== '--dry-run') error('Usage: import <file> [--dry-run]');
      const incoming = p.parse(readFile(path.resolve(args[0]),p.MAX_BYTES));
      const before = state.history.events.length; state.history = p.union(state.history,incoming);
      const result = {...summary(state),addedEvents:state.history.events.length - before,dryRun:args[1] === '--dry-run'};
      if (!result.dryRun) store(stateFile,state); return result;
    }
    if (cmd === 'export') {
      if (args.length < 2) error('Usage: export <new-file> <projectId> [projectId...]');
      const bundle = p.select(state.history,args.slice(1)), target = path.resolve(args[0]);
      // Exclusive create also refuses existing symlinks and state-file overwrites.
      const fd = fs.openSync(target,'wx',0o600);
      try {fs.writeFileSync(fd,JSON.stringify(bundle)); fs.fsyncSync(fd);} finally {fs.closeSync(fd);}
      return {file:target,eventCount:bundle.events.length};
    }
    if (cmd !== 'projects') error('Unknown command; use --help');
    const [action,projectId,value] = args;
    if (action === 'add') {
      if (args.length !== 2) error('Usage: projects add <name>');
      state.history = p.add(state.history,'created',p.newId('proj'),projectId,state.deviceId);
    } else if (action === 'rename') {
      if (args.length !== 3) error('Usage: projects rename <projectId> <name>');
      state.history = p.add(state.history,'renamed',projectId,value,state.deviceId);
    } else if (action === 'merge') {
      if (args.length !== 3) error('Usage: projects merge <sourceId> <targetId>');
      const model = p.reduce(state.history), source = model.canonical(projectId), target = model.canonical(value);
      if (source !== target) {
        const name = model.projects.find(e => e.projectId === target).name;
        state.history = p.add(state.history,'linked',source,target,state.deviceId);
        state.history = p.add(state.history,'renamed',target,name,state.deviceId);
      }
    } else if (action === 'bind') {
      if (args.length !== 3) error('Usage: projects bind <projectId> <folder>');
      const model = p.reduce(state.history), canonical = model.canonical(projectId), folder = fs.realpathSync(value);
      if (!fs.statSync(folder).isDirectory()) error('Choose an existing directory');
      const existing = state.bindings.find(b => b.localPath === folder);
      if (existing && model.canonical(existing.projectId) !== canonical) error('Folder is already bound to another project');
      if (!existing) state.bindings.push({bindingId:p.newId('binding'),projectId:canonical,localPath:folder});
    } else error('Unknown projects command; use --help');
    store(stateFile,state); return summary(state);
  } finally {if (locked) fs.rmdirSync(lock);}
}
if (require.main === module) {
  try { const result = run(process.argv.slice(2)); process.stdout.write(typeof result === 'string' ? result : JSON.stringify(result,null,2) + '\n'); }
  catch (e) { process.stderr.write((e.message || String(e)) + '\n'); process.exitCode = 1; }
}
module.exports = {run,readFile,validateState};
