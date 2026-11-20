package dev.mooner.lucent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandReplyTest {

    @Test fun `명령의 답에서 지금 값을 읽는다 - 확실할 때만`() {
        val options = listOf("엄격", "기본", "해제")
        assertEquals("기본", CommandReply.current("🛡️ 권한 모드: **기본** — 위험한 도구는 승인 카드를 거쳐요.", options))
        // 굵게 쓴 것이 하나면 다른 이름이 글에 섞여 있어도 그것이다.
        assertEquals("엄격", CommandReply.current("권한 모드: **엄격** — 기본보다 꼼꼼히 확인해요.", options))
        // 굵게 쓴 것이 없으면 이름이 하나만 나올 때.
        assertEquals("해제", CommandReply.current("지금은 해제 상태예요.", options))
        // 어느 것인지 알 수 없으면 모른다.
        assertNull(CommandReply.current("쓸 수 있는 모드는 엄격, 기본, 해제예요.", options))
        assertNull(CommandReply.current("**엄격** 또는 **기본**", options))
        assertNull(CommandReply.current("알 수 없어요", options))
        assertNull(CommandReply.current(null, options))
    }
}
