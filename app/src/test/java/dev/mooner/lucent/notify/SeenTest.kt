package dev.mooner.lucent.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 봤다고 알리기와 다른 기기에서 본 알림 치우기(사양 12.6, 10.4). 모자라게 알리고, 모자라게 치운다. */
class SeenTest {

    @Test fun `대화 화면이 보일 때만, 처리한 번호가 늘었을 때만 알린다`() {
        assertEquals(12L, Seen.toReport(visible = true, processed = 12, reported = 10, floor = 0))
        assertNull(Seen.toReport(visible = false, processed = 12, reported = 10, floor = 0))
        assertNull(Seen.toReport(visible = true, processed = 10, reported = 10, floor = 0))
        assertNull(Seen.toReport(visible = true, processed = 9, reported = 10, floor = 0))
    }

    @Test fun `처음 깔 때 기록으로 채운 번호까지는 그것만으로 알리지 않는다`() {
        assertNull(Seen.toReport(visible = true, processed = 57, reported = 0, floor = 57))
        // 그 뒤의 것을 보면 번호 하나로 알린다.
        assertEquals(58L, Seen.toReport(visible = true, processed = 58, reported = 0, floor = 57))
    }

    @Test fun `번호를 모르는 알림은 울렸을 수 있는 것이 모두 본 번호 안에 있을 때만 치운다`() {
        assertTrue(Seen.clearsAll(upTo = 92, newestAlertable = 92))
        assertTrue(Seen.clearsAll(upTo = 93, newestAlertable = 92))
        assertFalse(Seen.clearsAll(upTo = 91, newestAlertable = 92))
        // 상자의 끝을 모르면(다시 받는 중, 마지막 번호를 묻지 못함) 치우지 않는다.
        assertFalse(Seen.clearsAll(upTo = 92, newestAlertable = null))
    }

    @Test fun `앱이 띄운 알림은 그 번호로 가린다`() {
        assertTrue(Notifier.covers(seq = 58, upTo = 58))
        assertFalse(Notifier.covers(seq = 59, upTo = 58))
        // 번호가 없는 알림(예전에 띄운 것)은 치우지 않는다.
        assertFalse(Notifier.covers(seq = 0, upTo = 58))
    }

    @Test fun `번호를 다시 매긴 데몬에 맞춰 줄인다`() {
        assertEquals(3L, Seen.clamp(reported = 40, last = 3))
        assertEquals(2L, Seen.clamp(reported = 2, last = 3))
        assertEquals(0L, Seen.clamp(reported = 5, last = -1))
    }
}
