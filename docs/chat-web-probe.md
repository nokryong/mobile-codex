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

After the reply appears, **같은 대화 서버 재조회** reads the conversation through
the current Codex sign-in token and checks for the exact submitted text and a
finished assistant message descended from it. The screen shows only stage
names, timestamps, status codes, and the answer model. It does not log message
content, tokens, cookies, passwords, or verification material.

This experiment depends on current webpage DOM selectors and WebView login
compatibility. Both require testing on the phone before this can be called a
working integration. The character UI remains in the existing Codex screen;
this experiment only tests the transport and authentication path. The
independent Python probe in `tools/probe_chat_requirements.py` only inspects
requirements and never sends a conversation message.
