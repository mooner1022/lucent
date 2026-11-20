package dev.mooner.lucent.ui.sky

import android.app.Activity
import android.content.res.ColorStateList
import androidx.core.view.WindowCompat
import androidx.core.widget.ImageViewCompat
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.ViewPageHeaderBinding
import java.util.Calendar

/**
 * 설정·연결 화면 머리말의 하늘. 대화 화면과 같은 띠를 쓰되 달과 색만 있다: 장면은 지금 시각이, 기색은 연결 상태가 정한다.
 * 제목·뒤로 가기·상태 표시줄 아이콘의 색은 테마가 아니라 띠의 밝기를 따른다.
 */
class PageSky(private val activity: Activity, private val header: ViewPageHeaderBinding) {

    private var mood = Mood.CALM

    init {
        header.sky.onBandChanged = { applyBand() }
    }

    /** 화면이 앞에 올 때마다 부른다. 그 사이 시각이 넘어갔으면 장면이 바뀐다. */
    fun refresh(mood: Mood = this.mood, animate: Boolean = true) {
        this.mood = mood
        val now = Calendar.getInstance()
        header.sky.moonPhase = Moon.phase(now.timeInMillis)
        header.sky.show(SkyPhase.at(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)), mood, animate)
        applyBand()
    }

    private fun applyBand() {
        val dark = header.sky.isBandDark
        val color = activity.getColor(if (dark) R.color.band_white else R.color.band_ink)
        header.pageTitle.setTextColor(color)
        ImageViewCompat.setImageTintList(header.back, ColorStateList.valueOf(color))
        WindowCompat.getInsetsController(activity.window, header.root).isAppearanceLightStatusBars = !dark
    }
}
