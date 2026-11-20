package dev.mooner.lucent.ui.common

import android.app.Activity
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import dev.mooner.lucent.LucentHub.Notice
import dev.mooner.lucent.R
import dev.mooner.peekalert.PeekAlert
import dev.mooner.peekalert.createPeekAlert
import kotlin.math.roundToInt

/**
 * 잠깐 뜨는 안내(StarLight의 `Peeks`를 옮겼다. 라이브러리도 같은 PeekAlert다).
 *
 * 안내는 모두 같은 조용한 카드다: 면(`surface`)과 가는 윤곽선, 얕은 그림자. 무슨 안내인지는 카드의 색이 아니라 아이콘과
 * 머무는 시간이 말한다. 답으로 온 것(알림, 잘 됨)은 곧 사라지고, 놓치면 안 되는 것(주의, 오류)은 더 오래 머문다.
 * 모양은 여기 한 곳에서 정한다. 화면은 무슨 말을 어느 수준으로 할지만 정한다.
 */
object Peeks {

    private class Look(val icon: Int, val tint: Int, val showMillis: Long)

    private fun lookOf(level: Notice.Level) = when (level) {
        Notice.Level.INFO -> Look(R.drawable.ic_info, R.color.sky_deep, 3_000L)
        Notice.Level.SUCCESS -> Look(R.drawable.ic_check, R.color.ok, 2_500L)
        Notice.Level.WARNING -> Look(R.drawable.ic_warning, R.color.warn, 4_500L)
        Notice.Level.ERROR -> Look(R.drawable.ic_warning, R.color.error, 5_000L)
    }

    /** [notice]를 화면 위쪽에 잠깐 보인다. 이미 떠 있는 안내가 있으면 그 앞에 겹쳐 놓인다(PeekAlert가 한다). */
    fun show(activity: Activity, notice: Notice) = show(activity, notice.text, notice.level)

    fun show(activity: Activity, text: String, level: Notice.Level = Notice.Level.INFO) {
        if (text.isBlank() || activity.isFinishing || activity.isDestroyed) return
        val res = activity.resources
        val density = res.displayMetrics.density
        fun dpOf(dimen: Int) = res.getDimension(dimen) / density
        val look = lookOf(level)
        createPeekAlert(activity) {
            // 대화 화면의 아래에는 입력창이 있다. 안내는 위(하늘 머리말 자리)에 뜬다. StarLight의 대화 화면과 같다.
            position = PeekAlert.Position.Top
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            cornerRadius = res.getDimension(R.dimen.radius_m)
            paddingDp = dpOf(R.dimen.space_m).roundToInt()
            iconSizeDp = dpOf(R.dimen.icon_m).roundToInt()
            elevationDp = dpOf(R.dimen.elevation_float)
            draggable = true
            autoHideMillis = look.showMillis
            backgroundColor(res = R.color.surface)
            stroke(res = R.color.line)
            iconRes = look.icon
            iconTint(res = look.tint)
            text(text) {
                textColor(res = R.color.ink)
                textSize = TEXT_SP
                typeface = ResourcesCompat.getFont(activity, R.font.wantedsans_medium)
                maxLines = TEXT_MAX_LINES
            }
        }.peek()
    }

    /** 글자 스타일 `Text.BodyS`와 같은 크기. */
    private const val TEXT_SP = 14f
    private const val TEXT_MAX_LINES = 4
}
