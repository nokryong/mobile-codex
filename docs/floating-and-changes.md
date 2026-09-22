# Floating chat and change review — 0.1.9 alpha

## Floating conversation

Open Settings → Tools → Accessibility settings and connect Mobile Codex's service. Then choose **플로팅 대화 열기**. This does not arm phone automation. To operate other apps, separately enable phone control with its existing disclosure.

The native bubble can be moved by dragging its Codex header. Tap it to expand a conversation panel with the latest messages, an input field, **보내기 / 추가 지시**, **중단**, and **계속**. While the panel is expanded, phone screen reads and new phone actions are blocked so an unsent draft is not inspected and typing is not interrupted. Sending an instruction collapses the panel. Collapsing releases keyboard focus. The expanded window accepts keyboard input and responds to IME insets on API 30+. The app and floating panel observe the same Engine without replacing each other's listeners. Drafts are stored per conversation/project and survive closing the panel. Close hides the panel; accessibility disconnect destroys it.

**추가 지시** uses `turn/steer` with the exact expected turn id. If that turn ended, the draft stays available instead of silently starting another task. The main chat composer supports this too, including its existing attachments and mentions. **중단** interrupts the turn and disarms phone control. **계속** sends a new request to inspect the current state and continue; it is not a frozen-process resume, and phone control must be explicitly enabled again if stopped. Native file approvals and app-server approval/questions remain in the full app; the floating panel indicates when to open it.

## Changes and restoring files

The sidebar's **변경 사항** shows files reported by Git in the selected local project (or the app-private general workspace). This includes the user's edits, not just changes made by Codex. It does not initialize a repository. The recent app-server turn diff is saved with the conversation and can be inspected independently of Git status.

- **비교** compares the current file's bytes with the Git HEAD blob. Selecting **HEAD 내용으로 복원** restores only that worktree file's contents. It does **not** change the Git index, commit history, file attributes or mode intentionally. Staged changes can therefore remain visible afterward. No Git checkout/smudge or textconv filters run; repositories using content filters should review the shown bytes before restoring.
- A new file absent from HEAD can be removed after confirmation, with a recovery copy. Deleted files can be recreated if their parent directory exists.
- Every restoration first stores the current bytes in app-private `change-backups`. **복원 사본** previews these copies and can reverse a restoration if the file still matches the restored result. Copies persist across app restarts but are removed when app data is cleared/uninstalled.
- Restore tokens are limited to their canonical project root, expire after five minutes, and are consumed on success. HEAD and current file bytes are rechecked. A changed file, switched project, active model turn, read-only permission mode or running in-app terminal blocks restoration. Symlinks, Git internals, directory/submodule restoration and paths outside the selected root are rejected. Normal external editors cannot be locked by this app; checks reduce but cannot eliminate a filesystem change at the exact instant of replacement.
- Native previews support files up to 8 MiB; text rendering is limited to 1 MiB / 2,000 displayed diff lines. Larger files remain usable through the existing terminal/file tools. Binary previews show their size. The UI indicates omitted content.
- **복구 사본 → 비교 / 복원** works with existing Android document-provider backups from file writes/deletes. New backups record the verified post-change hash or absence. Text files within the provider editor's existing 1 MiB limit can be restored after a native confirmation. Later modifications are rejected. Older backups without a verified post-change state remain exportable to another location.

## Verification

Regression coverage includes actual temporary Git repositories (restoration, unchanged index, reverse restoration, new/deleted files, literal Unicode/wildcard names, subdirectory scope, HEAD/file conflicts and symlink rejection), provider-backed write/restore conflicts, Engine steering and independent subscribers, native overlay keyboard flags and scoped drafts, plus DOM checks for steering failures, literal diff text, switched-project races and accessibility gating.

On a real device verify: Samsung keyboard in portrait/landscape; expand/collapse while another app is active; bubble movement at display edges; draft persistence and project switches; sending a task and additional instruction; interruption/continue; opening pending approvals in the app; screen lock/service disconnect; Git and cloud-provider restores. Automated tests are not a claim of physical device validation.

References: [Android window flags](https://developer.android.com/reference/android/view/WindowManager.LayoutParams), [IME visibility](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility), [Git diff](https://git-scm.com/docs/git-diff), [Codex turn steering schema](https://github.com/openai/codex/blob/rust-v0.155.0/codex-rs/app-server-protocol/schema/json/v2/TurnSteerParams.json).
