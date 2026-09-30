# 기기 간 프로젝트 동기화

Mobile Codex 0.2.5는 별도 서버 설치 없이 Android 기기끼리, 또는 Android와 PC 보조 도구 사이에서 프로젝트 ID, 이름, 명시적 병합 이력을 공유한다. 같은 이름의 프로젝트는 자동 병합하지 않는다. 로컬 폴더 연결은 각 기기에 남는다.

Android와 PC 보조 도구 모두 사용자가 지정한 GitHub 비공개 저장소에 직접 연결해 프로젝트 정보를 수동 동기화할 수 있다. 파일 내보내기·가져오기도 계속 지원한다. 자동 동기화, 대화·작업 기록 이전, 공식 Codex Desktop 연동은 아직 포함하지 않는다. 소스 파일은 별도 관리한다.

## 동기화 범위

| 항목 | 동작 |
| --- | --- |
| 프로젝트 ID·이름·명시적 병합 이력 | 선택한 GitHub 비공개 저장소 또는 JSON 파일로 공유 |
| 로컬 작업 폴더 | 각 기기에서 따로 연결. 서로 다른 경로 사용 가능 |
| 대화 기록·진행 중인 작업·소스 파일 | 동기화하지 않음 |
| 로그인·인증정보·설정 | 각 기기에 유지 |
| 실행 시점 | 사용자가 미리보기와 적용을 실행. 자동 백그라운드 동기화 없음 |

Android 앱의 GitHub 동기화는 0.2.6-alpha.5에서 제거했다. Android에서는 프로젝트 정보를 파일 내보내기·가져오기로 옮긴다. 아래 GitHub 저장소 동기화는 PC 보조 도구에만 남아 있다.

## PC에서 실행

Node.js 20 이상이 필요하다. 저장소 안에서 실행하므로 별도 전역 설치는 필요 없다. Android 앱만 설치한 PC에는 이 보조 도구가 생기지 않는다.

```powershell
node packages/desktop-companion/cli.cjs init
node packages/desktop-companion/cli.cjs projects add "mobile-codex"
node packages/desktop-companion/cli.cjs projects list
```

출력된 `projectId`를 사용한다. 아래 예시의 `proj_EXAMPLE`을 실제 ID로 바꾼다.

```powershell
node packages/desktop-companion/cli.cjs projects bind proj_EXAMPLE "C:\CODEX\mobile-codex"
node packages/desktop-companion/cli.cjs export "C:\Transfer\project.json" proj_EXAMPLE
```

출력 폴더는 미리 있어야 하며 기존 파일을 덮어쓰지 않는다. 여러 프로젝트는 ID를 이어서 지정한다. 가져오기 전에 결과를 확인할 수 있다.

```powershell
node packages/desktop-companion/cli.cjs import "C:\Transfer\phone.json" --dry-run
node packages/desktop-companion/cli.cjs import "C:\Transfer\phone.json"
node packages/desktop-companion/cli.cjs status
```

기본 상태 파일은 `~/.mobile-codex/projects.json`이다. `~/.codex`의 인증·설정·대화 파일은 읽거나 수정하지 않는다. 별도 테스트 저장소는 `--home`으로 지정한다.

```powershell
node packages/desktop-companion/cli.cjs --home "C:\Temp\mobile-codex-test" init
```

## GitHub 동기화 저장소

PC의 `gh` 로그인 계정을 사용한다. 계정이 쓰기 가능한 비공개 저장소를 별도로 만든 뒤 연결한다. 아래 예시의 `OWNER/REPO`를 해당 저장소로 바꾼다.

```powershell
node packages/desktop-companion/cli.cjs sync connect OWNER/REPO
node packages/desktop-companion/cli.cjs sync push proj_EXAMPLE
node packages/desktop-companion/cli.cjs sync status
node packages/desktop-companion/cli.cjs sync pull --dry-run
node packages/desktop-companion/cli.cjs sync pull
```

`push`는 지정한 프로젝트만 업로드하고, `pull`은 저장소의 프로젝트 정보를 가져온다. GitHub Contents API를 통해 기본 브랜치의 `projects.json`에 Git 커밋을 만든다. 파일의 기존 SHA를 확인하고, 동시에 다른 기기가 갱신했으면 다시 읽어 이벤트를 합친 뒤 최대 3회 시도한다. 같은 정보를 다시 올리면 커밋을 추가하지 않는다.

기존 로컬 폴더·인증정보는 업로드하지 않는다. 연결 정보는 로컬 `~/.mobile-codex/sync.json`에 저장하며 토큰을 복사하지 않는다. 저장소가 공개로 바뀌거나 저장소 ID·기본 브랜치가 달라지면 중단한다. 현재는 명령을 실행할 때만 교환한다. Android도 같은 비공개 저장소에 직접 연결할 수 있으며 파일 가져오기·내보내기는 대체 경로로 계속 지원한다.

## Android에서 GitHub 동기화

**설정 → 동기화**에서 GitHub 연결과 프로젝트 정보 교환을 관리한다. 프로젝트 메뉴의 가져오기·내보내기는 JSON 파일 교환용이다.

1. **GitHub로 로그인**을 누른다. 앱에 표시된 일회용 인증 코드를 복사하고 **GitHub에서 승인**을 눌러 브라우저에서 로그인·승인한다. 개인 액세스 토큰을 만들거나 앱에 붙여넣지 않는다.
2. 앱으로 돌아와 쓰기 가능한 비공개 저장소를 목록에서 선택하고 연결한다. 더 많은 저장소는 추가로 불러올 수 있다. 저장소는 미리 만들어 두어야 한다.
3. 이 기기에서 **올릴 프로젝트**를 선택한다. 처음에는 아무 프로젝트도 선택하지 않으며, 선택은 이 기기에 저장한다. 선택하지 않으면 원격 정보를 가져오기만 한다.
4. **동기화 미리보기**에서 받을 프로젝트, 이름 충돌, 추가 이벤트와 올릴 프로젝트 수를 확인한 뒤 적용한다. 원격의 프로젝트 정보는 모두 가져오며, 업로드는 선택한 프로젝트만 포함한다.
5. 완료하면 프로젝트 목록과 마지막 성공 시간이 갱신된다. 새 프로젝트의 실제 작업 폴더는 각 기기에서 연결한다.

취소하거나 미리보기만 하면 로컬 프로젝트와 원격 파일을 수정하지 않는다. 미리보기 뒤 로컬 상태나 원격 파일이 바뀌면 다시 미리보기해야 한다. 적용 중 GitHub 쓰기가 실패하면 로컬 가져오기를 적용하지 않는다. GitHub 쓰기 뒤 로컬 저장이 실패하면 오류를 표시하며 완료 시간을 갱신하지 않는다. 다시 미리보기하면 중복 이벤트 없이 재시도할 수 있다.

인증 토큰은 Android Keystore의 AES-GCM 키로 암호화해 앱 전용 설정에 저장하며 WebView 응답이나 프로젝트 교환 JSON에 노출하지 않는다. 만료되거나 취소된 인증은 다시 로그인해야 한다. 기존 버전에서 연결한 저장소와 암호화된 인증정보도 설정 화면에서 사용할 수 있다.

현재 로그인은 OAuth App의 `repo` 권한을 요청한다. 이 권한은 선택한 저장소 하나로 제한되는 권한이 아니며 GitHub 승인 화면에서 접근 범위를 확인할 수 있다. 앱의 동기화 작업은 설정에서 선택한 저장소의 `projects.json`만 사용한다. **연결 해제**는 이 기기의 인증정보·연결·선택·완료 시간을 제거하며 원격 파일과 프로젝트 소스를 삭제하지 않는다. GitHub 측 OAuth 승인 취소는 GitHub 계정의 Applications 설정에서 별도로 할 수 있다.

저장소가 공개로 바뀌거나 저장소 ID·기본 브랜치가 달라지면 동기화를 중단한다. 마지막 성공 시간은 이 기기에서 완료한 시각이며 다른 기기의 현재 동기화 여부를 뜻하지 않는다. 자동 동기화, 대화 기록·작업 이어하기·소스 파일 동기화는 포함하지 않는다.

### 배포자용 GitHub 로그인 설정

이 설정은 앱 배포자가 한 번 수행한다. 일반 사용자에게 Client ID나 토큰 입력을 요구하지 않는다.

1. 앱 소유자의 GitHub 계정에서 Mobile Codex 전용 **OAuth App**을 등록하고 **Enable Device Flow**를 켠다. 다른 앱의 Client ID를 빌려 쓰지 않는다.
2. 발급된 공개 **Client ID**를 배포 저장소의 Actions variable `MOBILE_CODEX_GITHUB_CLIENT_ID`에 등록한다. 로컬 빌드는 `GITHUB_OAUTH_CLIENT_ID` 환경변수를 사용한다. OAuth 앱은 배포 주체인 `nokryong` 계정에만 등록하며, 개발 저장소 소유자별로 만들 필요는 없다.
3. Client secret은 이 네이티브 device flow에서 필요하지 않으며 앱·저장소·빌드 설정에 넣지 않는다. 토큰 갱신용 secret도 포함하지 않으며, 만료형 토큰은 만료 시 사용자가 재로그인한다.
4. 새 APK에서 로그인 승인·취소·만료, 저장소 목록, 프로젝트 왕복을 실기기로 확인한 뒤 배포한다.

Client ID가 없는 빌드는 설정에서 로그인 준비가 안 된 상태를 표시하고 로그인 버튼을 비활성화한다. 현재 CI는 Client ID 누락 안내를 출력하고 빌드를 계속하므로, GitHub 로그인 기능을 제공할 배포 저장소에는 위 변수를 반드시 설정한다. 코드와 모의 테스트만으로 GitHub 앱 등록이나 실제 로그인 검증을 완료했다고 간주하지 않는다.

공식 규격: [GitHub OAuth device flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow).

## Android에서 교환

1. 프로젝트 메뉴의 **프로젝트 내보내기**를 누르고 저장 위치를 선택한다.
2. JSON 파일을 사용자가 원하는 수단으로 다른 기기에 전달한다.
3. Android에서는 프로젝트 추가의 **프로젝트 가져오기**를 누른다.
4. 파일을 선택하고 프로젝트 이름, 새 변경 수, 병합 이력을 확인한 뒤 **가져오기**를 누른다.
5. 새 프로젝트에 **로컬 폴더 연결**을 실행한다. 연결 전에는 그 프로젝트에서 작업을 실행할 수 없다.

미리보기나 파일 선택을 취소하면 상태는 바뀌지 않는다. 미리보기 뒤 로컬 프로젝트 상태가 달라졌으면 다시 가져와야 한다. 파일 선택 중 Android가 화면을 재생성한 경우 가져오기를 다시 시작한다.

## 같은 프로젝트로 묶기

양쪽 프로젝트 정보를 한 기기로 가져온 뒤 Android의 **프로젝트 병합** 또는 PC 명령을 사용한다.

```powershell
node packages/desktop-companion/cli.cjs projects merge proj_SOURCE proj_TARGET
```

대상 이름을 사용하고 정렬상 앞선 ID를 대표 ID로 유지한다. 기존 ID도 별칭으로 유효하다. 결과를 다시 내보내 상대 기기에 가져오면 같은 관계가 반영된다. 폴더나 소스 파일은 병합하지 않는다. 현재 버전은 공유 ID 병합 취소를 제공하지 않는다.

## 이름 충돌

두 기기에서 독립적으로 이름을 바꾸면 두 값 모두 보존한다. Android의 **이름 충돌 해결**에서 선택하거나 PC의 `projects rename <projectId> <name>`으로 확정한다. 결과를 다시 교환하면 충돌이 해소된다. 충돌 중 기본 표시 이름은 정렬 순서로 정한다.

## 교환 범위와 저장

파일에는 버전, 프로젝트 이벤트 ID, 작성 기기 ID, 프로젝트 ID, 이름, 병합 상대 ID, 선행 이벤트 ID만 들어간다. 로컬 경로·바인딩·대화·소스·설정·인증정보는 제외한다. 과거 프로젝트 이름은 이력에 남으므로 전달 전에 확인한다. 알려진 토큰 형태가 이름에 포함되면 교환을 거부하지만 임의의 비밀 문자열 전체를 판별하지는 못한다.

동일 이벤트를 다시 가져와도 중복 적용하지 않는다. 같은 이벤트 ID의 내용이 다르거나 알 수 없는 필드, 중복 JSON 키, 잘못된 UTF-8, 끊어진/순환하는 이력은 거부한다. 로컬에서 제거한 연결은 과거 파일을 다시 가져와도 되살리지 않는다.

초기 제한은 교환 파일 및 전체 이벤트 이력 1 MiB, 이벤트 1,024개, 이름 512 UTF-16 단위다. Android의 직접 이름 입력은 기존 제한인 200자를 유지한다. 이력 압축·서명·암호화는 아직 제공하지 않는다. 가져오기로 병합 관계까지 적용되므로 신뢰하는 본인 기기에서 내보낸 파일을 사용한다.

Android는 기존 레지스트리를 백업한 뒤 이벤트 이력을 추가하며 프로젝트·바인딩 ID를 유지한다. 저장 성공 후 메모리에 반영한다. PC는 명령별 잠금과 임시 파일 교체를 사용한다. 비정상 종료로 `.writer-lock`이 남으면 실행 중인 보조 도구가 없는지 확인한 뒤 해당 잠금 디렉터리만 제거한다.

## 검증

```powershell
npm test
npm run test:project-layout
# JAVA_HOME: JDK 17 이상, JSON_JAR: org.json JAR의 절대 경로
npm run test:project-transfer
```

마지막 명령은 실제 Android 프로젝트 레지스트리 Java 코드와 PC CLI로 파일을 왕복한다. JUnit 대상은 `PortableProjectsTest`, `ProjectTransferFilesTest`, `ProjectIdentityIntegrationTest`와 기존 프로젝트/오프라인 세션 회귀 테스트다. 브라우저 검증은 모의 Native 브리지로 320·393·1280px에서 가져오기 확인·취소, 이름 충돌 선택, 내보내기를 실행한다.

자동 검증은 실제 Android 기기, 문서 제공자의 파일 선택·저장, PC와 휴대폰 사이의 실제 파일 전달을 대신하지 않는다.
