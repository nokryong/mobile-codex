# General Chat send experiment on Android

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
from inside the signed-in WebView. **같은 대화 서버 재조회** repeats the check. It
looks for the exact submitted text and a finished assistant message descended
from it. The latest diagnostic stages and the requery button sit above the web
page; Android status, navigation, and keyboard insets keep controls reachable.
The screen shows only stage names, timestamps, status codes, and the answer
model. It does not log message content, tokens, cookies, passwords, or
verification material.

An Android screenshot from 2026-09-24 shows two unique messages sent from the
app's native input and both replies displayed in the WebView. Server requery
remains to be verified after this control fix is installed. This experiment
still depends on current webpage DOM selectors. The character UI remains in
the existing Codex screen; this experiment tests only the transport and
authentication path. The independent Python probe in
`tools/probe_chat_requirements.py` only inspects requirements and never sends
a conversation message.
