# Android phone use — 0.1.8 alpha

Codex can operate the phone through four app-server dynamic tools. This is a native Android AccessibilityService integration; it needs no root, ADB, separate PC, or Termux setup. Model inference still uses the online AI service.

## Use

1. Install the updated APK. Open **Settings → Tools → Phone control → Accessibility settings**.
2. Enable **Mobile Codex phone control** in Android settings, then return to the app. Some sideloaded installations require the user to allow restricted settings from Android's app information screen first. The app does not bypass this OS permission.
3. Press **Enable phone control** and accept the on-device disclosure. Accessibility access alone does not arm automation. The app explains that screen text/images may be sent to the model and retained in Codex history.
4. Start a **new conversation** and describe the task, for example: “Open Calculator and calculate 125 × 48. Check the result on screen.” Conversations created before this version keep their original dynamic tool catalog; their history is not rewritten or discarded. Settings identifies these older threads.
5. Use **Stop phone control** floating over the screen or the in-app stop button at any time. Drag the floating button if it covers another app's controls. Stopping control does not delete the conversation or its draft. Chat stop, engine shutdown and connection loss also disarm phone control. A process/service restart requires enabling it again.

## Tools

| Tool | Result |
| --- | --- |
| `mobile_phone_status` | Connection, current consent and screenshot capability; can explain setup while disarmed |
| `mobile_phone_apps` | Launchable app labels and package names, using launcher-intent package visibility |
| `mobile_phone_screen` | Current app/window, visible node labels and ids, bounds, physical pixel size, snapshot id; optional JPEG screenshot on Android 11+ |
| `mobile_phone_action` | Tap/long press by node or coordinates; replace text; forward/backward node scroll; swipe; back/home/recents/notifications/quick settings; open an app by package |

Actions return whether Android accepted the operation. They do not claim the requested task succeeded. The agent is instructed to inspect the resulting screen and verify the outcome. These tools coexist with the existing file tools, plugins, skills, MCP and shell runtime.

## Behavior and boundaries

- UI changes invalidate observations. Node/coordinate actions require the most recent snapshot, correct app/window, screen size and a capture less than 30 seconds old. Nodes must still be enabled, visible, refreshable and in the same bounds. Each action consumes its observation; the next screen action needs another observation. A stale observation reports an error rather than guessing a target.
- Coordinates use physical screen pixels, matching screenshot dimensions. Returned `stopButtonBounds` marks the emergency control's occupied area. Gestures through this area are rejected so the agent cannot operate its own stop control. Android's default display is the screenshot/gesture target; external-display automation is not implemented.
- A revocable generation token invalidates queued operations even if the user stops and re-enables control. Native requests time out, cancel their futures, and do not execute later if they have not already started. An Android gesture already dispatched may finish its remaining duration (at most 2 seconds); stopping is not an undo operation.
- Screen events only invalidate node references. Their contents are not logged, continuously recorded, or automatically sent anywhere. Screen data is read when the enabled agent invokes the tool.
- Visible password fields are redacted in the node tree and suppress that observation's screenshot. Protected screenshot requests follow Android's restrictions. The service never unlocks the phone. Android can decline screen capture or withhold nodes; the result explains that limitation.
- The app does not add observation screenshots to the generated-image gallery. App-server/provider history may retain tool inputs/outputs. This is disclosed before enabling and is not an ephemeral/no-retention promise.
- The selected read-only permission policy allows observation but rejects `mobile_phone_action`. Enabling control is only a native UI operation, not a model tool. Model instructions require staying within the user-requested task and confirming consequential actions unless already explicitly authorized.
- Android 10 supports node inspection and gestures. The Accessibility screenshot API is available on Android 11 and later. Apps using custom canvases may expose few usable nodes; screenshots/coordinate gestures help on supported versions.
- Device/OEM battery management and permission changes can interrupt the service. Pending requests fail and consent is revoked on disconnect. No background recorder, startup receiver, or automatically rearmed permission is installed.

## Verification

Automated coverage includes revocation/re-enable generations, actual Android global-action dispatch, cancellation before main-thread dispatch, stop/re-enable overlay cleanup, stale app/window/rotation references, coordinate bounds, password redaction, disabled-service handling, retained file tools, new-thread tool registration, old-thread messaging, screenshot-gallery separation, native consent cancellation, and the UI emergency stop preserving a draft. Native phone tests run against Android API 29 and 35 with Robolectric.

On a physical device verify: service enable/disable, consent cancellation, Calculator task, node text replacement, scroll/swipe, rotation between observation and action, Home/Back, screenshot restrictions, password fields, immediate stop during a model task, force-stop/relaunch default-off, notification shade, and Samsung background limits. Automated tests do **not** establish these real-device results.

Primary API references:
- https://developer.android.com/guide/topics/ui/accessibility/views/service
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
