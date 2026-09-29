# Mobile Codex character icon packs

Mobile Codex에서 사용할 수 있는 32상태 캐릭터 팩입니다.

## 제공 팩

- `gptchan` — 지피짱( GP-Chann )
- `astrachan` — 아스트라짱( Astra-Chan )

각 팩 폴더에는 `mapping.json`과 투명 PNG 32개가 들어 있습니다.

## 설치

1. 원하는 ZIP을 다운로드하고 압축을 풉니다.
2. 휴대폰에서 캐릭터 팩을 모아 둘 일반 폴더를 하나 만듭니다.
3. 압축을 푼 `gptchan` 또는 `astrachan` 폴더를 그 안에 넣습니다.
4. Mobile Codex의 `설정 → 일반 → 캐릭터 팩`에서 상위 폴더를 선택합니다.
5. 새로고침한 뒤 원하는 캐릭터 팩을 선택합니다.

두 팩을 함께 쓰려면 다음처럼 배치합니다.

```text
character-packs/
├─ gptchan/
│  ├─ mapping.json
│  └─ 01-idle.png ... 32-sleep.png
└─ astrachan/
   ├─ mapping.json
   └─ 01-idle.png ... 32-sleep.png
```
