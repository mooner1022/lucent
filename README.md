# Lucent

Lucia(홈서버에서 도는 1인용 비서 에이전트)의 안드로이드 클라이언트. 접속 API 사양은 [`docs/app-api.md`](docs/app-api.md)(프로토콜 판 2)이고, 그 뒤에 바뀐 것은 [`docs/app-api-changes.md`](docs/app-api-changes.md)에 있다(주 모델 고르기, 바꾸기 전에 대화 정리하기, 앱이 내준 기능을 루시아가 부르기, 루시아의 프로필 그림).

- 대화: Discord와 같은 루시아, 같은 기억. 진행 안내, 파일, 승인 카드(버튼·값 입력)를 받는다. 그림·마크다운·글 파일은 앱 안에서 미리 본다. 데몬이 관리하는 커스텀 이모지와 스티커를 함께 쓴다(규격 [`docs/app-expressions.md`](docs/app-expressions.md), 앱 쪽 구현 [`docs/emotes.md`](docs/emotes.md)).
- 사이트 로그인: 루시아가 사이트에 로그인해야 할 때 카드의 입력창에서 아이디와 비밀번호를 넣는다(값은 보내는 것 말고 어디에도 남기지 않는다). 사이트가 사람 확인(캡차)을 띄우면 데몬의 브라우저 화면을 넘겨받아 직접 누르고 적어 푼다(토큰에 `browser_handover` 범위가 있을 때).
- 루시아의 얼굴: 루시아가 스스로 정한 프로필 그림을 받아 머리말·대화 줄·빈 화면·알림에 쓴다. 정한 그림이 없으면 앱의 기본 모습이다([`docs/design.md`](docs/design.md) 10장).
- 휴대폰 기능: 사용자가 켠 것만 루시아에게 내준다. 지금 위치, 휴대폰에 온 알림 읽기, 답장 단추가 있는 메신저 알림에 답장(보내기 전에 묻는다). [`docs/phone-tools.md`](docs/phone-tools.md)
- 연결: WebSocket(`/v1/ws`)과 HTTP(`/v1/...`). 집 안(LAN·VPN)과 밖(공개 주소)을 둘 다 저장해 두고 닿는 쪽으로 붙는다.
- 끊김: `seq`·`ack`·`hello.resume`으로 놓친 프레임을 이어 받고, 앱이 꺼져 있으면 FCM이 깨운다(설정 필요, [`docs/push.md`](docs/push.md)).
- 보안: 토큰은 Android Keystore 키로 감싸 저장, 밖에서는 https만, 값 입력창과 넘겨받은 브라우저 화면은 화면 캡처에서 가린다.

## 만들기

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk (applicationId dev.mooner.lucent.debug)
./gradlew :app:testDebugUnitTest    # 프로토콜·저장소·연결·화면 묶음·장면·하늘·이모지·스티커·파일 미리보기·마크다운 표·그림 자리·명령(권한 모드, 주 모델)과 대화 정리·휴대폰 기능·프로필 그림·Firebase 값·입력 칸이 있는 카드·브라우저 화면 넘겨받기 시험(246개)
./gradlew :app:lintDebug
```

`local.properties`에 `sdk.dir`이 필요하다(커밋하지 않는다). 툴체인은 StarLight와 같다(AGP 8.13.2, Kotlin 2.4.20, ViewBinding, Markwon, Coil).

## 가짜 서버로 시험하기

운영 데몬에 토큰을 발급하지 않고 앱을 시험하는 사양 구현체가 `tools/fake-lucia/`에 있다.

```sh
python3 -m venv .venv && .venv/bin/pip install -r tools/fake-lucia/requirements.txt
.venv/bin/python -u tools/fake-lucia/server.py --port 18790   # --scopes security_mode,app_tools: 권한 모드 바꾸기, 휴대폰 기능 부르기. --no-profile: 프로필 그림이 없는 예전 데몬
# 앱에서: 집 안 주소 <호스트 IP>:18790, 토큰은 서버가 출력하는 값
```

말에 `card`, `code`, `file`, `image`, `preview`, `long`, `note`, `emoji`, `dropme`, `revoke`, `where`, `notices`, `avatar`(루시아가 프로필 그림을 바꾼다), `noavatar`(없앤다)를 넣으면 시나리오가 돈다(한글 낱말도 된다).
`/_dev/send?kind=text&body=..`, `/_dev/drop`, `/_dev/reset`, `/_dev/state`, `/_dev/slow?ms=..`(느린 내려받기), `/_dev/fail?n=..`(내려받기 실패), `/_dev/invoke?method=..&params=..`(루시아가 휴대폰 기능을 부른 것처럼), `/_dev/profile?set=1|2|big|photo|wide|bad|huge|gone|none`(프로필 그림 바꾸기)는 시험용 조작 입구다(사양에 없다).

## 구조

| 위치 | 하는 일 |
|---|---|
| `protocol/` | 프레임·데이터 형식(사양 5~9장), 파서·인코더. 모르는 필드·`type`은 무시 |
| `net/` | `GatewayClient`(WebSocket, 백오프·핑·길 두 개), `GatewayApi`(HTTP), `AddressPolicy`(평문 허용 범위) |
| `data/` | `ChatStore`(SQLite, `lastSeq`와 프레임을 한 트랜잭션에), `AppSettings`(Keystore), `ExpressionStore`(이모지·스티커 목록과 그림의 기기 사본), `ProfileStore`(루시아가 정한 프로필 그림의 기기 사본), `Expressions`(목록에서 찾기, 글 속 `:name:`과 기록의 스티커 줄 읽기), `FilePreviews`(앱 안에서 보일 파일 가리기, 넘길 때의 종류, 파일 줄의 아이콘 갈래, 글 파일 읽기와 줄 꺾기), `MarkdownBlocks`(마크다운에서 표 가르기), `Commands`(명령의 지금 값·선택지 이름, 모델을 바꾸기 전에 대화 정리를 권할지, 정리의 답에 따라 할 일) |
| `LucentHub` | 연결·저장·알림·푸시를 묶는 중심(StarLight의 `AgentHub` 자리). 프레임을 순서대로 처리하고 ack. 루시아가 부른 휴대폰 기능을 넘기고 답장 물음을 쥔다 |
| `tools/` | 루시아에게 내주는 휴대폰 기능(사양 7.2): 목록(`ToolSpecs`), 처리(`PhoneTools`), 위치(`LocationReader`), 알림 리스너(`NotificationWatcher`)와 기록(`NotificationLog`), 알림 읽기와 답장 단추(`NoticeDigest`, `NoticeReplies`) |
| `ui/chat` | 대화 화면. `ChatScene`이 지금 상황에서 화면이 무엇을 보일지 정하고, `ChatRows`가 저장된 줄을 화면의 줄로 바꾼다. `AddSheet`(+ 단추의 시트), `ExpressionPanel`(키보드 자리의 이모지·스티커 판), `ImageBox`(대화 속 그림의 자리) |
| `ui/sky` | 하늘 머리말(`SkyView`: 시각의 장면, 실제 월령의 달), 아바타 고리(`PresenceAvatarView`), 설정·연결 화면의 머리말(`PageSky`) |
| `ui/handover` | 브라우저 화면 넘겨받기(`HandoverActivity`, 그림을 보이고 누른 자리를 되돌리는 `FrameView`·`FrameMap`) |
| `ui/setup`, `ui/settings` | 처음 설정 화면(서버 주소, 토큰, 푸시의 Firebase 값), 설정 화면(View + ViewBinding) |
| `ui/common` | 마크다운, 이모지 제거(`Plain`), 커스텀 이모지 그리기(`EmojiSpans`), 바텀 시트(`SheetDialog`, `CommandSheet`), 파일 받기(`FileActions`)와 미리보기 시트(`FilePreview`: 그림·마크다운·글·PDF), PDF 쪽 그리기(`PdfPages`), 그림을 크게 보는 화면(`ImageViewerActivity`), 루시아의 얼굴을 눌러 프로필 그림 크게 보기(`FaceViewer`), 글 고르기 시트(`TextSheet`), 위아래 끝이 스러지는 목록(`EdgeFadeRecyclerView`), 잠깐 뜨는 안내(`Peeks`, StarLight와 같은 PeekAlert 라이브러리: `app/libs/PeekAlert-release.aar`), 답장 물음 시트(`ReplyAskSheet`), 값 입력창, 시스템 막대·여백 |

## 디자인

디자인 언어는 [`docs/design.md`](docs/design.md)("달빛")에 있다. 머리말의 하늘은 지금 시각과 루시아의 상태를 따르고, 입력창 위의 상황 자리와 대화의 줄은 상황과 내용에 따라 모양이 바뀐다.
개발 빌드에서는 하늘 장면을 인텐트로 정해 볼 수 있다(`--ei sky_minute 780`, `--ef moon 0.25`, `--ez night true`). 답장 물음 시트는 `--es dev_reply_ask "글"`로 띄워 본다. 알림 읽기와 답장의 왕복은 시험용 상대 앱 [`tools/reply-probe`](tools/reply-probe/README.md)로 본다.

## 아직 정해지지 않은 것

- **로고·심볼(앱 아이콘)**: 자리표시다. `res/drawable/ic_logo_placeholder.xml`만 바꾸면 된다. 기본 아바타와 큰 초상은 캐릭터 그림에서 잘라 쓴다(`res/drawable-nodpi/lucia_avatar.webp`, `lucia_portrait.webp`). 루시아가 프로필 그림을 정하면 그 그림이 대신 보인다.
- Firebase 설정(`google-services.json`)은 저장소에 없다. 받은 그대로 빌드한 앱에서는 처음 설정 화면(나중에는 설정 → 알림 → 푸시)에서 그 파일을 불러오거나 값을 직접 넣는다. 빌드에 넣어 두려면 빌드 종류의 폴더에 둔다([`docs/push.md`](docs/push.md)).
- 휴대폰 기능을 앱이 닫혀 있을 때도 부르게 하기: 지금은 Lucent가 열려 있을 때(와 그 뒤 30초)만 연결돼 있다([`docs/phone-tools.md`](docs/phone-tools.md)).
- 글 속의 움직이는 이모지(지금은 첫 장면만 보인다)와 글·스티커를 한 메시지로 보내기([`docs/emotes.md`](docs/emotes.md)).
