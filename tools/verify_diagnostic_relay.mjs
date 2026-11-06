// Creates marked synthetic schema-2 and legacy reports (at most two issues).
// Three submissions fit the unchanged hourly cap: v2, v2 retry, v1.
import {randomUUID} from 'node:crypto';
import {proofValid} from '../services/diagnostic-relay/lib/schema.mjs';
const base = process.argv[2];
if (!/^https:\/\/[a-z0-9-]+\.vercel\.app$/.test(base || '')) throw Error('Provide the approved production Vercel alias.');
const legacy = {schema:1,reportId:randomUUID(),appVersion:'0.3.6',runtime:'codex_01621',errorCode:'relay_verification',operation:'other',stage:'unknown',homeKind:'unknown',elapsedMs:0,spawnMs:-1,writeMs:-1,firstByteMs:-1,responseMs:-1,processAlive:false,exitCode:-1,stdoutBytes:0,stdoutLines:0,invalidJson:0,unmatchedIds:0,stderrBytes:0,stderrCategory:'none',androidApi:35,abi:'arm64',manufacturer:'other',deviceClass:'phone',ramClass:'unknown',pageSize:'unknown'};
const report = {...legacy,schema:2,reportId:randomUUID(),appVersion:'0.3.8',elapsedMs:null,processAlive:null,stdoutBytes:null,stdoutLines:null,invalidJson:null,unmatchedIds:null,stderrBytes:null};
async function post(route, data) {
  const response = await fetch(base + '/api/' + route, {method:'POST',redirect:'error',headers:{'content-type':'application/json'},body:JSON.stringify(data),signal:AbortSignal.timeout(30000)});
  const result = await response.json(); if (!response.ok) throw Error('Relay check failed: HTTP ' + response.status); return result;
}
async function send(payload) {
  const challenge = await post('challenge', {reportId:payload.reportId});
  if (!/^[a-f0-9]{48}$/.test(challenge.challengeId || '') || challenge.difficulty !== 16) throw Error('Invalid challenge');
  let proof = 0; while (!proofValid(challenge.challengeId, proof)) { if (++proof > 100000000) throw Error('Proof limit'); }
  return post('reports', {...payload,challengeId:challenge.challengeId,proof});
}
async function verifyPublic(url, payload) {
  const number = url.match(/^https:\/\/github\.com\/nokryong\/mobile-codex\/issues\/([1-9][0-9]*)$/)?.[1];
  if (!number) throw Error('Invalid public issue result');
  const api = 'https://api.github.com/repos/nokryong/mobile-codex/issues/' + number;
  const headers = {'accept':'application/vnd.github+json','user-agent':'mobile-codex-relay-verification'};
  async function get(path) {
    const response = await fetch(path,{headers,redirect:'error',signal:AbortSignal.timeout(30000)});
    if (!response.ok) throw Error('Cannot verify public report: HTTP ' + response.status);
    return response.json();
  }
  const issue = await get(api), bodies = [issue.body];
  for (let page = 1; page <= Math.ceil(issue.comments / 100); page++) {
    const comments = await get(api + '/comments?per_page=100&page=' + page);
    bodies.push(...comments.map(comment => comment.body));
  }
  // Identical samples intentionally share the first public sample even when a
  // new receipt ID was submitted. Compare all diagnostic fields, not that ID.
  const matches = bodies.map(body => {
    const json = body?.match(/```json\n([\s\S]*?)\n```/)?.[1];
    return json ? JSON.parse(json) : null;
  }).filter(value => value && Object.entries(payload).every(([key,expected]) => key === 'reportId' || value[key] === expected));
  if (matches.length !== 1 || Object.keys(matches[0]).length !== 27 ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(matches[0].reportId || '')) throw Error('Public payload mismatch or duplicate');
}
const health = await fetch(base + '/api/health', {redirect:'error',signal:AbortSignal.timeout(10000)});
console.log(JSON.stringify({healthHttpStatus:health.status}));
if (!health.ok || !(await health.json()).ready) throw Error('Relay not ready');
const first = await send(report);
if (!/^https:\/\/github\.com\/nokryong\/mobile-codex\/issues\/[1-9][0-9]*$/.test(first.url || '')) throw Error('Invalid public issue result');
console.log(JSON.stringify({syntheticIssue:first.url,submitted:true,schema:2}));
const retry = await send(report); if (retry.url !== first.url || !retry.grouped) throw Error('Idempotency check failed');
await verifyPublic(first.url, report);
console.log(JSON.stringify({verified:true,syntheticIssue:first.url,idempotent:true,schema:2,nullMeasurements:true}));
const old = await send(legacy);
await verifyPublic(old.url, legacy);
console.log(JSON.stringify({verified:true,syntheticIssue:old.url,schema:1,legacyCompatible:true}));
