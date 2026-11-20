package dev.mooner.lucent

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.data.ChatStore
import dev.mooner.lucent.data.Commands
import dev.mooner.lucent.data.CompactStep
import dev.mooner.lucent.data.Delivery
import dev.mooner.lucent.data.ExpressionKind
import dev.mooner.lucent.data.ExpressionStore
import dev.mooner.lucent.data.Expressions
import dev.mooner.lucent.data.ProfileStore
import dev.mooner.lucent.data.Route
import dev.mooner.lucent.data.StickerRef
import dev.mooner.lucent.data.StoredItem
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.net.GatewayClient
import dev.mooner.lucent.net.GatewayConfig
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.net.RouteBase
import dev.mooner.lucent.notify.Notifier
import dev.mooner.lucent.protocol.ClientFrames
import dev.mooner.lucent.protocol.CommandInfo
import dev.mooner.lucent.protocol.CommandList
import dev.mooner.lucent.protocol.CommandText
import dev.mooner.lucent.protocol.CompactResult
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.InvokeReply
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.protocol.ServerFrame
import dev.mooner.lucent.protocol.Welcome
import dev.mooner.lucent.protocol.describeError
import dev.mooner.lucent.push.PushRegistrar
import dev.mooner.lucent.tools.AskAnswer
import dev.mooner.lucent.tools.NotificationWatcher
import dev.mooner.lucent.tools.PhoneTool
import dev.mooner.lucent.tools.PhoneTools
import dev.mooner.lucent.tools.ReplyAsk
import dev.mooner.lucent.ui.common.Plain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * 앱 전체가 함께 쓰는 중심(StarLight의 `AgentHub`와 같은 자리). 연결, 저장소, 알림, 푸시를 한곳에 둔다.
 *
 * 받은 프레임은 도착 순서대로 하나씩 처리한다(사양 12.2): 중복을 버리고, 저장하고, `lastSeq`를 올리고, `ack`한다.
 * 화면은 [store]의 목록과 [link]·[typing]·[pressing]을 보기만 하고, 보내는 일은 이 클래스의 함수로 한다.
 */
class LucentHub private constructor(private val app: Application) : GatewayConfig {

    val settings = AppSettings(app)
    val store = ChatStore(app)
    val notifier = Notifier(app, settings)
    private val http = OkHttpClient.Builder().build()
    val api = GatewayApi(settings, http, userAgent = "Lucent/${BuildConfig.VERSION_NAME}")
    val push = PushRegistrar(app, settings, api)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 루시아에게 내주는 휴대폰 기능(사양 7.2). 무엇을 내주는지는 `ToolSpecs`, 켜고 끄는 것은 설정이다. */
    val tools = PhoneTools(app, settings, isForeground = { isForeground }, ask = ::askReply)

    /** 마지막 `hello`에 실은 기능 이름들. 스위치를 바꾸면 이것과 견주어 다시 연결한다. */
    @Volatile private var sentTools: List<String> = emptyList()

    /** 마지막 `hello`에 실은 기능 이름들(설정 화면이 서버가 받아들였는지 견준다). */
    val sentToolNames: List<String> get() = sentTools

    val gateway = GatewayClient(
        http, scope, this,
        tools = { sentTools = tools.offeredNames(); tools.declarations() },
        onInvoke = ::onInvoke,
        userAgent = "Lucent/${BuildConfig.VERSION_NAME}",
    )

    /** 데몬이 관리하는 이모지·스티커의 목록과 그림(docs/app-expressions.md). */
    val expressions = ExpressionStore(app, api, scope)

    /** 루시아가 정한 프로필 그림(사양 8.7, 8.8). 화면은 `profile.face`를 본다. 없으면 앱의 기본 모습이다. */
    val profile = ProfileStore(app, scope, api::avatarFile)

    val link: StateFlow<LinkState> = gateway.state

    private val _welcome = MutableStateFlow(settings.lastWelcome)
    /** 가장 최근에 받은 `welcome`(연결이 없어도 마지막 값이 남는다). */
    val welcome: StateFlow<Welcome?> = _welcome.asStateFlow()

    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private val _activity = MutableStateFlow<String?>(null)
    /**
     * 루시아가 지금 하는 일(방금 온 `note`의 글). 다음 말·파일·카드가 오거나, 연결이 끊기거나, 잠시 지나면 지워진다.
     * 다시 받은(`replay`) 안내는 지난 일이므로 넣지 않는다. 화면의 상태 줄이 이 값을 보인다.
     */
    val activity: StateFlow<String?> = _activity.asStateFlow()

    private val _pressing = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** 버튼을 눌러 `card_closed`를 기다리는 카드(12.5). 그 카드의 버튼은 잠깐 잠근다. */
    val pressing: StateFlow<Map<String, Long>> = _pressing.asStateFlow()

    private val _commandList = MutableStateFlow<List<CommandInfo>>(emptyList())
    /** 마지막으로 읽은 명령 목록(6.3). 시트와 설정이 다시 묻기 전에 먼저 보인다. */
    val commandList: StateFlow<List<CommandInfo>> = _commandList.asStateFlow()

    private val _commandTexts = MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * 명령마다 마지막으로 받은 답의 글(6.4). 지금 값은 `commands`의 `current`로 알고, 이 글에서 읽는 것은 `current`를 주지 않는
     * 예전 데몬의 권한 모드뿐이다([dev.mooner.lucent.data.CommandReply]).
     */
    val commandTexts: StateFlow<Map<String, String>> = _commandTexts.asStateFlow()

    /** 화면이 잠깐 보일 안내 하나. [level]이 아이콘과 머무는 시간을 정한다([dev.mooner.lucent.ui.common.Peeks]). */
    data class Notice(val text: String, val level: Level) {
        enum class Level { INFO, SUCCESS, WARNING, ERROR }
    }

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    /** 화면이 잠깐 보일 안내(서버 오류 등). */
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private fun notify(text: String, level: Notice.Level) {
        if (text.isNotBlank()) _notices.tryEmit(Notice(text, level))
    }

    @Volatile private var isForeground = false
    @Volatile private var lastSeq = 0L
    private var ackedSeq = 0L
    private var ackJob: Job? = null
    private var typingJob: Job? = null
    private var activityJob: Job? = null
    private var backgroundJob: Job? = null
    private val localIds = AtomicLong()

    init {
        notifier.ensureChannels()
        notifier.emojiLabel = { name -> expressions.current.value.emoji(name)?.label }
        // 시스템은 알림의 큰 아이콘을 아바타쯤의 크기(36~48dp)로 줄여 쓴다. 그 크기로 읽어 둔 것을 넘긴다.
        notifier.face = { profile.face.value?.small }
        scope.launch { for (frame in gateway.frames) process(frame) }
        scope.launch {
            gateway.state.collect { state ->
                if (state !is LinkState.Online && state !is LinkState.Connecting) {
                    _typing.value = false
                    clearActivity()
                    // 연결이 끊긴 채 `accepted`를 못 받은 말은 데몬이 받았는지 알 수 없다(12.3).
                    store.markSendingUnconfirmed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ GatewayConfig

    override fun token(): String? = settings.token

    override fun routes(): List<RouteBase> = settings.routes().mapNotNull { r -> settings.base(r)?.let { RouteBase(r, it) } }

    /**
     * 처음 연결이면 `resume`을 주지 않는다. 서버에 남은 지난 프레임을 받는 대신 `history`로 화면을 채우므로
     * 같은 말이 두 번 보이지 않는다. 그 뒤로는 늘 마지막으로 처리한 번호를 준다(5.4).
     */
    override suspend fun resume(): Long? =
        if (store.meta(ChatStore.META_INITIAL_IMPORT) == "1") store.lastSeq() else null

    override fun onConnected(route: Route) {
        settings.lastRoute = route
    }

    // ------------------------------------------------------------------ 수명

    /** 앱 시작 때 한 번. 앞에 오고 뒤로 가는 것에 맞춰 연결과 `presence`를 다룬다. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onForeground()
            override fun onStop(owner: LifecycleOwner) = onBackground()
        })
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 망이 바뀌면 기다리지 않고 바로 다시 시도한다.
                    if (gateway.isRunning) gateway.wake()
                }
            })
        }
    }

    private fun onForeground() {
        isForeground = true
        backgroundJob?.cancel()
        notifier.clearAll()
        if (!settings.isConfigured) return
        gateway.sendPresence(true)
        gateway.start()
    }

    private fun onBackground() {
        isForeground = false
        gateway.sendPresence(false)
        armBackgroundDisconnect(BACKGROUND_GRACE_MS)
    }

    /**
     * 뒤로 가면 잠시 뒤 연결을 닫는다. 그 뒤의 글은 서버가 상자에 쌓고 푸시로 깨운다(10장). 앱이 오래 소켓을 쥐지
     * 않아 배터리를 아낀다.
     */
    private fun armBackgroundDisconnect(afterMs: Long) {
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(afterMs)
            if (!isForeground) gateway.stop()
        }
    }

    /** 푸시로 깨어났을 때. 연결해 쌓인 프레임을 받고, 잠시 뒤 다시 닫는다(10.3). */
    fun wakeForPush() {
        if (!settings.isConfigured) return
        gateway.sendPresence(isForeground)
        gateway.start()
        if (!isForeground) armBackgroundDisconnect(WAKE_WINDOW_MS)
    }

    /** 설정(주소·토큰)이 바뀐 뒤 새 값으로 다시 연결한다. */
    fun reconnect() {
        gateway.stop()
        if (settings.isConfigured) gateway.start()
    }

    /**
     * 설정 화면에서 Firebase 값을 바꾼 뒤. 값을 지워 푸시를 쓰지 않게 됐으면 서버의 등록도 지운다.
     * false면 이 프로세스가 예전 값으로 켜져 있다: 앱을 다시 시작해야 새 값을 쓴다.
     */
    fun pushConfigChanged(): Boolean {
        val started = push.start()
        if (push.source == PushRegistrar.Source.NONE && settings.registeredPushToken != null) scope.launch { push.unregister() }
        return started
    }

    /** 연결 해제: 서버의 푸시 토큰, 주소, 토큰, Firebase 값, 기록을 모두 지운다. */
    suspend fun disconnect() {
        runCatching { push.unregister() }
        gateway.stop()
        store.clear()
        settings.clearConnection()
        _welcome.value = null
        _commandList.value = emptyList()
        switchJob?.cancel()
        _modelSwitch.value = null
        _commandTexts.value = emptyMap()
        // 휴대폰 기능의 스위치는 설정과 함께 지워졌다. 알림 기록도 남기지 않는다.
        NotificationWatcher.stop()
        tools.clearNotices()
        lastSeq = 0
        ackedSeq = 0
        notifier.clearAll()
        expressions.clear()
        profile.clear()
    }

    // ------------------------------------------------------------------ 받기

    private suspend fun process(frame: ServerFrame) {
        try {
            when (frame) {
                is ServerFrame.WelcomeFrame -> onWelcome(frame.welcome)
                is ServerFrame.Resumed -> onResumed(frame)
                is ServerFrame.Numbered -> onNumbered(frame)
                is ServerFrame.Typing -> onTyping()
                is ServerFrame.Expressions -> scope.launch { expressions.sync(frame.version) }
                // 차례를 지켜야 하므로(바로 뒤에 또 바뀔 수 있다) 따로 띄우지 않는다. 그림 받기는 저장소가 뒤에서 한다.
                is ServerFrame.ProfileChanged -> profile.apply(frame.avatar)
                is ServerFrame.Accepted -> frame.id?.let { store.setDelivery(it, Delivery.SENT) }
                is ServerFrame.Error -> onServerError(frame)
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "프레임을 처리하지 못함: ${frame::class.simpleName}", e)
        }
    }

    private suspend fun onWelcome(w: Welcome) {
        _welcome.value = w
        settings.lastWelcome = w

        // 다른 토큰(다른 클라이언트)으로 바꿨다면 예전 대화는 남기지 않는다.
        val known = store.meta(ChatStore.META_CLIENT)
        if (known != null && known != w.client) {
            store.clear()
            // 다른 데몬일 수 있다. 이모지·스티커의 사본과 프로필 그림도 버리고 아래에서 다시 받는다.
            expressions.clear()
            profile.clear()
        }
        store.putMeta(ChatStore.META_CLIENT, w.client)

        val initialDone = store.meta(ChatStore.META_INITIAL_IMPORT) == "1"
        if (!initialDone) {
            // `resume`을 주지 않았으므로 지금까지의 번호는 처리한 것으로 두고 화면은 기록으로 채운다.
            store.setLastSeq(w.seq)
            lastSeq = w.seq
            ackedSeq = 0
            scheduleAck()
            scope.launch { importInitialHistory() }
        } else {
            lastSeq = store.lastSeq()
        }

        scope.launch { push.sync(w) }
        // 이모지·스티커를 다루는 데몬이면 목록의 판을 맞춘다. 필드가 없으면 그 기능이 없는 데몬이다.
        w.expressions?.let { summary -> scope.launch { expressions.sync(summary.version) } }
        // 루시아의 프로필 그림. 필드가 없으면 그 기능이 없는 데몬이라 기본 모습을 쓰고 서버에 묻지 않는다.
        profile.apply(w.profile?.avatar)
    }

    private suspend fun onResumed(r: ServerFrame.Resumed) {
        if (r.last < lastSeq) {
            // 서버가 번호를 처음부터 다시 매기기 시작했다(저장소 초기화 등). 새 프레임을 중복으로 버리지 않게 맞춘다.
            lastSeq = r.last
            store.setLastSeq(r.last)
            ackedSeq = 0
        }
        if (r.truncated) {
            if (r.to < r.last) {
                // 500개 상한에 걸렸다. 받은 데까지 처리하고 거기서부터 다시 연결해 나머지를 받는다(5.4).
                gateway.reconnectNow()
            } else {
                // 다시 받을 수 없는 구간이 있었다: 기록으로 메우고 번호는 `last`로 맞춘다.
                lastSeq = r.last
                store.setLastSeq(r.last)
                scheduleAck()
                scope.launch { fillGapFromHistory() }
            }
        }
    }

    private suspend fun onNumbered(frame: ServerFrame.Numbered) {
        if (frame.seq <= lastSeq) {
            // 다시 받기와 새 프레임이 겹쳤다. 이미 처리한 번호는 버리되 받았다는 알림은 다시 보낸다.
            scheduleAck()
            return
        }
        store.applyFrame(frame, System.currentTimeMillis())
        lastSeq = frame.seq

        when (frame) {
            is ServerFrame.Text, is ServerFrame.Files, is ServerFrame.CardOpen, is ServerFrame.Sticker -> {
                _typing.value = false
                clearActivity()
                // 목록이 늦게 받아져 모르는 스티커면 목록을 새로 받아 본다. 그래도 없으면 대체 표시로 남는다(5.1).
                if (frame is ServerFrame.Sticker && expressions.current.value.sticker(StickerRef(frame.id, frame.name)) == null)
                    scope.launch { expressions.sync(null, force = true) }
            }
            is ServerFrame.CardClosed -> _pressing.value = _pressing.value - frame.ref
            is ServerFrame.Note -> if (!frame.replay) onActivity(frame.body)
        }
        if (!isForeground) notifyInBackground(frame)
        scheduleAck()
    }

    private fun notifyInBackground(frame: ServerFrame.Numbered) {
        // 푸시가 이 앱을 깨우는 중이면 서버의 푸시 알림이 이미 뜬다. 앱이 같은 알림을 또 띄우지 않는다.
        val w = _welcome.value
        val coveredByPush = w != null && w.push.available && w.push.registered && push.isSupported
        if (!coveredByPush) notifier.notifyFrame(frame)
    }

    /** `typing`은 몇 초마다 다시 오므로 마지막으로 온 뒤 몇 초가 지나면 표시를 지운다(5.5). */
    private fun onTyping() {
        _typing.value = true
        typingJob?.cancel()
        typingJob = scope.launch {
            delay(TYPING_HOLD_MS)
            _typing.value = false
        }
    }

    /** 진행 안내는 다음 것이 오기 전까지만 "지금 하는 일"이다. 뒤따르는 것이 없으면 잠시 뒤 지운다. */
    private fun onActivity(body: String) {
        if (body.isBlank()) return
        _activity.value = body
        activityJob?.cancel()
        activityJob = scope.launch {
            delay(ACTIVITY_HOLD_MS)
            _activity.value = null
        }
    }

    private fun clearActivity() {
        activityJob?.cancel()
        _activity.value = null
    }

    private suspend fun onServerError(e: ServerFrame.Error) {
        when (e.code) {
            ErrorCodes.EMPTY, ErrorCodes.TOO_LONG, ErrorCodes.BAD_FILE, ErrorCodes.BAD_STICKER -> {
                store.markLatestSendingFailed()
                notify(describeError(e.code, e.message), Notice.Level.ERROR)
                // 목록에서 빠진 스티커를 보냈다. 이 앱의 목록이 낡았으니 새로 받는다.
                if (e.code == ErrorCodes.BAD_STICKER) scope.launch { expressions.sync(null, force = true) }
            }
            ErrorCodes.BAD_REF -> {
                _pressing.value = emptyMap()
                notify(describeError(e.code, e.message), Notice.Level.ERROR)
            }
            ErrorCodes.BAD_FRAME, ErrorCodes.CLOSED -> Log.w(TAG, "서버 오류: ${e.code}")
            // 토큰 문제는 연결 상태(Blocked)로 화면에 남는다. 같은 말을 잠깐 뜨는 안내로 또 하지 않는다.
            ErrorCodes.UNAUTHORIZED, ErrorCodes.UNSUPPORTED_ROLE -> Unit
            else -> notify(describeError(e.code, e.message), Notice.Level.ERROR)
        }
    }

    /** 받았다고 알린다. 1초쯤 모아 마지막 값 하나만 보낸다(12.2). */
    private fun scheduleAck() {
        if (ackJob?.isActive == true) return
        ackJob = scope.launch {
            delay(ACK_DELAY_MS)
            val seq = lastSeq
            if (seq > ackedSeq && gateway.sendAck(seq)) ackedSeq = seq
        }
    }

    // ------------------------------------------------------------------ 기록 가져오기

    private fun historyScope() = if (settings.importOwnerHistory) "owner" else "app"

    /** 처음 설치했을 때 화면을 기록으로 채운다(12.3). */
    private suspend fun importInitialHistory() {
        runCatching {
            val page = api.history(before = null, limit = HISTORY_PAGE, scope = historyScope())
            store.importHistory(page.items)
            store.putMeta(ChatStore.META_HISTORY_OLDEST, page.items.minOfOrNull { it.id }?.toString().orEmpty())
            store.putMeta(ChatStore.META_HISTORY_MORE, if (page.more) "1" else "0")
            store.putMeta(ChatStore.META_INITIAL_IMPORT, "1")
        }.onFailure { Log.w(TAG, "처음 기록을 가져오지 못함", it) }
    }

    /** 다시 받을 수 없는 구간이 있었을 때, 저장된 가장 최근 줄 뒤의 기록으로 메운다. */
    private suspend fun fillGapFromHistory() {
        runCatching {
            val newest = store.newestAt() ?: return
            var before: Long? = null
            repeat(GAP_PAGES) {
                val page = api.history(before, HISTORY_PAGE, historyScope())
                store.importHistory(page.items, after = newest - GAP_OVERLAP_MS)
                val oldest = page.items.minOfOrNull { it.id }
                val oldestAt = page.items.minOfOrNull { ChatStore.parseInstant(it.at) }
                if (!page.more || oldest == null || (oldestAt != null && oldestAt <= newest)) return
                before = oldest
            }
        }.onFailure { Log.w(TAG, "빠진 구간을 기록으로 메우지 못함", it) }
    }

    /** 사용자가 기록을 다시 불러오라고 했을 때. 가장 최근 쪽을 한 번 더 받아 빠진 것을 메운다. */
    suspend fun refreshHistory(): Result<Int> = runCatching {
        val page = api.history(before = null, limit = HISTORY_PAGE, scope = historyScope())
        val newest = store.newestAt()
        store.importHistory(page.items, after = newest?.minus(GAP_OVERLAP_MS))
    }

    /**
     * 위로 스크롤해 맨 위에 닿았을 때 더 오래된 줄을 보인다. 저장된 것이 있으면 그것부터 보이고, 없으면 기록을 더
     * 가져온다. 더 볼 것이 있었는지를 돌려준다.
     */
    suspend fun loadOlder(): Boolean {
        if (store.showMore()) return true
        if (store.meta(ChatStore.META_HISTORY_MORE) != "1") return false
        val oldest = store.meta(ChatStore.META_HISTORY_OLDEST)?.toLongOrNull() ?: return false
        return try {
            val page = api.history(before = oldest, limit = HISTORY_PAGE, scope = historyScope())
            store.importHistory(page.items)
            store.putMeta(ChatStore.META_HISTORY_OLDEST, page.items.minOfOrNull { it.id }?.toString() ?: oldest.toString())
            store.putMeta(ChatStore.META_HISTORY_MORE, if (page.more) "1" else "0")
            store.showMore()
            page.items.isNotEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ 보내기

    sealed interface SendResult {
        data object Sent : SendResult
        data class Rejected(val reason: String) : SendResult
    }

    /**
     * 말을 보낸다. 서버는 같은 말을 거르지 않으므로(12.3) 연결이 없을 때는 쌓아 두었다 보내지 않고 거절한다.
     * 글은 화면에 남아 있어 사용자가 다시 보낼 수 있다.
     */
    suspend fun sendMessage(text: String, files: List<FileRef>): SendResult {
        val body = text.trim()
        val caps = _welcome.value?.capabilities
        if (body.isEmpty() && files.isEmpty()) return SendResult.Rejected(app.getString(R.string.send_empty))
        val maxChars = caps?.maxMessageChars ?: 8000
        if (body.codePointCount(0, body.length) > maxChars)
            return SendResult.Rejected(app.getString(R.string.send_too_long, maxChars))
        if (files.size > (caps?.maxMessageFiles ?: 10))
            return SendResult.Rejected(app.getString(R.string.send_too_many_files, caps?.maxMessageFiles ?: 10))
        if (link.value !is LinkState.Online)
            return SendResult.Rejected(app.getString(R.string.send_offline))

        val localId = "m-${System.currentTimeMillis()}-${localIds.incrementAndGet()}"
        store.insertUser(localId, body, files, System.currentTimeMillis())
        deliver(localId, body, files, null)
        return SendResult.Sent
    }

    /**
     * 스티커 한 장을 바로 보낸다(`message.sticker`, app-expressions.md 5.2). 쓰던 글은 건드리지 않는다.
     * 데몬은 이 말을 `[스티커:이름 — 설명]` 한 줄로 루시아에게 넘긴다.
     */
    suspend fun sendSticker(sticker: Expression): SendResult {
        if (link.value !is LinkState.Online) return SendResult.Rejected(app.getString(R.string.send_offline))
        val localId = "m-${System.currentTimeMillis()}-${localIds.incrementAndGet()}"
        store.insertUser(localId, "", emptyList(), System.currentTimeMillis(), StickerRef(sticker.id, sticker.name))
        deliver(localId, "", emptyList(), sticker.id)
        expressions.markUsed(ExpressionKind.STICKER, sticker)
        return SendResult.Sent
    }

    /** 확인되지 않았거나 실패한 말을 다시 보낸다. 데몬이 이미 받았다면 두 번 답한다(12.3). */
    suspend fun resend(item: StoredItem): SendResult {
        val localId = item.localId ?: return SendResult.Rejected(app.getString(R.string.send_failed))
        if (link.value !is LinkState.Online) return SendResult.Rejected(app.getString(R.string.send_offline))
        // 스티커는 지금의 목록에 있는 ID로 다시 보낸다(그사이 목록이 바뀌었을 수 있다).
        val sticker = item.sticker?.let { expressions.current.value.sticker(it)?.id ?: it.id }
        if (item.body.isEmpty() && item.files.isEmpty() && sticker == null) return SendResult.Rejected(app.getString(R.string.send_failed))
        store.setDelivery(localId, Delivery.SENDING)
        deliver(localId, item.body, item.files, sticker)
        return SendResult.Sent
    }

    suspend fun discard(item: StoredItem) {
        item.localId?.let { store.deleteLocal(it) }
    }

    private suspend fun deliver(localId: String, body: String, files: List<FileRef>, sticker: String?) {
        val sent = gateway.send(ClientFrames.message(body, files.map { it.id }, localId, sticker))
        if (!sent) store.setDelivery(localId, Delivery.FAILED)
    }

    /** 카드 버튼 누르기. 결과는 바로 오지 않고 `card_closed`로 온다. */
    fun press(ref: String, action: String, input: String? = null): Boolean {
        if (link.value !is LinkState.Online || !gateway.send(ClientFrames.press(ref, action, input))) {
            notify(app.getString(R.string.send_offline), Notice.Level.WARNING)
            return false
        }
        val now = System.currentTimeMillis()
        _pressing.value = _pressing.value + (ref to now)
        // 잠깐 기다려도 결과가 없으면 다시 누를 수 있게 푼다(12.5).
        scope.launch {
            delay(PRESS_LOCK_MS)
            if (_pressing.value[ref] == now) _pressing.value = _pressing.value - ref
        }
        return true
    }

    // ------------------------------------------------------------------ 명령(6.3, 6.4)

    class CallFailed(val code: String, message: String) : Exception(message)

    suspend fun commands(): List<CommandInfo> =
        LucentJson.decodeFromJsonElement(CommandList.serializer(), call("commands", null)).commands.also { _commandList.value = it }

    /**
     * 명령을 본다([option]이 null) 또는 바꾼다. 바꾸려면 그 명령의 범위(권한 모드는 `security_mode`)가 필요하다.
     * 답은 사람이 읽는 글이다. 오류(`timeout` 포함)면 바뀌지 않은 것이다.
     */
    suspend fun command(name: String, option: String?): String {
        val params = buildJsonObject {
            put("name", name)
            if (option != null) put("option", option)
        }
        return LucentJson.decodeFromJsonElement(CommandText.serializer(), call("command", params)).text
            .also { _commandTexts.value = _commandTexts.value + (name to it) }
    }

    // ------------------------------------------------------------------ 휴대폰 기능(사양 7.2)

    /**
     * 루시아가 휴대폰 기능을 불렀다. 하는 동안 상태 줄에 무엇을 하는지 보인다(사용자가 모르게 위치나 알림을 읽지 않는다).
     * 답은 [GatewayClient]가 제때 보낸다.
     */
    private suspend fun onInvoke(frame: ServerFrame.Invoke): InvokeReply {
        val label = when (PhoneTool.of(frame.method)) {
            PhoneTool.LOCATION -> R.string.tool_activity_location
            PhoneTool.NOTIFICATIONS_RECENT, PhoneTool.NOTIFICATIONS_GET -> R.string.tool_activity_notifications
            PhoneTool.NOTIFICATIONS_REPLY -> R.string.tool_activity_reply
            null -> null
        }?.let(app::getString)
        if (label != null) {
            activityJob?.cancel()
            _activity.value = label
        }
        try {
            return tools.handle(frame.method, frame.params)
        } finally {
            if (label != null) {
                activityJob?.cancel()
                activityJob = scope.launch {
                    delay(TOOL_ACTIVITY_HOLD_MS)
                    if (_activity.value == label) _activity.value = null
                }
            }
        }
    }

    /** 스위치를 바꿨다. 내주는 기능이 달라졌으면 다시 연결한다(`hello.tools`는 연결마다 정한다). */
    fun toolsChanged() {
        if (tools.offeredNames() != sentTools && link.value is LinkState.Online) gateway.reconnectNow()
    }

    private val _replyAsk = MutableStateFlow<ReplyAsk?>(null)
    /** 답장을 보내기 전의 물음. 화면 위의 창이 이것을 보이고 [answerReply]로 답한다. */
    val replyAsk: StateFlow<ReplyAsk?> = _replyAsk.asStateFlow()
    @Volatile private var replyAnswer: CompletableDeferred<Boolean>? = null

    /** 루시아의 답장을 보낼지 묻는다. 앱이 화면에 없으면 묻지 않는다. 한 번에 하나만 묻는다. */
    private suspend fun askReply(ask: ReplyAsk): AskAnswer {
        if (!isForeground) return AskAnswer.AWAY
        val answer = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (replyAnswer != null) return AskAnswer.NO
            replyAnswer = answer
            _replyAsk.value = ask
        }
        try {
            val yes = withTimeoutOrNull(REPLY_ASK_MS) { answer.await() } ?: return AskAnswer.LATE
            return if (yes) AskAnswer.YES else AskAnswer.NO
        } finally {
            synchronized(this) {
                replyAnswer = null
                _replyAsk.value = null
            }
        }
    }

    /** 개발 빌드에서만: 답장 물음을 띄워 본다(ChatActivity의 `dev_reply_ask`). 답은 로그로만 남는다. */
    fun debugAskReply(ask: ReplyAsk) {
        if (!BuildConfig.DEBUG) return
        scope.launch { Log.d(TAG, "답장 물음의 답(개발): ${askReply(ask)}") }
    }

    /** 답장 물음에 답한다. 이미 답했거나 물음이 끝났으면 아무 일도 없다. */
    fun answerReply(ask: ReplyAsk, send: Boolean) {
        synchronized(this) {
            if (_replyAsk.value == ask) replyAnswer?.complete(send)
        }
    }

    // ------------------------------------------------------------------ 주 모델 바꾸기(app-api-changes.md 2026-11-20)

    /** 진행 중인 "주 모델 바꾸기". 고르는 시트가 이 값을 보고 그린다. 한 번에 하나만 돈다. */
    sealed interface ModelSwitch {
        val option: String

        /**
         * 바꾸기 전에 대화를 정리하는 중(`session.compact`의 답을 기다린다). [step]이 기다리는 까닭이다:
         * 평소([CompactStep.PROCEED]), 데몬이 바쁨([CompactStep.RETRY_LATER]), 연결을 기다림([CompactStep.WAIT_ONLINE]).
         */
        data class Compacting(override val option: String, val step: CompactStep) : ModelSwitch

        /** 모델을 바꾸는 중(`command`의 답을 기다린다). */
        data class Switching(override val option: String) : ModelSwitch

        /** 정리하지 못했다. 그래도 바꿀지 사용자가 정한다([confirmModelSwitch]). */
        data class Asking(override val option: String, val reason: String) : ModelSwitch

        /** 끝났다. [text]는 서버가 준 글, [changed]는 다시 읽은 지금 값이 고른 값과 같은지(예전 데몬이면 null). */
        data class Done(override val option: String, val text: String, val changed: Boolean?) : ModelSwitch
    }

    private val _modelSwitch = MutableStateFlow<ModelSwitch?>(null)
    val modelSwitch: StateFlow<ModelSwitch?> = _modelSwitch.asStateFlow()
    private var switchJob: Job? = null

    /** 고르는 시트가 떠서 진행과 결과를 직접 보이고 있다. 아니면 결과를 잠깐 뜨는 안내([notices])로 알린다. */
    @Volatile var modelSwitchWatched = false

    /** 이 데몬이 대화 정리를 기다려 주는가(`welcome.methods`). 아니면 "먼저 정리" 선택지를 보이지 않는다. */
    val canCompact: Boolean get() = _welcome.value?.canCall(METHOD_COMPACT) == true

    /**
     * 주 모델을 [option]으로 바꾼다. [compactFirst]면 먼저 대화를 정리하고 끝날 때까지 기다린다(보통 20~75초, 길면 몇 분).
     *
     * 이 일은 화면이 아니라 허브가 쥔다. 기다리는 동안 앱이 뒤로 가면 연결이 닫히는데, 정리는 데몬에서 계속되므로 다시
     * 연결됐을 때 한 번 더 불러 이어 간다(끝났으면 `unchanged`, 아직이면 `busy`가 온다. 정리가 두 번 돌지는 않는다).
     */
    fun switchModel(option: String, compactFirst: Boolean) {
        if (switchJob?.isActive == true) return
        switchJob = scope.launch {
            var compacted: String? = null
            if (compactFirst) {
                val giveUpAt = System.currentTimeMillis() + COMPACT_GIVE_UP_MS
                var step = CompactStep.PROCEED
                while (true) {
                    _modelSwitch.value = ModelSwitch.Compacting(option, step)
                    var failure: CallFailed? = null
                    try {
                        compacted = compact().text
                    } catch (e: CallFailed) {
                        failure = e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 읽을 수 없는 답 등. 정리가 됐는지 알 수 없으므로 사용자에게 묻는다.
                        Log.w(TAG, "대화 정리의 답을 다루지 못함", e)
                        failure = CallFailed(ErrorCodes.FAILED, describeError(ErrorCodes.FAILED))
                    }
                    step = CompactStep.of(failure?.code)
                    if (step == CompactStep.PROCEED) break
                    if (step == CompactStep.ASK || System.currentTimeMillis() > giveUpAt) {
                        _modelSwitch.value = ModelSwitch.Asking(option, failure?.message ?: describeError(ErrorCodes.TIMEOUT))
                        // 시트가 닫혀 있으면 물음이 기다리고 있다고 알린다(정리는 몇 분 뒤에 실패할 수도 있다).
                        if (!modelSwitchWatched) notify(app.getString(R.string.model_ask_notice), Notice.Level.WARNING)
                        return@launch
                    }
                    _modelSwitch.value = ModelSwitch.Compacting(option, step)
                    delay(COMPACT_RETRY_MS)
                    // 연결이 없으면 돌아올 때까지 기다린다(앱이 앞으로 오면 다시 붙는다).
                    link.first { it is LinkState.Online }
                }
            }
            applyModel(option, compacted)
        }
    }

    /** 정리하지 못한 뒤의 답: [anyway]면 정리 없이 바꾸고, 아니면 그만둔다. */
    fun confirmModelSwitch(anyway: Boolean) {
        val asking = _modelSwitch.value as? ModelSwitch.Asking ?: return
        if (!anyway) {
            _modelSwitch.value = null
            return
        }
        if (switchJob?.isActive == true) return
        switchJob = scope.launch { applyModel(asking.option, null) }
    }

    /**
     * 기다리기를 그만둔다. 모델은 바꾸지 않는다. 이미 시작한 정리는 데몬에서 끝까지 간다(멈출 길이 없다).
     * 모델을 바꾸는 요청을 이미 보냈으면 그만둘 수 없다.
     */
    fun cancelModelSwitch() {
        if (_modelSwitch.value is ModelSwitch.Switching) return
        switchJob?.cancel()
        _modelSwitch.value = null
    }

    /** 끝난 결과를 화면이 보였다. */
    fun clearModelSwitch() {
        if (_modelSwitch.value is ModelSwitch.Done) _modelSwitch.value = null
    }

    private suspend fun applyModel(option: String, compacted: String?) {
        _modelSwitch.value = ModelSwitch.Switching(option)
        val reply = try {
            command(Commands.MODEL, option)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = (e as? CallFailed)?.message ?: describeError(ErrorCodes.FAILED)
            finishModelSwitch(option, listOfNotNull(compacted, why).joinToString("\n"), why, changed = false)
            return
        }
        // 모르는 값도 `ok`로 답이 온다. 정말 바뀌었는지는 목록을 다시 읽어 본다.
        val fresh = try {
            commands().firstOrNull { it.name == Commands.MODEL }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        finishModelSwitch(option, listOfNotNull(compacted, reply).joinToString("\n"), reply, Commands.applied(fresh, option))
    }

    /** [text]는 시트에 보일 글 전체(정리의 결과와 바꾼 결과), [headline]은 그 가운데 바꾼 결과다. */
    private fun finishModelSwitch(option: String, text: String, headline: String, changed: Boolean?) {
        _modelSwitch.value = ModelSwitch.Done(option, text, changed)
        // 시트가 닫혀 있으면(그사이 다른 화면으로 갔다) 바꾼 결과의 첫 줄을 잠깐 뜨는 안내로 알린다.
        if (!modelSwitchWatched) {
            val line = Plain.text(headline).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(NOTICE_CHARS)
            // 바뀌었으면(예전 데몬이라 알 수 없으면 답을 믿는다) 잘 됐다는 표시, 아니면 주의.
            notify(line, if (changed == false) Notice.Level.WARNING else Notice.Level.SUCCESS)
            _modelSwitch.value = null
        }
    }

    /** 대화 정리를 청하고 끝날 때까지 기다린다(사양 6.6). 데몬은 길게는 630초 뒤에 `timeout`으로 답한다. */
    private suspend fun compact(): CompactResult {
        val params = buildJsonObject { put("min_tokens", Commands.COMPACT_MIN_TOKENS) }
        return LucentJson.decodeFromJsonElement(CompactResult.serializer(), call(METHOD_COMPACT, params, COMPACT_TIMEOUT_MS))
    }

    private suspend fun call(method: String, params: JsonObject?, timeoutMs: Long = CALL_TIMEOUT_MS): JsonElement {
        val result = try {
            gateway.call(method, params, timeoutMs)
        } catch (e: GatewayClient.ConnectionLost) {
            throw CallFailed(ErrorCodes.CLOSED, app.getString(R.string.send_offline))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw CallFailed(ErrorCodes.TIMEOUT, describeError(ErrorCodes.TIMEOUT))
        }
        if (!result.ok) {
            val code = result.error?.code ?: ErrorCodes.FAILED
            throw CallFailed(code, describeError(code, result.error?.message.orEmpty()))
        }
        return result.data ?: JsonObject(emptyMap())
    }

    companion object {
        private const val TAG = "LucentHub"
        private const val ACK_DELAY_MS = 1_000L
        private const val TYPING_HOLD_MS = 6_000L
        private const val ACTIVITY_HOLD_MS = 20_000L
        private const val PRESS_LOCK_MS = 8_000L
        private const val BACKGROUND_GRACE_MS = 30_000L
        private const val WAKE_WINDOW_MS = 20_000L
        private const val HISTORY_PAGE = 100
        private const val GAP_PAGES = 3
        private const val GAP_OVERLAP_MS = 2 * 60_000L
        private const val CALL_TIMEOUT_MS = 15_000L
        private const val METHOD_COMPACT = "session.compact"
        /** 데몬은 길게는 630초(운영 설정)를 기다린 뒤 `timeout`으로 답한다. 앱은 그보다 조금 더 기다린다. */
        private const val COMPACT_TIMEOUT_MS = 660_000L
        private const val COMPACT_RETRY_MS = 5_000L
        /** `busy`와 끊긴 연결을 이만큼 겪고도 끝나지 않으면 그만 기다리고 사용자에게 묻는다. */
        private const val COMPACT_GIVE_UP_MS = 15 * 60_000L
        private const val NOTICE_CHARS = 120
        /** 휴대폰 기능을 쓴 뒤 상태 줄에 남겨 두는 시간. 루시아의 다음 말이 오면 먼저 지워진다. */
        private const val TOOL_ACTIVITY_HOLD_MS = 4_000L
        /** 답장 물음을 기다리는 시간. 데몬이 기다리는 40초 안에 답이 가야 한다(GatewayClient.INVOKE_DEADLINE_MS). */
        private const val REPLY_ASK_MS = 28_000L

        @Volatile private var instance: LucentHub? = null

        fun get(context: Context): LucentHub =
            instance ?: synchronized(this) {
                instance ?: LucentHub(context.applicationContext as Application).also { instance = it }
            }
    }
}
