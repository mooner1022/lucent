package dev.mooner.lucent.ui.common

import android.widget.ImageView
import coil.decode.BitmapFactoryDecoder
import coil.dispose
import coil.load
import java.io.File

/** 이모지·스티커 그림을 그림 칸에 싣는다(스티커 줄, 고르는 판, 자동 완성). 글 속의 이모지는 [EmojiSpans]가 그린다. */
object ExpressionImage {
    /**
     * [file]은 이미 받아 둔 그림이다. 움직이는 그림은 화면에 붙어 있는 동안만 돌고, 시스템의 "움직임 줄이기"가 켜져 있으면
     * 첫 장면만 보인다(app-expressions.md 4.3). 디코더가 받지 못하면 [onError]가 불린다(대체 표시로 바꾼다).
     */
    fun load(view: ImageView, file: File, onError: () -> Unit = {}) {
        view.load(file) {
            crossfade(false)
            if (!Motion.enabled(view.context)) decoderFactory(BitmapFactoryDecoder.Factory())
            listener(onError = { _, _ -> onError() })
        }
    }

    fun clear(view: ImageView) {
        view.dispose()
        view.setImageDrawable(null)
    }
}
