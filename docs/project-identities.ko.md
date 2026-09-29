# 공유 프로젝트: 1단계

후속 구현: [프로젝트 정보 파일 교환](project-transfer.ko.md).

이 단계는 프로젝트의 공통 ID와 이 기기의 폴더 연결을 분리한다. 다른 기기와의 데이터 전송, Desktop CLI, Git sync는 아직 포함하지 않는다.

## 사용

- 프로젝트의 `+`에서 로컬 폴더를 연결하거나 폴더 없이 프로젝트를 만든다.
- 프로젝트 메뉴의 `프로젝트 병합`에서 같은 프로젝트로 묶을 항목을 선택하고 확인한다. 이름이 같아도 자동으로 병합하지 않는다.
- 병합하면 대상 프로젝트의 표시 이름과 기본 폴더를 사용한다. 기존 대화의 로컬 프로젝트 키와 폴더는 바뀌지 않는다.
- 연결이 여러 개면 `기본 로컬 폴더`에서 새 대화에 사용할 연결을 선택한다. 열어 둔 대화는 이동하지 않는다.
- 로컬 폴더가 없는 프로젝트에서는 기록을 열람할 수 있다. 새 작업은 폴더를 연결한 뒤 실행한다.
- 로컬 연결 해제는 프로젝트 파일을 삭제하지 않는다. 해제된 연결의 대화는 별도로 보존되며 다시 연결할 수 있다.

프로젝트 병합을 되돌리는 UI는 아직 없다. 확인 화면에서 선택한 프로젝트와 폴더를 확인한다.

## 저장과 호환성

Android `projects.registry` 안에 `identities` schema version 1을 추가한다. 논리적인 프로젝트·alias·기기 ID·binding ID·기본 binding을 기존 `{key,name,uri}` 데이터와 함께 한 번에 저장한다. 공유 ID와 로컬 경로는 모델에서 분리하되, 이 단계의 로컬 저장은 하나의 transaction으로 유지한다.

기존 레지스트리는 최초 이전 시 `registry-before-identities-v1`에 보존한다. 기존 대화의 `workspaceKey`와 `sessions.json`은 병합을 위해 다시 쓰지 않는다. 새 ID는 무작위로 발급하며 재시작·재연결 뒤에도 유지한다. 지원하지 않는 identity schema와 손상된 mapping을 임의로 새 ID로 대체하지 않는다.

기기 ID는 이번 설치의 로컬 식별자다. 다른 기기 목록·원격 binding·실시간 연결 상태는 아직 제공하지 않는다. 현재 `projects` 응답의 `uri`는 이 기기 UI용이며 향후 sync export 대상이 아니다.

새 RPC는 `projects.create`, `projects.merge`, `projects.prefer`다. 진행 중인 Codex turn이 있으면 이 변경들을 거부한다. 저장 실패 시 성공 응답이나 변경된 메모리 상태를 남기지 않는다.

## 검증

2026-09-29, Windows의 분리 worktree에서 실행했다.

- 순수 Java: 기존 ProjectRegistry 4개와 SharedProject 8개 통과.
- Android 전체: 173개 중 170개 통과. 프로젝트 관련 38개(ProjectRegistry 4, SharedProject 8, EngineOfflineSession 21, ProjectIdentityIntegration 5)는 모두 통과.
- 전체 JavaScript: 149개 통과. 중복 클릭·실패·확인 취소·공유 목록·원래 대화 연결·폴더 없는 전송 차단·영어 번역을 포함한다.
- 마지막 포커스 처리 보완 후 관련 UI 20개와 새 브라우저 검사를 재실행해 통과했다. 폴더 없는 프로젝트 생성 입력창의 포커스와 취소도 확인했다.
- Android `lintDebug` 통과.
- Chromium: 새 화면 320/393/1280px. 병합·취소 후 포커스·기본 폴더 선택·폴더 없는 상태와 가로 넘침을 검사했다. 네이티브 bridge는 테스트 대역이다.
- 기존 브라우저 레이아웃: 320/393/800/1280px, 한국어/영어, 밝은/어두운 테마, 키보드 높이, 시트 동작 검사 통과.

Android 전체 검사에서 실패한 세 항목은 이번 변경에 포함되지 않은 소스다.

| 테스트 | 관찰된 실패 |
| --- | --- |
| `AppUpdatesTest.providerSharesOnlyDedicatedUpdateDirectory` | Windows 경로의 APK를 AndroidX FileProvider의 설정 root로 해석하지 못함 |
| `LinuxRuntimeTest.damagedInstallationIsRemovableWithoutDeletingLinkedProject` | Windows 심볼릭링크 생성 권한 부족 |
| `ChangeReviewTest.rejectsTraversalGitInternalsAndSymlinks` | Windows 심볼릭링크 생성 권한 부족 |

검사 명령은 `testDebugUnitTest`에 `-x verifyRuntime`을 사용했다. 새 worktree에 배포용 네이티브 런타임을 복제하지 않은 상태의 소스 컴파일·단위 검사이며, APK 패키징이나 실기기 검증이 아니다. 실제 Android SAF 선택·앱 재실행과 두 기기 간 왕복은 아직 실행하지 않았다.

브라우저 재검사: `npm run test:project-layout`. 결과 이미지는 `artifacts/project-identity-preview/`에 생성된다. 전체 개발 방향은 [동기화 설계](cross-device-sync-design.ko.md)를 따른다.
