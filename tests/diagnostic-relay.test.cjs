const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const base = '../services/diagnostic-relay/lib/';
const sample = (extra = {}) => ({schema:1, reportId:crypto.randomUUID(), appVersion:'0.3.6', runtime:'codex_01621', errorCode:'initialize_timeout', operation:'login', stage:'initialize_sent', homeKind:'pending', elapsedMs:60000, spawnMs:0, writeMs:0, firstByteMs:-1, responseMs:-1, processAlive:true, exitCode:-1, stdoutBytes:0, stdoutLines:0, invalidJson:0, unmatchedIds:0, stderrBytes:32, stderrCategory:'none', androidApi:35, abi:'arm64', manufacturer:'samsung', deviceClass:'phone', ramClass:'le8gb', pageSize:'4k', ...extra});
class MemoryStore {
  constructor(){ this.values = new Map(); this.rates = new Map(); }
  async get(key){return this.values.get(key) || null;}
  async set(key,value){this.values.set(key, structuredClone(value));}
  async setOnce(key,value){if(this.values.has(key))return false;this.values.set(key,structuredClone(value));return true;}
  async rate(key,limit){const count=(this.rates.get(key)||0)+1;this.rates.set(key,count);return count<=limit;}
  async consume(key,value){if(JSON.stringify(this.values.get(key))!==JSON.stringify(value))return false;this.values.delete(key);return true;}
  async unlock(key,value){if(this.values.get(key)===value)this.values.delete(key);}
}
async function fixture() {
  const {createRelay}=await import(base+'relay.mjs'), {proofValid}=await import(base+'schema.mjs');
  const store=new MemoryStore(), issues=[], comments=[];
  const github={
    async create(report,marker){const issue={number:100+issues.length,url:'https://github.com/nokryong/mobile-codex/issues/'+(100+issues.length)};issues.push({report,marker,...issue});return issue;},
    async comment(number,report,marker){comments.push({number,report,marker});return {number,url:'https://github.com/nokryong/mobile-codex/issues/'+number};},
    async find(marker,number){return (number?comments:issues).find(row=>row.marker===marker) || null;}
  };
  const handle=createRelay({store,github,now:()=>1793628000000});
  const request=(route,payload,ip='192.0.2.1')=>handle(new Request('https://relay.test/api/'+route,{method:'POST',headers:{'content-type':'application/json','x-forwarded-for':ip},body:typeof payload==='string'?payload:JSON.stringify(payload)}),route);
  async function payload(report,ip){const challenge=await (await request('challenge',{reportId:report.reportId},ip)).json();assert.ok(challenge.challengeId);let proof=0;while(!proofValid(challenge.challengeId,proof))proof++;return {...report,challengeId:challenge.challengeId,proof};}
  async function send(report,ip){return request('reports',await payload(report,ip),ip);}
  return {store,github,issues,comments,handle,request,payload,send};
}
test('relay rejects private/free-form/nested/duplicate payloads before storage or GitHub',async()=>{
  const {parseFlat,reportPayload}=await import(base+'schema.mjs');
  for(const text of ['{"reportId":"x","reportId":"y"}','{"token":"secret"}','{"schema":1,"nested":{}}','{"schema":1,}','{"schema":1,"body":"@someone"}']) assert.throws(()=>reportPayload(text));
  assert.throws(()=>parseFlat('{"schema":1,"schema":1}'));
  const fx=await fixture();let called=false;fx.store.get=()=>{called=true;throw Error('should not read');};
  for(const extra of [{token:'secret'}, {stdout:'account secret'}, {repository:'someone/repo'}, {manufacturer:'private-model'}, {elapsedMs:Infinity}]){
    const value={...sample(),challengeId:'a'.repeat(48),proof:1,...extra};const response=await fx.request('reports',value);assert.equal(response.status,400);
  }
  assert.equal(called,false);assert.equal(fx.issues.length,0);
});
test('bounded request reader, methods and fail-closed unconfigured deployment',async()=>{
  const {createRelay}=await import(base+'relay.mjs');const handle=createRelay();
  assert.equal((await handle(new Request('https://r/api/health'),'health')).status,503);
  assert.equal((await handle(new Request('https://r/api/reports'),'reports')).status,405);
  const fx=await fixture();assert.equal((await fx.request('reports','x'.repeat(4097))).status,413);
  assert.equal((await fx.handle(new Request('https://r',{method:'POST',body:'x'}),'reports')).status,415);
});
test('explicit report creates once, same payload retries and duplicate click return original issue',async()=>{
  const fx=await fixture(),report=sample();const first=await fx.send(report);assert.equal(first.status,200);
  const result=await first.json();assert.match(result.url,/issues\/100$/);
  const retry=await fx.send(report);assert.equal((await retry.json()).url,result.url);assert.equal(fx.issues.length,1);assert.equal(fx.comments.length,0);
  const duplicate=await fx.send({...report,reportId:crypto.randomUUID()});assert.equal((await duplicate.json()).url,result.url);assert.equal(fx.issues.length,1);
});
test('distinct safe samples append bounded comments without changing original report',async()=>{
  const fx=await fixture(),report=sample();await fx.send(report);
  const original=JSON.stringify(fx.issues[0]);
  for(let i=1;i<=5;i++) {const response=await fx.send(sample({stderrBytes:i}),'198.51.'+i+'.1');assert.equal(response.status,200);}
  assert.equal(fx.issues.length,1);assert.equal(fx.comments.length,3);assert.equal(JSON.stringify(fx.issues[0]),original);
});
test('challenge is single-use, report-bound and network-bound',async()=>{
  const fx=await fixture(),report=sample(),payload=await fx.payload(report);
  assert.equal((await fx.request('reports',payload,'203.0.113.1')).status,409);
  assert.equal((await fx.request('reports',{...payload,reportId:crypto.randomUUID()})).status,409);
  assert.equal((await fx.request('reports',payload)).status,200);
  assert.equal((await fx.request('reports',payload)).status,409);assert.equal(fx.issues.length,1);
});
test('simultaneous report attempts cannot create duplicate issues',async()=>{
  const fx=await fixture(),report=sample();const a=await fx.payload(report),b=await fx.payload(report);
  const results=await Promise.all([fx.request('reports',a),fx.request('reports',b)]);
  assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);assert.equal(fx.issues.length,1);
});
test('unknown GitHub POST outcome reconciles marker, never writes again',async()=>{
  const fx=await fixture(),report=sample(),create=fx.github.create;
  fx.github.create=async(...args)=>{await create(...args);throw Error('response lost');};
  assert.equal((await fx.send(report)).status,503);
  assert.equal((await fx.send(report)).status,200);assert.equal(fx.issues.length,1);
});
test('unknown POST with no marker stays pending instead of duplicating',async()=>{
  const fx=await fixture(),report=sample();let writes=0;fx.github.create=async()=>{writes++;throw Error('unknown');};
  assert.equal((await fx.send(report)).status,503);assert.equal((await fx.send(report)).status,503);assert.equal(writes,1);
});
test('report ID cannot be reused with altered diagnostics',async()=>{
  const fx=await fixture(),report=sample();await fx.send(report);
  assert.equal((await fx.send({...report,stdoutBytes:100})).status,409);assert.equal(fx.issues.length,1);
});
test('durable storage outage and network abuse do not bypass protections',async()=>{
  const fx=await fixture();fx.store.setOnce=async()=>{throw Error('redis down');};assert.equal((await fx.request('challenge',{reportId:crypto.randomUUID()})).status,503);assert.equal(fx.issues.length,0);
  const limited=await fixture();for(let n=0;n<30;n++)assert.equal((await limited.request('challenge',{reportId:crypto.randomUUID()})).status,200);
  assert.equal((await limited.request('challenge',{reportId:crypto.randomUUID()})).status,429);
  assert.ok([...limited.store.values.keys()].every(key=>!key.includes('192.0.2')));
});
test('GitHub adapter pins repository/template, validates links and never exposes upstream errors',async()=>{
  const {GitHubReporter}=await import(base+'github.mjs');const calls=[];
  const github=new GitHubReporter('not-a-real-token',async(url,options)=>{if(url==='https://api.github.com/user')return Response.json({login:'nokryong'});calls.push({url,options});return Response.json({number:10,html_url:'https://github.com/nokryong/mobile-codex/issues/10'});});
  await github.create(sample(),'mcdiag-'+'a'.repeat(48));
  assert.equal(calls[0].url,'https://api.github.com/repos/nokryong/mobile-codex/issues');
  const data=JSON.parse(calls[0].options.body);assert.match(data.title,/initialize_timeout/);assert.equal(data.body.includes('not-a-real-token'),false);
  const broken=new GitHubReporter('secret',async()=>{throw Error('secret upstream dump');});await assert.rejects(broken.create(sample(),'marker'),error=>error.code==='reporter_not_authorized'&&!error.message.includes('secret'));
  let wrongWrites=0;const wrong=new GitHubReporter('wrong-account',async(url)=>{if(url==='https://api.github.com/user')return Response.json({login:'someone-else'});wrongWrites++;return Response.json({});});
  await assert.rejects(wrong.create(sample(),'marker'),error=>error.code==='reporter_not_authorized');assert.equal(wrongWrites,0);
});
test('Redis adapter uses atomic TTL primitives and never an in-memory fallback',async()=>{
  const {RedisStore}=await import(base+'redis.mjs');const calls=[];
  const store=new RedisStore('https://example.upstash.io','not-a-real-token',async(url,options)=>{calls.push(JSON.parse(options.body));return Response.json({result:1});});
  assert.equal(await store.rate('a',3,3600),true);assert.equal(calls[0][0],'EVAL');assert.equal(calls[0][3],'mcdiag:a');
  await store.consume('b',{reportId:'x'});assert.match(calls[1][1],/DEL/);
  assert.throws(()=>new RedisStore('http://untrusted.local','token'));
});
