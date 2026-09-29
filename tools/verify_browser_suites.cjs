/* Independent suites all run so one failure does not hide later diagnostics. */
const {spawnSync}=require('node:child_process');
const path=require('node:path');
let failed=false;
for(const suite of ['verify_ui_layout.cjs','verify_character_pack_browser.cjs','verify_project_identity_browser.cjs','verify_chat_switch_browser.cjs','verify_text_size_browser.cjs']) {
 console.log('\nBrowser suite: '+suite);
 const result=spawnSync(process.execPath,[path.join(__dirname,suite)],{stdio:'inherit'});
 if(result.error)console.error(result.error.message);
 if(result.status!==0)failed=true;
}
process.exitCode=failed?1:0;
