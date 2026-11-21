package dev.mooner.lucent.ui.handover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 넘겨받은 브라우저의 화면 한 장을 그린다. 한 손가락으로 끌어 옮기고 두 손가락으로 키우며, 살짝 누르면 그 자리를
 * 그림의 화소 좌표로 알린다([onTap]). 자리 셈은 [FrameMap]이 한다.
 *
 * 그림은 메모리에만 둔다(화면에 사용자의 계정 정보가 보일 수 있다). 화면 상태로도 저장하지 않는다.
 */
class FrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 그림 위의 화소 좌표로 누른 자리를 알린다. */
    var onTap: ((x: Int, y: Int) -> Unit)? = null

    private var frame: Bitmap? = null
    private var map = FrameMap(0, 0, 0, 0)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val target = RectF()

    init {
        isSaveEnabled = false
    }

    /** 새 화면. 보던 배율과 자리는 그대로 둔다. null이면 비운다. */
    fun show(bitmap: Bitmap?) {
        frame = bitmap
        map = if (bitmap == null) FrameMap(width, height, 0, 0) else map.resized(width, height, bitmap.width, bitmap.height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        map = map.resized(w, h, frame?.width ?: 0, frame?.height ?: 0)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = frame ?: return
        target.set(map.left, map.top, map.left + map.drawnWidth, map.top + map.drawnHeight)
        canvas.drawBitmap(bitmap, null, target, paint)
    }

    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            map = map.zoomed(detector.scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            map = map.panned(-distanceX, -distanceY)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            map.toImage(e.x, e.y)?.let { (x, y) -> onTap?.invoke(x, y) }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility") // 누른 자리의 좌표가 뜻이라 performClick으로 바꿀 수 없다.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scales.onTouchEvent(event)
        if (!scales.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
