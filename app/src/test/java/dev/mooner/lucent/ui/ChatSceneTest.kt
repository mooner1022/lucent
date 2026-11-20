package dev.mooner.lucent.ui

import dev.mooner.lucent.data.Route
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.Welcome
import dev.mooner.lucent.ui.chat.ChatScene
import dev.mooner.lucent.ui.chat.ChatScene.Header
import dev.mooner.lucent.ui.chat.ChatScene.Hint
import dev.mooner.lucent.ui.chat.ChatScene.Input
import dev.mooner.lucent.ui.chat.ChatScene.Slot
import dev.mooner.lucent.ui.chat.ChatScene.Status
import dev.mooner.lucent.ui.sky.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면의 "상황에 맞춰 변하는 부분"이 상황마다 무엇이 되는지(docs/design.md 7장의 표). */
class ChatSceneTest {

    private val now = 1_800_000_000_000L
    private val online = LinkState.Online(Welcome(), Route.HOME)

    /** 방금까지 대화하던 중인 평소 상태. */
    private fun input(
        link: LinkState = online, typing: Boolean = false, activity: String? = null,
        pending: Int = 0, pendingOnScreen: Boolean = false, draft: Boolean = false, atBottom: Boolean = true,
        keyboard: Boolean = false, lastItemAt: Long? = now - 60_000,
    ) = Input(link, typing, activity, pending, if (pending > 0) "메일 보내기" else "", pendingOnScreen, draft, atBottom, keyboard, lastItemAt, now)

    @Test fun `평소 - 고요하고 상황 자리는 비어 있다`() {
        val s = ChatScene.of(input())
        assertEquals(Mood.CALM, s.mood)
        assertEquals(Status.Online(Route.HOME), s.status)
        assertEquals(Header.COMPACT, s.header)
        assertEquals(Slot.None, s.slot)
        assertEquals(Hint.NORMAL, s.hint)
        assertTrue(s.canSend)
        assertNull(s.blockedCode)
    }

    @Test fun `루시아가 쓰는 중이거나 일하는 중이면 기색이 살아난다 - 상태 줄은 하는 일이 먼저`() {
        val typing = ChatScene.of(input(typing = true))
        assertEquals(Mood.ACTIVE, typing.mood)
        assertEquals(Status.Typing, typing.status)

        val working = ChatScene.of(input(typing = true, activity = "메일함을 확인하고 있어요"))
        assertEquals(Mood.ACTIVE, working.mood)
        assertEquals(Status.Working("메일함을 확인하고 있어요"), working.status)
    }

    @Test fun `승인 대기 - 카드가 화면 밖이면 상황 자리가 그리로 이끈다`() {
        val away = ChatScene.of(input(pending = 2, pendingOnScreen = false))
        assertEquals(Mood.ATTENTION, away.mood)
        assertEquals(Status.Pending(2), away.status)
        assertEquals(Slot.Approval(2, "메일 보내기"), away.slot)
        assertEquals(Hint.PENDING, away.hint)

        // 이미 보고 있는 카드를 가리키지 않는다.
        assertEquals(Slot.None, ChatScene.of(input(pending = 1, pendingOnScreen = true)).slot)
    }

    @Test fun `일하는 중에는 승인 대기보다 하는 일이 상태 줄에 보인다 - 상황 자리는 그대로 카드로 이끈다`() {
        val s = ChatScene.of(input(typing = true, pending = 1))
        assertEquals(Mood.ACTIVE, s.mood)
        assertEquals(Status.Typing, s.status)
        assertEquals(Slot.Approval(1, "메일 보내기"), s.slot)
    }

    @Test fun `한동안 조용했으면 말을 권한다 - 대화가 비어 있을 때도`() {
        assertEquals(Slot.Suggest, ChatScene.of(input(lastItemAt = now - ChatScene.QUIET_MS)).slot)
        assertEquals(Slot.Suggest, ChatScene.of(input(lastItemAt = null)).slot)
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = now - ChatScene.QUIET_MS + 1)).slot)
    }

    @Test fun `권하는 말은 방해가 되면 나오지 않는다`() {
        val quiet = now - ChatScene.QUIET_MS
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = quiet, draft = true)).slot)
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = quiet, atBottom = false)).slot)
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = quiet, typing = true)).slot)
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = quiet, pending = 1, pendingOnScreen = true)).slot)
    }

    @Test fun `연결이 끊기면 상황 자리가 다시 시도를 권하고 입력창이 이유를 말한다`() {
        val s = ChatScene.of(input(link = LinkState.Waiting("닿지 않음", now + 8_000), pending = 1, typing = true))
        assertEquals(Mood.OFFLINE, s.mood)
        assertEquals(Status.Offline(now + 8_000), s.status)
        assertEquals(Slot.Reconnect(now + 8_000, throttled = false), s.slot)
        assertEquals(Hint.OFFLINE, s.hint)
        assertFalse(s.canSend)
    }

    @Test fun `서버가 막았으면 풀릴 때를 알린다`() {
        val s = ChatScene.of(input(link = LinkState.Throttled(now + 600_000)))
        assertEquals(Status.Throttled(now + 600_000), s.status)
        assertEquals(Slot.Reconnect(now + 600_000, throttled = true), s.slot)
    }

    @Test fun `연결하는 중에는 기다린다 - 상황 자리도 비운다`() {
        for (link in listOf(LinkState.Connecting(Route.HOME), LinkState.Idle)) {
            val s = ChatScene.of(input(link = link, lastItemAt = null))
            assertEquals(Mood.CONNECTING, s.mood)
            assertEquals(Status.Connecting, s.status)
            assertEquals(Slot.None, s.slot)
            assertFalse(s.canSend)
        }
    }

    @Test fun `토큰이 문제면 입력창 자리에 다시 입력이 나온다`() {
        val s = ChatScene.of(input(link = LinkState.Blocked(ErrorCodes.UNAUTHORIZED)))
        assertEquals(Mood.OFFLINE, s.mood)
        assertEquals(Status.Blocked(ErrorCodes.UNAUTHORIZED), s.status)
        assertEquals(ErrorCodes.UNAUTHORIZED, s.blockedCode)
        assertEquals(Slot.None, s.slot)
    }

    @Test fun `조용하다 돌아오면 머리말이 인사한다 - 대화가 시작되면 줄어든다`() {
        val quiet = now - ChatScene.QUIET_MS
        assertEquals(Header.GREETING, ChatScene.of(input(lastItemAt = quiet)).header)
        assertEquals(Header.GREETING, ChatScene.of(input(lastItemAt = null)).header)
        // 승인 대기가 있어도 인사는 한다(상태 줄과 상황 자리가 대기를 말한다).
        assertEquals(Header.GREETING, ChatScene.of(input(lastItemAt = quiet, pending = 1)).header)

        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = now - 60_000)).header)
        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = quiet, draft = true)).header)
        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = quiet, keyboard = true)).header)
        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = quiet, typing = true)).header)
        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = quiet, atBottom = false)).header)
        assertEquals(Header.COMPACT, ChatScene.of(input(lastItemAt = quiet, link = LinkState.Waiting("닿지 않음", now + 1_000))).header)
    }

    @Test fun `키보드가 올라와 있으면 권하는 말도 물러난다`() {
        assertEquals(Slot.None, ChatScene.of(input(lastItemAt = now - ChatScene.QUIET_MS, keyboard = true)).slot)
    }

    @Test fun `끝으로 가는 단추 - 멀리 올라와 있으면 화살표, 그사이 새 말이 왔으면 새 메시지`() {
        fun jump(atBottom: Boolean, far: Boolean, unseen: Boolean) =
            ChatScene.of(input(atBottom = atBottom).copy(farFromEnd = far, unseen = unseen)).jump
        assertEquals(ChatScene.Jump.NONE, jump(atBottom = true, far = false, unseen = false))
        // 조금 올린 정도로는 뜨지 않는다.
        assertEquals(ChatScene.Jump.NONE, jump(atBottom = false, far = false, unseen = false))
        assertEquals(ChatScene.Jump.DOWN, jump(atBottom = false, far = true, unseen = false))
        assertEquals(ChatScene.Jump.NEW, jump(atBottom = false, far = false, unseen = true))
        assertEquals(ChatScene.Jump.NEW, jump(atBottom = false, far = true, unseen = true))
        // 끝에 닿으면 사라진다.
        assertEquals(ChatScene.Jump.NONE, jump(atBottom = true, far = false, unseen = true))
    }
}
