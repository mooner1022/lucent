package dev.mooner.lucent.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan

/**
 * 코드 블록의 모양: 고정폭 글꼴, 한 단계 작은 글자, 앞에 가는 세로줄.
 *
 * 면을 칠한 상자로 그리면 말풍선 안에 상자가 하나 더 생긴다(포함은 두 겹까지). 인용문이 하늘색 세로줄을 쓰듯, 코드는
 * 잉크색 옅은 세로줄로 구분한다.
 */
class CodeRuleSpan(
    private val typeface: Typeface,
    private val textSize: Float,
    private val textColor: Int,
    private val ruleColor: Int,
    private val ruleWidth: Int,
    private val margin: Int,
) : MetricAffectingSpan(), LeadingMarginSpan {

    private val rule = Paint().apply { style = Paint.Style.FILL }

    override fun updateMeasureState(paint: TextPaint) = apply(paint)

    override fun updateDrawState(paint: TextPaint) {
        apply(paint)
        paint.color = textColor
    }

    private fun apply(paint: TextPaint) {
        paint.typeface = typeface
        paint.textSize = textSize
    }

    override fun getLeadingMargin(first: Boolean): Int = margin

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        rule.color = ruleColor
        val left = if (dir > 0) x else x - ruleWidth
        c.drawRect(left.toFloat(), top.toFloat(), (left + ruleWidth).toFloat(), bottom.toFloat(), rule)
    }
}
