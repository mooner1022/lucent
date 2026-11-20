package dev.mooner.lucent.ui.sky

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import dev.mooner.lucent.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 머리말의 하늘(docs/design.md 6장). 그림이 아니라 색과 달 하나, 밤의 별 몇 개가 전부다.
 *
 * - 장면([SkyPhase])은 지금 시각이 정한다: 새벽·낮은 밝은 띠, 노을·밤은 어두운 띠. 장면이 바뀌면 색이 천천히 넘어간다.
 * - 달은 띠의 끝에 크게 걸쳐 있다(위와 끝으로 잘려 나간다). 또렷한 아이콘이 아니라 은은한 빛의 면이라 그 위에 놓인 메뉴
 *   단추나 상태 표시줄 아이콘을 가리지 않고, 누르는 것으로 보이지도 않는다. 모양은 오늘의 실제 위상이다([Moon]).
 * - 기색([Mood])이 하늘을 바꾼다: 연결이 없으면 안개가 끼어 색이 가라앉고 별이 사라지며, 루시아가 내 답을 기다리면
 *   달무리가 금빛으로 숨 쉰다.
 * - 띠의 아래쪽 색은 [onLookChanged]로 알린다. 화면은 그 빛을 머리말 아래 종이에 옅게 번지게 한다.
 *
 * 별은 글자 뒤에 두지 않는다. 반짝임은 느려서 초당 몇 장이면 충분하므로 그만큼만 다시 그린다(StarLight의 SkyEmblemView와
 * 같은 방식). 시스템의 움직임 줄이기가 켜져 있으면 움직이지 않는다.
 */
class SkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 한 순간의 하늘. 장면과 기색이 바뀌면 이 값들 사이를 섞어 넘어간다. */
    private class Look(
        val top: Int,
        val bottom: Int,
        /** 노을빛(0..1). */
        val glow: Float,
        /** 별의 밝기(0..1). */
        val stars: Float,
        /** 달의 빛나는 면과 그늘진 면의 불투명도(0..1). */
        val moonLit: Float,
        val moonShade: Float,
        /** 달무리의 세기(0..1)와 금빛 정도(0..1). */
        val halo: Float,
        val gold: Float,
    ) {
        val dark: Boolean get() = ColorUtils.calculateLuminance(ColorUtils.blendARGB(top, bottom, 0.5f)) < DARK_BELOW

        fun mix(to: Look, t: Float) = Look(
            top = ColorUtils.blendARGB(top, to.top, t),
            bottom = ColorUtils.blendARGB(bottom, to.bottom, t),
            glow = lerp(glow, to.glow, t),
            stars = lerp(stars, to.stars, t),
            moonLit = lerp(moonLit, to.moonLit, t),
            moonShade = lerp(moonShade, to.moonShade, t),
            halo = lerp(halo, to.halo, t),
            gold = lerp(gold, to.gold, t),
        )
    }

    private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

    private val density = resources.displayMetrics.density
    private val corner = resources.getDimension(R.dimen.radius_l)
    private val moonRadius = resources.getDimension(R.dimen.moon_size) / 2f
    private val moonFromEnd = resources.getDimension(R.dimen.moon_center_from_end)
    private val starEdgeGap = resources.getDimension(R.dimen.space_s)

    private val moonColor = context.getColor(R.color.sky_moon)
    private val moonGlowColor = context.getColor(R.color.sky_moon_glow)
    private val goldColor = context.getColor(R.color.gold)
    private val starColor = context.getColor(R.color.sky_star)
    private val duskGlowColor = context.getColor(R.color.sky_dusk_glow)

    private val animates = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    private var phase = SkyPhase.NIGHT
    private var mood = Mood.CALM

    /** 0은 삭, 0.5는 보름([Moon.phase]). */
    var moonPhase: Float = 0.5f
        set(value) {
            if (abs(field - value) < 0.002f) return
            field = value
            invalidate()
        }

    /** 상태 표시줄 높이. 달은 그 아래 머리말 줄의 가운데 높이에 뜬다. */
    var contentTop: Int = 0
        set(value) {
            if (field == value) return
            field = value
            generateStars(width, height)
            invalidate()
        }

    /** 넓은 화면에서 본문을 가운데로 묶을 때의 옆 여백. 달은 본문의 끝을 따라 안쪽으로 들어온다. */
    var contentSide: Int = 0
        set(value) {
            if (field == value) return
            field = value
            generateStars(width, height)
            invalidate()
        }

    /** 띠가 어두운지(흰 글자를 써야 하는지)가 바뀌면 불린다. 글자색과 상태 표시줄 아이콘이 이 값을 따른다. */
    var onBandChanged: ((dark: Boolean) -> Unit)? = null

    /** 띠의 색이 바뀔 때마다(넘어가는 중에도) 아래쪽 색을 알린다. */
    var onLookChanged: ((bottom: Int) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(current.bottom)
        }

    /** 지금 향하는 하늘이 어두운 띠인가. */
    val isBandDark: Boolean get() = target.dark

    private var from: Look = lookOf(phase, mood)
    private var target: Look = from
    private var current: Look = from
    private var transition: ValueAnimator? = null

    private val bandPath = Path()
    private val bandRect = RectF()
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = starColor }
    private val moonPath = Path()
    private val moonOval = RectF()

    private val stars = ArrayList<Star>()
    /** 창까지 포함해 지금 화면에 보이는가. */
    private var shown = false
    private var time = 0f
    private var lastDrawnAt = 0f
    private val ticker = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            time = total / 1000f
            if (time - lastDrawnAt >= FRAME_INTERVAL) {
                lastDrawnAt = time
                invalidate()
            }
        }
    }

    init {
        // 레이아웃 미리보기와 접근성 도구에는 장식일 뿐이다.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 장면과 기색을 정한다. 처음 그릴 때가 아니면 색이 부드럽게 넘어간다. */
    fun show(phase: SkyPhase, mood: Mood, animate: Boolean = true) {
        if (phase == this.phase && mood == this.mood) return
        val wasDark = target.dark
        this.phase = phase
        this.mood = mood
        val next = lookOf(phase, mood)
        transition?.cancel()
        if (animate && animates && isLaidOut) {
            from = current
            target = next
            transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TRANSITION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    current = from.mix(target, it.animatedFraction)
                    rebuildShaders()
                    invalidate()
                }
                start()
            }
        } else {
            from = next
            target = next
            current = next
            rebuildShaders()
            invalidate()
        }
        if (wasDark != next.dark) onBandChanged?.invoke(next.dark)
        updateTicker()
    }

    // ------------------------------------------------------------------ 장면

    private fun lookOf(phase: SkyPhase, mood: Mood): Look {
        val (topRes, bottomRes) = when (phase) {
            SkyPhase.DAWN -> R.color.sky_dawn_top to R.color.sky_dawn_bottom
            SkyPhase.DAY -> R.color.sky_day_top to R.color.sky_day_bottom
            SkyPhase.DUSK -> R.color.sky_dusk_top to R.color.sky_dusk_bottom
            SkyPhase.NIGHT -> R.color.sky_night_top to R.color.sky_night_bottom
        }
        var top = context.getColor(topRes)
        var bottom = context.getColor(bottomRes)
        val dark = ColorUtils.calculateLuminance(ColorUtils.blendARGB(top, bottom, 0.5f)) < DARK_BELOW
        val offline = mood == Mood.OFFLINE
        val dim = mood == Mood.CONNECTING

        // 연결이 없으면 안개가 낀다. 띠의 밝기(글자색)는 바꾸지 않도록 같은 밝기 쪽의 안개를 섞는다.
        val veil = if (offline) VEIL_OFFLINE else if (dim) VEIL_CONNECTING else 0f
        if (veil > 0f) {
            val veilColor = context.getColor(if (dark) R.color.sky_veil_dark else R.color.sky_veil_light)
            top = ColorUtils.blendARGB(top, veilColor, veil)
            bottom = ColorUtils.blendARGB(bottom, veilColor, veil)
        }
        val clear = 1f - veil / VEIL_OFFLINE // 1 맑음 … 0 안개

        val stars = when (phase) {
            SkyPhase.NIGHT -> 1f
            SkyPhase.DUSK -> 0.55f
            SkyPhase.DAWN -> if (dark) 0.3f else 0f
            SkyPhase.DAY -> 0f
        } * clear
        // 달은 빛의 면이다: 어두운 띠에서는 옅게 비치고, 밝은 띠에서는 흰 낮달로 또렷해진다.
        val moonLit = (if (dark) MOON_ON_DARK else MOON_ON_LIGHT) * when (phase) {
            SkyPhase.NIGHT -> 1f
            SkyPhase.DUSK -> 0.85f
            SkyPhase.DAWN -> 0.9f
            SkyPhase.DAY -> if (dark) 0.8f else 1f
        } * (0.45f + 0.55f * clear)
        val attention = mood == Mood.ATTENTION
        return Look(
            top = top,
            bottom = bottom,
            glow = if (phase == SkyPhase.DUSK) 0.38f * clear else 0f,
            stars = stars,
            moonLit = moonLit,
            moonShade = moonLit * SHADE_SHARE,
            halo = (if (dark) 0.45f else 0.3f) * clear + if (attention) 0.35f else 0f,
            gold = if (attention) 1f else 0f,
        )
    }

    // ------------------------------------------------------------------ 크기, 셰이더

    /** 하늘은 제 크기가 없다. 머리말(내용)이 정한 높이를 그대로 채운다. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bandRect.set(0f, 0f, w.toFloat(), h.toFloat())
        bandPath.reset()
        // 아래 두 모서리만 둥글다(radius_l). 클립 대신 길로 칠해 가장자리가 매끄럽다.
        bandPath.addRoundRect(bandRect, floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner), Path.Direction.CW)
        generateStars(w, h)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        onLookChanged?.invoke(current.bottom)
        if (width == 0 || height == 0) return
        skyPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), current.top, current.bottom, Shader.TileMode.CLAMP)
        val end = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0f else width.toFloat()
        glowPaint.shader = RadialGradient(
            end, height.toFloat(), height * 1.6f,
            ColorUtils.setAlphaComponent(duskGlowColor, 255), ColorUtils.setAlphaComponent(duskGlowColor, 0),
            Shader.TileMode.CLAMP,
        )
    }

    private fun generateStars(w: Int, h: Int) {
        stars.clear()
        if (w == 0 || h == 0) return
        val random = Random(STAR_SEED)
        val count = (w / density / STAR_EVERY_DP).toInt().coerceIn(5, 16)
        val moonX = moonCenterX()
        var guard = 0
        // 글자 뒤에도, 상태 표시줄 아이콘 곁에도 두지 않는다: 머리말 줄의 끝 쪽 절반, 달의 바깥에만.
        val from = w * STAR_FROM
        val to = w - contentSide - starEdgeGap
        val top = contentTop + starEdgeGap
        val bottom = h - starEdgeGap
        if (to <= from || bottom <= top) return
        val moonY = moonCenterY()
        val keepOut = moonRadius + starEdgeGap
        while (stars.size < count && guard++ < count * 12) {
            val fx = from + random.nextFloat() * (to - from)
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) w - fx else fx
            val y = top + random.nextFloat() * (bottom - top)
            if (hypot(x - moonX, y - moonY) < keepOut) continue
            stars += Star(x, y, (0.55f + random.nextFloat() * 0.75f) * density, random.nextFloat() * TWO_PI, 0.5f + random.nextFloat() * 0.9f)
        }
    }

    private fun moonCenterX() =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) contentSide + moonFromEnd else width - contentSide - moonFromEnd

    /** 달의 중심은 머리말 줄의 위쪽에 있다. 머리말이 커지면(인사) 달도 따라 내려와 더 많이 드러난다. */
    private fun moonCenterY() = contentTop + (height - contentTop) * MOON_Y

    // ------------------------------------------------------------------ 그리기

    override fun onDraw(canvas: Canvas) {
        val look = current
        canvas.drawPath(bandPath, skyPaint)
        if (look.glow > 0.01f) {
            glowPaint.alpha = (look.glow * 255).toInt()
            canvas.drawPath(bandPath, glowPaint)
        }
        drawStars(canvas, look)
        drawMoon(canvas, look)
    }

    private fun drawStars(canvas: Canvas, look: Look) {
        if (look.stars <= 0.01f) return
        for (star in stars) {
            val twinkle = if (animates) 0.55f + 0.45f * sin(time * star.speed + star.phase) else 0.8f
            starPaint.alpha = (look.stars * twinkle * 215).toInt().coerceIn(0, 255)
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }
    }

    private fun drawMoon(canvas: Canvas, look: Look) {
        val cx = moonCenterX()
        val cy = moonCenterY()
        val r = moonRadius

        // 달무리: 달의 둘레에서 바깥으로 스러진다. 루시아가 답을 기다리면 금빛으로 천천히 숨 쉰다.
        val breath = if (look.gold > 0.01f && animates) 0.5f + 0.5f * sin(time * TWO_PI / BREATH_SECONDS) else 0.5f
        val haloAlpha = (look.halo * (1f - 0.4f * look.gold * (1f - breath))).coerceIn(0f, 1f)
        if (haloAlpha > 0.01f) {
            val haloColor = ColorUtils.blendARGB(moonGlowColor, goldColor, look.gold)
            val haloRadius = r * HALO_RADIUS
            val rim = ColorUtils.setAlphaComponent(haloColor, (haloAlpha * 90).toInt())
            haloPaint.shader = RadialGradient(
                cx, cy, haloRadius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, rim, Color.TRANSPARENT),
                floatArrayOf(0f, 1f / HALO_RADIUS - 0.02f, 1f / HALO_RADIUS, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.save()
            canvas.clipPath(bandPath)
            canvas.drawCircle(cx, cy, haloRadius, haloPaint)
            canvas.restore()
        }

        // 그늘진 면까지 온 달을 아주 옅게, 그 위에 빛나는 면을 그린다. 띠 밖으로 나간 부분은 자른다.
        canvas.save()
        canvas.clipPath(bandPath)
        moonPaint.color = moonColor
        moonPaint.alpha = (look.moonShade * 255).toInt()
        canvas.drawCircle(cx, cy, r, moonPaint)
        moonPaint.alpha = (look.moonLit * 255).toInt()
        canvas.drawPath(litPath(cx, cy, r), moonPaint)
        canvas.restore()
    }

    /**
     * 빛나는 면의 길. 반원(빛나는 쪽 가장자리)과 반타원(명암 경계)으로 이뤄진다. 차오를 때는 오른쪽이, 기울 때는 왼쪽이
     * 빛난다(북반구에서 보는 모양).
     */
    private fun litPath(cx: Float, cy: Float, r: Float): Path {
        val p = moonPhase
        val waxing = p < 0.5f
        // 1(삭) … 0(반달) … -1(보름): 명암 경계 타원의 가로 반지름과 방향.
        val k = cos(2.0 * PI * p).toFloat()
        moonPath.reset()
        moonOval.set(cx - r, cy - r, cx + r, cy + r)
        // 빛나는 쪽 반원: 위에서 아래로.
        moonPath.arcTo(moonOval, -90f, if (waxing) 180f else -180f, true)
        // 명암 경계: 아래에서 위로 돌아온다.
        val rx = abs(k) * r
        moonOval.set(cx - rx, cy - r, cx + rx, cy + r)
        val towardLit = k > 0f // 초승·그믐: 경계가 빛나는 쪽으로 부푼다
        val sweep = if (waxing == towardLit) -180f else 180f
        moonPath.arcTo(moonOval, 90f, sweep, false)
        moonPath.close()
        return moonPath
    }

    // ------------------------------------------------------------------ 움직임

    private fun updateTicker() {
        val wanted = animates && isAttachedToWindow && shown && (target.stars > 0.01f || target.gold > 0.01f)
        if (wanted && !ticker.isStarted) {
            // 다시 돌기 시작하면 시계가 0부터 센다. 마지막으로 그린 때도 같이 되돌려야 바로 이어 그린다.
            lastDrawnAt = 0f
            ticker.start()
        }
        if (!wanted && ticker.isStarted) ticker.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        transition?.cancel()
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
        const val TRANSITION_MS = 900L
        /** 반짝임과 숨은 느리다. 초당 열두 장이면 이어져 보인다. */
        const val FRAME_INTERVAL = 1f / 12f
        const val BREATH_SECONDS = 3.2f
        const val TWO_PI = (2 * PI).toFloat()
        const val DARK_BELOW = 0.32
        const val VEIL_OFFLINE = 0.62f
        const val VEIL_CONNECTING = 0.22f
        const val HALO_RADIUS = 1.45f
        /** 빛나는 면의 불투명도: 어두운 띠 위에서는 비치는 정도, 밝은 띠 위에서는 흰 낮달. */
        const val MOON_ON_DARK = 0.26f
        const val MOON_ON_LIGHT = 0.62f
        /** 그늘진 면은 빛나는 면의 이만큼만 보인다. */
        const val SHADE_SHARE = 0.22f
        /** 달 중심의 세로 위치(머리말 줄 높이에 대한 비율). */
        const val MOON_Y = 0.24f
        const val STAR_SEED = 11
        const val STAR_EVERY_DP = 30f
        /** 별이 놓이기 시작하는 가로 위치(띠 너비에 대한 비율). */
        const val STAR_FROM = 0.42f

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
