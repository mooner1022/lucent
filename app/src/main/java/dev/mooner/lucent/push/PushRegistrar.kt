package dev.mooner.lucent.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.protocol.Welcome
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * FCM 등록 토큰을 데몬에 알린다(사양 10.1, `PUT /v1/push`).
 *
 * Firebase 설정(`google-services.json`)이 없는 빌드에서는 [isSupported]가 false이고 아무것도 하지 않는다.
 * 설정 화면이 그 사실을 보인다(docs/push.md).
 */
class PushRegistrar(
    private val context: Context,
    private val settings: AppSettings,
    private val api: GatewayApi,
) {
    /** 이 빌드가 FCM을 쓸 수 있는가. */
    val isSupported: Boolean
        get() = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /**
     * 연결할 때마다 부른다. 서버에 이 앱의 토큰이 없거나(`push.registered`가 false) 토큰이 바뀌었으면 등록한다.
     * 앱은 시작할 때마다 토큰을 다시 등록해 두는 것이 안전하다(10.3): 서버가 무효 토큰을 지우기 때문이다.
     */
    suspend fun sync(welcome: Welcome) {
        if (!isSupported || !welcome.push.available) return
        val token = fetchToken() ?: return
        if (welcome.push.registered && token == settings.registeredPushToken) return
        register(token)
    }

    suspend fun register(token: String) {
        if (!isSupported) return
        runCatching { api.registerPush(token) }.onSuccess { settings.registeredPushToken = token }
    }

    /** 설정 화면의 "다시 등록". 지금의 FCM 토큰을 가져와 서버에 등록한다. 성공 여부를 돌려준다. */
    suspend fun registerCurrent(): Boolean {
        if (!isSupported) return false
        val token = fetchToken() ?: return false
        return runCatching { api.registerPush(token) }.onSuccess { settings.registeredPushToken = token }.isSuccess
    }

    /** 연결을 끊을 때 서버에서 이 앱의 푸시 토큰을 지운다. 실패해도(서버에 닿지 못해도) 로컬은 정리한다. */
    suspend fun unregister() {
        runCatching { api.unregisterPush() }
        settings.registeredPushToken = null
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching { FirebaseMessaging.getInstance().token }
            .onFailure { cont.resume(null) }
            .onSuccess { task ->
                task.addOnCompleteListener { if (cont.isActive) cont.resume(if (it.isSuccessful) it.result else null) }
            }
    }
}
