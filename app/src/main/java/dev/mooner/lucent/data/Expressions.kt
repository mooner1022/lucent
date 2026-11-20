package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.ExpressionCatalog
import dev.mooner.lucent.protocol.ExpressionCategory
import kotlinx.serialization.Serializable

/**
 * 커스텀 이모지와 스티커(docs/app-expressions.md). 목록은 데몬 하나가 관리하고, 앱은 목록과 그림을 내려받아 그린다.
 *
 * 이 파일은 받은 목록을 찾기 좋게 묶은 것([Expressions])과 글 속 표기를 읽는 규칙([ExpressionText], [StickerLine])이다.
 * 내려받기와 캐시는 [ExpressionStore]가 한다.
 */

/**
 * 저장된 줄에 붙는 스티커 한 장. 실시간 프레임과 이 앱에서 보낸 것은 [id]가 있고, 기록(`history`)에서 읽은 것은 이름뿐이다.
 * 그릴 때 지금의 목록에서 찾는다(ID 먼저, 없으면 이름). 목록에 없으면 대체 표시(`[스티커: 이름]`)가 된다.
 */
@Serializable
data class StickerRef(val id: String? = null, val name: String = "")

/** 고르기 화면의 한 묶음. [icon]은 탭에 보일 그림이다(없을 수 있다). */
data class ExpressionSection(val id: String, val label: String, val icon: Expression?, val items: List<Expression>)

/** 받은 목록 한 벌. 규칙에 맞지 않는 항목은 버린다(이름 규칙이 틀린 이모지는 글 속에서 찾을 수 없다). */
class Expressions(catalog: ExpressionCatalog) {
    val version: String = catalog.version
    val emoji: List<Expression> = catalog.emoji.filter { usable(it) && ExpressionText.isEmojiName(it.name) }.distinctBy { it.name }
    val stickers: List<Expression> = catalog.stickers.filter { usable(it) && it.name.isNotBlank() }.distinctBy { it.id }
    val categories: List<ExpressionCategory> = catalog.categories.filter { it.id.isNotEmpty() }.sortedWith(compareBy({ it.order }, { it.id }))

    private val emojiByName = emoji.associateBy { it.name }
    private val byId = (emoji + stickers).associateBy { it.id }
    private val stickerByKey = stickers.associateBy { nameKey(it.name) }

    /** 글 속 `:name:`의 이름으로 찾는다. 대소문자를 구분한다(목록의 이름은 늘 소문자다). */
    fun emoji(name: String): Expression? = emojiByName[name]

    fun byId(id: String): Expression? = byId[id]

    /** 스티커를 이름으로 찾는다(대소문자·앞뒤 공백 무시). `이름 — 설명` 꼴이면 이름만 본다. */
    fun stickerNamed(name: String): Expression? = stickerByKey[nameKey(name.substringBefore('—'))]

    /** 저장된 스티커가 지금의 목록에서 가리키는 항목. ID가 먼저고, 없으면 이름이다. */
    fun sticker(ref: StickerRef): Expression? =
        ref.id?.let { byId[it] }?.takeIf { it in stickerSet } ?: stickerNamed(ref.name)

    private val stickerSet = stickers.toHashSet()

    /**
     * 고르기 화면의 묶음(2.3): 분류를 `order` 순으로, 그 뒤에 분류가 없는(또는 목록에 없는 분류를 가리키는) 항목을 한 묶음으로 둔다.
     * 비어 있는 분류는 뺀다. 분류 없는 묶음의 ID는 빈 글이다.
     */
    fun sections(kind: ExpressionKind): List<ExpressionSection> {
        val items = of(kind)
        val known = categories.map { it.id }.toHashSet()
        val out = categories.mapNotNull { c ->
            val inside = items.filter { it.category == c.id }
            if (inside.isEmpty()) null else ExpressionSection(c.id, c.label.ifBlank { c.id }, c.icon?.let(byId::get), inside)
        }
        val loose = items.filter { it.category !in known }
        return if (loose.isEmpty()) out else out + ExpressionSection("", "", null, loose)
    }

    fun of(kind: ExpressionKind): List<Expression> = if (kind == ExpressionKind.EMOJI) emoji else stickers

    /**
     * [query]로 찾는다(7장): 이름·보이는 이름·태그에서, 대소문자를 가리지 않는다. 앞에서 맞는 것이 먼저, 그다음 가운데서 맞는 것,
     * 태그로 맞은 것 순이다. 같은 순위 안에서는 목록 순서를 지킨다.
     */
    fun search(kind: ExpressionKind, query: String, limit: Int = Int.MAX_VALUE): List<Expression> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return of(kind).mapNotNull { e ->
            val name = e.name.lowercase()
            val label = e.label.lowercase()
            val rank = when {
                name.startsWith(q) -> 0
                label.startsWith(q) -> 1
                q in name -> 2
                q in label -> 3
                e.tags.any { it.lowercase().startsWith(q) } -> 4
                e.tags.any { q in it.lowercase() } -> 5
                else -> return@mapNotNull null
            }
            rank to e
        }.sortedBy { it.first }.take(limit).map { it.second }
    }

    companion object {
        val EMPTY = Expressions(ExpressionCatalog())

        /** 그림 파일 ID(`x_` + 16진수). 파일 이름으로 쓰므로 이 모양이 아니면 받지 않는다. */
        private val FILE_ID = Regex("^x_[0-9a-f]{1,64}$")

        fun isFileId(id: String): Boolean = FILE_ID.matches(id)

        private fun usable(e: Expression) = e.id.isNotEmpty() && isFileId(e.file.id)

        fun nameKey(name: String): String = name.trim().lowercase()

        /** 접근성 라벨과 고르기 화면에 쓰는, 사람에게 보일 이름. */
        fun title(e: Expression): String = e.label.ifBlank { e.name }
    }
}

enum class ExpressionKind { EMOJI, STICKER }

/**
 * 글 속 이모지 표기 `:name:`(app-expressions.md 4.1). 데몬의 `emoji_tokens`와 같은 규칙으로 읽는다.
 *
 * - 이름은 `[a-z0-9_]` 2~32자이고 영문 소문자가 하나 이상 있다.
 * - 오른쪽 `:`를 다음 토큰과 나눠 쓰지 않는다(`:a_b::c_d:`는 둘). 왼쪽부터 가장 짧게 맞추고, **맞춘 뒤** 목록에 있는지 본다.
 * - 코드(백틱) 안, `\:` 뒤, Discord 이모지 꼴(`<:name:id>`, `<a:name:id>`)은 토큰이 아니다.
 */
object ExpressionText {
    const val NAME_MIN = 2
    const val NAME_MAX = 32

    class Token(val start: Int, val end: Int, val name: String)

    /** 그릴 글과 그 안의 이모지 자리. */
    class Plain(val text: String, val emoji: List<Token>)

    fun isEmojiName(name: String): Boolean =
        name.length in NAME_MIN..NAME_MAX && name.all(::isNameChar) && name.any { it in 'a'..'z' }

    private fun isNameChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_'

    /** [text]의 [colon] 자리에서 시작하는 `:name:` 꼴의 끝(닫는 콜론 다음 자리). 꼴이 아니면 -1. */
    fun shapeEnd(text: CharSequence, colon: Int): Int {
        if (colon >= text.length || text[colon] != ':') return -1
        var j = colon + 1
        while (j < text.length && j - colon - 1 < NAME_MAX && isNameChar(text[j])) j++
        return if (j < text.length && text[j] == ':' && isEmojiName(text.substring(colon + 1, j))) j + 1 else -1
    }

    /** Discord 이모지(`<:name:id>`, `<a:name:id>`)의 콜론인가. */
    fun afterDiscordOpen(text: CharSequence, colon: Int): Boolean =
        (colon >= 1 && text[colon - 1] == '<') || (colon >= 2 && text[colon - 1] == 'a' && text[colon - 2] == '<')

    /** 꼴이 맞는 토큰 전부(목록에 있는지는 보지 않는다). */
    fun tokens(text: CharSequence): List<Token> {
        val out = ArrayList<Token>()
        scan(text, onToken = { start, end -> out += Token(start, end, text.substring(start + 1, end - 1)) }, onEscape = { _, _ -> })
        return out
    }

    /**
     * 마크다운으로 풀지 않는 글(내가 보낸 말, 진행 안내)을 그릴 모양으로 바꾼다. [known]에 있는 이름만 이모지 자리가 되고,
     * 그런 이름 앞의 `\`는 지워 글로 보인다. 모르는 이름과 그 앞의 `\`는 건드리지 않는다.
     */
    fun plain(text: String, known: (String) -> Boolean): Plain {
        if (':' !in text) return Plain(text, emptyList())
        val tokens = ArrayList<Token>()
        val drop = ArrayList<Int>()
        scan(
            text,
            onToken = { start, end -> text.substring(start + 1, end - 1).let { if (known(it)) tokens += Token(start, end, it) } },
            onEscape = { slash, end -> if (known(text.substring(slash + 2, end - 1))) drop += slash },
        )
        if (drop.isEmpty()) return Plain(text, tokens)
        val out = StringBuilder(text.length)
        var at = 0
        for (slash in drop) {
            out.append(text, at, slash)
            at = slash + 1
        }
        out.append(text, at, text.length)
        fun shift(i: Int) = i - drop.count { it < i }
        return Plain(out.toString(), tokens.map { Token(shift(it.start), shift(it.end), it.name) })
    }

    /**
     * 그림을 그릴 수 없는 한 줄짜리 자리(머리말의 상태 줄)에 쓸 글: [known]에 있는 이모지 표기를 빼고 남은 공백을 정리한다.
     */
    fun without(text: String, known: (String) -> Boolean): String {
        val plan = plain(text, known)
        if (plan.emoji.isEmpty()) return plan.text
        val out = StringBuilder(plan.text.length)
        var at = 0
        for (token in plan.emoji) {
            out.append(plan.text, at, token.start)
            at = token.end
        }
        out.append(plan.text, at, plan.text.length)
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /** [onEscape]는 `\:name:` 꼴(역슬래시 자리, 끝)에서 불린다. 데몬처럼 그 콜론 하나만 지나간다. */
    private inline fun scan(text: CharSequence, onToken: (start: Int, end: Int) -> Unit, onEscape: (slash: Int, end: Int) -> Unit) {
        var inCode = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    while (i < text.length && text[i] == '`') i++
                    inCode = !inCode
                }
                c == ':' && !inCode -> {
                    val escaped = i > 0 && text[i - 1] == '\\'
                    val end = if (afterDiscordOpen(text, i)) -1 else shapeEnd(text, i)
                    when {
                        end < 0 -> i++
                        escaped -> {
                            onEscape(i - 1, end)
                            i++
                        }
                        else -> {
                            onToken(i, end)
                            i = end
                        }
                    }
                }
                else -> i++
            }
        }
    }

    /**
     * 자동 완성(7장): [text]의 [cursor] 바로 앞이 `:` 다음 두 글자 이상이면 그 찾을 말을 돌려준다(콜론 자리와 함께).
     * 이미 닫힌 `:name:`의 닫는 콜론이나 `\:` 뒤는 찾지 않는다.
     */
    fun completing(text: CharSequence, cursor: Int): Pair<Int, String>? {
        if (cursor > text.length) return null
        var colon = cursor - 1
        while (colon >= 0 && text[colon] != ':') {
            if (text[colon].isWhitespace()) return null
            colon--
        }
        if (colon < 0 || cursor - colon - 1 < NAME_MIN) return null
        if (colon > 0 && text[colon - 1] == '\\') return null
        // 앞에서 이미 닫힌 토큰의 닫는 콜론이면 새 이모지를 쓰는 중이 아니다.
        if (tokens(text.subSequence(0, colon + 1)).any { it.end == colon + 1 }) return null
        return colon to text.substring(colon + 1, cursor)
    }
}

/**
 * 기록(`history`)의 스티커 줄(app-expressions.md 6장). 기록은 글이라 스티커가 `[스티커:이름]` 또는 `[스티커:이름 — 설명]`
 * 한 줄로 남는다. 실시간 프레임(`text` + `sticker`)과 모양이 다르므로, 가져올 때 글과 스티커로 다시 나눈다.
 */
object StickerLine {
    private val LINE = Regex("""^\[스티커:\s*([^\]\n—]+?)\s*(?:—[^\]\n]*)?\]$""", RegexOption.MULTILINE)

    class Split(val text: String, val sticker: StickerRef?)

    /** 글에서 스티커 줄(마지막 것 하나)을 떼어 낸다. 메시지 하나에 스티커는 하나다(8장). */
    fun split(body: String): Split {
        if ("[스티커:" !in body) return Split(body, null)
        val match = LINE.findAll(body).lastOrNull() ?: return Split(body, null)
        val before = body.substring(0, match.range.first).trimEnd('\n', '\r')
        val after = body.substring(match.range.last + 1).trimStart('\n', '\r')
        val text = if (before.isEmpty() || after.isEmpty()) before + after else before + "\n" + after
        return Split(text, StickerRef(name = match.groupValues[1].trim()))
    }

    /** 그릴 수 없을 때의 대체 표시(5.3). */
    fun fallback(name: String): String = "[스티커: $name]"
}
