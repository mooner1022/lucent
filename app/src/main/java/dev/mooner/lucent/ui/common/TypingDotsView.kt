package dev.mooner.lucent.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import dev.mooner.lucent.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * 루시아가 답을 쓰는 중임을 보이는 별 세 개. 점이 아니라 앱의 별 모양(섹션 제목, 진행 안내와 같은 네 갈래 별)이
 * 차례로 커지며 반짝인다. 화면에 보이는 동안에만 돌고, 움직임 줄이기가 켜져 있으면 가만히 있다.
 */
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val starSize = resources.getDimension(R.dimen.typing_star)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.sky_deep) }
    private val star = Path()
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedFraction; invalidate() }
    }
    private val animates = Motion.enabled(context)

    init {
        // ic_sparkle과 같은 모양: 네 꼭짓점을 안으로 휜 곡선으로 잇는다. 중심이 원점, 반지름 1.
        star.moveTo(0f, -1f)
        star.quadTo(0f, 0f, 1f, 0f)
        star.quadTo(0f, 0f, 0f, 1f)
        star.quadTo(0f, 0f, -1f, 0f)
        star.quadTo(0f, 0f, 0f, -1f)
        star.close()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resources.getDimensionPixelSize(R.dimen.typing_width), resources.getDimensionPixelSize(R.dimen.typing_height))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val step = (width - starSize) / (COUNT - 1)
        for (i in 0 until COUNT) {
            val wave = if (animates) 0.5f + 0.5f * sin((t - i * STAGGER) * 2 * PI.toFloat()) else STILL
            paint.alpha = (255 * (MIN_ALPHA + (1f - MIN_ALPHA) * wave)).toInt()
            val radius = starSize / 2f * (MIN_SCALE + (1f - MIN_SCALE) * wave)
            canvas.save()
            canvas.translate(starSize / 2f + step * i, cy)
            canvas.scale(radius, radius)
            canvas.drawPath(star, paint)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    /** 화면에 보이는 동안에만 돈다(앱이 뒤로 가면 멈춘다). */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && animates) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    private companion object {
        const val COUNT = 3
        const val CYCLE_MS = 1400L
        const val STAGGER = 0.2f
        const val MIN_ALPHA = 0.35f
        const val MIN_SCALE = 0.6f
        const val STILL = 0.7f
    }
}
