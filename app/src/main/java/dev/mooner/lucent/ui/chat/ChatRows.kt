package dev.mooner.lucent.ui.chat

import dev.mooner.lucent.data.Kind
import dev.mooner.lucent.data.Sender
import dev.mooner.lucent.data.StoredItem
import java.time.Instant
import java.time.ZoneId

/** 화면에 그리는 한 줄. 저장된 [StoredItem]에 날짜 구분선, 묶음 표시, "쓰는 중" 표시를 더한 것이다. */
sealed interface Row {
    val key: String

    /** 날짜가 바뀌는 곳의 구분선. */
    data class Day(val epochDay: Long, val at: Long) : Row {
        override val key get() = "day:$epochDay"
    }

    /**
     * 말(루시아의 글·파일, 내가 보낸 말). [avatar]는 묶음의 첫 줄에만, [time]은 묶음의 마지막 줄에만 켠다.
     * [wide]는 긴 글(목록·코드·표가 있거나 긴 문단)이다. 말풍선이 반대쪽 여백 없이 화면 폭을 다 쓴다.
     * 스티커가 붙은 말([StoredItem.sticker])은 말풍선 아래에 그림 한 장이 놓인다. 글이 없으면 그림만 보인다.
     */
    data class Message(
        val item: StoredItem,
        val avatar: Boolean,
        val time: Boolean,
        val wide: Boolean = false,
    ) : Row {
        override val key get() = "item:${item.id}"
    }

    /**
     * 진행 안내. 잇달아 온 안내는 한 줄로 합친다: 마지막 것만 보이고 앞의 것은 눌러서 펼친다.
     * 줄의 열쇠는 묶음의 첫 안내다(안내가 더 붙어도 같은 줄로 남는다). [live]는 루시아가 지금 그 일을 하는 중이라는 뜻이다.
     */
    data class Note(val items: List<StoredItem>, val live: Boolean = false) : Row {
        override val key get() = "item:${items.first().id}"
        val latest: StoredItem get() = items.last()
    }

    data class CardRow(val item: StoredItem, val pressing: Boolean) : Row {
        override val key get() = "item:${item.id}"
    }

    data object Typing : Row {
        override val key get() = "typing"
    }
}

private const val GROUP_GAP_MS = 3 * 60_000L

/**
 * 저장된 줄을 화면의 줄로 바꾼다.
 *
 * - 같은 쪽의 말이 3분 안에 이어지면 한 묶음이다. 아바타는 묶음의 첫 줄, 시각은 마지막 줄에만 보인다.
 * - `note`와 카드는 묶음을 끊는다. 잇달아 온 `note`는 한 줄로 합친다.
 * - [working]이면(루시아가 지금 일하는 중) 맨 끝의 안내 줄이 살아 있다고 표시한다.
 * - [typing]이면 맨 끝에 "쓰는 중"을 붙인다.
 * - 스티커만 있는 줄(루시아의 `sticker` 프레임)도 말이다. 바로 앞의 글과 한 묶음으로 이어진다.
 */
fun buildRows(
    items: List<StoredItem>,
    typing: Boolean,
    pressing: Set<String>,
    zone: ZoneId = ZoneId.systemDefault(),
    ownConversation: String? = null,
    working: Boolean = false,
): List<Row> {
    val rows = ArrayList<Row>(items.size + 4)
    var lastDay: Long? = null

    fun isLuciaMessage(i: StoredItem) = i.sender != Sender.USER && (i.kind == Kind.TEXT || i.kind == Kind.FILES)
    fun sameGroup(a: StoredItem, b: StoredItem): Boolean = when {
        a.sender == Sender.USER && b.sender == Sender.USER ->
            a.kind != Kind.CARD && b.kind != Kind.CARD && b.at - a.at <= GROUP_GAP_MS
        isLuciaMessage(a) && isLuciaMessage(b) ->
            b.at - a.at <= GROUP_GAP_MS && sourceOf(a, ownConversation) == sourceOf(b, ownConversation)
        else -> false
    }

    items.forEachIndexed { index, item ->
        val day = Instant.ofEpochMilli(item.at).atZone(zone).toLocalDate().toEpochDay()
        if (day != lastDay) {
            rows += Row.Day(day, item.at)
            lastDay = day
        }
        val prev = items.getOrNull(index - 1)
        val next = items.getOrNull(index + 1)
        val nextSameDay = next != null &&
            Instant.ofEpochMilli(next.at).atZone(zone).toLocalDate().toEpochDay() == day
        val startsGroup = prev == null || lastRowWasDay(rows) || !sameGroup(prev, item)
        val endsGroup = next == null || !nextSameDay || !sameGroup(item, next)

        when {
            item.kind == Kind.NOTE -> {
                val last = rows.lastOrNull()
                if (last is Row.Note) rows[rows.lastIndex] = last.copy(items = last.items + item)
                else rows += Row.Note(listOf(item))
            }
            item.kind == Kind.CARD -> rows += Row.CardRow(item, item.ref != null && item.ref in pressing)
            else -> rows += Row.Message(
                item, avatar = startsGroup, time = endsGroup,
                wide = item.sender != Sender.USER && isLongForm(item.body),
            )
        }
    }
    if (working) (rows.lastOrNull() as? Row.Note)?.let { rows[rows.lastIndex] = it.copy(live = true) }
    if (typing) rows += Row.Typing
    return rows
}

private fun lastRowWasDay(rows: List<Row>) = rows.lastOrNull() is Row.Day

private const val LONG_FORM_CHARS = 280
private const val LONG_FORM_LIST_ITEMS = 3
private val LIST_ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\S""")
private val HEADING = Regex("""^#{1,6}\s+\S""")

/**
 * 좁은 말풍선보다 넓은 지면이 어울리는 글인가. 코드 블록·표·제목이 있거나, 목록이 세 줄 이상이거나, 글자가 많으면 그렇다.
 * 짧은 대화는 말풍선 그대로 둔다.
 */
fun isLongForm(body: String): Boolean {
    if (body.length >= LONG_FORM_CHARS) return true
    if ("```" in body) return true
    var listItems = 0
    for (line in body.lineSequence()) {
        if (HEADING.containsMatchIn(line)) return true
        if (line.trimStart().startsWith("|") && line.count { it == '|' } >= 3) return true
        if (LIST_ITEM.containsMatchIn(line) && ++listItems >= LONG_FORM_LIST_ITEMS) return true
    }
    return false
}

/** 이 앱의 대화가 아닌 곳에서 온 기록의 출처. 이 앱의 말이면 null. */
enum class Source { OTHER_APP, DISCORD, DAEMON }

fun sourceOf(item: StoredItem, ownConversation: String?): Source? {
    if (item.sender == Sender.DAEMON) return Source.DAEMON
    val conv = item.conversation ?: return null
    if (conv == ownConversation) return null
    return if (conv.startsWith("app:")) Source.OTHER_APP else Source.DISCORD
}
