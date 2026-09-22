# Third-party notices

Mobile Codex is an independent client; it is not an official OpenAI Android application.

The APK bundles a pinned Android port of Codex:
- Source: https://github.com/DioNanos/codex-termux/tree/v0.155.1
- Upstream: https://github.com/openai/codex
- Package: `@mmmbuto/codex-cli-termux@0.155.1`
- The package's original `LICENSE` and `NOTICE` are copied into `assets/runtime/` at build time.
- A fixed-width helper executable name change is applied for Android APK native-library packaging. See `tools/prepare_runtime.py` and README.
- The original archive integrity and the resulting packaged file hashes are recorded separately.

AndroidX Core 1.15.0 (Apache-2.0) supplies window-inset compatibility across Android versions. Java desugaring libraries are provided by the Android Open Source Project. Test-only dependencies include JUnit, AndroidX Test, Robolectric, org.json, and jsdom, as declared in the Gradle/npm manifests. Their upstream license terms apply.

## Original project code

Original Mobile Codex code and original artwork are GPL-3.0-only (see LICENSE), copyright 2026 nokryong and contributors. Third-party components are not relicensed by this declaration. Third-party trademarks remain with their owners. The launcher uses an original antler-and-terminal design.

## Bundled developer tools

Python, Node.js, Git, and their runtime libraries are pinned in `tools/devtools-lock.json`. These components have different upstream licenses, including permissive and copyleft terms. The preparation scripts preserve package license notices in the APK. `devtools-corresponding-source.zip` accompanies releases with upstream source archives, pinned Termux build recipes, patches, packaging scripts, and their notices. `SOURCES.json` maps each source archive to its original URL and SHA-256. Keep the corresponding sources and notices available when redistributing an APK.

App license notices are also packaged under `assets/legal/`.
