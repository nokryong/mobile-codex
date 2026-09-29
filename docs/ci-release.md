# CI and independent releases

Both repositories (`nokryong/mobile-codex` and `SeeUSoon93/mobile-codex`) run UI/source tests first.
Only a successful `checks` job permits Android setup and the APK build.
Browser suites stop at the first failure; opt in to collecting every suite
with `MOBILE_CODEX_COLLECT_ALL_LAYOUT_FAILURES=1` for manual diagnosis.
Android tasks stop on failure too; report uploads are the only always-run steps.

Each repository builds and publishes its own main-branch commit after full
validation, for both pushes and manual runs. The private repository does not
wait for a public release or copy its APK. Release tags, assets and notes belong
to the repository where the workflow runs. Both builds reuse the nokryong OAuth
app through the public `MOBILE_CODEX_GITHUB_CLIENT_ID` repository variable;
no separate OAuth app is needed for the private repository.

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

A push that only fixes Android test sources can reuse a completed main-branch
UI/source checks job. The gate compares the Git blob identities of all checked
inputs and the exact checks-job commands, and verifies that the original JS,
Python and browser steps succeeded. PRs cannot reuse results. Android tests,
Gradle build, lint, signing and release validation always run again. The small
reuse gate and its regression tests run every time; their routing files and this
CI document are excluded from the UI input fingerprint. A reused job cannot be
used as evidence for another reuse: the original successful validation steps
must still be present.

Native Java source changes retain the browser result only: browser fixtures mock
the native bridge, so their input fingerprint excludes Java sources. JS/source
and Python checks still run for native source changes, and Android validation
checks the actual packaged asset route. Any web asset or browser harness change
invalidates browser reuse.

Actions history cleanup runs after Android runs complete and daily. It removes
superseded main-branch failed/cancelled/timed-out runs and old cleanup runs, while
preserving active runs, successful builds, the latest unresolved failure and the
latest original successful browser evidence needed for reuse. Cleanup changes
are maintenance-only; publish them with `[skip ci]` to avoid restarting Android
release work. The one-time maintenance branch runs cleanup without an APK build
and removes itself afterward.
