package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.ExpressionCatalog
import dev.mooner.lucent.protocol.ExpressionCategory
import dev.mooner.lucent.protocol.ExpressionFile
import dev.mooner.lucent.protocol.LucentJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사양서(docs/app-expressions.md)의 규칙과 데몬의 시험(`expressions.rs`)에 쓰인 글을 그대로 넣어 본다. */
class ExpressionsTest {

    private fun names(text: String) = ExpressionText.tokens(text).map { it.name }

    // ------------------------------------------------------------------ 글 속 이모지 표기(4.1)

    @Test fun `토큰을 찾는다 - 데몬의 시험과 같은 글`() {
        assertEquals(listOf("lucia_smile"), names("좋아요 :lucia_smile:!"))
        // 오른쪽 콜론을 다음 토큰과 나눠 쓰지 않는다.
        assertEquals(listOf("a_b", "c_d"), names(":a_b::c_d:"))
        assertTrue(names("10:30:00 그리고 \\:lucia_smile: 과 `:lucia_smile:`").isEmpty())
        assertTrue(names("```\n:lucia_smile:\n```").isEmpty())
        assertEquals(listOf("fmt"), names("std::fmt::Display"))
        assertTrue(names("<:lucia_smile:123> <a:lucia_smile:456>").isEmpty())
    }

    @Test fun `이름 규칙 - 소문자와 숫자와 밑줄, 2~32자, 글자 하나 이상`() {
        assertTrue(ExpressionText.isEmojiName("ok"))
        assertTrue(ExpressionText.isEmojiName("a".repeat(32)))
        assertFalse(ExpressionText.isEmojiName("a"))
        assertFalse(ExpressionText.isEmojiName("a".repeat(33)))
        assertFalse(ExpressionText.isEmojiName("30"))
        assertFalse(ExpressionText.isEmojiName("__"))
        assertFalse(ExpressionText.isEmojiName("Smile"))
        assertFalse(ExpressionText.isEmojiName("반짝"))
        assertTrue(names(":Smile: :반짝: :a: :12:").isEmpty())
    }

    @Test fun `꼴이 맞으면 목록에 없어도 통째로 지나간다`() {
        // `:not_ours:`가 닫는 콜론을 가져가므로 뒤의 `lucia_smile:`은 토큰이 아니다.
        assertEquals(listOf("not_ours"), names(":not_ours:lucia_smile:"))
        val plain = ExpressionText.plain(":not_ours:lucia_smile:") { it == "lucia_smile" }
        assertTrue(plain.emoji.isEmpty())
    }

    @Test fun `풀지 않는 글 - 아는 이름만 이모지 자리가 되고 그 앞의 역슬래시는 지워진다`() {
        val known = setOf("lucia_smile", "spark")
        val p = ExpressionText.plain("좋아요 :lucia_smile: 그리고 \\:spark: 와 :not_ours:") { it in known }
        assertEquals("좋아요 :lucia_smile: 그리고 :spark: 와 :not_ours:", p.text)
        assertEquals(listOf("lucia_smile"), p.emoji.map { it.name })
        assertEquals(":lucia_smile:", p.text.substring(p.emoji[0].start, p.emoji[0].end))

        // 역슬래시가 앞에 있으면 뒤의 토큰 자리도 한 칸씩 당겨진다.
        val q = ExpressionText.plain("\\:spark: :spark:") { it in known }
        assertEquals(":spark: :spark:", q.text)
        assertEquals(listOf(8 to 15), q.emoji.map { it.start to it.end })

        // 모르는 이름 앞의 역슬래시와 코드 안의 글은 그대로다.
        assertEquals("C:\\:temp: `:spark:`", ExpressionText.plain("C:\\:temp: `:spark:`") { it in known }.text)
        assertTrue(ExpressionText.plain("`:spark:`") { it in known }.emoji.isEmpty())
        assertTrue(ExpressionText.plain("콜론 없는 글") { true }.emoji.isEmpty())
    }

    @Test fun `그림을 그릴 수 없는 자리에서는 아는 이모지 표기를 뺀다`() {
        val known = setOf("spark")
        assertEquals("찾는 중", ExpressionText.without("찾는 중 :spark:") { it in known })
        assertEquals("메일을 확인하고 있어요", ExpressionText.without("메일을 :spark: 확인하고 있어요") { it in known })
        assertEquals("10:30 :not_ours: 그대로", ExpressionText.without("10:30 :not_ours: 그대로") { it in known })
    }

    @Test fun `자동 완성 - 콜론 다음 두 글자부터`() {
        fun completing(text: String) = ExpressionText.completing(text, text.length)
        assertNull(completing("안녕 :l"))
        assertEquals(3 to "lu", completing("안녕 :lu"))
        assertEquals(0 to "웃음", completing(":웃음"))
        // 이미 닫힌 토큰의 닫는 콜론은 새 이모지의 시작이 아니다.
        assertNull(completing(":lucia_smile:lu"))
        assertNull(completing("\\:lu"))
        assertNull(completing("시간은 :lu 이후"))
        assertNull(completing("콜론 없음"))
        // 커서가 글 가운데 있어도 그 앞만 본다.
        assertEquals(0 to "sp", ExpressionText.completing(":sp 뒤의 글", 3))
    }

    // ------------------------------------------------------------------ 기록의 스티커 줄(6장)

    @Test fun `기록의 스티커 줄을 글과 스티커로 나눈다`() {
        StickerLine.split("정말 축하해요! :lucia_clap:\n[스티커:축하]").let {
            assertEquals("정말 축하해요! :lucia_clap:", it.text)
            assertEquals(StickerRef(name = "축하"), it.sticker)
        }
        // 사용자가 보낸 것은 설명이 붙는다. 이름만 읽는다.
        StickerLine.split("나 합격했어!\n[스티커:축하 — 꽃가루가 날리는 축하]").let {
            assertEquals("나 합격했어!", it.text)
            assertEquals("축하", it.sticker?.name)
        }
        StickerLine.split("[스티커: 꾸벅 ]").let {
            assertEquals("", it.text)
            assertEquals("꾸벅", it.sticker?.name)
        }
        // 줄 전체가 그 모양일 때만이다.
        assertNull(StickerLine.split("그건 [스티커:축하] 같은 거예요").sticker)
        assertNull(StickerLine.split("[스티커:]").sticker)
        assertNull(StickerLine.split("평범한 글").sticker)
        assertEquals("[스티커: 축하]", StickerLine.fallback("축하"))
    }

    // ------------------------------------------------------------------ 목록(2장, 3.1)

    private fun file(n: Int) = ExpressionFile("x_%024x".format(n), "image/webp", 128, 128, 100)
    private fun emoji(n: Int, name: String, label: String = "", tags: List<String> = emptyList(), category: String = "") =
        Expression("e_%012x".format(n), name, label, "", tags, category, false, file(n))
    private fun sticker(n: Int, name: String, tags: List<String> = emptyList(), category: String = "") =
        Expression("s_%012x".format(n), name, "", "설명 $name", tags, category, false, file(100 + n))

    private val catalog = Expressions(
        ExpressionCatalog(
            version = "c41d9e07a2b8",
            categories = listOf(
                ExpressionCategory("mark", "표시", order = 1),
                ExpressionCategory("lucia", "루시아", order = 0, icon = "e_000000000001"),
                ExpressionCategory("empty", "빈 분류", order = 2),
            ),
            emoji = listOf(
                emoji(1, "lucia_smile", "루시아 웃음", listOf("웃음", "좋아"), "lucia"),
                emoji(2, "spark", "반짝", listOf("별"), "mark"),
                emoji(3, "smile_small", "", listOf("웃음"), "gone"),
                emoji(4, "moon", "달", listOf("밤", "미소")),
            ),
            stickers = listOf(sticker(1, "축하", listOf("합격"), "lucia"), sticker(2, "Bow", category = "")),
        ),
    )

    @Test fun `사양서의 목록 예시를 읽는다`() {
        val parsed = LucentJson.decodeFromString(
            ExpressionCatalog.serializer(),
            """{"version":"c41d9e07a2b8",
               "categories":[{"id":"lucia","label":"루시아","order":0,"icon":"e_3f9a0c12de4b"},{"id":"etc","label":"기타","order":1,"icon":null}],
               "emoji":[{"id":"e_3f9a0c12de4b","name":"lucia_smile","label":"루시아 웃음","description":"기분 좋게 웃는 얼굴",
                 "tags":["웃음","좋아","기쁨"],"category":"lucia","animated":false,
                 "file":{"id":"x_9b2e00000000000000000000","mime":"image/webp","width":128,"height":128,"size":18342}}],
               "stickers":[{"id":"s_7a1c0e9b3f52","name":"축하","description":"꽃가루가 날리는 축하","tags":["축하","합격"],
                 "category":"lucia","animated":true,
                 "file":{"id":"x_51c000000000000000000000","mime":"image/webp","width":320,"height":320,"size":201733}}],
               "나중에_생길_필드":1}""",
        )
        val x = Expressions(parsed)
        assertEquals("c41d9e07a2b8", x.version)
        assertEquals("루시아 웃음", x.emoji("lucia_smile")?.label)
        assertTrue(x.stickerNamed("축하")!!.animated)
        assertNull(parsed.categories[1].icon)
        assertEquals("", x.stickers.single().label)
    }

    @Test fun `쓸 수 없는 항목은 버린다`() {
        val x = Expressions(
            ExpressionCatalog(
                emoji = listOf(
                    emoji(1, "ok_fine"),
                    emoji(2, "Bad Name"),
                    emoji(3, "no_file").copy(file = ExpressionFile("../../etc/passwd")),
                    emoji(4, "no_id").copy(id = ""),
                ),
            ),
        )
        assertEquals(listOf("ok_fine"), x.emoji.map { it.name })
        assertFalse(Expressions.isFileId("x_"))
        assertFalse(Expressions.isFileId("x_9b2e/../a"))
        assertTrue(Expressions.isFileId("x_9b2e00000000000000000000"))
    }

    @Test fun `이모지는 이름 그대로, 스티커는 대소문자와 앞뒤 공백을 가리지 않고 찾는다`() {
        assertEquals("spark", catalog.emoji("spark")?.name)
        assertNull(catalog.emoji("Spark"))
        assertNull(catalog.emoji("축하"))
        assertEquals("축하", catalog.stickerNamed(" 축하 ")?.name)
        assertEquals("축하", catalog.stickerNamed("축하 — 꽃가루")?.name)
        assertEquals("Bow", catalog.stickerNamed("bow")?.name)
        assertNull(catalog.stickerNamed("없음"))
    }

    @Test fun `저장된 스티커는 ID로 먼저, 없으면 이름으로 찾는다`() {
        assertEquals("축하", catalog.sticker(StickerRef("s_000000000001", "예전 이름"))?.name)
        assertEquals("Bow", catalog.sticker(StickerRef("s_ffffffffffff", "bow"))?.name)
        assertEquals("축하", catalog.sticker(StickerRef(null, "축하"))?.name)
        assertNull(catalog.sticker(StickerRef("s_ffffffffffff", "사라진 스티커")))
        // 이모지의 ID로는 스티커를 찾지 못한다.
        assertNull(catalog.sticker(StickerRef("e_000000000001", "")))
    }

    @Test fun `묶음은 order 순이고 분류 없는 것은 맨 뒤 한 묶음이다`() {
        val sections = catalog.sections(ExpressionKind.EMOJI)
        assertEquals(listOf("lucia", "mark", ""), sections.map { it.id })
        assertEquals("lucia_smile", sections[0].icon?.name)
        // 목록에 없는 분류를 가리키는 항목도 분류 없는 묶음에 든다.
        assertEquals(listOf("smile_small", "moon"), sections[2].items.map { it.name })
        // 비어 있는 분류는 보이지 않는다.
        assertEquals(listOf("lucia", ""), catalog.sections(ExpressionKind.STICKER).map { it.id })
        assertTrue(Expressions.EMPTY.sections(ExpressionKind.EMOJI).isEmpty())
    }

    @Test fun `찾기 - 이름, 보이는 이름, 태그에서, 앞에서 맞는 것이 먼저`() {
        assertEquals(listOf("smile_small", "lucia_smile"), catalog.search(ExpressionKind.EMOJI, "smile").map { it.name })
        assertEquals(listOf("lucia_smile"), catalog.search(ExpressionKind.EMOJI, "루시").map { it.name })
        // 태그로 맞은 것: 앞에서 맞는 것이 가운데서 맞는 것보다 먼저.
        assertEquals(listOf("lucia_smile", "smile_small"), catalog.search(ExpressionKind.EMOJI, "웃").map { it.name })
        assertEquals(listOf("moon"), catalog.search(ExpressionKind.EMOJI, "MOON").map { it.name })
        assertEquals(listOf("축하"), catalog.search(ExpressionKind.STICKER, "합격").map { it.name })
        assertEquals(1, catalog.search(ExpressionKind.EMOJI, "smile", limit = 1).size)
        assertTrue(catalog.search(ExpressionKind.EMOJI, "  ").isEmpty())
        assertTrue(catalog.search(ExpressionKind.EMOJI, "없는말").isEmpty())
    }
}
