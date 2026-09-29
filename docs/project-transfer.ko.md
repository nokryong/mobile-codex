# 프로젝트 정보 파일 교환

별도 서버 없이 Android와 PC 사이에서 프로젝트 ID, 이름, 명시적 병합 이력을 교환한다. 같은 이름의 프로젝트는 자동 병합하지 않는다. 로컬 폴더 연결은 각 기기에 남는다.

Android와 PC 보조 도구 모두 사용자가 지정한 GitHub 비공개 저장소에 직접 연결해 프로젝트 정보를 수동 동기화할 수 있다. 파일 내보내기·가져오기도 계속 지원한다. 자동 동기화, 대화·작업 기록 이전, 공식 Codex Desktop 연동은 아직 포함하지 않는다. 소스 파일은 별도 관리한다.

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

프로젝트의 **프로젝트 내보내기** 또는 프로젝트 추가의 **프로젝트 가져오기**를 누르면 파일과 GitHub 중 전송 방법을 고를 수 있다. 처음 GitHub를 선택하면 다음 정보를 입력한다.

1. 쓰기 가능한 비공개 저장소를 `OWNER/REPO` 형식으로 입력한다.
2. 해당 저장소의 Contents를 읽고 쓸 수 있는 GitHub 토큰을 입력한다.
3. 앱은 저장소가 실제로 비공개인지, 토큰에 쓰기 권한이 있는지 확인한 뒤 저장소 ID와 기본 브랜치를 고정한다.
4. 토큰은 Android Keystore의 AES-GCM 키로 암호화해 앱 전용 설정에만 저장한다. 프로젝트 교환 JSON이나 GitHub의 `projects.json`에는 토큰이 들어가지 않는다.

**GitHub에서 가져오기**는 원격 `projects.json`을 읽은 뒤 기존 파일 가져오기와 같은 미리보기·확인 단계를 거친다. 확인 전에는 로컬 프로젝트 상태를 바꾸지 않는다.

**GitHub에 동기화**는 선택한 프로젝트의 휴대용 이벤트를 원격 이벤트와 합집합으로 병합한 뒤 GitHub Contents API로 커밋한다. 같은 이벤트는 중복되지 않으며, 동시에 다른 기기가 파일을 갱신해 409/422 경합이 발생하면 원격을 다시 읽어 최대 3회 병합을 재시도한다. force push는 하지 않는다.

Android와 PC companion은 동일한 `projects.json` 규격을 사용한다. 저장소가 공개로 바뀌거나 저장소 ID·기본 브랜치가 달라지면 동기화를 중단하고 다시 연결하도록 요구한다. **GitHub 연결 해제**는 이 기기의 암호화된 토큰과 연결 정보만 제거하며 원격 파일이나 프로젝트 소스는 삭제하지 않는다.

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
