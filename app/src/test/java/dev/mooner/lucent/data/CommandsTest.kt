package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.CommandInfo
import dev.mooner.lucent.protocol.CommandList
import dev.mooner.lucent.protocol.CompactResult
import dev.mooner.lucent.protocol.LucentJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명령(권한 모드, 주 모델)과 대화 정리의 규칙(docs/app-api-changes.md 2026-11-20). */
class CommandsTest {

    private val models = listOf("fable", "opus", "sonnet", "haiku", "default")

    /** 변경사항 문서의 `commands` 답 그대로. */
    @Test
    fun `commands with current values`() {
        val json = """{"commands": [
          {"name": "권한", "about": "권한 모드 보기·바꾸기", "options": ["엄격", "기본", "해제"], "current": "기본", "can_change": false},
          {"name": "모델", "about": "주 모델 보기·바꾸기", "options": ["fable", "opus", "sonnet", "haiku", "default"], "current": "default", "can_change": true}
        ]}"""
        val commands = LucentJson.decodeFromString(CommandList.serializer(), json).commands
        assertEquals(listOf("권한", "모델"), commands.map { it.name })
        assertEquals("기본", commands[0].current)
        assertFalse(commands[0].canChange)
        assertEquals("default", commands[1].current)
        assertTrue(commands[1].canChange)
        assertEquals(models, commands[1].options)
    }

    /** 예전 데몬: `모델`도 `current`도 없다. */
    @Test
    fun `commands from an older daemon`() {
        val json = """{"commands": [{"name": "권한", "about": "권한 모드 보기·바꾸기", "options": ["엄격", "기본", "해제"], "can_change": false}]}"""
        val command = LucentJson.decodeFromString(CommandList.serializer(), json).commands.single()
        assertNull(command.current)
        // 권한에 한해 답의 글에서 읽어 본다.
        assertEquals("기본", Commands.current(command, "🛡️ 권한 모드: **기본** — 위험한 도구는 승인 카드를 거쳐요."))
        assertNull(Commands.current(command, null))
    }

    @Test
    fun `the current value comes from the list and never from the text of the model command`() {
        val model = CommandInfo("모델", options = models, current = "Opus", canChange = true)
        // 대소문자는 가리지 않고, 선택지에 있는 모양으로 돌려준다.
        assertEquals("opus", Commands.current(model, "주 모델: 기본(sonnet)\n고를 수 있는 모델:\n- Fable: …"))
        // `current`가 없으면 모델의 글은 풀어 읽지 않는다(글에는 모든 모델의 이름이 나온다).
        assertNull(Commands.current(model.copy(current = null), "주 모델: Sonnet"))
        // 데몬이 선택지에 없는 값을 알려 주면 값 그대로.
        assertEquals("mythos", Commands.current(model.copy(current = "mythos"), null))
    }

    @Test
    fun `whether the change was applied is told by the list read again`() {
        val model = CommandInfo("모델", options = models, current = "opus", canChange = true)
        assertEquals(true, Commands.applied(model, "opus"))
        assertEquals(true, Commands.applied(model, "OPUS"))
        // 모르는 값을 보내면 `ok`로 안내 글이 오고 바뀌지 않는다.
        assertEquals(false, Commands.applied(model, "gpt"))
        // 예전 데몬이면 알 수 없다.
        assertNull(Commands.applied(model.copy(current = null), "opus"))
        assertNull(Commands.applied(null, "opus"))
    }

    @Test
    fun `model aliases get friendly names and unknown values stay as they are`() {
        val model = CommandInfo("모델", options = models)
        assertEquals(listOf("Fable", "Opus", "Sonnet", "Haiku", "기본"), models.map { Commands.optionLabel(model, it) })
        assertEquals("mythos", Commands.optionLabel(model, "mythos"))
        // 다른 명령의 선택지는 건드리지 않는다.
        assertEquals("default", Commands.optionLabel(CommandInfo("권한", options = listOf("default")), "default"))
    }

    @Test
    fun `compacting first is suggested only when moving to a bigger model`() {
        assertTrue(Commands.compactFirstByDefault("sonnet", "opus"))
        assertTrue(Commands.compactFirstByDefault("haiku", "fable"))
        assertFalse(Commands.compactFirstByDefault("fable", "haiku"))
        assertFalse(Commands.compactFirstByDefault("opus", "sonnet"))
        assertFalse(Commands.compactFirstByDefault("opus", "opus"))
        // 지금 값이 기본이면 Sonnet쯤으로 본다.
        assertTrue(Commands.compactFirstByDefault("default", "opus"))
        assertFalse(Commands.compactFirstByDefault("default", "sonnet"))
        assertFalse(Commands.compactFirstByDefault(null, "haiku"))
        // 크기를 모르는 값으로 갈 때는 권하지 않는다.
        assertFalse(Commands.compactFirstByDefault("sonnet", "default"))
        assertFalse(Commands.compactFirstByDefault("sonnet", "mythos"))
    }

    @Test
    fun `what to do after one compact call`() {
        assertEquals(CompactStep.PROCEED, CompactStep.of(null))
        assertEquals(CompactStep.RETRY_LATER, CompactStep.of("busy"))
        assertEquals(CompactStep.WAIT_ONLINE, CompactStep.of("closed"))
        for (code in listOf("timeout", "failed", "bad_params", "unsupported", "unknown_method", "something_new"))
            assertEquals(code, CompactStep.ASK, CompactStep.of(code))
    }

    @Test
    fun `the compact result is read whatever the outcome`() {
        val done = LucentJson.decodeFromString(
            CompactResult.serializer(),
            """{"outcome": "compacted", "pre_tokens": 128662, "context_tokens": 2778, "text": "대화를 정리했습니다(128,662→2,778 토큰)."}""",
        )
        assertEquals("compacted", done.outcome)
        assertEquals(128662L, done.preTokens)
        assertEquals(2778L, done.contextTokens)
        // 값이 늘 수 있다. 모르는 값과 모르는 필드가 와도 읽힌다.
        val later = LucentJson.decodeFromString(CompactResult.serializer(), """{"outcome": "deferred", "text": "나중에 합니다.", "eta": 30}""")
        assertEquals("deferred", later.outcome)
        assertEquals("나중에 합니다.", later.text)
        assertEquals(0L, later.preTokens)
    }
}
