# Lucia 앱 접속 API 사양서(프로토콜 판 2)

- 대상: Lucia 전용 앱(Android·iOS·웹·데스크톱)을 만드는 개발자
- 기준 구현: `crates/luciad/src/gateway.rs`, `gateway/methods.rs`, `gateway/http.rs`, `apppush.rs`. 이 문서와 코드가 다르면 코드가 맞고, 이 문서를 고친다.
- 설계 까닭: 결정 0021(판 1), 결정 0043(판 2, 외부 노출, 푸시)
- 판: `2`(`welcome.v`). 하위 호환이 깨지는 변경이 있으면 판을 올린다.
- 이 사양서를 낸 뒤 바뀐 것만 모은 문서는 `docs/app-api-changes.md`다(새 변경이 위에 쌓인다).
- 커스텀 이모지·스티커 확장은 `docs/app-expressions.md`에 있다(데몬 구현됨, 결정 0045). 이 문서에 더해지는 것:
  - `welcome`과 `GET /v1/me`의 `expressions` 필드, 번호 없는 `expressions` 프레임, 번호 붙는 `sticker` 프레임
  - `message.sticker`, 오류 `bad_sticker`
  - `GET /v1/expressions`, `GET /v1/expressions/files/{file_id}`
  - 푸시 `data.kind`의 `sticker`

---

## 목차

1. [개요](#1-개요)
2. [접속 정보](#2-접속-정보)
3. [인증, 토큰, 범위](#3-인증-토큰-범위)
4. [공통 규칙](#4-공통-규칙)
5. [WebSocket 프로토콜](#5-websocket-프로토콜)
6. [메서드(`call`)](#6-메서드call)
7. [데몬의 부탁(`invoke`)](#7-데몬의-부탁invoke)
8. [HTTP API](#8-http-api)
9. [데이터 형식](#9-데이터-형식)
10. [푸시(FCM)](#10-푸시fcm)
11. [메시지가 가는 곳(라우팅)](#11-메시지가-가는-곳라우팅)
12. [앱 구현 지침](#12-앱-구현-지침)
13. [보안 요구 사항](#13-보안-요구-사항)
14. [한도와 시간 모음](#14-한도와-시간-모음)
15. [오류 코드 모음](#15-오류-코드-모음)
16. [기기 역할(`device`)](#16-기기-역할device)
17. [예시 흐름](#17-예시-흐름)
18. [판 1에서 바뀐 점](#18-판-1에서-바뀐-점)

---

## 1. 개요

Lucia는 홈서버에서 도는 1인용 비서다. 데몬 `luciad`가 대화, 기억, 일정, 승인을 맡는다. 앱은 Discord와 같은 자리의 **채널**이다.

- 앱에서 한 말은 Discord에서 한 말과 같은 Lucia, 같은 기억, 같은 상주 세션으로 간다.
- Lucia의 답, 진행 안내, 파일, 승인 카드가 앱으로 온다. 앱은 카드의 버튼을 눌러 승인하거나 거절한다.
- 통신 길은 둘이다.
  - **WebSocket**(`/v1/ws`): 실시간 대화, 카드, 메서드 호출.
  - **HTTP**(`/v1/...`): 파일 올리기·받기, 연결 없이 하는 일(기록 읽기, 푸시 토큰 등록).
- 앱이 연결돼 있지 않을 때 보낸 것은 서버의 **보낸 프레임 상자**에 번호(`seq`)와 함께 남는다. 앱은 다시 연결할 때 받는다. 푸시를 설정했으면 FCM으로 앱을 깨운다.

---

## 2. 접속 정보

### 2.1 주소

| 구분 | WebSocket | HTTP 기준 주소 |
|---|---|---|
| 집 안(LAN·VPN) | `ws://<LAN 주소>:<포트>/v1/ws` | `http://<LAN 주소>:<포트>` |
| 밖(공개 주소) | `wss://<공개 호스트>/v1/ws` | `https://<공개 호스트>` |

- `<LAN 주소>:<포트>`는 데몬 설정 `[gateway] bind`의 값이다. 예: `192.168.0.10:8790`.
- 공개 호스트는 설정 `[public] gateway_url`의 값이다. TLS는 리버스 프록시(Traefik)가 맡는다.
- 관리 페이지에는 쓸 수 있는 주소가 보인다(연결 → "앱·기기 연결"). 앱은 주소를 사용자가 넣게 하거나, 집 안과 밖 둘을 저장해 두고 고른다.

### 2.2 받는 출처

| 연결이 들어온 길 | 받는가 |
|---|---|
| 루프백, 사설(10/8, 172.16/12, 192.168/16), 링크 로컬, CGNAT(100.64/10, Tailscale 등), IPv6 ULA·링크 로컬에서 직접 | 받는다 |
| 데몬이 믿는 리버스 프록시를 거쳐(공개 주소가 설정됐을 때) | 받는다 |
| 데몬이 믿는 리버스 프록시를 거쳐(공개 주소가 설정되지 않았을 때) | `403` |
| 그 밖의 주소에서 직접 | `403` |
| 믿는 프록시가 `X-Forwarded-For`를 주지 않았다 | `400` |
| 믿지 않는 상대가 `X-Forwarded-For`나 `Forwarded` 머리를 실었다 | `400` |

> **앱은 `X-Forwarded-For`·`Forwarded` 머리를 보내지 않는다.** 집 안에서 직접 붙을 때 이 머리가 있으면 거절된다.

- WebSocket 업그레이드가 출처 때문에 거절되면 본문 없이 HTTP 상태만 온다.
- HTTP API는 같은 경우 `{"error": {"code": "forbidden", ...}}`를 돌려준다(상태는 `403`이나 `400`).

### 2.3 TLS

- 밖에서는 반드시 `wss`/`https`로 붙는다. 인증서는 공인 CA(Let's Encrypt)의 것이다.
- 집 안 주소는 평문(`ws`/`http`)이다. 집 LAN이나 VPN을 믿는다는 전제다.
  - Android는 평문을 쓰려면 Network Security Config에서 그 주소를 허용해야 한다.
  - iOS는 ATS 예외가 필요하다.
  - 밖에서도 VPN(Tailscale·WireGuard)으로 집 안 주소에 붙을 수 있다.

---

## 3. 인증, 토큰, 범위

### 3.1 토큰

- 관리 페이지 → 연결 → "앱·기기 연결"에서 이름을 붙여 발급한다. 역할은 `app`이다.
- 형식: `lucia_` 다음에 소문자 16진수 64자. 약 244비트이고 길이는 70자다.
- **발급 직후 한 번만 보인다.** 서버에는 SHA-256 해시만 남는다. 앱은 기기의 안전한 저장소(Android Keystore 기반 암호화 저장소, iOS Keychain)에 둔다.
- 사용자가 관리 페이지에서 취소할 수 있다. 취소되면:
  - 열려 있던 WebSocket은 다음 프레임을 받을 때 `unauthorized` 오류를 보내고 끊는다. 관리 페이지에서 취소하면 바로 끊기기도 한다.
  - HTTP는 `401 unauthorized`를 돌려준다.
  - 그 클라이언트의 보낸 프레임 상자와 푸시 토큰은 지워진다.
- 클라이언트는 20개까지 발급된다.

### 3.2 인증 방법

| 길 | 방법 |
|---|---|
| WebSocket | 연결 후 첫 프레임 `hello{token}`(10초 안). 업그레이드 요청의 머리는 보지 않는다. |
| HTTP | `Authorization: Bearer <토큰>`. `Bearer `(대문자 B, 띄어쓰기 하나)로 시작해야 한다. |

`/v1/health` 말고 모든 HTTP 요청에 인증이 필요하다. 기기 역할 토큰은 HTTP API를 쓸 수 없다(`403 forbidden`).

### 3.3 인증 실패 막기

- 한 주소(실제 상대 주소)에서 인증 실패가 쌓이면 막는다. WebSocket과 HTTP가 같은 셈을 쓴다.
  - 첫 실패로부터 10분 안에 10번이면, 그 10분이 끝날 때까지 맞는 토큰도 거절한다.
  - 거절은 WebSocket `too_many_attempts`, HTTP `429`다.
- 실패로 세는 것은 토큰이 틀린 경우뿐이다. 다시 연결하는 것 자체는 세지 않는다.

### 3.4 범위(scopes)

기본 권한 밖의 일은 범위로만 할 수 있다. 사용자가 관리 페이지에서 토큰마다 켜고 끈다.

| 범위 | 허용하는 일 | 기본 |
|---|---|---|
| (없음, 기본) | 대화, 카드 누르기, 기록 읽기, 파일, 푸시 등록, 이벤트, 권한 모드 **보기**, 주 모델 보기·바꾸기, 대화 정리(`session.compact`) | 늘 |
| `security_mode` | 권한 모드 **바꾸기**(`command` 메서드의 `option`) | 꺼짐 |
| `app_tools` | Lucia가 이 앱이 내준 기능(`hello.tools`, 7.2)을 부른다 | 꺼짐 |

- 클라이언트의 범위는 `welcome.scopes`와 `GET /v1/me`의 `scopes`에 있다.
- 범위가 필요한 메서드는 그 범위가 없으면 `welcome.methods`에 보이지 않는다.
- 범위는 매 프레임마다 다시 읽는다. 바꾸면 다음 프레임부터 듣는다.

---

## 4. 공통 규칙

- **인코딩**: UTF-8 JSON. WebSocket은 글(Text) 프레임만 쓴다. 바이너리 프레임은 `bad_frame` 오류를 받고 무시된다(연결은 그대로).
- **글자 수**: "자"는 유니코드 스칼라 값(코드 포인트) 수다. 한글 한 글자는 1자다.
- **시각**: RFC 3339 UTC(예: `2026-11-19T03:13:06.534831+00:00`).
- **ID 모양**: 앱은 아래 ID를 해석하지 말고 그대로 쓴다.
  - 클라이언트: `cl-` + 16진수 10자
  - 대화: `app:<클라이언트 ID>`
  - 카드 참조: `app:<클라이언트 ID>:<16진수 12자>`
  - 파일: `f_` + 16진수 12자
  - 데몬의 부탁: `d-` + 16진수 12자
- **넓혀질 수 있음**:
  - 앱은 **모르는 필드를 무시한다.**
  - 데몬 → 앱의 **모르는 `type`도 무시한다**(오류로 다루지 않는다).
  - 같은 판 안에서는 필드와 프레임 종류가 늘어날 수만 있다.
- **글 서식**: Lucia의 글(`text.body`, `note.body`, 카드의 `body`·`fields[].value`)은 Discord식 마크다운 하위 집합이다.
  - 굵게 `**`, 기울임 `*`, 코드 `` ` ``/```` ``` ````, 목록, 링크 `<URL>`·`[글](URL)`, 인용 `>`.
  - 모델이 쓴 글이 카드에 들어갈 때는 마크다운 기호가 `\`로 이스케이프돼 있다.
  - 앱은 마크다운을 그리되, **HTML로 해석하지 않는다.** 링크는 누를 때 사용자 확인을 거쳐 연다.

---

## 5. WebSocket 프로토콜

### 5.1 연결 수명

```
연결 ──hello──▶ welcome ──(resume을 줬으면) 다시 받는 프레임들 → resumed──▶ 주고받기 ──▶ 끊김
   └─ 10초 안에 hello가 없으면 아무 말 없이 끊는다
   └─ hello 실패: error 프레임 하나를 보내고 끊는다
```

- 같은 토큰으로 새로 연결하면 예전 연결은 데몬 쪽에서 닫힌다. 마지막 연결 하나만 산다.
- 데몬이 멈추거나 다시 시작하면 연결이 끊긴다. 앱은 다시 연결한다(12.3).

### 5.2 `hello`(앱 → 데몬, 첫 프레임)

```json
{"type": "hello", "token": "lucia_…", "resume": 41, "features": ["location"],
 "tools": [{"name": "location.get", "description": "휴대폰의 현재 위치", "params": {"precise": "boolean, 정밀 위치"}}]}
```

| 필드 | 형식 | 필수 | 설명 |
|---|---|---|---|
| `token` | 문자열 | 예 | 3.1의 토큰 |
| `resume` | 정수 | 아니오 | 앱이 마지막으로 **처리한** 프레임 번호다. 주면 그 뒤의 프레임을 다시 받는다(5.4). 처음이면 `0`을 준다(남아 있는 것 전부). 빼면 다시 받지 않는다. |
| `features` | 문자열 배열 | 아니오 | 데몬이 부탁(`invoke`, 7장)할 수 있는 앱 기능 이름. 정리 규칙은 아래와 같다. |
| `tools` | 객체 배열 | 아니오 | **Lucia에게 내주는 기능**(7.2). 여기 적은 것만 Lucia가 보고 부를 수 있다. |

`features` 정리 규칙:
- 앞뒤 공백을 지우고 소문자로 바꾼다.
- `[a-z0-9_-]`만 쓸 수 있고 40자까지다. 어기는 이름은 버린다.
- 중복을 지우고 정렬한 뒤 32개까지 남긴다.
- 정리된 결과는 `welcome.features`로 돌아온다.

`hello` 오류:

| 경우 | 오류 `code` | 그 뒤 |
|---|---|---|
| 첫 프레임이 `hello`가 아니다 | `hello_required` | 끊음 |
| 토큰이 틀렸거나 취소됐다 | `unauthorized` | 끊음(실패로 센다) |
| 그 주소가 막혀 있다 | `too_many_attempts` | 끊음 |
| 받지 않는 역할 | `unsupported_role` | 끊음 |
| 10초 안에 아무것도 없다 | (프레임 없음) | 끊음 |

### 5.3 `welcome`(데몬 → 앱)

```json
{
  "type": "welcome",
  "v": 2,
  "client": "cl-3f9a0c12de",
  "name": "휴대폰 앱",
  "role": "app",
  "conversation": "app:cl-3f9a0c12de",
  "seq": 57,
  "scopes": [],
  "methods": ["commands", "command", "history", "push.register", "push.unregister", "session.compact"],
  "features": ["location"],
  "tools": ["location.get"],
  "notify_target": true,
  "push": {"available": true, "registered": false},
  "profile": {"avatar": {"id": "a_cfc0dacd5dcd71abf496c418", "mime": "image/png", "width": 800, "height": 800,
                         "size": 579183, "updated_at": "2026-11-20T04:43:07Z"}},
  "capabilities": {
    "max_message_chars": 8000,
    "buttons": true,
    "edit": true,
    "prompts": true,
    "attachments": true,
    "max_upload_bytes": 31457280,
    "max_message_files": 10
  }
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `v` | 정수 | 프로토콜 판(2). 앱이 아는 판보다 크면 사용자에게 앱 업데이트를 권한다. |
| `client` | 문자열 | 이 클라이언트 ID |
| `name` | 문자열 | 발급할 때 붙인 이름 |
| `role` | 문자열 | `app` |
| `conversation` | 문자열 | 이 앱의 대화 ID |
| `seq` | 정수 | 이 클라이언트에 지금까지 매긴 마지막 프레임 번호(없으면 0) |
| `scopes` | 문자열 배열 | 3.4 |
| `methods` | 문자열 배열 | 이 토큰이 부를 수 있는 메서드(6장), 정렬됨 |
| `features` | 문자열 배열 | 정리된 `hello.features`(`tools`의 첫 마디가 더해진다) |
| `tools` | 문자열 배열 | 받아들인 `hello.tools`의 이름(7.2). Lucia가 실제로 부르려면 `scopes`에 `app_tools`가 있어야 한다. |
| `notify_target` | 불 | 이 앱이 "알림 받을 곳"인가(11장) |
| `push.available` | 불 | 데몬에 FCM 자격 증명이 있어 푸시를 보낼 수 있다 |
| `push.registered` | 불 | 이 앱의 푸시 토큰이 등록돼 있다 |
| `profile` | 객체 | Lucia의 프로필(8.7). 이 필드가 없으면 프로필 이미지 기능이 없는 데몬이다. `profile.avatar`가 `null`이면 Lucia가 정한 그림이 없다(앱의 기본 모습을 쓴다). |
| `capabilities.attachments` | 불 | 파일 올리기를 받는다 |
| `capabilities.max_upload_bytes` | 정수 | 파일 하나 상한(바이트) |
| `capabilities.max_message_files` | 정수 | 말 하나에 붙이는 파일 수 상한 |
| `capabilities.buttons`, `edit`, `prompts` | 불 | 카드 버튼, 카드 고쳐 그리기(`card_closed`), 값을 받는 버튼을 쓴다(늘 `true`) |

### 5.4 번호, 받음 확인, 다시 받기

아래 **번호 붙는 프레임**에는 `seq`가 붙고 서버 상자에 남는다: `text`, `note`, `files`, `card`, `card_closed`.

- `seq`는 클라이언트마다 1부터 1씩 늘어난다. 지워져도 다시 쓰지 않는다.
- 앱은 프레임을 **처리한 뒤** `{"type": "ack", "seq": N}`을 보낸다.
  - 누적이다: N과 그 앞이 모두 처리됐다는 뜻이다.
  - 모아서 보내도 된다(12.2). 줄어드는 값은 무시된다. 답은 없다.
- 다시 받기: `hello.resume = R`이면 `seq > R`인 프레임을 순서대로 보낸다.
  - 최대 500개이고, 각 프레임에 `"replay": true`가 붙는다.
  - 끝에 `resumed`가 온다.

```json
{"type": "resumed", "from": 41, "to": 57, "last": 57, "truncated": false}
```

| 필드 | 설명 |
|---|---|
| `from` | 앱이 준 `resume` |
| `to` | 이번에 다시 보낸 마지막 `seq`(없으면 `from`) |
| `last` | 지금까지 매긴 마지막 번호 |
| `truncated` | `true`이면 다시 받을 수 없는 구간이 있었다. 보관 기간이 지났거나 500개를 넘었다. 앱은 `history`로 기록을 다시 읽고, `seq`는 `last`로 맞춘다. 500개를 넘은 경우는 `resume = to`로 다시 연결해 나머지를 받아도 된다. |

- **중복**: 다시 받는 프레임과 그 사이 새로 생긴 프레임이 겹칠 수 있다. **앱은 이미 처리한 `seq` 이하의 프레임을 버린다.**
- **보관**: 받았다고 알린(ack) 프레임은 7일, 받았다고 알리지 않은 프레임도 30일 뒤 지운다. 한 시간마다 정리한다.
- `resume`을 주지 않으면 끊긴 동안의 프레임은 오지 않는다. 앱은 `seq`를 하나라도 받았으면 늘 `resume`을 준다.

### 5.5 데몬 → 앱 프레임

#### 번호 붙는 프레임

**`text`**: Lucia의 말.

```json
{"type": "text", "seq": 58, "body": "오늘 3시에 회의가 있어요."}
```

- `body`는 8000자까지다. 더 길면 같은 내용이 여러 `text` 프레임으로 나뉘어 온다(번호가 따로 붙는다).
- 일상 대화는 사람처럼 몇 초 간격으로 여러 프레임에 나눠 올 수 있다. 사용자가 관리 페이지에서 정한다.

**`note`**: 진행 안내. 예: "메일을 찾아보는 중…". 작게 보이고 푸시로 깨우지 않는다.

```json
{"type": "note", "seq": 59, "body": "메일함을 확인하고 있어요"}
```

**`files`**: 파일이 붙은 메시지.

```json
{"type": "files", "seq": 60, "body": "정리했어요",
 "files": [{"id": "f_9c1e0a7b2d4f", "name": "정리.md", "size": 1834, "mime": "text/markdown", "kind": "text"}]}
```

- 파일은 `GET /v1/files/{id}`로 받는다(8.5). 30일 뒤 지워진다.
- 본문이 길면 앞부분은 `text` 프레임으로, 마지막 부분이 `files.body`로 온다.

**`card`**: 승인 카드(9.1).

```json
{"type": "card", "seq": 61, "ref": "app:cl-3f9a0c12de:5b0e1f3a9c2d", "card": { … }}
```

**`card_closed`**: 같은 `ref`의 카드가 닫혔다(승인됨·거절됨·만료됨·실패). 받은 `card`로 그 카드를 바꿔 그린다. 닫힌 카드에는 보통 버튼이 없고, `headline`이 결과 문구다.

```json
{"type": "card_closed", "seq": 62, "ref": "app:cl-3f9a0c12de:5b0e1f3a9c2d", "card": { … }}
```

> 다시 받기에서는 `card`와 `card_closed`가 차례로 온다. 순서대로 처리하면 닫힌 카드가 닫힌 채로 보인다.

#### 번호 없는 프레임(상자에 남지 않는다)

| type | 필드 | 설명 |
|---|---|---|
| `typing` | 없음 | Lucia가 답을 쓰는 중. 몇 초마다 다시 올 수 있다. 다음 `text`가 오거나 몇 초 지나면 표시를 지운다. |
| `accepted` | `id`(말의 ID, 없으면 `null`) | 앱의 `message`·`event`를 받았다. 처리했다는 뜻은 아니다. 답은 나중에 `text`로 온다. |
| `result` | `id`, `ok`, `data` / `error` | 앱의 `call`에 대한 답(6장) |
| `invoke` | `id`, `method`, `params` | 데몬의 부탁(7장) |
| `error` | `code`, `message` | 오류(15장). `message`는 사람용 한국어이고 바뀔 수 있다. 앱은 `code`로 가른다. |
| `pong` | 없음 | 앱의 `ping`에 대한 답 |
| `resumed` | 5.4 | 다시 받기의 끝 |
| `profile` | `avatar`(8.7의 객체 또는 `null`) | Lucia의 프로필 그림이 바뀌었거나 없어졌다. 받은 값으로 바꿔 그린다. 꺼져 있던 앱은 다음 `welcome.profile`에서 받는다. |

### 5.6 앱 → 데몬 프레임

**`message`**: 사용자의 말.

```json
{"type": "message", "text": "내일 일정 알려 줘", "files": ["f_9c1e0a7b2d4f"], "id": "m-1700000000001"}
```

| 필드 | 형식 | 필수 | 설명 |
|---|---|---|---|
| `text` | 문자열 | `files`가 없으면 예 | 앞뒤 공백은 지운다. 8000자까지. |
| `files` | 문자열 배열 | 아니오 | 먼저 `POST /v1/files`로 올린 파일 ID. 10개까지. 이 앱이 올린 파일만 쓸 수 있다. |
| `id` | 문자열 | 아니오 | 앱이 붙인 ID. `accepted.id`로 돌아온다(보낸 말과 짝 맞추기). |

- 성공하면 `accepted`가 온다.
- 실패하면 오류가 온다:
  - `empty`: 글도 파일도 없다.
  - `too_long`: 글이 너무 길다.
  - `bad_file`: 쓸 수 없는 파일 ID다(`message`에 그 ID가 들어 있다).
  - `closed`: 데몬이 멈추는 중이다.

**`press`**: 카드 버튼 누르기.

```json
{"type": "press", "ref": "app:cl-3f9a0c12de:5b0e1f3a9c2d", "action": "approve:apr-…", "input": "123456"}
```

| 필드 | 설명 |
|---|---|
| `ref` | 카드의 `ref`. 이 앱의 카드(`app:<자기 ID>:`로 시작)여야 한다. 아니면 `bad_ref`. |
| `action` | 누른 버튼의 `action` 그대로 |
| `input` | 값을 받는 버튼(`prompt`가 있는 버튼)만. 앞뒤 공백은 지운다. 서버는 로그에 남기지 않는다. |

- 성공해도 바로 답이 없다. 결과는 `card_closed`로 온다.
- 이미 닫힌 카드, 다른 곳에 올라간 카드, 모르는 `action`을 누르면 아무 일도 없다(조용히 무시).

**`ack`**: `{"type": "ack", "seq": 62}`(5.4). 답 없음.

**`presence`**: 앱이 앞에 있는지 알린다. 답 없음. 값이 틀리면 `bad_frame`.

```json
{"type": "presence", "state": "background"}
```

- `background`이면 연결이 살아 있어도 `text`·`files`·`card`가 올 때 푸시로 깨운다.
- 다시 앞으로 오면 `foreground`를 보낸다.
- 새 연결은 늘 `foreground`로 시작한다.

**`call`**: 메서드 호출(6장). `{"type": "call", "id": "c1", "method": "history", "params": {...}}`

**`result`**: 데몬의 `invoke`에 대한 답(7장).

**`event`**: 앱 연동의 이벤트. 예: 휴대폰 알림, 위치 도착.

```json
{"type": "event", "key": "noti-8812", "text": "택배: 문 앞에 두었습니다"}
```

| 필드 | 설명 |
|---|---|
| `key` | 선택. 같은 이벤트를 두 번 넣지 않기 위한 키(앞뒤 공백 제거, 120자까지). 없으면 데몬이 만든다. |
| `text` | 앞뒤 공백 제거, 1000자까지(넘으면 자른다) |

- 성공하면 `accepted`가 온다.
- 실패하면 오류가 온다:
  - `empty`: 빈 글이다.
  - `too_many_events`: 이 연결에서 한 시간 60개를 넘었다.
  - `unsupported`: 이벤트 입구가 없는 데몬이다.
- Lucia는 이 글을 **사람의 말이 아닌 외부 자료**로 다룬다. 신호 처리로 분류되고, 그 안의 지시를 따르지 않는다.

**`ping`**: `{"type": "ping"}`을 보내면 `pong`이 온다. 앱 쪽 생존 확인용이다.

모르는 `type`이나 깨진 JSON에는 `bad_frame`이 온다(연결은 그대로). 인증 뒤의 `hello`도 `bad_frame`이다.

### 5.7 생존 확인

- 데몬은 30초마다 WebSocket 핑(제어 프레임)을 보낸다. 표준 WebSocket 라이브러리는 자동으로 퐁을 보낸다.
- 데몬은 75초 동안 앱에서 **아무것도**(퐁 포함) 듣지 못하면 연결을 끊는다.
- 앱 쪽에서도 끊김을 빨리 알려면 30초 정도마다 `ping` 프레임을 보내고, `pong`이 10초 넘게 없으면 다시 연결한다.

---

## 6. 메서드(`call`)

### 6.1 호출과 답

```json
→ {"type": "call", "id": "c1", "method": "history", "params": {"limit": 50}}
← {"type": "result", "id": "c1", "ok": true, "data": {…}}
← {"type": "result", "id": "c1", "ok": false, "error": {"code": "forbidden", "message": "…"}}
```

- `id`는 앱이 정하는 문자열이다. 같은 연결 안에서 겹치지 않게 한다.
- `params`는 빼도 된다(`null`로 본다).
- 답은 다른 프레임과 섞여, 순서 없이 올 수 있다. `id`로 짝을 맞춘다.
- 연결이 끊기면 답은 오지 않는다. 앱은 다시 연결한 뒤 필요하면 다시 부른다.
- 부를 수 있는 메서드 목록은 `welcome.methods`에 있다.

### 6.2 `history`: 대화 기록

| params | 형식 | 기본 | 설명 |
|---|---|---|---|
| `before` | 정수 | 없음 | 이 기록 ID보다 앞의 것만 |
| `limit` | 정수 | 50 | 1~100(밖의 값은 맞춘다) |
| `scope` | 문자열 | `"app"` | `app`: 이 앱의 대화. `owner`: 이 앱 + Discord DM + 다른 앱의 대화. 서버 채널 대화는 넣지 않는다. |

```json
{"items": [
   {"id": 1201, "at": "2026-11-19T00:10:00+00:00", "from": "user", "conversation": "app:cl-3f9a0c12de", "body": "내일 일정 알려 줘"},
   {"id": 1202, "at": "2026-11-19T00:10:09+00:00", "from": "lucia", "conversation": "app:cl-3f9a0c12de", "body": "내일은…"}
 ],
 "more": true}
```

- `items`는 **오래된 것부터** 온다. `more`가 `true`이면 더 앞이 있다. 더 읽으려면 `before`에 첫 항목의 `id`를 넣는다.
- `from`:
  - `user`: 사용자
  - `lucia`: Lucia
  - `daemon`: 데몬이 남긴 기록(브리핑 등)
- 기록은 데몬의 대화 기록(에피소드)이다. 카드·파일 프레임 자체는 들어 있지 않다.
  - 파일이 붙은 말은 본문 끝에 파일 설명 줄이 붙어 있을 수 있다.
  - 사용자가 보관 기간을 정했으면 오래된 기록은 지워진다.
- 오류: `bad_params`(scope 값이 틀렸다), `failed`.

### 6.3 `commands`: 쓸 수 있는 명령

```json
{"commands": [{"name": "권한", "about": "권한 모드 보기·바꾸기",
               "options": ["엄격", "기본", "해제"], "current": "기본", "can_change": false},
              {"name": "모델", "about": "주 모델 보기·바꾸기",
               "options": ["fable", "opus", "sonnet", "haiku", "default"], "current": "default",
               "can_change": true}]}
```

- `options`는 `command`의 `option`으로 그대로 보낼 수 있는 값이다.
- `current`는 지금 값이고 `options` 가운데 하나다. `모델`의 `default`는 "고르지 않았다(데몬 설정 파일의 모델)"는 뜻이다.
  - 값은 다른 곳(관리 페이지, Discord)에서도 바뀐다. 바뀌었다는 프레임은 오지 않으므로, 고르는 화면을 열 때 `commands`를 다시 부른다.
- `can_change`는 이 토큰으로 바꿀 수 있는지다. `권한`은 `security_mode` 범위가 있을 때만, `모델`은 늘 `true`다.
- 명령은 늘어날 수 있다. 앱은 이 목록으로 화면을 그리고, 모르는 명령도 `options`를 고르는 모양으로 보이면 된다.

### 6.4 `command`: 명령 실행

| params | 설명 |
|---|---|
| `name` | `"권한"` 또는 `"모델"` |
| `option` | 선택. 없으면 지금 값을 보기만 한다. `권한`: `"엄격"`·`"기본"`·`"해제"`(또는 `strict`·`standard`·`open`). `모델`: `fable`·`opus`·`sonnet`·`haiku`, 설정 파일 값으로 되돌리려면 `default`(또는 `"기본"`). |

- 답: `{"text": "🛡️ 권한 모드: **기본** — …"}`. 마크다운 글이다.
- `모델`(결정 0055): Lucia의 대화·작업·브리핑이 쓰는 모델이다.
  - 바꾸면 다음 실행부터 적용되고 대화는 이어진다. 바꾼 뒤 첫 답은 평소보다 느릴 수 있다.
  - 대화가 길 때는 바꾸기 전에 `session.compact`(6.6)를 먼저 부르면 새 모델이 다시 읽을 양이 줄어든다.
  - 보기의 답은 지금 모델과 고를 수 있는 모델의 설명이다. 바꾼 것은 사용자의 알림 대화에도 기록된다.
- 오류:
  - `unknown_command`
  - `forbidden`: `권한`에 `option`이 있는데 `security_mode` 범위가 없다.
  - `timeout`: 5초 안에 답이 없었다. 그때는 적용되지 않는다.
  - `failed`, `closed`, `unsupported`
- 모드 값이 틀리면 오류가 아니라 안내 글이 `text`로 온다.
- 모드를 바꾸면 Discord DM(있으면)에 기록이 남는다.

### 6.5 `push.register` / `push.unregister`

| params | 설명 |
|---|---|
| `token` | FCM 등록 토큰. `[A-Za-z0-9:_.-]`만 쓸 수 있고 4096자까지다. |
| `platform` | `"android"` \| `"ios"` \| `"web"` |

- `push.register`의 답은 `{"registered": true, "available": <데몬 푸시 가능>}`이다. 오류는 `bad_params`, `failed`.
- `push.unregister`의 답은 `{"registered": false}`이다.
- HTTP `PUT`/`DELETE /v1/push`와 같다(8.6).

### 6.6 `session.compact`: 대화 정리하고 끝날 때까지 기다리기(결정 0056)

Lucia의 상주 대화를 요약해 줄인다(모델 1회). 답은 **정리가 끝난 뒤에** 온다. 주 모델을 바꾸기 전에 부르면 새 모델이 다시 읽을 대화가 줄어든다(6.4의 `모델`).

| params | 형식 | 기본 | 설명 |
|---|---|---|---|
| `min_tokens` | 정수(0 이상) | 0 | 대화 맥락이 이보다 작으면 정리하지 않고 `small`로 답한다. 0이면 크기를 보지 않는다. |

```json
→ {"type": "call", "id": "s1", "method": "session.compact", "params": {"min_tokens": 80000}}
← {"type": "result", "id": "s1", "ok": true,
   "data": {"outcome": "compacted", "pre_tokens": 128662, "context_tokens": 2778,
            "text": "대화를 정리했습니다(128,662→2,778 토큰)."}}
```

| `outcome` | 뜻 | 모델 호출 | `context_tokens` |
|---|---|---|---|
| `compacted` | 정리했다. `pre_tokens`는 정리 전 크기다 | 1회 | 정리 뒤 크기 |
| `unchanged` | 마지막 정리 뒤에 새 대화가 없어 다시 하지 않았다 | 없음 | 지금 크기 |
| `small` | 맥락이 `min_tokens`보다 작아 하지 않았다 | 없음 | 지금 크기 |
| `none` | 정리할 대화(시작된 세션)가 없다 | 없음 | 0 |
| `reset` | 정리하지 못해 새 세션으로 넘어갔다. 기억과 최근 대화로 이어 간다 | 1회(실패) | 0 |

- 다섯 경우 모두 `ok: true`이고, 그 뒤에 주 모델을 바꿔도 된다. `text`는 사용자에게 그대로 보여 줄 수 있는 글이다.
- **기다리는 시간**: 정리 자체는 보통 20~75초다. 다만 차례대로 돈다. Lucia가 답하는 중이거나 정기 작업이 돌면 그것이 끝난 뒤에 시작한다. 데몬은 실행 시간 제한의 두 배에 30초를 더한 만큼(운영 설정으로 630초) 기다린 뒤 `timeout`으로 답한다.
  - 앱은 이 메서드에 짧은 시간 제한을 걸지 않는다. 기다리는 동안 진행 표시를 보이고, 연결은 평소처럼 유지한다(핑에 답한다).
  - 정리하는 동안 사용자가 보낸 말은 정리가 끝난 뒤에 처리된다.
- 오류:
  - `busy`: 앱이 청한 정리가 이미 큐에 있거나 돌고 있다. 몇 초 뒤에 다시 부른다.
  - `timeout`: 제때 끝나지 않았다. 아직 시작하지 않은 요청은 버려지고(뒤늦게 돌지 않는다), 이미 시작한 정리는 끝까지 간다.
  - `bad_params`: `min_tokens`가 0 이상의 정수가 아니다.
  - `failed`: 정리를 돌리지 못했다(세션은 그대로다). `closed`, `unsupported`.
- **연결이 끊겼을 때**: 답은 오지 않지만 정리는 계속된다. 다시 연결해 한 번 더 부르면 된다. 이미 끝났으면 모델을 부르지 않고 `unchanged`가 오고, 아직 도는 중이면 `busy`가 온다. 그래서 다시 불러도 정리가 두 번 돌지 않는다.
- 정리는 **지금 주 모델**이 한다. 그래서 순서가 중요하다: 정리 → 모델 바꾸기. 반대로 하면 새 모델이 대화 전체를 먼저 읽는다.
- 범위는 필요 없다.

---

## 7. 데몬의 부탁(`invoke`)

앱에서만 할 수 있는 일(위치, 휴대폰 알림 읽기, 카메라 등)을 데몬이 앱에 부탁하는 길이다.

```json
← {"type": "invoke", "id": "d-7a1c0e9b3f52", "method": "location.get", "params": {"precise": true}}
→ {"type": "result", "id": "d-7a1c0e9b3f52", "ok": true, "data": {"lat": 37.5, "lng": 127.0}}
→ {"type": "result", "id": "d-7a1c0e9b3f52", "ok": false, "error": {"code": "denied", "message": "사용자가 거절"}}
```

### 7.1 부탁과 답

- 데몬은 앱이 `hello.features`로 알린 기능만 부른다. 기능 이름은 메서드 이름의 첫 마디다(`location.get` → `location`).
- 앱은 같은 `id`로 `result`를 한 번 보낸다. 오류의 `code`는 앱이 정한다.
  - 권장 코드: `denied`(사용자·OS가 거절), `unavailable`(지금 할 수 없음), `bad_params`, `failed`.
- 부탁마다 데몬이 정한 시간 제한이 있다. 늦은 답은 `bad_ref` 오류를 받는다.
- 기다리는 부탁이 아닌 `id`로 답하면 `bad_ref`가 온다. 연결이 끊기면 데몬 쪽 부탁은 실패로 끝난다.
- `result.data`의 글은 Lucia가 외부 자료로 다룬다.
- 시간 제한은 40초다(Lucia의 도구 호출 한도 안에서 답해야 한다). 사용자에게 물어야 하는 일(권한 창)은 그 안에 끝나지 않으면 `unavailable`이나 `denied`로 답하고, 다음 호출에서 되게 한다.

### 7.2 Lucia에게 기능 내주기(`hello.tools`, 결정 0053)

앱은 자기가 할 수 있는 일을 `hello.tools`로 알린다. Lucia는 그 목록을 보고(도구 `app_tools`) 필요할 때 부른다(도구 `app_call` → 위의 `invoke`). **무엇을 내줄지는 앱이 정한다.** 데몬은 미리 정해 둔 기능 목록이 없다.

```json
"tools": [
  {"name": "location.get", "description": "휴대폰의 현재 위치(위도·경도·정확도)", "params": {"precise": "boolean, 정밀 위치(기본 false)"}},
  {"name": "notifications.recent", "description": "최근 휴대폰 알림 목록", "params": {"limit": "1~50"}},
  {"name": "alarm.set", "description": "알람 맞추기", "params": {"time": "HH:MM", "label": "string"}, "effect": "write"}
]
```

| 필드 | 형식 | 규칙 |
|---|---|---|
| `name` | 문자열 | `기능.동작`(마디 둘이나 셋). 마디는 `[a-z0-9_-]` 40자까지. 어기는 것은 버린다. 겹치면 처음 것만. |
| `description` | 문자열 | Lucia가 읽고 고르는 설명. 한 줄로 정리되고 200자까지. **언제 쓰는지와 무엇이 돌아오는지**를 적는다. |
| `params` | JSON | 인자 설명. 모양은 자유다(JSON Schema든 `{"이름": "설명"}`이든). Lucia에게 그대로 보인다. 직렬화해 2 KB를 넘으면 버린다. |
| `effect` | 문자열 | `"write"`면 무엇을 바꾸는 기능이다(알람 맞추기, 메시지 보내기 등). 빼면 읽기다. |

- 32개까지 받는다. 받아들인 이름은 `welcome.tools`로 돌아온다.
- `tools`에 적은 이름의 첫 마디는 `features`에 따로 적지 않아도 된다(데몬이 더한다).
- **사용자 허락**: 관리 페이지 "연결"에서 그 앱 토큰에 범위 `app_tools`("Lucia가 이 앱의 기능 부르기 허용")를 켜야 Lucia가 부른다. 기본은 꺼져 있다. `welcome.scopes`에 `app_tools`가 있는지로 안다. 없으면 앱이 "관리 페이지에서 허용해 달라"고 안내하면 좋다.
- **읽기와 쓰기**:
  - 읽기 기능은 Lucia가 사람 없이 도는 일(브리핑 등)에서도 부를 수 있다. 원치 않으면 그 기능을 내주지 않거나 앱에서 거절한다.
  - `effect: "write"`는 사용자가 Lucia와 대화 중일 때만 부른다.
  - 휴대폰의 권한(위치 권한 등)과 사용자 확인은 앱이 맡는다. 거절이면 `denied`로 답한다.
- **연결돼 있을 때만**: 앱이 끊겨 있으면 Lucia는 "앱이 연결되어 있지 않다"는 답을 받는다. 데몬이 푸시로 깨워서 부르지는 않는다 [제안: 필요하면 더한다].
- **결과**: `result.data`는 JSON이면 무엇이든 된다. Lucia에게 8 KB까지 보인다(넘으면 잘린다). 외부 자료로 다뤄지므로 지시처럼 읽히는 글을 넣어도 따르지 않는다.
- 다시 연결하면 `tools`를 다시 보낸다(연결마다 새로 정한다). 앱이 기능을 더하거나 빼면 다음 `hello`부터 반영된다.

---

## 8. HTTP API

공통:
- 기준 주소는 2.1에 있다.
- 인증은 `Authorization: Bearer <토큰>`이다(`/v1/health` 빼고).
- 응답 본문은 JSON이다(파일 받기 빼고).
- 오류 본문은 늘 아래 모양이다.

```json
{"error": {"code": "unauthorized", "message": "토큰이 틀렸거나 취소됐다"}}
```

공통 오류:

| 상태 | code | 경우 |
|---|---|---|
| 400 | `forbidden` | 출처를 알 수 없다(2.2) |
| 403 | `forbidden` | 받지 않는 출처, 또는 기기 역할 토큰 |
| 401 | `unauthorized` | 토큰이 없거나 틀렸거나 취소됐다 |
| 429 | `too_many_attempts` | 그 주소가 막혀 있다(3.3) |

### 8.1 `GET /v1/health`

인증 없음. `200`, 본문 `ok`(text/plain).

### 8.2 `GET /v1/me`

```json
{"v": 2, "client": "cl-3f9a0c12de", "name": "휴대폰 앱", "conversation": "app:cl-3f9a0c12de",
 "scopes": [], "methods": ["commands", "command", "history", "push.register", "push.unregister", "session.compact"],
 "seq": 62, "acked": 60, "online": true,
 "push": {"available": true, "registered": true}}
```

- `seq`: 마지막으로 매긴 번호
- `acked`: 앱이 마지막으로 받았다고 알린 번호
- `online`: 지금 WebSocket이 붙어 있다
- `profile`: `welcome.profile`과 같다(위 예시에는 뺐다). 없으면 그 기능이 없는 데몬이다.

### 8.3 `GET /v1/history`

질의 `before`, `limit`, `scope`는 6.2와 같다. 답도 같다. 오류는 `400`과 `bad_params`.

### 8.4 `POST /v1/files?name=<파일 이름>`

- **본문이 파일 그대로**다(multipart 아님). `Content-Type`은 보지 않는다. 종류는 파일 앞부분의 서명으로 정한다.
- `name`은 퍼센트 인코딩한다. 빼면 `file`이다. 서버가 이름을 다듬는다:
  - 경로 앞부분을 지운다.
  - 제어 문자와 `/ \ : * ? " < > |`는 `_`로 바꾼다.
  - 앞의 `.`을 지운다.
  - 100자가 넘으면 줄인다.
  - 다듬은 이름이 답으로 온다.
- 크기는 31,457,280바이트(30 MiB)까지다. 빈 파일은 받지 않는다.
- 토큰을 확인한 뒤에야 본문을 읽는다.

| 상태 | 본문 |
|---|---|
| 201 | `{"id", "name", "size", "mime", "kind"}`(9.2) |
| 400 | `bad_file`(빈 파일, 크기 초과 등) |
| 413 | `too_large`(본문이 상한을 넘었거나 끊겼다) |
| 501 | `unsupported`(파일을 받지 않는 데몬) |

- 올린 파일은 **이 앱의 대화로만** 쓸 수 있다. `message.files`로 붙이면 Lucia가 연다:
  - 이미지·PDF는 10 MB까지 그대로 본다.
  - 텍스트·Office 문서는 글로 뽑아 읽는다.
  - 그 밖의 종류는 받았다는 사실만 안다.
- 30일 뒤 지운다.

### 8.5 `GET /v1/files/{id}`

- 이 앱의 대화로 오간 파일(앱이 올린 것, 데몬이 보낸 것)만 받을 수 있다.
- `200`이면 본문이 파일이다. 머리:
  - `Content-Type: <mime>`
  - `Content-Disposition: attachment; filename*=UTF-8''<퍼센트 인코딩 이름>`
  - `X-Content-Type-Options: nosniff`
  - `Cache-Control: private, no-store`
- `404 not_found`: 없거나 다른 대화의 파일이다.
- `410 gone`: 보관 기간이 지나 지웠다.

### 8.6 `PUT /v1/push`, `DELETE /v1/push`

```
PUT /v1/push
Content-Type: application/json

{"token": "<FCM 등록 토큰>", "platform": "android"}
```

- `PUT`:
  - 성공하면 `200 {"registered": true, "available": <불>}`.
  - 형식이 틀리면 `400 bad_params`.
  - `Content-Type`이 JSON이 아니면 `415`, JSON이 깨졌으면 `400`/`422`(프레임워크 기본 응답, 본문 형식이 다르다).
- `DELETE`: `200 {"registered": false}`.
- WebSocket 없이 부를 수 있다. 백그라운드에서 FCM 토큰이 바뀌었을 때(`onNewToken`) 쓴다.

### 8.7 `GET /v1/profile`: Lucia의 프로필(결정 0060)

Lucia가 스스로 정한 프로필 그림의 정보다. `welcome.profile`, `/v1/me`의 `profile`, `profile` 프레임과 같은 모양이다.

```json
{"avatar": {"id": "a_cfc0dacd5dcd71abf496c418", "mime": "image/png", "width": 800, "height": 800,
            "size": 579183, "updated_at": "2026-11-20T04:43:07Z"}}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `avatar` | 객체 또는 `null` | `null`이면 정한 그림이 없다. 앱의 기본 모습을 쓴다. |
| `avatar.id` | 문자열 | 그림의 ID(`a_` + 16진수 24자). **내용 해시**라서 그림이 바뀌면 ID가 바뀐다. 캐시 키로 쓴다. |
| `avatar.mime` | 문자열 | `image/png` 또는 `image/jpeg`. 움직이는 그림은 없다. |
| `avatar.width`, `avatar.height` | 정수 | 픽셀. 늘 정사각형이고 한 변은 128~2048이다. |
| `avatar.size` | 정수 | 바이트(3 MiB까지) |
| `avatar.updated_at` | 문자열 | 정한 시각(RFC 3339, UTC) |

- `200`이고 `Cache-Control: private, no-cache`다.
- `501 unsupported`: 프로필 이미지 기능이 없는 데몬이다.

### 8.8 `GET /v1/profile/avatar/{id}`: 프로필 그림

- `200`이면 본문이 그림이다. 머리:
  - `Content-Type: image/png` 또는 `image/jpeg`
  - `ETag: "<id>"`
  - `X-Content-Type-Options: nosniff`
  - `Cache-Control: private, max-age=31536000, immutable`
- ID가 내용 해시이므로 **한 번 받은 그림은 다시 받을 필요가 없다.** 앱은 ID를 키로 디스크에 캐시한다.
- 예전 ID의 그림도 한동안(최근 20개) 받을 수 있다. 지워진 ID는 `404 not_found`다.
- 다른 요청과 같이 `Authorization: Bearer <토큰>`이 있어야 한다. 그림을 주소로 바로 그리는 이미지 로더를 쓰면 **인증 머리를 붙이게 설정**하거나, 앱이 직접 받아 캐시한 파일을 그린다.

---

## 9. 데이터 형식

### 9.1 카드(`card`)

```json
{
  "headline": "확인해 주세요",
  "title": "메일 보내기",
  "fields": [{"name": "받는 사람", "value": "a@example.com", "inline": true}],
  "body": "안녕하세요, …",
  "footer": "만료 11-19 15:00 · apr-1a2b",
  "tone": "pending",
  "buttons": [
    {"action": "approve:apr-1a2b", "label": "보내기", "primary": true, "prompt": null},
    {"action": "reject:apr-1a2b", "label": "거절", "primary": false, "prompt": null}
  ]
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `headline` | 문자열 | 맨 위 한 줄(알림 미리보기용). 닫힌 카드에서는 결과 문구다(예: "✅ 보냈습니다"). |
| `title` | 문자열 | 제목(고정 문구) |
| `fields` | 배열 | `{name: 고정 문구, value: 마크다운, inline: 옆 칸과 나란히 놓아도 되는 짧은 값}` |
| `body` | 문자열 | 본문(마크다운, 비어 있을 수 있음). 모델이 쓴 글은 이스케이프돼 있다. |
| `footer` | 문자열 \| null | 꼬리말(시각, ID 같은 데몬 값) |
| `tone` | 문자열 | `pending`(대기), `success`, `danger`, `warning`, `neutral`. 색과 아이콘에 쓴다. |
| `buttons` | 배열 | 아래. 닫힌 카드는 보통 비어 있다. |

버튼:

| 필드 | 형식 | 설명 |
|---|---|---|
| `action` | 문자열 | 누를 때 `press.action`으로 그대로 보낸다. 해석하지 않는다. |
| `label` | 문자열 | 버튼 글 |
| `primary` | 불 | 되돌릴 수 없는 일(보내기 등). 강조해 그린다. |
| `prompt` | 객체 \| null | 있으면 누를 때 값을 하나 입력받는다(인증 코드 등). |

`prompt`:

| 필드 | 형식 | 설명 |
|---|---|---|
| `title` | 문자열 | 입력창 제목(45자까지) |
| `label` | 문자열 | 칸 이름(45자까지) |
| `placeholder` | 문자열 \| null | 자리 표시 글 |
| `min_len`, `max_len` | 정수 | 입력 길이 범위. 앱이 먼저 확인한다. |

- 입력값은 `press.input`으로 보낸다. 앱은 이 값을 저장하거나 로그에 남기지 않는다.
- 승인 카드는 만료 시간이 있다. 답이 없으면 거부로 닫힌다(`card_closed`, "⌛ 답이 없어 만료됐습니다").

### 9.2 파일

```json
{"id": "f_9c1e0a7b2d4f", "name": "정리.md", "size": 1834, "mime": "text/markdown", "kind": "text"}
```

| `kind` | 뜻 |
|---|---|
| `image` | 이미지 |
| `pdf` | PDF |
| `text` | 텍스트 계열 |
| `office` | Office 문서 |
| `other` | 그 밖(Lucia가 열 수 없는 종류) |

`mime`은 서버가 파일 서명으로 정한 값이다.

### 9.3 기록 항목

6.2를 본다: `{id: 정수, at: RFC 3339, from: "user"|"lucia"|"daemon", conversation: 문자열|null, body: 문자열}`.

---

## 10. 푸시(FCM)

### 10.1 준비

- 데몬: 관리 페이지 → 연결 → "앱 알림과 푸시"에 Firebase **서비스 계정 JSON**을 넣는다.
- 앱:
  - 같은 Firebase 프로젝트로 FCM SDK를 설정한다. iOS는 Firebase 콘솔에 APNs 인증 키를 등록한다.
  - 등록 토큰을 받으면, 그리고 바뀔 때마다 `push.register`나 `PUT /v1/push`로 등록한다.

### 10.2 언제 오나

| 상황 | 푸시 |
|---|---|
| 앱이 연결돼 있고 `foreground` | 보내지 않음 |
| 앱이 연결돼 있고 `background` | `text`·`files`·`card`일 때 보냄 |
| 앱이 끊겨 있음(푸시 토큰 있음) | `text`·`files`·`card`일 때 보냄. 프레임은 상자에 쌓인다. |
| `note`, `card_closed`, `typing` | 보내지 않음 |

- 글(`text`·`files`)은 한 앱에 **10초에 한 번까지**만 보낸다. 그 사이 것은 앱을 열면 받는다.
- 카드는 늘 보낸다.

### 10.3 메시지 모양

```json
{
  "notification": {"title": "Lucia", "body": "새 메시지가 있습니다"},
  "data": {"type": "wake", "kind": "text", "seq": "58", "client": "cl-3f9a0c12de"},
  "android": {"priority": "normal", "collapse_key": "lucia-messages",
              "notification": {"tag": "lucia-messages"}},
  "apns": {"headers": {"apns-priority": "5", "apns-collapse-id": "lucia-messages"}}
}
```

| `data.kind` | 기본 본문 | 우선순위 | 접기 |
|---|---|---|---|
| `text` | 새 메시지가 있습니다 | 보통(Android `normal`, APNs 5) | `lucia-messages`(앞 알림을 바꿔 끼움) |
| `files` | 파일이 왔습니다 | 보통 | `lucia-messages` |
| `card` | 확인할 카드가 있습니다 | 높음(Android `high`, APNs 10) | 없음(카드마다 따로) |

- 관리 페이지에서 "푸시에 글 앞부분 싣기"를 켜면 본문이 글 앞 120자로 바뀐다(마스킹을 거친다). 기본은 고정 문구다.
- `data`의 값은 모두 문자열이다. `seq`는 그 프레임의 번호다.
- 관리 페이지의 "시험 푸시"는 `kind: text`, `seq: "0"`이다.
- 앱 처리:
  - 푸시를 받으면, 또는 사용자가 알림을 누르면 WebSocket으로 연결해 `hello.resume`으로 받는다.
  - **푸시 본문을 대화 내용으로 쓰지 않는다**(미리보기가 꺼져 있으면 고정 문구다).
  - iOS 백그라운드 처리 시간이 모자라면 알림만 보이고, 열 때 받는다.
- 토큰이 무효(UNREGISTERED)가 되면 데몬이 지운다. 그 뒤로는 앱이 끊겨 있을 때 글이 Discord DM으로 간다(11장). 앱은 시작할 때마다 토큰을 다시 등록해 두는 것이 안전하다.

---

## 11. 메시지가 가는 곳(라우팅)

- **앱 대화로 가는 것**:
  - 앱에서 한 말에 대한 답과 진행 안내
  - 앱 대화가 정한 카드
  - "알림 받을 곳"이 이 앱이면 브리핑·알림·승인 카드
- "알림 받을 곳"은 관리 페이지 연결 화면에서 정한다. `welcome.notify_target`으로 알 수 있다. 정하지 않았으면 앱에서 시킨 일의 승인 카드도 Discord DM으로 간다.
- 앱 대화로 갈 것은 이 순서로 처리된다.
  1. 앱이 연결돼 있으면 앱으로 보낸다(상자에도 남는다).
  2. 끊겨 있어도 푸시를 쓸 수 있으면(데몬 자격 증명 + 이 앱의 푸시 토큰) 상자에 쌓고 푸시로 깨운다. 카드는 앱에서 누른다.
  3. 둘 다 아니면 **Discord DM으로 보낸다.** 그 글과 카드는 앱 상자에 들어가지 않는다. 카드는 Discord에서 누른다.
     - 기록(`history`)에는 남는다. 앱 대화에서 한 말의 답은 앱 대화 기록으로 남는다.
- 소유자가 Discord DM에서 직접 한 대화의 답은 DM으로 간다.
- 작업 스레드(긴 작업의 진행)는 Discord에만 있다.

---

## 12. 앱 구현 지침

### 12.1 앱이 저장할 것

| 값 | 설명 |
|---|---|
| 토큰 | 안전한 저장소에 둔다 |
| 서버 주소 | 집 안, 밖 |
| `lastSeq` | 처리를 마친 마지막 `seq`. 처음은 0. |
| 화면용 기록 | 받은 프레임이나 `history` 결과. 카드는 `ref`로 찾아 바꿔 그린다. |
| Lucia의 프로필 그림 | `profile.avatar.id`와 그 그림 파일(8.8). ID가 같으면 다시 받지 않는다. 연결하지 못해도 마지막으로 받은 그림을 보인다. |

### 12.2 프레임 처리

1. `seq ≤ lastSeq`이면 버린다(중복).
2. 화면에 반영하고 기기에 저장한다.
3. `lastSeq = seq`.
4. `ack`를 보낸다. 매번 보내도 되고, 1초쯤 모아 마지막 값 하나만 보내도 된다.

### 12.3 연결 관리

- 시작할 때, 앞으로 올 때, 푸시를 받았을 때 연결한다. `hello.resume = lastSeq`(처음이면 0)를 준다.
- `welcome` 뒤 `resumed.truncated`가 `true`이면 `history`를 다시 읽고 `lastSeq = resumed.last`로 맞춘다.
- 처음 설치했으면 `history`(`scope: "owner"`면 Discord에서 나눈 대화까지)로 화면을 채운다.
- 끊기면 지수 백오프로 다시 연결한다. 1초에서 시작해 두 배씩, 30초까지 늘리고 흔들림을 섞는다.
  - `unauthorized`나 `unsupported_role`로 끊겼으면 다시 연결하지 않는다. 사용자에게 토큰을 다시 넣게 한다.
  - `too_many_attempts`이면 10분 기다린다.
- 뒤로 갈 때는 `presence: background`를 보낸다. OS가 소켓을 닫아도 괜찮다(푸시가 깨운다). 앞으로 오면 `presence: foreground`를 보내거나 새로 연결한다.
- 말을 보낼 때는 `message.id`를 붙인다. `accepted`가 오지 않고 끊기면, 다시 연결한 뒤 `history`로 그 말이 들어갔는지 보고 다시 보낼지 정한다.
  - 데몬은 같은 말을 거르지 않는다. 두 번 보내면 두 번 답한다.

### 12.4 파일 보내기

1. `POST /v1/files?name=…`로 올려 `id`를 받는다.
2. `message.files`에 넣어 보낸다(글은 비워도 된다).
3. 올린 뒤 오래 붙이지 않은 파일은 30일 뒤 지워진다.

### 12.5 카드

- `card`는 `ref`로 저장한다. `card_closed`가 오면 같은 `ref`를 바꿔 그린다.
- 버튼을 누르면 결과(`card_closed`)가 올 때까지 그 카드의 버튼을 잠근다. 잠깐 기다려도 결과가 없으면 다시 누를 수 있게 푼다.
- 카드 내용(받는 사람, 본문 등)은 사용자가 무엇을 승인하는지 알 수 있게 줄이지 말고 보인다.

---

## 13. 보안 요구 사항

- 토큰은 안전한 저장소에만 둔다. 로그, 분석, 오류 보고, 백업에 넣지 않는다.
- 밖에서는 `wss`/`https`만 쓴다. 인증서 검증을 끄지 않는다.
- `X-Forwarded-For`·`Forwarded` 머리를 보내지 않는다(2.2).
- 카드의 값 입력(`prompt`, 인증 코드 등)은 저장하거나 로그에 남기지 않는다.
- 메시지와 카드의 마크다운을 HTML로 해석하지 않는다. 웹뷰로 그린다면 이스케이프한다.
- 앱을 잃어버렸거나 토큰이 샜으면 관리 페이지에서 바로 취소한다.
  - "알림 받을 곳"이 이 앱이면, 토큰을 가진 사람은 승인 카드를 받아 누를 수 있다.
  - `security_mode` 범위가 있으면 권한 모드도 바꿀 수 있다.
  - 주 모델은 범위 없이 바꿀 수 있다(더 큰 모델로 바꾸면 사용량을 빨리 쓴다). 바뀌면 알림 대화에 남는다.
- `event`와 `invoke` 답으로 보내는 외부 글(다른 앱의 알림 등)은 Lucia가 외부 자료로 다룬다. 다만 개인 정보이므로 보낼 것을 사용자가 고르게 한다.

---

## 14. 한도와 시간 모음

| 항목 | 값 |
|---|---|
| WebSocket 프레임(앱 → 데몬) | 64 KiB |
| `hello` 기다림 | 10초 |
| 데몬 핑 간격 / 무응답 한도 | 30초 / 75초 |
| `message.text` | 8000자 |
| `text`·`note` 프레임 하나(데몬 → 앱) | 8000자(넘으면 나뉨) |
| 말 하나의 파일 수 | 10 |
| 파일 하나 | 31,457,280바이트(30 MiB) |
| 파일 보관 | 30일 |
| 다시 받기 한 번 | 500 프레임 |
| 상자 보관 | ack한 것 7일, 안 한 것 30일 |
| `history.limit` | 1~100(기본 50) |
| `event.text` / `event.key` | 1000자 / 120자 |
| 이벤트 | 연결마다 한 시간 60개 |
| `hello.features` | 32개, 이름 40자 |
| 명령 답 기다림 | 5초 |
| `session.compact` 답 기다림 | 실행 시간 제한 × 2 + 30초(운영 설정으로 630초). 정리 자체는 보통 20~75초 |
| 인증 실패 | 한 주소 10분에 10번이면 그 10분 동안 막음 |
| 클라이언트 수 | 20 |
| 글 푸시 간격 | 한 앱 10초 |
| 푸시 미리보기 | 120자 |
| 푸시 토큰 | 4096자, `[A-Za-z0-9:_.-]` |
| 프로필 그림 | PNG·JPEG(움직이지 않음), 정사각형, 한 변 128~2048px, 3,145,728바이트(3 MiB)까지 |

`[제안]`인 값(보관 기간, 이벤트 상한, 푸시 간격 등)은 바뀔 수 있다. 바뀌면 이 표를 고친다.

---

## 15. 오류 코드 모음

### 15.1 WebSocket `error` 프레임

| code | 경우 | 연결 |
|---|---|---|
| `hello_required` | 첫 프레임이 `hello`가 아니다 | 끊음 |
| `unauthorized` | 토큰이 틀렸거나 취소됐다(인증 때, 또는 연결 중 취소) | 끊음 |
| `too_many_attempts` | 그 주소가 인증 실패로 막혀 있다 | 끊음 |
| `unsupported_role` | 받지 않는 역할 | 끊음 |
| `bad_frame` | 깨진 JSON, 모르는 `type`, 바이너리 프레임, 두 번째 `hello`, 틀린 `presence.state` | 유지 |
| `empty` | 빈 말(글·파일 없음), 빈 이벤트 | 유지 |
| `too_long` | 말이 8000자를 넘는다 | 유지 |
| `bad_file` | 쓸 수 없는 파일 ID, 파일 10개 초과, 파일을 받지 않는 데몬 | 유지 |
| `bad_ref` | 자기 카드가 아닌 `press`, 기다리는 부탁이 아닌 `result` | 유지 |
| `too_many_events` | 이벤트 한 시간 상한 | 유지 |
| `unsupported` | 이벤트 입구가 없는 데몬 | 유지 |
| `forbidden` | (기기 연결) 이벤트가 아닌 프레임 | 유지 |
| `closed` | 데몬이 멈추는 중 | 곧 끊김 |

### 15.2 메서드 `result.error.code`

| code | 경우 |
|---|---|
| `unknown_method` | 없는 메서드 |
| `forbidden` | 필요한 범위가 없다 |
| `bad_params` | `params`가 틀렸다 |
| `unknown_command` | 없는 명령 |
| `timeout` | 처리기가 제때 답하지 않았다 |
| `busy` | 같은 일이 이미 돌고 있다(`session.compact`) |
| `failed` | 처리하지 못했다(저장소 오류 등) |
| `unsupported` | 이 데몬이 하지 않는 일 |
| `closed` | 데몬이 멈추는 중 |

### 15.3 HTTP

| 상태 | code | 경우 |
|---|---|---|
| 400 | `forbidden` | 출처를 알 수 없다(프록시 머리 문제) |
| 400 | `bad_params` | 기록·푸시 매개변수가 틀렸다 |
| 400 | `bad_file` | 올린 파일이 비었거나 너무 크다 |
| 401 | `unauthorized` | 토큰 문제 |
| 403 | `forbidden` | 받지 않는 출처, 기기 토큰 |
| 404 | `not_found` | 없는 파일, 남의 파일 |
| 410 | `gone` | 보관 기간이 지난 파일 |
| 413 | `too_large` | 올린 본문이 상한을 넘었거나 끊겼다 |
| 415, 422 | (프레임워크) | `PUT /v1/push`의 JSON 문제 |
| 429 | `too_many_attempts` | 그 주소가 막혀 있다 |
| 501 | `unsupported` | 파일을 받지 않는 데몬 |

---

## 16. 기기 역할(`device`)

센서·가젯처럼 사람이 아닌 기기용이다. 관리 페이지 화면에서는 아직 발급하지 않는다.

- 연결과 `hello`는 앱과 같다. `welcome`은 짧다.

```json
{"type": "welcome", "v": 2, "client": "cl-…", "name": "현관 센서", "role": "device",
 "capabilities": {"events": true, "max_event_chars": 1000}}
```

- 받는 프레임은 `event`(5.6과 같은 규칙, 연결마다 한 시간 60개)와 `ping`뿐이다. 그 밖의 프레임은 `forbidden`이다.
- 번호 붙는 프레임, 카드, 메서드, HTTP API는 없다.
- 이벤트 입구가 없는 데몬은 기기 연결을 `unsupported_role`로 거절한다.

---

## 17. 예시 흐름

### 17.1 처음 연결과 대화

```
→ {"type":"hello","token":"lucia_…","resume":0,"features":[]}
← {"type":"welcome","v":2,"client":"cl-3f9a0c12de",…,"seq":0,…}
← {"type":"resumed","from":0,"to":0,"last":0,"truncated":false}
→ {"type":"call","id":"h1","method":"history","params":{"scope":"owner","limit":50}}
← {"type":"result","id":"h1","ok":true,"data":{"items":[…],"more":false}}
→ {"type":"message","text":"내일 일정 알려 줘","id":"m1"}
← {"type":"accepted","id":"m1"}
← {"type":"typing"}
← {"type":"text","seq":1,"body":"내일은 10시에 …"}
→ {"type":"ack","seq":1}
```

### 17.2 승인 카드

```
← {"type":"card","seq":2,"ref":"app:cl-3f9a0c12de:5b0e1f3a9c2d","card":{…,"buttons":[{"action":"approve:apr-1a2b",…},{"action":"reject:apr-1a2b",…}]}}
→ {"type":"ack","seq":2}
→ {"type":"press","ref":"app:cl-3f9a0c12de:5b0e1f3a9c2d","action":"approve:apr-1a2b"}
← {"type":"card_closed","seq":3,"ref":"app:cl-3f9a0c12de:5b0e1f3a9c2d","card":{"headline":"✅ 보냈습니다",…,"buttons":[]}}
→ {"type":"ack","seq":3}
```

### 17.3 앱이 꺼져 있을 때

```
(앱 꺼짐) 데몬: 브리핑 → 상자 seq 4 → FCM {"data":{"type":"wake","kind":"text","seq":"4",…}}
(사용자가 알림을 누름)
→ {"type":"hello","token":"lucia_…","resume":3}
← {"type":"welcome",…,"seq":4}
← {"type":"text","seq":4,"replay":true,"body":"좋은 아침이에요. 오늘은 …"}
← {"type":"resumed","from":3,"to":4,"last":4,"truncated":false}
→ {"type":"ack","seq":4}
```

### 17.4 파일 보내기

```
POST /v1/files?name=%EC%98%81%EC%88%98%EC%A6%9D.jpg   (본문: JPEG)
← 201 {"id":"f_0a1b2c3d4e5f","name":"영수증.jpg","size":482113,"mime":"image/jpeg","kind":"image"}
→ {"type":"message","text":"이거 정리해 줘","files":["f_0a1b2c3d4e5f"],"id":"m2"}
← {"type":"accepted","id":"m2"}
```

---

## 18. 판 1에서 바뀐 점

| 판 1(결정 0021) | 판 2(결정 0043) |
|---|---|
| 번호·ack 없음, 끊긴 동안 잃음 | `seq`·`ack`·`hello.resume`·`resumed` |
| 파일을 base64로 프레임에 실음 | 파일은 ID로 알리고 HTTP로 받음 |
| 앱에서 파일 보내기 없음 | `POST /v1/files` + `message.files` |
| HTTP API 없음 | `/v1/me`, `/v1/history`, `/v1/files`, `/v1/push` |
| 메서드·`invoke` 없음 | `call`/`result`, `invoke`, `hello.features` |
| 앱 이벤트 없음 | 앱도 `event`를 보냄 |
| 끊긴 앱의 글은 늘 Discord로 | 푸시를 쓸 수 있으면 상자에 쌓고 FCM으로 깨움 |
| 승인 카드는 늘 Discord로 | "알림 받을 곳"을 앱으로 정할 수 있음 |
| 집 LAN·VPN에서만 | 설정하면 리버스 프록시 뒤 공개 주소로도 |
