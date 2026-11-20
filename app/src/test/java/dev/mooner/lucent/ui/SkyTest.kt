package dev.mooner.lucent.ui

import dev.mooner.lucent.ui.sky.Moon
import dev.mooner.lucent.ui.sky.SkyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

class SkyTest {

    private fun at(hour: Int, minute: Int = 0) = SkyPhase.at(hour * 60 + minute)

    @Test fun `장면은 시각을 따른다`() {
        assertEquals(SkyPhase.NIGHT, at(0))
        assertEquals(SkyPhase.NIGHT, at(4, 59))
        assertEquals(SkyPhase.DAWN, at(5))
        assertEquals(SkyPhase.DAWN, at(7, 59))
        assertEquals(SkyPhase.DAY, at(8))
        assertEquals(SkyPhase.DAY, at(17, 29))
        assertEquals(SkyPhase.DUSK, at(17, 30))
        assertEquals(SkyPhase.DUSK, at(19, 59))
        assertEquals(SkyPhase.NIGHT, at(20))
        assertEquals(SkyPhase.NIGHT, at(23, 59))
    }

    @Test fun `하루를 벗어난 분은 하루로 접는다`() {
        assertEquals(SkyPhase.at(12 * 60), SkyPhase.at(12 * 60 + 1440))
        assertEquals(SkyPhase.at(23 * 60), SkyPhase.at(-60))
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 위상은 돌고 도는 값이라 0과 1이 이웃이다. */
    private fun apart(a: Float, b: Float): Float = abs(a - b).let { min(it, 1f - it) }

    @Test fun `달의 위상은 알려진 삭과 보름에 맞는다`() {
        // 삭: 2024-04-08 18:21 UTC(개기일식), 보름: 2025-09-07 18:09 UTC(개기월식).
        assertTrue(apart(Moon.phase(utc(2024, 4, 8, 18, 21)), 0f) < 0.02f)
        assertTrue(apart(Moon.phase(utc(2025, 9, 7, 18, 9)), 0.5f) < 0.02f)
    }

    @Test fun `위상은 0 이상 1 미만이다`() {
        var t = utc(1999, 1, 1, 0, 0)
        repeat(400) {
            val p = Moon.phase(t)
            assertTrue(p >= 0f && p < 1f)
            t += 86_400_000L * 3
        }
    }
}
