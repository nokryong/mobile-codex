# App updates — 0.1.11 alpha

**설정 → 업데이트**에서 현재 버전, 배포 저장소, 시험판 포함 여부를 확인하고 **업데이트 확인**을 누릅니다. 새 버전이 있으면 다운로드 크기·릴리스 설명을 읽고 **다운로드**합니다. 진행 중 취소할 수 있고, 완료 후 **업데이트 설치**로 Android 설치 확인 화면을 엽니다. 앱을 종료하거나 기기가 다운로드를 중단하면 남은 부분 파일을 다음 실행에서 정리하며, 처음부터 다시 다운로드할 수 있습니다. 완료한 APK는 보관하고 설치 전에 다시 검증합니다.

설치 허용이 필요하면 **이 앱의 설치 허용**으로 Android 설정을 열고 직접 허용한 뒤 돌아와 설치 버튼을 누릅니다. 자동으로 권한을 켜거나 설치하지 않습니다. 대화 작업·터미널 명령·음성 입력을 마친 뒤 설치하세요. 설치/설정 화면을 열 때 휴대폰 자동 조작을 중지하고 플로팅 창을 닫습니다. 플로팅 초안은 기존 보관 정책대로 유지합니다.

## Release source

기본 조회 대상은 공개 저장소 `nokryong/mobile-codex`입니다. 로그인 토큰 없이 GitHub Releases API의 최근 20개 릴리스를 확인합니다. 저장소를 `소유자/저장소` 형식으로 변경할 수 있으며, 변경하면 이전 저장소의 설치 후보를 해제합니다. 앱 시작 시에는 저장된 상태·현재 버전만 읽습니다. 사용자 요청 없이 릴리스를 조회하거나 APK를 다운로드하지 않습니다.

지원하는 파일은 `mobile-codex-<version>-arm64.apk`이며, 예시는 `mobile-codex-0.1.11-alpha-arm64.apk`입니다. 버전은 숫자로 비교하고 `alpha`, `beta`, `rc` 및 번호를 지원합니다. GitHub 자산의 `digest`에 SHA-256이 있어야 하고, 파일 크기는 2 GiB 이하여야 합니다. ZIP 묶음, 다른 아키텍처, 초안 릴리스, 해시 없는 자산은 설치 후보로 삼지 않습니다. 시험판 제외 설정이면 시험판 릴리스와 시험판 APK 이름을 모두 제외합니다. 접근 거부·조회 한도·응답 오류·적합한 자산 없음은 최신 버전이라는 뜻으로 표시하지 않습니다.

현재 공개 릴리스가 설치된 개발 버전보다 오래됐다면 그 사실을 표시합니다. CI 빌드 성공이나 main 커밋 자체가 공개 릴리스를 뜻하지 않습니다. 이 변경은 공개 저장소에 릴리스를 생성하거나 업로드하지 않습니다.

## Verification and installation

- GitHub HTTPS와 정해진 GitHub 자산 호스트로만 다운로드하며 리디렉션도 검사합니다. 로그인 정보는 전달하지 않습니다.
- 실제 바이트 수와 SHA-256을 GitHub 자산 정보와 비교한 뒤, APK의 패키지명·증가한 versionCode·표시된 versionName·최소 Android 버전·서명 호환성을 검사합니다.
- 설치 직전에 파일 전체를 다시 읽어 같은 검증을 수행합니다. 파일이 사라지거나 변경됐다면 설치 화면을 열지 않습니다.
- 현재 서명 집합이 같거나, 단일 서명 앱의 새 APK 서명 이력에 현재 서명이 포함된 경우만 후보로 허용합니다. 예전 키로 되돌리는 것을 자동 허용하지 않습니다. 실제 APK 서명과 키 회전 권한의 최종 검증은 Android 패키지 설치 관리자가 수행합니다.
- 서명이 다르면 기존 데이터 유지 업데이트를 진행할 수 없다는 설명을 표시합니다. 앱 삭제·데이터 초기화를 실행하지 않습니다. 기존 설치와 호환되는 서명키로 빌드해야 합니다.
- 설치 파일은 앱 캐시에 보관하고, 읽기 권한을 부여한 해당 APK URI만 설치 관리자에 전달합니다. 파일 제공자는 업데이트 디렉터리만 노출합니다. 설치 화면이 열려 있는 동안 다운로드 교체·삭제·저장소 변경을 막습니다.

## Preparing a release

기존 서명 환경 변수로 APK를 빌드합니다. 기존 설치와 같은 키 또는 Android가 인정하는 올바른 키 회전이 필요합니다. debug CI 키를 배포 키라고 간주하면 안 됩니다. 키를 생성·교체하거나 소스 저장소에 올리지 마세요.

```sh
python3 tools/prepare_update_release.py --build-tools "$ANDROID_HOME/build-tools/35.0.0"
```

스크립트는 Android `apksigner verify` 성공 후 `aapt2`에서 실제 앱 ID·버전·ABI를 읽고 이름 있는 APK, `.sha256`, `mobile-codex-update.json`을 `artifacts/update`에 만듭니다. JSON은 버전·크기·해시·공개 서명 인증서 지문을 기록합니다. 앱의 다운로드 검증은 GitHub 자산의 `digest`를 사용합니다.

CI의 **mobile-codex-update-assets**에는 같은 파일이 들어 있습니다. 공개 배포할 때는 검토한 APK와 체크섬·메타데이터를 선택한 저장소의 GitHub Release에 업로드하고, 기존 **mobile-codex-arm64-debug** 묶음에 포함되는 대응 소스·라이선스 자료도 함께 배포하세요. 준비 스크립트와 CI는 공개 업로드를 자동 실행하지 않습니다.

## Actions signing

`main`의 push 및 수동 실행은 저장소 Secret **`MOBILE_CODEX_SIGNING_JSON`**을 사용합니다. 테스트 후 APK를 기존 키로 다시 서명하고, 아래의 고정 인증서 SHA-256과 일치하는지 확인한 뒤 APK와 업데이트 메타데이터를 생성합니다.

```text
f9a8d59abf5ab33b44879ade1b9500c385b2cf03e1cb17f94936eadb85337bd7
```

Secret 값은 다음 필드가 있는 JSON입니다. `keystoreBase64`는 기존 키스토어 파일 바이트를 Base64로 인코딩한 값이며, 나머지는 해당 키의 자격증명입니다. 예시 자리표시자를 실제 값으로 바꿔 저장소의 **Settings → Secrets and variables → Actions → New repository secret**에 등록합니다.

```json
{
  "keystoreBase64": "<base64-encoded existing keystore>",
  "keyAlias": "<existing alias>",
  "storePassword": "<existing store password>",
  "keyPassword": "<existing key password>"
}
```

키와 이 JSON은 Git·Actions 캐시·Artifacts에 넣지 않습니다. 서명 단계에서만 Secret을 전달하며, 키 파일은 checkout 밖의 임시 디렉터리에 권한 0600으로 생성하고 성공·실패 시 모두 정리합니다. 비밀번호는 명령줄 인수가 아닌 환경 변수로 전달합니다. 공개 로그에는 인증서 지문만 출력합니다.

Secret 누락·형식 오류·서명 오류·인증서 불일치는 빌드 실패로 처리하며 다른 키로 자동 대체하지 않습니다. `pull_request` 실행은 Secret을 전달받지 않고 빌드·테스트만 수행하며 설치용 APK를 업로드하지 않습니다. 기존 키를 사용하는 배포는 검토 후 `main`에 반영한 코드에 한정합니다.

키가 맞아도 설치된 앱의 패키지명·버전 조건이 맞아야 합니다. 이 설정은 초기 버전의 다른 패키지명을 되돌리거나 과거 임시 debug 키로 설치한 앱을 자동 이전하지 않습니다.

## Tests and physical-device limits

자동 검사: 숫자/시험판 버전 정렬, 다른 저장소 URL·잘못된 해시·초안 제외, 스트림 크기·해시·취소, 패키지·버전·서명 정책, 모의 PackageManager의 APK 검사, 변조 후 설치 거부, 설치 중 파일 변경 방지, 다운로드 복구, FileProvider 경로 범위, 설정 변경 중 결과 경합, 명시적 설치 클릭, 진행 상태·오류 텍스트 표시. CI에서 실제 산출 APK에 `apksigner verify`를 실행합니다.

실기기 미확인: 삼성 설치 허용 화면, 설치 취소·재시도, 같은 키로 서명한 두 버전 사이의 실제 업데이트와 로그인·대화 유지, 실제 서명키 회전, 다운로드 중 앱 종료·저장 공간 부족. 모의 패키지 검사 성공은 이 실기기 검증을 대체하지 않습니다.

References: [GitHub release assets](https://docs.github.com/en/rest/releases/assets), [Android PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager), [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
