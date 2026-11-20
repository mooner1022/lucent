package dev.mooner.lucent.ui.common

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.mooner.lucent.R
import kotlin.math.roundToInt

/**
 * 아래에서 올라오는 시트(StarLight의 `SlideBottomSheetDialog`를 옮겼다).
 *
 * 시트의 면은 내용 레이아웃이 그린다(`bg_sheet`). 이 클래스는 올라오고 내려가는 움직임과 뒤의 어둠을 맡는다: 어둠은
 * 시트가 올라온 만큼만 짙어지고, 손으로 끌어내릴 때도 따라간다. 움직임 줄이기가 켜져 있으면 바로 나타나고 사라진다.
 * 넓은 화면에서는 본문과 같은 폭(`content_max_width`)으로 묶인다. 내비게이션 막대만큼의 아래 여백은 Material의 시트가
 * 스스로 더하므로 여기서는 더하지 않는다.
 */
open class SheetDialog(context: Context) : BottomSheetDialog(context, R.style.ThemeOverlay_Lucent_Sheet) {

    private var animator: ValueAnimator? = null
    private var dismissing = false
    private var tracking = false
    private val motion = Motion.enabled(context)

    private val sheet: View? get() = findViewById(com.google.android.material.R.id.design_bottom_sheet)
    private val scrim: View? get() = findViewById(com.google.android.material.R.id.touch_outside)

    init {
        behavior.skipCollapsed = true
        behavior.maxHeight = (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        behavior.maxWidth = context.resources.getDimensionPixelSize(R.dimen.content_max_width)
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) = Unit

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (!tracking || animator?.isRunning == true) return
                val parent = bottomSheet.parent as? View ?: return
                if (bottomSheet.height > 0) setScrim((parent.height - bottomSheet.top).toFloat() / bottomSheet.height)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        if (dismissing) return
        val sheet = sheet ?: return
        if (!motion) {
            tracking = true
            setScrim(1f)
            return
        }
        sheet.visibility = View.INVISIBLE
        setScrim(0f)
        sheet.doOnLayout {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            tracking = true
            slide(sheet, 0f, ENTER_MS, DecelerateInterpolator(2f))
        }
    }

    override fun dismiss() {
        if (dismissing) return
        val sheet = sheet
        if (!motion || sheet == null || !sheet.isLaidOut || !isShowing || behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            removeWindow()
            return
        }
        dismissing = true
        slide(sheet, sheet.height.toFloat(), EXIT_MS, AccelerateInterpolator(1.6f)) {
            removeWindow()
            dismissing = false
        }
    }

    /** 화면이 사라질 때 함께 닫는다(회전 뒤에 시트만 남지 않게). 닫힐 때 할 일이 있으면 [onDismiss]로 준다. */
    fun closeWith(activity: ComponentActivity, onDismiss: () -> Unit = {}) {
        val closer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) removeWindow() }
        activity.lifecycle.addObserver(closer)
        setOnDismissListener {
            activity.lifecycle.removeObserver(closer)
            onDismiss()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.removeAllListeners()
        animator?.cancel()
        dismissing = false
    }

    /** 창이 이미 화면과 함께 사라졌으면 창 관리자가 예외로 알린다. 그때는 할 일이 없다. */
    private fun removeWindow() {
        try {
            super.dismiss()
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun slide(sheet: View, target: Float, duration: Long, interpolator: TimeInterpolator, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(sheet.translationY, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                val y = it.animatedValue as Float
                sheet.translationY = y
                if (sheet.height > 0) setScrim(1f - y / sheet.height)
            }
            if (onEnd != null) doOnEnd { onEnd() }
            start()
        }
    }

    private fun setScrim(visible: Float) {
        val alpha = (SCRIM_ALPHA * visible.coerceIn(0f, 1f)).roundToInt()
        scrim?.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private companion object {
        const val SCRIM_ALPHA = 128f
        const val MAX_HEIGHT_RATIO = 0.85f
        const val ENTER_MS = 300L
        const val EXIT_MS = 220L
    }
}
