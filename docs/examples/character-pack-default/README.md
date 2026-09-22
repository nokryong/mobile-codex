# Default character pack example

This example shows the complete mapping for the 32 built-in chat icon states.
The repository already contains the source images in
`app/src/main/assets/web/chat-icons/`; copy those PNG files into the same
directory as this `mapping.json` when making a local pack.

The example keeps the images out of the documentation tree so the repository
does not carry a second copy of roughly 42 MB of PNG data. The app reads the
selected pack from any user-selected folder, such as `Documents/MobileCodex/character-packs`; no server or installer is involved.

The supported pack shape is:

```text
<pack>/
  mapping.json
  01-idle.png
  ...
  32-sleep.png
```

`mapping.json` maps each existing state key to a relative PNG or WebP
filename. Keep filenames inside the pack directory.

## Create and use a pack

1. Create `<root>/<pack>` and copy the 32 PNG files from the [built-in icon folder](../../../app/src/main/assets/web/chat-icons/) into the pack folder.
2. Keep `mapping.json` beside the images and edit its relative filenames if needed.
3. In the app, choose the `character-packs` root folder once. Any ordinary folder can be used; the path above is only an example.
4. Refresh the list, then select the pack shown under that root.
5. After changing or deleting files, refresh again. If access is lost, choose the root folder again.

## 한국어 제작 단계

1. [내장 아이콘 폴더](../../../app/src/main/assets/web/chat-icons/)의 32개 PNG를 새 팩 폴더로 복사합니다.
2. 이미지 옆에 `mapping.json`을 두고 상대 경로 파일명을 맞춥니다.
3. 앱에서 일반 폴더를 `character-packs` 루트로 선택한 뒤 새로고침하고 목록에서 팩을 선택합니다.
4. 파일을 바꾸거나 삭제하면 다시 새로고침하고, 접근 권한을 잃으면 루트 폴더를 재선택합니다.

각 이미지는 PNG 또는 WebP이며 파일 크기 4 MiB 이하, 가로·세로 각각 2048px 이하로 준비합니다.
