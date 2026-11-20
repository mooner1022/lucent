package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.FileRef
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 앱이 스스로 보일 수 있는 파일의 갈래. */
enum class PreviewKind {
    /** 그림 그대로. */
    IMAGE,

    /** 마크다운으로 풀어서. */
    MARKDOWN,

    /** 글자 그대로(고정폭). */
    TEXT,

    /** 쪽마다 그림으로 그려서. */
    PDF;

    companion object {
        /**
         * [file]을 앱 안에서 어떻게 보일지. 보일 수 없으면 null이고, 그런 파일은 받아서 다른 앱에 넘긴다.
         *
         * 서버의 `kind`·`mime`은 파일 서명으로 정한 값이다(사양 9.2). 데몬은 PNG·JPEG·GIF·WebP만 `image`로, UTF-8로 읽히는
         * 것만 `text`로 알린다. 그래서 다른 글자 집합으로 쓴 글(엑셀이 낸 CSV 등)은 `other`로 오는데, 그런 것은 이름의
         * 확장자로 알아본다. 실제로 읽히는지는 열 때 정한다([PreviewText.decode]).
         */
        fun of(file: FileRef): PreviewKind? {
            val extension = extensionOf(file)
            val mime = file.mime.lowercase()
            return when {
                // SVG는 글로 된 그림이다. 앱이 그리지 못하고, 원문을 보이는 것도 바라는 바가 아니다.
                mime == "image/svg+xml" || extension == "svg" -> null
                file.kind == "image" || mime.startsWith("image/") -> IMAGE
                // PDF·Office는 이름이 무엇이든 서버가 서명으로 알아본 것이다.
                file.kind == "pdf" || mime == "application/pdf" -> PDF
                file.kind == "office" -> null
                extension in MARKDOWN_EXTENSIONS || mime == "text/markdown" -> MARKDOWN
                file.kind == "text" || mime.startsWith("text/") || mime in TEXT_TYPES || extension in TEXT_EXTENSIONS -> TEXT
                extension in IMAGE_TYPES -> IMAGE
                else -> null
            }
        }

        /**
         * 다른 앱에 보낼 때(공유) 알리는 종류. 서버가 알아보지 못한 파일(`application/octet-stream`)이라도 앱이 이름으로
         * 알아본 것이면 그 종류를 알린다. 그러지 않으면 받을 앱이 거의 잡히지 않는다.
         */
        fun shareType(file: FileRef): String {
            val mime = file.mime.lowercase()
            if (mime.isNotBlank() && mime != UNKNOWN_TYPE) return file.mime
            val extension = extensionOf(file)
            return when {
                extension in MARKDOWN_EXTENSIONS -> "text/markdown"
                extension == "csv" -> "text/csv"
                extension in TEXT_EXTENSIONS -> "text/plain"
                else -> IMAGE_TYPES[extension] ?: UNKNOWN_TYPE
            }
        }

        /** 다른 앱으로 열 때 알리는 종류. 스크립트 종류를 여는 앱은 드물어도 글을 여는 앱은 대개 있다. */
        fun viewType(file: FileRef): String {
            val type = shareType(file)
            val lower = type.lowercase()
            return if (lower.startsWith("text/") || lower in TEXT_TYPES) "text/plain" else type
        }
    }
}

private const val UNKNOWN_TYPE = "application/octet-stream"

private fun extensionOf(file: FileRef) = extensionOf(file.name)

private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
private val TEXT_TYPES = setOf("application/json", "application/javascript", "application/xml", "application/x-javascript")

/** 사람이 줄글로 쓰는 글. 화면 폭에 맞춰 줄을 꺾어 보인다. */
private val PROSE_EXTENSIONS = setOf("txt")

/** 줄이 곧 뜻인 글(기록, 표, 코드). 줄을 꺾지 않고 가로로 굴려 본다. */
private val LINE_EXTENSIONS = setOf(
    "log", "csv", "tsv", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "ics",
    "html", "htm", "css", "js", "mjs", "ts", "kt", "kts", "java", "py", "rs", "sh", "sql",
)
private val TEXT_EXTENSIONS = PROSE_EXTENSIONS + LINE_EXTENSIONS

/** 서버가 서명을 알아보지 못해 `other`로 오지만 기기가 풀 수 있는 그림. 못 풀면 열 때 안내로 바뀐다. */
private val IMAGE_TYPES = mapOf("bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif")

/** 파일 줄 앞에 놓는 아이콘의 갈래. 누르기 전에 무엇인지 알아보게 한다. */
enum class FileGlyph {
    IMAGE, PDF, TEXT, CODE, TABLE, ARCHIVE, OTHER;

    companion object {
        fun of(file: FileRef): FileGlyph {
            val extension = extensionOf(file)
            val mime = file.mime.lowercase()
            return when {
                file.kind == "image" || mime.startsWith("image/") || extension in IMAGE_TYPES || extension == "svg" -> IMAGE
                file.kind == "pdf" || mime == "application/pdf" -> PDF
                extension in TABLE_EXTENSIONS -> TABLE
                file.kind == "office" -> TEXT
                mime in ARCHIVE_TYPES || extension in ARCHIVE_EXTENSIONS -> ARCHIVE
                extension in MARKDOWN_EXTENSIONS || extension in PROSE_EXTENSIONS || extension == "log" -> TEXT
                extension in LINE_EXTENSIONS || mime in TEXT_TYPES -> CODE
                file.kind == "text" || mime.startsWith("text/") -> TEXT
                else -> OTHER
            }
        }

        private val TABLE_EXTENSIONS = setOf("csv", "tsv", "xlsx", "xls")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "tar", "gz", "tgz", "rar")
        private val ARCHIVE_TYPES = setOf("application/zip", "application/gzip", "application/x-tar", "application/x-7z-compressed")
    }
}

/** 글 파일의 앞부분을 읽는다. */
object PreviewText {

    /** 한 번에 보이는 양. 이보다 큰 파일은 앞부분만 보인다. */
    const val MAX_BYTES = 256 * 1024

    /** 줄을 꺾지 않고 보이는 글에서 한 줄이 이보다 길면(줄바꿈 없는 JSON 등) 꺾어 보인다. 끝없이 옆으로 굴릴 수는 없다. */
    const val MAX_UNWRAPPED_LINE = 400

    /**
     * 글 파일을 화면 폭에 맞춰 줄을 꺾어 보일지. 기록·표·코드는 줄이 곧 뜻이라 꺾지 않고 가로로 굴려 보고, 줄글(`.txt`,
     * 확장자로 알 수 없는 글)은 꺾는다. 꺾지 않을 글이라도 아주 긴 줄이 있으면 꺾는다.
     */
    fun wraps(name: String, text: String): Boolean {
        if (extensionOf(name) !in LINE_EXTENSIONS) return true
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (end - start > MAX_UNWRAPPED_LINE) return true
            start = end + 1
        }
        return false
    }

    /** 읽은 글과, 파일에 더 남았는지. */
    class Head(val text: String, val truncated: Boolean)

    /** [file]의 앞 [limit]바이트를 글로 읽는다. 글이 아니면 null이다. */
    fun read(file: File, limit: Int = MAX_BYTES): Head? {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(limit + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        }
        val truncated = bytes.size > limit
        val text = decode(if (truncated) bytes.copyOf(limit) else bytes, truncated) ?: return null
        return Head(text, truncated)
    }

    /**
     * [bytes]를 글로 푼다. UTF-8을 먼저 보고, 아니면 한국어 윈도의 글자 집합(CP949)으로 본다. 어느 쪽으로도 깨끗이
     * 읽히지 않거나 NUL이 섞여 있으면 글이 아니라고 보고 null을 준다(깨진 글자를 보이지 않는다).
     *
     * [truncated]면 뒤가 잘린 것이므로 끝에 걸친 글자 하나는 버린다.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): String? {
        if (bytes.isEmpty()) return ""
        utf16(bytes)?.let { charset ->
            val even = bytes.size - 2 - (bytes.size - 2) % 2
            return strict(bytes, 2, even, charset, truncated)?.takeUnless { '\u0000' in it }
        }
        if (bytes.any { it == 0.toByte() }) return null
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        strict(bytes, start, bytes.size - start, Charsets.UTF_8, truncated)?.let { return it }
        if (start > 0) return null
        val korean = KOREAN ?: return null
        // CP949는 거의 어떤 바이트열이든 받아 주므로, 한글이 하나도 나오지 않으면 그 글자 집합이 아니라고 본다.
        return strict(bytes, 0, bytes.size, korean, truncated)?.takeIf { text -> text.any { it in '가'..'힣' } }
    }

    private fun utf16(bytes: ByteArray): Charset? = when {
        bytes.size < 2 -> null
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }

    /** 잘못된 바이트가 하나라도 있으면 null. [truncated]면 끝에 덜 온 글자는 잘못으로 치지 않고 버린다. */
    private fun strict(bytes: ByteArray, offset: Int, length: Int, charset: Charset, truncated: Boolean): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, length)
        val output = CharBuffer.allocate(length + 1)
        val result = decoder.decode(input, output, !truncated)
        if (result.isError) return null
        if (!truncated && decoder.flush(output).isError) return null
        output.flip()
        // 잘린 끝이 대리 쌍의 앞쪽이면 그것도 버린다.
        if (truncated && output.hasRemaining() && Character.isHighSurrogate(output.get(output.limit() - 1))) output.limit(output.limit() - 1)
        return output.toString()
    }

    private val KOREAN: Charset? =
        listOf("x-windows-949", "MS949", "EUC-KR").firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
}

/** 그림 파일의 앞머리로 알아보는 것들. */
object PreviewImage {

    /** 앞머리를 이만큼 읽으면 [isAnimated]가 판단할 수 있다. */
    const val HEAD_BYTES = 32

    /**
     * 움직이는 그림인가(GIF, 움직이는 WebP). 움직이는 그림은 조각내어 크게 보는 화면에 올릴 수 없으므로 통째로 그린다.
     * 움직이는 PNG 등 그 밖은 멈춘 그림으로 다룬다.
     */
    fun isAnimated(head: ByteArray): Boolean {
        fun at(offset: Int, text: String) = head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
        if (at(0, "GIF8")) return true
        // RIFF....WEBPVP8X 다음 바이트의 0x02가 "움직임"이다.
        return at(0, "RIFF") && at(8, "WEBPVP8X") && head.size > 20 && head[20].toInt() and 0x02 != 0
    }
}
