package dev.mooner.lucent.protocol

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 여러 칸을 받는 버튼(`form`, 결정 0063). 값은 모두 지어낸 것이다. */
class FormsTest {

    private val loginCard = """{"type":"card","seq":10,"ref":"app:cl-1:5b0e","card":{"headline":"로그인이 필요합니다","title":"로그인 정보 입력",
        "fields":[{"name":"사이트","value":"https://example.com","inline":true}],"body":"링크: https://admin.example/site-login/x","tone":"pending",
        "buttons":[
          {"action":"approval:3f2a:approve","label":"로그인 정보 입력","primary":true,"prompt":null,
           "form":{"title":"로그인 정보 입력","fields":[
             {"key":"username","label":"아이디","kind":"text","min_len":1,"max_len":100},
             {"key":"password","label":"비밀번호","kind":"secret","min_len":1,"max_len":200},
             {"key":"save","label":"이 로그인 저장","kind":"check","min_len":0,"max_len":0}]}},
          {"action":"approval:3f2a:deny","label":"취소","primary":false,"prompt":null,"form":null}]}}"""

    private fun card() = (parseServerFrame(loginCard) as ServerFrame.CardOpen).card

    private fun field(kind: String, key: String = "k", min: Int = 1, max: Int = 10) = FormField(key, "칸", kind, min, max)

    @Test fun `카드 버튼의 form을 읽는다`() {
        val form = card().buttons[0].form!!
        assertEquals("로그인 정보 입력", form.title)
        assertEquals(listOf("username", "password", "save"), form.fields.map { it.key })
        assertEquals(listOf("text", "secret", "check"), form.fields.map { it.kind })
        assertEquals(200, form.fields[1].maxLen)
        assertNull(card().buttons[1].form)
        assertTrue(Forms.drawable(form))
    }

    @Test fun `forms는 welcome에 없으면 꺼진 것이다`() {
        val old = """{"type":"welcome","v":2,"client":"c","role":"app","capabilities":{"buttons":true,"prompts":true}}"""
        assertFalse((parseServerFrame(old) as ServerFrame.WelcomeFrame).welcome.capabilities.forms)
        val new = """{"type":"welcome","v":2,"client":"c","role":"app","capabilities":{"buttons":true,"forms":true}}"""
        assertTrue((parseServerFrame(new) as ServerFrame.WelcomeFrame).welcome.capabilities.forms)
    }

    @Test fun `입력창이 있는 버튼은 데몬이 쓰고 앱이 그릴 수 있을 때만 남긴다`() {
        val card = card()
        assertEquals(listOf("로그인 정보 입력", "취소"), Forms.buttons(card, forms = true).map { it.label })
        // 예전 데몬(forms 없음)에는 그 버튼을 그리지 않는다. 본문의 링크가 길이다.
        assertEquals(listOf("취소"), Forms.buttons(card, forms = false).map { it.label })
        // 모르는 종류의 칸이 하나라도 있으면 그리지 않는다.
        val odd = card.copy(buttons = listOf(card.buttons[0].copy(form = CardForm("x", listOf(field("text"), field("slider", "s")))), card.buttons[1]))
        assertEquals(listOf("취소"), Forms.buttons(odd, forms = true).map { it.label })
    }

    @Test fun `데몬이 받지 않을 모양의 입력창은 그리지 않는다`() {
        assertFalse(Forms.drawable(CardForm("x", emptyList())))
        assertFalse(Forms.drawable(CardForm("x", (1..9).map { field("text", "k$it") })))
        assertTrue(Forms.drawable(CardForm("x", (1..8).map { field("text", "k$it") })))
        assertFalse(Forms.drawable(CardForm("x", listOf(field("text", "")))))
        assertFalse(Forms.drawable(CardForm("x", listOf(field("text", "k".repeat(41))))))
        assertFalse(Forms.drawable(CardForm("x", listOf(field("text", "same"), field("secret", "same")))))
    }

    @Test fun `글의 길이는 다듬지 않고 문자 수로 센다`() {
        val f = field("secret", min = 2, max = 4)
        assertEquals(Forms.Problem.TooShort(2), Forms.check(f, ""))
        assertEquals(Forms.Problem.TooShort(2), Forms.check(f, "a"))
        assertNull(Forms.check(f, " a"))        // 앞의 빈칸도 값이다
        assertNull(Forms.check(f, "가나다라"))
        assertNull(Forms.check(f, "🌙🌙🌙🌙")) // 대행 쌍 넷은 네 자다
        assertEquals(Forms.Problem.TooLong(4), Forms.check(f, "가나다라마"))
        // 서버가 알린 범위가 데몬의 값 한도를 넘어도 한도까지만 받는다.
        assertEquals(1..1000, Forms.range(field("text", min = 1, max = 5000)))
        assertEquals(0..1, Forms.range(field("text", min = 0, max = 0)))
    }

    @Test fun `press는 inputs에 글은 그대로 불은 불로 싣는다`() {
        val frame = ClientFrames.press(
            "app:cl-1:5b0e", "approval:3f2a:approve", null,
            linkedMapOf("username" to FormValue.Text("me"), "password" to FormValue.Text("  p w  "), "save" to FormValue.Check(false)),
        )
        val obj = LucentJson.parseToJsonElement(frame).jsonObject
        assertEquals("press", obj["type"]!!.jsonPrimitive.content)
        assertNull(obj["input"])
        val inputs = obj["inputs"]!!.jsonObject
        assertEquals("me", inputs["username"]!!.jsonPrimitive.content)
        assertEquals("  p w  ", inputs["password"]!!.jsonPrimitive.content)
        assertTrue(inputs["password"]!!.jsonPrimitive.isString)
        assertFalse(inputs["save"]!!.jsonPrimitive.isString)
        assertFalse(inputs["save"]!!.jsonPrimitive.boolean)
        // 값이 없는 누르기에는 inputs가 없다.
        assertNull(LucentJson.parseToJsonElement(ClientFrames.press("r", "a", null)).jsonObject["inputs"])
    }
}
