# GPT-6-Pro 읽기 전용 WebView 경로 구현 인계

## 작업 기준

- 저장소: `SeeUSoon93/mobile-codex` 비공개 저장소
- 기준 브랜치·리비전: `main`, `8d3168a` (`v0.1.43-alpha`)
- 작성 시점의 기존 미추적 경로 `icon-pack/`은 이 작업과 무관하므로 수정하거나 포함하지 않는다.
- 구현을 시작할 때 원격과 작업 트리 상태를 다시 확인하고, 사용자 작업을 보존한다.

## 목표

Codex 화면의 모델 목록에 `GPT-6-Pro`를 추가한다. 사용자는 기존 작성창에서 이 모델을 선택하고 그대로 요청한다. 앱은 요청을 Codex app-server가 아니라 로그인된 `chatgpt.com` WebView의 GPT-6 Pro 대화로 보내고, 답변을 현재 Mobile Codex 대화에 저장해 표시한다.

사용자에게 보이는 계약은 다음과 같다.

- 모델 이름: `GPT-6-Pro`
- 추론 강도: 선택 불가. `Pro에서 자동 결정`으로 표시한다.
- 작업 권한: `읽기 전용`으로 고정한다.
- 작성창 요약: `GPT-6-Pro · 읽기 전용`
- 입력·전송·답변 위치: 현재 Codex 대화와 동일하다.
- 답변 출처: 메시지 메타데이터에는 `ChatGPT Pro`로 기록한다. `Codex Pro`라고 표기하지 않는다.

## 확정한 범위

첫 구현은 분석·검토·질문 답변만 지원한다. GPT-6-Pro 경로는 파일 수정, 명령 실행, 휴대폰 제어, 승인 요청을 실행하지 않는다. MCP 서버, OpenAI API 키, GitHub 쓰기 권한도 사용하지 않는다.

ChatGPT 웹·모바일은 기기 폴더를 직접 읽지 못한다. 앱이 파일이나 문맥을 전달해야 한다. OpenAI 공식 문서도 웹·모바일에서는 컴퓨터 파일에 직접 접근할 수 없고, 필요한 파일과 문맥을 추가해야 한다고 설명한다.

- [ChatGPT Work and Codex](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex)
- [How does the new file uploads capability work?](https://help.openai.com/en/articles/8982896-how-does-the-new-file-uploads-capability-work)
- [Projects in ChatGPT](https://help.openai.com/en/articles/10169521-projects-in-chatgpt)

## 구현 원칙

`GPT-6-Pro`는 Codex의 `model/list` 결과가 아닌 앱 전용 합성 항목이다. 내부 라우트 ID는 충돌을 피하도록 `chatgpt-web:gpt-6-pro`를 사용한다.

이 ID를 `thread/start`, `thread/resume`, `turn/start`의 `model`로 절대 전달하지 않는다. UI는 Pro 요청에 전용 `chat.pro.send` 액션을 호출하고, 네이티브 라우터가 다음처럼 분기한다. `Engine.send()`에도 방어 검사를 넣어 합성 ID 유입을 거부한다.

```text
model == chatgpt-web:gpt-6-pro
  -> chat.pro.send -> Pro WebView 경로
그 외
  -> chat.send -> 기존 Codex Engine.send 경로
```

UI는 하나의 대화처럼 보여도 원격 백엔드는 분리되어 있다.

```text
Mobile Codex 로컬 대화
  ├─ Codex turn     -> codexThreadId
  └─ GPT-6-Pro turn -> chatConversationId
```

따라서 로컬 대화 기록이 화면과 저장의 기준이어야 한다. 한쪽 원격 대화에 메시지를 표시만 추가해서는 다른 쪽 백엔드가 그 내용을 알 수 없다. 백엔드가 바뀌는 요청에는 필요한 이전 대화 요약과 파일 문맥을 함께 전달한다.

## 현재 코드에서 이어받을 부분

- 모델·추론·권한 UI: `app/src/main/assets/web/index.html`, `app/src/main/assets/web/app.js`
- Codex 전송: `app/src/main/java/dev/mobilecodex/app/Engine.java`
- 공식 ChatGPT WebView와 파일 선택: `app/src/main/java/dev/mobilecodex/app/ChatWebActivity.java`
- 네이티브 브리지 라우팅: `app/src/main/java/dev/mobilecodex/app/MainActivity.java`
- 브라우저 UI 테스트: `tests/ui.test.cjs`
- ChatGPT WebView 테스트: `tests/chat-web-custom.test.cjs`, `app/src/test/java/dev/mobilecodex/app/ChatWebActivityTest.java`

과거 구현도 참고한다.

- `34bc000`: 자체 UI에서 ChatGPT 웹 세션으로 전송하고 답변을 로컬 UI에 표시했다.
- `d15330a`: 일반 Chat을 보이는 공식 WebView로 전환했다.
- `dfdb73b`: `ChatWebTransport`, 재조회 코드, 로컬 Chat 렌더러를 제거했다.

과거 `ChatWebTransport`의 작업 ID, 실제 설정 확인, 대화 ID 관찰, 불확실 전송 재확인 구조는 재사용할 가치가 있다. 다만 이전 코드를 그대로 복원하지 말고 현재 보안 경계와 로컬 통합 대화 구조에 맞춰 작은 전용 전송기로 다시 만든다.

## 권장 구성

### 1. 모델 선택 상태

`app.js`의 `renderModelList()`에서 기본 모델 다음에 합성 항목을 넣는다. `state.models` 자체에는 넣지 않는다. 그래야 app-server가 제공한 모델과 앱 전용 경로가 섞이지 않는다.

```js
const PRO_ROUTE_ID = 'chatgpt-web:gpt-6-pro';
```

Pro 선택 시 다음 상태를 적용한다.

- `effort`는 `Pro에서 자동 결정` 한 항목만 보여 주고 비활성화한다.
- 권한 라디오는 `읽기 전용`을 선택한 상태로 보여 주고 세 항목 모두 비활성화한다.
- 승인 방식도 실행 의미가 없으므로 비활성화한다.
- `permissions.set`을 호출해 앱 전역 권한을 덮어쓰지 않는다. Pro 경로의 유효 권한만 읽기 전용으로 강제한다.
- Pro에서 다른 모델로 돌아오면 그 대화에서 마지막으로 고른 Codex 추론 강도와 기존 앱 전역 권한 표시를 복원한다.
- 초안 저장·복원 시 합성 모델 ID도 보존한다.

표시만 잠그면 안 된다. 네이티브 전송 경로에서도 Pro 요청에 쓰기·명령·도구 입력이 포함되지 않도록 다시 검증한다.

### 2. Pro 사용 가능 여부

계정의 플랜 문자열만 보고 Pro 사용 가능으로 판단하지 않는다. 전송 전에 실제 `chatgpt.com` 모델 선택기에서 GPT-6 Pro 항목을 찾고, 선택 후 현재 표시가 GPT-6 Pro인지 확인한다.

상태는 최소한 다음 네 가지로 나눈다.

- `available`: 로그인되어 있고 GPT-6 Pro 선택을 확인했다.
- `login_required`: ChatGPT 웹 로그인이 필요하다.
- `unavailable`: 로그인했지만 계정에서 GPT-6 Pro를 찾을 수 없다.
- `web_changed`: 페이지 구조가 달라져 안전하게 확인할 수 없다.

확인에 실패하면 다른 모델이나 `xhigh`로 조용히 대체하지 않는다. 입력 초안과 첨부를 유지하고 원인을 표시하며, 필요하면 공식 Chat 화면을 열어 로그인·모델 상태를 확인하게 한다.

### 3. 네이티브 전송기

`ProWebTransport` 같은 전용 클래스를 추가한다. 과거 `ChatWebTransport`처럼 WebView 작업은 메인 스레드에서 실행하되, 보이는 `ChatWebActivity.page` 객체에 직접 의존하지 않는다. 동일 앱의 `CookieManager` 세션을 사용하고, 로그인이 필요할 때만 보이는 Chat 화면을 연다.

필수 동작 순서는 다음과 같다.

1. 요청마다 고유 `operationId`와 로컬 대화 revision을 만든다.
2. 현재 페이지가 정확한 `https://chatgpt.com` 출처인지 확인한다.
3. 로그인과 GPT-6 Pro 실제 선택 상태를 확인한다.
4. 앱이 만든 읽기 전용 문맥과 사용자 요청을 공식 작성창에 넣는다.
5. 공식 전송 버튼을 한 번만 누른다.
6. 사용자 메시지 ID, 대화 ID, 응답 완료 상태를 관찰한다.
7. 완료된 답변을 수집해 원래 `operationId`와 연결한다.
8. 로컬 저장이 끝난 뒤에만 UI에 성공을 반환한다.

원격 `chatgpt.com` 페이지에 `addJavascriptInterface`를 노출하지 않는다. 쿠키, 액세스 토큰, 인증 헤더를 WebView 밖으로 복사하거나 로그에 남기지 않는다. 주입 스크립트는 정확한 `https://chatgpt.com` 페이지에서만 실행한다.

ChatGPT 페이지 DOM과 내부 요청 형식은 공식 공개 API가 아니므로 바뀔 수 있다. 선택자와 관찰 코드는 한 파일에 격리하고, 기능 플래그와 버전 진단값을 둔다.

### 4. 읽기 전용 문맥 묶음

Pro는 Android 파일시스템을 직접 탐색하지 않는다. 앱이 전송할 문맥을 명시적으로 만든다.

첫 구현에서 포함할 대상은 다음으로 제한한다.

- 사용자가 첨부한 파일
- 사용자가 `@`로 지정한 파일
- 현재 프로젝트에 적용되는 `AGENTS.md`
- 현재 Git diff가 있으면 그 diff
- 요청 해결에 필요한 현재 파일과 제한된 파일 트리
- 백엔드 전환 직전까지의 로컬 대화 요약

텍스트 파일은 경로, SHA-256, 문자 인코딩, 잘림 여부를 포함한 구분자로 묶는다. 이미지·PDF처럼 기존 ChatGPT 파일 업로드가 더 적합한 형식은 현재 `ChatWebActivity`의 파일 선택 흐름을 기반으로 앱 소유의 임시 읽기 전용 사본을 전달한다.

다음 항목은 기본 제외한다.

- `.git/`
- `.codex/`의 인증·계정 파일
- `.env`, keystore, 서명 파일, 인증서 개인키
- 토큰·쿠키·브라우저 데이터
- 빌드 결과와 대형 바이너리

파일 수, 개별 크기, 전체 바이트 수에 상한을 둔다. 상한을 넘으면 임의로 전체 폴더를 보내지 말고 어떤 파일이 제외됐는지 사용자에게 알려야 한다. 전송 시점의 해시를 저장해 답변이 오래된 파일 문맥에 기반했는지도 판별한다.

### 5. 동일 대화 저장

현재 `sessions.json`의 `id`는 사실상 Codex 원격 thread ID 역할까지 겸한다. Pro가 첫 메시지인 대화도 저장하려면 로컬 대화 ID와 원격 ID를 분리해야 한다.

권장 세션 형태는 다음과 같다.

```json
{
  "id": "local-uuid",
  "codexThreadId": "optional",
  "chatConversationId": "optional",
  "workspaceKey": "...",
  "messages": [
    {
      "id": "local-message-uuid",
      "role": "assistant",
      "text": "...",
      "backend": "chatgpt-web",
      "requestedModel": "chatgpt-web:gpt-6-pro",
      "displayModel": "GPT-6-Pro",
      "remoteMessageId": "optional",
      "operationId": "...",
      "contextHash": "...",
      "status": "completed"
    }
  ]
}
```

기존 세션은 읽을 때 `codexThreadId = old.id`로 해석하는 호환 마이그레이션을 둔다. 한 번의 쓰기 실패로 기록이 깨지지 않도록 현재 `sessions.json.tmp` 후 교체 방식은 유지한다.

메시지 상태는 최소 `sending`, `completed`, `not_sent`, `uncertain`, `failed`를 지원한다. 응답은 보였지만 로컬 저장이 실패한 경우 성공으로 처리하지 않는다.

### 6. 백엔드 전환

같은 로컬 대화에서 Codex와 GPT-6-Pro를 오갈 수 있다.

- Codex에서 Pro로 전환: 필요한 최근 대화와 변경 상태를 Pro 문맥 묶음에 포함한다.
- Pro에서 Codex로 전환: Pro의 답변과 이후 사용자 지시를 Codex 입력 문맥에 포함한다.
- 전체 대화 원문을 매번 중복 전송하지 말고, 마지막 동기화 지점 이후 내용과 검증 가능한 요약을 사용한다.
- 동기화 기준이 불명확하면 새 원격 대화를 만들고 로컬 대화 연결 정보를 갱신한다.

첫 구현에서 자동 동기화가 안전하지 않다면 모델 전환 자체를 막지 말고, 전환 시 전달할 요약을 미리 보여 주는 방식으로 범위를 줄인다.

## 실패·취소·중복 방지

- 전송 버튼은 `operationId`가 살아 있는 동안 중복 실행되지 않아야 한다.
- 페이지 이동, 앱 백그라운드 전환, 회전, 네트워크 끊김에도 원래 작업 ID를 잃지 않는다.
- 전송 여부를 확인하지 못한 경우 자동 재전송하지 않는다. `uncertain`으로 저장하고 웹 대화를 재조회한다.
- 사용자 메시지 ID 또는 대화 ID로 이미 전달된 요청인지 먼저 확인한다.
- 취소는 응답 대기만 끝냈는지, 공식 웹 요청까지 중지됐는지를 구분해 표시한다.
- 실패 후에도 입력 초안, 첨부, 선택 모델, 포커스를 복원한다.
- Pro 답변을 받는 중에는 Codex `chat.steer`로 잘못 보내지 않는다.

## 구현 순서

1. 로컬 대화 ID와 `codexThreadId` 분리 마이그레이션을 먼저 만든다.
2. 합성 모델 항목과 Pro 선택 시 추론·권한 잠금 UI를 구현한다.
3. 전용 `chat.pro.send` 네이티브 액션을 추가하고 Codex RPC에 합성 ID가 유입되지 못하게 차단한다.
4. 읽기 전용 문맥 묶음 생성기와 민감 파일 제외 규칙을 구현한다.
5. `ProWebTransport`에 로그인 확인, GPT-6 Pro 선택 검증, 전송, 응답 수집을 구현한다.
6. 결과와 원격 식별자를 로컬 대화에 원자적으로 저장한다.
7. 불확실 전송 재확인, 취소, 앱 재시작 복구를 붙인다.
8. 자동 테스트 후 실제 Pro 계정이 로그인된 Android 기기에서 검증한다.

## 인수 조건

- 모델 목록에 `GPT-6-Pro`가 표시되고 선택 상태가 대화별로 복원된다.
- Pro 선택 시 추론 강도는 선택할 수 없고 권한은 읽기 전용으로 고정된다.
- Pro 선택 상태에서 `turn/start`에 합성 모델 ID가 한 번도 전달되지 않는다.
- 로그인된 지원 계정에서는 실제 GPT-6 Pro 선택을 확인한 뒤 한 번만 전송한다.
- 답변이 현재 Mobile Codex 대화에 저장되고 앱 재시작 후에도 유지된다.
- 같은 로컬 대화에서 Codex 응답과 Pro 응답이 순서대로 표시된다.
- Pro 경로는 파일 쓰기, 명령, 휴대폰 제어, 승인 요청을 실행할 수 없다.
- 지원하지 않는 계정, 로그아웃, DOM 변경, 네트워크 실패를 서로 구분한다.
- 실패·취소 후 초안과 첨부가 사라지지 않는다.
- 기존 Codex 모델 전송, 추론 강도, 권한 설정은 회귀하지 않는다.

## 필수 테스트

### 브라우저 UI 테스트

- 합성 모델이 서버 모델 목록과 별개로 한 번만 표시된다.
- Pro 선택 시 effort·approval·permission이 올바르게 잠긴다.
- 다른 모델로 돌아가면 이전 Codex effort와 권한 표시가 복원된다.
- Pro 전송은 전용 네이티브 액션만 호출하고 기존 Codex `chat.send` payload로 흘러가지 않는다.
- 첫 전송으로 새 대화가 만들어져도 모델·초안 범위가 유지된다.
- 좁은 화면에서 긴 모델명, 잠긴 컨트롤, 완료 버튼이 겹치거나 잘리지 않는다.

### Android 단위 테스트

- 정확한 ChatGPT 출처만 전송 스크립트를 실행한다.
- 합성 모델 ID는 Codex RPC에서 거부된다.
- 민감 경로와 허용 범위를 벗어난 파일은 문맥 묶음에 들어가지 않는다.
- 세션 마이그레이션과 원자적 저장 실패가 기존 기록을 손상하지 않는다.
- 같은 `operationId`의 중복 콜백과 중복 저장을 무시한다.

### 실제 기기 검증

- GPT-6 Pro가 보이는 계정과 보이지 않는 계정을 각각 확인한다.
- 새 대화와 기존 대화에서 전송·답변·재시작 복원을 확인한다.
- 텍스트 파일, 이미지, PDF, `@` 파일, Git diff 문맥을 확인한다.
- 앱 전환, 화면 회전, 네트워크 단절, 취소, 응답 지연을 확인한다.
- 전송 직전과 응답 후 실제 ChatGPT 웹 대화에도 같은 요청이 한 번만 존재하는지 확인한다.

자동 테스트나 빌드 성공만으로 실제 로그인 WebView와 GPT-6 Pro 선택이 검증됐다고 기록하지 않는다.

## 완료 시 남길 증거

- 변경 파일 목록과 설계상 주요 선택
- 실행한 테스트 명령과 결과
- 실제 기기·계정 검증 여부
- 확인한 ChatGPT 표시 모델명
- 실패·중복 전송 재현 결과
- 아직 DOM 변화에 취약한 선택자와 후속 과제
