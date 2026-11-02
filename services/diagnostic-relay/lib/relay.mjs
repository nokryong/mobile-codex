import {randomBytes, createHmac} from 'node:crypto';
import {isIP} from 'node:net';
import {Rejected, MAX_BODY, challengePayload, reportPayload, signature, sampleHash, proofValid} from './schema.mjs';
import {RedisStore} from './redis.mjs';
import {GitHubReporter} from './github.mjs';
const random = () => randomBytes(24).toString('hex');
const json = (value, status = 200) => Response.json(value, {status, headers:{'cache-control':'no-store', 'x-content-type-options':'nosniff'}});
async function body(request) {
  if (request.headers.get('content-type')?.split(';')[0] !== 'application/json') throw new Rejected(415, 'json_required');
  if (request.headers.get('content-encoding')) throw new Rejected(415, 'encoding_not_supported');
  if (Number(request.headers.get('content-length')) > MAX_BODY) throw new Rejected(413, 'body_too_large');
  const reader = request.body?.getReader(); if (!reader) throw new Rejected(400, 'invalid_schema');
  let size = 0; const parts = [];
  try {
    while (true) { const {value, done} = await reader.read(); if (done) break; size += value.length; if (size > MAX_BODY) throw new Rejected(413, 'body_too_large'); parts.push(Buffer.from(value)); }
  } finally { await reader.cancel().catch(() => {}); }
  return Buffer.concat(parts).toString('utf8');
}
function prefix(request) {
  // Vercel overwrites x-forwarded-for at its edge. Do not deploy this adapter
  // behind an untrusted proxy that forwards caller-supplied versions.
  const ip = request.headers.get('x-forwarded-for')?.trim();
  if (!ip || !isIP(ip)) throw new Rejected(400, 'network_unavailable');
  // Prefixes are HMACed with an expiring daily salt, never stored/published.
  if (isIP(ip) === 4) return ip.split('.').slice(0, 3).join('.');
  // Normalize IPv6 before /48 grouping, including compressed addresses.
  const halves = ip.toLowerCase().split('::'), left = halves[0] ? halves[0].split(':') : [], right = halves[1] ? halves[1].split(':') : [];
  const expanded = halves.length === 2 ? [...left, ...Array(8-left.length-right.length).fill('0'), ...right] : left;
  return expanded.slice(0, 3).map(part => parseInt(part, 16).toString(16)).join(':');
}
export function createRelay({store, github, now = Date.now} = {}) {
  async function network(request) {
    const day = Math.floor(now() / 86400000), key = 'salt:' + day;
    await store.setOnce(key, random(), 172800); const salt = await store.get(key);
    return createHmac('sha256', salt).update(prefix(request)).digest('hex');
  }
  async function rate(key, limit, seconds) { if (!await store.rate(key, limit, seconds)) throw new Rejected(429, 'rate_limited'); }
  async function publish(report) {
    const fingerprint = signature(report), sample = sampleHash(report), key = 'group:' + fingerprint, lock = 'lock:' + fingerprint, owner = random();
    const receiptKey = 'receipt:' + report.reportId;
    await store.setOnce(receiptKey, {sample}, 604800);
    const bound = await store.get(receiptKey);
    if (bound?.sample !== sample) throw new Rejected(409, 'report_id_reused');
    if (!await store.setOnce(lock, owner, 120)) throw new Rejected(409, 'report_pending');
    try {
      const receipt = await store.get(receiptKey);
      if (receipt && receipt.sample !== sample) throw new Rejected(409, 'report_id_reused');
      if (receipt?.url) return {url:receipt.url, grouped:true};
      let group = await store.get(key);
      if (group?.state === 'pending') {
        const found = await github.find(group.marker);
        if (!found) throw new Rejected(503, 'report_pending');
        group = {state:'done', ...found}; await store.set(key, group, 2592000);
      }
      const sampleKey = 'sample:' + fingerprint + ':' + sample;
      const prior = await store.get(sampleKey);
      if (prior?.state === 'pending') {
        const found = await github.find(prior.marker, prior.issueNumber);
        if (!found) throw new Rejected(503, 'report_pending');
        await store.set(sampleKey, {state:'done', url:found.url});
        await store.set(receiptKey, {sample, url:found.url}); return {url:found.url, grouped:true};
      }
      if (prior?.url) { await store.set(receiptKey, {sample, url:prior.url}); return {url:prior.url, grouped:true}; }
      const marker = 'mcdiag-' + random();
      const day = Math.floor(now() / 86400000);
      if (!group) {
        await rate('new:' + day, 10, 172800);
        await rate('writes:' + day, 30, 172800);
        await store.set(receiptKey, {sample});
        await store.set(key, {state:'pending', marker}, 2592000);
        // Persist before POST so an uncertain result can only be reconciled,
        // not replayed into duplicate public issues.
        await store.set(sampleKey, {state:'pending', marker});
        const issue = await github.create(report, marker);
        group = {state:'done', ...issue}; await store.set(key, group, 2592000);
        await store.set(sampleKey, {state:'done', url:issue.url});
        await store.set(receiptKey, {sample, url:issue.url}); return {url:issue.url, grouped:false};
      }
      if (!await store.rate('samples:' + fingerprint + ':' + day, 3, 172800)) {
        await store.set(receiptKey, {sample, url:group.url}); return {url:group.url, grouped:true};
      }
      await rate('writes:' + day, 30, 172800);
      await store.set(receiptKey, {sample});
      await store.set(sampleKey, {state:'pending', marker, issueNumber:group.number});
      const result = await github.comment(group.number, report, marker);
      await store.set(sampleKey, {state:'done', url:result.url}); await store.set(receiptKey, {sample, url:result.url});
      return {url:result.url, grouped:true};
    } finally { await store.unlock(lock, owner); }
  }
  return async function handle(request, route) {
    try {
      if (route === 'health') {
        if (request.method !== 'GET') throw new Rejected(405, 'method_not_allowed');
        if (!store || !github) throw new Rejected(503, 'not_configured');
        await store.get('health'); return json({schema:1, ready:true});
      }
      if (request.method !== 'POST') throw new Rejected(405, 'method_not_allowed');
      const text = await body(request);
      const incoming = route === 'challenge' ? challengePayload(text) : route === 'reports' ? reportPayload(text) : null;
      if (!incoming) throw new Rejected(404, 'not_found');
      if (!store || !github) throw new Rejected(503, 'not_configured');
      const net = await network(request), hour = Math.floor(now() / 3600000), day = Math.floor(now() / 86400000);
      await rate('requests:' + day, 10000, 172800);
      await rate('requests:' + net + ':' + hour, 30, 7200);
      if (route === 'challenge') {
        const challengeId = random();
        await store.set('challenge:' + challengeId, {net, reportId:incoming.reportId}, 300);
        return json({challengeId, difficulty:16, expiresIn:300});
      }
      if (!proofValid(incoming.challengeId, incoming.proof)) throw new Rejected(400, 'invalid_proof');
      if (!await store.consume('challenge:' + incoming.challengeId, {net, reportId:incoming.report.reportId})) throw new Rejected(409, 'challenge_expired');
      await rate('reports:' + net + ':' + hour, 3, 7200);
      return json(await publish(incoming.report));
    } catch (error) {
      // Never log incoming bodies, headers, exceptions or upstream response text.
      return json({error:error instanceof Rejected ? error.code : 'temporarily_unavailable'}, error instanceof Rejected ? error.status : 503);
    }
  };
}
export function productionRelay(environment = process.env) {
  if (!environment.REPORT_GITHUB_TOKEN || !environment.UPSTASH_REDIS_REST_URL || !environment.UPSTASH_REDIS_REST_TOKEN) return createRelay();
  try { return createRelay({store:new RedisStore(environment.UPSTASH_REDIS_REST_URL, environment.UPSTASH_REDIS_REST_TOKEN), github:new GitHubReporter(environment.REPORT_GITHUB_TOKEN)}); }
  catch { return createRelay(); }
}
