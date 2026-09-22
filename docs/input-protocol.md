# 대화 컨텍스트와 첨부 입력 (0.1.2)

확인 기준은 APK에 고정된 `DioNanos/codex-termux` **v0.155.1**입니다. 최신 문서만 보고 해당 버전에 없는 API를 사용하지 않습니다.

## 확인한 원본 스키마

- [UserInput.ts](https://github.com/DioNanos/codex-termux/blob/v0.155.1/codex-rs/app-server-protocol/schema/typescript/v2/UserInput.ts)
- [TurnStartParams.ts](https://github.com/DioNanos/codex-termux/blob/v0.155.1/codex-rs/app-server-protocol/schema/typescript/v2/TurnStartParams.ts)
- [ThreadResumeParams.ts](https://github.com/DioNanos/codex-termux/blob/v0.155.1/codex-rs/app-server-protocol/schema/typescript/v2/ThreadResumeParams.ts)
- [Rust UserInput 정의](https://github.com/DioNanos/codex-termux/blob/v0.155.1/codex-rs/protocol/src/user_input.rs)
- [AppInfo.ts](https://github.com/DioNanos/codex-termux/blob/v0.155.1/codex-rs/app-server-protocol/schema/typescript/v2/AppInfo.ts)

## 입력 전달

| 선택한 항목 | `turn/start.input` 전달 |
| --- | --- |
| 텍스트 | `text`, `text_elements: []` |
| 이미지 첨부 | `localImage` + 앱 내부 원본 파일의 절대 경로 |
| 문서·코드·기타 첨부 | 파일명·MIME·크기·원본 절대 경로를 담은 `text` |
| `$`로 선택한 스킬 | `$스킬이름` 텍스트 + `skill { name, path }` |
| `@`로 선택한 앱 | `mention { name, path: "app://<id>" }` |
| `@`로 선택한 프로젝트 파일 | 검증된 절대 경로의 `mention` + 경로 설명 텍스트 |

일반 첨부는 원본을 바꾸거나 문서를 텍스트로 가장하지 않습니다. Codex 엔진과 셸은 같은 Android 앱 UID로 실행되므로 앱 내부에 복사된 파일을 실제로 읽을 수 있습니다. 기존 Codex 셸 도구는 유지되며 `mobile_*` 도구는 일반 파일 경로가 없는 Android 문서 제공자를 위한 추가 도구입니다. PDF·Office·압축 파일 등의 내용 해석은 해당 포맷을 처리할 도구의 존재에 따릅니다. 현재 APK는 모든 문서 변환 도구를 포함하지 않습니다.

파일 선택은 Android `ACTION_OPEN_DOCUMENT`의 다중 선택을 사용합니다. 스트리밍 복사 후 원본과 메타데이터를 보관하며, 원본 제공자의 임시 권한이 끝나도 첨부 파일은 유지됩니다. 같은 이름의 파일은 고유 디렉터리에 보관합니다. 이미지 미리보기는 검증된 앱 내부 이미지 URL만 사용합니다.

선택 취소와 개별 파일 복사 실패는 결과에 각각 기록합니다. 성공한 파일은 그대로 남습니다. 선택 결과에는 시작 시점의 초안 키가 들어가며, Android가 화면을 재생성한 경우에도 보관한 결과를 복원합니다. 대화에 전송된 첨부 메타데이터는 `sessions.json`에 보관하며 원본은 앱의 `files/attachments/`에 남습니다. 앱 데이터 삭제·앱 제거 시에는 함께 삭제됩니다.

## 프로젝트와 대화

프로젝트의 안정적인 키와 SAF URI를 별도로 보관합니다. 폴더 재연결은 URI를 갱신하며 기존 대화의 프로젝트 키는 유지합니다. 이전 버전의 프로젝트 대화는 저장된 키를 바탕으로 이관하고, 권한이 없어진 프로젝트도 목록에서 제거하지 않습니다.

대화 목록과 저장된 메시지는 로컬에서 읽습니다. `chat.resume` 자체가 서버 시작이나 로그인을 요구하지 않습니다. 실제 전송 시 `thread/resume` 또는 `thread/start`를 사용하고 해당 프로젝트의 `cwd`를 전달합니다. 새 대화의 `turn/start`가 거절되면 성공한 것처럼 로컬 메시지를 추가하거나 초안을 지우지 않습니다.

## 시각 기준과 검증 범위

실제 웹 ChatGPT의 흰색 대화 영역, 회색 사이드바, 프로젝트 아래 대화 목록, 둥근 입력창, 왼쪽 추가 버튼, 모델 선택과 설정 분류를 참고했습니다. 태블릿 설정은 왼쪽 분류/오른쪽 내용 구조이고 휴대폰에서는 하단 시트를 사용합니다. 모바일 운영체제의 파일 선택기와 권한 화면은 Android 기본 동작을 유지합니다.

브라우저 검증에서는 모의 네이티브 응답을 사용해 화면과 조작을 확인합니다. 실제 계정으로 모델 호출, Android 문서 제공자, 삼성 키보드 및 ARM64 엔진 구동의 종단 간 검증과 구분합니다.

## 0.1.3: 목록과 개인 지침

빈 `@`는 `files.search`가 아니라 `files.list`를 호출합니다. 네이티브 검색은 빈 검색어를 거절하므로 이 둘을 구분합니다. 파일과 앱 응답을 독립적으로 표시하여 앱 연결이 느리더라도 파일을 선택할 수 있습니다. `$`는 `skills/list`에서 실제 활성 스킬과 설명을 표시합니다. 결과가 없거나 실패해도 메뉴를 숨기지 않으며 가져오기·재시도를 제공합니다.

개인 지침은 [공식 AGENTS.md 규칙](https://learn.chatgpt.com/docs/agent-configuration/agents-md)에 맞춰 앱의 `CODEX_HOME`인 `files/.codex`에서 관리합니다. `instructions.read`는 적용 파일과 내용을 반환하고 `instructions.save`는 원자적으로 저장한 뒤 엔진을 종료합니다. 다음 실행에서 갱신된 전역 지침을 읽습니다. 비어 있지 않은 `AGENTS.override.md`가 있으면 그 파일을 편집하며, 비우면 기본 `AGENTS.md`가 다시 적용됩니다. 실제 파일과 UI 설정값을 따로 유지하지 않습니다.

번들 v0.155.1의 [ThreadManager 구현](https://github.com/DioNanos/codex-termux/blob/67097d146463f12a4e15516fd6abe826f54963c1/codex-rs/core/src/thread_manager.rs#L1655-L1681)은 새 대화와 cold resume에서 전역 지침을 새로 읽고, 실행 중인 대화 재개에서는 기존 지침을 유지합니다. 따라서 저장 후 서버를 종료하고 `serverThreadId`도 초기화해 다음 재개가 새 실행으로 처리되도록 합니다.
