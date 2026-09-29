const {test}=require('node:test');
const assert=require('node:assert/strict');
const {runSuites}=require('../tools/verify_browser_suites.cjs');

test('browser runner stops immediately at a failed suite',()=>{
 let calls=0;
 assert.equal(runSuites(()=>({status:++calls===2?1:0}),false),1);
 assert.equal(calls,2);
});
test('browser runner stops on a failed process launch',()=>{
 let calls=0;
 assert.equal(runSuites(()=>{calls++;return {status:null,error:new Error('fixture launch failure')};},false),1);
 assert.equal(calls,1);
});
test('all diagnostic suites require explicit opt in',()=>{
 let calls=0;
 assert.equal(runSuites(()=>{calls++;return {status:1};},true),1);
 assert.equal(calls,5);
});
test('all suites still run when each succeeds',()=>{
 let calls=0;
 assert.equal(runSuites(()=>{calls++;return {status:0};},false),0);
 assert.equal(calls,5);
});
