package dev.mooner.lucent.tools

import android.app.Notification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 메신저 알림 속의 메시지 하나. [from]이 null이면 사용자 자신이 보낸 것이다. */
@Serializable
data class NoticeMessage(val from: String? = null, val text: String = "", val at: Long = 0)

/** 알림에서 읽어 낸 내용(기록하기 전). 글은 이미 상한까지 잘려 있다([NoticeDigest]). */
data class NoticeDraft(
    /** 시스템이 붙인 알림 열쇠(`StatusBarNotification.key`). 같은 알림이 고쳐 올라오면 같다. */
    val key: String,
    val pkg: String,
    /** 사람이 읽는 앱 이름. 모르면 패키지 이름이다. */
    val app: String,
    val title: String,
    val text: String,
    /** 알림의 작은 글(`EXTRA_SUB_TEXT`, 없으면 `EXTRA_SUMMARY_TEXT`): 메일 계정, 카카오톡의 단톡방 이름 등. */
    val sub: String? = null,
    /** 메신저 알림의 대화 이름(단톡방 등). */
    val conversation: String? = null,
    val messages: List<NoticeMessage> = emptyList(),
    val category: String? = null,
    val postedAt: Long,
)

/** 기록된 알림 하나. 같은 알림이 고쳐 올라오면(새 메시지) 이 줄이 바뀐다. 사라진 뒤 다시 오면 새 줄이다. */
data class Notice(
    val id: Long,
    val draft: NoticeDraft,
    /** 마지막으로 올라오거나 고쳐진 때. 목록은 이 순서다. */
    val updatedAt: Long,
    /** 알림 창에서 사라진 때. null이면 아직 떠 있다. */
    val removedAt: Long? = null,
) {
    val active: Boolean get() = removedAt == null
}

/** 어떤 알림을 기록할지. 소식이 아닌 것(이 앱의 알림, 묶음의 머리, 진행 중 표시, 재생 막대)은 기록하지 않는다. */
object NoticeFilter {

    /** 재생 막대, 진행 표시, 서비스·시스템, 길 안내, 초시계. 뒤의 둘은 늦게 생긴 값이라 글로 적는다(API 28, 31). */
    private val SKIPPED_CATEGORIES = setOf(
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM,
        "navigation",
        "stopwatch",
    )

    fun records(pkg: String, ownPkg: String, flags: Int, category: String?): Boolean = when {
        pkg == ownPkg -> false
        flags and Notification.FLAG_GROUP_SUMMARY != 0 -> false
        flags and Notification.FLAG_ONGOING_EVENT != 0 -> false
        flags and Notification.FLAG_FOREGROUND_SERVICE != 0 -> false
        category in SKIPPED_CATEGORIES -> false
        else -> true
    }

    /** 읽을 글이 하나도 없는 알림은 남기지 않는다. */
    fun hasContent(draft: NoticeDraft): Boolean =
        draft.title.isNotBlank() || draft.text.isNotBlank() || draft.messages.isNotEmpty()
}

/** `notifications.recent`의 조건. */
data class NoticeQuery(
    val limit: Int = DEFAULT_LIMIT,
    val app: String? = null,
    val query: String? = null,
    val hours: Int = DEFAULT_HOURS,
    val activeOnly: Boolean = false,
) {
    fun since(now: Long): Long = now - hours * 3_600_000L

    fun matches(notice: Notice, now: Long): Boolean {
        if (notice.updatedAt < since(now)) return false
        if (activeOnly && !notice.active) return false
        val d = notice.draft
        if (app != null && !d.app.contains(app, ignoreCase = true) && !d.pkg.contains(app, ignoreCase = true)) return false
        if (query != null) {
            val texts = sequenceOf(d.title, d.text, d.sub.orEmpty(), d.conversation.orEmpty()) + d.messages.asSequence().map { it.text }
            if (texts.none { it.contains(query, ignoreCase = true) }) return false
        }
        return true
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val DEFAULT_HOURS = 24
        const val MAX_HOURS = NotificationLogRules.KEEP_HOURS

        fun of(params: ToolParams) = NoticeQuery(
            limit = params.int("limit", DEFAULT_LIMIT, 1..MAX_LIMIT),
            app = params.string("app"),
            query = params.string("query"),
            hours = params.int("hours", DEFAULT_HOURS, 1..MAX_HOURS),
            activeOnly = params.bool("active", false),
        )
    }
}

/** 알림 기록을 얼마나 두는가. */
object NotificationLogRules {
    const val KEEP_HOURS = 48
    const val KEEP_MS = KEEP_HOURS * 3_600_000L
    const val MAX_ROWS = 500

    // 기록하는 글의 상한(자). 목록에는 더 짧게 보인다([NoticeResults]).
    const val TITLE_CHARS = 200
    const val TEXT_CHARS = 2_000
    const val MESSAGE_CHARS = 500
    const val MESSAGES = 10
}

/** 답장의 규칙. */
object ReplyRules {
    const val MAX_CHARS = 1_000
}

/** 기록된 알림을 루시아에게 보일 JSON으로 바꾼다. 시각은 휴대폰의 시간대로 적는다(RFC 3339). */
object NoticeResults {

    /** 목록에서 한 알림의 글 앞부분(자). 8 KB에 열 개 남짓이 들도록 짧게 둔다. */
    const val PREVIEW_CHARS = 120
    private const val ID_PREFIX = "n"

    fun idOf(notice: Notice): String = "$ID_PREFIX${notice.id}"

    /**
     * 답장이 가는 곳의 이름. 메신저 알림의 대화 이름이 먼저다. 그것이 없는 메신저(카카오톡)는 단톡방 이름을 작은 글에 두고 제목에는
     * 보낸 사람을 둔다. 그래서 메신저 알림이면 작은 글이 대화 이름이다. 메일처럼 작은 글이 계정인 알림에서는 제목(보낸 사람)이다.
     */
    fun replyTo(d: NoticeDraft): String {
        d.conversation?.let { return it }
        val chat = d.category == Notification.CATEGORY_MESSAGE || d.messages.isNotEmpty()
        val room = d.sub?.takeIf { chat && it != d.title }
        return room ?: d.title.ifBlank { d.app }
    }

    /** 루시아가 돌려준 id. `n42`든 `42`든 받는다. */
    fun parseId(id: String?): Long? = id?.trim()?.removePrefix(ID_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

    fun time(at: Long, zone: ZoneId): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(at), zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** 목록의 한 줄. 메신저 알림이면 글 대신 마지막 메시지가 앞부분이 된다. */
    fun summary(notice: Notice, canReply: Boolean, zone: ZoneId): JsonObject = buildJsonObject {
        val d = notice.draft
        put("id", idOf(notice))
        put("app", d.app)
        put("title", d.title.clip(NotificationLogRules.TITLE_CHARS))
        d.conversation?.let { put("conversation", it) }
        d.sub?.let { put("sub_text", it.clip(SUB_CHARS)) }
        put("text", preview(d).clip(PREVIEW_CHARS))
        put("at", time(notice.updatedAt, zone))
        put("active", notice.active)
        put("can_reply", canReply)
    }

    /**
     * `notifications.get`: 한 알림의 전체. 8 KB에 들지 않으면 오래된 메시지부터 덜고, 그래도 넘치면 본문을 줄인다. 줄였으면
     * `truncated`로 알린다.
     */
    fun detail(notice: Notice, canReply: Boolean, zone: ZoneId): JsonObject {
        var draft = notice.draft
        var cut = false
        while (true) {
            val shown = detailOf(notice, draft, canReply, cut, zone)
            if (ToolBudget.size(shown) <= ToolBudget.RESULT_BYTES) return shown
            cut = true
            val chars = draft.text.codePointCount(0, draft.text.length)
            draft = when {
                draft.messages.size > 1 -> draft.copy(messages = draft.messages.drop(1))
                chars > MIN_TEXT_CHARS -> draft.copy(text = draft.text.clip(chars / 2))
                draft.messages.isNotEmpty() -> draft.copy(messages = emptyList())
                else -> return shown
            }
        }
    }

    private fun detailOf(notice: Notice, d: NoticeDraft, canReply: Boolean, cut: Boolean, zone: ZoneId) = buildJsonObject {
        put("id", idOf(notice))
        put("app", d.app)
        put("package", d.pkg)
        put("title", d.title)
        d.conversation?.let { put("conversation", it) }
        d.sub?.let { put("sub_text", it) }
        put("text", d.text)
        if (d.messages.isNotEmpty()) putJsonArray("messages") {
            for (m in d.messages) add(buildJsonObject {
                put("from", m.from ?: SELF)
                put("text", m.text)
                if (m.at > 0) put("at", time(m.at, zone))
            })
        }
        d.category?.let { put("category", it) }
        put("posted_at", time(d.postedAt, zone))
        put("at", time(notice.updatedAt, zone))
        put("active", notice.active)
        put("can_reply", canReply)
        if (canReply) put("reply_to", replyTo(d))
        if (cut) put("truncated", true)
    }

    /**
     * `notifications.recent`의 답. [notices]는 조건에 맞는 것을 최근 순으로 [NoticeQuery.limit]보다 하나 더 받은 것이다
     * (더 있는지 알려고). 8 KB에 들지 않으면 앞에서부터 들어가는 만큼만 담고 `more`로 알린다.
     */
    fun list(notices: List<Notice>, query: NoticeQuery, canReply: (Notice) -> Boolean, zone: ZoneId): JsonObject {
        val wanted = notices.take(query.limit)
        val moreThanLimit = notices.size > query.limit
        val rows = wanted.map { summary(it, canReply(it), zone) }
        return ToolBudget.fit(rows) { shown, cut ->
            buildJsonObject {
                putJsonArray("items") { shown.forEach { add(it) } }
                put("more", cut || moreThanLimit)
                if (cut) put("note", CUT_NOTE)
                if (wanted.isEmpty()) put("note", EMPTY_NOTE)
            }
        }.first
    }

    private const val SUB_CHARS = 60
    private const val MIN_TEXT_CHARS = 200

    /** 메신저 알림의 내 메시지에 붙이는 이름. */
    const val SELF = "나"
    private const val CUT_NOTE = "더 있다. app·query·hours로 좁히거나 limit를 줄여 다시 부른다."
    private const val EMPTY_NOTE = "조건에 맞는 알림이 없다. 기록은 알림 기록을 켠 뒤에 온 것만, 48시간까지 있다."

    private fun preview(d: NoticeDraft): String {
        val last = d.messages.lastOrNull()
        val body = when {
            last != null -> "${last.from ?: SELF}: ${last.text}"
            else -> d.text
        }
        return body.replace('\n', ' ')
    }
}
