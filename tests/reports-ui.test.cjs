const test=require('node:test'),assert=require('node:assert/strict');
const create=require('../app/src/main/assets/web/reports.js');
function fixture(call){const elements=new Map();const document={getElementById(id){if(!elements.has(id))elements.set(id,{textContent:'',hidden:false,disabled:false,dataset:{},addEventListener(){}});return elements.get(id);}};let visible=false;const ui=create({document,call,t:x=>x,show(){visible=true;},close(){visible=false;}});return{ui,e:id=>document.getElementById(id),visible:()=>visible};}
const preview={status:'preview',reportId:'ephemeral-report-id',payload:{errorCode:'initialize_timeout',stdoutBytes:0}};
test('schema 2 preview displays null measurements exactly and sends only the consented receipt',async()=>{
 const payload=require('../app/src/test/resources/diagnostic-v2.json'),calls=[];
 const fx=fixture(async(action,args)=>{calls.push({action,args});return action==='reports.preview'?{status:'preview',reportId:payload.reportId,payload}:{status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/100'};});
 await fx.ui.open(); assert.equal(fx.e('report-payload').textContent,JSON.stringify(payload,null,2));
 assert.match(fx.e('report-payload').textContent,/"stdoutBytes": null/);
 await fx.ui.send(); assert.deepEqual(calls[1],{action:'reports.send',args:{reportId:payload.reportId,consent:true}});
});
test('preview does not send, exact payload uses textContent and cancellation before consent sends nothing',async()=>{
 const calls=[],fx=fixture(async(action,args)=>{calls.push({action,args});return preview;});await fx.ui.open();assert.equal(fx.e('report-payload').textContent,JSON.stringify(preview.payload,null,2));await fx.ui.cancel();assert.equal(fx.visible(),false);assert.deepEqual(calls.map(c=>c.action),['reports.preview','reports.cancel']);
});
test('duplicate click suppressed, immutable preview ID and explicit consent only',async()=>{
 let release,count=0;const fx=fixture(async(action,args)=>{if(action==='reports.preview')return preview;count++;assert.deepEqual(args,{reportId:preview.reportId,consent:true});return new Promise(resolve=>release=resolve);});await fx.ui.open();const sending=fx.ui.send();await fx.ui.send();assert.equal(count,1);assert.equal(fx.e('report-send').disabled,true);release({status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19'});await sending;assert.equal(fx.e('report-result').hidden,false);assert.equal(fx.e('report-send').disabled,true);
});
test('offline failure allows retry with same ID, does not include exception details',async()=>{
 const ids=[];let attempts=0;const fx=fixture(async(action,args)=>{if(action==='reports.preview')return preview;ids.push(args.reportId);if(++attempts===1)throw Error('secret/path/token');return{status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19',grouped:true};});await fx.ui.open();await fx.ui.send();assert.equal(fx.e('report-send').disabled,false);assert.equal(fx.e('report-status').textContent.includes('secret'),false);await fx.ui.send();assert.deepEqual(ids,[preview.reportId,preview.reportId]);
});
test('cancel in flight revokes consent and requires a fresh preview before retry',async()=>{
 let finish;const actions=[];const fx=fixture(async(action)=>{actions.push(action);if(action==='reports.preview')return preview;if(action==='reports.send')return new Promise(resolve=>finish=resolve);return{status:'cancelled'};});await fx.ui.open();const work=fx.ui.send();await fx.ui.cancel();assert.ok(actions.includes('reports.cancel'));assert.equal(fx.visible(),true);finish({status:'cancelled'});await work;assert.equal(fx.e('report-send').disabled,true);await fx.ui.open();assert.equal(fx.e('report-send').disabled,false);
});
test('invalid or foreign result links are never offered',async()=>{
 const fx=fixture(async(action)=>action==='reports.preview'?preview:{status:'sent',url:'https://evil.test/steal'});await fx.ui.open();await fx.ui.send();assert.equal(fx.e('report-result').hidden,true);assert.equal(fx.e('report-send').disabled,false);
});
const launchers = ['report-open', 'report-login-open', 'report-error-open'];
function assertLaunchers(fx, hidden) { for (const id of launchers) assert.equal(fx.e(id).hidden, hidden, id); }
test('all report launchers start hidden and follow native current-error availability', async () => {
 const fx = fixture(async () => ({available:true}));
 assertLaunchers(fx, true);
 await fx.ui.refresh(); assertLaunchers(fx, false);
 fx.ui.available({available:false}); assertLaunchers(fx, true);
});
test('late initial availability cannot overwrite a newer recovery or error event', async () => {
 for (const current of [true, false]) {
  let resolve; const fx = fixture(() => new Promise(done => resolve = done));
  const refresh = fx.ui.refresh(); fx.ui.available({available:current});
  resolve({available:!current}); await refresh; assertLaunchers(fx, !current);
 }
});
test('recovery clears consented preview and prevents a stale send', async () => {
 const actions = [], fx = fixture(async action => { actions.push(action); return preview; });
 fx.ui.available({available:true}); await fx.ui.open();
 fx.ui.available({available:false}); await fx.ui.send();
 assertLaunchers(fx, true); assert.equal(fx.e('report-payload').textContent, '');
 assert.equal(fx.e('report-send').disabled, true); assert.deepEqual(actions, ['reports.preview']);
});
test('recovery invalidates a pending preview response', async () => {
 let resolve; const fx = fixture(() => new Promise(done => resolve = done));
 fx.ui.available({available:true}); const opening = fx.ui.open();
 fx.ui.available({available:false}); resolve(preview); await opening;
 assertLaunchers(fx, true); assert.equal(fx.e('report-payload').textContent, '');
 assert.equal(fx.e('report-send').disabled, true);
});
test('native unavailable preview hides launchers after a missed recovery event', async () => {
 const fx = fixture(async () => ({status:'unavailable'}));
 fx.ui.available({available:true}); await fx.ui.open(); assertLaunchers(fx, true);
});
test('successful send clears snapshot and launchers, retaining only the result link', async () => {
 const fx = fixture(async action => action === 'reports.preview' ? preview : {status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19'});
 fx.ui.available({available:true}); await fx.ui.open(); await fx.ui.send();
 assertLaunchers(fx, true); assert.equal(fx.e('report-payload').textContent, '');
 assert.equal(fx.e('report-result').hidden, false); assert.equal(fx.e('report-send').disabled, true);
});
test('sending an older report does not hide a newer current error', async () => {
 let resolve; const fx = fixture(async action => action === 'reports.preview' ? preview : new Promise(done => resolve = done));
 fx.ui.available({available:true}); await fx.ui.open(); const sending = fx.ui.send();
 fx.ui.available({available:true}); resolve({status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19'}); await sending;
 assertLaunchers(fx, false); assert.equal(fx.e('report-send').disabled, true);
});
test('recovery during an uncertain submission does not re-enable stale retry', async () => {
 let resolve; const fx = fixture(async action => action === 'reports.preview' ? preview : new Promise(done => resolve = done));
 await fx.ui.open(); const sending = fx.ui.send(); fx.ui.available({available:false});
 resolve({status:'failed'}); await sending;
 assertLaunchers(fx, true); assert.equal(fx.e('report-send').disabled, true);
});

test('reopening waits for cancellation so native revocation cannot invalidate a new preview', async () => {
 let finish; const actions = [], fx = fixture(async action => {
  actions.push(action); return action === 'reports.cancel' ? new Promise(resolve => finish = resolve) : preview;
 });
 await fx.ui.open(); const cancel = fx.ui.cancel(); const reopen = fx.ui.open();
 assert.deepEqual(actions, ['reports.preview', 'reports.cancel']);
 finish({status:'cancelled'}); await cancel; await reopen;
 assert.deepEqual(actions, ['reports.preview', 'reports.cancel', 'reports.preview']);
 assert.equal(fx.e('report-send').disabled, false);
});
test('unavailable event removes a stale issue result link', async () => {
 const fx = fixture(async action => action === 'reports.preview' ? preview : {status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19'});
 await fx.ui.open(); await fx.ui.send(); fx.ui.available({available:false});
 assert.equal(fx.e('report-result').hidden, true); assert.equal(fx.e('report-result').dataset.url, undefined);
});

test('recovering a preview clears its consent while keeping a newer unrelated error available', async () => {
 const fx = fixture(async () => preview);
 fx.ui.available({available:true}); await fx.ui.open();
 fx.ui.available({available:true,previewInvalidated:true});
 assertLaunchers(fx, false); assert.equal(fx.e('report-payload').textContent, '');
 assert.equal(fx.e('report-send').disabled, true);
});
