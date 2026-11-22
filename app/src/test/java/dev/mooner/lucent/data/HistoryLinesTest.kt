package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryLinesTest {

    @Test fun `고른 글 표지는 마지막 줄일 때만 떼어 낸다`() {
        val split = HistoryLines.splitContext("이 부분 요약해 줘\n[고른 글 4000자 함께 보냄 · Chrome]")
        assertEquals("이 부분 요약해 줘", split.text)
        assertEquals("[고른 글 4000자 함께 보냄 · Chrome]", split.contextNote)
        val bare = HistoryLines.splitContext("[고른 글 12자 함께 보냄]")
        assertEquals("", bare.text)
        assertEquals("[고른 글 12자 함께 보냄]", bare.contextNote)
        // 글 가운데에 있거나 모양이 다르면 사용자가 쓴 글이다.
        assertNull(HistoryLines.splitContext("[고른 글 12자 함께 보냄]\n그리고").contextNote)
        assertNull(HistoryLines.splitContext("[고른 글 많이 함께 보냄]").contextNote)
    }

    @Test fun `견줄 글은 파일 설명 줄과 고른 글 표지를 뺀 것이다`() {
        assertEquals("번역해 줘", HistoryLines.comparable("번역해 줘\n[첨부: 원문.md, 표.xlsx]\n[고른 글 120자 함께 보냄 · msedge.exe]"))
        assertEquals("번역해 줘", HistoryLines.comparable("번역해 줘\n[첨부: 원문.md]"))
        assertEquals("", HistoryLines.comparable("[첨부: 사진.jpg]"))
        assertEquals("[첨부 없음]", HistoryLines.comparable("[첨부 없음]"))
    }

    @Test fun `표지를 화면의 글로`() {
        assertEquals("고른 글 120자 함께 보냄 · msedge.exe", HistoryLines.contextLabel("[고른 글 120자 함께 보냄 · msedge.exe]"))
        assertEquals("그대로", HistoryLines.contextLabel("그대로"))
    }

    @Test fun `기록 항목을 줄로 - 조각이 없으면 한 줄, 있으면 조각마다`() {
        val one = HistoryRows.of(HistoryItem(1, "2026-11-22T00:00:00Z", "lucia", "app:cl-1", "안녕하세요\n[스티커:축하 — 손뼉]"))
        assertEquals(listOf("h:1"), one.map { it.key })
        assertEquals("안녕하세요", one.single().text)
        assertEquals("축하", one.single().sticker?.name)

        val file = FileRef("f_1", "정리.md")
        val many = HistoryRows.of(HistoryItem(
            2, "2026-11-22T00:00:00Z", "lucia", "app:cl-1", "하나\n\n둘\n[스티커:축하]",
            parts = listOf("하나", "둘"), files = listOf(file),
        ))
        assertEquals(listOf("h:2:0", "h:2:1"), many.map { it.key })
        assertEquals(listOf("하나", "둘"), many.map { it.text })
        // 파일과 스티커는 마지막 조각에 붙는다.
        assertEquals(listOf(emptyList(), listOf(file)), many.map { it.files })
        assertEquals(listOf(null, "축하"), many.map { it.sticker?.name })

        // 조각이 하나뿐이면 나누지 않는다.
        assertEquals(listOf("h:3"), HistoryRows.of(HistoryItem(3, "2026-11-22T00:00:00Z", "lucia", null, "글", parts = listOf("글"))).map { it.key })
    }

    @Test fun `고른 글 표지는 사용자의 말에서만 뗀다`() {
        val user = HistoryRows.of(HistoryItem(4, "2026-11-22T00:00:00Z", "user", "app:cl-2", "요약\n[고른 글 9자 함께 보냄]")).single()
        assertEquals("요약", user.text)
        assertEquals("[고른 글 9자 함께 보냄]", user.contextNote)
        val lucia = HistoryRows.of(HistoryItem(5, "2026-11-22T00:00:00Z", "lucia", "app:cl-2", "요약\n[고른 글 9자 함께 보냄]")).single()
        assertNull(lucia.contextNote)
    }
}
