package dev.mooner.lucent.net

import dev.mooner.lucent.data.Route
import dev.mooner.lucent.protocol.ClientFrames
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.FrameParseException
import dev.mooner.lucent.protocol.InvokeReply
import dev.mooner.lucent.protocol.ServerFrame
import dev.mooner.lucent.protocol.parseServerFrame
import dev.mooner.lucent.protocol.Welcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** 앱과 서버 사이의 연결 상태. 화면 머리말과 설정이 이 값을 그대로 보인다. */
sealed interface LinkState {
    /** 연결하지 않는 중(앱이 뒤에 있거나 아직 시작 전). */
    data object Idle : LinkState
    data class Connecting(val route: Route?) : LinkState
    data class Online(val welcome: Welcome, val route: Route) : LinkState

    /** 끊겼고 [retryAt]에 다시 시도한다. */
    data class Waiting(val reason: String, val retryAt: Long) : LinkState

    /** 다시 시도해도 소용없다. 사용자가 토큰을 다시 넣어야 한다(`unauthorized`, `unsupported_role`) 또는 주소가 없다. */
    data class Blocked(val code: String) : LinkState

    /** 인증 실패가 쌓여 그 주소가 막혔다. [until]까지 기다린다(사양 3.3). */
    data class Throttled(val until: Long) : LinkState
}

class RouteBase(val route: Route, val base: String)

/** 연결에 필요한 값을 그때그때 건네는 쪽(설정). 토큰은 매번 새로 읽는다. */
interface GatewayConfig {
    fun token(): String?
    fun routes(): List<RouteBase>

    /** `hello.resume`: 앱이 마지막으로 처리한 번호. null이면 다시 받지 않는다(처음 연결, 사양 5.2). */
    suspend fun resume(): Long?
    fun onConnected(route: Route) {}
}

/**
 * Lucia 데몬의 WebSocket(`/v1/ws`) 연결(사양 5장).
 *
 * - 첫 프레임은 `hello`다. 같은 토큰으로 연결하면 서버가 예전 연결을 닫는다.
 * - 끊기면 지수 백오프(1초에서 두 배씩 30초까지, 흔들림 포함)로 다시 붙는다. 길이 둘이면 마지막으로 붙은 길부터 해 본다.
 * - `unauthorized`·`unsupported_role`이면 다시 붙지 않는다. `too_many_attempts`이면 10분 기다린다.
 * - 30초마다 `ping`을 보내고 10초 안에 아무것도 오지 않으면 끊고 다시 붙는다(5.7).
 * - 받은 프레임은 [frames]에 도착 순서대로 쌓인다. 소비하는 쪽이 하나씩 처리한 뒤 ack한다.
 * - 데몬의 부탁(`invoke`)은 프레임 흐름에 싣지 않고 [onInvoke]에 바로 넘긴다. 답은 [invokeDeadlineMs] 안에 꼭 한 번
 *   보낸다(데몬은 40초를 기다리고, 늦은 답에는 `bad_ref` 오류를 보낸다). 연결이 끊기면 하던 부탁은 그만둔다(데몬 쪽에서
 *   이미 실패로 끝났다).
 */
class GatewayClient(
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val config: GatewayConfig,
    private val features: () -> List<String> = { emptyList() },
    /** `hello.tools`: 루시아에게 내주는 기능의 선언(7.2). 연결할 때마다 새로 읽는다. */
    private val tools: () -> List<JsonObject> = { emptyList() },
    /** 데몬의 부탁을 처리한다. 없으면 모든 부탁을 `unavailable`로 거절한다. */
    private val onInvoke: (suspend (ServerFrame.Invoke) -> InvokeReply)? = null,
    private val userAgent: String = "Lucent",
    private val invokeDeadlineMs: Long = INVOKE_DEADLINE_MS,
) {
    private val client = http.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state

    /** 번호 없는 프레임(`typing`, `accepted` 등)도 함께 도착 순서대로 온다. */
    val frames = Channel<ServerFrame>(Channel.UNLIMITED)

    private var loop: Job? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    private val callCounter = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerFrame.Result>>()

    @Volatile private var foreground = true

    val isRunning get() = loop?.isActive == true

    /** 연결을 시작한다. 이미 돌고 있으면 기다리는 중일 때만 바로 다시 시도한다. */
    fun start() {
        if (loop?.isActive == true) {
            wake()
            return
        }
        loop = scope.launch { run() }
    }

    /** 연결을 닫고 다시 붙지 않는다. */
    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, null)
        socket = null
        failPending("연결을 닫았어요")
        _state.value = LinkState.Idle
    }

    /** 열린 연결을 끊고 바로 다시 붙는다. 같은 `resume` 값으로 이어 받을 때 쓴다. */
    fun reconnectNow() {
        wake()
        socket?.cancel()
    }

    /** 기다리는 중이면 백오프를 건너뛰고 지금 다시 시도한다. */
    fun wake() {
        wakeSignal.trySend(Unit)
    }

    // ------------------------------------------------------------------ 보내기

    fun send(frame: String): Boolean = socket?.send(frame) == true

    fun sendAck(seq: Long) = send(ClientFrames.ack(seq))

    /** 앞에 있는지 알린다. 연결이 새로 열리면 서버는 늘 `foreground`로 시작하므로 다시 붙을 때 뒤에 있다면 다시 보낸다. */
    fun sendPresence(foreground: Boolean) {
        this.foreground = foreground
        send(ClientFrames.presence(foreground))
    }

    /** `call`을 보내고 `result`를 기다린다. 연결이 끊기면 [ConnectionLost]. */
    suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = 15_000): ServerFrame.Result {
        val id = "c${callCounter.incrementAndGet()}"
        val waiting = CompletableDeferred<ServerFrame.Result>()
        pending[id] = waiting
        try {
            if (!send(ClientFrames.call(id, method, params)))
                throw ConnectionLost("연결되어 있지 않아요")
            return withTimeout(timeoutMs) { waiting.await() }
        } finally {
            pending.remove(id)
        }
    }

    class ConnectionLost(message: String) : Exception(message)

    private fun failPending(reason: String) {
        val it = pending.values.iterator()
        while (it.hasNext()) it.next().completeExceptionally(ConnectionLost(reason))
        pending.clear()
    }

    // ------------------------------------------------------------------ 연결 반복

    private sealed interface End {
        val wasOnline: Boolean
        class Dropped(val reason: String, override val wasOnline: Boolean, val onlineMs: Long = 0) : End
        class Fatal(val code: String) : End { override val wasOnline = false }
        object Throttled : End { override val wasOnline = false }
    }

    private suspend fun run() {
        var backoff = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val token = config.token()
            val routes = config.routes()
            if (token == null || routes.isEmpty()) {
                _state.value = LinkState.Blocked(if (token == null) ErrorCodes.UNAUTHORIZED else "no_address")
                return
            }
            var end: End = End.Dropped("연결하지 못했어요", false)
            for (r in routes) {
                _state.value = LinkState.Connecting(r.route)
                end = try {
                    session(r, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    End.Dropped(e.message ?: "연결 오류", false)
                }
                if (end is End.Fatal || end is End.Throttled || end.wasOnline) break
            }
            when (val e = end) {
                is End.Fatal -> {
                    _state.value = LinkState.Blocked(e.code)
                    return
                }
                End.Throttled -> {
                    val until = System.currentTimeMillis() + THROTTLE_WAIT_MS
                    _state.value = LinkState.Throttled(until)
                    sleepOrWake(THROTTLE_WAIT_MS)
                    backoff = INITIAL_BACKOFF_MS
                }
                is End.Dropped -> {
                    if (e.wasOnline && e.onlineMs >= STABLE_MS) backoff = INITIAL_BACKOFF_MS
                    val wait = jitter(backoff)
                    _state.value = LinkState.Waiting(e.reason, System.currentTimeMillis() + wait)
                    sleepOrWake(wait)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun sleepOrWake(ms: Long) {
        // 기다리는 동안 들어온 깨우기는 이번 한 번만 건너뛰기로 쓴다.
        withTimeoutOrNull(ms) { wakeSignal.receive() }
    }

    private fun jitter(ms: Long): Long = (ms * (0.75 + Random.nextDouble() * 0.5)).toLong()

    // ------------------------------------------------------------------ 연결 하나

    private suspend fun session(target: RouteBase, token: String): End {
        val resume = config.resume()
        val done = CompletableDeferred<End>()
        val welcomedFlag = AtomicBoolean(false)
        val onlineSinceAt = AtomicLong(0L)
        val lastInboundAt = AtomicLong(System.currentTimeMillis())
        fun welcomed() = welcomedFlag.get()
        fun onlineSince() = onlineSinceAt.get()

        val request = Request.Builder()
            .url("${target.base}/v1/ws")
            .header("User-Agent", userAgent)
            .build()

        fun finish(end: End) { done.complete(end) }

        // 이 연결에서 받은 부탁들. 연결이 끝나면 함께 그만둔다.
        val invokes = SupervisorJob(scope.coroutineContext[Job])

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ClientFrames.hello(token, resume, features(), tools()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundAt.set(System.currentTimeMillis())
                val frame = try {
                    parseServerFrame(text)
                } catch (_: FrameParseException) {
                    return // 읽을 수 없는 프레임은 버린다. 연결은 그대로 둔다.
                }
                when (frame) {
                    is ServerFrame.WelcomeFrame -> {
                        welcomedFlag.set(true)
                        onlineSinceAt.set(System.currentTimeMillis())
                        config.onConnected(target.route)
                        // 새 연결은 늘 foreground로 시작한다(5.6). 뒤에 있는 채로 붙었다면 알린다.
                        if (!foreground) webSocket.send(ClientFrames.presence(false))
                        _state.value = LinkState.Online(frame.welcome, target.route)
                        frames.trySend(frame)
                    }
                    is ServerFrame.Error -> when {
                        ErrorCodes.endsSession(frame.code) -> {
                            frames.trySend(frame)
                            finish(End.Fatal(frame.code))
                        }
                        frame.code == ErrorCodes.TOO_MANY_ATTEMPTS -> finish(End.Throttled)
                        else -> frames.trySend(frame)
                    }
                    is ServerFrame.Result -> {
                        // 앱의 call에 대한 답은 기다리는 쪽에 건네고, 프레임 흐름에는 싣지 않는다.
                        pending.remove(frame.id)?.complete(frame)
                    }
                    is ServerFrame.Pong -> Unit
                    is ServerFrame.Invoke -> scope.launch(invokes) { answer(webSocket, frame) }
                    is ServerFrame.Unknown -> Unit
                    else -> frames.trySend(frame)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(End.Dropped(reason.ifBlank { "서버가 연결을 닫았어요" }, welcomed(), elapsed(onlineSince())))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(End.Dropped(reason.ifBlank { "연결이 닫혔어요" }, welcomed(), elapsed(onlineSince())))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                // 출처가 받아지지 않거나(403, 400) 인증이 막은 업그레이드는 본문 없이 상태만 온다(2.2).
                val reason = when (status) {
                    403 -> "이 주소에서는 접속이 허용되지 않아요"
                    400 -> "프록시 설정이 올바르지 않아요"
                    404 -> "접속 API를 찾을 수 없어요. 주소를 확인해 주세요"
                    else -> describeFailure(t)
                }
                finish(End.Dropped(reason, welcomed(), elapsed(onlineSince())))
            }
        }

        val ws = client.newWebSocket(request, listener)
        socket = ws

        val watchers = scope.launch {
            launch {
                delay(HELLO_WAIT_MS)
                if (!welcomed()) finish(End.Dropped("서버가 응답하지 않아요", false))
            }
            launch {
                while (isActive) {
                    delay(PING_EVERY_MS)
                    if (!welcomed()) continue
                    val sentAt = System.currentTimeMillis()
                    if (!ws.send(ClientFrames.ping())) continue
                    delay(PONG_WAIT_MS)
                    if (lastInboundAt.get() < sentAt) {
                        finish(End.Dropped("서버에서 응답이 없어요", true, elapsed(onlineSince())))
                    }
                }
            }
        }

        try {
            return done.await()
        } finally {
            invokes.cancel()
            watchers.cancel()
            if (socket === ws) socket = null
            ws.cancel()
            failPending("연결이 끊겼어요")
        }
    }

    private fun elapsed(since: Long) = if (since == 0L) 0L else System.currentTimeMillis() - since

    /** 부탁 하나에 답한다. 처리기가 늦거나 실패해도 답은 꼭 한 번 간다. */
    private suspend fun answer(webSocket: WebSocket, frame: ServerFrame.Invoke) {
        val handler = onInvoke
        val reply = when {
            frame.id.isEmpty() -> return // 답할 곳이 없다.
            handler == null -> InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_OFFERED)
            else -> try {
                withTimeoutOrNull(invokeDeadlineMs) { handler(frame) }
                    ?: InvokeReply.Failed(ErrorCodes.UNAVAILABLE, INVOKE_LATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InvokeReply.Failed(ErrorCodes.FAILED, INVOKE_FAILED)
            }
        }
        webSocket.send(resultFrame(frame.id, reply))
    }

    /** 앱 → 데몬 프레임은 64 KiB까지다(14장). 넘는 답은 보내지 못하니 실패로 바꾼다. */
    private fun resultFrame(id: String, reply: InvokeReply): String {
        val text = when (reply) {
            is InvokeReply.Ok -> ClientFrames.invokeOk(id, reply.data)
            is InvokeReply.Failed -> ClientFrames.invokeError(id, reply.code, reply.message)
        }
        return if (text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) text
        else ClientFrames.invokeError(id, ErrorCodes.FAILED, INVOKE_TOO_BIG)
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val STABLE_MS = 10_000L
        const val THROTTLE_WAIT_MS = 10 * 60_000L
        const val HELLO_WAIT_MS = 12_000L
        const val PING_EVERY_MS = 30_000L
        const val PONG_WAIT_MS = 10_000L

        /** 데몬은 부탁 하나에 40초를 기다린다(7.1). 그 안에 닿도록 앱은 이만큼에서 끊고 답한다. */
        const val INVOKE_DEADLINE_MS = 34_000L
        /** 앱 → 데몬 프레임 상한(64 KiB)보다 조금 작게. */
        const val MAX_FRAME_BYTES = 60_000

        // 루시아가 사용자에게 그대로 전하는 까닭(7.2). 사람이 읽을 글로 쓴다.
        const val NOT_OFFERED = "이 앱은 그 기능을 내주지 않았다"
        const val INVOKE_LATE = "휴대폰이 제때 답하지 못했다"
        const val INVOKE_FAILED = "휴대폰에서 처리하지 못했다"
        const val INVOKE_TOO_BIG = "결과가 너무 커서 보내지 못했다"

        fun describeFailure(t: Throwable): String = when (t) {
            is java.net.UnknownHostException -> "서버 주소를 찾을 수 없어요"
            is java.net.SocketTimeoutException -> "서버가 응답하지 않아요"
            is java.net.ConnectException -> "서버에 연결할 수 없어요"
            is javax.net.ssl.SSLException -> "보안 연결(TLS)에 실패했어요"
            else -> t.message?.take(80) ?: "연결 오류"
        }
    }
}
