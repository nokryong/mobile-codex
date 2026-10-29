# Session recovery

Only an absent `sessions.json` starts an empty store. An existing unreadable or
invalid file locks session writes, including runtime shutdown and message sends.
The state response exposes `sessionStorage.recoveryRequired` and the load result.

`sessions.json.last-good` contains a validated snapshot and its SHA-256. It is a
recovery candidate, never an automatic fallback: an older snapshot can contain
conversations deleted after it was saved. A valid current file always wins,
including a deliberately empty array.

To inspect the candidate, call `sessions.storage` through the native bridge.
With no running work, explicitly call `sessions.recover` with its `sha256` and
`acceptOlderSnapshot: true`. Recovery revalidates the candidate, preserves the
current bytes in `sessions.json.recovery-<id>`, atomically replaces current, and
reenables saving. A missing or damaged candidate is refused. Keep the app's
private storage intact when obtaining support; deleting app data removes recovery
files and account data.

Saving stages and syncs bytes in the same directory before atomic replacement.
The verified current is backed up before replacement; the successful new current
then refreshes the candidate. If that final refresh fails, current and the older
candidate remain available. A changed or corrupt current cannot overwrite a good
candidate.
