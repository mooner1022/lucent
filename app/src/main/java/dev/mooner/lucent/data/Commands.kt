package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.CommandInfo
import dev.mooner.lucent.protocol.ErrorCodes

/**
 * 명령(`commands`·`command`, 사양 6.3·6.4)을 화면에 옮길 때의 규칙. 지금은 `권한`과 `모델` 둘이고 더 늘 수 있다.
 * 모르는 명령도 선택지를 고르는 모양으로 보인다.
 */
object Commands {
    const val MODE = "권한"
    const val MODEL = "모델"

    /** `session.compact`에 주는 값: 대화 맥락이 이보다 작으면 데몬이 정리하지 않는다(변경사항 문서가 권하는 값). */
    const val COMPACT_MIN_TOKENS = 80_000

    /**
     * 지금 값. 서버가 알려 준 `current`를 쓴다(선택지 가운데 같은 것을 대소문자 없이 찾는다). `current`가 없는 예전 데몬이면
     * `권한`에 한해 답의 글에서 읽어 본다([CommandReply]). 다른 명령의 글은 풀어 읽지 않는다.
     */
    fun current(command: CommandInfo, text: String?): String? {
        val told = command.current
        if (told != null) return command.options.firstOrNull { it.equals(told, ignoreCase = true) } ?: told
        return if (command.name == MODE) CommandReply.current(text, command.options) else null
    }

    /** 바꾼 뒤 다시 읽은 목록으로, 고른 값이 정말 적용됐는지 본다(모르는 값도 `ok`로 답이 오므로 답만으로는 알 수 없다). */
    fun applied(fresh: CommandInfo?, picked: String): Boolean? {
        val now = fresh?.current ?: return null
        return now.equals(picked, ignoreCase = true)
    }

    /** 선택지를 화면에 보일 이름. 모델의 별칭은 풀어 쓰고, 모르는 값은 값 그대로 보인다(목록은 데몬이 정한다). */
    fun optionLabel(command: CommandInfo, option: String): String =
        if (command.name == MODEL) MODEL_LABELS[option.lowercase()] ?: option else option

    /**
     * 모델을 [to]로 바꾸기 전에 대화 정리를 먼저 권할지. 정리는 지금의 모델이 하므로 더 큰 모델로 갈 때만 이득이다.
     * 더 작은 모델로 가거나 크기를 모르는 값이면 권하지 않는다.
     *
     * 지금 값이 `default`(데몬 설정 파일의 모델)이면 무엇인지 알 수 없다. Sonnet쯤으로 본다(변경사항 문서의 예가 그렇다).
     */
    fun compactFirstByDefault(from: String?, to: String): Boolean {
        val target = MODEL_RANK[to.lowercase()] ?: return false
        val now = MODEL_RANK[from?.lowercase()] ?: ASSUMED_DEFAULT_RANK
        return target > now
    }

    private val MODEL_LABELS = mapOf("fable" to "Fable", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku", "default" to "기본")
    private val MODEL_RANK = mapOf("fable" to 4, "opus" to 3, "sonnet" to 2, "haiku" to 1)
    private const val ASSUMED_DEFAULT_RANK = 2
}

/** `session.compact`를 한 번 부른 뒤 할 일(변경사항 문서 1·3장의 표). */
enum class CompactStep {
    /** 끝났다(무엇이든 `ok: true`). 모델을 바꿔도 된다. */
    PROCEED,

    /** 이미 정리가 큐에 있거나 돌고 있다. 몇 초 뒤 다시 부른다(끝났으면 `unchanged`가 온다). */
    RETRY_LATER,

    /** 연결이 끊겼거나 데몬이 멈추는 중이다. 정리는 데몬에서 계속된다. 다시 연결된 뒤 한 번 더 부른다. */
    WAIT_ONLINE,

    /** 정리하지 못했다. 모델을 바꾸지 말고 사용자에게 묻는다. */
    ASK;

    companion object {
        /** [errorCode]는 `ok: false`일 때의 코드, 성공이면 null. 모르는 오류는 묻는 쪽으로 둔다. */
        fun of(errorCode: String?): CompactStep = when (errorCode) {
            null -> PROCEED
            ErrorCodes.BUSY -> RETRY_LATER
            ErrorCodes.CLOSED -> WAIT_ONLINE
            else -> ASK
        }
    }
}
