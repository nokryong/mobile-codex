# Mobile Codex

## Before installing

If Play Protect blocks the APK installation, follow these steps temporarily:

1. Open **Google Play Store → profile icon → Play Protect → Settings**.
2. Turn off **Scan apps with Play Protect**.
3. Install the APK from the [official project GitHub release](https://github.com/nokryong/mobile-codex/releases).
4. Return to the same Play Protect screen and turn scanning back on.

The APK-opening app may separately require **Allow from this source**. Menu names can vary by Android version; see [Google's official Play Protect help](https://support.google.com/android/answer/2812853?hl=en).

**English** · [한국어](README.ko.md)

<p align="center"><img src="app/src/main/assets/web/codex-logo.png" alt="Mobile Codex logo" width="144" /></p>

<p>
  <img width="24%" alt="General chat" src="https://github.com/user-attachments/assets/14dd8782-58eb-4784-96bc-aaa190adfa87" />
  <img width="24%" alt="Git clone" src="https://github.com/user-attachments/assets/84b311d7-c584-4107-a09e-07862b11797d" />
  <img width="24%" alt="Skill selection" src="https://github.com/user-attachments/assets/e2ca66de-9ac6-4044-89c5-4daf41c8e308" />
  <img width="24%" alt="Image generation" src="https://github.com/user-attachments/assets/7beb3213-9051-4fc8-9051-40ff5fc45a5a" />
</p>

**Use Codex on your Android phone or tablet, with files and tools running on the device.**

Mobile Codex is an independent Android client built around the Codex app-server. It bundles an Android port of Codex, Python, Node.js, and Git, so you do not need to install Termux or keep a separate PC server running.

**0.1.27 alpha** · **ARM64 / Android 10+** · Created by [nokryong](https://github.com/nokryong)

[Download APK](https://github.com/nokryong/mobile-codex/releases) · [Builds](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml) · [Report an issue](https://github.com/nokryong/mobile-codex/issues)

> This is not an official OpenAI app. Model inference requires an internet connection and an account with Codex access. The Codex process and file/command tools run on your Android device.

## Chat and Codex

Switch with **Chat / Codex** below the sidebar logo. Chat uses the app's own composer and message view; the signed-in official web client handles delivery. Open **ChatGPT sign-in**, complete authentication, then choose **Return to Chat**. This session is separate from Codex sign-in.

Chat defaults to Instant. The reasoning slider closes after selection. Long user messages can be expanded or collapsed. Setting failures are reported rather than silently sending with another selection. Chat currently supports text delivery and the current conversation's local view; full web-history synchronization and Chat attachments are not yet supported.

## Features

| Feature | What it does |
| --- | --- |
| Projects and chats | Separate chat lists per project, general chats without a folder, saved history, streaming, interruption, and additional instructions during a task |
| Files | Browse, search, read, create, edit, move, rename, and delete files; confirm changes and keep recovery copies |
| Attachments and images | Attach multiple files, preview images, browse generated image galleries, zoom, swipe, and save originals |
| Developer tools | Bundled Python/pip, Node.js/npm/npx, and Git; enter commands and stream output |
| Change review | Inspect Git status and differences against HEAD, restore working files, and use recovery copies |
| Plugins, skills, and MCP | Install and enable plugins, connect accounts, import skill folders, and configure MCP servers |
| Accounts and limits | Save multiple ChatGPT accounts locally, switch safely with separate chat histories, and see remaining Codex limits as compact and detailed charts |
| Phone controls | Optionally use Android accessibility to open apps, read screens, tap, type, scroll, and navigate |
| Floating chat and dictation | Chat over other apps; in the main composer, dictate with live status, partial text, and Done/Cancel controls, then review the draft |
| Preferences | System/English/Korean interface, model and reasoning effort, task permissions, custom instructions, themes, and optional chat icons |
| Updates | Check public GitHub releases, verify APK integrity and signing compatibility, and open Android’s installation prompt |

The interface uses drawers and sheets on phones and multiple panels on larger screens. Light and dark themes include a translucent header, a floating composer, and draggable mobile sheets. Motion follows the device’s reduced-motion preference. Features depend on the runtime, account, Android permissions, and installed commands. A feature without a dedicated screen may still be accessible through Codex tools or configuration.

## Install

You need an **ARM64 device running Android 10 or later**, an up-to-date Android System WebView, internet access, and an account that can use Codex.

### Download the APK

Open [Releases](https://github.com/nokryong/mobile-codex/releases) and download **`mobile-codex-0.1.27-alpha-arm64.apk`** from the release assets. Open the APK and follow Android’s installation prompts. If requested, allow installation from the app you used to download/open it.

New app versions are published automatically after the `main` workflow passes its tests, builds, and original-key signature verification. Alpha versions are marked as prereleases. A published version is never silently replaced; maintainers must increment both `versionName` and `versionCode` for the next release.

You can also open a successful [Actions run](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml) and scroll to **Artifacts**:

| Artifact | Contents |
| --- | --- |
| **`mobile-codex-update-assets`** | **The small installation bundle.** Extract the ZIP to get the `.apk`, checksum, and update manifest. |
| `mobile-codex-arm64-debug` | A larger bundle with the APK, runtime metadata, and corresponding developer-tool sources. |
| `check-reports` | Test and lint reports, browser layout checks, and UI screenshots; no APK. |

Actions artifact downloads require GitHub sign-in and expire after 14 days. Public release assets remain available without that artifact sign-in requirement. An unsuccessful build does not publish a new release.

### First launch

1. Choose **Connect your ChatGPT account**. Copy the device code and complete sign-in in your browser.
2. Start a **general chat**, or use **Add project** to choose a folder. Expand a project to see its chats.
3. For shell access to a local project, enable **Settings → Tools → Allow device file access**.
4. Choose a model, reasoning effort, and permissions, then send a request. Use **`+`** for attachments, **`@`** for files/apps, and **`$`** for skills.
5. Choose **Settings → General → Language** to follow the device language or select English/Korean. Chats and file contents are not translated or reset.

The lower-left quota ring opens **Settings → Account**. From there you can add or switch ChatGPT accounts. Credentials remain in app-private storage, and each account only sees its own saved chats.

Saved chats can be opened without signing in or starting the runtime. Removing a project from the sidebar leaves its folder and chat history intact. Reconnect a folder if Android revokes access.

### Update an existing installation

Use **Settings → Updates** to check public releases. The app verifies the APK checksum, package, version, and certificate before opening Android’s installer. Installation requires your confirmation.

`main` builds use the registered original signing key and verify its certificate fingerprint. Missing or incorrect signing configuration stops publication. Older CI builds created before this configuration may have incompatible temporary debug signatures. Uninstalling the app deletes its internal chats, sign-in data, and recovery copies, so do not uninstall just to troubleshoot an update without preserving needed data.

Since 0.1.7 the application ID is `dev.mobilecodex.app`. Older builds with a different ID install as a separate app. See [updates and releases](docs/app-updates.md).

## Using the app

### Plugins, skills, and MCP

Open **Plugins · Skills · MCP** in the sidebar.

- **Plugins:** Browse marketplaces and details, install/enable plugins, and connect additional accounts when prompted.
- **Skills:** Import a folder with `SKILL.md` directly inside it. Scripts, references, and assets are copied too. Select a skill with `$` in chat.
- **MCP:** Inspect server status and tools, connect with OAuth where supported, or edit `config.toml`. Commands for stdio servers must run on Android.

### Phone controls and floating chat

In **Settings → Tools → Phone controls**, connect the accessibility service, explicitly enable controls, and start a **new chat**. Use the on-screen stop button to disable controls. They are disabled after the app process restarts.

Android 10 supports reading screen elements; Android 11+ also supports screenshots. **Screen content and images used for a task are sent to the AI service and may remain in chat history.** Accessibility permission alone does not start automation.

With the service connected, **Open floating chat** lets you chat over other apps. Floating dictation still uses the device’s separate recognition screen. Main-chat dictation stays inside the composer: tap the microphone, speak, choose **Done** or **Cancel**, review the resulting draft, then send it yourself. It never sends automatically.

Recognition uses the device’s configured speech service, which may use external servers. It is not the ChatGPT app’s speech backend; accuracy, supported languages, silence detection, and offline availability depend on that service. The app does not retain audio files. See [dictation](docs/voice-input.md), [phone controls](docs/phone-use.md), and [floating chat](docs/floating-and-changes.md).

### Images, instructions, and tools

- Generated images appear in a gallery with individual original-file downloads. Generation availability depends on the runtime and account.
- Custom instructions are saved to Codex’s global instruction file and read from the next request.
- Chat character icons can be disabled in General settings without affecting attached/generated images.
- **Local character packs:** In **Settings → General**, choose one `character-packs` root folder in the app. `Documents/MobileCodex/character-packs` is only an example; create a pack beneath the selected root, refresh the list, then select the pack. Refresh after file changes, and reselect the root if permission is lost. Each pack maps the 32 built-in state keys through `mapping.json`; PNG and WebP files must be at most 4 MiB and 2048 px on each side. See the [complete 32-state example](docs/examples/character-pack-default/README.md).
- **Settings → Tools → Check tools** verifies bundled tool execution without requiring sign-in.

## Android limitations

This is alpha software. Core workflows have user reports of successful use on physical devices, but new behavior still needs device testing. See [verification](docs/verification.md) and the [device checklist](docs/device-validation.md).

- **File access:** Android permissions apply. Full file access is not root and cannot access other apps’ private data or protected system areas. Cloud document providers use document tools rather than ordinary shell paths.
- **Approvals:** Choose **Ask**, **Auto review**, or **Allow all** beside the model in the chat composer. Auto review routes approval requests to Codex's risk reviewer; task file access remains a separate setting.
- **Task permissions:** The Android port does not provide the desktop command sandbox; the OS boundary for shell commands is the Android app’s permissions.
- **Command compatibility:** Python, JavaScript, and Android-compatible packages are supported. A compiler, JDK, Perl, and SSH client are not bundled. Arbitrary Linux/Windows executables and native extensions may not work.
- **Storage:** Shared storage can restrict execution and symbolic links. App-internal paths work better for npm installations and Git operations. General chats use an internal working folder.
- **Editing and recovery:** The built-in editor supports UTF-8 text up to 1 MiB. Document tools copy files before modification and deleted files up to 32 MiB, but cannot recover every shell change or entire deleted directory. These editor limits do not restrict ordinary Codex tools or shell commands.
- **Dedicated interfaces:** An interactive PTY, Git commit/worktree management, and scheduled automation do not yet have dedicated screens. The current terminal provides command entry and streaming output.
- **Background work:** A foreground service helps keep tasks running, but device battery management can still interrupt them.

## Build from source

Install JDK 17, Android SDK Platform 35, Build Tools 35.0.0, NDK 28.2.13676358, Python 3, and Node.js 20+. Set `ANDROID_HOME` to your SDK directory.

```sh
python3 tools/prepare_runtime.py
python3 -m pip install -r tools/requirements-devtools.txt
python3 tools/build_native.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python3 tools/prepare_devtools.py
npm ci --ignore-scripts
npm test
npx playwright install --with-deps chromium
npm run test:layout
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

<details>
<summary>Signing and physical-device tests</summary>

For a local build, provide `MOBILE_CODEX_KEYSTORE` (absolute path), `MOBILE_CODEX_KEY_ALIAS`, `MOBILE_CODEX_STORE_PASSWORD`, and `MOBILE_CODEX_KEY_PASSWORD` to use your own signing key. Otherwise, Gradle uses its local debug key. Keep keys and passwords outside the repository and use the same key for future updates.

CI uses the `MOBILE_CODEX_SIGNING_JSON` repository secret and a pinned expected certificate. Forks must configure their own key/certificate; repository copies do not inherit secrets. Never commit a signing key. See [release setup](docs/app-updates.md).

With an ARM64 device attached:

```sh
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Runtime tests use an isolated test home rather than your sign-in files. An x86 Linux CI build does not prove that Android ARM64 processes work on a physical device.

</details>

## Documentation and provenance

Additional technical documents are currently mostly in Korean.

| Document | Topic |
| --- | --- |
| [Input and attachments](docs/input-protocol.md) | File/app mentions, skills, and attachment storage |
| [Android developer tools](docs/android-devtools.md) | Python, Node.js, Git, packaging, and limitations |
| [Git runtime fix](docs/git-runtime-fix.md) | Shared-library loading and verification |
| [Updates and releases](docs/app-updates.md) | APK checks, signing compatibility, and publication |
| [Localization](docs/localization.md) | UI translations and adding languages |
| [Third-party notices](THIRD_PARTY_NOTICES.md) | Component sources and licenses |

The runtime uses [`@mmmbuto/codex-cli-termux@0.155.1`](https://github.com/DioNanos/codex-termux/tree/v0.155.1). The UI connects through the [Codex app-server JSON-RPC protocol](https://github.com/openai/codex/tree/main/codex-rs/app-server). Android native executables are bundled directly in the APK; the Termux app is not installed.

Versions and checksums are pinned in [`runtime-lock.json`](tools/runtime-lock.json) and [`devtools-lock.json`](tools/devtools-lock.json). Preparation scripts include Android packaging changes to executable names and library references. Original LICENSE/NOTICE files are retained in the APK; corresponding developer-tool sources accompany releases. Preserve these notices and sources when redistributing.

Authentication data stays in app-private storage and is excluded from Android backup/device transfer. Personal settings, logs, signing keys, credentials, and build outputs are not committed.

## License and contributions

Original Mobile Codex code and original artwork are licensed under **[GPL-3.0-only](LICENSE)**. Copyright © 2026 nokryong and contributors. Third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). This license does not grant rights to third-party trademarks.

Bug reports, patches, and translation improvements are welcome. Contributions to the original project code are provided under GPL-3.0-only. Please report your device model, Android version, app version, reproduction steps, and logs with credentials and personal content removed.
