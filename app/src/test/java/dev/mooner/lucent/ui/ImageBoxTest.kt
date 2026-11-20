package dev.mooner.lucent.ui

import dev.mooner.lucent.ui.chat.ImageBox
import org.junit.Assert.assertEquals
import org.junit.Test

/** 대화 속 그림의 자리(docs/design.md 7장). 시험 기기의 값: 가장 작은 자리 315×210, 한 변의 한도 630. */
class ImageBoxTest {

    private fun fit(width: Int, height: Int) = ImageBox.fit(width, height, minWidth = 315, minHeight = 210, max = 630)

    @Test
    fun `an ordinary photo is scaled to fit and not cut`() {
        assertEquals(ImageBox(504, 630, 504, 630, crop = true), fit(1080, 1350))
        assertEquals(ImageBox(630, 354, 630, 354, crop = true), fit(1920, 1080))
    }

    @Test
    fun `a long capture fills the narrowest box and is cut`() {
        // 줄이기만 하면 폭 54짜리 띠가 된다.
        assertEquals(ImageBox(315, 630, 315, 630, crop = true), fit(360, 4200))
        assertEquals(ImageBox(315, 630, 315, 630, crop = true), fit(1080, 12000))
    }

    @Test
    fun `a wide strip fills the lowest box and is cut`() {
        assertEquals(ImageBox(630, 210, 630, 210, crop = true), fit(4000, 300))
    }

    @Test
    fun `a small picture is enlarged a little and sits in the middle`() {
        assertEquals(ImageBox(315, 210, 144, 144, crop = false), fit(48, 48))
        // 조금만 키우면 자리가 차는 그림은 채운다.
        assertEquals(ImageBox(315, 315, 315, 315, crop = true), fit(105, 105))
    }

    @Test
    fun `an unknown size takes the smallest box`() {
        assertEquals(ImageBox(315, 210, 315, 210, crop = true), fit(0, 0))
    }
}
