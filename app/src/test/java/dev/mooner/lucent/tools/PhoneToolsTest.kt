package dev.mooner.lucent.tools

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.InvokeReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneToolsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settings: AppSettings
    private lateinit var log: NotificationLog
    private var foreground = true
    private var answer = AskAnswer.YES
    private val asked = mutableListOf<ReplyAsk>()
    private lateinit var tools: PhoneTools

    @Before fun setUp() {
        settings = AppSettings(app)
        log = NotificationLog(app, name = null)
        tools = PhoneTools(app, settings, { foreground }, { asked += it; answer }, { log })
        NoticeReplies.clear()
    }

    @After fun tearDown() = NoticeReplies.clear()

    private fun call(method: String, params: JsonObject? = null) = runBlocking { tools.handle(method, params) }

    private fun failed(reply: InvokeReply): InvokeReply.Failed = reply as InvokeReply.Failed
    private fun ok(reply: InvokeReply): JsonObject = (reply as InvokeReply.Ok).data.jsonObject

    private fun allowListener() {
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(ComponentName(app, NotificationWatcher::class.java), true)
    }

    /** 답장 단추가 있는 메신저 알림 하나를 기록한다. */
    private fun recordMessage(key: String = "k1"): Long {
        val input = androidx.core.app.RemoteInput.Builder("reply_text").build()
        val pending = PendingIntent.getBroadcast(app, 1, Intent("test.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
        val n = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("민수").setContentText("어디야")
            .addAction(NotificationCompat.Action.Builder(0, "답장", pending).addRemoteInput(input).build())
            .build()
        val id = log.posted(NoticeDigest.draft(app, "com.kakao.talk", key, n, 0L), System.currentTimeMillis())
        NoticeReplies.put(key, NoticeDigest.replyTarget(n))
        return id
    }

    private fun reply(id: Long, text: String = "가는 중") = call("notifications.reply", buildJsonObject {
        put("id", "n$id")
        put("text", text)
    })

    @Test fun `모르는 기능과 꺼진 기능은 거절한다`() {
        assertEquals(ErrorCodes.UNAVAILABLE, failed(call("alarm.set")).code)
        assertEquals(PhoneTools.SWITCHED_OFF, failed(call("location.get")).message)
        settings.toolReply = true // 알림 기록 없이 답장만 켠 것은 내주지 않는다.
        assertEquals(ErrorCodes.DENIED, failed(call("notifications.reply")).code)
    }

    @Test fun `위치 권한이 없으면 거절하고 화면에 없으면 읽지 않는다`() {
        settings.toolLocation = true
        assertEquals(LocationReader.NO_PERMISSION, failed(call("location.get")).message)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        foreground = false
        val away = failed(call("location.get"))
        assertEquals(ErrorCodes.UNAVAILABLE, away.code)
        assertEquals(LocationReader.NOT_ON_SCREEN, away.message)
    }

    @Test fun `알림 접근이 없으면 목록을 주지 않는다`() {
        settings.toolNotifications = true
        assertEquals(PhoneTools.NO_ACCESS, failed(call("notifications.recent")).message)
    }

    @Test fun `기록한 알림의 목록과 자세히를 준다`() {
        settings.toolNotifications = true
        allowListener()
        val id = recordMessage()
        val list = ok(call("notifications.recent"))
        val item = list["items"]!!.jsonArray.single().jsonObject
        assertEquals("n$id", item["id"]!!.jsonPrimitive.content)
        // 답장을 켜지 않았으면 답할 수 있다고 하지 않는다.
        assertEquals("false", item["can_reply"]!!.jsonPrimitive.content)
        settings.toolReply = true
        assertEquals("true", ok(call("notifications.recent"))["items"]!!.jsonArray.single().jsonObject["can_reply"]!!.jsonPrimitive.content)
        val detail = ok(call("notifications.get", buildJsonObject { put("id", "n$id") }))
        assertEquals("어디야", detail["text"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.UNAVAILABLE, failed(call("notifications.get", buildJsonObject { put("id", "n999") })).code)
        assertTrue(settings.toolUsedAt(ToolSwitch.NOTIFICATIONS) > 0)
    }

    @Test fun `답장은 물어서 허락받으면 보낸다`() {
        settings.toolNotifications = true
        settings.toolReply = true
        allowListener()
        val id = recordMessage()
        val sent = ok(reply(id))
        assertEquals("true", sent["sent"]!!.jsonPrimitive.content)
        assertEquals("민수", sent["to"]!!.jsonPrimitive.content)
        assertEquals(ReplyAsk("com.kakao.talk", "민수", "가는 중"), asked.single())
        val intent = shadowOf(app).broadcastIntents.last { it.action == "test.REPLY" }
        assertEquals("가는 중", android.app.RemoteInput.getResultsFromIntent(intent)!!.getCharSequence("reply_text").toString())
    }

    @Test fun `거절하거나 늦거나 화면에 없으면 보내지 않는다`() {
        settings.toolNotifications = true
        settings.toolReply = true
        allowListener()
        val id = recordMessage()
        answer = AskAnswer.NO
        assertEquals(PhoneTools.USER_DECLINED, failed(reply(id)).message)
        answer = AskAnswer.LATE
        assertEquals(PhoneTools.USER_LATE, failed(reply(id)).message)
        answer = AskAnswer.AWAY
        assertEquals(ErrorCodes.UNAVAILABLE, failed(reply(id)).code)
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "test.REPLY" })
    }

    @Test fun `묻지 않기로 했으면 바로 보낸다`() {
        settings.toolNotifications = true
        settings.toolReply = true
        settings.replyConfirm = false
        allowListener()
        val id = recordMessage()
        ok(reply(id))
        assertTrue(asked.isEmpty())
        // 묻지 않아도 앱이 화면에 없으면 보내지 않는다.
        foreground = false
        assertEquals(PhoneTools.NOT_ON_SCREEN, failed(reply(id)).message)
    }

    @Test fun `글이 없거나 알림이 사라졌으면 답장하지 않는다`() {
        settings.toolNotifications = true
        settings.toolReply = true
        allowListener()
        val id = recordMessage()
        assertEquals(ErrorCodes.BAD_PARAMS, failed(call("notifications.reply", buildJsonObject { put("id", "n$id") })).code)
        assertEquals(ErrorCodes.BAD_PARAMS, failed(reply(id, "가".repeat(ReplyRules.MAX_CHARS + 1))).code)
        log.removed("k1", System.currentTimeMillis())
        assertEquals(PhoneTools.GONE, failed(reply(id)).message)
        val other = recordMessage("k2")
        NoticeReplies.remove("k2")
        assertEquals(PhoneTools.NO_REPLY, failed(reply(other)).message)
        assertTrue(asked.isEmpty())
    }

    @Test fun `알림 접근이 없으면 기록에 남은 알림도 자세히 주지 않는다`() {
        settings.toolNotifications = true
        val id = recordMessage()
        val reply = failed(call("notifications.get", buildJsonObject { put("id", "n$id") }))
        assertEquals(ErrorCodes.DENIED, reply.code)
    }
}
