/* CI stops at the first failed suite; opt in to collecting all diagnostics. */
const {spawnSync}=require('node:child_process');
const path=require('node:path');
function runSuites(execute=spawnSync, collectAll=process.env.MOBILE_CODEX_COLLECT_ALL_LAYOUT_FAILURES==='1') {
 let failed=false;
 for(const suite of ['verify_chat_switch_browser.cjs','verify_text_size_browser.cjs','verify_ui_layout.cjs','verify_character_pack_browser.cjs','verify_project_identity_browser.cjs']) {
  console.log('\nBrowser suite: '+suite);
  const result=execute(process.execPath,[path.join(__dirname,suite)],{stdio:'inherit'});
  if(result.error)console.error(result.error.message);
  if(result.error || result.status!==0) {
   failed=true;
   if(!collectAll)break;
  }
 }
 return failed?1:0;
}
module.exports={runSuites};
if(require.main===module)process.exitCode=runSuites();
