# 푸시(FCM) 설정

푸시는 앱이 꺼져 있을 때 서버가 앱을 깨우는 신호다(사양 10장). **푸시 본문을 대화 내용으로 쓰지 않는다**: 깨어나면 WebSocket으로 붙어 `hello.resume`으로 쌓인 프레임을 받는다.

## 앱 쪽

1. Firebase 콘솔에서 안드로이드 앱을 등록한다. 패키지 이름은 빌드에 따라 `dev.mooner.lucent`(release) 또는 `dev.mooner.lucent.debug`(debug)다.
2. 받은 `google-services.json`을 그 빌드 종류의 폴더에 둔다(커밋하지 않는다. `.gitignore`에 있다).
   - `app/src/release/google-services.json`: release용(`dev.mooner.lucent`).
   - `app/src/debug/google-services.json`: debug용(`dev.mooner.lucent.debug`를 Firebase에 따로 등록했을 때).
   - `app/google-services.json`: 두 패키지가 모두 들어 있는 파일 하나를 함께 쓸 때.
3. 파일이 있는 빌드 종류만 `google-services` 플러그인이 처리한다. 파일이 없는 종류는 푸시 없이 빌드된다. 저장소에는 파일이 없으므로, 받은 그대로 빌드하면 푸시 없이 돈다.
4. 앱을 설치해 연결하면 FCM 토큰을 `PUT /v1/push`로 등록한다. 설정 → 알림 → 푸시에서 상태를 본다.

## 서버 쪽

관리 페이지 → 연결 → "앱 알림과 푸시"에 **같은 Firebase 프로젝트**의 서비스 계정 JSON을 넣는다.

## 설정이 없으면

앱은 푸시 없이 돈다. 앱이 뒤에 있는 동안 30초는 연결을 쥐고 있고, 그동안 도착한 글은 앱이 직접 알림으로 띄운다. 30초 뒤 연결을 닫으면 다음에 앱을 열 때 이어 받는다.

## 알림 내용

기본은 고정 문구("새 메시지가 있어요")다. 설정 → 알림 → "알림에 글 앞부분 보이기"를 켜면 앱이 직접 띄우는 알림에만 글 앞 120자가 실린다.
