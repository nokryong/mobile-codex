# Voice input — 0.1.12 alpha

In the main chat, the microphone starts **inline dictation**. The composer displays microphone permission/preparation, listening, and transcription states. It shows actual microphone level and interim text when supplied by the device service. Choose **Done** to stop recording and wait for final transcription, or **Cancel** to discard it. Final text goes into the draft and is never sent automatically.

The first use requests Android microphone permission. Backgrounding/locking the main activity cancels an active recording; returning does not automatically restart it. A permission dialog is allowed to complete without being treated as a recording interruption. Provider silence detection may finish recognition before Done is pressed. Startup and final-result waits have timeouts; the app does not impose its own listening-duration limit. Late results after cancellation cannot be inserted into a new session.

Floating chat currently retains Android’s separate recognition screen. Both paths use the same durable result receipts:

- If the draft is unchanged, insert at the original cursor or replace the selected range. If it changed, preserve the edit and append the recognized text.
- Results belong to the original conversation/project even if the user switches chats.
- Persist the native receipt before acknowledging it. A recovered receipt must not append twice after process recreation.
- Recognition blocks new phone automation actions. Cancelling or an error keeps the original draft intact.

Recognition uses Android `SpeechRecognizer` in the main composer and `ACTION_RECOGNIZE_SPEECH` for floating chat. This is not the ChatGPT app’s speech backend. The selected app language is requested for inline recognition; System uses the device locale. The service may send audio to its servers and may require internet access. Accuracy, supported languages, silence handling, and offline availability depend on that provider. Mobile Codex does not save an audio file or send audio to a developer-operated server. Continuous listening, spoken replies, and realtime voice conversation are not implemented.

## Verification

Automated checks cover language requests, result parsing, recording states, cancellation/late callbacks, scope-bound receipt recovery, preserving edits, and preventing automatic sends. Physical-device checks remain necessary for microphone permissions, Korean/English recognition, Samsung speech services, silence detection, network loss, rotation, screen lock, and floating chat return behavior.

References: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) · [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent).
