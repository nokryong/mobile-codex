# General Chat transport and historical Android probe

## Shipped UI (0.1.27-alpha)

The app's Chat mode uses its own composer and reply view, backed by `ChatWebTransport`.
`ChatWebLoginActivity` opens the official page solely for sign-in, verification, and
account access. It has a Return to Chat button, system/keyboard insets, loading feedback,
and retry on page failure. The old probe activity, diagnostic log, test composer,
and server-requery button were removed from the shipped UI. Settings → Account and
the Chat sidebar open the same login screen. Neither remote WebView exposes `Native`.

## Historical experiment (before the shipped UI)

The following observations describe earlier experimental builds, not current controls.


The installed Codex CLI (`0.155.1`) and its generated app-server schema expose
Codex `thread/*` and `turn/*` requests, but no ordinary ChatGPT Chat send
request. Mobile Codex's existing composer uses those Codex requests.

Settings → Advanced → **일반 Chat 전송 실험** opens a separate Android WebView.
It loads the official ChatGPT page. The user must log in and complete any
verification in that page, then select a normal Chat model. The input beneath
the page belongs to Mobile Codex. Pressing **보내기** attempts to insert its
text into the webpage composer and click the webpage's send button. The
diagnostic text distinguishes input insertion, click dispatch, answer display,
and server requery. A click alone is never reported as HTTP success.

After the reply appears, the app automatically re-requests that conversation
from inside the signed-in WebView. It obtains the web session access token
inside page JavaScript for that read request; the token is never returned to
Android code or written to diagnostics. **같은 대화 서버 재조회** repeats the check. It
looks for the exact submitted text and a finished assistant message descended
from it. The latest diagnostic stages and the requery button sit above the web
page; Android status, navigation, and keyboard insets keep controls reachable.
The screen shows only stage names, timestamps, status codes, and the answer
model. It does not log message content, tokens, cookies, passwords, or
verification material.

Android screenshots from 2026-09-24 show unique messages sent from the app's
native input and replies displayed in the WebView. The official ChatGPT Android
app was then opened independently: it showed the exact
`MC-NATIVE-1790182813756` user message and its reply in the same saved
conversation. The initial in-WebView requery returned HTTP 404 without the
web session bearer header. After the header fix, an on-device requery found
the exact `MC-NATIVE-1790183737963` user message and its linked finished
assistant reply with model `gpt-5-6-thinking`.
This experiment depends on current webpage DOM selectors. The character UI
remains in the existing Codex screen; this experiment tests only the transport
and authentication path. The independent Python probe in
`tools/probe_chat_requirements.py` only inspects requirements and never sends
a conversation message.

## Pro selection failure capture (after 1ac60cc)

The debug transport now freezes a settings-only `lastFailedSelection` before
clearing the operation. It includes its own build/environment, operation ID,
start/end session epochs, exact failure/stage, monotonic elapsed time, timeout
source, last observed snapshot, and bounded change events. Read operations and
successful recovery selections do not erase it. The last failure is kept in
app-private `chat-model-diagnostic` preferences, not shared storage or Git.
`currentAfterRecovery` is a separate observation; if evaluation times out it is
null, not a stale snapshot presented as current.

The same sending WebView records touch dispatch results, Android/DOM focus,
arrow-key down/up return values, and operation-scoped DOM arrow events. DOM
event delivery is evidence of delivery only, not setting application. Settings
value text and individual accessible descriptions are bounded and separated;
text/ordinal interpretations and conflicts remain visible. Unrelated active
or event-target elements are described structurally without text or IDs.
The observer ignores all typed characters and removes its listeners after the
operation or its timer. No conversation, request body, cookie or token is read
by this diagnostic.

For one minimal reproduction: confirm X-High, choose Pro once, then open
**설정 진단 보기** / **진단 복사**. Preserve `lastFailedSelection` before comparing
the current value or changing to High to check recovery. A message send is not
needed. A successful setting choice still verifies the observed menu/closed
label only; persistence after reopening and actual generation settings require
separate evidence. Pro's failure cause is not yet established on the device.

`waitModelChange` now waits for a condition within the existing 5-second
operation deadline instead of failing after ten 80ms polls. It sends no extra
key while waiting. No longer timeout or repeated menu reopen was added.
Pro radio options with duplicate normalized names report `ambiguous-option`,
not `disabled`; original bounded setting labels remain in the diagnostic.

For iterative test APKs, dispatch `android.yml` on the development branch with
`apk_only=true`. This prepares required packaged runtimes, assembles and signs
the APK, and uploads artifacts. Full test/layout/lint jobs and release publishing
are skipped. Default/main checks remain full. Targeted local DOM regressions:
`node --test tests/chat-model-dom.test.cjs`. Frozen-trace JVM regressions:
`./gradlew testDebugUnitTest --tests dev.mobilecodex.app.ChatModelTraceTest`
(where an Android/JDK build environment is available).

## Device finding: compact Pro button label

On installed build `23f86eedd8d8` (API 36, WebView 153.0.8010.36, ko-KR),
operation 13 targeting High failed at `locate-trigger` in 10ms with two trigger
candidates and no open popup. Its nested key evidence belonged to operation 8;
it was not evidence that operation 13 sent a key. The initial Pro failure had
been overwritten by later selection failures.

A read-only DevTools inspection of the actual sending WebView found the
composer attachment trigger (`composer-plus-btn`) and the model button with
literal DOM text `6Pro`. The old Pro regex required whitespace before `Pro`,
so it rejected `6Pro` and fell back to both composer menu buttons. This explains
the ambiguous-trigger state and subsequent selection failures. Running the
updated adapter in an isolated read-only scope against the same live document
changed the result from `ambiguous / 2 candidates` to `closed / Pro / 1 candidate`.
No menu click or message send was performed in this check. Actual setting
changes still need verification with the new installed build.

The parser now accepts a numeric model prefix adjacent to Pro and excludes the
observed attachment trigger. Diagnostic snapshots retain bounded trigger
candidates, filter key observations by operation and epoch, and keep the first
failure in a chain separately from the latest recovery failure. A successful
selection ends that failure chain without deleting its first failure record.
