# Mobile Codex

## 설치 전에 확인하세요

Play 프로텍트가 APK 설치를 막는 경우, 다음 순서로 잠시 설정을 바꾸세요.

1. **Google Play 스토어 → 프로필 아이콘 → Play 프로텍트 → 설정**을 엽니다.
2. **Play 프로텍트로 앱 검사**를 끕니다.
3. [공식 프로젝트 GitHub 릴리스](https://github.com/nokryong/mobile-codex/releases)의 APK를 설치합니다.
4. 같은 Play 프로텍트 화면으로 돌아가 검사를 다시 켭니다.

APK를 여는 앱에서 **이 출처 허용**을 별도로 요구할 수 있습니다. Android 버전에 따라 메뉴 이름이 다를 수 있으니 [Google Play 프로텍트 공식 도움말](https://support.google.com/googleplay/answer/2812853?hl=ko)도 확인하세요.

[English](README.md) · **한국어**

<p align="center"><img src="app/src/main/assets/web/codex-logo.png" alt="Mobile Codex 로고" width="144" /></p>

<p>
  <img width="24%" alt="General chat" src="https://github.com/user-attachments/assets/14dd8782-58eb-4784-96bc-aaa190adfa87" />
  <img width="24%" alt="Git clone" src="https://github.com/user-attachments/assets/84b311d7-c584-4107-a09e-07862b11797d" />
  <img width="24%" alt="Skill selection" src="https://github.com/user-attachments/assets/e2ca66de-9ac6-4044-89c5-4daf41c8e308" />
  <img width="24%" alt="Image generation" src="https://github.com/user-attachments/assets/7beb3213-9051-4fc8-9051-40ff5fc45a5a" />
</p>

**Android 휴대폰과 태블릿에서, 대화부터 파일 작업과 코드 실행까지.**

Codex 데스크톱의 작업 방식을 모바일로 옮기는 개인용 클라이언트입니다. Codex 실행 엔진과 Python·Node.js·Git을 APK에 포함해, Termux 설치나 별도 PC 서버 없이 기기에서 바로 작업할 수 있습니다.

**0.1.27 alpha** · **ARM64 / Android 10 이상** · 제작 [nokryong](https://github.com/nokryong)

[APK 다운로드](https://github.com/nokryong/mobile-codex/releases) · [개발 빌드](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml) · [문제 제보](https://github.com/nokryong/mobile-codex/issues)

> OpenAI 공식 앱이 아닌 개인 프로젝트입니다. 모델 추론에는 인터넷과 Codex를 사용할 수 있는 계정이 필요합니다. Codex 프로세스와 파일·명령 도구는 Android 기기에서 실행합니다.

## 일반 Chat과 Codex

사이드바 로고 아래의 **Chat / Codex**로 전환합니다. 일반 Chat은 앱 자체 입력창과 대화 화면을 사용하고, **ChatGPT 로그인**에서 연결한 공식 웹 세션이 전송을 처리합니다. 로그인 화면에서 인증을 마친 뒤 **Chat으로 돌아가기**를 누르세요. Codex 계정 로그인과는 별개입니다.

Chat 기본 모델은 Instant입니다. 추론 슬라이더는 선택 즉시 닫히며, 긴 사용자 메시지는 펼치거나 접을 수 있습니다. 모델 적용 실패는 표시되며 다른 설정으로 조용히 전송하지 않습니다. 일반 Chat은 현재 텍스트 전송과 현재 대화의 로컬 표시를 지원합니다. 전체 웹 대화 목록 동기화와 Chat 첨부 기능은 아직 지원하지 않습니다.

## 주요 기능

| 기능 | 할 수 있는 일 |
| --- | --- |
| 프로젝트와 대화 | 프로젝트별 대화 목록, 폴더 없는 일반 대화, 저장된 기록 열기, 응답 스트리밍·중지·재개, 작업 중 추가 지시 |
| 파일 작업 | 폴더 탐색·검색, 파일 읽기·생성·수정·이동·이름 변경·삭제, 변경 전 확인과 복구 사본 |
| 첨부와 이미지 | 여러 파일 첨부, 이미지 미리보기, 여러 장의 생성 결과 갤러리, 확대·스와이프·원본 저장 |
| 기기 내 개발 도구 | Python·pip, Node.js·npm/npx, Git, 명령 입력과 출력 스트리밍 |
| 변경 사항 검토 | Git 상태 확인, HEAD 기준 변경 비교, 작업 파일 복원과 복구 사본 |
| 플러그인·스킬·MCP | 플러그인 설치·활성화와 계정 연결, 스킬 폴더 가져오기, MCP 서버 설정·상태·도구 확인 |
| 계정과 사용 한도 | ChatGPT 계정을 여러 개 등록해 안전하게 전환하고, 계정별 대화를 분리하며, 남은 Codex 한도를 간단·상세 그래프로 확인 |
| 휴대폰 제어 | 접근성을 통한 다른 앱 열기, 화면 읽기, 탭·텍스트 입력·스크롤·뒤로·홈 이동 |
| 플로팅 대화와 음성 | 다른 앱 위에서 대화·작업 중지·추가 지시, 메인 입력창 안에서 음성 받아쓰기·완료·취소, 인식 결과 초안 확인 |
| 맞춤 설정 | 모델·추론 강도·작업 권한, 맞춤 지침, Codex 설정 편집, 테마와 대화 아이콘, 기기 언어/한국어/영어 UI |
| 앱 업데이트 | 공개 GitHub 릴리스 확인, APK 다운로드·무결성·서명 검증, Android 설치 화면 연결 |

휴대폰에서는 서랍과 시트로, 태블릿에서는 여러 패널로 구성합니다. 라이트·다크 테마에 반투명 상단 바, 떠 있는 입력창, 끌어서 닫는 모바일 시트를 제공하며 동작 줄이기 설정을 따릅니다. UI가 아직 전용 화면을 제공하지 않는 기능도 Codex 도구와 설정을 통해 사용할 수 있는 구조이며, 실제 지원 범위는 실행 엔진·계정·Android 권한·설치된 명령에 따라 달라집니다.

## 설치

**준비물:** ARM64 Android 10 이상 기기, 최신 Android System WebView, 인터넷 연결, Codex를 사용할 수 있는 계정.

### APK 받기

배포된 버전은 [Releases](https://github.com/nokryong/mobile-codex/releases)에서 `mobile-codex-<버전>-arm64.apk`를 받습니다. 최신 개발 버전은 [Actions → Android APK](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml)에서 **성공한 실행**을 열고 아래의 **Artifacts**를 확인하세요. 릴리스와 개발 빌드의 버전은 다를 수 있습니다.

| Actions 산출물 | 용도 |
| --- | --- |
| **`mobile-codex-update-assets`** | **설치할 때 받을 파일.** ZIP을 풀면 `mobile-codex-0.1.27-alpha-arm64.apk`와 검증 파일이 나옵니다. |
| `mobile-codex-arm64-debug` | APK·런타임 정보·개발 도구의 대응 소스를 함께 담은 큰 묶음입니다. |
| `check-reports` | 테스트·Lint 결과와 브라우저 화면 검사·스크린샷입니다. 설치 파일은 없습니다. |

Actions 산출물 다운로드에는 GitHub 로그인이 필요하며, 보관 기간은 14일입니다. 빌드에 실패했거나 산출물이 만료된 실행에서는 APK를 받을 수 없습니다.

다운로드한 APK를 열고 Android 설치 화면을 따릅니다. 요청이 나타나면 다운로드에 사용한 앱의 **알 수 없는 앱 설치** 권한을 허용합니다.

### 처음 시작하기

1. **ChatGPT 계정 연결**을 누르고 표시된 코드를 브라우저에서 입력해 로그인합니다.
2. 사이드바에서 **일반 대화**를 시작하거나 **프로젝트 추가**로 작업할 폴더를 등록합니다. 프로젝트 옆 화살표를 누르면 해당 프로젝트의 대화 목록이 열립니다.
3. 로컬 프로젝트에서 셸 명령을 실행하려면 **설정 → 도구 → 기기 파일 접근 허용**을 설정합니다.
4. **설정 → 일반 → 언어**에서 기기 언어를 따르거나 한국어/영어를 선택합니다. 기존 대화와 파일 내용은 바꾸지 않습니다.
5. 입력창에서 모델·추론 강도·작업 권한을 선택하고 요청을 보냅니다. **`+`**는 파일 첨부, **`@`**는 파일·앱 선택, **`$`**는 스킬 선택입니다.

왼쪽 아래의 한도 도넛을 누르면 **설정 → 계정**이 열립니다. 여기서 ChatGPT 계정을 추가하거나 전환할 수 있습니다. 인증 정보는 앱 비공개 저장소에 보관하며 계정마다 저장된 대화 목록을 따로 표시합니다.

저장된 대화는 로그인이나 엔진 실행 없이 열 수 있습니다. 프로젝트를 목록에서 제거해도 실제 폴더와 대화 기록은 남으며, 폴더 권한이 끊기면 **폴더 다시 연결**로 재연결할 수 있습니다.

### 기존 앱 업데이트

**설정 → 업데이트**에서 공개 릴리스를 확인할 수 있습니다. 다운로드한 APK의 체크섬·패키지·버전·서명을 검증한 뒤 Android 설치 화면으로 연결하며, 설치는 사용자가 승인합니다. main에서 테스트·빌드·기존 키 서명 검증이 성공하면 새 버전을 Releases에 자동 게시합니다. 알파 버전은 시험판으로 표시하며, 이미 게시한 버전의 파일은 덮어쓰지 않습니다. 다음 릴리스를 만들 때 versionName과 versionCode를 함께 올립니다.

기존 앱을 유지하며 업데이트하려면 호환되는 패키지명과 서명키가 필요합니다. **main의 Actions APK는 등록된 기존 키로 서명하고 인증서 지문을 검증합니다.** 서명 Secret이 없거나 인증서가 다르면 APK를 게시하지 않습니다. 이 설정 이전의 Actions APK는 임시 debug 키로 서명되어 호환되지 않을 수 있습니다. 앱을 삭제하면 내부 대화·로그인·복구 사본도 삭제됩니다.

0.1.7부터 앱 ID는 `dev.mobilecodex.app`입니다. 다른 앱 ID를 사용한 이전 버전과는 별도 앱으로 설치됩니다. 자세한 내용은 [업데이트와 배포 안내](docs/app-updates.md)를 참고하세요.

## 기능 사용 안내

### 플러그인·스킬·MCP

사이드바의 **플러그인 · 스킬 · MCP**에서 관리합니다.

- **플러그인:** 마켓플레이스와 상세 정보를 확인해 설치·활성화합니다. 추가 계정 연결이 필요하면 연결 버튼이 표시됩니다.
- **스킬:** `SKILL.md`가 바로 아래에 있는 폴더를 **스킬 가져오기**로 선택합니다. 스크립트·참고 자료·에셋도 함께 가져오며, 대화에서 `$`로 선택합니다.
- **MCP:** 서버 상태와 도구 목록을 확인하고 OAuth 연결 또는 `config.toml` 설정을 사용합니다. stdio 서버의 명령은 Android에서 실행 가능해야 합니다.

### 휴대폰 제어와 플로팅 대화

**설정 → 도구 → 휴대폰 제어**에서 접근성 서비스를 연결한 뒤, 직접 제어를 켜고 **새 대화**에서 요청합니다. 다른 앱을 열고 화면을 확인하거나 탭·입력·스크롤할 수 있습니다. 화면 위 중지 버튼으로 끌 수 있으며 앱 프로세스가 재시작되면 제어가 꺼집니다.

Android 10은 화면 요소를 읽고, Android 11 이상은 스크린샷도 사용합니다. **제어에 사용되는 화면 내용과 이미지는 AI 서비스로 전송되고 대화 기록에 남을 수 있습니다.**

접근성 서비스가 연결되어 있으면 **플로팅 대화 열기**로 다른 앱 위에서도 대화할 수 있습니다. 메인 대화의 음성 입력은 별도 화면 대신 입력창 안에서 녹음 상태·인식 중인 텍스트·완료·취소를 표시합니다. 결과는 초안에 넣으며 확인한 뒤 직접 전송합니다. 플로팅 음성 입력은 기기의 별도 인식 화면을 사용합니다. 인식 엔진은 기기의 음성 서비스이며 ChatGPT 앱의 음성 엔진과 같지 않습니다. 서비스에 따라 외부 서버와 인터넷을 사용할 수 있습니다.

[휴대폰 제어 안내](docs/phone-use.md) · [플로팅 대화와 변경 사항](docs/floating-and-changes.md) · [음성 입력 안내](docs/voice-input.md)

### 이미지·지침·개발 도구

- **이미지:** 여러 결과를 갤러리로 묶어 확대·넘기기·개별 원본 저장을 제공합니다. 생성 기능의 사용 가능 여부는 엔진과 계정에 따릅니다.
- **맞춤 지침:** 설정에서 언어·말투·작업 규칙을 편집합니다. 실제 Codex 전역 지침 파일에 저장하고 다음 요청부터 다시 읽습니다.
- **대화 아이콘:** 설정 → 일반에서 캐릭터 표시를 켜거나 끕니다. 생성 이미지와 첨부 사진에는 영향을 주지 않습니다.
- **로컬 캐릭터 팩:** **설정 → 일반**에서 `character-packs` 루트 폴더를 한 번 선택합니다. `Documents/MobileCodex/character-packs`는 예시일 뿐 어느 일반 폴더든 연결할 수 있습니다. 루트 아래 팩 폴더에 32개 이미지를 복사하고 `mapping.json`을 둔 뒤 새로고침하고 목록에서 팩을 선택하세요. 파일을 바꾸거나 삭제한 뒤에도 새로고침하며, 접근 권한을 잃으면 루트 폴더를 다시 선택하세요. 각 팩은 `mapping.json`으로 32개 기본 상태 키를 연결하며 PNG·WebP는 파일당 4 MiB 이하, 가로·세로 각각 2048px 이하이어야 합니다. [32개 상태 전체 예제](docs/examples/character-pack-default/README.md)를 참고하세요.
- **도구 진단:** 설정 → 도구 → **도구 실행 확인**에서 Python·Node.js·Git 등의 실행 상태를 로그인 없이 확인합니다.

## Android에서 알아둘 점

현재 알파 버전입니다. 기본 실행·대화·파일 작업에는 사용자 실기기 성공 보고가 있으나, 새 기능의 기기별 동작은 계속 검증 중입니다. 자동 테스트와 실기기 확인 범위는 [검증 기록](docs/verification.md)과 [기기 검증 목록](docs/device-validation.md)에 구분합니다.

- **파일 접근:** Android가 허용한 범위에서 동작합니다. 다른 앱의 비공개 데이터나 시스템 보호 영역에는 접근할 수 없으며, 전체 파일 접근은 루트 권한이 아닙니다. 클라우드 문서 제공자 폴더는 일반 셸 경로 대신 문서 도구를 사용합니다.
- **승인 방식:** 대화 입력창의 모델 옆에서 **승인 받기 / 자동 검토 / 모두 허용**을 선택합니다. 자동 검토는 승인 요청을 Codex 위험 검토기에 맡기며 파일 접근 범위와는 별도입니다.
- **작업 권한:** Android 포트에는 데스크톱의 명령 샌드박스가 없으며, 셸의 OS 접근 경계는 Android 앱 권한입니다.
- **명령 호환성:** Python·JavaScript와 Android 호환 패키지를 대상으로 합니다. 컴파일러·JDK·Perl·SSH 클라이언트는 번들하지 않습니다. 일반 Linux/Windows 실행 파일과 네이티브 확장이 모두 호환되지는 않습니다.
- **저장 위치:** 공유 저장소는 실행·심볼릭 링크를 제한할 수 있어 npm 설치·Git 작업에는 앱 내부 경로가 적합합니다. 일반 대화의 터미널은 앱 내부 작업 폴더를 사용합니다.
- **편집과 복구:** 내장 텍스트 편집기는 UTF-8 1 MiB까지 지원합니다. 문서 도구는 수정 전 파일과 32 MiB 이하의 삭제 파일을 복사하지만, 셸 변경이나 디렉터리 전체 삭제까지 복구하지는 않습니다. 일반 Codex 도구·셸에는 편집기 크기 한도를 적용하지 않습니다.
- **아직 없는 전용 UI:** 대화형 PTY, Git 커밋·worktree 관리, 예약 자동화 화면은 구현 중인 범위입니다. 현재 터미널은 명령 입력과 출력 스트리밍을 제공합니다.
- **백그라운드:** foreground service로 작업을 유지하지만 제조사 배터리 관리에 따라 중단될 수 있습니다.

## 직접 빌드하기

JDK 17, Android SDK Platform 35, Build Tools 35.0.0, NDK 28.2.13676358, Python 3, Node.js 20 이상이 필요합니다. `ANDROID_HOME`은 Android SDK 경로로 설정합니다.

```sh
python3 tools/prepare_runtime.py
python3 -m pip install -r tools/requirements-devtools.txt
python3 tools/build_native.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python3 tools/prepare_devtools.py
npm ci --ignore-scripts
npm test
npx playwright install --with-deps chromium
npm run test:layout
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

<details>
<summary>서명과 실기기 테스트</summary>

프로젝트 전용 서명을 사용하려면 빌드 프로세스에 다음 환경 변수를 제공합니다. 값이 없으면 Android 기본 debug 서명을 사용합니다.

| 환경 변수 | 값 |
| --- | --- |
| `MOBILE_CODEX_KEYSTORE` | 키스토어의 절대 경로 |
| `MOBILE_CODEX_KEY_ALIAS` | 키 별칭 |
| `MOBILE_CODEX_STORE_PASSWORD` | 키스토어 비밀번호 |
| `MOBILE_CODEX_KEY_PASSWORD` | 키 비밀번호 |

키와 비밀번호는 저장소 밖에 보관하고, 업데이트를 배포할 때 같은 서명키를 유지합니다.

ARM64 기기를 연결한 경우:

```sh
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

런타임 테스트는 별도 테스트 홈에서 실행하며 사용자의 로그인 파일을 사용하지 않습니다. 일반 x86 Linux CI에서 Android ARM64 프로세스의 실기기 실행까지 확인하는 것은 아닙니다.

</details>

## 문서와 출처

| 문서 | 내용 |
| --- | --- |
| [입력과 첨부](docs/input-protocol.md) | 파일·앱 멘션, 스킬, 첨부 전달과 저장 방식 |
| [기기 내 개발 도구](docs/android-devtools.md) | Python·Node.js·Git 패키징, 출처와 제약 |
| [Git 런타임 수정](docs/git-runtime-fix.md) | 공유 라이브러리 로딩 문제의 원인과 검증 |
| [업데이트와 배포](docs/app-updates.md) | APK 검증, 서명 호환성, 릴리스 준비 |
| [검증 기록](docs/verification.md) | 테스트 결과와 실기기 미확인 항목 |
| [서드파티 고지](THIRD_PARTY_NOTICES.md) | 포함된 구성 요소의 출처와 라이선스 |

Android 실행 엔진은 [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux/tree/v0.155.1)의 `@mmmbuto/codex-cli-termux@0.155.1`을 사용하고, UI와 엔진은 [Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server)의 JSON-RPC 프로토콜로 연결합니다. Termux 앱을 설치하는 방식이 아니라 Android 네이티브 실행 파일을 APK에 포함해 직접 실행합니다.

엔진·개발 도구의 버전과 체크섬은 [`runtime-lock.json`](tools/runtime-lock.json)과 [`devtools-lock.json`](tools/devtools-lock.json)에 고정합니다. Android 패키징에 필요한 실행 파일 이름과 라이브러리 참조를 조정하며, 준비 스크립트와 변경 사항을 저장소에 포함합니다. 원본 LICENSE·NOTICE는 APK에, 개발 도구의 대응 소스는 빌드 산출물에 포함합니다. 재배포 시에도 해당 고지와 소스를 함께 제공하세요.

인증 정보는 앱 비공개 저장소에 보관하며 Android 백업·기기 전송에서 제외합니다. 저장소에는 앱 소스·에셋·테스트·빌드 설정을 관리하고, 개인 설정·로그·서명키·인증 파일·빌드 산출물은 커밋하지 않습니다.

## 라이선스와 기여

Mobile Codex의 자체 작성 코드와 독자적으로 제작한 아트워크는 **[GPL-3.0-only](LICENSE)**로 제공합니다. Copyright © 2026 nokryong and contributors. 서드파티 구성 요소에는 각각의 라이선스가 유지되며, 이 라이선스가 제삼자의 상표 사용 권리를 부여하지는 않습니다. [서드파티 고지](THIRD_PARTY_NOTICES.md)를 함께 확인하세요.

버그 제보, 코드 개선과 번역 기여를 환영합니다. 자체 코드에 대한 기여는 GPL-3.0-only로 제공됩니다. 제보에는 기기·Android·앱 버전과 재현 방법을 포함하고 인증 정보와 개인 내용을 제거해 주세요. 번역 방법은 [Localization](docs/localization.md)에 정리했습니다.
