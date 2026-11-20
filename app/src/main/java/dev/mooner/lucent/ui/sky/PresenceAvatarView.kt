package dev.mooner.lucent.ui.sky

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.mooner.lucent.R
import dev.mooner.lucent.ui.common.Motion
import kotlin.math.PI
import kotlin.math.sin

/**
 * 머리말의 루시아: 초상과 그 둘레의 고리. 고리가 루시아의 지금 기색([Mood])을 말한다.
 *
 * - 평소: 고리 없음.
 * - 일하는 중(쓰는 중, 진행 안내): 하늘빛 고리 위로 빛이 한 바퀴씩 돈다.
 * - 답을 기다림(승인 대기): 금빛 고리가 천천히 숨 쉰다.
 * - 연결하는 중: 짧은 빛만 돈다.
 * - 연결 없음: 고리가 없고 초상의 색이 빠진다.
 *
 * 초상은 다른 이미지와 같은 모서리(radius_image)이고, 고리는 그 바깥을 같은 중심으로 돈다.
 */
class PresenceAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val inset = resources.getDimension(R.dimen.presence_inset)
    private val ringWidth = resources.getDimension(R.dimen.presence_ring)
    private val imageRadius = resources.getDimension(R.dimen.radius_image)

    private val animates = Motion.enabled(context)

    private val portraitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** 얼굴이 바뀌는 동안, 사라지는 앞의 얼굴. */
    private val leavingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val portraitRect = RectF()
    private val ringPath = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val saturation = ColorMatrix()

    private var mood = Mood.CALM
    /** 띠가 어두우면 밝은 고리를, 밝으면 깊은 고리를 쓴다. */
    private var onDark = true
    /** 1은 제 색, 0은 색이 빠진 초상. */
    private var vivid = 1f
    private var vividAnimator: ValueAnimator? = null

    /** 루시아가 정한 프로필 그림. null이면 앱의 기본 모습(ic_avatar_placeholder)이다. */
    private var face: Bitmap? = null
    /** 새 얼굴이 얼마나 드러났는가(0~1). 1이면 앞의 얼굴은 그리지 않는다. */
    private var arrived = 1f
    private var faceAnimator: ValueAnimator? = null

    /** 창까지 포함해 지금 화면에 보이는가. */
    private var shown = false
    private var time = 0f
    private val ticker = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            time = total / 1000f
            invalidate()
        }
    }

    fun show(mood: Mood, onDark: Boolean) {
        if (mood == this.mood && onDark == this.onDark) return
        val wasOffline = this.mood == Mood.OFFLINE
        this.mood = mood
        this.onDark = onDark
        val offline = mood == Mood.OFFLINE
        if (offline != wasOffline) fadeVivid(if (offline) 0f else 1f)
        updateTicker()
        invalidate()
    }

    /** 얼굴을 바꾼다. 보이는 중에 바뀌면 앞의 얼굴 위로 새 얼굴이 스며든다. */
    fun setFace(bitmap: Bitmap?) {
        if (bitmap === face) return
        face = bitmap
        val before = portraitPaint.shader
        if (width == 0 || height == 0) return // 아직 크기가 없다. 크기가 정해질 때 그린다.
        portraitPaint.shader = portraitShader()
        faceAnimator?.cancel()
        if (!animates || !isLaidOut || !shown || before == null) {
            finishFace()
            invalidate()
            return
        }
        leavingPaint.shader = before
        arrived = 0f
        faceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener {
                arrived = it.animatedValue as Float
                if (arrived >= 1f) finishFace()
                invalidate()
            }
            start()
        }
    }

    private fun finishFace() {
        arrived = 1f
        leavingPaint.shader = null
    }

    /** 지금 얼굴을 초상 자리에 꼭 맞게(정사각형이 아니면 가운데를) 그리는 셰이더. */
    private fun portraitShader(): Shader? {
        val size = portraitRect.width().toInt().coerceAtLeast(1)
        val bitmap = face ?: ContextCompat.getDrawable(context, R.drawable.ic_avatar_placeholder)?.toBitmap(size, size) ?: return null
        val scale = maxOf(portraitRect.width() / bitmap.width, portraitRect.height() / bitmap.height)
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    portraitRect.left + (portraitRect.width() - bitmap.width * scale) / 2f,
                    portraitRect.top + (portraitRect.height() - bitmap.height * scale) / 2f,
                )
            })
        }
    }

    private fun fadeVivid(to: Float) {
        vividAnimator?.cancel()
        if (!animates || !isLaidOut) {
            vivid = to
            applyVivid()
            return
        }
        vividAnimator = ValueAnimator.ofFloat(vivid, to).apply {
            duration = FADE_MS
            addUpdateListener {
                vivid = it.animatedValue as Float
                applyVivid()
                invalidate()
            }
            start()
        }
    }

    private fun applyVivid() {
        saturation.setSaturation(vivid)
        portraitPaint.colorFilter = if (vivid >= 0.999f) null else ColorMatrixColorFilter(saturation)
        portraitPaint.alpha = baseAlpha()
    }

    private fun baseAlpha() = (255 * (OFFLINE_ALPHA + (1f - OFFLINE_ALPHA) * vivid)).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        portraitRect.set(inset, inset, w - inset, h - inset)
        faceAnimator?.cancel()
        finishFace()
        portraitPaint.shader = portraitShader()
        // 고리는 초상과 같은 중심의 둥근 사각: 선의 가운데가 가장자리에서 반 굵기만큼 안쪽을 돈다.
        val half = ringWidth / 2f
        val ringRadius = imageRadius + inset - half
        ringPath.reset()
        ringPath.addRoundRect(RectF(half, half, w - half, h - half), ringRadius, ringRadius, Path.Direction.CW)
        measure.setPath(ringPath, true)
    }

    override fun onDraw(canvas: Canvas) {
        if (arrived < 1f && leavingPaint.shader != null) {
            // 앞의 얼굴을 바탕에 두고 새 얼굴을 그 위에 올린다. 끊긴 동안의 빠진 색과 흐림은 둘 다 같다.
            leavingPaint.colorFilter = portraitPaint.colorFilter
            leavingPaint.alpha = baseAlpha()
            canvas.drawRoundRect(portraitRect, imageRadius, imageRadius, leavingPaint)
            portraitPaint.alpha = (baseAlpha() * arrived).toInt()
            canvas.drawRoundRect(portraitRect, imageRadius, imageRadius, portraitPaint)
            portraitPaint.alpha = baseAlpha()
        } else {
            canvas.drawRoundRect(portraitRect, imageRadius, imageRadius, portraitPaint)
        }
        when (mood) {
            Mood.ACTIVE -> {
                ringPaint.color = context.getColor(if (onDark) R.color.band_sky_on_dark else R.color.band_sky_on_light)
                ringPaint.alpha = if (animates) RING_BASE_ALPHA else 255
                canvas.drawPath(ringPath, ringPaint)
                if (animates) drawRunner(canvas, ACTIVE_SECONDS, RUNNER_ACTIVE, 255)
            }
            Mood.ATTENTION -> {
                ringPaint.color = context.getColor(if (onDark) R.color.band_gold_on_dark else R.color.band_gold_on_light)
                val breath = if (animates) 0.5f + 0.5f * sin(time * 2f * PI.toFloat() / BREATH_SECONDS) else 1f
                ringPaint.alpha = (255 * (0.5f + 0.5f * breath)).toInt()
                canvas.drawPath(ringPath, ringPaint)
            }
            Mood.CONNECTING -> {
                ringPaint.color = context.getColor(if (onDark) R.color.band_white_muted else R.color.band_ink_muted)
                if (animates) drawRunner(canvas, CONNECTING_SECONDS, RUNNER_CONNECTING, 200)
                else {
                    ringPaint.alpha = RING_BASE_ALPHA
                    canvas.drawPath(ringPath, ringPaint)
                }
            }
            Mood.CALM, Mood.OFFLINE -> Unit
        }
    }

    /** 고리 위를 도는 빛 한 줄기. [share]는 고리 둘레에 대한 길이. */
    private fun drawRunner(canvas: Canvas, seconds: Float, share: Float, alpha: Int) {
        val length = measure.length
        if (length <= 0f) return
        val start = (time / seconds % 1f) * length
        val end = start + length * share
        segment.reset()
        measure.getSegment(start, end.coerceAtMost(length), segment, true)
        if (end > length) measure.getSegment(0f, end - length, segment, true)
        ringPaint.alpha = alpha
        canvas.drawPath(segment, ringPaint)
    }

    private fun updateTicker() {
        val wanted = animates && isAttachedToWindow && shown &&
            (mood == Mood.ACTIVE || mood == Mood.ATTENTION || mood == Mood.CONNECTING)
        if (wanted && !ticker.isStarted) ticker.start()
        if (!wanted && ticker.isStarted) ticker.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        vividAnimator?.cancel()
        faceAnimator?.cancel()
        finishFace()
        shown = false
        super.onDetachedFromWindow()
    }

    /** 화면이 꺼지거나 앱이 뒤로 가면(창이 보이지 않으면) 불린다. 보이지 않는 동안에는 돌지 않는다. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        updateTicker()
    }

    private companion object {
        const val FADE_MS = 350L
        const val OFFLINE_ALPHA = 0.6f
        const val RING_BASE_ALPHA = 70
        const val ACTIVE_SECONDS = 1.6f
        const val CONNECTING_SECONDS = 1.2f
        const val BREATH_SECONDS = 3.2f
        const val RUNNER_ACTIVE = 0.3f
        const val RUNNER_CONNECTING = 0.22f
    }
}
