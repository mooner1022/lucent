package dev.mooner.lucent.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.mooner.lucent.LucentHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 서버의 푸시(사양 10.3)를 받는다.
 *
 * 푸시는 앱을 깨우는 신호일 뿐이다. `data.type == "wake"`이면 WebSocket으로 연결해 `hello.resume`으로 쌓인 프레임을 받는다.
 * **푸시 본문을 대화 내용으로 쓰지 않는다**: 미리보기가 꺼져 있으면 고정 문구이고, 대화는 연결로 받은 프레임이 기준이다.
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
        if (message.data["type"] == "wake") LucentHub.get(this).wakeForPush()
    }
}
