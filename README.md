# Mobile Codex

**English** · [한국어](README.ko.md)

<p align="center"><img src="app/src/main/assets/web/codex-logo.png" alt="Mobile Codex logo" width="144" /></p>

<p>
  <img width="24%" alt="General chat" src="https://github.com/user-attachments/assets/14dd8782-58eb-4784-96bc-aaa190adfa87" />
  <img width="24%" alt="Git clone" src="https://github.com/user-attachments/assets/84b311d7-c584-4107-a09e-07862b11797d" />
  <img width="24%" alt="Skill selection" src="https://github.com/user-attachments/assets/e2ca66de-9ac6-4044-89c5-4daf41c8e308" />
  <img width="24%" alt="Image generation" src="https://github.com/user-attachments/assets/7beb3213-9051-4fc8-9051-40ff5fc45a5a" />
</p>

## Codex on Android, without a separate PC

Mobile Codex is an independent Android client built around the Codex app-server. It runs an Android port of Codex on the phone or tablet and bundles Python, Node.js and Git, so normal project work does not require Termux or a separate PC server.

**Current release: 0.2.7** · **ARM64** · **Android 10+** · Codex runtime **0.155.1**

[Download APK](https://github.com/nokryong/mobile-codex/releases/tag/v0.2.7) · [All releases](https://github.com/nokryong/mobile-codex/releases) · [Builds](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml) · [Issues](https://github.com/nokryong/mobile-codex/issues)

> Mobile Codex is not an official OpenAI app. Model inference still requires an internet connection and an account with Codex access. File tools, command execution and the bundled Codex process run on the Android device.

## What 0.2.7 includes

- **Chat / Codex switch:** keep the native Codex workspace and the signed-in ChatGPT web surface in one app and switch between them from the sidebar.
- **Ask Pro:** choose **+ → Ask Pro** to request one focused Pro consultation during the next Codex request. The answer returns to the same Codex task as a tool result.
- **Fast mode:** opt in on supported Codex models with the lightning button. It is off by default.
- **Projects and local files:** connect Android folders, keep chats grouped by project, browse and edit files, review changes and keep recovery copies.
- **On-device developer tools:** bundled Python/pip, Node.js/npm/npx and Git, plus an optional Arch Linux ARM environment.
- **Plugins, skills and MCP:** install plugins, import skill folders and configure MCP servers from the app.
- **Phone controls:** optional Android accessibility tools can read the current screen and perform taps, typing and scrolling when explicitly enabled.
- **Floating chat and dictation:** keep a chat over other apps and dictate drafts before reviewing and sending them.
- **Multiple accounts and usage:** keep local ChatGPT account profiles, switch accounts and inspect Codex usage limits.
- **Signed self-update path:** public main builds are verified, signed and published to GitHub Releases when the workflow succeeds.

See [0.2.7 release notes](docs/releases/0.2.7.md).

## Chat and Codex

The **Chat / Codex** switch beside the sidebar logo changes surfaces without throwing away the Codex screen.

**Codex mode** is the native Mobile Codex workspace. It owns project folders, local files, command execution, approvals, skills, MCP, images and phone tools.

**Chat mode** opens the official ChatGPT website inside an isolated WebView. Its web login, model picker, projects and conversation history remain part of the ChatGPT web session. The remote page does not receive Mobile Codex's native JavaScript bridge.

### Ask Pro

Choose **+ → Ask Pro** before sending a Codex request. The selection only arms the next request; it does not contact Pro immediately. Codex prepares a focused prompt, the app verifies the Pro selection in the signed-in ChatGPT web surface, sends it once, then returns the review to the same Codex task.

The consultation does not replace the selected Codex model or its permissions. Codex remains responsible for applying and verifying any change. Failed or ambiguous Pro selection is not silently resent.

## Projects and cross-device metadata

Android projects keep a stable project identity separate from each device's local folder binding. You can merge matching project identities explicitly, reconnect folders, rename projects and move project metadata between devices.

In **0.2.7, Android does not connect directly to GitHub for project sync.** Android exchanges project identity metadata through JSON export/import. This exchange contains project IDs, names and merge history, not source files, chats, credentials or local paths.

The optional [desktop companion](packages/desktop-companion) can import/export the same format and can sync that metadata through a private GitHub repository:

```sh
node packages/desktop-companion/cli.cjs init
node packages/desktop-companion/cli.cjs sync connect OWNER/REPO
node packages/desktop-companion/cli.cjs sync push PROJECT_ID
node packages/desktop-companion/cli.cjs sync pull
```

See the [project transfer guide](docs/project-transfer.ko.md).

The removed experimental Codex Cloud task panel is not part of 0.2.7. The bundled Codex CLI can submit one-shot cloud tasks but cannot continue an existing cloud task, so Mobile Codex does not present that as a full cross-device continuation feature.

## Main features

| Area | Current behavior |
| --- | --- |
| Conversations | General chats and per-project chats, streaming, interruption and steering while a task is running |
| Models | Account-provided Codex models, reasoning effort, approvals, task permissions and optional Fast |
| Pro consultation | One explicit Pro consultation can be attached to the next Codex request |
| Files | Browse, search, read, create, edit, rename, move and delete; recovery copies for supported app edits |
| Attachments / images | Multiple attachments, image previews, generated-image gallery, zoom and original-file saving |
| Terminal | Run commands with streamed output using bundled Android-native development tools |
| Linux | Optional Arch Linux ARM environment mounted to the current project at `/workspace` |
| Changes | Inspect Git status/diffs and restore supported working-tree changes or recovery copies |
| Plugins / Skills / MCP | Install and enable plugins, import `SKILL.md` folders and configure MCP servers |
| Phone tools | Opt-in accessibility control for reading, tapping, typing, scrolling and navigation |
| Voice | Main-composer dictation with Done/Cancel before send; floating chat support |
| Accounts | Multiple local account profiles, switching and usage-limit display |
| Updates | Check GitHub Releases, verify the APK and open Android's package installer |

## Install

Requirements:

- ARM64 Android device
- Android 10 or later
- Current Android System WebView
- Internet access
- ChatGPT account with Codex access

Download **`mobile-codex-0.2.7-arm64.apk`** from the [0.2.7 release](https://github.com/nokryong/mobile-codex/releases/tag/v0.2.7), then open it with Android's package installer. Android may require **Allow from this source** for the browser or file manager used to open the APK.

If Android or Play Protect shows a warning for a sideloaded build, verify that the APK came from this repository's Release page and compare the included SHA-256 metadata before deciding whether to install it.

### First launch

1. Connect a ChatGPT account with the device-code login flow.
2. Start a general chat or add a project folder.
3. If shell access to shared storage is needed, grant the requested file access in **Settings → Tools**.
4. Choose model, reasoning effort, approval mode and task permissions.
5. Use **+** for attachments / Ask Pro, **@** for files and apps, and **$** for skills.

Saved local chats can be opened without starting Codex. Removing a project entry does not delete the underlying folder.

## Updates and releases

A normal push to `main` triggers the Android workflow. A successful release build:

1. runs or reuses the required source/UI checks,
2. builds the ARM64 APK,
3. verifies the configured release signing key,
4. prepares APK metadata and SHA-256,
5. publishes the matching version to GitHub Releases.

Published release assets are treated as immutable. The next public release must increment both `versionName` and `versionCode`.

The application ID is `dev.mobilecodex.app`. Updating an existing installation requires a compatible signing certificate. Uninstalling the app removes app-private chats, account data and recovery copies.

## Optional Linux environment

Mobile Codex can download an Arch Linux ARM root filesystem on demand. When enabled, Codex and the terminal can run Linux commands through the app's Linux launcher. The current project is exposed as `/workspace`; Linux packages and the guest home remain in app-private storage.

The Linux environment is optional and separate from the APK. Removing it does not delete connected project folders.

See [Linux runtime](docs/linux-runtime.md).

## Phone controls and privacy

Phone control is disabled until the user enables the Mobile Codex accessibility service and explicitly turns control on. Depending on Android version, the app can read UI elements, capture supported screenshots, tap, type and scroll for the active task.

Screen content or screenshots used by a task can be sent to the AI service and can become part of the conversation history. Accessibility permission by itself does not start automation, and control can be stopped from the on-screen control or settings.

ChatGPT/Codex credentials, project folder bindings and recovery data are kept in app-private storage and excluded from Android backup/device-transfer rules where configured. Project JSON exchange deliberately excludes credentials and local absolute paths.

## Android limitations

- The app is not root. Android storage and app sandbox rules still apply.
- Shared-storage folders can have execution and symlink restrictions; app-private storage is more predictable for package managers and Git internals.
- The built-in text editor is for UTF-8 text and has size limits; shell/Codex tools are separate.
- The Android port does not provide the same command sandbox as desktop Codex. Task permissions and Android OS permissions are the effective boundary.
- Some packages expect desktop Linux binaries or services and will not work directly on Android; use the optional ARM64 Linux environment when appropriate.
- Background execution can still be interrupted by device battery management.
- Web-only ChatGPT capabilities depend on Android WebView support.
- Native Codex session state is not transparently migrated between Android and desktop; project metadata exchange is not full session sync.

See [verification notes](docs/verification.md) for automated versus physical-device coverage.

## Build from source

Requirements: JDK 17, Android SDK 35, NDK 28.2.13676358, Python 3 and Node.js 22.

```sh
python3 tools/prepare_runtime.py
python3 tools/build_native.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python3 tools/prepare_devtools.py
npm ci --ignore-scripts
npm test
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Pinned runtime and tool inputs are recorded in [runtime-lock.json](tools/runtime-lock.json) and [devtools-lock.json](tools/devtools-lock.json).

## Documentation

- [Project transfer / desktop companion](docs/project-transfer.ko.md)
- [Android development tools](docs/android-devtools.md)
- [Linux runtime](docs/linux-runtime.md)
- [Phone controls](docs/phone-use.md)
- [Voice input](docs/voice-input.md)
- [Updates and releases](docs/app-updates.md)
- [Verification](docs/verification.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Mobile Codex's original code and independently created artwork are provided under **[GPL-3.0-only](LICENSE)**. Third-party components retain their own licenses and notices.

Copyright © 2026 nokryong and contributors.
