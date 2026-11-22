package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.HistoryItem

/**
 * 기록(`history`)의 본문 끝에 데몬이 이어 붙이는 설명 줄들(사양 5.5, 6.2).
 *
 * 사용자의 말이면 글 뒤에 스티커 줄(`[스티커:이름 — 설명]`, [StickerLine]), 파일 설명 줄(`[첨부: 이름, …]`), 고른 글 표지
 * (`[고른 글 N자 함께 보냄 · 프로그램]`)가 차례로 붙을 수 있다. 실시간으로 받은 말(`message`로 보낸 내 말, 다른 기기의
 * `sent`)에는 이 줄들이 없으므로, 같은 말인지 견줄 때는 이 줄들을 뺀 글로 본다.
 */
object HistoryLines {
    private val CONTEXT = Regex("""^\[고른 글 \d+자 함께 보냄(?: · [^\]\n]*)?]$""")

    class Split(val text: String, val contextNote: String?)

    /** 본문 끝의 고른 글 표지(마지막 줄)를 떼어 낸다. 없으면 글 그대로다. */
    fun splitContext(body: String): Split {
        val trimmed = body.trimEnd()
        val at = trimmed.lastIndexOf('\n')
        val last = trimmed.substring(at + 1)
        if (!CONTEXT.matches(last)) return Split(body, null)
        return Split(if (at < 0) "" else trimmed.substring(0, at).trimEnd(), last)
    }

    /** 파일 설명 줄(마지막 것 하나)을 뺀다. 데몬이 `shape: 2`에서 하는 것과 같다. */
    fun withoutAttachment(body: String): String {
        val lines = body.split('\n')
        val at = lines.indexOfLast { it.startsWith("[첨부: ") && it.endsWith("]") }
        if (at < 0) return body
        return (lines.subList(0, at) + lines.subList(at + 1, lines.size)).joinToString("\n").trimEnd()
    }

    /** 같은 말인지 견줄 때 쓰는 글: 고른 글 표지와 파일 설명 줄을 뺀다. */
    fun comparable(body: String): String = withoutAttachment(splitContext(body).text).trim()

    /** 고른 글 표지를 화면에 보일 글로(대괄호를 벗긴다). 표지가 아니면 그대로다. */
    fun contextLabel(note: String): String {
        val t = note.trim()
        return if (t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1).trim() else t
    }
}

/**
 * 기록 항목 하나를 화면의 줄로 나눈다(사양 6.2).
 *
 * - 고른 글 표지(사용자의 말 끝)는 글에서 떼어 [Row.contextNote]로 둔다. 스티커 줄은 [StickerLine]이 떼어 낸다.
 * - `shape: 2`의 조각(`parts`)이 둘 이상이면 조각마다 한 줄이다. 실시간으로 여러 `text` 프레임으로 받은 것과 같은 모양이다.
 *   파일과 스티커, 고른 글 표지는 마지막 조각에 붙는다(실시간에서도 파일은 마지막 조각과 함께 `files` 프레임으로 온다).
 * - 줄의 열쇠는 `h:<기록 ID>`이고, 조각이면 `h:<기록 ID>:<차례>`다.
 */
object HistoryRows {
    data class Row(val key: String, val text: String, val sticker: StickerRef?, val files: List<FileRef>, val contextNote: String?)

    fun of(item: HistoryItem): List<Row> {
        val context = if (item.from == "user") HistoryLines.splitContext(item.body) else HistoryLines.Split(item.body, null)
        val whole = StickerLine.split(context.text)
        val files = item.files.orEmpty()
        val parts = item.parts?.takeIf { it.size > 1 }
            ?: return listOf(Row("h:${item.id}", whole.text, whole.sticker, files, context.contextNote))
        val splits = parts.map { StickerLine.split(it) }
        val partSticker = splits.any { it.sticker != null }
        return splits.mapIndexed { i, split ->
            val last = i == splits.lastIndex
            Row(
                key = "h:${item.id}:$i",
                text = split.text,
                sticker = split.sticker ?: whole.sticker.takeIf { last && !partSticker },
                files = if (last) files else emptyList(),
                contextNote = context.contextNote.takeIf { last },
            )
        }
    }
}
