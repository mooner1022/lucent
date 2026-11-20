package dev.mooner.lucent.ui

import dev.mooner.lucent.ui.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    private val now = 1_763_600_000_000L

    @Test fun `지난 때는 기기 언어와 상관없이 한국어로 짧게 적는다`() {
        assertEquals("방금", Format.ago(now - 30_000, now))
        assertEquals("5분 전", Format.ago(now - 5 * 60_000, now))
        assertEquals("3시간 전", Format.ago(now - 3 * 3_600_000, now))
        assertEquals("방금", Format.ago(now + 10_000, now)) // 시계가 어긋나 앞서 있어도
        val old = Format.ago(now - 3 * 86_400_000L, now)
        assertTrue(old, old.matches(Regex("\\d{1,2}월 \\d{1,2}일 .+")))
    }
}
