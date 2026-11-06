const test=require('node:test'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
function verify(mode) {
 const script=`
 import {proofValid} from './services/diagnostic-relay/lib/schema.mjs';
 const mode=${JSON.stringify(mode)}, reports=new Map(); let submissions=0;
 process.argv[2]='https://mobile-codex-diagnostic-relay.vercel.app';
 globalThis.fetch=async(url, options={})=>{
   const path=new URL(url).pathname;
   if(path==='/api/health') return Response.json({ready:true});
   if(path==='/api/challenge') return Response.json({challengeId:'a'.repeat(48),difficulty:16});
   if(path==='/api/reports') {
     const {challengeId,proof,...report}=JSON.parse(options.body);
     if(!proofValid(challengeId,proof)) throw Error('invalid work');
     submissions++;
     const number=report.schema===2?123:124, grouped=reports.has(number);
     if(report.schema===2 && (report.appVersion!=='0.3.8' || report.stdoutBytes!==null || report.processAlive!==null)) throw Error('v2 missing');
     if(report.schema===1 && (report.appVersion!=='0.3.6' || report.stdoutBytes!==0)) throw Error('v1 missing');
     reports.set(number,report); return Response.json({url:'https://github.com/nokryong/mobile-codex/issues/'+number,grouped});
   }
   const number=Number(path.split('/').at(-1)), report={...reports.get(number)};
   report.reportId='11111111-1111-4111-8111-111111111111'; // Previously deduplicated sample.
   if(mode==='mismatch') report.appVersion='0.0.0';
   const body='\x60\x60\x60json\\n'+JSON.stringify(report)+'\\n\x60\x60\x60';
   if(path.endsWith('/comments')) return Response.json([{body:globalThis.duplicateBody}]);
   globalThis.duplicateBody=body;
   return Response.json({body,comments:mode==='duplicate'?1:0});
 };
 await import('./tools/verify_diagnostic_relay.mjs');
 if(submissions!==3) throw Error('unexpected submissions');
 `;
 return spawnSync(process.execPath,['--input-type=module','-e',script],{encoding:'utf8',timeout:15000});
}
test('live verifier checks v2 nulls, retry and legacy samples including earlier deduplicated receipts',()=>{
 const result=verify('ok'); assert.equal(result.status,0,result.stderr); assert.match(result.stdout,/"nullMeasurements":true/); assert.match(result.stdout,/"legacyCompatible":true/);
});
test('live verifier rejects mismatched or duplicated public diagnostics',()=>{
 for(const mode of ['mismatch','duplicate']) { const result=verify(mode); assert.notEqual(result.status,0); assert.match(result.stderr,/Public payload mismatch or duplicate/); }
});
