# Android 개발 도구 번들

0.1.7은 Codex와 터미널에서 공통으로 사용할 Python 3.14.6, Node.js 24.18.0,
Git 2.55.0, npm 11.19.1, pip 26.2.1을 ARM64 APK에 포함한다.
별도 Termux 앱, 루트 권한, 첫 실행 시 실행 파일 다운로드가 필요하지 않다.
실기기 실행 성공을 확인한 버전으로 표기하지 않는다.

## 패키징과 설치

- `tools/devtools-lock.json`은 Termux 공식 패키지 저장소의 `.deb` URL·버전·SHA-256,
  소스 및 빌드 레시피 커밋을 고정한다. 자동 최신 버전 선택은 일반 빌드에서 하지 않는다.
- `prepare_devtools.py`는 체크섬과 ARM64 ELF를 확인하고, 실행 파일·공유 라이브러리·
  Python 확장 모듈을 Android가 설치할 `lib*.so` 파일로 패키징한다.
  Debian 설치 스크립트나 npm lifecycle 코드를 호스트에서 실행하지 않는다.
- LIEF로 DT_NEEDED와 SONAME을 설치 파일명에 맞추고 기존 RPATH/RUNPATH를 변경한다.
  의존 라이브러리 누락 및 기존 Codex용 libc++에 필요한 심볼이 없으면 실패한다.
- `build_native.py`는 NDK r28c로 npm/npx/pip 명령 런처와 스크립트 실행 호환 계층을 만든다.
  APK 라이브러리 외부의 ELF를 실행하도록 복사하거나 OS 실행 제한을 해제하지 않는다.
- `DevTools`는 payload ZIP의 SHA-256을 검사하고 경로 이탈을 차단한 뒤 별도 임시
  디렉터리에 추출한다. 완성된 버전만 활성화하고, APK 재설치로 달라진 네이티브
  라이브러리 경로에 맞춰 심볼릭 링크를 복구한다. 사용자 pip/npm 설치 경로는 별도다.

## 지원 범위

일반 Python/JavaScript 실행, 표준 라이브러리, npm scripts, 호환되는 pip/npm 패키지,
Git의 로컬 저장소 작업과 HTTPS 전송을 대상으로 한다. Git HTTPS 인증은 사용자 설정이
필요하다. 인증서 검증을 끄지 않는다. SSH 클라이언트·Perl·컴파일러·JDK는 번들하지 않는다.
따라서 Git SSH 전송, git-svn 및 네이티브 확장 빌드 등의 추가 도구가 필요한 작업은
별도 지원 전까지 작동한다고 보장하지 않는다.

셸 스크립트·Python·Node의 일반적인 shebang을 APK에 설치된 인터프리터로 연결한다.
지원하지 않는 복잡한 `env` 옵션을 조용히 제거하여 실행 의미를 바꾸지 않는다.
공유 저장소의 심볼릭 링크 제한, Python 가상 환경, 플랫폼 전용 패키지, 장시간
백그라운드 실행은 기기/패키지별 확인이 필요하다. 설정의 실행 확인 결과도 해당
검사 명령의 성공만 의미하며 모든 외부 패키지의 호환성을 보장하지 않는다.

## 출처와 재배포

Termux 포트와 해당 upstream 프로젝트의 라이선스를 따른다. 전체 패키지 목록,
바이너리 체크섬, 원본 소스 URL·체크섬, 수정 내역은 lock 및 생성된 manifest에 있다.
APK payload의 `share/licenses/mobile-codex` 및 각 패키지의 `share/doc` 아래에 고지 문구를 포함한다.

APK를 배포할 때 `python3 tools/prepare_devtools_sources.py`로 생성한
`artifacts/devtools-corresponding-source.zip`도 같은 위치에서 제공한다.
해당 묶음에는 고정된 upstream 아카이브, Termux 레시피·패치와 앱의 런처/패키징
소스가 포함된다. APK·다운로드 캐시·서명키는 Git에 커밋하지 않는다.

이 소스 프로젝트의 기본 Java 패키지와 앱 ID는 `dev.mobilecodex.app`이다.
이전 앱 ID가 다른 배포본과는 별도 설치되며, 동일한 서명만으로 기존 앱 데이터를
승계한다고 안내하지 않는다.

## 검증 경계

호스트에서 패키지 무결성·ELF 의존성·APK 구성, Java/DOM 테스트, Android 빌드와
lint를 검사한다. Linux CI에서는 실행 호환 계층을 호스트용으로 컴파일한 동작
테스트도 수행한다. 이는 ARM64 Android에서 실행한 결과와 다르다.
`DevToolsSmokeTest`는 로그인·네트워크 없이 도구 기동과 Git 커밋·npm 스크립트를
검사하는 선택적 기기 테스트다. 기기에서 실행하지 않았다면 실행했다고 보고하지 않는다.
