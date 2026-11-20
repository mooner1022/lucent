package dev.mooner.lucent.tools

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeDigestTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun replyAction(): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder("reply_text").setLabel("답장").build()
        val intent = PendingIntent.getBroadcast(app, 1, Intent("test.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(0, "답장", intent).addRemoteInput(input).build()
    }

    private fun messaging(withReply: Boolean = true): Notification {
        val me = Person.Builder().setName("나").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("가족방")
            .setGroupConversation(true)
            .addMessage("밥 먹었니", 1_000L, Person.Builder().setName("엄마").build())
            .addMessage("응 먹었어", 2_000L, null as Person?)
        return NotificationCompat.Builder(app, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("엄마")
            .setContentText("응 먹었어")
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .apply { if (withReply) addAction(replyAction()) }
            .build()
    }

    @Test fun `메신저 알림에서 대화와 메시지를 읽는다`() {
        val d = NoticeDigest.draft(app, "com.kakao.talk", "k1", messaging(), 5_000L)
        assertEquals("가족방", d.conversation)
        assertEquals(listOf("엄마", null), d.messages.map { it.from })
        assertEquals("응 먹었어", d.messages.last().text)
        assertEquals("응 먹었어", d.text)
        assertEquals(Notification.CATEGORY_MESSAGE, d.category)
        assertEquals(5_000L, d.postedAt)
        // 보이지 않는 패키지는 패키지 이름으로 적는다.
        assertEquals("com.kakao.talk", d.app)
    }

    @Test fun `긴 글과 여러 줄 알림의 본문을 읽는다`() {
        val big = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("메일").setContentText("앞부분")
            .setStyle(NotificationCompat.BigTextStyle().bigText("전체 본문입니다")).build()
        assertEquals("전체 본문입니다", NoticeDigest.draft(app, "p", "k", big, 0).text)

        val inbox = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("새 메일 2통").setContentText("요약")
            .setStyle(NotificationCompat.InboxStyle().addLine("첫 메일").addLine("둘째 메일")).build()
        assertEquals("첫 메일\n둘째 메일", NoticeDigest.draft(app, "p", "k", inbox, 0).text)
    }

    @Test fun `글은 상한까지 자른다`() {
        val n = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("t").build()
        // 빌더는 글을 먼저 자르므로(플랫폼 판마다 다르다) 긴 글을 직접 넣는다. 다른 앱의 알림은 더 길 수 있다.
        n.extras.putCharSequence(Notification.EXTRA_TEXT, "가".repeat(NotificationLogRules.TEXT_CHARS + 50))
        val text = NoticeDigest.draft(app, "p", "k", n, 0).text
        assertEquals(NotificationLogRules.TEXT_CHARS + 1, text.codePointCount(0, text.length))
    }

    @Test fun `답장 단추는 자유 입력이 있는 동작만이다`() {
        assertNotNull(NoticeDigest.replyTarget(messaging()))
        assertNull(NoticeDigest.replyTarget(messaging(withReply = false)))
    }

    @Test fun `답장은 입력 칸에 글을 넣어 보낸다`() {
        val target = NoticeDigest.replyTarget(messaging())!!
        target.send(app, "지금 갈게")
        val sent = shadowOf(app).broadcastIntents.last { it.action == "test.REPLY" }
        assertEquals("지금 갈게", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence("reply_text").toString())
    }

    @Test fun `작은 글이 없으면 요약 글을 단톡방 이름으로 읽는다`() {
        val n = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("엄마").setContentText("밥 먹었니").setCategory(Notification.CATEGORY_MESSAGE).build()
        n.extras.putString(Notification.EXTRA_SUMMARY_TEXT, "가족방")
        val d = NoticeDigest.draft(app, "com.kakao.talk", "k", n, 0)
        assertEquals("가족방", d.sub)
        assertEquals("가족방", NoticeResults.replyTo(d))
    }
}
