# Mobile Codex

[English](README.md) · **한국어**

<p align="center"><img src="app/src/main/assets/web/codex-logo.png" alt="Mobile Codex 로고" width="144" /></p>

<p>
  <img width="24%" alt="일반 대화" src="https://github.com/user-attachments/assets/14dd8782-58eb-4784-96bc-aaa190adfa87" />
  <img width="24%" alt="Git clone" src="https://github.com/user-attachments/assets/84b311d7-c584-4107-a09e-07862b11797d" />
  <img width="24%" alt="스킬 선택" src="https://github.com/user-attachments/assets/e2ca66de-9ac6-4044-89c5-4daf41c8e308" />
  <img width="24%" alt="이미지 생성" src="https://github.com/user-attachments/assets/7beb3213-9051-4fc8-9051-40ff5fc45a5a" />
</p>

## 별도 PC 없이 Android에서 쓰는 Codex

Mobile Codex는 Codex app-server를 기반으로 만든 독립 Android 클라이언트입니다. Android용 Codex 실행 파일과 Python, Node.js, Git을 함께 넣어 스마트폰·태블릿에서 프로젝트 파일과 개발 도구를 직접 사용할 수 있습니다. Termux나 별도 PC 서버를 항상 켜둘 필요가 없습니다.

**현재 버전: 0.2.7** · **ARM64** · **Android 10+** · Codex 런타임 **0.155.1**

[APK 다운로드](https://github.com/nokryong/mobile-codex/releases/tag/v0.2.7) · [전체 릴리즈](https://github.com/nokryong/mobile-codex/releases) · [빌드](https://github.com/nokryong/mobile-codex/actions/workflows/android.yml) · [이슈](https://github.com/nokryong/mobile-codex/issues)

> OpenAI 공식 앱이 아닙니다. 모델 추론에는 인터넷 연결과 Codex 사용 권한이 있는 계정이 필요합니다. Codex 프로세스, 파일 도구와 명령 실행은 Android 기기에서 동작합니다.

## 0.2.7의 현재 기능

- **Chat / Codex 전환:** 사이드바에서 네이티브 Codex 작업 화면과 로그인된 ChatGPT 웹 화면을 전환합니다.
- **Pro에게 물어보기:** **+ → Pro에게 물어보기**를 선택하면 다음 Codex 요청에서 한 번의 집중된 Pro 자문을 요청하고, 답변을 같은 Codex 작업의 도구 결과로 돌려받습니다.
- **Fast 모드:** 지원되는 Codex 모델에서 번개 버튼으로 선택할 수 있으며 기본값은 꺼짐입니다.
- **프로젝트와 로컬 파일:** Android 폴더 연결, 프로젝트별 대화, 파일 탐색·수정, 변경 비교와 복구 사본을 지원합니다.
- **기기 내 개발 도구:** Python/pip, Node.js/npm/npx, Git을 번들하며 필요하면 Arch Linux ARM 환경을 추가로 설치할 수 있습니다.
- **Plugins · Skills · MCP:** 플러그인 설치/연결, 스킬 폴더 가져오기, MCP 서버 설정을 지원합니다.
- **휴대폰 제어:** 사용자가 명시적으로 켠 경우 Android 접근성 기능으로 화면 읽기, 탭, 입력, 스크롤 등을 수행할 수 있습니다.
- **플로팅 대화와 음성 입력:** 다른 앱 위에서 대화하거나, 메인 입력창에서 음성을 초안으로 만든 뒤 확인하고 전송할 수 있습니다.
- **다중 계정과 사용량:** 여러 ChatGPT 계정을 로컬에 보관·전환하고 Codex 사용 한도를 확인할 수 있습니다.
- **자동 배포:** public main 빌드가 검증과 서명에 성공하면 GitHub Releases에 해당 버전을 자동 게시합니다.

자세한 변경은 [0.2.7 릴리즈 노트](docs/releases/0.2.7.md)를 참고하세요.

## Chat과 Codex

사이드바 로고 옆 **Chat / Codex** 스위치로 화면을 전환합니다.

**Codex 모드**는 Mobile Codex의 네이티브 작업 공간입니다. 프로젝트 폴더, 로컬 파일, 명령 실행, 승인, Skills, MCP, 이미지, 휴대폰 도구를 담당합니다.

**Chat 모드**는 공식 ChatGPT 웹사이트를 분리된 WebView에서 엽니다. 로그인, 모델 선택, 프로젝트와 대화 기록은 ChatGPT 웹 세션의 기능을 그대로 사용합니다. 원격 웹 페이지에는 Mobile Codex의 Native JavaScript bridge를 노출하지 않습니다.

### Pro에게 물어보기

Codex 요청을 보내기 전에 **+ → Pro에게 물어보기**를 선택합니다. 선택만으로 Pro에 요청이 전송되지는 않으며 다음 요청에만 자문 플래그가 붙습니다.

Codex가 필요한 근거를 포함한 질문을 만들고, 앱은 로그인된 ChatGPT 웹 세션에서 실제 Pro 선택 상태를 확인한 뒤 한 번만 전송합니다. 답변은 원래 Codex 작업으로 돌아오며, 이후 수정·검증 책임은 계속 Codex에 있습니다. Pro 선택 확인이 실패하거나 애매한 경우 임의로 재전송하지 않습니다.

## 프로젝트와 기기 간 메타데이터

Android의 프로젝트 ID와 각 기기의 로컬 폴더 연결은 분리되어 있습니다. 같은 프로젝트로 묶기, 로컬 폴더 다시 연결, 이름 변경과 프로젝트 메타데이터 이동을 지원합니다.

**0.2.7에서는 Android 앱이 GitHub에 직접 연결해 프로젝트를 동기화하지 않습니다.** Android에서는 JSON 내보내기/가져오기로 프로젝트 ID, 이름, 병합 이력을 옮깁니다. 이 파일에는 소스 파일, 대화, 인증정보, 로컬 절대경로가 들어가지 않습니다.

선택 기능인 [PC companion](packages/desktop-companion)은 같은 형식을 사용하며, PC에서는 비공개 GitHub 저장소를 통해 해당 메타데이터를 동기화할 수 있습니다.

```sh
node packages/desktop-companion/cli.cjs init
node packages/desktop-companion/cli.cjs sync connect OWNER/REPO
node packages/desktop-companion/cli.cjs sync push PROJECT_ID
node packages/desktop-companion/cli.cjs sync pull
```

자세한 내용은 [프로젝트 교환 가이드](docs/project-transfer.ko.md)를 참고하세요.

실험적으로 추가됐던 **Codex Cloud 작업 화면은 0.2.7에 포함되지 않습니다.** 번들 Codex CLI의 cloud 명령은 새 작업 전송·조회는 가능하지만 기존 cloud 작업에 이어서 요청할 수 없어, 완전한 기기 간 연속 작업 기능으로 제공하지 않습니다.

## 주요 기능

| 영역 | 현재 동작 |
| --- | --- |
| 대화 | 일반 대화와 프로젝트별 대화, 스트리밍, 중지, 실행 중 추가 지시 |
| 모델 | 계정에서 제공되는 Codex 모델, reasoning effort, 승인 방식, 작업 권한, 선택적 Fast |
| Pro 자문 | 사용자가 명시적으로 선택한 다음 요청에 한 번의 Pro 자문 추가 |
| 파일 | 탐색·검색·읽기·생성·수정·이름 변경·이동·삭제, 앱 편집의 복구 사본 |
| 첨부 / 이미지 | 다중 첨부, 이미지 미리보기, 생성 이미지 갤러리, 확대와 원본 저장 |
| 터미널 | 번들 Android 네이티브 개발 도구로 명령 실행과 출력 스트리밍 |
| Linux | 선택 설치형 Arch Linux ARM, 현재 프로젝트를 `/workspace`로 연결 |
| 변경 검토 | Git 상태/diff 확인, 지원되는 작업 파일과 복구 사본 복원 |
| Plugins / Skills / MCP | 플러그인 설치·활성화, `SKILL.md` 폴더 가져오기, MCP 설정 |
| 휴대폰 도구 | 명시적 opt-in 후 화면 읽기·탭·입력·스크롤·탐색 |
| 음성 | 메인 입력창에서 Done/Cancel 후 직접 전송, 플로팅 대화 지원 |
| 계정 | 여러 로컬 계정 프로필, 계정 전환, Codex 사용 한도 표시 |
| 업데이트 | GitHub Releases 확인, APK 검증, Android 설치 화면 실행 |

## 설치

필요 조건:

- ARM64 Android 기기
- Android 10 이상
- 최신 Android System WebView
- 인터넷 연결
- Codex를 사용할 수 있는 ChatGPT 계정

[0.2.7 릴리즈](https://github.com/nokryong/mobile-codex/releases/tag/v0.2.7)에서 **`mobile-codex-0.2.7-arm64.apk`**를 내려받아 Android 패키지 설치기로 엽니다. 브라우저나 파일 관리자에 **이 출처의 앱 설치 허용**이 필요할 수 있습니다.

Android 또는 Play Protect가 사이드로드 앱 경고를 표시한다면, APK가 이 저장소의 Releases에서 받은 파일인지 확인하고 함께 제공되는 SHA-256 메타데이터를 비교한 뒤 설치 여부를 결정하세요.

### 처음 실행

1. 기기 코드 로그인으로 ChatGPT 계정을 연결합니다.
2. 일반 대화를 시작하거나 프로젝트 폴더를 추가합니다.
3. 공유 저장소에서 셸 접근이 필요하면 **설정 → 도구**에서 필요한 파일 접근 권한을 허용합니다.
4. 모델, 추론 강도, 승인 방식, 작업 권한을 고릅니다.
5. **+**는 첨부/Pro 자문, **@**는 파일·앱, **$**는 Skills에 사용합니다.

저장된 로컬 대화는 Codex 런타임을 시작하지 않아도 열람할 수 있습니다. 프로젝트 목록에서 제거해도 실제 폴더나 파일은 삭제되지 않습니다.

## 업데이트와 릴리즈

`main`에 정상 push가 들어가면 Android workflow가 시작됩니다. 성공한 정식 빌드는 다음 과정을 거칩니다.

1. 필요한 소스/UI 검사를 실행하거나 검증된 결과를 재사용
2. ARM64 APK 빌드
3. 등록된 원래 배포 서명키 확인
4. APK 메타데이터와 SHA-256 생성
5. 해당 `versionName`으로 GitHub Releases 게시

이미 공개된 릴리즈 파일은 덮어쓰지 않습니다. 다음 배포는 반드시 `versionName`과 `versionCode`를 함께 올립니다.

앱 ID는 `dev.mobilecodex.app`입니다. 기존 설치 위에 업데이트하려면 서명 인증서가 호환돼야 합니다. 앱을 제거하면 앱 내부 대화, 계정 정보와 복구 사본도 삭제됩니다.

## 선택형 Linux 환경

필요할 때 Arch Linux ARM rootfs를 내려받아 설치할 수 있습니다. 활성화하면 Codex와 터미널이 앱의 Linux launcher를 통해 Linux 명령을 실행합니다. 현재 프로젝트는 `/workspace`로 노출되며 Linux 패키지와 홈은 앱 내부 저장소에 남습니다.

Linux 환경은 APK와 분리된 선택 기능이며 제거해도 연결된 프로젝트 폴더는 삭제되지 않습니다.

[Linux 환경 문서](docs/linux-runtime.md)

## 휴대폰 제어와 개인정보

휴대폰 제어는 사용자가 Mobile Codex 접근성 서비스를 켜고 앱 안에서 제어를 명시적으로 활성화하기 전까지 동작하지 않습니다. Android 버전과 화면 상태에 따라 UI 요소 읽기, 지원되는 스크린샷, 탭, 입력, 스크롤을 사용할 수 있습니다.

작업에 사용된 화면 내용이나 스크린샷은 AI 서비스로 전달되고 대화 기록에 남을 수 있습니다. 접근성 권한만 켠 상태에서는 자동 조작을 시작하지 않으며 화면 위 중지 버튼이나 설정에서 즉시 끌 수 있습니다.

ChatGPT/Codex 인증정보, 프로젝트 폴더 연결과 복구 데이터는 앱 전용 저장소에 보관합니다. 프로젝트 JSON 교환에는 인증정보와 로컬 절대경로를 포함하지 않습니다.

## Android 제약

- root 권한이 없으며 Android 저장소/앱 샌드박스 규칙을 따릅니다.
- 공유 저장소는 실행 권한과 심볼릭 링크에 제약이 있을 수 있습니다. 패키지 관리자와 Git 내부 작업은 앱 내부 저장소가 더 안정적일 수 있습니다.
- 내장 텍스트 편집기는 UTF-8 텍스트와 파일 크기에 제한이 있습니다. 일반 Codex/셸 도구의 한도와는 별개입니다.
- Android 포트에는 데스크톱 Codex와 같은 명령 sandbox가 없습니다. 앱 작업 권한과 Android OS 권한이 실제 경계입니다.
- 일부 패키지는 데스크톱 Linux 바이너리·서비스를 요구해 Android에서 직접 실행되지 않습니다. 필요한 경우 ARM64 Linux 환경을 사용하세요.
- 포그라운드 서비스가 있어도 제조사 배터리 정책에 따라 장시간 작업이 중단될 수 있습니다.
- ChatGPT 웹 전용 기능은 Android WebView 지원 범위에 영향을 받습니다.
- Android와 Desktop 사이에 Codex 네이티브 세션 전체가 자동 이식되는 것은 아닙니다. 프로젝트 메타데이터 교환은 완전한 세션 동기화가 아닙니다.

자동 검사와 실기기 검증 범위는 [검증 기록](docs/verification.md)을 참고하세요.

## 소스 빌드

필요 도구: JDK 17, Android SDK 35, NDK 28.2.13676358, Python 3, Node.js 22.

```sh
python3 tools/prepare_runtime.py
python3 tools/build_native.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python3 tools/prepare_devtools.py
npm ci --ignore-scripts
npm test
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

고정된 런타임·개발 도구 입력은 [runtime-lock.json](tools/runtime-lock.json)과 [devtools-lock.json](tools/devtools-lock.json)에 기록합니다.

## 문서

- [프로젝트 교환 / PC companion](docs/project-transfer.ko.md)
- [Android 개발 도구](docs/android-devtools.md)
- [Linux 환경](docs/linux-runtime.md)
- [휴대폰 제어](docs/phone-use.md)
- [음성 입력](docs/voice-input.md)
- [업데이트와 배포](docs/app-updates.md)
- [검증 기록](docs/verification.md)
- [서드파티 고지](THIRD_PARTY_NOTICES.md)

## 라이선스

Mobile Codex의 자체 작성 코드와 독자 제작 아트워크는 **[GPL-3.0-only](LICENSE)**로 제공합니다. 서드파티 구성 요소에는 각각의 라이선스와 고지가 유지됩니다.

Copyright © 2026 nokryong and contributors.
