package dev.mooner.lucent.tools

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.InvokeReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** 답장을 보내기 전에 사용자에게 묻는 것. [to]는 받는 사람이나 대화 이름이다. */
data class ReplyAsk(val app: String, val to: String, val text: String)

/** 물음의 답. */
enum class AskAnswer {
    YES,
    NO,
    /** 제때 답하지 않았다. */
    LATE,
    /** 앱이 화면에 없어 물을 수 없었다. */
    AWAY,
}

/**
 * 루시아가 부른 휴대폰 기능(`invoke`)을 처리한다. 무엇을 내주는지는 [ToolSpecs], 켜고 끄는 것은 설정([AppSettings])이다.
 *
 * 답의 `message`는 루시아가 사용자에게 그대로 전하는 까닭이라 사람이 읽을 글로 쓴다. 결과의 글은 루시아에게 외부 자료다.
 */
class PhoneTools(
    private val context: Context,
    private val settings: AppSettings,
    private val isForeground: () -> Boolean,
    /** 답장을 보내기 전에 사용자에게 묻는다. */
    private val ask: suspend (ReplyAsk) -> AskAnswer,
    private val logOf: () -> NotificationLog = { NotificationLog.get(context) },
) {
    private val location = LocationReader(context, isForeground)
    private val log get() = logOf()

    /** 사용자가 켠 스위치. */
    fun switches(): Set<ToolSwitch> = buildSet {
        if (settings.toolLocation) add(ToolSwitch.LOCATION)
        if (settings.toolNotifications) add(ToolSwitch.NOTIFICATIONS)
        if (settings.toolReply) add(ToolSwitch.REPLY)
    }

    /** 내주는 기능의 이름들. */
    fun offeredNames(): List<String> = ToolSpecs.offered(switches()).map { it.method }

    /** `hello.tools`에 실을 선언. */
    fun declarations(): List<JsonObject> = ToolSpecs.declarations(switches())

    suspend fun handle(method: String, params: JsonElement?): InvokeReply {
        val tool = PhoneTool.of(method) ?: return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_OFFERED)
        // 연결한 뒤에 스위치를 껐을 수 있다(선언은 다음 연결부터 바뀐다).
        if (tool !in ToolSpecs.offered(switches())) return InvokeReply.Failed(ErrorCodes.DENIED, SWITCHED_OFF)
        val p = ToolParams(params)
        val reply = try {
            when (tool) {
                PhoneTool.LOCATION -> location.read(p.bool("precise", false))
                PhoneTool.NOTIFICATIONS_RECENT -> recent(p)
                PhoneTool.NOTIFICATIONS_GET -> detail(p)
                PhoneTool.NOTIFICATIONS_REPLY -> reply(p)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "기능을 처리하지 못함: $method", e)
            InvokeReply.Failed(ErrorCodes.FAILED, FAILED)
        }
        if (reply is InvokeReply.Ok) settings.markToolUsed(tool.switch, System.currentTimeMillis())
        return reply
    }

    /** 알림 기록과 답장 단추를 지운다(알림 기록을 껐거나 연결을 해제했다). */
    fun clearNotices() {
        NoticeReplies.clear()
        runCatching { log.clear() }
    }

    fun noticeCount(): Int = runCatching { log.count() }.getOrDefault(0)

    // ------------------------------------------------------------------ 알림

    private fun accessProblem(): InvokeReply.Failed? =
        if (NotificationWatcher.isAllowed(context)) null else InvokeReply.Failed(ErrorCodes.DENIED, NO_ACCESS)

    private suspend fun recent(p: ToolParams): InvokeReply = withContext(Dispatchers.IO) {
        accessProblem()?.let { return@withContext it }
        val query = NoticeQuery.of(p)
        val found = log.recent(query, System.currentTimeMillis())
        InvokeReply.Ok(NoticeResults.list(found, query, ::canReply, ZoneId.systemDefault()))
    }

    private suspend fun detail(p: ToolParams): InvokeReply = withContext(Dispatchers.IO) {
        accessProblem()?.let { return@withContext it }
        val notice = find(p) ?: return@withContext InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_IN_LOG)
        InvokeReply.Ok(NoticeResults.detail(notice, canReply(notice), ZoneId.systemDefault()))
    }

    private suspend fun reply(p: ToolParams): InvokeReply {
        val id = p.string("id") ?: return InvokeReply.Failed(ErrorCodes.BAD_PARAMS, NEED_ID)
        val text = p.string("text") ?: return InvokeReply.Failed(ErrorCodes.BAD_PARAMS, NEED_TEXT)
        if (text.codePointCount(0, text.length) > ReplyRules.MAX_CHARS) return InvokeReply.Failed(ErrorCodes.BAD_PARAMS, TEXT_TOO_LONG)
        accessProblem()?.let { return it }
        val noticeId = NoticeResults.parseId(id) ?: return InvokeReply.Failed(ErrorCodes.BAD_PARAMS, NEED_ID)
        val notice = withContext(Dispatchers.IO) { log.byId(noticeId) } ?: return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_IN_LOG)
        if (!notice.active) return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, GONE)
        val target = NoticeReplies.get(notice.draft.key) ?: return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NO_REPLY)

        val to = NoticeResults.replyTo(notice.draft)
        // 사용자의 이름으로 남에게 글을 보내는 일이다. 묻지 않기로 했어도 앱이 화면에 있을 때만 한다.
        if (!isForeground()) return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_ON_SCREEN)
        if (settings.replyConfirm) {
            when (ask(ReplyAsk(notice.draft.app, to, text))) {
                AskAnswer.YES -> Unit
                AskAnswer.NO -> return InvokeReply.Failed(ErrorCodes.DENIED, USER_DECLINED)
                AskAnswer.LATE -> return InvokeReply.Failed(ErrorCodes.DENIED, USER_LATE)
                AskAnswer.AWAY -> return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_ON_SCREEN)
            }
        }
        return try {
            target.send(context, text)
            InvokeReply.Ok(buildJsonObject {
                put("sent", true)
                put("app", notice.draft.app)
                put("to", to)
            })
        } catch (e: PendingIntent.CanceledException) {
            NoticeReplies.remove(notice.draft.key)
            InvokeReply.Failed(ErrorCodes.UNAVAILABLE, REPLY_CLOSED)
        }
    }

    private fun find(p: ToolParams): Notice? = NoticeResults.parseId(p.string("id"))?.let { log.byId(it) }

    /** 지금 답장할 수 있는가: 아직 떠 있고 그 단추를 쥐고 있다. */
    private fun canReply(notice: Notice): Boolean =
        ToolSwitch.REPLY in switches() && notice.active && NoticeReplies.get(notice.draft.key) != null

    companion object {
        private const val TAG = "PhoneTools"

        // 루시아가 사용자에게 그대로 전하는 까닭.
        const val NOT_OFFERED = "이 앱은 그 기능을 내주지 않았다."
        const val SWITCHED_OFF = "사용자가 Lucent 설정에서 이 기능을 껐다."
        const val FAILED = "휴대폰에서 처리하지 못했다."
        const val NO_ACCESS = "휴대폰에서 Lucent의 알림 접근이 허용되어 있지 않다. 사용자가 Lucent 설정의 '휴대폰 기능'에서 허용하면 된다."
        const val NOT_IN_LOG = "그 id의 알림이 기록에 없다(48시간이 지났거나 기록을 지웠다). notifications.recent로 다시 찾는다."
        const val NEED_ID = "id가 필요하다(notifications.recent가 준 것)."
        const val NEED_TEXT = "보낼 글(text)이 필요하다."
        const val TEXT_TOO_LONG = "답장은 ${ReplyRules.MAX_CHARS}자까지 보낼 수 있다."
        const val GONE = "그 알림이 이미 사라져 답장할 수 없다."
        const val NO_REPLY = "그 알림에는 답장 단추가 없다."
        const val USER_DECLINED = "사용자가 휴대폰에서 보내지 않기로 했다."
        const val USER_LATE = "사용자가 휴대폰에서 제때 확인하지 않아 보내지 않았다."
        const val NOT_ON_SCREEN = "Lucent가 화면에 열려 있지 않아 사용자에게 확인받지 못했다. 보내지 않았다."
        const val REPLY_CLOSED = "그 앱이 이 알림의 답장을 더는 받지 않는다. 보내지 못했다."
    }
}
