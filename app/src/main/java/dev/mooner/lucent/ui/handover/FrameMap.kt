package dev.mooner.lucent.ui.handover

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 넘겨받은 브라우저 화면(그림)을 뷰에 놓는 자리와, 뷰에서 누른 곳을 그림의 화소로 되돌리는 셈(사양 6.7: 줄여 그렸으면
 * 누른 자리를 그림의 실제 화소로 되돌려 보낸다).
 *
 * 그림은 폭을 뷰에 맞춰 위쪽에 놓는 것이 기본이다(브라우저 화면은 휴대폰보다 넓다). [zoom]은 그 기본에 대한 배율이고
 * 1에서 [MAX_ZOOM]까지다. [left], [top]은 그림의 왼쪽 위가 뷰에서 놓이는 자리(화소)다.
 */
data class FrameMap(
    val viewWidth: Int,
    val viewHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val zoom: Float = 1f,
    val left: Float = 0f,
    val top: Float = 0f,
) {
    private val usable get() = viewWidth > 0 && viewHeight > 0 && imageWidth > 0 && imageHeight > 0

    /** 그림 한 화소가 뷰에서 차지하는 크기. */
    val scale: Float get() = if (usable) viewWidth.toFloat() / imageWidth * zoom else 1f

    val drawnWidth: Float get() = imageWidth * scale
    val drawnHeight: Float get() = imageHeight * scale

    /** 뷰의 한 점이 그림의 어느 화소인가. 그림 밖이면 null(누른 것으로 치지 않는다). */
    fun toImage(x: Float, y: Float): Pair<Int, Int>? {
        if (!usable) return null
        val ix = floor((x - left) / scale).toInt()
        val iy = floor((y - top) / scale).toInt()
        return if (ix in 0 until imageWidth && iy in 0 until imageHeight) ix to iy else null
    }

    /** 끌어서 옮긴다. 그림이 뷰보다 큰 쪽으로만 움직이고, 빈 곳이 보이게 벗어나지 않는다. */
    fun panned(dx: Float, dy: Float): FrameMap = copy(left = left + dx, top = top + dy).settled()

    /** 뷰의 ([focusX], [focusY])에 보이던 곳이 그 자리에 머물도록 배율을 바꾼다. */
    fun zoomed(factor: Float, focusX: Float, focusY: Float): FrameMap {
        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        if (next == zoom || !usable) return this
        val ratio = next / zoom
        return copy(zoom = next, left = focusX - (focusX - left) * ratio, top = focusY - (focusY - top) * ratio).settled()
    }

    /** 새 그림(크기가 달라졌을 수 있다)이나 새 뷰 크기에 맞춘다. 보던 배율과 자리는 되도록 지킨다. */
    fun resized(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): FrameMap =
        copy(viewWidth = viewWidth, viewHeight = viewHeight, imageWidth = imageWidth, imageHeight = imageHeight).settled()

    private fun settled(): FrameMap {
        if (!usable) return copy(zoom = 1f, left = 0f, top = 0f)
        // 뷰보다 작은 쪽은 왼쪽 위에 붙이고, 큰 쪽은 가장자리 안에서만 움직인다.
        val minLeft = min(0f, viewWidth - drawnWidth)
        val minTop = min(0f, viewHeight - drawnHeight)
        return copy(left = max(minLeft, min(0f, left)), top = max(minTop, min(0f, top)))
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}
