# CI and paired releases

The public repository (`nokryong/mobile-codex`) runs UI/source tests first.
Only a successful `checks` job permits Android setup and the APK build.
Browser suites stop at the first failure; opt in to collecting every suite
with `MOBILE_CODEX_COLLECT_ALL_LAYOUT_FAILURES=1` for manual diagnosis.
Android tasks stop on failure too; report uploads are the only always-run steps.

The private main branch (`SeeUSoon93/mobile-codex`) does not rebuild the APK.
It waits for the canonical release with exactly the same Git tree, checks all
six assets against their sizes and SHA-256 digests, inspects APK identity and
the pinned signing certificate, and publishes those same bytes and notes.
A failed/cancelled canonical run for that tree ends the wait early. Unrelated
failures do not. There is also a bounded timeout.

Prepared runtimes and corresponding-source archives use exact content keys.
Native/package inputs and packaging scripts are part of those keys. Prepared
runtime validation still runs on cache hits. Gradle's dependency/build caches
are reused. A cache miss still needs the full preparation and has no promised
completion time.

Normal releases upload APK/source bytes to Releases only, not duplicate Actions
artifacts. Explicit `apk_only` manual runs retain one uncompressed test artifact,
still require UI/source checks, skip Android unit/lint suites, and never publish.
Pull requests keep validation/build checks and never sign or publish releases.

Published release versions remain immutable. Bump the version for a new release;
do not replace an existing official APK to repair CI or UI changes.
