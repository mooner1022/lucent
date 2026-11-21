package dev.mooner.lucent.ui

import dev.mooner.lucent.ui.common.Plain
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTest {
    @Test fun `카드에 붙어 오는 이모지를 걷어 낸다`() {
        assertEquals("보냈습니다", Plain.text("✅ 보냈습니다"))
        assertEquals("답이 없어 만료됐습니다", Plain.text("⌛ 답이 없어 만료됐습니다"))
        assertEquals("권한 모드: 기본", Plain.text("🛡️ 권한 모드: 기본"))
        assertEquals("거절했어요", Plain.text("❌ 거절했어요"))
        assertEquals("경고", Plain.text("⚠️ 경고"))
    }

    @Test fun `글자와 기호는 남긴다`() {
        assertEquals("관리 페이지의 연결 → 앱·기기 연결", Plain.text("관리 페이지의 연결 → 앱·기기 연결"))
        assertEquals("10:00 주간 회의 (A-1)", Plain.text("10:00 주간 회의 (A-1)"))
        assertEquals("한글과 English 123", Plain.text("한글과 English 123"))
    }

    @Test fun `이모지가 있던 자리의 공백과 줄을 정리한다`() {
        assertEquals("가 나", Plain.text("가 🎉 나"))
        assertEquals("첫 줄\n둘째 줄", Plain.text("🙂 첫 줄\n✅ 둘째 줄"))
        assertEquals("", Plain.text("✅"))
    }

    @Test fun `국기와 ZWJ 조합도 지운다`() {
        assertEquals("집", Plain.text("🏠 집"))
        assertEquals("가족", Plain.text("👨‍👩‍👧 가족"))
        assertEquals("한국", Plain.text("🇰🇷 한국"))
    }

    @Test fun `한 줄로 그리는 글에서는 굵게 표시도 걷어 낸다`() {
        assertEquals("로그인이 필요합니다 예시 사이트", Plain.line("🔐 **로그인이 필요합니다** 예시 사이트"))
        assertEquals("보냈습니다", Plain.line("✅ 보냈습니다"))
        assertEquals("2 * 3", Plain.line("2 * 3"))
    }
}
