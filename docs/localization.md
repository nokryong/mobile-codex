# Localization

Settings → General → Language supports **System**, **English**, and **한국어**. Korean device locales use Korean; other device locales fall back to English. Changing the app language does not restart Codex, clear drafts, translate messages, change file contents, or change custom instructions. Recognition in the main chat requests the selected language (or the actual device locale for System); installed speech services determine availability.

UI strings are explicitly translated with `t(source)` in JavaScript and `Texts.t(source)` in Java. Korean source labels are the catalog keys. The English catalog is `app/src/main/assets/translations-en.json`; its web copy is `web/translations.js`. Keep both copies identical. Static text/attributes in the packaged HTML are captured before rendering. There is no general text replacement over user/model messages, filenames, terminal output, or editors.

Use named placeholders for messages with dynamic values. Do not translate protocol identifiers, model responses, or data received from tools. Tests cover catalog parity, missing keys, switching languages with existing drafts, and preserving user content. Native accessibility descriptions use Android resource locale selection.

Chinese (Simplified) and Japanese are potential future translations, not selectable languages yet. To add one: provide a complete reviewed catalog, extend both locale selectors/resolvers, translate Android accessibility resources, and test long labels and input composition on a real device. Do not display an option backed by an incomplete translation.

README.md is the English entry point; README.ko.md is Korean. Keep installation, signing, and feature descriptions aligned.
