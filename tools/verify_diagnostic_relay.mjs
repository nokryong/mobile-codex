// Run manually only after production provisioning is approved. This creates one
// clearly marked synthetic public issue, then verifies idempotency. No user data.
import {randomUUID} from 'node:crypto';
import {proofValid} from '../services/diagnostic-relay/lib/schema.mjs';
const base = process.argv[2];
if (!/^https:\/\/[a-z0-9-]+\.vercel\.app$/.test(base || '')) throw Error('Provide the approved production Vercel alias.');
const report = {schema:1,reportId:randomUUID(),appVersion:'0.3.6',runtime:'codex_01621',errorCode:'relay_verification',operation:'other',stage:'unknown',homeKind:'unknown',elapsedMs:0,spawnMs:-1,writeMs:-1,firstByteMs:-1,responseMs:-1,processAlive:false,exitCode:-1,stdoutBytes:0,stdoutLines:0,invalidJson:0,unmatchedIds:0,stderrBytes:0,stderrCategory:'none',androidApi:35,abi:'arm64',manufacturer:'other',deviceClass:'phone',ramClass:'unknown',pageSize:'unknown'};
async function post(route, data) {
  const response = await fetch(base + '/api/' + route, {method:'POST',redirect:'error',headers:{'content-type':'application/json'},body:JSON.stringify(data),signal:AbortSignal.timeout(30000)});
  const result = await response.json(); if (!response.ok) throw Error('Relay check failed: HTTP ' + response.status); return result;
}
async function send() {
  const challenge = await post('challenge', {reportId:report.reportId});
  if (!/^[a-f0-9]{48}$/.test(challenge.challengeId || '') || challenge.difficulty !== 16) throw Error('Invalid challenge');
  let proof = 0; while (!proofValid(challenge.challengeId, proof)) { if (++proof > 100000000) throw Error('Proof limit'); }
  return post('reports', {...report,challengeId:challenge.challengeId,proof});
}
const health = await fetch(base + '/api/health', {redirect:'error',signal:AbortSignal.timeout(10000)});
console.log(JSON.stringify({healthHttpStatus:health.status}));
if (!health.ok || !(await health.json()).ready) throw Error('Relay not ready');
const first = await send();
if (!/^https:\/\/github\.com\/nokryong\/mobile-codex\/issues\/[1-9][0-9]*$/.test(first.url || '')) throw Error('Invalid public issue result');
console.log(JSON.stringify({syntheticIssue:first.url,submitted:true}));
const retry = await send(); if (retry.url !== first.url || !retry.grouped) throw Error('Idempotency check failed');
console.log(JSON.stringify({verified:true,syntheticIssue:first.url,idempotent:true}));
