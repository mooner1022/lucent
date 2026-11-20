package dev.mooner.lucent.ui.chat

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 대화에 놓는 그림 한 장의 자리(docs/design.md 7장). 모두 화소 단위다.
 *
 * 그림은 한 변이 `max`를 넘지 않게 줄여 보인다. 그러면 세로로 아주 긴 그림(긴 화면 갈무리)은 실 같은 띠가 되고, 아주 작은
 * 그림(아이콘)은 누르기 힘든 점이 된다. 그래서 자리에는 가장 작은 크기가 있다:
 * - 비율이 한쪽으로 치우친 그림은 자리를 채우고 넘치는 쪽을 자른다([crop], 가운데를 보인다).
 * - 작은 그림은 [MAX_UPSCALE]배까지만 키워 자리의 가운데에 둔다(더 키우면 뭉개진다). 남는 곳은 자리표시 면이다.
 */
data class ImageBox(
    /** 자리의 크기. */
    val width: Int,
    val height: Int,
    /** 그 안에 그려지는 그림의 크기. [crop]이면 자리와 같다. */
    val contentWidth: Int,
    val contentHeight: Int,
    val crop: Boolean,
) {
    companion object {
        const val MAX_UPSCALE = 3f

        /** [imageWidth]×[imageHeight]의 그림이 놓일 자리. 크기를 모르면(0 이하) 가장 작은 자리다. */
        fun fit(imageWidth: Int, imageHeight: Int, minWidth: Int, minHeight: Int, max: Int): ImageBox {
            if (imageWidth <= 0 || imageHeight <= 0) return ImageBox(minWidth, minHeight, minWidth, minHeight, crop = true)
            val scale = min(min(max.toFloat() / imageWidth, max.toFloat() / imageHeight), MAX_UPSCALE)
            val shownWidth = (imageWidth * scale).roundToInt().coerceAtLeast(1)
            val shownHeight = (imageHeight * scale).roundToInt().coerceAtLeast(1)
            val width = shownWidth.coerceIn(minWidth, max)
            val height = shownHeight.coerceIn(minHeight, max)
            // 자리를 채우려면 그림을 이만큼 키워야 한다. 그 정도가 지나치면 채우지 않고 가운데에 둔다.
            val fill = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            return if (fill <= MAX_UPSCALE) ImageBox(width, height, width, height, crop = true)
            else ImageBox(width, height, shownWidth, shownHeight, crop = false)
        }
    }
}
