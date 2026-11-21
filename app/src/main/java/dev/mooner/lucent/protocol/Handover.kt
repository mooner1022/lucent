package dev.mooner.lucent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 브라우저 화면 넘겨받기(사양 6.7·8.9, 결정 0063): 로그인 도중 사이트가 사람 확인(캡차 등)을 띄우면 사용자가 그 화면을
 * 직접 보고 푼다. 토큰에 `browser_handover` 범위가 있고 브라우저를 켠 데몬에만 있다([Handover.available]).
 */
@Serializable
data class HandoverState(
    val active: Boolean = false,
    /** 남은 초. 넘겨받는 중이 아니면 null. */
    @SerialName("left_seconds") val leftSeconds: Int? = null,
    @SerialName("max_seconds") val maxSeconds: Int = 900,
    val tabs: List<HandoverTab> = emptyList(),
)

/** 넘겨받은 브라우저의 탭. [id]는 풀어 읽지 않고 화면 받기와 입력에 그대로 쓴다. */
@Serializable
data class HandoverTab(val id: String = "", val url: String = "", val title: String = "")

/** 넘겨받은 화면에 보내는 입력 하나(`browser.handover.input`). */
sealed interface HandoverInput {
    /** [x], [y]는 받은 그림 위의 화소 좌표다. */
    class Click(val x: Int, val y: Int) : HandoverInput
    /** 지금 초점이 있는 칸에 넣는다. 비밀번호일 수 있으므로 보내는 것 말고 어디에도 두지 않는다. */
    class Text(val text: String) : HandoverInput
    class Key(val key: String) : HandoverInput
    /** 양수는 아래로. */
    class Scroll(val dy: Int) : HandoverInput
}

object Handover {
    const val STATE = "browser.handover.state"
    const val START = "browser.handover.start"
    const val INPUT = "browser.handover.input"
    const val END = "browser.handover.end"

    /** `text` 입력의 길이 한도(자). */
    const val MAX_TEXT_CHARS = 2000
    /** 데몬이 아는 키. */
    val KEYS = listOf("enter", "tab", "backspace", "escape", "up", "down")

    /** 이 연결에서 화면 넘겨받기를 쓸 수 있는가. 넷이 다 `welcome.methods`에 있어야 한다. */
    fun available(welcome: Welcome?): Boolean =
        welcome != null && listOf(STATE, START, INPUT, END).all(welcome::canCall)

    fun params(tab: String, input: HandoverInput): JsonObject = buildJsonObject {
        put("tab", tab)
        when (input) {
            is HandoverInput.Click -> {
                put("kind", "click")
                put("x", input.x)
                put("y", input.y)
            }
            is HandoverInput.Text -> {
                put("kind", "text")
                put("text", input.text)
            }
            is HandoverInput.Key -> {
                put("kind", "key")
                put("key", input.key)
            }
            is HandoverInput.Scroll -> {
                put("kind", "scroll")
                put("dy", input.dy)
            }
        }
    }
}
