package dev.mooner.lucent.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.mooner.lucent.LucentHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 서버의 푸시(사양 10.3)를 받는다.
 *
 * 푸시는 앱을 깨우는 신호일 뿐이다. `data.type == "wake"`이면 WebSocket으로 연결해 `hello.resume`으로 쌓인 프레임을 받는다.
 * **푸시 본문을 대화 내용으로 쓰지 않는다**: 미리보기가 꺼져 있으면 고정 문구이고, 대화는 연결로 받은 프레임이 기준이다.
 *
 * `data.type == "clear"`(10.4)는 다른 기기에서 본 알림을 치우라는 내용 없는 푸시다. 연결하지 않고 알림만 치운다
 * ([LucentHub.onClearPush]).
 */
class LucentMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        val hub = LucentHub.get(this)
        if (!hub.settings.isConfigured) return
        // 토큰이 바뀌면 서버의 것은 쓸 수 없다. WebSocket 없이도 `PUT /v1/push`로 다시 등록한다(8.6).
        scope.launch { hub.push.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val hub = LucentHub.get(this)
        when (message.data["type"]) {
            "wake" -> hub.wakeForPush()
            "clear" -> {
                val seq = message.data["seq"]?.toLongOrNull() ?: return
                // 이 함수가 돌아오면 서비스가 멈출 수 있다. 짧은 일(번호 하나 묻기)이라 여기서 끝낸다.
                runBlocking { hub.onClearPush(seq, message.data["client"]) }
            }
        }
    }
}
