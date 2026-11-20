# Lucent

Lucia 앱 접속 API(`docs/app-api.md`, 프로토콜 판 2)와 이모지·스티커 확장(`docs/app-expressions.md`)의 안드로이드 클라이언트. 디자인·컴포넌트는 StarLight(`../StarLight`)를 따르되 이 프로젝트에 맞게 바꿨다. 디자인 언어는 `docs/design.md`("달빛")에 있다.

## 명령

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## 지켜야 할 것

- **사양과 코드가 다르면 사양(`docs/app-api.md`, `docs/app-expressions.md`, 그 뒤에 바뀐 것만 모은 `docs/app-api-changes.md`)이 맞다.** 프레임을 다룰 때 모르는 필드·`type`은 무시한다. 두 사양서는 데몬 저장소(`../lucia/docs/`)의 사본이다. 거기서 바뀌면 여기도 맞춘다.
- **토큰을 로그·분석·백업에 넣지 않는다.** 값 입력(`press.input`)도 저장하거나 남기지 않는다.
- **레이아웃에 dp·곡률 숫자를 직접 적지 않는다.** `values/dimens.xml`의 토큰과 `values/styles.xml`의 글자 스타일만 쓴다(`docs/design.md`).
- **포함은 두 겹까지**(페이지 → 면 → 내용), **이미지 모서리는 `radius_image` 하나**, 포인트는 파스텔 하늘색 하나. 금사(`gold*`)는 승인 대기·주의에만, 면이 아니라 선·아이콘·글자로만 쓴다.
- **상황에 따라 화면이 바뀌는 조건은 `ui/chat/ChatScene.kt` 한 곳에 둔다.** 화면 코드에 조건을 흩지 않고, 바꾸면 `ChatSceneTest`와 design.md 7장의 표를 같이 고친다.
- **보이기·숨기기를 목록의 스크롤 콜백 안에서 바로 바꾸지 않는다**(배치 도중이라 재배치 요청이 버려진다). `renderSoon()`처럼 다음 차례로 미룬다.
- 머리말 위의 글자·아이콘 색은 테마가 아니라 띠의 밝기를 따른다(`band_*` 색, `SkyView.isBandDark`).
- **앱이 그리는 글에 유니코드 이모지를 넣지 않는다.** 서버가 보낸 것은 `Plain.text`로 지운다. 커스텀 이모지·스티커는 대화의 내용이라 사양이 정한 곳(말풍선, 진행 안내, 카드의 `body`·`fields[].value`)에서만 그린다.
- **이모지·스티커의 목록과 그림은 데몬이 관리한다**(`docs/app-expressions.md`, 앱 쪽 구현은 `docs/emotes.md`). 앱에 그림을 넣지 않는다. 글 속 표기를 읽는 규칙은 `ExpressionText` 한 곳에 두고 데몬의 `emoji_tokens`와 같게 유지한다. 그릴 수 없으면 대체 표시(글 그대로, `[스티커: 이름]`)다. 그림은 토큰이 든 머리로 받아야 하므로 이미지 라이브러리에 주소를 넘기지 않는다.
- **파일을 앱 안에서 보일지는 `PreviewKind.of` 한 곳에서 정한다**(그림, 마크다운, 글, PDF). 보이는 것은 `FilePreview` 시트 하나이고, 그림과 PDF의 쪽은 거기서 `ImageViewerActivity`로 크게 본다. 파일의 글은 대화가 아니므로 커스텀 이모지를 그리지 않고, HTML·SVG를 그려 보이지 않는다(웹뷰를 두지 않는다. PDF는 플랫폼의 `PdfRenderer`로 쪽을 그림으로 그린다). 글 파일의 줄을 꺾을지는 `PreviewText.wraps`, 다른 앱에 넘길 때의 종류는 `PreviewKind.shareType`·`viewType`, 파일 줄의 아이콘은 `FileGlyph`가 정한다.
- **대화의 그림 받기는 줄(뷰)이 아니라 목록이 쥔다**(`ChatAdapter.pictures`). 줄은 자주 다시 그려지므로 뷰에 묶으면 받던 것을 버리고 다시 받는다. 그림의 자리는 `ImageBox`가 정한다.
- 허브가 알리는 안내(`hub.notices`: 서버 오류, 주 모델을 바꾼 결과 등)는 스낵바가 아니라 `Peeks`(PeekAlert, StarLight와 같은 라이브러리)로 화면 위쪽에 보인다. 모양은 `Peeks` 한 곳에서 정하고, 수준(알림·잘 됨·주의·오류)은 보내는 쪽이 정한다.
- 명령(권한 모드, 주 모델 등)의 값을 고르는 화면은 `CommandSheet` 하나다. "더하기" 시트와 설정이 같이 쓴다. 지금 값은 `commands`의 `current`로 알고(`Commands.current`), 명령의 답 글은 풀어 읽지 않는다(지금 값을 알려 주지 않는 예전 데몬의 `권한`만 예외). 선택지는 데몬이 준 `options`로 그린다.
- **주 모델 바꾸기와 그 앞의 대화 정리(`session.compact`)는 화면이 아니라 허브가 쥔다**(`LucentHub.switchModel`). 정리는 길면 몇 분이고 그사이 앱이 뒤로 가면 연결이 닫힌다. 다시 붙으면 한 번 더 불러 이어 간다(`CompactStep`).
- **루시아에게 내주는 휴대폰 기능(`hello.tools`, 사양 7.2)의 목록은 `tools/ToolSpecs.kt` 한 곳이다**(`docs/phone-tools.md`). 설정의 스위치로 켠 것만 내주고, 스위치를 바꾸면 다시 연결한다(`LucentHub.toolsChanged`). `invoke`의 답은 `GatewayClient`가 34초 안에 꼭 한 번 보낸다. 오류의 `message`는 루시아가 사용자에게 그대로 전하므로 사람이 읽을 한국어로 쓰고, 결과는 `ToolBudget`으로 8 KB 안에 맞춘다. 기능을 쓰는 동안 상태 줄에 보인다. 답장이 가는 곳의 이름은 `NoticeResults.replyTo` 한 곳에서 정한다(카카오톡 단톡방은 작은 글에 있다).
- **휴대폰에 온 알림의 글은 `NotificationLog`(48시간, 500줄)에만 둔다.** 로그·가짜 서버의 출력에 남기지 않는다. 알림 기록을 끄거나 연결을 해제하면 지운다. 답장 단추(`RemoteInput`)는 프로세스 안에만 둔다(`NoticeReplies`). 답장은 쓰기 기능이라 보내기 전에 묻는다(설정, 기본 켜짐, `ReplyAskSheet`).
- **로고(앱 아이콘)는 자리표시다.** 정해지면 `ic_logo_placeholder.xml`만 바꾼다. 앱의 기본 아바타는 캐릭터 그림의 얼굴(`drawable-nodpi/lucia_avatar.webp`), 기본 큰 초상은 `lucia_portrait.webp`이고, 화면은 `ic_avatar_placeholder.xml`·`ic_portrait_placeholder.xml`을 거쳐 쓴다.
- **루시아의 얼굴은 루시아가 정한 프로필 그림이 먼저다**(사양 8.7·8.8, 결정 0060). 받기와 보관은 `ProfileStore` 한 곳이고, 화면은 `hub.profile.face`만 본다(없으면 위의 기본 모습). 그림은 `id`(내용 해시)를 이름으로 한 파일 하나로 두고 같은 `id`는 다시 받지 않는다. 새 그림을 다 받기 전에는 보이던 것을 그대로 둔다. `welcome`에 `profile`이 없으면(예전 데몬) 서버에 묻지 않는다. 그림은 토큰이 든 머리로 받아야 하므로 이미지 라이브러리에 주소를 넘기지 않고, `id`는 `Avatar.usable`로 확인한 뒤에만 파일 이름과 주소에 넣는다. 앱이 그림을 정하거나 올리는 길은 없다. 얼굴이 보이는 자리를 새로 만들면 `ChatActivity.showFace`처럼 이 값을 따르게 하고, 누르면 `FaceViewer`로 크게 보이게 한다(그림 보는 화면은 앱의 캐시와 프로필 그림 폴더의 파일만 연다).
- 문서와 주석은 한국어, 식별자는 영어.
- 기기 시험은 `tools/fake-lucia`로 한다(운영 데몬에 시험용 토큰을 발급하지 않는다). `--scopes security_mode`를 주면 권한 모드를 바꿀 수 있는 토큰이 되고, `--legacy-commands`를 주면 주 모델·`current`·`session.compact`가 없는 예전 데몬처럼 굴고(`/_dev/compact`, `/_dev/model`로 대화 정리의 시간·결과·오류와 지금 모델을 조작한다), `--no-expressions`를 주면 이모지·스티커가 없는 데몬처럼 군다. 말에 `미리보기`(preview)를 넣으면 미리보기 시험용 파일 세 묶음이 온다. `/_dev/slow?ms=`는 내려받기를 느리게, `/_dev/fail?n=`은 다음 내려받기를 실패하게 한다. `--scopes app_tools`를 주면 루시아가 앱의 휴대폰 기능을 부를 수 있고, 말에 `where`(위치)·`notices`(받은 알림)를 넣거나 `/_dev/invoke?method=…&params=…`로 부른다. 프로필 그림은 말에 `avatar`(바꾸기)·`noavatar`(없애기)를 넣거나 `/_dev/profile?set=1|2|big|photo|wide|bad|huge|gone|none`(`&quiet=1`이면 다음 `welcome`에서 알게 된다)으로 바꾸고, `--no-profile`을 주면 프로필 기능이 없는 예전 데몬처럼 군다. 답장 물음 시트는 개발 빌드에서 `--es dev_reply_ask "글"`로 띄워 본다(아무것도 보내지 않는다). **시험 기기에서 Lucent에 알림 접근을 허용하지 않는다**(그 기기의 다른 앱 알림까지 기록된다. 하려면 먼저 사용자에게 묻는다). 허락받아 볼 때는 시험용 상대 앱 `tools/reply-probe`의 알림으로 읽기와 답장을 보고(실제 메신저 알림에는 답장을 보내지 않는다. 그 기기에는 알림 접근을 가진 다른 앱이 여럿 있다), 다른 앱 알림의 글은 읽지 않으며, 끝나면 스위치를 끄고 접근을 회수하고 상대 앱을 지운다. 공유·"다른 앱"·말의 "복사"는 기기에서 누르지 않는다(받는 앱이 하나뿐이면 고르는 화면 없이 그 앱이 바로 열리고, 복사는 기기의 클립보드를 바꾼다). 앱이 보일 수 없는 파일(압축 파일 등)은 다 받으면 스스로 다른 앱에 넘기므로, 기기에서는 느린 내려받기로 열고 다 받기 전에 닫는다. 화면은 `adb exec-out screencap -p`로 보되, 값 입력창은 보안 창이라 캡처되지 않는다(`uiautomator dump`로 본다).
- 하늘 장면은 개발 빌드에서 인텐트로 정해 볼 수 있다: `am start -n dev.mooner.lucent.debug/dev.mooner.lucent.ui.chat.ChatActivity --ei sky_minute 780`(자정부터 센 분), `--ef moon 0.25`(위상), `--ez night true|false`(이 앱 안에서만 테마를 강제한다).
- **시험 기기(redroid)는 다른 서비스도 쓴다.** Lucent 디버그 앱 안에서 끝나는 조작만 한다. 기기 전역 설정(`cmd uimode`, `wm size`, `settings put`)은 건드리지 않는다.
