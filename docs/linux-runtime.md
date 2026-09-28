# 선택 설치하는 Linux 환경

## 설치와 사용

**설정 → 도구 → Linux 환경**에서 **설치**를 누릅니다. Arch Linux ARM의 고정된 ARM64 배포본을 내려받고 검증한 뒤 압축을 풉니다. 실제 PRoot 실행 확인까지 통과하면 **Linux 환경 사용**을 켤 수 있습니다. 앱 업데이트만으로 다운로드하거나 Linux 실행을 자동 활성화하지 않습니다.

- 다운로드: 151,744,988 bytes, 약 152 MB.
- 설치 전 여유 공간: 압축 파일·압축 해제 상한·작업 여유 공간을 합쳐 약 1.62 GB를 확인합니다. 화면에는 기기의 실제 남은 공간을 표시합니다.
- APK에는 작은 Android용 PRoot 실행기와 설정 코드만 추가합니다. Linux 루트 파일 시스템은 포함하지 않습니다.
- 설치 중 취소하거나 실패 후 다시 시도할 수 있습니다. 불완전한 설치는 사용할 수 있는 환경으로 표시하지 않습니다.
- 사용을 끄면 기존 Android 개발 도구로 돌아갑니다. Linux 패키지와 홈 파일은 유지합니다.
- **Linux 환경 삭제**는 Linux 내부 패키지와 홈을 삭제합니다. `/workspace`에 연결했던 프로젝트 폴더는 삭제하지 않습니다.

활성화하면 앱 터미널이 현재 프로젝트를 Linux의 `/workspace`로 연결합니다. 다음 명령으로 환경을 확인할 수 있습니다.

```sh
uname -m
pwd
cat /etc/arch-release
```

Codex는 기존 셸 도구로 다음처럼 실행합니다. 별도의 무승인 실행 도구를 추가하지 않으며 기존 작업 승인 설정을 따릅니다.

```sh
mc-linux status
mc-linux -- /bin/bash -lc 'pwd && command -v python && command -v gcc'
```

`mc-linux`는 호출한 셸의 현재 폴더를 연결합니다. 필요한 개발 도구는 Linux 내부에서 설치합니다. 모든 개발 도구가 기본 설치되어 있다고 가정하지 않습니다.

## 호환 범위

- Android 10 이상 ARM64 기기용입니다. 별도 커널이나 가상 머신을 설치하지 않습니다.
- PRoot는 Android 권한 안에서 Linux 사용자 공간을 실행합니다. Android root 권한이나 독립 보안 샌드박스를 제공하지 않습니다.
- x86·Windows 실행 파일, systemd와 커널 서비스, 모든 데스크톱 앱을 지원하는 환경은 아닙니다. 현재 번들의 System V shared memory에도 제약이 있습니다.
- 공유 저장소의 실행·심볼릭 링크 제약은 그대로 적용됩니다. 해당 기능이 필요한 빌드는 앱 내부 작업 폴더를 사용합니다.
- 앱 터미널은 명령과 출력 스트림을 제공합니다. 전체 화면 인터랙티브 터미널이나 PTY는 제공하지 않습니다.
- Linux 홈과 패키지는 앱 비공개 저장소에 있습니다. 기존 Codex 로그인과 Git 인증 파일을 Linux 홈으로 자동 복사하지 않습니다.
- Linux의 기본 DNS는 `1.1.1.1`과 `8.8.8.8`입니다. 해당 DNS를 허용하지 않는 네트워크에서는 Linux 안의 `/etc/resolv.conf`를 네트워크 환경에 맞게 설정합니다.
- 모델 추론에는 계속 온라인 연결이 필요합니다.

## 구현과 검증

`LinuxRuntime.java`가 설치 상태·명시적 활성화·다운로드·취소를 관리합니다. 루트 파일 시스템 URL, 압축 크기, SHA-256, 최대 해제 크기는 `assets/linux/manifest.json`에 고정합니다. `runtime.py`는 검증된 아카이브를 별도 준비 폴더에 해제하고, 경로 이탈·링크 경유 쓰기·특수 파일·크기 상한을 검사합니다. 설치 확인 후 준비 폴더를 원자적으로 교체합니다.

PRoot와 loader는 Android가 APK에서 설치하는 `nativeLibraryDir`에 둡니다. 기존 Android Python을 통해 실행 스크립트를 시작한 뒤 PRoot 진입 시 Android 동적 로더·Python 환경을 전달하지 않습니다. Codex app-server는 계속 기존 Android 실행 파일을 사용합니다. 일반 Chat WebView와 로그인 경로도 유지합니다.

`mc-linux -- <명령>`의 구분자는 앱 실행기가 소비합니다. PRoot는 별도 `--`를 지원하지 않으므로 옵션 뒤에 실행할 명령을 바로 전달합니다. 명령 이후의 인수와 구분자는 그대로 유지합니다.

`python3 -m unittest discover -s tests -p test_linux_runtime.py -v`는 `MC_LINUX_PROOT`와 `MC_LINUX_LOADER`가 있는 Android 환경에서 실제 설치 바이너리의 인자 처리·loader·실행 경로도 확인합니다. 이 테스트는 앱에 포함된 실행 파일을 사용하며, Linux를 설치하거나 활성화하지 않습니다. 일반 CI에서는 이 기기 전용 검사만 건너뜁니다.

GitHub Actions에서 JS·Python·Android 단위 테스트, UI 레이아웃, Android 빌드와 Lint를 실행합니다. 이 검사는 실제 Android 기기에서의 Linux 설치·명령 실행을 대신하지 않습니다. 실기기 확인 순서는 설치 → 사용 켜기 → 터미널 `uname -m`/`pwd` → Codex `mc-linux status` → 사용 끄기 → 기존 Android 터미널 확인입니다. 삭제 테스트는 Linux 홈에 필요한 파일이 없는 상태에서 진행합니다.
