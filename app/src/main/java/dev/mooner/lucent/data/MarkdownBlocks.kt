package dev.mooner.lucent.data

/**
 * 마크다운을 글과 표로 가른다(StarLight의 `MarkdownBlocks`를 옮겼다).
 *
 * 마크다운을 그리는 라이브러리의 기본 구성에는 표가 없고, 휴대폰 폭에 눌러 담은 표는 어차피 읽기 어렵다. 그래서 표는 따로
 * 떼어 가로로 구르는 뷰로 그린다. 쓰는 곳은 파일 미리보기다. 대화의 글은 사양(4장)의 서식에 표가 없어 가르지 않는다.
 */
object MarkdownBlocks {

    enum class Align { START, CENTER, END }

    sealed interface Block {
        data class Text(val markdown: String) : Block

        /** [rows]의 줄마다 칸 수는 [header]와 같다(모자라면 빈 칸, 넘치면 버린다). */
        data class Table(val header: List<String>, val aligns: List<Align>, val rows: List<List<String>>) : Block
    }

    /** 머리 줄 바로 아래의 구분 줄: `|---|:--:|--:|`. */
    private val DELIMITER = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")

    fun split(markdown: String): List<Block> {
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        val text = StringBuilder()
        var inFence = false
        var i = 0

        fun flushText() {
            if (text.isNotBlank()) blocks += Block.Text(text.toString().trimEnd('\n'))
            text.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) inFence = !inFence
            val next = lines.getOrNull(i + 1)
            // 코드 블록 안의 `|`는 표가 아니다.
            if (!inFence && '|' in line && next != null && DELIMITER.matches(next)) {
                val header = cells(line)
                val delimiter = cells(next)
                if (header.isNotEmpty() && header.size == delimiter.size) {
                    flushText()
                    val rows = mutableListOf<List<String>>()
                    var j = i + 2
                    while (j < lines.size && '|' in lines[j] && lines[j].isNotBlank()) {
                        val row = cells(lines[j])
                        rows += List(header.size) { row.getOrElse(it) { "" } }
                        j++
                    }
                    blocks += Block.Table(header, delimiter.map(::alignOf), rows)
                    i = j
                    continue
                }
            }
            text.append(line).append('\n')
            i++
        }
        flushText()
        return blocks
    }

    /** 표 한 줄의 칸들. `\|`는 칸 안의 세로줄이다. */
    private fun cells(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < row.length) {
            val c = row[index]
            when {
                c == '\\' && row.getOrNull(index + 1) == '|' -> {
                    cell.append('|')
                    index++
                }
                c == '|' -> {
                    cells += cell.toString().trim()
                    cell.setLength(0)
                }
                else -> cell.append(c)
            }
            index++
        }
        cells += cell.toString().trim()
        return cells
    }

    private fun alignOf(delimiter: String): Align {
        val left = delimiter.startsWith(":")
        val right = delimiter.endsWith(":")
        return when {
            left && right -> Align.CENTER
            right -> Align.END
            else -> Align.START
        }
    }
}
