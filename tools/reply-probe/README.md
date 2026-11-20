# reply-probe

Lucent의 알림 읽기·답장(`notifications.*`, [`docs/phone-tools.md`](../../docs/phone-tools.md))을 기기에서 시험할 때 쓰는 상대 앱이다.
메신저처럼 답장 단추(`RemoteInput`)가 달린 알림을 띄우고, 받은 답장을 그 알림에 덧붙인다. 실제 메신저에 답장을 보내지 않고
왕복을 볼 수 있다. Lucent 본체와 따로 짓는다(본체의 빌드에 들어가지 않는다).

```sh
./gradlew -p tools/reply-probe :app:assembleDebug
adb install -r tools/reply-probe/app/build/outputs/apk/debug/app-debug.apk

P=dev.mooner.lucent.probe/.ProbeReceiver
# 메신저 모양(MessagingStyle). room을 주면 단톡방이다.
adb shell "am broadcast -n $P -a dev.mooner.lucent.probe.POST --es style messaging --es room 'Dev Room' --es from 'Tester' --es text 'ping'"
# 카카오톡 모양: 제목이 보낸 사람, 작은 글이 방 이름.
adb shell "am broadcast -n $P -a dev.mooner.lucent.probe.POST --es style kakao --es room 'Dev Room' --es from 'Tester' --es text 'ping'"
# 답장 단추가 없는 보통 알림.
adb shell "am broadcast -n $P -a dev.mooner.lucent.probe.POST --es style plain --es from 'Shop' --es text 'notice'"
# 알림 걷기.
adb shell am broadcast -n $P -a dev.mooner.lucent.probe.CLEAR

adb logcat -s ReplyProbe     # "reply received chars=13 source=0": 답장이 도착했다(글은 남기지 않고 길이만. source 0은 직접 입력)
adb uninstall dev.mooner.lucent.probe   # 시험이 끝나면 지운다
```

- 빈칸이 든 값은 위처럼 `adb shell "…"` 안에서 작은따옴표로 싼다(아니면 첫 낱말만 간다).
- 홈 화면에 나오지 않는 앱이라 Lucent에는 앱 이름 대신 패키지 이름(`dev.mooner.lucent.probe`)으로 보인다. 그 이름으로 거른다:
  `/_dev/invoke?method=notifications.recent&params={"app":"probe"}`.
- 함께 쓰는 기기에서는 Lucent에 알림 접근을 허용하기 전에 사용자에게 묻고, 끝나면 스위치를 끄고 접근을 회수한다
  (`adb shell cmd notification disallow_listener dev.mooner.lucent.debug/dev.mooner.lucent.tools.NotificationWatcher`).
  그 기기의 다른 앱 알림도 기록되므로 결과를 볼 때 다른 앱의 글은 읽지 않는다(필드와 길이만 본다).
