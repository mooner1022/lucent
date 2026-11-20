package dev.mooner.lucent.ui

import dev.mooner.lucent.data.Kind
import dev.mooner.lucent.data.Sender
import dev.mooner.lucent.data.StickerRef
import dev.mooner.lucent.data.StoredItem
import dev.mooner.lucent.protocol.Card
import dev.mooner.lucent.ui.chat.Row
import dev.mooner.lucent.ui.chat.Source
import dev.mooner.lucent.ui.chat.buildRows
import dev.mooner.lucent.ui.chat.isLongForm
import dev.mooner.lucent.ui.chat.sourceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatRowsTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private var nextId = 1L

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun item(
        at: Long, sender: Sender = Sender.LUCIA, kind: Kind = Kind.TEXT, body: String = "글",
        ref: String? = null, card: Card? = null, conversation: String? = null,
    ) = StoredItem(id = nextId++, key = "k$nextId", at = at, sender = sender, kind = kind, body = body, ref = ref, card = card, conversation = conversation)

    private fun rows(vararg items: StoredItem, typing: Boolean = false, pressing: Set<String> = emptySet()) =
        buildRows(items.toList(), typing, pressing, zone)

    @Test fun `날짜가 바뀌면 구분선이 들어간다`() {
        val r = rows(item(at(19, 23, 59)), item(at(20, 0, 1)))
        assertEquals(listOf("Day", "Message", "Day", "Message"), r.map { it::class.java.simpleName })
    }

    @Test fun `같은 쪽의 말이 3분 안에 이어지면 한 묶음이다 - 아바타는 첫 줄, 시각은 마지막 줄`() {
        val r = rows(item(at(19, 10, 0)), item(at(19, 10, 1)), item(at(19, 10, 2)))
            .filterIsInstance<Row.Message>()
        assertEquals(listOf(true, false, false), r.map { it.avatar })
        assertEquals(listOf(false, false, true), r.map { it.time })
    }

    @Test fun `3분을 넘기면 묶음이 갈린다`() {
        val r = rows(item(at(19, 10, 0)), item(at(19, 10, 5))).filterIsInstance<Row.Message>()
        assertEquals(listOf(true, true), r.map { it.avatar })
        assertEquals(listOf(true, true), r.map { it.time })
    }

    @Test fun `내 말과 루시아의 말은 묶이지 않는다`() {
        val r = rows(item(at(19, 10, 0), Sender.USER), item(at(19, 10, 0, 30), Sender.LUCIA)).filterIsInstance<Row.Message>()
        assertTrue(r.all { it.avatar && it.time })
    }

    @Test fun `진행 안내와 카드는 묶음을 끊는다`() {
        val r = rows(
            item(at(19, 10, 0)),
            item(at(19, 10, 0, 10), kind = Kind.NOTE, body = "찾는 중"),
            item(at(19, 10, 0, 20)),
        )
        val messages = r.filterIsInstance<Row.Message>()
        assertTrue(messages[1].avatar)
        assertTrue(r.any { it is Row.Note })
    }

    @Test fun `카드는 누르는 중이면 표시한다`() {
        val card = Card(tone = "pending")
        val r = rows(item(at(19, 10, 0), kind = Kind.CARD, ref = "ref-1", card = card), pressing = setOf("ref-1"))
        assertTrue(r.filterIsInstance<Row.CardRow>().single().pressing)
        val idle = rows(item(at(19, 10, 0), kind = Kind.CARD, ref = "ref-1", card = card))
        assertFalse(idle.filterIsInstance<Row.CardRow>().single().pressing)
    }

    @Test fun `쓰는 중이면 맨 끝에 붙는다`() {
        val r = rows(item(at(19, 10, 0)), typing = true)
        assertTrue(r.last() === Row.Typing)
        assertFalse(rows(item(at(19, 10, 0))).any { it === Row.Typing })
    }

    @Test fun `출처 표시 - 이 앱의 대화는 없고 Discord와 데몬은 있다`() {
        val own = "app:cl-1"
        assertNull(sourceOf(item(0, conversation = own), own))
        assertNull(sourceOf(item(0, conversation = null), own))
        assertEquals(Source.DISCORD, sourceOf(item(0, conversation = "discord:dm:123"), own))
        assertEquals(Source.OTHER_APP, sourceOf(item(0, conversation = "app:cl-2"), own))
        assertEquals(Source.DAEMON, sourceOf(item(0, sender = Sender.DAEMON), own))
    }

    @Test fun `출처가 다른 말은 같은 묶음이 아니다`() {
        val own = "app:cl-1"
        val r = buildRows(
            listOf(item(at(19, 10, 0), conversation = own), item(at(19, 10, 1), conversation = "discord:dm:1")),
            typing = false, pressing = emptySet(), zone = zone, ownConversation = own,
        ).filterIsInstance<Row.Message>()
        assertTrue(r[1].avatar)
    }

    @Test fun `잇달아 온 진행 안내는 한 줄로 합친다 - 마지막 것이 보이고 열쇠는 첫 안내다`() {
        val a = item(at(19, 10, 0, 1), kind = Kind.NOTE, body = "일정을 보는 중")
        val b = item(at(19, 10, 0, 2), kind = Kind.NOTE, body = "메일을 보는 중")
        val one = rows(item(at(19, 10, 0)), a).filterIsInstance<Row.Note>().single()
        val two = rows(item(at(19, 10, 0)), a, b).filterIsInstance<Row.Note>().single()
        assertEquals(listOf("일정을 보는 중", "메일을 보는 중"), two.items.map { it.body })
        assertEquals("메일을 보는 중", two.latest.body)
        // 안내가 더 붙어도 같은 줄이다(스크롤 위치와 펼침 상태가 이 열쇠에 걸려 있다).
        assertEquals(one.key, two.key)
    }

    @Test fun `사이에 말이 끼면 진행 안내는 합치지 않는다`() {
        val r = rows(
            item(at(19, 10, 0, 1), kind = Kind.NOTE, body = "찾는 중"),
            item(at(19, 10, 0, 2)),
            item(at(19, 10, 0, 3), kind = Kind.NOTE, body = "쓰는 중"),
        ).filterIsInstance<Row.Note>()
        assertEquals(2, r.size)
    }

    @Test fun `날짜가 바뀌면 진행 안내는 합치지 않는다`() {
        val r = rows(
            item(at(19, 23, 59), kind = Kind.NOTE, body = "어제"),
            item(at(20, 0, 1), kind = Kind.NOTE, body = "오늘"),
        ).filterIsInstance<Row.Note>()
        assertEquals(2, r.size)
    }

    @Test fun `일하는 중이면 맨 끝의 안내만 살아 있다`() {
        val note = item(at(19, 10, 0, 1), kind = Kind.NOTE, body = "찾는 중")
        val live = buildRows(listOf(note), typing = true, pressing = emptySet(), zone = zone, working = true)
        assertTrue(live.filterIsInstance<Row.Note>().single().live)
        assertTrue(live.last() === Row.Typing)

        val idle = buildRows(listOf(note), typing = false, pressing = emptySet(), zone = zone, working = false)
        assertFalse(idle.filterIsInstance<Row.Note>().single().live)

        // 안내 뒤에 말이 왔으면 그 안내는 지난 일이다.
        val past = buildRows(listOf(note, item(at(19, 10, 0, 5))), typing = false, pressing = emptySet(), zone = zone, working = true)
        assertFalse(past.filterIsInstance<Row.Note>().single().live)
    }

    @Test fun `긴 글은 넓은 지면을 쓴다 - 짧은 대화와 내 말은 말풍선 그대로`() {
        assertFalse(isLongForm("오늘 3시에 회의가 있어요."))
        assertFalse(isLongForm("두 가지예요.\n- 서류\n- 치과"))
        assertTrue(isLongForm("할 일\n1. 서류 제출\n2. 치과 예약\n3. 프린터 청소"))
        assertTrue(isLongForm("이렇게 해요.\n```\ncargo run\n```"))
        assertTrue(isLongForm("## 오늘 정리\n회의가 두 개 있어요."))
        assertTrue(isLongForm("| 항목 | 값 |\n|---|---|\n| 식비 | 32만 |"))
        assertTrue(isLongForm("가".repeat(300)))

        val long = "- 하나\n- 둘\n- 셋"
        val r = rows(item(at(19, 10, 0), body = long), item(at(19, 10, 5), Sender.USER, body = long)).filterIsInstance<Row.Message>()
        assertEquals(listOf(true, false), r.map { it.wide })
    }

    @Test fun `스티커만 있는 줄은 바로 앞의 글과 한 묶음으로 이어진다`() {
        val r = rows(
            item(at(19, 10, 0), body = "정말 축하해요!"),
            item(at(19, 10, 0, 1), body = "").copy(sticker = StickerRef("s_1", "축하")),
            item(at(19, 10, 1), Sender.USER, body = "").copy(sticker = StickerRef("s_2", "꾸벅")),
        ).filterIsInstance<Row.Message>()
        // 아바타는 글에, 시각은 스티커 아래에.
        assertEquals(listOf(true, false, true), r.map { it.avatar })
        assertEquals(listOf(false, true, true), r.map { it.time })
        assertTrue(r.none { it.wide })
        assertEquals(listOf(null, "축하", "꾸벅"), r.map { it.item.sticker?.name })
    }
}
