package dev.mooner.lucent.net

import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.data.Route
import dev.mooner.lucent.protocol.ApiError
import dev.mooner.lucent.protocol.ExpressionCatalog
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.HistoryPage
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.protocol.Welcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 서버가 답한 오류(`{"error": {"code", "message"}}`). 서버에 닿지 못한 경우는 [IOException]이다. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/**
 * HTTP API(사양 8장). 인증은 `Authorization: Bearer <토큰>`이다.
 *
 * 두 길이 있으면 마지막으로 붙은 길부터 해 보고, **서버에 닿지 못한 경우에만** 다음 길로 넘어간다. 서버가 오류를
 * 답했으면 그대로 알린다. `X-Forwarded-For`·`Forwarded`는 보내지 않는다(2.2). 다른 호스트로 보내는 리다이렉트는 따라가지 않아
 * 토큰이 새지 않는다.
 */
class GatewayApi(
    private val settings: AppSettings,
    http: OkHttpClient,
    private val userAgent: String = "Lucent",
) {
    private val client = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 파일 받기·올리기는 오래 걸릴 수 있다. */
    private val bulkClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 지금 붙을 수 있는 가장 가능성 높은 주소. 이미지 같은 곳에 URL을 만들 때 쓴다. */
    fun preferredBase(): String? = settings.routes().firstOrNull()?.let(settings::base)

    // ------------------------------------------------------------------ 호출

    /** 설정에 저장하기 전의 주소·토큰을 시험해 볼 때 쓴다. */
    suspend fun probe(base: String, token: String): Welcome = withContext(Dispatchers.IO) {
        getJson("$base/v1/me", token, Welcome.serializer())
    }

    suspend fun me(): Welcome = onRoutes { base, token -> getJson("$base/v1/me", token, Welcome.serializer()) }

    suspend fun history(before: Long?, limit: Int, scope: String): HistoryPage = onRoutes { base, token ->
        val url = "$base/v1/history".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("scope", scope)
            .apply { if (before != null) addQueryParameter("before", before.toString()) }
            .build()
        getJson(url.toString(), token, HistoryPage.serializer())
    }

    suspend fun registerPush(fcmToken: String, platform: String = "android") {
        val body = buildJsonObject {
            put("token", fcmToken)
            put("platform", platform)
        }.toString().toRequestBody(JSON)
        onRoutes { base, token ->
            execute(Request.Builder().url("$base/v1/push").put(body), token).use(::requireSuccess)
        }
    }

    suspend fun unregisterPush() {
        onRoutes { base, token ->
            execute(Request.Builder().url("$base/v1/push").delete(), token).use(::requireSuccess)
        }
    }

    /**
     * 파일을 올린다(`POST /v1/files?name=`). 본문이 파일 그대로다. [open]은 호출될 때마다 처음부터 읽는 스트림을 준다
     * (길이 둘을 시도하면 두 번 읽을 수 있다).
     */
    suspend fun upload(
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (sent: Long) -> Unit = {},
    ): FileRef = onRoutes(bulk = true) { base, token ->
        val url = "$base/v1/files".toHttpUrl().newBuilder().addQueryParameter("name", name).build()
        val body = StreamBody(open, size, onProgress)
        execute(Request.Builder().url(url).post(body), token, bulk = true).use { response ->
            requireSuccess(response)
            LucentJson.decodeFromString(FileRef.serializer(), response.requireBody().string())
        }
    }

    /** 파일을 [dest]에 내려받는다. 이 앱의 대화로 오간 파일만 받을 수 있다. */
    suspend fun download(id: String, dest: File, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }) {
        onRoutes(bulk = true) { base, token ->
            execute(Request.Builder().url("$base/v1/files/$id").get(), token, bulk = true).use { response ->
                requireSuccess(response)
                val total = response.requireBody().contentLength()
                dest.parentFile?.mkdirs()
                response.requireBody().byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 이모지·스티커(docs/app-expressions.md 3장)

    /**
     * 이모지·스티커 목록. [known]은 이미 가진 판이다: 서버의 목록이 그대로면(304) null을 돌려준다.
     * 이 기능이 없는 데몬은 `501 unsupported`로 답한다([ApiException]).
     */
    suspend fun expressions(known: String?): ExpressionCatalog? = onRoutes { base, token ->
        val request = Request.Builder().url("$base/v1/expressions").get()
        if (!known.isNullOrEmpty()) request.header("If-None-Match", "\"$known\"")
        execute(request, token).use { response ->
            if (response.code == 304) return@use null
            requireSuccess(response)
            LucentJson.decodeFromString(ExpressionCatalog.serializer(), response.requireBody().string())
        }
    }

    /** 그림 한 장을 [dest]에 받는다. [maxBytes]를 넘으면 받지 않는다(목록의 한도보다 큰 파일은 정상이 아니다). */
    suspend fun expressionFile(fileId: String, dest: File, maxBytes: Long) {
        fetchCapped(dest, maxBytes) { base -> "$base/v1/expressions/files".toHttpUrl().newBuilder().addPathSegment(fileId).build() }
    }

    // ------------------------------------------------------------------ 프로필(사양 8.7, 8.8)

    /**
     * 루시아의 프로필 그림을 [dest]에 받는다(`GET /v1/profile/avatar/{id}`). id가 내용 해시라 한 번 받으면 다시 받지
     * 않는다. 지워진 id는 `404 not_found`, 이 기능이 없는 데몬은 `501 unsupported`다([ApiException]).
     */
    suspend fun avatarFile(id: String, dest: File, maxBytes: Long) {
        fetchCapped(dest, maxBytes) { base -> "$base/v1/profile/avatar".toHttpUrl().newBuilder().addPathSegment(id).build() }
    }

    /** 토큰이 든 머리로 그림을 받아 [dest]에 쓴다. [maxBytes]를 넘으면 그만두고 [IOException]을 던진다. */
    private suspend fun fetchCapped(dest: File, maxBytes: Long, url: (base: String) -> HttpUrl) {
        onRoutes(bulk = true) { base, token ->
            execute(Request.Builder().url(url(base)).get(), token, bulk = true).use { response ->
                requireSuccess(response)
                val body = response.requireBody()
                if (body.contentLength() > maxBytes) throw IOException("그림이 너무 커요")
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            if (received > maxBytes) throw IOException("그림이 너무 커요")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 안쪽

    private suspend fun <T> onRoutes(bulk: Boolean = false, block: suspend (base: String, token: String) -> T): T =
        withContext(Dispatchers.IO) { tryRoutes(block) }

    private suspend fun <T> tryRoutes(block: suspend (base: String, token: String) -> T): T {
        val token = settings.token ?: throw ApiException(401, "unauthorized", "토큰이 없어요")
        val routes = settings.routes()
        if (routes.isEmpty()) throw IOException("서버 주소가 없어요")
        var last: IOException? = null
        for (route in routes) {
            val base = settings.base(route) ?: continue
            try {
                val result = block(base, token)
                settings.lastRoute = route
                return result
            } catch (e: ApiException) {
                throw e // 서버가 답했다. 다른 길로 가도 같다.
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("서버에 연결할 수 없어요")
    }

    private suspend fun <T> getJson(url: String, token: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        execute(Request.Builder().url(url).get(), token).use { response ->
            requireSuccess(response)
            LucentJson.decodeFromString(serializer, response.requireBody().string())
        }

    private suspend fun execute(builder: Request.Builder, token: String, bulk: Boolean = false): Response {
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .build()
        return (if (bulk) bulkClient else client).newCall(request).await()
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val raw = runCatching { response.requireBody().string() }.getOrDefault("")
        val parsed = runCatching {
            LucentJson.decodeFromString(ErrorEnvelope.serializer(), raw).error
        }.getOrNull()
        val code = parsed?.code ?: fallbackCode(response.code)
        throw ApiException(response.code, code, parsed?.message?.ifBlank { null } ?: "서버가 ${response.code}로 답했어요")
    }

    @kotlinx.serialization.Serializable
    private class ErrorEnvelope(val error: ApiError)

    private fun fallbackCode(status: Int) = when (status) {
        400 -> "bad_params"
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        410 -> "gone"
        413 -> "too_large"
        429 -> "too_many_attempts"
        501 -> "unsupported"
        else -> "failed"
    }

    /** 앞에서부터 읽으며 보낸 만큼 알리는 요청 본문. */
    private class StreamBody(
        private val open: () -> InputStream,
        private val size: Long,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        // 종류는 서버가 파일 앞부분의 서명으로 정한다(8.4). Content-Type은 보지 않는다.
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun isOneShot() = false

        override fun writeTo(sink: BufferedSink) {
            val counting = CountingSink(sink, onProgress).buffer()
            // 파일을 열지 못하면(읽을 권한이 사라진 주소 등) 요청의 실패로 알린다. 다른 예외로 두면 OkHttp의 스레드에서
            // 앱이 죽는다.
            val input = try {
                open()
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                throw java.io.IOException("파일을 읽을 수 없어요", e)
            }
            input.use { counting.writeAll(it.source()) }
            counting.flush()
        }
    }

    private class CountingSink(delegate: Sink, private val onProgress: (Long) -> Unit) : ForwardingSink(delegate) {
        private var sent = 0L
        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            sent += byteCount
            onProgress(sent)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** OkHttp 호출을 코루틴으로 기다린다. 취소하면 요청도 취소한다. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

private fun Response.requireBody() = body ?: throw IOException("서버가 빈 응답을 보냈어요")
