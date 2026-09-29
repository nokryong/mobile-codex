'use strict';
const {spawnSync} = require('node:child_process');
const p = require('./projects.cjs');
const FILE = 'projects.json';
function api(method, endpoint, body) {
  const args = ['api','--hostname','github.com','--method',method,endpoint];
  if (body !== undefined) args.push('--input','-');
  const result = spawnSync('gh',args,{encoding:'utf8',windowsHide:true,timeout:45000,maxBuffer:3*p.MAX_BYTES,
    input:body === undefined ? undefined : JSON.stringify(body),env:{...process.env,GH_PROMPT_DISABLED:'1'}});
  if (result.error || result.status !== 0) {
    const status = Number(/\(HTTP (\d+)\)/.exec(result.stderr || '')?.[1] || 0);
    const error = new Error(status ? `GitHub request failed (HTTP ${status})` : 'GitHub request failed; check gh login and network access');
    error.status = status; throw error;
  }
  return JSON.parse(result.stdout);
}
function repoName(repo) {
  if (typeof repo !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9_.-]+$/.test(repo) || /\/(?:\.|\.\.)$/.test(repo)) throw new Error('Use a GitHub owner/repository name');
  return repo;
}
function validateConfig(config) {
  p.fields(config,['schemaVersion','repository','repositoryId','branch']);
  if (config.schemaVersion !== 1 || !Number.isSafeInteger(config.repositoryId) || config.repositoryId < 1 || typeof config.branch !== 'string' || !config.branch) throw new Error('Invalid sync configuration');
  repoName(config.repository); return config;
}
function client(request = api) {
  function verify(config) {
    validateConfig(config);
    const info = request('GET','repos/' + config.repository);
    if (info.private !== true) throw new Error('Project sync requires a private repository');
    if (info.id !== config.repositoryId || info.default_branch !== config.branch) throw new Error('Sync repository identity or default branch changed; reconnect explicitly');
    return info;
  }
  function read(config) {
    let file;
    try { file = request('GET',`repos/${config.repository}/contents/${FILE}?ref=${encodeURIComponent(config.branch)}`); }
    catch (e) {if (e.status === 404) return {sha:null,bundle:p.empty()}; throw e;}
    if (file.type !== 'file' || file.encoding !== 'base64' || !Number.isInteger(file.size) || file.size > p.MAX_BYTES || !/^[a-f0-9]{40,64}$/.test(file.sha) || typeof file.content !== 'string') throw new Error('Invalid remote project file');
    const encoded = file.content.replace(/[\r\n]/g,'');
    if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new Error('Invalid remote encoding');
    const bytes = Buffer.from(encoded,'base64');
    if (bytes.length !== file.size || bytes.length > p.MAX_BYTES) throw new Error('Invalid remote file size');
    return {sha:file.sha,bundle:p.parse(new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes))};
  }
  function connect(repository) {
    const info = request('GET','repos/' + repoName(repository));
    if (info.private !== true || info.permissions?.push !== true) throw new Error('Choose a private repository you can write to');
    const config = validateConfig({schemaVersion:1,repository:info.full_name,repositoryId:info.id,branch:info.default_branch});
    read(config); return config;
  }
  function pull(config) {verify(config); return read(config);}
  function push(config, history, selected) {
    const exported = p.select(history,selected); // Validate before any remote write.
    for (let attempt=0; attempt<3; attempt++) {
      const info = verify(config);
      if (info.permissions?.push !== true) throw new Error('Write permission is required');
      const remote = read(config), combined = p.union(remote.bundle,exported);
      if (remote.sha && JSON.stringify(combined) === JSON.stringify(remote.bundle)) return {changed:false,eventCount:combined.events.length};
      const body = {message:'Sync Mobile Codex project identities',content:Buffer.from(JSON.stringify(combined),'utf8').toString('base64')};
      if (remote.sha) {body.sha=remote.sha; body.branch=config.branch;}
      // On an empty repository the first commit creates its default branch.
      else if (info.size > 0) body.branch=config.branch;
      try {
        const result = request('PUT',`repos/${config.repository}/contents/${FILE}`,body);
        return {changed:true,eventCount:combined.events.length,commit:result.commit.sha};
      } catch(e) {if (![409,422].includes(e.status) || attempt === 2) throw e;}
    }
  }
  return {connect,pull,push};
}
module.exports = {client,validateConfig};
