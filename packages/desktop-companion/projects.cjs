'use strict';
const {randomUUID} = require('node:crypto');
const MAX_BYTES = 1024 * 1024, MAX_EVENTS = 1024;
const FORMAT = 'mobile-codex-projects';
const fail = message => { throw new Error(message); };
const secret = /sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN .*PRIVATE KEY-----|(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|secret)\s*[:=]\s*\S+/i;
const empty = () => ({format:FORMAT, schemaVersion:1, events:[]});
const newId = prefix => prefix + '_' + randomUUID();
function fields(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).sort().join(',') !== keys.slice().sort().join(',')) fail('Unknown or missing project fields');
}
function identifier(value, prefix) {
  if (typeof value !== 'string' || !new RegExp('^' + prefix + '_[a-z0-9_-]{1,96}$').test(value)) fail('Invalid ' + prefix + ' ID');
  return value;
}
// JSON.parse accepts duplicate keys. Check the token stream before decoding it.
function strictParse(raw) {
  if (typeof raw !== 'string' || Buffer.byteLength(raw, 'utf8') > MAX_BYTES) fail('Project file exceeds 1 MiB');
  let at = 0;
  function ws() { while (' \r\n\t'.includes(raw[at]) && at < raw.length) at++; }
  function take(char) { ws(); if (raw[at++] !== char) fail('Invalid project JSON'); }
  function string() {
    ws(); const start = at; take('"');
    while (at < raw.length) {
      const c = raw[at++];
      if (c === '"') return JSON.parse(raw.slice(start, at));
      if (c === '\\') at++;
    }
    fail('Incomplete JSON string');
  }
  function value(depth) {
    if (depth > 12) fail('JSON too deep'); ws();
    if (raw[at] === '{') {
      at++; ws(); if (raw[at] === '}') {at++; return;}
      const seen = new Set();
      while (true) {
        const key = string(); if (seen.has(key)) fail('Duplicate JSON key'); seen.add(key);
        take(':'); value(depth + 1); ws(); if (raw[at] === '}') {at++; return;} take(',');
      }
    } else if (raw[at] === '[') {
      at++; ws(); if (raw[at] === ']') {at++; return;}
      while (true) { value(depth + 1); ws(); if (raw[at] === ']') {at++; return;} take(','); }
    } else if (raw[at] === '"') string();
    else {
      const match = /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(raw.slice(at));
      if (!match) fail('Invalid JSON value'); at += match[0].length;
    }
  }
  value(0); ws(); if (at !== raw.length) fail('Trailing JSON data');
  return JSON.parse(raw);
}
function read(data, exchange = false) {
  if (Buffer.byteLength(JSON.stringify(data), 'utf8') > MAX_BYTES) fail('Project history exceeds 1 MiB');
  fields(data, ['format','schemaVersion','events']);
  if (data.format !== FORMAT || data.schemaVersion !== 1 || !Array.isArray(data.events) || data.events.length > MAX_EVENTS) fail('Unsupported project bundle');
  const events = new Map();
  for (const e of data.events) {
    const linked = e?.kind === 'linked';
    fields(e, ['id','deviceId','projectId','kind','parents',linked ? 'targetId' : 'name']);
    if (!['created','renamed','linked'].includes(e.kind)) fail('Unknown project event');
    identifier(e.id, 'evt'); identifier(e.deviceId, 'device'); identifier(e.projectId, 'proj');
    if (!Array.isArray(e.parents) || e.parents.length > MAX_EVENTS || new Set(e.parents).size !== e.parents.length) fail('Invalid event parents');
    e.parents.forEach(p => identifier(p, 'evt'));
    if (e.kind === 'created' && e.parents.length) fail('Creation cannot have parents');
    if (linked) identifier(e.targetId, 'proj');
    else if (typeof e.name !== 'string' || !e.name.trim() || e.name.length > 512 || e.name.includes('\0') || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(e.name) || (exchange && secret.test(e.name))) fail('Invalid project name or possible credential');
    if (events.has(e.id)) fail('Duplicate event ID');
    events.set(e.id, {id:e.id,deviceId:e.deviceId,projectId:e.projectId,kind:e.kind,parents:[...e.parents].sort(),[linked ? 'targetId' : 'name']:linked ? e.targetId : e.name});
  }
  const normalized = {format:FORMAT,schemaVersion:1,events:[...events.values()].sort((a,b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0)};
  reduce(normalized); return normalized;
}
function parse(raw, exchange = true) { return read(strictParse(raw), exchange); }
function reduce(bundle) {
  const roots = new Map(), events = new Map(bundle.events.map(e => [e.id,e])), ancestors = new Map();
  for (const e of events.values()) if (e.kind === 'created') roots.set(e.projectId,e.projectId);
  function canonical(id) {
    if (!roots.has(id)) fail('Unknown project ID');
    while (roots.get(id) !== id) id = roots.get(id); return id;
  }
  for (const e of events.values()) {
    const a = canonical(e.projectId);
    if (e.kind === 'linked') { const b = canonical(e.targetId); if (a !== b) roots.set(a < b ? b : a, a < b ? a : b); }
  }
  const remaining = new Set(events.keys());
  while (remaining.size) {
    let progress = false;
    for (const id of [...remaining]) {
      const e = events.get(id), seen = new Set(); let ready = true;
      for (const p of e.parents) {
        if (!events.has(p)) fail('Missing event parent');
        if (canonical(events.get(p).projectId) !== canonical(e.projectId)) fail('Unrelated event parent');
        if (!ancestors.has(p)) {ready = false; break;}
        seen.add(p); for (const a of ancestors.get(p)) seen.add(a);
      }
      if (!ready) continue;
      if (e.kind !== 'created') for (const project of e.kind === 'linked' ? [e.projectId,e.targetId] : [e.projectId]) {
        if (![...seen].some(p => events.get(p).kind === 'created' && events.get(p).projectId === project)) fail('Event has no causal project creation');
      }
      ancestors.set(id,seen); remaining.delete(id); progress = true;
    }
    if (!progress) fail('Cyclic project history');
  }
  const superseded = new Set(), names = new Map();
  for (const e of events.values()) if (e.kind !== 'linked') for (const id of ancestors.get(e.id)) superseded.add(id);
  for (const e of events.values()) if (e.kind !== 'linked' && !superseded.has(e.id)) {
    const id = canonical(e.projectId); if (!names.has(id)) names.set(id,new Set()); names.get(id).add(e.name);
  }
  const projects = [...names].sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0).map(([projectId,values]) => {
    const sorted = [...values].sort(); return {projectId,name:sorted[0],nameConflicts:sorted.length > 1 ? sorted : []};
  });
  return {canonical,projects,eventCount:events.size,linkCount:bundle.events.filter(e => e.kind === 'linked').length,conflictCount:projects.filter(p => p.nameConflicts.length).length};
}
function union(left, right) {
  const events = new Map(left.events.map(e => [e.id,e]));
  for (const e of right.events) {
    if (events.has(e.id) && JSON.stringify(events.get(e.id)) !== JSON.stringify(e)) fail('Event ID has different contents');
    events.set(e.id,e);
  }
  return read({...empty(),events:[...events.values()]});
}
function add(bundle, kind, projectId, value, deviceId) {
  const parents = new Set();
  if (kind !== 'created') {
    const {canonical} = reduce(bundle), groups = new Set([canonical(projectId)]);
    if (kind === 'linked') groups.add(canonical(value));
    for (const e of bundle.events) if (groups.has(canonical(e.projectId))) parents.add(e.id);
    for (const e of bundle.events) for (const id of e.parents) parents.delete(id);
  }
  return read({...bundle,events:[...bundle.events,{id:newId('evt'),deviceId,projectId,kind,parents:[...parents].sort(),[kind === 'linked' ? 'targetId' : 'name']:value}]});
}
function select(bundle, ids) {
  const {canonical} = reduce(bundle), selected = new Set(ids.map(canonical));
  if (!selected.size) fail('Select at least one project');
  return read({...bundle,events:bundle.events.filter(e => selected.has(canonical(e.projectId)))}, true);
}
module.exports = {MAX_BYTES,MAX_EVENTS,empty,newId,fields,identifier,strictParse,read,parse,reduce,union,add,select};
