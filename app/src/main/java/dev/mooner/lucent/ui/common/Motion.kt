package dev.mooner.lucent.ui.common

import android.content.Context
import android.provider.Settings

/** 시스템의 "움직임 줄이기"(애니메이션 배율 0)를 따른다. 꺼져 있으면 앱이 직접 돌리는 움직임도 멈춘다. */
object Motion {
    fun enabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
}
