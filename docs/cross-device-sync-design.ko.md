# 기기 간 프로젝트·작업 상태 공유 설계

검토 기준: `3c7c8b7`, Android `0.1.43-alpha`, 번들 Codex `0.155.1`.
상태: 구현 전 기술 검토. 아래 새 모델·명령·파일은 제안이며 현재 기능이 아니다.

후속 구현: [1단계 공유 프로젝트](project-identities.ko.md), [2단계 프로젝트 파일 교환](project-transfer.ko.md). 이 문서는 최초 설계 기준을 보존하며, 현재 구현 범위와 검증 결과는 후속 문서를 따른다.

## 1. 현재 구조

`filesDir`는 Android 앱 전용 저장소를 뜻한다. 실제 기기 경로를 상수로 사용하지 않는다.

| 데이터 | 현재 저장 위치 / 담당 코드 | 의미 |
| --- | --- | --- |
| 프로젝트 목록·선택·연결 해제 기록 | SharedPreferences `projects.registry`; `DocumentStore.java`, `core/ProjectRegistry.java` | `{key,name,uri}`, `selectedKey`, `removed`를 JSON으로 저장 |
| 이전 단일 프로젝트 | SharedPreferences `workspace.uri` | 다중 프로젝트 레지스트리로 이전하는 입력 |
| 프로젝트 식별 | `DocumentStore.select`, `WorkspacePath.hash` | 최초 SAF URI의 SHA-256; 재연결 시 기존 key 유지 가능 |
| 대화 목록·제목·화면용 메시지 | `filesDir/sessions.json`; `Engine` 생성자, `persistSessions`, `snapshot` | 대화 id는 현재 Codex threadId와 같고 `workspaceKey`로 프로젝트에 연결 |
| 실제 Codex 실행·기록 | `filesDir/.codex`; `CodexHome`, `Engine.start`, `DevTools.configure` | Java가 네이티브 Codex `app-server --listen stdio://`를 실행; CODEX_HOME 지정 |
| 권한·승인 정책 | SharedPreferences `settings`; `Engine.handle` | `permissions`, `approvalMode`; 실행 기기의 정책 |
| Codex 설정·개인 지침 | `.codex/config.toml`, `.codex/AGENTS.md` 또는 `AGENTS.override.md`; `PersonalInstructions` | 원문 파일 편집. 기기 경로·인증 관련 설정을 포함할 수 있음 |
| 모델 | sessions의 `model`; `Engine.send` | 선택 모델을 thread/turn 호출에 반영 |
| reasoning effort·작성 중 입력 | WebView localStorage; `app.js`의 `restoreOptions`, `saveOptions`, `saveDraft` 관련 처리 | effort는 turn/start로 전달하지만 sessions의 독립 필드로 저장하지 않음 |
| 도구·승인 요청·실행 중 turn | `Engine`의 `runningTurns`, `requests`, `pendingApproval`; UI 이벤트 | 대기 객체와 프로세스 상태는 메모리. sessions의 `approvalPending`은 완전한 승인 기록이 아님 |
| 작업 diff·도구 결과 | `Engine` 이벤트 처리 | `turnDiff`와 일부 메시지·이미지는 저장; 일반 tool 이벤트 전체를 sessions에 보존하지 않음 |
| Skills | `.codex/skills`; `SkillImporter`; UI의 `skills/list`, `skills/config/write` RPC | 로컬 설치와 경로 기반 설정 |
| Plugins/MCP | `app.js`의 `plugin/*`, `mcpServerStatus/list`, `mcpServer/oauth/login` RPC | Codex가 설치·설정·인증을 관리; 앱 자체 공통 plugin DB는 확인되지 않음 |
| 첨부·이미지 | `filesDir/attachments`, `filesDir/images`; `AttachmentStore`, `ImageStore` | 로컬 파일 및 메타데이터. 메시지에 절대 경로 포함 가능 |
| UI | WebView localStorage | 테마, 아이콘, 작성 초안, 모델 옵션, 프로젝트 펼침 상태 등 |

소스 루트는 `app/src/main/java/dev/mobilecodex/app/`, 웹 UI는 `app/src/main/assets/web/`다. 프로젝트 관리와 대화 목록을 위한 앱 자체 SQLite DB는 해당 소스에서 확인되지 않았다. Codex 내부 저장소의 실제 파일 구성은 Android 기기에서 검사하지 않았으며, 앱 상태와 별도로 취급한다.

현재 `package.json`은 jsdom/Playwright 기반 UI 테스트용이다. Node 개발 도구를 번들하지만 재사용할 Node 애플리케이션 서버가 있는 구조는 아니다.

Source of truth는 하나가 아니다.

- 프로젝트 로컬 연결: `DocumentStore`와 `ProjectRegistry`.
- 앱의 대화 제목·목록·화면용 기록: `Engine`의 sessions 저장소.
- 실행 가능한 thread·turn·도구 문맥: 해당 기기의 Codex app-server와 저장 기록.
- 표시·작성 초안: WebView. 화면 state snapshot 전체를 sync 원본으로 삼으면 안 된다.

## 2. 바로 동기화를 붙일 때의 문제

1. SAF URI hash는 기기 독립 ID가 아니다. 동일 이름도 동일 프로젝트를 보장하지 않는다. `restoreLegacyKey`는 과거 데이터 복구 때 이름으로 매칭하므로 cross-device import 경로에서 사용하면 안 된다.
2. `sessions.id == threadId`여서 다른 기기에 기록을 표시하는 것과 그 기기에서 실행하는 것이 결합돼 있다.
3. `resumeRemote`는 현재 기기의 threadId를 재개한다. sessions JSON 복사로 Codex의 실행 기록이 생성되지는 않는다.
4. 메시지에는 첨부·멘션·skill의 로컬 경로가 있다. 단순 문자열 치환은 과거 기록을 훼손하고 잘못된 파일 접근을 만들 수 있다.
5. SAF 전용 폴더에서는 `projectDirectory()`가 앱 내부 workspace로 대체되고 파일 접근은 `mobile_*` 도구를 이용한다. Desktop의 직접 경로와 같은 실행 조건이 아니다.
6. 앱의 현재 대화 삭제는 Codex `thread/delete`까지 호출한다. 공유 기록 삭제와 로컬 실행 기록 삭제를 분리해야 한다.
7. `sessions.json`은 전체 배열 재저장이고 모든 tool history를 포함하지 않는다. 파일 그대로 merge하거나 완전한 Codex 이벤트 로그로 간주할 수 없다.
8. `thread/start`에는 Android 전용 dynamic tools와 지침이 전달된다. 원본 세션을 Desktop에 가져오면 해당 도구가 존재하지 않을 수 있다.

## 3. 권장 아키텍처

```text
Android DocumentStore / Engine     Desktop companion / Codex adapter
                │                              │
        Android 로컬 연결·세션          Desktop 로컬 연결·세션
                └──────── 공유 규격 ────────────┘
                      Shared Project
                      Conversation / Run
                      불변 이벤트 + reducer
                              │
                     로컬 sync 저장소
                              │
                  Git / Local folder / 이후 backend
```

프로젝트 파일은 읽거나 복사할 대상이 아니라 로컬 binding이 가리키는 작업 폴더다. sync 실행은 프로젝트 저장소에서 checkout, pull, reset, commit하지 않는다. Git clone은 사용자가 별도로 실행하는 폴더 연결 작업이다.

최초 등록마다 무작위 `projectId`를 발급한다. 이름·remote·경로 hash를 전역 ID로 쓰지 않는다. 각 기기 프로젝트는 처음에는 독립된 Shared Project다.

사용자의 프로젝트 병합은 ID 사이의 동등 관계를 추가한다. 원래 ID와 기록은 보존하고 alias 해석으로 하나의 프로젝트를 표시한다. 병합된 집합의 canonical ID는 안정적인 정렬 규칙으로 선택하여 동시에 A↔B, B↔C를 연결해도 순환하지 않게 한다. 이름 선택은 별도 메타데이터 변경이다. 파일·폴더·Git branch·대화를 서로 합쳐 쓰지 않는다.

같은 기기의 두 폴더까지 하나의 프로젝트로 묶일 수 있으므로 bindingId를 별도로 둔다. 여러 binding 중 실행할 기본값은 그 기기에서 사용자가 정한다. 다른 기기의 binding이 로컬 기본 경로를 바꾸지 않는다.

remote 일치 여부는 후보 추천에만 사용한다. remote에 포함된 사용자정보·토큰을 제거하고, HTTPS/SSH의 명백한 동일성만 정규화한다. 임의 서버의 path 대소문자까지 통일하지 않는다. 이름·패키지 메타데이터는 약한 신호다. repository root의 절대 경로는 기기 내부에서만 의미가 있다.

## 4. 데이터 모델과 저장 규격

### 분류

‘필수’도 sync를 활성화하고 사용자가 선택한 프로젝트·대화 범위 내에서만 적용한다.

| 분류 | 상태 | 처리 |
| --- | --- | --- |
| 반드시 공유 | projectId, 표시 이름, 사용자가 확정한 병합 관계, conversationId, 제목, 선택한 대화 기록, run 계보, 공유 삭제 기록 | schema가 허용한 필드만 export |
| 공유하면 유용 | 최소 기기 표시명, 마지막으로 보고된 연결 상태, 모델·effort 선호, 정리된 작업 요약, 공유용 프로젝트 설정, 인증정보 없는 remote | 선택적으로 활성화; 실행 시 로컬 가용성 재검사 |
| 선택 공유·추가 검토 | 검토된 개인 지침, skill/plugin 식별자·버전, 도구 결과 요약, 첨부 | 원문 설정이나 설치 파일을 자동 공유하지 않음; 설치·신뢰 권한은 로컬 |
| 기기 로컬 | 절대 경로, SAF URI·permission, 기본 binding, 실제 branch/HEAD/dirty 정보, Codex threadId mapping, 최근 열기, UI 펼침·알림·초안, runtime 옵션 | 로컬 저장소 유지. 필요한 commit 정보만 인계 checkpoint에 선택 포함 |
| 동기화 금지 | ChatGPT/OAuth/API 인증정보, 계정 프로필과 복구 사본, device credential, Git 자격증명, MCP 인증정보, 쿠키, 권한·trust grant, 실행 중 승인 요청·응답 채널 | export allowlist 밖에 두고 credential 디렉터리는 읽지 않음 |
| 동기화 제외 | node_modules, venv, 바이너리, Linux rootfs, build/cache/temp, 복구·change backup, 화면 캡처·음성 원본 | 자동 수집하지 않음 |

대화 본문·도구 출력·diff에도 비밀이 포함될 수 있다. 필드 allowlist만으로 자유 텍스트의 비밀 부재를 보장할 수 없다. export 미리보기·제외/수정·일반적인 비밀 패턴 차단을 함께 제공하고, 내용 검사가 애매하면 전송을 중단한다. 자동 sync는 민감 정보 처리 검증 이후 단계다. Git history에 남은 유출은 새 commit에서 삭제해도 사라지지 않는다.

### 예시

아래 ID는 설명용이다. 실제 ID는 충돌 가능성이 충분히 낮은 무작위 ID를 사용한다.

```json
{
  "schemaVersion": 1,
  "projectId": "proj_123",
  "name": "mobile-codex",
  "settings": {},
  "repositoryHint": {"host": "github.com", "path": "user/mobile-codex"}
}
```

동기화 가능한 binding 요약에는 경로가 없다. `linked`는 마지막으로 보고한 연결 상태이며 실시간 접근 가능성 표시가 아니다.

```json
{
  "bindingId": "binding_phone",
  "deviceId": "device_phone",
  "projectId": "proj_123",
  "linked": true,
  "observedAt": "2026-09-29T00:00:00Z"
}
```

다음은 로컬 전용이며 export 대상이 아니다.

```json
{
  "bindingId": "binding_phone",
  "projectId": "proj_123",
  "localProjectKey": "existing-uri-hash",
  "localPath": "/storage/emulated/0/CODEX/mobile-codex",
  "documentTreeUri": "content://provider/tree/example"
}
```

공유 대화와 실행 기록은 분리한다.

```json
{
  "conversationId": "conv_123",
  "projectId": "proj_123",
  "runId": "run_phone_1",
  "parentRunId": "run_desktop_1",
  "baseCheckpointId": "checkpoint_42",
  "deviceId": "device_phone",
  "continuationKind": "context-handoff"
}
```

로컬에만 `(runId, bindingId) -> codexThreadId, codexVersion, importedCheckpointId`를 저장한다. 처음 가져온 공유 기록에는 실행 가능한 로컬 threadId가 없어도 정상이다.

### 저장 트리

```text
state-repository/
  schema.json
  events/<writerId>/<eventId>.json
  checkpoints/<conversationId>/<checkpointId>.json
  blobs/<contentHash>                         # 후속 선택 기능

device-local/                                # 상태 저장소 밖
  bindings.json
  codex-thread-bindings.json
  sync-cursors.json
  materialized-state.json
```

이벤트 봉투는 `schemaVersion`, `eventId`, `writerId`, `sequence`, `entityType`, `entityId`, `parents`, `kind`, `payload`를 갖는다. sequence와 새 이벤트를 하나의 로컬 transaction/journal로 저장한다. writerId는 장치 이름이 아니라 설치 인스턴스 ID이며 복원·복제로 writer가 중복되면 새 ID를 발급한다. 같은 eventId에 다른 내용이 오면 덮어쓰지 않고 격리한다.

대화 delta 한 글자마다 이벤트를 만들지 않고 완료된 message/turn과 명시적인 수정·인계 checkpoint를 기록한다. 체크포인트는 참조 이벤트와 마지막 완료 turn을 명시하고, 미완료 작업은 상태가 불확실한 것으로 표시한다.

append-only 구조는 적합하지만 기기별 단일 JSONL에 계속 append하면 Git rebase 충돌과 동기화 중 부분 쓰기가 남는다. 초기에는 불변 이벤트 파일을 권장한다. 이후 파일 수가 문제가 되면 닫힌 JSONL segment와 manifest를 도입한다. 어느 형식이든 정렬된 타임스탬프만으로 인과관계를 결정하지 않는다.

기존 SharedPreferences와 sessions 저장 형식은 유지하면서 별도 mapping을 추가한다. 기존 key를 공유 projectId로 즉시 교체하지 않는다. 이동·삭제·이름 변경 RPC 및 UI의 workspaceKey 검증을 점진적으로 확장한다. mapping 이전은 재실행 가능해야 하며 백업과 완료 marker를 갖는다.

## 5. 충돌·삭제·오프라인 처리

- 이벤트 집합을 ID로 합집합 처리하고 같은 이벤트 재수신은 무시한다. 불변 payload는 그대로 유지한다.
- 이름·선호 설정은 causal parent를 비교한다. 한쪽이 다른 쪽을 포함한 변경이면 최신 후속 변경을 적용하고, 동시 변경은 양쪽 값을 보존해 선택하도록 한다. 임의 기기 시계로 덮어쓰지 않는다.
- 동일 대화에서 두 기기가 실행하면 서로 다른 run을 만든다. UI에서 분기를 표시하며 응답을 하나의 선형 대화에 섞지 않는다. 후속 실행은 사용자가 선택한 run/checkpoint에서 시작한다.
- 중앙 서버 없는 오프라인 환경에서는 강제 단일 실행 lease를 보장하지 않는다. presence는 안내용이다.
- 프로젝트 병합은 project identity만 연결한다. 기존 conversationId/runId는 유지한다. 서로 다른 설정·복수 로컬 binding의 충돌은 별도로 해결한다.
- 로컬 연결 해제와 공유 프로젝트 삭제는 별도 명령이다. 동기화된 삭제는 tombstone으로 기록하고 과거 device에서 재업로드해도 되살아나지 않게 한다. 공유 삭제 이벤트가 로컬 파일 삭제나 Codex thread/delete를 실행해서는 안 된다.
- 삭제와 동시 수정은 수정 기록을 보존한 채 기본 목록에서 숨기고 복구 선택을 제공한다. 기기들의 관측 여부가 불명확한 동안 tombstone을 자동 정리하지 않는다.
- 프로젝트 병합 취소는 최초 버전에서 자동 역변환하지 않는다. 원본 ID·merge 이벤트를 보존하고 재분리 정책을 별도로 설계한다.
- PC가 꺼지기 전에 성공적으로 게시된 checkpoint까지만 다른 기기에서 받을 수 있다. UI에 마지막 sync 시각·checkpoint와 대기 중 변경 수를 표시한다.

Git backend는 전용 clone에서만 fetch → 파일 검증 → 이벤트 union → projection 검증 → commit → push를 수행한다. push 경합은 fetch 후 제한 횟수 재시도하며 force push하지 않는다. `.git` 제어 파일·hook·symlink·경로 탈출·과도한 파일 크기를 remote 데이터로 받아 실행하지 않는다. 실패 시 로컬 outbox를 유지하고 성공했다고 표시하지 않는다.

Git 외 backend도 불변 객체의 목록·읽기·안전한 게시 계약만 구현하게 한다. optimistic concurrency나 rename 원자성을 모든 backend가 제공한다고 가정하지 않는다. 목록 반영이 늦거나 일부 checkpoint 객체가 없으면 완성된 checkpoint로 공개하지 않는다. 추가 backend 구현은 필요할 때 한다.

## 6. Codex 재개와 Desktop companion

공식 [Codex App Server 문서](https://learn.chatgpt.com/docs/app-server)는 저장된 thread의 resume/fork/read, 실행 이벤트, rollout에 저장되는 dynamic tools를 설명한다. 이 문서의 현재 API와 Android 번들 `0.155.1`의 지원 범위가 같다고 가정하면 안 된다.

현재 소스의 `resumeRemote`는 threadId와 현재 cwd·권한·지침을 전달한다. `thread/read(includeTurns=true)`는 과거 이미지 복구에 일부 사용된다. 이 두 기능은 로컬 세션 재개·내보낼 이력 수집에 재사용할 수 있다. 단, `thread/read` 응답이 그 자체로 다시 import 가능한 원시 Codex history라는 보장은 확인하지 못했다.

이어가기에는 두 수준이 있다.

1. **문맥 인계**: 선택한 완료 checkpoint를 읽고 대상 기기에서 새 Codex thread를 만든다. 공유 대화 아래 새로운 run을 연결한다. 과거 메시지·작업 요약·미완료 항목은 기록으로 명시하여 전달하고 도구 호출을 재실행하지 않는다. 큰 기록은 제외 범위를 표시한 요약과 사용자가 선택한 관련 내용으로 인계한다. 원본 세션과 동일한 내부 상태 복원이라고 표시하지 않는다.
2. **네이티브 세션 이식**: 정확한 버전별 export/import 계약·compaction·tool schema·첨부·경로 처리와 재개 가능성을 별도 실험한다. 검증 전에는 rollout 복사, SQLite 교체, 임의 thread/inject_items를 제품 경로로 채택하지 않는다.

대화 열람에는 transcript로 충분하지만 네이티브 resume에는 Codex 저장 상태가 추가로 필요하다. 메모리 프로세스, 진행 중 shell, 승인 future, 실행 grant는 어떤 방식에서도 이동하지 않는다. 첫 버전은 문맥 인계를 권장한다.

이어가기 전 대상 기기는 로컬 binding, 파일 접근, 인증, 모델 가용성, 필요한 도구를 확인한다. 소스코드 상태가 다를 수 있으므로 선택적 commit/dirty checkpoint를 비교하고 차이를 표시한다. 동기화는 파일 차이를 고치지 않는다. model/effort가 없으면 조용히 다른 값으로 바꾸지 않고 사용자 선택을 받는다.

Desktop companion은 필요하다. Android의 app-private 저장소를 Desktop에서 그대로 사용할 수 없고 현재 재사용할 Node 서버도 없다. 다만 첫날부터 별도 npm 배포나 범용 sync 패키지를 만들 이유는 없다.

- 같은 repository의 `packages/desktop-companion/`에서 소규모 Node CLI로 시작한다. 패키지명은 배포 시 가용성을 확인한다.
- 최소 명령: `init`, `status`, `projects list`, `projects bind`, `projects merge`, `export`, `import`; Git 도입 후 `sync`; 문맥 인계 도입 후 `continue`.
- 초기 로컬 폴더 교환으로 Android와 Desktop의 동일 fixture를 검증하고 그 뒤 Git을 붙인다.
- Android는 순수 Java domain/codec, Desktop은 JS adapter를 사용한다. 공통 자산은 JSON Schema와 golden fixture·동일 입력/출력 규칙이다. 작은 규격을 공유하기 위해 Android에 새 Node 서비스 상주를 강제하지 않는다.
- 기존 Desktop Codex 앱의 모든 채팅·SQLite·설정을 곧바로 수정하는 기능은 포함하지 않는다. CLI가 관리하는 세션부터 지원하고, 기존 Desktop 대화 수집은 지원 API·버전별 adapter 검증을 거친 별도 범위다.

## 7. 구현 단계와 완료 조건

| 단계 | 구현 | 완료 조건 |
| --- | --- | --- |
| 0 | 현재 검토와 버전별 resume 실험 계획 | 저장 경계·보안 제외·문맥 인계와 원본 재개의 차이 명시 |
| 1 | Shared Project / 로컬 binding mapping, 프로젝트 연결·병합 | 두 동일 이름 프로젝트는 별개; 명시 병합 후 한 프로젝트 표시; 재시작 후 유지; 폴더 내용 불변; binding 없는 프로젝트는 열람만 가능 |
| 2 | portable export/import와 작은 Desktop CLI, 이벤트 reducer | 두 독립 임시 홈의 왕복·중복 import·동시 변경·파일 누락·손상·schema 오류·secret fixture 검증; 현재 local ID 보존 |
| 3 | 대화 snapshot·run·문맥 인계 | 각 기기 독립 인증으로 새 로컬 실행; 공유 기록과 로컬 thread binding 유지; tool/approval 재실행 없음; PC 종료 후 이미 전달된 상태로 이어가기 |
| 4 | 사용자 개인 Git remote + 수동 sync | 분리 clone 사용; offline outbox 유지; push 경합·삭제 전파·분기·인증 실패·중단 복구 검증 |
| 5 | 자동 sync, 필요할 때 추가 backend | 배터리·백그라운드 제약·잠금·재시도·마지막 성공 표시·민감 데이터 차단 검증 |

기능 구현은 단계 1부터 작게 진행한다. 첫 검증은 Java/JS fixture, 그 뒤 Android UI desktop/narrow viewport, 마지막으로 Android 실기기 SAF 및 Desktop과의 왕복이다. 단위 테스트나 APK 빌드 성공을 실기기 검증으로 대체하지 않는다.

## 8. 예상 수정 파일

| 파일 | 변경 예정 |
| --- | --- |
| `app/src/main/java/dev/mobilecodex/app/core/ProjectRegistry.java` | 기존 key를 보존하고 별도 공유 ID mapping과 연동; legacy 복구와 import 분리 |
| `app/src/main/java/dev/mobilecodex/app/DocumentStore.java` | 로컬 binding 연결·해제, remote-only 프로젝트 조회·실행 제한 |
| `app/src/main/java/dev/mobilecodex/app/Engine.java` | sync RPC 경계, conversation/run과 threadId mapping, export snapshot, continuation |
| `app/src/main/java/dev/mobilecodex/app/core/sync/` (신규) | ProjectIdentity, DeviceBinding, PortableEvent, reducer, codec, 검증·migration |
| `app/src/main/java/dev/mobilecodex/app/SyncStore.java` (신규) | 앱 전용 journal·outbox·mapping persistence |
| `app/src/main/java/dev/mobilecodex/app/GitSyncBackend.java` (후속 신규) | 상태 저장소 전용 Git 수행; 기존 read-only git helper와 분리 |
| `app/src/main/assets/web/app.js`, `ui-core.js`, `index.html`, `translations.js` | 하나의 shared project 표시, 기기 연결 상태·병합·폴더 연결·sync 상태 UI; 기존 RPC scope 보존 |
| `schema/sync/`, `tests/fixtures/sync/` (신규) | 버전 규격 및 Java/JS 공통 성공·실패 fixture |
| `packages/desktop-companion/` (신규) | 로컬 binding·파일 교환·Codex adapter·후속 Git CLI |
| `app/src/test/java/dev/mobilecodex/app/core/ProjectRegistryTest.java`, `EngineOfflineSessionTest.java` | 기존 로컬 복구·삭제·실행 제한 회귀 검증 |
| `app/src/test/java/dev/mobilecodex/app/core/sync/`, `tests/sync*.test.cjs` (신규) | 식별·merge·idempotence·병행 변경·안전한 import·migration 검증 |

`AccountProfiles`, 기존 인증·권한 설정, 실제 프로젝트 파일은 sync 구현을 위해 옮기거나 재구성할 필요가 없다. CodexHome 전체 export API는 만들지 않는다.

## 9. 위험과 검증 항목

| 위험 | 대응·검증 |
| --- | --- |
| 인증정보 유출 | settings/state snapshot 통째 export 금지; credential 복구 디렉터리도 제외; userinfo 포함 remote와 자유 텍스트 secret fixture 차단 |
| 공개 remote 또는 범용 Git privacy 확인 불가 | backend 설정에 공개 가능성 표시와 export 검토; 공급자 불문 private 확인을 보장하지 않음; 자동 게시 전 정책 결정 |
| Git conflict·불완전 전송 | 불변 이벤트; 전용 clone; 검증된 checkpoint만 표시; push 경합 제한 재시도 |
| 대화 중복 | 전역 conversationId/eventId, origin ID의 안정적인 로컬 mapping, 반복 import 테스트 |
| stale state | lastSynced checkpoint와 관측 시각 표시; 원격 running 표시를 로컬 실행 가능 상태로 해석하지 않음 |
| schema migration | 알 수 없는 필수 버전은 import 중단; staging 검증 후 적용; 원본·백업 보존; 부분 migration 재실행 테스트 |
| 서로 다른 Codex 버전 | 런타임별 schema 생성 및 capability 검증; 미지원 네이티브 이식 거부; 문맥 인계 선택 |
| OS·경로·첨부 | 상대 프로젝트 참조와 로컬 resolver; 절대 경로 치환 금지; 누락 첨부 표시; traversal·symlink·크기 제한 검증 |
| 동시 작업 | run fork와 명시적인 baseCheckpoint; 강제 lock 보장 주장 금지 |
| 삭제·연결 해제 혼동 | 로컬 detach와 공유 tombstone 분리; 파일 삭제 및 원격 승인 재실행 금지 |
| 설치된 도구 차이 | skill/plugin은 검토된 참조만; 누락 도구 표시; 자동 설치·권한 부여 금지 |
| 저장량 증가 | 완료 turn 단위 기록; 대형 결과 opt-in; 이후 segment·checkpoint 보존정책 마련 |

## 10. 최종 추천

Shared Project / Device Binding을 첫 구현 단위로 삼는다. 기존 로컬 key와 저장소를 유지하면서 공유 ID·대화 계보를 덧붙이고, 상태 이동은 로컬 export/import로 먼저 검증한다. Desktop companion과 양방향 문맥 인계를 확인한 뒤 개인 Git remote를 첫 네트워크 backend로 추가한다.

첫 제품 범위는 프로젝트 연결·명시 병합, 선택한 대화 기록 공유, 완료 checkpoint로 새 로컬 실행 시작, 수동 sync다. 자동 sync·추가 클라우드 backend·원본 Codex 세션의 완전 이식·소스 파일 동기화는 이 범위에 포함하지 않는다.

## 근거와 검증 상태

- 현재 저장소 소스와 관련 테스트를 읽어 분석했다. 이번 검토에서 앱 코드는 변경하지 않았고 테스트·빌드·실기기 왕복을 실행하지 않았다.
- `DocumentStore`의 `select`, `selectProject`, `directDirectory`, `requireWorkspaceAvailable`; `ProjectRegistry`의 `fromJson`, `restoreLegacyKey`, `toJson`.
- `Engine`의 `persistSessions`, `restoreSessionProjects`, `threadStartParams`, `resumeRemote`, `send`, `resume`, `deleteSession`, 이벤트 처리, `projectDirectory`.
- `CodexHome`, `AccountProfiles`, `AttachmentStore`, `ImageStore`, `PersonalInstructions`, `SkillImporter`, `DevTools`, `ToolCatalog`.
- `app.js`의 localStorage·skill/plugin/MCP RPC; `EngineOfflineSessionTest`, `ProjectRegistryTest`; `package.json`, `app/build.gradle`, `tools/runtime-lock.json`.
- 공식 [Codex App Server](https://learn.chatgpt.com/docs/app-server), 2026-09-29 확인. 최신 문서의 기능은 번들 0.155.1에서 별도 검증해야 한다.
