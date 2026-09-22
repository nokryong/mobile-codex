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
