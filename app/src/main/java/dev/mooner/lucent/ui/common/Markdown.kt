package dev.mooner.lucent.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.util.LruCache
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mooner.lucent.R
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.protocol.Expression
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.CustomNode
import org.commonmark.node.Node

/**
 * 루시아의 글을 그린다(사양 4장). Discord식 마크다운의 하위 집합: 굵게, 기울임, 코드, 목록, 링크, 인용.
 *
 * - HTML로는 해석하지 않는다(Markwon 코어는 HTML 플러그인 없이는 태그를 글자 그대로 둔다).
 * - 링크는 눌렀을 때 주소를 보이고 사용자가 확인해야 연다. `http`·`https`만 연다.
 * - 같은 글을 다시 그릴 때(스크롤)마다 파싱하지 않도록 결과를 캐시한다.
 * - [emoji]를 주면(대화 화면) 글 속의 `:name:`을 이모지로 그린다(app-expressions.md 4장). 마크다운을 읽는 도중에
 *   찾으므로 코드 안, 링크 주소, `\:` 뒤의 것은 저절로 빠진다. 설정의 글에는 주지 않는다.
 */
class Markdown(private val context: Context, private val emoji: EmojiSpans? = null) {

    /** 글 속의 이모지 한 개. 그릴 때 `:name:` 글자를 놓고 그 위에 그림 표시를 붙인다. */
    private class EmojiNode(val expression: Expression, val literal: String) : CustomNode()

    /**
     * `:`에서 `:name:` 꼴을 찾는다. 꼴이 맞으면 목록에 없어도 통째로 지나간다(닫는 콜론을 다음 토큰과 나눠 쓰지 않는다).
     * 역슬래시 뒤의 콜론과 코드 안의 글은 다른 처리기가 먼저 가져가므로 여기 오지 않는다.
     */
    private inner class EmojiProcessor : InlineProcessor() {
        override fun specialCharacter() = ':'

        override fun parse(): Node? {
            val spans = emoji ?: return null
            if (ExpressionText.afterDiscordOpen(input, index)) return null
            val end = ExpressionText.shapeEnd(input, index)
            if (end < 0) return null
            val literal = input.substring(index, end)
            index = end
            val expression = spans.known(literal.substring(1, literal.length - 1)) ?: return text(literal)
            return EmojiNode(expression, literal)
        }
    }

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create { it.addInlineProcessor(EmojiProcessor()) })
        // Discord처럼 한 번의 줄바꿈도 줄바꿈으로 보인다(CommonMark는 공백으로 합친다).
        .usePlugin(io.noties.markwon.SoftBreakAddsNewLinePlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                builder.linkResolver(LinkResolver { view, link ->
                    // 눌린 링크가 선택된 채 남아 하이라이트가 사라지지 않는 것을 막는다.
                    ((view as? TextView)?.text as? Spannable)?.let(android.text.Selection::removeSelection)
                    confirmAndOpen(view.context, link)
                })
            }

            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(EmojiNode::class.java) { visitor, node ->
                    val start = visitor.length()
                    visitor.builder().append(node.literal)
                    // 그림이 아직 없거나 디코더가 받지 못하면 표시를 붙이지 않는다. 글자 그대로 보인다.
                    emoji?.spansFor(node.expression)?.let { visitor.setSpans(start, it) }
                }
            }

            /** 코드 블록은 면을 칠한 상자 대신 세로줄로 구분한다([CodeRuleSpan]). */
            override fun configureSpansFactory(builder: io.noties.markwon.MarkwonSpansFactory.Builder) {
                val mono = ResourcesCompat.getFont(context, R.font.jbmono_nl_regular) ?: Typeface.MONOSPACE
                val res = context.resources
                val factory = io.noties.markwon.SpanFactory { _, _ ->
                    CodeRuleSpan(
                        typeface = mono,
                        textSize = res.getDimension(R.dimen.text_code),
                        textColor = context.getColor(R.color.ink),
                        ruleColor = context.getColor(R.color.line),
                        ruleWidth = res.getDimensionPixelSize(R.dimen.code_rule),
                        margin = res.getDimensionPixelSize(R.dimen.space_m),
                    )
                }
                builder.setFactory(org.commonmark.node.FencedCodeBlock::class.java, factory)
                builder.setFactory(org.commonmark.node.IndentedCodeBlock::class.java, factory)
            }

            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val mono = ResourcesCompat.getFont(context, R.font.jbmono_nl_regular) ?: Typeface.MONOSPACE
                builder
                    .codeTypeface(mono)
                    .codeBlockTypeface(mono)
                    .codeBackgroundColor(context.getColor(R.color.surface_alt))
                    .codeBlockBackgroundColor(context.getColor(R.color.surface_alt))
                    .codeTextSize(context.resources.getDimensionPixelSize(R.dimen.text_code))
                    .codeBlockMargin(dp(10))
                    .linkColor(context.getColor(R.color.sky_deep))
                    .isLinkUnderlined(true)
                    .blockQuoteColor(context.getColor(R.color.sky))
                    .bulletWidth(dp(5))
                    .headingBreakHeight(0)
                    .thematicBreakColor(context.getColor(R.color.line))
            }
        })
        .build()

    private val cache = LruCache<String, Spanned>(CACHE_SIZE)

    /** [jumbo]면(대화의 말풍선) 이모지만 있는 말을 크게 그린다. 카드의 글에는 켜지 않는다. */
    fun render(view: TextView, body: String, jumbo: Boolean = false) {
        val key = if (jumbo) JUMBO_KEY + body else body
        val spanned = cache.get(key) ?: parse(body, jumbo).also { cache.put(key, it) }
        markwon.setParsedMarkdown(view, spanned)
    }

    /**
     * 긴 글(파일 미리보기)을 화면 밖에서 풀어 둔다. 캐시에 넣지 않는다. 푼 것은 [show]로 그린다.
     */
    fun prepare(body: String): Spanned = markwon.toMarkdown(body)

    fun show(view: TextView, prepared: Spanned) = markwon.setParsedMarkdown(view, prepared)

    private fun parse(body: String, jumbo: Boolean): Spanned {
        val parsed = markwon.toMarkdown(body)
        if (emoji == null || !jumbo) return parsed
        val text = parsed as? Spannable ?: SpannableString(parsed)
        emoji.enlargeIfAlone(text)
        return text
    }

    /** 이모지 목록이나 그림이 바뀌었다. 풀어 둔 글을 버려 다음에 다시 풀게 한다. */
    fun invalidate() = cache.evictAll()

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val CACHE_SIZE = 200
        private const val JUMBO_KEY = "\u0000"

        fun confirmAndOpen(context: Context, link: String) {
            val uri = runCatching { link.toUri() }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri == null || (scheme != "http" && scheme != "https")) {
                Toast.makeText(context, R.string.link_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.link_open_title)
                .setMessage(link)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.link_open) { _, _ ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.link_no_app, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }
}
