const test = require('node:test'), assert = require('node:assert/strict');
const {client} = require('../packages/desktop-companion/github-sync.cjs');
const p = require('../packages/desktop-companion/projects.cjs');
const config = {schemaVersion:1,repository:'user/sync',repositoryId:123,branch:'main'};
const created = (id,name) => p.add(p.empty(),'created',id,name,'device_test');
function mock() {
  let bundle = null, revision = 0, race = null;
  const info = {id:123,full_name:'user/sync',private:true,default_branch:'main',size:0,permissions:{push:true}}, writes=[];
  const sha = () => String(revision).padStart(40,'0');
  const transport = (method,endpoint,body) => {
    if (endpoint === 'repos/user/sync') return info;
    if (method === 'GET') {
      if (!bundle) throw Object.assign(new Error('missing'),{status:404});
      const raw = JSON.stringify(bundle); return {sha:sha(),type:'file',encoding:'base64',size:Buffer.byteLength(raw),content:Buffer.from(raw).toString('base64')};
    }
    if (race) {bundle = race;race=null;revision++;throw Object.assign(new Error('conflict'),{status:409});}
    assert.equal(body.sha, bundle ? sha() : undefined); writes.push(body); bundle = p.parse(Buffer.from(body.content,'base64').toString('utf8')); revision++; info.size=1;
    return {commit:{sha:sha()}};
  };
  return {client:client(transport),info,writes,setRace:value=>{race=value;},bundle:()=>bundle};
}
test('private repository exchange is selective, replay safe, and contains no binding state',()=>{
  const m=mock();assert.deepEqual(m.client.connect('user/sync'),config);
  const history=p.union(created('proj_a','A'),created('proj_b','B'));
  assert.equal(m.client.push(config,history,['proj_a']).changed,true);
  assert.deepEqual(p.reduce(m.bundle()).projects.map(x=>x.projectId),['proj_a']);
  assert.equal(m.client.push(config,history,['proj_a']).changed,false);assert.equal(m.writes.length,1);
  assert.equal(m.client.pull(config).bundle.events.length,1);
  assert.doesNotMatch(JSON.stringify(m.bundle()),/localPath|bindings|auth|token/);
});
test('concurrent remote commit is merged on retry without deleting either writer',()=>{
  const m=mock();m.setRace(created('proj_b','Other device'));
  m.client.push(config,created('proj_a','This device'),['proj_a']);
  assert.deepEqual(p.reduce(m.bundle()).projects.map(x=>x.projectId),['proj_a','proj_b']);assert.equal(m.writes.length,1);
});
test('public repository, changed identity, credentials and malformed remote state cannot be written',()=>{
  const m=mock(),history=created('proj_a','A');
  m.info.private=false;assert.throws(()=>m.client.connect('user/sync'));assert.throws(()=>m.client.push(config,history,['proj_a']));
  m.info.private=true;m.info.id=999;assert.throws(()=>m.client.pull(config));m.info.id=123;m.info.default_branch='other';assert.throws(()=>m.client.push(config,history,['proj_a']));
  m.info.default_branch='main';assert.throws(()=>m.client.push(config,created('proj_s','api_key=secret'),['proj_s']));assert.equal(m.writes.length,0);
  const bad=client((method,endpoint)=>endpoint==='repos/user/sync'?m.info:{type:'file',encoding:'base64',sha:'a'.repeat(40),size:2,content:Buffer.from('{}').toString('base64')});
  assert.throws(()=>bad.push(config,history,['proj_a']));
});
