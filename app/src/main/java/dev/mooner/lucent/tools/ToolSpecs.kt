package dev.mooner.lucent.tools

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 루시아에게 내주는 휴대폰 기능(사양 7.2, 결정 0053). **무엇을 내줄지는 앱이 정하고, 그 목록은 이 파일 하나다.**
 *
 * 기능 하나는 `기능.동작` 이름, 루시아가 읽고 고르는 설명, 인자 설명, 읽기·쓰기로 이루어진다. 사용자는 설정에서 [ToolSwitch]
 * 단위로 켜고 끈다. 켠 것만 `hello.tools`로 알리고, 루시아가 실제로 부르려면 관리 페이지에서 그 토큰에 범위 `app_tools`도
 * 켜야 한다.
 */
enum class PhoneTool(val method: String, val switch: ToolSwitch, val write: Boolean = false) {
    LOCATION("location.get", ToolSwitch.LOCATION),
    NOTIFICATIONS_RECENT("notifications.recent", ToolSwitch.NOTIFICATIONS),
    NOTIFICATIONS_GET("notifications.get", ToolSwitch.NOTIFICATIONS),

    /** 다른 사람에게 사용자의 이름으로 글을 보낸다. 데몬은 사람이 대화 중일 때만 부르고, 앱은 보내기 전에 묻는다(설정). */
    NOTIFICATIONS_REPLY("notifications.reply", ToolSwitch.REPLY, write = true);

    companion object {
        fun of(method: String): PhoneTool? = entries.firstOrNull { it.method == method }
    }
}

/** 설정의 스위치 하나가 켜고 끄는 기능 묶음. 답장은 알림 기록이 켜져 있어야 쓸 수 있다(답할 알림을 기록에서 고른다). */
enum class ToolSwitch { LOCATION, NOTIFICATIONS, REPLY }

object ToolSpecs {

    /** 루시아가 이 앱의 기능을 부르도록 사용자가 허락한 범위(사양 3.4). */
    const val SCOPE = "app_tools"

    /** 켠 스위치로 실제로 쓸 수 있는 기능들. */
    fun offered(on: Set<ToolSwitch>): List<PhoneTool> = PhoneTool.entries.filter { tool ->
        tool.switch in on && (tool.switch != ToolSwitch.REPLY || ToolSwitch.NOTIFICATIONS in on)
    }

    /** `hello.tools`에 싣는 선언들. */
    fun declarations(on: Set<ToolSwitch>): List<JsonObject> = offered(on).map(::declare)

    fun declare(tool: PhoneTool): JsonObject = buildJsonObject {
        put("name", tool.method)
        put("description", description(tool))
        put("params", params(tool))
        if (tool.write) put("effect", "write")
    }

    // 설명은 한 줄 200자까지(데몬이 자른다). 언제 쓰는지와 무엇이 돌아오는지를 적는다.
    private fun description(tool: PhoneTool): String = when (tool) {
        PhoneTool.LOCATION ->
            "사용자 휴대폰의 지금 위치. 사용자가 있는 곳이 필요할 때(날씨, 길 찾기, 주변 장소) 쓴다. 위도·경도·정확도(m)·잡은 시각을 돌려준다."
        PhoneTool.NOTIFICATIONS_RECENT ->
            "사용자 휴대폰에 최근 온 알림(메신저, 메일, 앱 소식) 목록. 새 소식이나 놓친 연락을 볼 때 쓴다. 최근 것부터 id·앱·제목·글 앞부분·시각·답장 가능 여부를 돌려준다."
        PhoneTool.NOTIFICATIONS_GET ->
            "알림 하나의 전체 글(메신저 알림이면 최근 메시지와 보낸 사람까지). notifications.recent가 준 id로 부른다."
        PhoneTool.NOTIFICATIONS_REPLY ->
            "답장할 수 있는 알림(can_reply가 true인 메신저 알림)에 사용자 대신 답장을 보낸다. 사용자가 시켰을 때만 쓴다. 휴대폰에서 사용자가 확인한 뒤 보낼 수 있다."
    }

    private fun params(tool: PhoneTool): JsonObject = buildJsonObject {
        when (tool) {
            PhoneTool.LOCATION ->
                put("precise", "boolean, 정밀 위치(기본 false). 휴대폰이 대략적인 위치만 허용했으면 대략적인 값이 온다")
            PhoneTool.NOTIFICATIONS_RECENT -> {
                put("limit", "1~${NoticeQuery.MAX_LIMIT}(기본 ${NoticeQuery.DEFAULT_LIMIT})")
                put("app", "앱 이름이나 패키지 이름의 일부(선택)")
                put("query", "제목·글에 든 말(선택)")
                put("hours", "몇 시간 전까지(1~${NoticeQuery.MAX_HOURS}, 기본 ${NoticeQuery.DEFAULT_HOURS})")
                put("active", "boolean, 알림 창에 아직 남은 것만(기본 false)")
            }
            PhoneTool.NOTIFICATIONS_GET -> put("id", "notifications.recent가 준 id")
            PhoneTool.NOTIFICATIONS_REPLY -> {
                put("id", "notifications.recent가 준 id(can_reply가 true인 것)")
                put("text", "보낼 글(${ReplyRules.MAX_CHARS}자까지)")
            }
        }
    }
}

/**
 * 루시아가 준 인자. 모양은 자유이므로(사양 7.2) 너그럽게 읽는다: 숫자나 불이 글로 와도 받고, 범위를 넘으면 가장자리로 맞춘다.
 * 틀린 값은 기본값으로 둔다. 꼭 있어야 하는 값이 없을 때만 부르는 쪽이 `bad_params`로 답한다.
 */
class ToolParams(private val params: JsonElement?) {

    private fun value(name: String): JsonPrimitive? = (params as? JsonObject)?.get(name) as? JsonPrimitive

    fun bool(name: String, default: Boolean): Boolean {
        val v = value(name) ?: return default
        return v.booleanOrNull ?: when (v.contentOrNull?.trim()?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
    }

    fun int(name: String, default: Int, range: IntRange): Int {
        val v = value(name) ?: return default
        val number = v.doubleOrNull ?: v.contentOrNull?.trim()?.toDoubleOrNull() ?: return default
        if (number.isNaN()) return default
        return number.roundToInt().coerceIn(range)
    }

    /** 앞뒤 공백을 지운 글. 없거나 비었으면 null. */
    fun string(name: String): String? = value(name)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** 루시아에게 보이는 결과의 크기(사양 7.2: 8 KB까지 보이고 넘으면 잘린다). 잘린 JSON을 보이지 않으려고 앱이 먼저 줄인다. */
object ToolBudget {

    /**
     * 데몬은 결과를 들여쓴 JSON(두 칸)으로 바꿔 8000바이트에서 자른다. 그보다 조금 작게 맞춘다. 한글은 한 자에 3바이트다.
     */
    const val RESULT_BYTES = 7_600

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** 데몬이 세는 것과 같은 들여쓴 JSON의 바이트 수. */
    fun size(element: JsonElement): Int = pretty.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8).size

    /**
     * [items]의 앞에서부터 [render]한 결과가 [budget] 안에 드는 만큼만 담는다. 돌려주는 것은 (결과, 담은 개수)다.
     * 하나도 들지 않으면 빈 목록으로 그린다.
     */
    fun <T> fit(items: List<T>, budget: Int = RESULT_BYTES, render: (List<T>, Boolean) -> JsonObject): Pair<JsonObject, Int> {
        var count = items.size
        while (count > 0) {
            val shown = render(items.subList(0, count), count < items.size)
            if (size(shown) <= budget) return shown to count
            // 크게 넘치면 한 번에 여럿을 덜어 낸다(목록은 50개까지라 몇 번이면 맞는다).
            count = if (count > 8) count * 3 / 4 else count - 1
        }
        return render(emptyList(), items.isNotEmpty()) to 0
    }
}

/** 글을 [max] 자(코드 포인트)까지 자른다. 잘렸으면 끝에 `…`를 붙인다. */
internal fun String.clip(max: Int): String {
    if (codePointCount(0, length) <= max) return this
    val end = offsetByCodePoints(0, max)
    return substring(0, end).trimEnd() + "…"
}
