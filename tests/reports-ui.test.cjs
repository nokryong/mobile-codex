const test=require('node:test'),assert=require('node:assert/strict');
const create=require('../app/src/main/assets/web/reports.js');
function fixture(call){const elements=new Map();const document={getElementById(id){if(!elements.has(id))elements.set(id,{textContent:'',hidden:false,disabled:false,dataset:{},addEventListener(){}});return elements.get(id);}};let visible=false;const ui=create({document,call,t:x=>x,show(){visible=true;},close(){visible=false;}});return{ui,e:id=>document.getElementById(id),visible:()=>visible};}
const preview={status:'preview',reportId:'ephemeral-report-id',payload:{errorCode:'initialize_timeout',stdoutBytes:0}};
test('preview does not send, exact payload uses textContent and cancellation before consent sends nothing',async()=>{
 const calls=[],fx=fixture(async(action,args)=>{calls.push({action,args});return preview;});await fx.ui.open();assert.equal(fx.e('report-payload').textContent,JSON.stringify(preview.payload,null,2));await fx.ui.cancel();assert.equal(fx.visible(),false);assert.deepEqual(calls.map(c=>c.action),['reports.preview']);
});
test('duplicate click suppressed, immutable preview ID and explicit consent only',async()=>{
 let release,count=0;const fx=fixture(async(action,args)=>{if(action==='reports.preview')return preview;count++;assert.deepEqual(args,{reportId:preview.reportId,consent:true});return new Promise(resolve=>release=resolve);});await fx.ui.open();const sending=fx.ui.send();await fx.ui.send();assert.equal(count,1);assert.equal(fx.e('report-send').disabled,true);release({status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19'});await sending;assert.equal(fx.e('report-result').hidden,false);assert.equal(fx.e('report-send').disabled,true);
});
test('offline failure allows retry with same ID, does not include exception details',async()=>{
 const ids=[];let attempts=0;const fx=fixture(async(action,args)=>{if(action==='reports.preview')return preview;ids.push(args.reportId);if(++attempts===1)throw Error('secret/path/token');return{status:'sent',url:'https://github.com/nokryong/mobile-codex/issues/19',grouped:true};});await fx.ui.open();await fx.ui.send();assert.equal(fx.e('report-send').disabled,false);assert.equal(fx.e('report-status').textContent.includes('secret'),false);await fx.ui.send();assert.deepEqual(ids,[preview.reportId,preview.reportId]);
});
test('cancel in flight preserves retry snapshot and reports uncertain submission',async()=>{
 let finish;const actions=[];const fx=fixture(async(action)=>{actions.push(action);if(action==='reports.preview')return preview;if(action==='reports.send')return new Promise(resolve=>finish=resolve);return{status:'cancelled'};});await fx.ui.open();const work=fx.ui.send();await fx.ui.cancel();assert.ok(actions.includes('reports.cancel'));assert.equal(fx.visible(),true);finish({status:'cancelled'});await work;assert.equal(fx.e('report-send').disabled,false);
});
test('invalid or foreign result links are never offered',async()=>{
 const fx=fixture(async(action)=>action==='reports.preview'?preview:{status:'sent',url:'https://evil.test/steal'});await fx.ui.open();await fx.ui.send();assert.equal(fx.e('report-result').hidden,true);assert.equal(fx.e('report-send').disabled,false);
});
