package dev.mooner.lucent.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.text.style.TtsSpan
import android.util.LruCache
import dev.mooner.lucent.R
import dev.mooner.lucent.data.ExpressionStore
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.data.Expressions
import dev.mooner.lucent.protocol.Expression
import kotlin.math.roundToInt

/**
 * 글 속의 이모지 표기 `:name:`을 그림으로 그린다(app-expressions.md 4장).
 *
 * - 글은 바꾸지 않고 그 자리에 그림을 그리는 표시만 붙인다. 복사하면 `:name:`이 그대로 나온다.
 * - **그릴 수 있을 때만** 표시를 붙인다: 목록에 있고, 그림이 이미 기기에 있고, 디코더가 받아야 한다. 그렇지 않으면 글 그대로
 *   남는다(5.3의 대체 표시). 그림이 나중에 도착하면 화면이 다시 그린다.
 * - 화면 읽기에는 "이모지: {보이는 이름}"으로 읽힌다.
 * - 움직이는 그림은 글 속에서는 첫 장면만 보인다.
 */
class EmojiSpans(private val context: Context, private val store: ExpressionStore) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val jumboSize = context.resources.getDimensionPixelSize(R.dimen.emoji_jumbo)

    /** 지금의 목록에서 이름으로 찾는다. */
    fun known(name: String): Expression? = store.current.value.emoji(name)

    /** [expression]을 그릴 표시들. 그릴 수 없으면 null이고, 그 자리는 글로 남는다. */
    fun spansFor(expression: Expression): Array<Any>? {
        if (bitmap(expression, jumboSize) == null) return null
        return arrayOf(
            Span(expression),
            TtsSpan.TextBuilder(context.getString(R.string.emoji_spoken, Expressions.title(expression))).build(),
        )
    }

    /**
     * 마크다운으로 풀지 않는 글(내가 보낸 말, 진행 안내)에 이모지 표시를 붙인다. [jumbo]면 이모지만 있는 말을 크게 그린다.
     */
    fun plain(text: String, jumbo: Boolean = false): CharSequence {
        val now = store.current.value
        if (now.emoji.isEmpty()) return text
        val plan = ExpressionText.plain(text) { now.emoji(it) != null }
        if (plan.emoji.isEmpty()) return plan.text
        val out = SpannableString(plan.text)
        for (token in plan.emoji) {
            val spans = now.emoji(token.name)?.let(::spansFor) ?: continue
            spans.forEach { out.setSpan(it, token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (jumbo) enlargeIfAlone(out)
        return out
    }

    /** 이모지만 있는 말(공백 말고 다른 글이 없고 이모지가 1~3개)이면 크게 그린다(4.3). */
    fun enlargeIfAlone(text: Spannable) {
        val spans = text.getSpans(0, text.length, Span::class.java)
        if (spans.isEmpty() || spans.size > JUMBO_MAX) return
        var i = 0
        while (i < text.length) {
            val span = spans.firstOrNull { text.getSpanStart(it) == i }
            when {
                span != null -> i = text.getSpanEnd(span)
                text[i].isWhitespace() -> i++
                else -> return
            }
        }
        spans.forEach { it.fixedSize = jumboSize }
    }

    /** 목록이나 그림이 바뀌었다. 기억해 둔 그림을 버린다. */
    fun clear() = cache.evictAll()

    private fun bitmap(expression: Expression, size: Int): Bitmap? {
        val file = store.file(expression) ?: return null
        val key = "${expression.file.id}@$size"
        cache.get(key)?.let { return it }
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        cache.put(key, decoded)
        return decoded
    }

    /** 한 글자 자리에 그림을 그린다. 크기는 그 글의 글자 크기를 따르고, [fixedSize]가 있으면 그 크기다. */
    private inner class Span(private val expression: Expression) : ReplacementSpan() {
        var fixedSize = 0
        private val box = RectF()

        private fun size(paint: Paint) = if (fixedSize > 0) fixedSize else (paint.textSize * EM).roundToInt()

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val size = size(paint)
            if (fm != null && fixedSize > 0) {
                // 큰 이모지는 줄 높이를 넘으므로 줄을 그만큼 넓힌다.
                val metrics = paint.fontMetricsInt
                val extra = size - (metrics.descent - metrics.ascent)
                if (extra > 0) {
                    fm.ascent = metrics.ascent - extra / 2
                    fm.descent = metrics.descent + (extra - extra / 2)
                    fm.top = fm.ascent
                    fm.bottom = fm.descent
                }
            }
            return size
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val size = size(paint)
            val bitmap = bitmap(expression, size) ?: return
            // 글자의 가운데 높이에 맞춘다.
            val metrics = paint.fontMetricsInt
            val center = y + (metrics.ascent + metrics.descent) / 2f
            val ratio = bitmap.width.toFloat() / bitmap.height
            val w = if (ratio >= 1f) size.toFloat() else size * ratio
            val h = if (ratio >= 1f) size / ratio else size.toFloat()
            box.set(x + (size - w) / 2f, center - h / 2f, x + (size + w) / 2f, center + h / 2f)
            canvas.drawBitmap(bitmap, null, box, BITMAP_PAINT)
        }
    }

    private companion object {
        const val CACHE_BYTES = 8 * 1024 * 1024
        /** 글 속 이모지의 한 변(글자 크기에 대한 비율). 줄 높이쯤이다. */
        const val EM = 1.3f
        const val JUMBO_MAX = 3
        val BITMAP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
