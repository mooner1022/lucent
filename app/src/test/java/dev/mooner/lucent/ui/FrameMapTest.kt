package dev.mooner.lucent.ui

import dev.mooner.lucent.ui.handover.FrameMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 넘겨받은 브라우저 화면에서 누른 자리를 그림의 화소로 되돌리는 셈. */
class FrameMapTest {

    /** 휴대폰(1080×1700)에 브라우저 화면(1365×812)을 폭에 맞춰 놓은 것. */
    private val fit = FrameMap(0, 0, 0, 0).resized(1080, 1700, 1365, 812)

    @Test fun `폭에 맞춰 위쪽에 놓는다`() {
        assertEquals(1080f, fit.drawnWidth, 0.01f)
        assertEquals(812 * 1080f / 1365, fit.drawnHeight, 0.01f)
        assertEquals(0f, fit.left, 0f)
        assertEquals(0f, fit.top, 0f)
    }

    @Test fun `누른 곳을 그림의 화소로 되돌린다`() {
        assertEquals(0 to 0, fit.toImage(0f, 0f))
        // 뷰의 한가운데 폭은 그림의 한가운데 폭이다.
        assertEquals(682, fit.toImage(540f, 100f)!!.first)
        assertEquals((100 / fit.scale).toInt(), fit.toImage(540f, 100f)!!.second)
        // 오른쪽 아래 끝의 화소.
        assertEquals(1364 to 811, fit.toImage(1079.9f, fit.drawnHeight - 0.1f))
    }

    @Test fun `그림 밖을 누른 것은 누른 것이 아니다`() {
        assertNull(fit.toImage(540f, fit.drawnHeight + 1))
        assertNull(fit.toImage(-1f, 10f))
        assertNull(fit.toImage(1080f, 10f))
        assertNull(FrameMap(1080, 1700, 0, 0).toImage(10f, 10f))
    }

    @Test fun `키운 뒤에도 누른 곳이 맞는다`() {
        // 그림이 뷰보다 큰 쪽에서만 손가락 밑의 자리가 지켜진다. 낮은 뷰(가로로 든 휴대폰)로 본다.
        val fit = FrameMap(0, 0, 0, 0).resized(1080, 600, 1365, 812)
        val before = fit.toImage(300f, 200f)!!
        val zoomed = fit.zoomed(2f, 300f, 200f)
        assertEquals(2f, zoomed.zoom, 0f)
        // 손가락 밑의 화소는 그대로다.
        assertEquals(before, zoomed.toImage(300f, 200f))
        // 옮기면 같은 화소가 옮긴 만큼 따라간다.
        val moved = zoomed.panned(-100f, -50f)
        assertEquals(before, moved.toImage(200f, 150f))
    }

    @Test fun `키워도 뷰보다 작은 쪽은 위에 붙어 있다`() {
        val zoomed = fit.zoomed(2f, 300f, 200f)
        assertEquals(0f, zoomed.top, 0f)
        assertEquals(fit.toImage(300f, 200f)!!.first, zoomed.toImage(300f, 200f)!!.first)
    }

    @Test fun `배율과 자리는 범위를 벗어나지 않는다`() {
        assertEquals(1f, fit.zoomed(0.2f, 0f, 0f).zoom, 0f)
        assertEquals(FrameMap.MAX_ZOOM, fit.zoomed(100f, 0f, 0f).zoom, 0f)
        // 뷰보다 작으면 움직이지 않는다.
        assertEquals(fit, fit.panned(300f, 300f))
        assertEquals(fit, fit.panned(-300f, -300f))
        // 키운 그림은 가장자리 안에서만 움직인다.
        val big = fit.zoomed(4f, 0f, 0f).panned(-100_000f, -100_000f)
        assertEquals(1080 - big.drawnWidth, big.left, 0.01f)
        assertEquals(1700 - big.drawnHeight, big.top, 0.01f)
        assertEquals(1364 to 811, big.toImage(1079.9f, 1699.9f))
        val back = big.panned(100_000f, 100_000f)
        assertEquals(0f, back.left, 0f)
        assertEquals(0f, back.top, 0f)
    }

    @Test fun `그림의 크기가 바뀌어도 배율은 남는다`() {
        val zoomed = fit.zoomed(2f, 0f, 0f).panned(-500f, -100f)
        val next = zoomed.resized(1080, 1700, 1280, 720)
        assertEquals(2f, next.zoom, 0f)
        assertEquals(1280 * (1080f / 1280) * 2, next.drawnWidth, 0.01f)
    }
}
