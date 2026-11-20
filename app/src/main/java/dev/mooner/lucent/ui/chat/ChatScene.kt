package dev.mooner.lucent.ui.chat

import dev.mooner.lucent.data.Route
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.ui.sky.Mood

/**
 * 지금 대화 화면이 무엇을 보여야 하는지(docs/design.md 7장).
 *
 * 화면의 "상황에 맞춰 변하는 부분"은 모두 이 값 하나에서 나온다. 화면은 연결·대화·입력의 지금 상태를 [of]에 넣고,
 * 나온 값대로 머리말(기색·상태 줄), 상황 자리, 입력창을 그린다. 조건이 흩어지지 않게 하려는 것이고, 그래서 시험할 수 있다.
 */
data class ChatScene(
    val mood: Mood,
    val status: Status,
    val header: Header,
    val slot: Slot,
    val hint: Hint,
    /** 지금 말을 보낼 수 있다. 아니어도 글은 쓸 수 있다. */
    val canSend: Boolean,
    /** 토큰·주소 문제로 입력창 자리에 "다시 입력"이 나와야 하면 그 오류 코드. */
    val blockedCode: String?,
    /** 목록의 끝으로 가는 단추. */
    val jump: Jump = Jump.NONE,
) {
    /** 머리말의 상태 줄. 한 번에 한 가지만 말한다. */
    sealed interface Status {
        data class Online(val route: Route) : Status
        /** 루시아가 지금 하는 일(`note`의 글). */
        data class Working(val note: String) : Status
        data object Typing : Status
        data class Pending(val count: Int) : Status
        data object Connecting : Status
        data class Offline(val retryAt: Long) : Status
        data class Throttled(val until: Long) : Status
        data class Blocked(val code: String) : Status
    }

    /** 머리말의 크기. */
    enum class Header {
        /** 이름과 상태 줄만. 대화하는 동안의 모양이다. */
        COMPACT,
        /** 한동안 조용하다 돌아왔을 때: 머리말이 커지며 시간대 인사를 건넨다. 대화가 시작되면 다시 줄어든다. */
        GREETING,
    }

    /** 입력창 바로 위의 상황 자리. 지금 가장 필요한 것 하나만 놓인다. */
    sealed interface Slot {
        data object None : Slot
        /** 연결이 끊겼다. [retryAt]까지 기다리거나 바로 다시 시도한다. */
        data class Reconnect(val retryAt: Long, val throttled: Boolean) : Slot
        /** 화면 밖에 답을 기다리는 카드가 있다. */
        data class Approval(val count: Int, val title: String) : Slot
        /** 한동안 조용했다. 지금 시간대에 꺼낼 만한 말을 권한다. */
        data object Suggest : Slot
    }

    enum class Hint { NORMAL, PENDING, OFFLINE }

    /** 위로 올려 보는 동안 목록 위에 뜨는 "끝으로" 단추. */
    enum class Jump {
        NONE,
        /** 끝에서 멀리 올라와 있다: 화살표만. */
        DOWN,
        /** 올려 보는 사이 새 말이 왔다: "새 메시지". */
        NEW,
    }

    /** [of]에 넣는 지금 상태. */
    data class Input(
        val link: LinkState,
        val typing: Boolean = false,
        /** 루시아가 지금 하는 일. 다시 받은(replay) 안내는 넣지 않는다. */
        val activity: String? = null,
        val pendingCount: Int = 0,
        val pendingTitle: String = "",
        /** 가장 오래된 대기 카드가 화면에 온전히 보인다. */
        val pendingOnScreen: Boolean = false,
        /** 입력창에 쓰던 글이나 첨부가 있다. */
        val hasDraft: Boolean = false,
        /** 대화의 맨 끝을 보고 있다. */
        val atBottom: Boolean = true,
        /** 키보드가 올라와 있다. */
        val keyboard: Boolean = false,
        /** 마지막 말의 시각. 대화가 비었으면 null. */
        val lastItemAt: Long? = null,
        val now: Long = 0,
        /** 끝에서 한 화면 넘게 올라와 있다. */
        val farFromEnd: Boolean = false,
        /** 올려 보는 사이 새 말이 왔고 아직 끝으로 가지 않았다. */
        val unseen: Boolean = false,
    )

    companion object {
        /** 이만큼 조용했으면 "돌아왔다"고 보고 말을 권한다. */
        const val QUIET_MS = 30 * 60_000L

        fun of(i: Input): ChatScene {
            val link = i.link
            val online = link is LinkState.Online
            val pending = i.pendingCount > 0
            val busy = i.typing || !i.activity.isNullOrBlank()

            val mood = when {
                link is LinkState.Connecting || link is LinkState.Idle -> Mood.CONNECTING
                !online -> Mood.OFFLINE
                busy -> Mood.ACTIVE
                pending -> Mood.ATTENTION
                else -> Mood.CALM
            }

            val status: Status = when (link) {
                is LinkState.Online -> when {
                    !i.activity.isNullOrBlank() -> Status.Working(i.activity)
                    i.typing -> Status.Typing
                    pending -> Status.Pending(i.pendingCount)
                    else -> Status.Online(link.route)
                }
                is LinkState.Connecting, LinkState.Idle -> Status.Connecting
                is LinkState.Waiting -> Status.Offline(link.retryAt)
                is LinkState.Throttled -> Status.Throttled(link.until)
                is LinkState.Blocked -> Status.Blocked(link.code)
            }

            val quiet = i.lastItemAt == null || i.now - i.lastItemAt >= QUIET_MS
            // 조용하다 돌아온 순간: 아직 아무것도 쓰지 않았고, 루시아도 가만히 있고, 대화의 끝을 보고 있다.
            val returned = online && quiet && !busy && !i.hasDraft && !i.keyboard && i.atBottom
            val slot: Slot = when {
                link is LinkState.Blocked -> Slot.None
                link is LinkState.Waiting -> Slot.Reconnect(link.retryAt, throttled = false)
                link is LinkState.Throttled -> Slot.Reconnect(link.until, throttled = true)
                !online -> Slot.None
                pending && !i.pendingOnScreen -> Slot.Approval(i.pendingCount, i.pendingTitle)
                !pending && returned -> Slot.Suggest
                else -> Slot.None
            }

            val hint = when {
                !online -> Hint.OFFLINE
                pending -> Hint.PENDING
                else -> Hint.NORMAL
            }

            return ChatScene(
                mood = mood,
                status = status,
                header = if (returned) Header.GREETING else Header.COMPACT,
                slot = slot,
                hint = hint,
                canSend = online,
                blockedCode = (link as? LinkState.Blocked)?.code,
                jump = when {
                    i.atBottom -> Jump.NONE
                    i.unseen -> Jump.NEW
                    i.farFromEnd -> Jump.DOWN
                    else -> Jump.NONE
                },
            )
        }
    }
}
