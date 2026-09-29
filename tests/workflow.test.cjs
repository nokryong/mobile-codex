const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const workflow=fs.readFileSync('.github/workflows/android.yml','utf8');
// Inspect this repository's deliberately simple two-space job layout.
function job(name) {
 const match=workflow.match(new RegExp('^  '+name+':\\n([\\s\\S]*?)(?=^  [a-z][a-z_]*:|$(?![\\s\\S]))','m'));
 assert.ok(match,'missing workflow job '+name);
 return match[1];
}
test('UI and source checks gate all Android work without failure overrides',()=>{
 const checks=job('checks'),build=job('build');
 assert.match(checks,/npm test/);
 assert.match(checks,/npm run test:layout/);
 assert.match(checks,/unittest discover/);
 assert.doesNotMatch(checks,/setup-android|gradlew|prepare_runtime|sdkmanager/);
 assert.match(build,/^    needs: checks$/m);
 assert.doesNotMatch(workflow,/!cancelled\(\)|continue-on-error: true|--continue/);
 const androidStep=build.slice(build.indexOf('- name: Build and check Android app'),build.indexOf('- name: Verify Android'));
 assert.doesNotMatch(androidStep,/always\(|failure\(/);
});
test('paired main release mirrors verified assets without a duplicate build',()=>{
 const mirror=job('mirror');
 assert.match(mirror,/github.repository == 'SeeUSoon93\/mobile-codex'/);
 assert.match(mirror,/github.ref == 'refs\/heads\/main'/);
 assert.match(mirror,/sync_release_assets.py --without-build/);
 assert.match(mirror,/publish_release.py/);
 assert.doesNotMatch(mirror,/gradlew|prepare_runtime|prepare_devtools|build_native|ndk;|SIGNING_JSON|setup-node/);
 for(const name of ['checks','build'])
  assert.match(job(name),/github.repository != 'SeeUSoon93\/mobile-codex'/);
});
test('cached packaging outputs are exact-keyed and still verified',()=>{
 const build=job('build');
 const cache=build.slice(build.indexOf('- name: Restore exact prepared runtime'),build.indexOf('- name: Restore corresponding tool sources'));
 for(const input of ['runtime-lock.json','devtools-lock.json','prepare_runtime.py','prepare_devtools.py','build_native.py','tools/native/**','requirements-devtools.txt'])
  assert.ok(cache.includes(input),input);
 assert.doesNotMatch(cache,/restore-keys/);
 assert.match(build,/prepare_devtools.py --verify/);
 assert.match(build,/test_real_prepared_runtime_contract/);
 assert.match(build,/cache: gradle/);
});
test('normal releases do not upload duplicate APK artifacts',()=>{
 const build=job('build');
 const upload=build.slice(build.indexOf('- name: Upload explicitly requested test APK only'),build.indexOf('- name: Publish canonical'));
 assert.match(upload,/if: github.event_name != 'pull_request' && inputs.apk_only/);
 assert.match(upload,/compression-level: 0/);
 assert.doesNotMatch(workflow,/name: mobile-codex-arm64-debug|name: mobile-codex-update-assets/);
});
