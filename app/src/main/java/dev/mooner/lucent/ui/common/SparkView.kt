package dev.mooner.lucent.ui.common

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 진행 안내 앞의 별. [live]이면(루시아가 지금 그 일을 하는 중) 천천히 깜박인다.
 * 화면에 보이는 동안에만 깜박이고, 움직임 줄이기가 켜져 있으면 깜박이지 않는다.
 */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val animates = Motion.enabled(context)
    private var shown = false
    private var pulse: ObjectAnimator? = null

    var live: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private fun update() {
        val wanted = live && animates && shown
        if (wanted && pulse == null) {
            pulse = ObjectAnimator.ofFloat(this, ALPHA, 1f, DIM).apply {
                duration = PULSE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!wanted) {
            pulse?.cancel()
            pulse = null
            alpha = 1f
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        update()
    }

    override fun onDetachedFromWindow() {
        shown = false
        update()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PULSE_MS = 700L
        const val DIM = 0.25f
    }
}
