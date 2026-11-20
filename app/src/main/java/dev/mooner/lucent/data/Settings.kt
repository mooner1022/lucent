package dev.mooner.lucent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.protocol.Welcome
import dev.mooner.lucent.tools.ToolSwitch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 토큰을 Android Keystore의 AES-GCM 키로 감싸 저장한다(사양 3.1, 13장).
 * 키는 기기를 떠나지 않고, 토큰은 로그·백업·분석 어디에도 넣지 않는다.
 */
internal class SecretStore {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    /** 풀 수 없으면(키가 바뀌었거나 복원된 기기) null. 사용자가 토큰을 다시 넣어야 한다. */
    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "lucent_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** 서버로 가는 두 길. 집 안(LAN·VPN)과 밖(공개 주소). */
enum class Route(val key: String) {
    HOME("home"),
    PUBLIC("public");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** 앱이 기기에 남기는 설정. 토큰만 Keystore로 감싼다. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("lucent", Context.MODE_PRIVATE)
    private val secrets = SecretStore()

    var homeBase: String?
        get() = prefs.getString(HOME, null)
        set(value) = prefs.edit { putString(HOME, value) }

    var publicBase: String?
        get() = prefs.getString(PUBLIC, null)
        set(value) = prefs.edit { putString(PUBLIC, value) }

    /** 토큰. 읽을 때마다 푼다. 호출하는 쪽은 변수에 오래 들고 있지 않는다. */
    var token: String?
        get() = prefs.getString(TOKEN, null)?.let(secrets::decrypt)
        set(value) {
            prefs.edit {
                if (value == null) remove(TOKEN) else putString(TOKEN, secrets.encrypt(value))
            }
        }

    /** 마지막으로 붙은 길. 다음 연결은 이 길부터 시도한다. */
    var lastRoute: Route?
        get() = Route.of(prefs.getString(LAST_ROUTE, null))
        set(value) = prefs.edit { putString(LAST_ROUTE, value?.key) }

    /** 알림에 글 앞부분을 보일지. 기본은 고정 문구다(사양 10.3). */
    var previewInNotifications: Boolean
        get() = prefs.getBoolean(PREVIEW, false)
        set(value) = prefs.edit { putBoolean(PREVIEW, value) }

    /** 처음 채울 때 Discord에서 나눈 대화까지 가져올지(`history`의 `scope`). */
    var importOwnerHistory: Boolean
        get() = prefs.getBoolean(OWNER_HISTORY, true)
        set(value) = prefs.edit { putBoolean(OWNER_HISTORY, value) }

    /** 알림 권한을 한 번 물었는가. 거절해도 다시 묻지 않는다(설정 화면에서 안내한다). */
    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFY, false)
        set(value) = prefs.edit { putBoolean(ASKED_NOTIFY, value) }

    /** 위치 권한을 한 번 물었는가. 두 번 거절하면 시스템이 더 묻지 않으므로 그때는 앱 정보로 보낸다. */
    var askedLocationPermission: Boolean
        get() = prefs.getBoolean(ASKED_LOCATION, false)
        set(value) = prefs.edit { putBoolean(ASKED_LOCATION, value) }

    /** 마지막으로 등록한 FCM 토큰. 같은 토큰을 다시 등록하지 않으려고 둔다. */
    var registeredPushToken: String?
        get() = prefs.getString(PUSH_TOKEN, null)
        set(value) = prefs.edit { putString(PUSH_TOKEN, value) }

    /** 마지막 `welcome`. 연결이 없을 때 설정 화면이 보여 주는 값이다. */
    var lastWelcome: Welcome?
        get() = prefs.getString(WELCOME, null)?.let { runCatching { LucentJson.decodeFromString(Welcome.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit { putString(WELCOME, value?.let { LucentJson.encodeToString(Welcome.serializer(), it) }) }

    // 루시아에게 내주는 휴대폰 기능(사양 7.2). 모두 꺼진 채로 시작한다. 켜도 휴대폰 권한과 관리 페이지의 허락이 따로 있어야 한다.

    /** 지금 위치(`location.get`). */
    var toolLocation: Boolean
        get() = prefs.getBoolean(TOOL_LOCATION, false)
        set(value) = prefs.edit { putBoolean(TOOL_LOCATION, value) }

    /** 알림 기록(`notifications.recent`, `notifications.get`). 켜야 알림을 기록한다. */
    var toolNotifications: Boolean
        get() = prefs.getBoolean(TOOL_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(TOOL_NOTIFICATIONS, value) }

    /** 알림에 답장(`notifications.reply`). 알림 기록이 켜져 있어야 쓰인다. */
    var toolReply: Boolean
        get() = prefs.getBoolean(TOOL_REPLY, false)
        set(value) = prefs.edit { putBoolean(TOOL_REPLY, value) }

    /** 답장을 보내기 전에 이 휴대폰에서 묻는다. 기본은 묻는다. */
    var replyConfirm: Boolean
        get() = prefs.getBoolean(REPLY_CONFIRM, true)
        set(value) = prefs.edit { putBoolean(REPLY_CONFIRM, value) }

    /** 루시아가 그 기능을 마지막으로 쓴 때(설정 화면이 보인다). 쓴 적이 없으면 0. */
    fun toolUsedAt(switch: ToolSwitch): Long = prefs.getLong(TOOL_USED + switch.name.lowercase(), 0L)

    fun markToolUsed(switch: ToolSwitch, at: Long) = prefs.edit { putLong(TOOL_USED + switch.name.lowercase(), at) }

    val isConfigured: Boolean get() = (homeBase != null || publicBase != null) && token != null

    fun base(route: Route): String? = when (route) {
        Route.HOME -> homeBase
        Route.PUBLIC -> publicBase
    }

    /** 시도할 길의 순서: 마지막으로 붙은 길이 먼저다. */
    fun routes(): List<Route> {
        val configured = Route.entries.filter { base(it) != null }
        val last = lastRoute
        return if (last != null && last in configured) listOf(last) + configured.filter { it != last } else configured
    }

    /** 연결 해제: 주소·토큰·캐시를 모두 지운다. */
    fun clearConnection() {
        prefs.edit { clear() }
    }

    private companion object {
        const val HOME = "home_base"
        const val PUBLIC = "public_base"
        const val TOKEN = "token_sealed"
        const val LAST_ROUTE = "last_route"
        const val PREVIEW = "notify_preview"
        const val OWNER_HISTORY = "import_owner_history"
        const val ASKED_NOTIFY = "asked_notify"
        const val PUSH_TOKEN = "push_token"
        const val WELCOME = "last_welcome"
        const val ASKED_LOCATION = "asked_location"
        const val TOOL_LOCATION = "tool_location"
        const val TOOL_NOTIFICATIONS = "tool_notifications"
        const val TOOL_REPLY = "tool_reply"
        const val REPLY_CONFIRM = "tool_reply_confirm"
        const val TOOL_USED = "tool_used_"
    }
}
