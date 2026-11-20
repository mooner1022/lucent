package dev.mooner.lucent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSpecsTest {

    private val all = ToolSwitch.entries.toSet()

    /** 데몬의 `clean_tools`(gateway.rs)가 받아들이는 모양: 이름은 마디 둘이나 셋, 설명은 한 줄 200자, 인자는 2000바이트. */
    @Test fun `모든 선언이 데몬의 정리 규칙을 지난다`() {
        val declared = ToolSpecs.declarations(all)
        assertEquals(PhoneTool.entries.size, declared.size)
        assertTrue(declared.size <= 32)
        val names = mutableSetOf<String>()
        for (d in declared) {
            val name = d["name"]!!.jsonPrimitive.content
            val parts = name.split('.')
            assertTrue(name, parts.size in 2..3)
            assertTrue(name, parts.all { p -> p.isNotEmpty() && p.length <= 40 && p.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' } })
            assertTrue("겹친 이름: $name", names.add(name))
            val description = d["description"]!!.jsonPrimitive.content
            assertFalse(name, '\n' in description)
            assertTrue("$name 설명 ${description.codePointCount(0, description.length)}자", description.codePointCount(0, description.length) <= 200)
            assertTrue(name, d["params"].toString().toByteArray().size <= 2000)
        }
    }

    @Test fun `답장만 쓰기 기능이다`() {
        val writes = ToolSpecs.declarations(all).filter { it["effect"]?.jsonPrimitive?.content == "write" }.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("notifications.reply"), writes)
    }

    @Test fun `켠 스위치의 기능만 내주고 답장은 알림 기록이 있어야 한다`() {
        assertEquals(emptyList<PhoneTool>(), ToolSpecs.offered(emptySet()))
        assertEquals(listOf(PhoneTool.LOCATION), ToolSpecs.offered(setOf(ToolSwitch.LOCATION)))
        assertEquals(emptyList<PhoneTool>(), ToolSpecs.offered(setOf(ToolSwitch.REPLY)))
        assertEquals(
            listOf(PhoneTool.NOTIFICATIONS_RECENT, PhoneTool.NOTIFICATIONS_GET, PhoneTool.NOTIFICATIONS_REPLY),
            ToolSpecs.offered(setOf(ToolSwitch.NOTIFICATIONS, ToolSwitch.REPLY)),
        )
    }

    @Test fun `이름으로 기능을 찾는다`() {
        assertEquals(PhoneTool.NOTIFICATIONS_GET, PhoneTool.of("notifications.get"))
        assertNull(PhoneTool.of("alarm.set"))
    }

    @Test fun `인자는 너그럽게 읽고 범위로 맞춘다`() {
        val p = ToolParams(buildJsonObject {
            put("precise", "true")
            put("limit", 500)
            put("hours", "3")
            put("app", "  카카오  ")
            put("query", "   ")
            put("active", 1)
        })
        assertTrue(p.bool("precise", false))
        assertEquals(50, p.int("limit", 20, 1..50))
        assertEquals(3, p.int("hours", 24, 1..48))
        assertEquals("카카오", p.string("app"))
        assertNull(p.string("query"))
        assertTrue(p.bool("active", false))
        assertEquals(20, p.int("missing", 20, 1..50))
        // 인자가 객체가 아니면 모두 기본값.
        val none = ToolParams(JsonPrimitive("x"))
        assertFalse(none.bool("precise", false))
        assertEquals(7, ToolParams(null).int("limit", 7, 1..50))
        assertEquals(1, ToolParams(buildJsonObject { put("limit", -3) }).int("limit", 20, 1..50))
        assertEquals(20, ToolParams(buildJsonObject { put("limit", "많이") }).int("limit", 20, 1..50))
    }

    @Test fun `결과는 들어가는 만큼만 담는다`() {
        val rows = List(40) { i -> buildJsonObject { put("text", "가".repeat(100) + i) } }
        val (shown, count) = ToolBudget.fit(rows) { items, cut ->
            buildJsonObject {
                putJsonArray("items") { items.forEach { add(it) } }
                put("more", cut)
            }
        }
        assertTrue(count in 1 until 40)
        assertEquals(count, shown["items"]!!.jsonArray.size)
        assertTrue(ToolBudget.size(shown) <= ToolBudget.RESULT_BYTES)
        assertEquals("true", shown["more"]!!.jsonPrimitive.content)
        // 넉넉하면 다 담는다.
        val small = ToolBudget.fit(rows.take(3)) { items, _ -> buildJsonObject { putJsonArray("items") { items.forEach { add(it) } } } }
        assertEquals(3, small.second)
    }

    @Test fun `들여쓴 JSON의 바이트를 센다`() {
        // {"a": "가"} 를 두 칸 들여쓰면 {\n  "a": "가"\n} 이다(한글은 3바이트).
        assertEquals("{\n  \"a\": \"가\"\n}".toByteArray().size, ToolBudget.size(buildJsonObject { put("a", "가") }))
        assertEquals(0, ToolBudget.fit(emptyList<JsonObject>()) { _, _ -> JsonObject(emptyMap()) }.second)
    }

    @Test fun `글은 코드 포인트로 자르고 줄임표를 붙인다`() {
        assertEquals("가나다", "가나다".clip(3))
        assertEquals("가나…", "가나다라".clip(2))
        assertEquals("😀…", "😀😀".clip(1))
    }
}
