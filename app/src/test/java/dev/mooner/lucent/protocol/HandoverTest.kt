package dev.mooner.lucent.protocol

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 브라우저 화면 넘겨받기(사양 6.7, 결정 0063). */
class HandoverTest {

    private fun welcome(vararg methods: String) = Welcome(methods = methods.toList())

    @Test fun `메서드 넷이 다 있어야 쓸 수 있다`() {
        assertTrue(Handover.available(welcome("history", Handover.STATE, Handover.START, Handover.INPUT, Handover.END)))
        assertFalse(Handover.available(welcome("history", Handover.STATE, Handover.START)))
        assertFalse(Handover.available(welcome("history")))
        assertFalse(Handover.available(null))
    }

    @Test fun `상태를 읽는다`() {
        val on = LucentJson.decodeFromString(HandoverState.serializer(),
            """{"active":true,"left_seconds":812,"max_seconds":900,"tabs":[{"id":"6F1A","url":"https://example.com/login","title":"로그인"}],"later":1}""")
        assertTrue(on.active)
        assertEquals(812, on.leftSeconds)
        assertEquals(listOf(HandoverTab("6F1A", "https://example.com/login", "로그인")), on.tabs)
        val off = LucentJson.decodeFromString(HandoverState.serializer(), """{"active":false,"left_seconds":null,"max_seconds":900,"tabs":[]}""")
        assertFalse(off.active)
        assertNull(off.leftSeconds)
    }

    @Test fun `입력은 종류마다 제 값만 싣는다`() {
        val click = Handover.params("T", HandoverInput.Click(412, 288))
        assertEquals(setOf("tab", "kind", "x", "y"), click.keys)
        assertEquals("click", click["kind"]!!.jsonPrimitive.content)
        assertEquals(412, click["x"]!!.jsonPrimitive.int)
        assertFalse(click["x"]!!.jsonPrimitive.isString)

        val text = Handover.params("T", HandoverInput.Text("  가 나  "))
        assertEquals(setOf("tab", "kind", "text"), text.keys)
        assertEquals("  가 나  ", text["text"]!!.jsonPrimitive.content)

        val key = Handover.params("T", HandoverInput.Key("enter"))
        assertEquals(setOf("tab", "kind", "key"), key.keys)

        val scroll = Handover.params("T", HandoverInput.Scroll(-400))
        assertEquals(setOf("tab", "kind", "dy"), scroll.keys)
        assertEquals(-400, scroll["dy"]!!.jsonPrimitive.int)
    }
}
