package dev.mooner.lucent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lucia 접속 API 프로토콜 판 2(docs/app-api.md)와 이모지·스티커 확장(docs/app-expressions.md)의 프레임과 데이터 형식.
 *
 * 사양 4장에 따라 모르는 필드와 모르는 `type`은 무시한다. 같은 판 안에서는 필드와 프레임 종류가 늘어나기만 한다.
 */
const val PROTOCOL_VERSION = 2

val LucentJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

// ---------------------------------------------------------------- 데이터 형식(9장)

@Serializable
data class FileRef(
    val id: String,
    val name: String = "file",
    val size: Long = 0,
    val mime: String = "application/octet-stream",
    val kind: String = "other",
) {
    val isImage get() = kind == "image"
}

@Serializable
data class CardPrompt(
    val title: String = "",
    val label: String = "",
    val placeholder: String? = null,
    @SerialName("min_len") val minLen: Int = 0,
    @SerialName("max_len") val maxLen: Int = 200,
)

@Serializable
data class CardButton(
    val action: String,
    val label: String = "",
    val primary: Boolean = false,
    val prompt: CardPrompt? = null,
)

@Serializable
data class CardField(
    val name: String = "",
    val value: String = "",
    val inline: Boolean = false,
)

@Serializable
data class Card(
    val headline: String = "",
    val title: String = "",
    val fields: List<CardField> = emptyList(),
    val body: String = "",
    val footer: String? = null,
    val tone: String = "neutral",
    val buttons: List<CardButton> = emptyList(),
) {
    /** 대기 중인 카드만 눌러 답할 수 있다. 닫힌 카드는 보통 버튼이 없다. */
    val isPending get() = tone == "pending" && buttons.isNotEmpty()
}

@Serializable
data class HistoryItem(
    val id: Long,
    val at: String,
    val from: String,
    val conversation: String? = null,
    val body: String = "",
)

@Serializable
data class HistoryPage(
    val items: List<HistoryItem> = emptyList(),
    val more: Boolean = false,
)

@Serializable
data class PushState(
    val available: Boolean = false,
    val registered: Boolean = false,
)

@Serializable
data class Capabilities(
    @SerialName("max_message_chars") val maxMessageChars: Int = 8000,
    val buttons: Boolean = true,
    val edit: Boolean = true,
    val prompts: Boolean = true,
    val attachments: Boolean = false,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 31_457_280,
    @SerialName("max_message_files") val maxMessageFiles: Int = 10,
)

// ---------------------------------------------------------------- 이모지·스티커(docs/app-expressions.md)

/** `welcome`·`GET /v1/me`의 `expressions`. 이 필드가 없으면 이모지·스티커를 다루지 않는 데몬이다(9장). */
@Serializable
data class ExpressionsSummary(
    val version: String = "",
    val emoji: Int = 0,
    val stickers: Int = 0,
)

/**
 * 루시아의 프로필(8.7). `welcome`·`GET /v1/me`의 `profile`, `GET /v1/profile`, `profile` 프레임이 같은 모양이다.
 * [avatar]가 null이면 루시아가 정한 그림이 없다(앱의 기본 모습을 쓴다).
 */
@Serializable
data class Profile(val avatar: Avatar? = null)

/**
 * 루시아가 정한 프로필 그림. [id]는 내용 해시(`a_` + 16진수 24자)라 그림이 바뀌면 바뀐다. 캐시의 열쇠다.
 * 그림은 늘 정사각형의 PNG·JPEG이고 3 MiB를 넘지 않는다. `mime`은 믿지 않고 디코더가 받는지로 판단한다.
 */
@Serializable
data class Avatar(
    val id: String = "",
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** 파일 이름과 주소의 마디로 쓸 수 있는 id인가. 아니면 그림이 없는 것으로 다룬다. */
    val usable: Boolean get() = ID.matches(id)

    companion object {
        private val ID = Regex("^a_[0-9a-fA-F]{24}$")

        /** 프로필 그림의 상한(14장): 3 MiB. 이보다 큰 것은 받지 않는다. */
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}

/** 그림 파일(2.4). `id`는 내용 해시라 바뀌지 않는다. `mime`은 믿지 않고 디코더가 받는지로 판단한다. */
@Serializable
data class ExpressionFile(
    val id: String = "",
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
)

/** 이모지나 스티커 한 항목(2.1, 2.2). `label`은 이모지에만 있다. */
@Serializable
data class Expression(
    val id: String = "",
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "",
    val animated: Boolean = false,
    val file: ExpressionFile = ExpressionFile(),
)

/** 고르기 화면의 묶음(2.3). `icon`은 탭 아이콘으로 쓸 항목의 ID다(없을 수 있다). */
@Serializable
data class ExpressionCategory(
    val id: String = "",
    val label: String = "",
    val order: Int = 0,
    val icon: String? = null,
)

/** `GET /v1/expressions`의 본문(3.1). */
@Serializable
data class ExpressionCatalog(
    val version: String = "",
    val categories: List<ExpressionCategory> = emptyList(),
    val emoji: List<Expression> = emptyList(),
    val stickers: List<Expression> = emptyList(),
)

/** `welcome` 프레임과 `GET /v1/me`가 함께 쓰는 모양(me에는 일부 필드가 없다). */
@Serializable
data class Welcome(
    val v: Int = PROTOCOL_VERSION,
    val client: String = "",
    val name: String = "",
    val role: String = "app",
    val conversation: String = "",
    val seq: Long = 0,
    val acked: Long = 0,
    val online: Boolean = false,
    val scopes: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    /** 받아들인 `hello.tools`의 이름(7.2). 루시아가 실제로 부르려면 [scopes]에 `app_tools`가 있어야 한다. */
    val tools: List<String> = emptyList(),
    @SerialName("notify_target") val notifyTarget: Boolean = false,
    val push: PushState = PushState(),
    val capabilities: Capabilities = Capabilities(),
    /** 이모지·스티커 목록의 요약. 없으면(null) 그 기능이 없는 데몬이고, 앱은 고르기 화면을 숨긴다. */
    val expressions: ExpressionsSummary? = null,
    /** 루시아의 프로필(8.7). 없으면(null) 프로필 그림 기능이 없는 데몬이고, 앱은 기본 모습을 쓴다. */
    val profile: Profile? = null,
) {
    fun hasScope(scope: String) = scope in scopes
    fun canCall(method: String) = method in methods
}

@Serializable
data class CommandInfo(
    val name: String,
    val about: String = "",
    val options: List<String> = emptyList(),
    /** 지금 값. `options` 가운데 하나다. 예전 데몬에는 이 필드가 없다(null). */
    val current: String? = null,
    @SerialName("can_change") val canChange: Boolean = false,
)

/**
 * `session.compact`의 답(app-api-changes.md 2026-11-20, 사양 6.6). 분기는 [outcome]으로 하되, `ok: true`이면 값이
 * 무엇이든(모르는 값이어도) "끝났다"로 다룬다. [text]는 사용자에게 그대로 보여도 되는 글이다.
 */
@Serializable
data class CompactResult(
    val outcome: String = "",
    @SerialName("pre_tokens") val preTokens: Long = 0,
    @SerialName("context_tokens") val contextTokens: Long = 0,
    val text: String = "",
)

@Serializable
data class CommandList(val commands: List<CommandInfo> = emptyList())

@Serializable
data class CommandText(val text: String = "")

@Serializable
data class ApiError(val code: String = "failed", val message: String = "")

// ---------------------------------------------------------------- 데몬 → 앱 프레임(5.5)

sealed interface ServerFrame {
    data class WelcomeFrame(val welcome: Welcome) : ServerFrame
    data class Resumed(val from: Long, val to: Long, val last: Long, val truncated: Boolean) : ServerFrame

    /** 번호 붙는 프레임: 서버 상자에 남고 앱이 ack한다. */
    sealed interface Numbered : ServerFrame {
        val seq: Long
        val replay: Boolean
    }

    data class Text(override val seq: Long, override val replay: Boolean, val body: String) : Numbered
    data class Note(override val seq: Long, override val replay: Boolean, val body: String) : Numbered
    data class Files(override val seq: Long, override val replay: Boolean, val body: String, val files: List<FileRef>) : Numbered
    data class CardOpen(override val seq: Long, override val replay: Boolean, val ref: String, val card: Card) : Numbered
    data class CardClosed(override val seq: Long, override val replay: Boolean, val ref: String, val card: Card) : Numbered
    /** 스티커 한 장(app-expressions.md 5.1). 보통 같은 답의 `text` 바로 뒤에 온다. */
    data class Sticker(override val seq: Long, override val replay: Boolean, val id: String, val name: String) : Numbered

    data object Typing : ServerFrame
    /** 이모지·스티커 목록이 바뀌었다(번호 없음, 3.3). 저장한 판과 다르면 목록을 다시 받는다. */
    data class Expressions(val version: String) : ServerFrame
    /** 루시아의 프로필 그림이 바뀌었거나 없어졌다(번호 없음, 5.5). [avatar]가 null이면 기본 모습으로 돌아간다. */
    data class ProfileChanged(val avatar: Avatar?) : ServerFrame
    data class Accepted(val id: String?) : ServerFrame
    data class Result(val id: String, val ok: Boolean, val data: JsonElement?, val error: ApiError?) : ServerFrame
    data class Invoke(val id: String, val method: String, val params: JsonElement?) : ServerFrame
    data class Error(val code: String, val message: String) : ServerFrame
    data object Pong : ServerFrame

    /** 모르는 `type`. 오류로 다루지 않고 버린다(4장). */
    data class Unknown(val type: String) : ServerFrame
}

/** 데몬의 부탁(`invoke`, 7장)에 앱이 줄 답. 같은 `id`로 한 번만 보낸다. */
sealed interface InvokeReply {
    /** [data]는 JSON이면 무엇이든 된다. 루시아에게는 8 KB까지 보인다(7.2). */
    data class Ok(val data: JsonElement) : InvokeReply

    /** [message]는 루시아가 사용자에게 그대로 전하는 까닭이다. 사람이 읽을 한국어로 쓴다. */
    data class Failed(val code: String, val message: String) : InvokeReply
}

/** 읽을 수 없는 프레임. 연결은 그대로 둔다. */
class FrameParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun parseServerFrame(text: String): ServerFrame {
    val obj = try {
        LucentJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw FrameParseException("깨진 JSON", e)
    }
    // 여기서 던지는 것은 FrameParseException뿐이어야 한다. 다른 예외는 받는 쪽에서 연결을 끊는다. 그래서 값이 글·수가
    // 아닐 수 있는 자리(객체, 배열)는 `jsonPrimitive`로 단정하지 않고 없는 것으로 본다.
    fun flag(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull == "true"
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: throw FrameParseException("type 없음")
    fun str(key: String) = obj[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    fun long(key: String) = obj[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    fun seq() = long("seq") ?: throw FrameParseException("$type 프레임에 seq 없음")
    val replay = flag("replay")
    return try {
        when (type) {
            "welcome" -> ServerFrame.WelcomeFrame(LucentJson.decodeFromJsonElement<Welcome>(obj))
            "resumed" -> ServerFrame.Resumed(
                from = long("from") ?: 0,
                to = long("to") ?: 0,
                last = long("last") ?: 0,
                truncated = flag("truncated"),
            )
            "text" -> ServerFrame.Text(seq(), replay, str("body").orEmpty())
            "note" -> ServerFrame.Note(seq(), replay, str("body").orEmpty())
            "files" -> ServerFrame.Files(
                seq(), replay, str("body").orEmpty(),
                obj["files"]?.let { LucentJson.decodeFromJsonElement<List<FileRef>>(it) }.orEmpty(),
            )
            "card" -> ServerFrame.CardOpen(seq(), replay, str("ref").orEmpty(), LucentJson.decodeFromJsonElement(obj["card"] ?: JsonObject(emptyMap())))
            "card_closed" -> ServerFrame.CardClosed(seq(), replay, str("ref").orEmpty(), LucentJson.decodeFromJsonElement(obj["card"] ?: JsonObject(emptyMap())))
            "sticker" -> {
                val sticker = obj["sticker"] as? JsonObject
                fun field(key: String) = sticker?.get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty()
                ServerFrame.Sticker(seq(), replay, field("id"), field("name"))
            }
            "expressions" -> ServerFrame.Expressions(str("version").orEmpty())
            "profile" -> ServerFrame.ProfileChanged(
                (obj["avatar"] as? JsonObject)?.let { LucentJson.decodeFromJsonElement<Avatar>(it) },
            )
            "typing" -> ServerFrame.Typing
            "accepted" -> ServerFrame.Accepted(str("id"))
            "result" -> ServerFrame.Result(
                id = str("id").orEmpty(),
                ok = obj["ok"]?.jsonPrimitive?.contentOrNull == "true",
                data = obj["data"],
                error = obj["error"]?.takeIf { it !is JsonNull }?.let { LucentJson.decodeFromJsonElement<ApiError>(it) },
            )
            "invoke" -> ServerFrame.Invoke(str("id").orEmpty(), str("method").orEmpty(), obj["params"])
            "error" -> ServerFrame.Error(str("code").orEmpty(), str("message").orEmpty())
            "pong" -> ServerFrame.Pong
            else -> ServerFrame.Unknown(type)
        }
    } catch (e: FrameParseException) {
        throw e
    } catch (e: Exception) {
        throw FrameParseException("$type 프레임을 읽지 못함", e)
    }
}

// ---------------------------------------------------------------- 앱 → 데몬 프레임(5.6)

object ClientFrames {

    /**
     * [tools]는 루시아에게 내주는 기능의 선언(7.2)이다. 비어 있으면 필드를 보내지 않는다. 그 이름의 첫 마디는 데몬이
     * `features`에 더하므로 [features]에 따로 적지 않아도 된다.
     */
    fun hello(token: String, resume: Long?, features: Collection<String>, tools: List<JsonObject> = emptyList()): String = buildJsonObject {
        put("type", "hello")
        put("token", token)
        if (resume != null) put("resume", resume)
        putJsonArray("features") { features.forEach { add(JsonPrimitive(it)) } }
        if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(it) } }
    }.toString()

    /** [sticker]는 목록에 있는 스티커 ID 하나다(app-expressions.md 5.2). 글·파일·스티커 가운데 하나는 있어야 한다. */
    fun message(text: String, files: List<String>, id: String, sticker: String? = null): String = buildJsonObject {
        put("type", "message")
        if (text.isNotEmpty() || sticker.isNullOrEmpty()) put("text", text)
        if (files.isNotEmpty()) putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
        if (!sticker.isNullOrEmpty()) put("sticker", sticker)
        put("id", id)
    }.toString()

    fun press(ref: String, action: String, input: String?): String = buildJsonObject {
        put("type", "press")
        put("ref", ref)
        put("action", action)
        if (input != null) put("input", input)
    }.toString()

    fun ack(seq: Long): String = buildJsonObject {
        put("type", "ack")
        put("seq", seq)
    }.toString()

    fun presence(foreground: Boolean): String = buildJsonObject {
        put("type", "presence")
        put("state", if (foreground) "foreground" else "background")
    }.toString()

    fun call(id: String, method: String, params: JsonObject?): String = buildJsonObject {
        put("type", "call")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    /** 데몬의 `invoke`에 대한 답(7.1). 같은 `id`로 한 번만 보낸다. */
    fun invokeOk(id: String, data: JsonElement): String = buildJsonObject {
        put("type", "result")
        put("id", id)
        put("ok", true)
        put("data", data)
    }.toString()

    /** 데몬의 `invoke`에 대한 거절. [code]는 앱이 정한다(권장: `denied`, `unavailable`, `bad_params`, `failed`). */
    fun invokeError(id: String, code: String, message: String): String = buildJsonObject {
        put("type", "result")
        put("id", id)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    fun ping(): String = """{"type":"ping"}"""
}

/** WebSocket `error`/`result.error`의 `code`(15장). 앱은 메시지가 아니라 코드로 가른다. */
object ErrorCodes {
    const val HELLO_REQUIRED = "hello_required"
    const val UNAUTHORIZED = "unauthorized"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val UNSUPPORTED_ROLE = "unsupported_role"
    const val BAD_FRAME = "bad_frame"
    const val EMPTY = "empty"
    const val TOO_LONG = "too_long"
    const val BAD_FILE = "bad_file"
    const val BAD_STICKER = "bad_sticker"
    const val BAD_REF = "bad_ref"
    const val CLOSED = "closed"
    const val FORBIDDEN = "forbidden"
    const val TIMEOUT = "timeout"
    const val BAD_PARAMS = "bad_params"
    const val UNKNOWN_COMMAND = "unknown_command"
    const val UNSUPPORTED = "unsupported"
    const val FAILED = "failed"
    /** 같은 일이 이미 돌고 있다(`session.compact`). */
    const val BUSY = "busy"

    // 앱이 `invoke`에 답할 때 쓰는 코드(7.1의 권장 코드).
    /** 사용자나 휴대폰(권한)이 거절했다. */
    const val DENIED = "denied"
    /** 지금은 할 수 없다(위치를 못 잡았다, 알림이 사라졌다 등). */
    const val UNAVAILABLE = "unavailable"

    /** 재연결해도 소용없는 오류. 사용자가 토큰을 다시 넣어야 한다(12.3). */
    fun endsSession(code: String) = code == UNAUTHORIZED || code == UNSUPPORTED_ROLE
}

/** 사용자에게 보일 한국어 풀이. 서버 `message`는 바뀔 수 있어 코드로 고른다. */
fun describeError(code: String, fallback: String = ""): String = when (code) {
    ErrorCodes.UNAUTHORIZED -> "토큰이 틀렸거나 취소됐어요"
    ErrorCodes.TOO_MANY_ATTEMPTS -> "인증 실패가 많아 잠시 막혔어요. 10분쯤 뒤에 다시 해 주세요"
    ErrorCodes.UNSUPPORTED_ROLE -> "이 토큰은 앱 연결에 쓸 수 없어요"
    ErrorCodes.EMPTY -> "보낼 내용이 없어요"
    ErrorCodes.TOO_LONG -> "글이 너무 길어요"
    ErrorCodes.BAD_FILE -> "쓸 수 없는 파일이에요"
    ErrorCodes.BAD_STICKER -> "지금은 쓸 수 없는 스티커예요"
    ErrorCodes.BAD_REF -> "이 카드는 지금 누를 수 없어요"
    ErrorCodes.CLOSED -> "서버가 멈추는 중이에요"
    ErrorCodes.FORBIDDEN -> "이 토큰에는 그 권한이 없어요"
    ErrorCodes.TIMEOUT -> "서버가 제때 답하지 않았어요"
    ErrorCodes.BAD_PARAMS -> "요청 값이 올바르지 않아요"
    ErrorCodes.UNSUPPORTED -> "이 서버가 지원하지 않는 기능이에요"
    ErrorCodes.BUSY -> "이미 하고 있는 중이에요"
    else -> fallback.ifBlank { "처리하지 못했어요" }
}
