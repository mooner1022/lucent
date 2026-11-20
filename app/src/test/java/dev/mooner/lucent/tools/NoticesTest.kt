package dev.mooner.lucent.tools

import android.app.Notification
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NoticesTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = 1_763_600_000_000L // 2025-11-20 무렵

    private fun notice(
        id: Long,
        title: String = "민수",
        text: String = "곧 도착해",
        app: String = "카카오톡",
        pkg: String = "com.kakao.talk",
        messages: List<NoticeMessage> = emptyList(),
        ago: Long = 60_000,
        removed: Boolean = false,
        conversation: String? = null,
    ) = Notice(
        id = id,
        draft = NoticeDraft(key = "k$id", pkg = pkg, app = app, title = title, text = text, conversation = conversation, messages = messages, postedAt = now - ago),
        updatedAt = now - ago,
        removedAt = if (removed) now else null,
    )

    @Test fun `소식이 아닌 알림은 기록하지 않는다`() {
        val own = "dev.mooner.lucent"
        assertTrue(NoticeFilter.records("com.kakao.talk", own, 0, Notification.CATEGORY_MESSAGE))
        assertFalse(NoticeFilter.records(own, own, 0, null))
        assertFalse(NoticeFilter.records("a", own, Notification.FLAG_GROUP_SUMMARY, null))
        assertFalse(NoticeFilter.records("a", own, Notification.FLAG_ONGOING_EVENT, null))
        assertFalse(NoticeFilter.records("a", own, Notification.FLAG_FOREGROUND_SERVICE, null))
        assertFalse(NoticeFilter.records("a", own, 0, Notification.CATEGORY_TRANSPORT))
        assertFalse(NoticeFilter.records("a", own, 0, Notification.CATEGORY_PROGRESS))
        assertFalse(NoticeFilter.hasContent(NoticeDraft(key = "k", pkg = "a", app = "a", title = " ", text = "", postedAt = 0)))
    }

    @Test fun `조건은 앱 이름이나 패키지, 글과 메시지, 시간, 떠 있는지로 거른다`() {
        val q = NoticeQuery(app = "kakao")
        assertTrue(q.matches(notice(1), now))
        assertTrue(NoticeQuery(app = "카카오").matches(notice(1), now))
        assertFalse(NoticeQuery(app = "gmail").matches(notice(1), now))
        assertTrue(NoticeQuery(query = "도착").matches(notice(1), now))
        assertTrue(NoticeQuery(query = "회의").matches(notice(1, messages = listOf(NoticeMessage("팀장", "회의 3시", now))), now))
        assertFalse(NoticeQuery(query = "회의").matches(notice(1), now))
        assertFalse(NoticeQuery(hours = 1).matches(notice(1, ago = 2 * 3_600_000L), now))
        assertFalse(NoticeQuery(activeOnly = true).matches(notice(1, removed = true), now))
    }

    @Test fun `조건은 인자에서 읽고 범위로 맞춘다`() {
        val q = NoticeQuery.of(ToolParams(kotlinx.serialization.json.buildJsonObject {
            put("limit", kotlinx.serialization.json.JsonPrimitive(99))
            put("hours", kotlinx.serialization.json.JsonPrimitive(100))
        }))
        assertEquals(NoticeQuery.MAX_LIMIT, q.limit)
        assertEquals(NotificationLogRules.KEEP_HOURS, q.hours)
        assertEquals(NoticeQuery(), NoticeQuery.of(ToolParams(null)))
    }

    @Test fun `id는 n을 붙여 주고 둘 다 읽는다`() {
        assertEquals("n42", NoticeResults.idOf(notice(42)))
        assertEquals(42L, NoticeResults.parseId("n42"))
        assertEquals(42L, NoticeResults.parseId(" 42 "))
        assertNull(NoticeResults.parseId("abc"))
        assertNull(NoticeResults.parseId("n0"))
        assertNull(NoticeResults.parseId(null))
    }

    @Test fun `시각은 휴대폰 시간대의 RFC 3339로 적는다`() {
        assertEquals("2025-11-20T09:53:20+09:00", NoticeResults.time(now, seoul))
    }

    @Test fun `목록의 한 줄은 마지막 메시지를 앞부분으로 보인다`() {
        val n = notice(7, conversation = "가족방", messages = listOf(NoticeMessage("엄마", "밥 먹었니", now - 1000), NoticeMessage(null, "응\n먹었어", now)))
        val row = NoticeResults.summary(n, canReply = true, zone = seoul)
        assertEquals("n7", row["id"]!!.jsonPrimitive.content)
        assertEquals("가족방", row["conversation"]!!.jsonPrimitive.content)
        assertEquals("나: 응 먹었어", row["text"]!!.jsonPrimitive.content)
        assertEquals("true", row["can_reply"]!!.jsonPrimitive.content)
        assertEquals("true", row["active"]!!.jsonPrimitive.content)
    }

    @Test fun `자세히는 메시지와 보낸 사람을 모두 준다`() {
        val n = notice(8, messages = listOf(NoticeMessage("민수", "어디야", now), NoticeMessage(null, "가는 중", 0)))
        val d = NoticeResults.detail(n, canReply = false, zone = seoul)
        val messages = d["messages"]!!.jsonArray
        assertEquals("민수", messages[0].jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals(NoticeResults.SELF, messages[1].jsonObject["from"]!!.jsonPrimitive.content)
        assertNull(messages[1].jsonObject["at"])
        assertEquals("com.kakao.talk", d["package"]!!.jsonPrimitive.content)
    }

    @Test fun `목록은 8 KB 안에 들고 더 있으면 알린다`() {
        val many = List(51) { i -> notice(i + 1L, title = "제목".repeat(40), text = "긴 글 ".repeat(200), ago = i * 1000L) }
        val q = NoticeQuery(limit = 50)
        val result = NoticeResults.list(many, q, { true }, seoul)
        val items = result["items"]!!.jsonArray
        assertTrue(items.size in 1 until 50)
        assertTrue(ToolBudget.size(result) <= ToolBudget.RESULT_BYTES)
        assertEquals("true", result["more"]!!.jsonPrimitive.content)
        assertTrue(result.containsKey("note"))
        // 최근 것부터 그대로.
        assertEquals("n1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun `한도보다 하나 더 받았으면 더 있다고 하고 비었으면 그렇게 적는다`() {
        val two = listOf(notice(1), notice(2))
        val r = NoticeResults.list(two, NoticeQuery(limit = 1), { false }, seoul)
        assertEquals(1, r["items"]!!.jsonArray.size)
        assertEquals("true", r["more"]!!.jsonPrimitive.content)
        val empty = NoticeResults.list(emptyList(), NoticeQuery(), { false }, seoul)
        assertEquals(0, empty["items"]!!.jsonArray.size)
        assertEquals("false", empty["more"]!!.jsonPrimitive.content)
        assertTrue(empty.containsKey("note"))
    }

    @Test fun `답장이 가는 곳은 대화 이름, 메신저면 작은 글의 방 이름, 아니면 제목이다`() {
        fun d(title: String, sub: String? = null, conversation: String? = null, category: String? = null, messages: List<NoticeMessage> = emptyList()) =
            NoticeDraft(key = "k", pkg = "p", app = "앱", title = title, text = "", sub = sub, conversation = conversation, category = category, messages = messages, postedAt = 0)
        assertEquals("가족방", NoticeResults.replyTo(d("엄마", sub = "다른 것", conversation = "가족방")))
        // 카카오톡: 제목은 보낸 사람, 작은 글이 단톡방.
        assertEquals("가족방", NoticeResults.replyTo(d("엄마", sub = "가족방", category = Notification.CATEGORY_MESSAGE)))
        assertEquals("가족방", NoticeResults.replyTo(d("엄마", sub = "가족방", messages = listOf(NoticeMessage("엄마", "밥", 0)))))
        // 1:1 대화는 작은 글이 없거나 제목과 같다.
        assertEquals("민수", NoticeResults.replyTo(d("민수", category = Notification.CATEGORY_MESSAGE)))
        assertEquals("민수", NoticeResults.replyTo(d("민수", sub = "민수", category = Notification.CATEGORY_MESSAGE)))
        // 메일: 작은 글은 계정이다. 답장은 보낸 사람에게 간다.
        assertEquals("김부장", NoticeResults.replyTo(d("김부장", sub = "me@example.com", category = Notification.CATEGORY_EMAIL)))
        assertEquals("앱", NoticeResults.replyTo(d("")))
    }

    @Test fun `자세히도 8 KB 안에 들도록 오래된 메시지부터 덜어 낸다`() {
        val messages = List(NotificationLogRules.MESSAGES) { i -> NoticeMessage("민수", "메시지$i " + "가".repeat(NotificationLogRules.MESSAGE_CHARS - 10), now + i) }
        val big = notice(9, text = "나".repeat(NotificationLogRules.TEXT_CHARS), messages = messages)
        val d = NoticeResults.detail(big, canReply = true, zone = seoul)
        assertTrue(ToolBudget.size(d) <= ToolBudget.RESULT_BYTES)
        assertEquals("true", d["truncated"]!!.jsonPrimitive.content)
        val kept = d["messages"]?.jsonArray.orEmpty()
        // 남은 것은 최근 메시지다.
        if (kept.isNotEmpty()) assertTrue(kept.last().jsonObject["text"]!!.jsonPrimitive.content.startsWith("메시지9"))
        assertEquals("민수", d["reply_to"]!!.jsonPrimitive.content)
        // 작으면 그대로.
        val small = NoticeResults.detail(notice(10), canReply = false, zone = seoul)
        assertNull(small["truncated"])
        assertNull(small["reply_to"])
    }
}
