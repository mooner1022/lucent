package dev.mooner.lucent.data

import dev.mooner.lucent.data.MarkdownBlocks.Align
import dev.mooner.lucent.data.MarkdownBlocks.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `prose without a table stays one block`() {
        val text = "# 제목\n\n- 하나\n- 둘\n\n끝."
        assertEquals(listOf(Block.Text(text)), MarkdownBlocks.split(text))
        assertTrue(MarkdownBlocks.split("").isEmpty())
        assertTrue(MarkdownBlocks.split("\n\n").isEmpty())
    }

    @Test
    fun `a table is cut out between the prose around it`() {
        val blocks = MarkdownBlocks.split(
            """
            # 11월 지출

            | 날짜 | 내용 | 금액 |
            |:-----|:----:|-----:|
            | 11-01 | 점심 | 8,100 |
            | 11-02 | 책 | 15,000 |

            합계는 **23,100원**이에요.
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertEquals(Block.Text("# 11월 지출"), blocks[0])
        assertEquals(
            Block.Table(
                header = listOf("날짜", "내용", "금액"),
                aligns = listOf(Align.START, Align.CENTER, Align.END),
                rows = listOf(listOf("11-01", "점심", "8,100"), listOf("11-02", "책", "15,000")),
            ),
            blocks[1],
        )
        assertEquals(Block.Text("\n합계는 **23,100원**이에요."), blocks[2])
    }

    @Test
    fun `rows are fitted to the header`() {
        val table = MarkdownBlocks.split("a | b\n--|--\n| 1 |\n1 | 2 | 3").single() as Block.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), table.rows)
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        val table = MarkdownBlocks.split("| 식 | 뜻 |\n|---|---|\n| a \\| b | 또는 |").single() as Block.Table
        assertEquals(listOf(listOf("a | b", "또는")), table.rows)
    }

    @Test
    fun `pipes inside a code block are not a table`() {
        val text = "```\n| a | b |\n|---|---|\n| 1 | 2 |\n```"
        assertEquals(listOf(Block.Text(text)), MarkdownBlocks.split(text))
    }

    @Test
    fun `a line of dashes under plain text is not a table`() {
        // 구분 줄의 칸 수가 머리와 다르면 표가 아니다(제목 밑줄 등).
        val text = "값 | 뜻\n---"
        assertEquals(listOf(Block.Text(text)), MarkdownBlocks.split(text))
    }
}
