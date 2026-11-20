package dev.mooner.lucent.tools

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationLogTest {

    private lateinit var log: NotificationLog
    private val now = 1_763_600_000_000L

    @Before fun setUp() {
        log = NotificationLog(ApplicationProvider.getApplicationContext(), name = null)
    }

    private fun draft(key: String, text: String = "안녕", app: String = "카카오톡", messages: List<NoticeMessage> = emptyList()) =
        NoticeDraft(key = key, pkg = "com.kakao.talk", app = app, title = "민수", text = text, messages = messages, postedAt = now)

    @Test fun `떠 있는 알림이 고쳐 올라오면 같은 줄을 고친다`() {
        val first = log.posted(draft("a", "첫 메시지"), now)
        val second = log.posted(draft("a", "둘째 메시지", messages = listOf(NoticeMessage("민수", "둘째 메시지", now))), now + 1000)
        assertEquals(first, second)
        val n = log.byId(first)!!
        assertEquals("둘째 메시지", n.draft.text)
        assertEquals(now + 1000, n.updatedAt)
        assertEquals("민수", n.draft.messages.single().from)
        assertEquals(1, log.count())
    }

    @Test fun `사라진 뒤 다시 오면 새 줄이다`() {
        val first = log.posted(draft("a"), now)
        log.removed("a", now + 10)
        assertTrue(!log.byId(first)!!.active)
        val again = log.posted(draft("a", "다시"), now + 20)
        assertNotEquals(first, again)
        assertEquals(2, log.count())
    }

    @Test fun `떠 있는 열쇠로 맞추면 없는 것은 사라진 것이 된다`() {
        val a = log.posted(draft("a"), now)
        val b = log.posted(draft("b"), now)
        log.reconcile(setOf("b"), now + 5)
        assertTrue(!log.byId(a)!!.active)
        assertTrue(log.byId(b)!!.active)
    }

    @Test fun `최근 순으로 한도보다 하나 더 준다`() {
        for (i in 1..5) log.posted(draft("k$i", "글 $i"), now + i * 1000L)
        val found = log.recent(NoticeQuery(limit = 2), now + 10_000)
        assertEquals(listOf("글 5", "글 4", "글 3"), found.map { it.draft.text })
        val filtered = log.recent(NoticeQuery(query = "글 2"), now + 10_000)
        assertEquals(listOf("글 2"), filtered.map { it.draft.text })
    }

    @Test fun `오래된 것과 상한을 넘는 것은 지운다`() {
        val old = log.posted(draft("old"), now)
        log.posted(draft("new"), now + NotificationLogRules.KEEP_MS + 1)
        assertNull(log.byId(old))
        for (i in 0 until NotificationLogRules.MAX_ROWS + 5) log.posted(draft("m$i"), now + NotificationLogRules.KEEP_MS + 2 + i)
        assertEquals(NotificationLogRules.MAX_ROWS, log.count())
    }

    @Test fun `지우면 모두 사라진다`() {
        log.posted(draft("a"), now)
        log.clear()
        assertEquals(0, log.count())
    }
}
