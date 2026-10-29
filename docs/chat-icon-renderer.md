# Chat icon renderer

`app/src/main/assets/chat-icon-renderer.js` adapts the icon vocabulary and category aliases from [`nokryong/ChatGPT-DCCon-Renderer-Optimized`](https://github.com/nokryong/ChatGPT-DCCon-Renderer-Optimized), pinned at commit `98e9aee413c2ff6a91531f46f4a92da008b19a95` (`userscript.js`).

The original artwork source is the Firebase Storage bucket `mytaskmanager-cf059.appspot.com`. The Android WebView client serves only the 32 allowed filenames, loading public PNGs from that Firebase bucket, caching them privately for one day and falling back to the bundled icons at `https://chatgpt.com/__mobile_codex_icons__/<file>.png`; the renderer has no Firebase configuration, API key, remote request, blob URL, status storage, or conversation logging.

The renderer processes text only inside elements with `data-message-author-role="assistant"`. It preserves tokens in user messages, composer fields, and code-like elements. A failed image is replaced with its original `[[icon:...]]` token.

The 32 original PNGs remain in `icon-pack/gptchan`, pinned by
`tests/chat-icons.sha256.json`. Bundled deployment assets are lossless WebP files;
`web/chat-icons/manifest.json` maps the unchanged logical PNG URLs to them. Native
routes return `image/webp` for bundled bytes and `image/png` for the existing
remote PNG cache. Custom packs retain their own format and cache generation.
The model-selected tags and the policy hiding tags when icons are off are unchanged.

Install `tools/requirements-icons.txt` and run `python tools/optimize_chat_icons.py`
to verify original hashes, derivative hashes, dimensions and exact RGBA pixels.
Use `--generate` to rebuild only deployment assets and the derivative hash fixture.

## Attribution

Adapted at the repository owner's request from the source and artwork above. The upstream repository does not currently contain a separate license file. This records its origin without assigning a third-party license that was not supplied. The app source and original owner-contributed artwork retain the project notices.
