package dev.mooner.lucent.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.protocol.Welcome
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 푸시(FCM)를 쓸 준비를 하고, FCM 등록 토큰을 데몬에 알린다(사양 10.1, `PUT /v1/push`).
 *
 * Firebase 설정은 두 곳에서 온다(docs/push.md). 빌드에 `google-services.json`이 들어 있으면 그것을 쓰고(시스템이 앱을
 * 시작할 때 켠다), 없으면 설정 화면에서 넣은 값([AppSettings.firebase])을 쓴다. 둘 다 없으면 [isSupported]가 false이고
 * 아무것도 하지 않는다.
 *
 * Firebase는 프로세스에서 한 번만 켠다([start]). 켜진 뒤에 값을 바꾸면 켜 둔 것을 갈아 끼우지 않고, 앱을 다시 시작해
 * 새 값으로 켠다(설정 화면이 그렇게 한다). 그사이에는 [isSupported]가 false다.
 */
class PushRegistrar(
    private val context: Context,
    private val settings: AppSettings,
    private val api: GatewayApi,
) {
    /** 어디서 온 설정을 쓰는가. 설정 화면이 보인다. */
    enum class Source { ENTERED, BUILD, NONE }

    /** 빌드에 든 설정(`google-services.json`). 없으면 null. */
    private val buildOptions: FirebaseOptions? by lazy { runCatching { FirebaseOptions.fromResource(context) }.getOrNull() }

    /** 빌드에 든 설정의 프로젝트 ID. 설정 화면이 "이 빌드는 이 프로젝트를 쓴다"고 알릴 때 쓴다. */
    val buildProjectId: String? get() = buildOptions?.projectId

    val source: Source
        get() = when {
            buildOptions != null -> Source.BUILD
            settings.firebase != null -> Source.ENTERED
            else -> Source.NONE
        }

    /** 써야 할 설정. 빌드에 든 것이 먼저다(그때는 넣은 값을 받지 않는다). */
    private fun wanted(): FirebaseOptions? = buildOptions ?: settings.firebase?.let(::optionsOf)

    private fun live(): FirebaseApp? =
        runCatching { FirebaseApp.getApps(context).firstOrNull { it.isDefaultApp } }.getOrNull()

    /** 지금 푸시를 쓸 수 있는가: 설정이 있고, 이 프로세스가 그 설정으로 켜져 있다. */
    val isSupported: Boolean
        get() = wanted()?.let { it == live()?.options } ?: false

    /**
     * 써야 할 설정으로 Firebase를 켠다. 앱을 시작할 때와 설정을 저장한 뒤에 부른다.
     * 이미 다른 설정으로 켜져 있으면 손대지 않고 false를 돌려준다: 앱을 다시 시작해야 새 설정을 쓴다.
     */
    fun start(): Boolean {
        val wanted = wanted() ?: return true
        val live = live()
        if (live != null) return live.options == wanted
        return runCatching { FirebaseApp.initializeApp(context, wanted) }.isSuccess
    }

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

    private fun optionsOf(config: FirebaseConfig): FirebaseOptions = FirebaseOptions.Builder()
        .setProjectId(config.projectId)
        .setApplicationId(config.appId)
        .setApiKey(config.apiKey)
        .setGcmSenderId(config.senderId)
        .build()
}
