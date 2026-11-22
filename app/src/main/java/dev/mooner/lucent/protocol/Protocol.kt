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
    /** 기록(`history`, `shape: 2`)의 파일만: 보관 기간이 지나 지워졌다(6.2). */
    val gone: Boolean = false,
    /** 기록의 파일만: 이 토큰이 받을 수 있는가(6.2, 8.5). 실시간 프레임의 파일은 늘 받을 수 있다. */
    val reachable: Boolean = true,
) {
    val isImage get() = kind == "image"

    /** 받아 볼 수 있는 파일인가. 지워졌거나 이 기기에서 받을 수 없는 파일은 이름만 보인다. */
    val available get() = !gone && reachable
}

@Serializable
data class CardPrompt(
    val title: String = "",
    val label: String = "",
    val placeholder: String? = null,
    @SerialName("min_len") val minLen: Int = 0,
    @SerialName("max_len") val maxLen: Int = 200,
)

/** 여러 칸을 받는 버튼의 입력창(9.1, 결정 0063). 아이디·비밀번호 등. 값은 `press.inputs`로 보낸다. */
@Serializable
data class CardForm(
    val title: String = "",
    val fields: List<FormField> = emptyList(),
)

@Serializable
data class FormField(
    /** 값의 이름. `press.inputs`의 키로 그대로 쓴다. */
    val key: String = "",
    val label: String = "",
    /** [TEXT](보이는 글), [SECRET](가려서 받는 글), [CHECK](켜고 끄는 칸). 그 밖은 모르는 칸이다. */
    val kind: String = "",
    @SerialName("min_len") val minLen: Int = 0,
    @SerialName("max_len") val maxLen: Int = 0,
) {
    companion object {
        const val TEXT = "text"
        const val SECRET = "secret"
        const val CHECK = "check"
    }
}

@Serializable
data class CardButton(
    val action: String,
    val label: String = "",
    val primary: Boolean = false,
    val prompt: CardPrompt? = null,
    val form: CardForm? = null,
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
    /** `shape: 2`(6.2): 여러 `text` 프레임으로 나뉘어 갔던 말의 조각들. 하나로 갔으면 없다. */
    val parts: List<String>? = null,
    /** `shape: 2`(6.2): 이 말에 붙은 파일. 있으면 `body`에서 파일 설명 줄이 빠져 있다. */
    val files: List<FileRef>? = null,
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
    /** 여러 칸을 받는 버튼(`form`)을 쓰는 데몬인가. 없으면(false) `form`을 모르는 예전 데몬이다. */
    val forms: Boolean = false,
    val attachments: Boolean = false,
    @SerialName("max_upload_bytes") val maxUploadBytes: Long = 31_457_280,
    @SerialName("max_message_files") val maxMessageFiles: Int = 10,
    /** `2`면 `history`의 `shape: 2`(조각과 파일)를 안다(6.2, 결정 0066). */
    @SerialName("history_shape") val historyShape: Int = 0,
    /** `seen`·`alert` 프레임을 주고받는다(5.5, 5.6, 11.1, 결정 0066). 없으면 `seen`을 보내지 않는다(예전 데몬은 `bad_frame`). */
    val seen: Boolean = false,
    /** 앱이 기기가 여럿이어도 한 대화다(결정 0067). 다른 기기에서 한 말이 `sent`로 온다(5.5, 12.8). */
    @SerialName("shared_conversation") val sharedConversation: Boolean = false,
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
 * [avatar]가 null이면 정한 그림이 없다(앱의 기본 모습을 쓴다). [name]은 사용자가 관리 페이지에서 정한 보이는 이름이다
 * (결정 0064, 정하지 않았으면 `"Lucia"`). 없으면(null) 이름이 붙기 전의 데몬이라 앱의 기본 이름을 쓴다.
 */
@Serializable
data class Profile(val name: String? = null, val avatar: Avatar? = null)

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

    /**
     * 울릴 수 있는 프레임(`text`·`files`·`card`·`sticker`, 5.4). [alert]가 false면 다른 기기가 울린다: 화면에 놓기만 하고
     * 알리지 않는다. 데몬이 나중에 `alert{seq}`로 지금 알리라고 할 수 있다(5.5, 11.1).
     */
    sealed interface Alerting : Numbered {
        val alert: Boolean
    }

    // [conversation]은 다른 앱(기기)의 대화에 속한 프레임에만 붙는다(5.5, 결정 0067): 다른 기기에서 한 말의 답, 그 대화의 카드.
    // 없으면 이 앱의 대화나 앱 모두의 대화(`app:*`)다.
    data class Text(
        override val seq: Long, override val replay: Boolean, val body: String,
        override val alert: Boolean = true, val conversation: String? = null,
    ) : Alerting
    data class Note(override val seq: Long, override val replay: Boolean, val body: String, val conversation: String? = null) : Numbered
    data class Files(
        override val seq: Long, override val replay: Boolean, val body: String, val files: List<FileRef>,
        override val alert: Boolean = true, val conversation: String? = null,
    ) : Alerting
    data class CardOpen(
        override val seq: Long, override val replay: Boolean, val ref: String, val card: Card,
        override val alert: Boolean = true, val conversation: String? = null,
    ) : Alerting
    data class CardClosed(
        override val seq: Long, override val replay: Boolean, val ref: String, val card: Card, val conversation: String? = null,
    ) : Numbered
    /** 스티커 한 장(app-expressions.md 5.1). 보통 같은 답의 `text` 바로 뒤에 온다. */
    data class Sticker(
        override val seq: Long, override val replay: Boolean, val id: String, val name: String,
        override val alert: Boolean = true, val conversation: String? = null,
    ) : Alerting

    /**
     * 사용자가 **다른 기기**의 앱에서 한 말의 사본(5.5, 결정 0067). 사용자 자신의 말풍선으로 그린다. 울리지 않는다.
     * [body]는 보낸 글 그대로(스티커 줄·파일 설명 줄·고른 글 표지가 없다), [contextNote]는 고른 글 표지 한 줄이다.
     * [fromName]은 그 앱을 발급할 때 붙인 이름, [at]은 데몬이 말을 받은 시각(RFC 3339)이다.
     */
    data class Sent(
        override val seq: Long, override val replay: Boolean, val body: String,
        val conversation: String?, val fromName: String?, val at: String?,
        val files: List<FileRef> = emptyList(), val stickerId: String? = null, val stickerName: String? = null,
        val contextNote: String? = null,
    ) : Numbered

    /** 번호가 있지만 모르는 `type`. 그리지 않되 번호는 올리고 `ack`한다(5.4). */
    data class UnknownNumbered(override val seq: Long, override val replay: Boolean, val type: String) : Numbered

    data object Typing : ServerFrame
    /** 이모지·스티커 목록이 바뀌었다(번호 없음, 3.3). 저장한 판과 다르면 목록을 다시 받는다. */
    data class Expressions(val version: String) : ServerFrame
    /**
     * 프로필 그림이나 이름이 바뀌었다(번호 없음, 5.5). 늘 지금의 이름과 그림 전체가 온다. [avatar]가 null이면 기본 모습으로,
     * [name]이 null이면(이름이 붙기 전의 데몬) 기본 이름으로 돌아간다.
     */
    data class ProfileChanged(val name: String?, val avatar: Avatar?) : ServerFrame
    /** 다른 기기에서 이 번호(이 앱의 번호)까지 봤다(번호 없음, 5.5). 그 번호까지의 알림을 치운다. 되돌려 `seen`을 보내지 않는다. */
    data class Seen(val seq: Long) : ServerFrame
    /** 울리지 않고 놓아 둔(`alert: false`) 이 번호의 프레임을 지금 알려라(번호 없음, 5.5, 11.1). */
    data class Alert(val seq: Long) : ServerFrame
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
    // 필드가 없으면 울린다(5.4). `false`일 때만 놓아 둔다.
    val alert = (obj["alert"] as? JsonPrimitive)?.contentOrNull != "false"
    val conversation = (obj["conversation"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    return try {
        when (type) {
            "welcome" -> ServerFrame.WelcomeFrame(LucentJson.decodeFromJsonElement<Welcome>(obj))
            "resumed" -> ServerFrame.Resumed(
                from = long("from") ?: 0,
                to = long("to") ?: 0,
                last = long("last") ?: 0,
                truncated = flag("truncated"),
            )
            "text" -> ServerFrame.Text(seq(), replay, str("body").orEmpty(), alert, conversation)
            "note" -> ServerFrame.Note(seq(), replay, str("body").orEmpty(), conversation)
            "files" -> ServerFrame.Files(seq(), replay, str("body").orEmpty(), files(obj), alert, conversation)
            "card" -> ServerFrame.CardOpen(
                seq(), replay, str("ref").orEmpty(), LucentJson.decodeFromJsonElement(obj["card"] ?: JsonObject(emptyMap())), alert, conversation,
            )
            "card_closed" -> ServerFrame.CardClosed(
                seq(), replay, str("ref").orEmpty(), LucentJson.decodeFromJsonElement(obj["card"] ?: JsonObject(emptyMap())), conversation,
            )
            "sticker" -> {
                val sticker = obj["sticker"] as? JsonObject
                ServerFrame.Sticker(seq(), replay, sticker.field("id").orEmpty(), sticker.field("name").orEmpty(), alert, conversation)
            }
            "sent" -> {
                val from = obj["from"] as? JsonObject
                val sticker = obj["sticker"] as? JsonObject
                ServerFrame.Sent(
                    seq(), replay, str("body").orEmpty(),
                    conversation = conversation,
                    fromName = from.field("name")?.takeIf { it.isNotBlank() },
                    at = str("at"),
                    files = files(obj),
                    stickerId = sticker.field("id")?.takeIf { it.isNotEmpty() },
                    stickerName = sticker.field("name")?.takeIf { it.isNotEmpty() },
                    contextNote = str("context_note")?.takeIf { it.isNotBlank() },
                )
            }
            "expressions" -> ServerFrame.Expressions(str("version").orEmpty())
            "profile" -> ServerFrame.ProfileChanged(
                str("name"),
                (obj["avatar"] as? JsonObject)?.let { LucentJson.decodeFromJsonElement<Avatar>(it) },
            )
            "seen" -> ServerFrame.Seen(seq())
            "alert" -> ServerFrame.Alert(seq())
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
            // 모르는 type이라도 번호가 있으면 상자에 남는 프레임이다. 번호를 올리고 ack해야 상자가 빈다(5.4).
            else -> long("seq")?.let { ServerFrame.UnknownNumbered(it, replay, type) } ?: ServerFrame.Unknown(type)
        }
    } catch (e: FrameParseException) {
        throw e
    } catch (e: Exception) {
        throw FrameParseException("$type 프레임을 읽지 못함", e)
    }
}

/** 객체의 글 필드. 객체가 아니거나 글이 아니면 null이다. */
private fun JsonObject?.field(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun files(obj: JsonObject): List<FileRef> =
    obj["files"]?.takeIf { it !is JsonNull }?.let { LucentJson.decodeFromJsonElement<List<FileRef>>(it) }.orEmpty()

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

    /**
     * 카드 버튼 누르기(5.6). 값을 하나 받는 버튼은 [input], 여러 칸을 받는 버튼은 [inputs](칸 이름 → 글이나 불)로 보낸다.
     * [inputs]의 글은 다듬지 않은 그대로다.
     */
    fun press(ref: String, action: String, input: String?, inputs: Map<String, FormValue>? = null): String = buildJsonObject {
        put("type", "press")
        put("ref", ref)
        put("action", action)
        if (input != null) put("input", input)
        if (inputs != null) put("inputs", buildJsonObject {
            for ((key, value) in inputs) when (value) {
                is FormValue.Text -> put(key, value.value)
                is FormValue.Check -> put(key, value.on)
            }
        })
    }.toString()

    fun ack(seq: Long): String = buildJsonObject {
        put("type", "ack")
        put("seq", seq)
    }.toString()

    /**
     * 사용자가 이 번호(이 앱의 번호)까지 **모두** 봤다(5.6, 12.6). `capabilities.seen`이 있을 때만 보낸다. 모자라게 알린다:
     * 잘못 알린 `seen`은 휴대폰으로 넘어갈 승인 카드를 막는다.
     */
    fun seen(seq: Long): String = buildJsonObject {
        put("type", "seen")
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

    /** 데몬의 `invoke`에 대한 거절. [code]는 앱이 정한다(권장: `denied`, `timeout`, `unavailable`, `bad_params`, `failed`). */
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
    /** 브라우저 화면을 넘겨받은 상태가 아니다(6.7, HTTP는 409). 데몬이 끝낸 뒤에도 온다. */
    const val NOT_ACTIVE = "not_active"
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
    // 사용자가 제때 답하지 않았다는 답은 [TIMEOUT]으로 한다. 거절([DENIED])과 가려 쓴다(7.1).

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
    ErrorCodes.NOT_ACTIVE -> "브라우저 화면 넘겨받기가 끝났어요"
    else -> fallback.ifBlank { "처리하지 못했어요" }
}
