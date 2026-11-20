package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.FileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class FilePreviewsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun ref(name: String, mime: String, kind: String) = FileRef(id = "f_1", name = name, size = 1, mime = mime, kind = kind)

    /** 데몬이 서명으로 정해 보내는 값들(`files.rs`의 `detect`)을 그대로 넣어 본다. */
    @Test
    fun `kinds the daemon reports`() {
        assertEquals(PreviewKind.IMAGE, PreviewKind.of(ref("노을.png", "image/png", "image")))
        assertEquals(PreviewKind.IMAGE, PreviewKind.of(ref("a.gif", "image/gif", "image")))
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of(ref("정리.md", "text/markdown", "text")))
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("표.csv", "text/csv", "text")))
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("값.json", "application/json", "text")))
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("Main.kt", "text/plain", "text")))
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("이름없음", "text/plain", "text")))
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("page.html", "text/html", "text")))
        assertEquals(PreviewKind.PDF, PreviewKind.of(ref("보고서.pdf", "application/pdf", "pdf")))
        assertNull(PreviewKind.of(ref("표.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "office")))
        assertNull(PreviewKind.of(ref("묶음.zip", "application/zip", "other")))
        assertNull(PreviewKind.of(ref("무엇", "application/octet-stream", "other")))
    }

    @Test
    fun `markdown is told by its name even when the type is plain text`() {
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of(ref("11월 지출 정리.md", "text/plain", "text")))
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of(ref("README.MARKDOWN", "text/plain", "text")))
    }

    @Test
    fun `svg is neither drawn nor shown as text`() {
        assertNull(PreviewKind.of(ref("그림.svg", "image/svg+xml", "text")))
        assertNull(PreviewKind.of(ref("그림.svg", "text/plain", "text")))
    }

    /** UTF-8이 아닌 글은 데몬이 `other`로 알린다. 이름으로 알아보고, 읽히는지는 열 때 정한다. */
    @Test
    fun `text in another charset comes as other and is told by its name`() {
        assertEquals(PreviewKind.TEXT, PreviewKind.of(ref("거래내역.csv", "application/octet-stream", "other")))
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of(ref("메모.md", "application/octet-stream", "other")))
        assertEquals(PreviewKind.IMAGE, PreviewKind.of(ref("사진.heic", "application/octet-stream", "other")))
    }

    @Test
    fun `the signature wins over the name`() {
        // 이름은 글인데 서명은 그림이다.
        assertEquals(PreviewKind.IMAGE, PreviewKind.of(ref("메모.txt", "image/png", "image")))
        // 이름은 PNG인데 서버가 그 서명을 보지 못했다.
        assertNull(PreviewKind.of(ref("가짜.png", "application/octet-stream", "other")))
        // 이름은 글인데 PDF다.
        assertEquals(PreviewKind.PDF, PreviewKind.of(ref("문서.txt", "application/pdf", "pdf")))
        // 이름은 PDF인데 서버가 그 서명을 보지 못했다.
        assertNull(PreviewKind.of(ref("가짜.pdf", "application/octet-stream", "other")))
    }

    @Test
    fun `text types are handed to other apps as plain text`() {
        assertEquals("text/plain", PreviewKind.viewType(ref("정리.md", "text/markdown", "text")))
        assertEquals("text/plain", PreviewKind.viewType(ref("값.json", "application/json", "text")))
        assertEquals("image/png", PreviewKind.viewType(ref("노을.png", "image/png", "image")))
        assertEquals("application/pdf", PreviewKind.viewType(ref("a.pdf", "application/pdf", "pdf")))
    }

    /** 서버가 종류를 알아보지 못했어도(`other`) 앱이 이름으로 알아본 파일은 그 종류로 넘긴다. */
    @Test
    fun `files told by their name are handed over with a real type`() {
        val csv = ref("거래내역.csv", "application/octet-stream", "other")
        assertEquals("text/csv", PreviewKind.shareType(csv))
        assertEquals("text/plain", PreviewKind.viewType(csv))
        val heic = ref("사진.HEIC", "application/octet-stream", "other")
        assertEquals("image/heic", PreviewKind.shareType(heic))
        assertEquals("image/heic", PreviewKind.viewType(heic))
        assertEquals("text/markdown", PreviewKind.shareType(ref("메모.md", "application/octet-stream", "other")))
        assertEquals("text/plain", PreviewKind.shareType(ref("기록.log", "", "other")))
        // 서버가 알아본 종류는 그대로 둔다.
        assertEquals("text/markdown", PreviewKind.shareType(ref("정리.md", "text/markdown", "text")))
        assertEquals("application/zip", PreviewKind.shareType(ref("묶음.csv", "application/zip", "other")))
        // 앱도 모르는 것은 모르는 대로.
        assertEquals("application/octet-stream", PreviewKind.shareType(ref("무엇.bin", "application/octet-stream", "other")))
    }

    @Test
    fun `the glyph of a file row`() {
        assertEquals(FileGlyph.PDF, FileGlyph.of(ref("보고서.pdf", "application/pdf", "pdf")))
        assertEquals(FileGlyph.TEXT, FileGlyph.of(ref("정리.md", "text/markdown", "text")))
        assertEquals(FileGlyph.TEXT, FileGlyph.of(ref("동기화.log", "text/plain", "text")))
        assertEquals(FileGlyph.TEXT, FileGlyph.of(ref("이름없음", "text/plain", "text")))
        assertEquals(FileGlyph.TEXT, FileGlyph.of(ref("기획.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "office")))
        assertEquals(FileGlyph.CODE, FileGlyph.of(ref("Expense.kt", "text/plain", "text")))
        assertEquals(FileGlyph.CODE, FileGlyph.of(ref("값.json", "application/json", "text")))
        assertEquals(FileGlyph.TABLE, FileGlyph.of(ref("거래내역.csv", "application/octet-stream", "other")))
        assertEquals(FileGlyph.TABLE, FileGlyph.of(ref("표.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "office")))
        assertEquals(FileGlyph.ARCHIVE, FileGlyph.of(ref("묶음.zip", "application/zip", "other")))
        assertEquals(FileGlyph.IMAGE, FileGlyph.of(ref("사진.heic", "application/octet-stream", "other")))
        assertEquals(FileGlyph.IMAGE, FileGlyph.of(ref("그림.svg", "image/svg+xml", "text")))
        assertEquals(FileGlyph.OTHER, FileGlyph.of(ref("무엇.bin", "application/octet-stream", "other")))
    }

    @Test
    fun `moving pictures are told by their head`() {
        fun riff(flags: Int) = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(4) + byteArrayOf(flags.toByte()) + ByteArray(8)
        assertTrue(PreviewImage.isAnimated("GIF89a".toByteArray() + ByteArray(20)))
        assertTrue(PreviewImage.isAnimated(riff(0x02)))
        assertTrue(PreviewImage.isAnimated(riff(0x12)))
        assertFalse(PreviewImage.isAnimated(riff(0x10)))
        assertFalse(PreviewImage.isAnimated("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray() + ByteArray(16)))
        assertFalse(PreviewImage.isAnimated(byteArrayOf(0x89.toByte()) + "PNG".toByteArray() + ByteArray(20)))
        assertFalse(PreviewImage.isAnimated(ByteArray(0)))
    }

    // ---------------------------------------------------------------- 줄 꺾기

    @Test
    fun `logs tables and code keep their lines while prose wraps`() {
        val lines = "2026-11-19 09:00:00 INFO 일정 동기화 0번째 묶음을 마쳤습니다\n".repeat(3)
        assertFalse(PreviewText.wraps("동기화.log", lines))
        assertFalse(PreviewText.wraps("거래내역.CSV", "날짜,내용,금액\r\n11-01,점심,8100\r\n"))
        assertFalse(PreviewText.wraps("Expense.kt", "fun main() = Unit"))
        assertTrue(PreviewText.wraps("회의록.txt", lines))
        assertTrue(PreviewText.wraps("이름없음", lines))
    }

    @Test
    fun `a very long line wraps whatever the file is`() {
        val json = "{" + "\"k\":1,".repeat(200) + "}"
        assertTrue(PreviewText.wraps("값.json", json))
        assertTrue(PreviewText.wraps("값.json", "{}\n$json"))
        assertFalse(PreviewText.wraps("값.json", "{\n" + "  \"k\": 1,\n".repeat(200) + "}"))
        assertFalse(PreviewText.wraps("값.json", ""))
    }

    // ---------------------------------------------------------------- 글 읽기

    @Test
    fun `utf8 with and without a byte order mark`() {
        assertEquals("안녕하세요", PreviewText.decode("안녕하세요".toByteArray()))
        assertEquals("안녕", PreviewText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "안녕".toByteArray()))
        assertEquals("", PreviewText.decode(ByteArray(0)))
    }

    @Test
    fun `a cut in the middle of a character drops that character`() {
        val bytes = "가나다".toByteArray() // 글자마다 3바이트
        assertEquals("가나", PreviewText.decode(bytes.copyOf(8), truncated = true))
        assertEquals("가나", PreviewText.decode(bytes.copyOf(7), truncated = true))
        assertEquals("가나", PreviewText.decode(bytes.copyOf(6), truncated = true))
        // 잘린 것이 아니라면 덜 온 글자는 잘못된 글이다. 그때는 다른 글자 집합으로도 읽히지 않아야 한다.
        assertNull(PreviewText.decode("abc".toByteArray() + byteArrayOf(0xEA.toByte()), truncated = false))
    }

    @Test
    fun `a cut inside a surrogate pair drops the pair`() {
        val bytes = "a🌙".toByteArray() // 'a' + 4바이트 글자
        assertEquals("a", PreviewText.decode(bytes.copyOf(3), truncated = true))
    }

    @Test
    fun `korean windows text is read`() {
        val cp949 = Charset.forName("x-windows-949")
        val text = "날짜,내용,금액\n11-03,점심,9000\n"
        assertEquals(text, PreviewText.decode(text.toByteArray(cp949)))
        // 2바이트 글자의 가운데에서 잘려도 읽힌다.
        val bytes = "가나다".toByteArray(cp949)
        assertEquals("가나", PreviewText.decode(bytes.copyOf(5), truncated = true))
    }

    @Test
    fun `utf16 with a byte order mark is read`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "달빛".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "달빛".toByteArray(Charsets.UTF_16BE)
        assertEquals("달빛", PreviewText.decode(le))
        assertEquals("달빛", PreviewText.decode(be))
    }

    @Test
    fun `binary is not text`() {
        assertNull(PreviewText.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08)))
        assertNull(PreviewText.decode(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0xFF.toByte(), 0xFE.toByte(), 0xC0.toByte(), 0x80.toByte())))
        // 한글이 하나도 나오지 않는 높은 바이트 열은 CP949로 읽어 주지 않는다.
        assertNull(PreviewText.decode(byteArrayOf(0x41, 0xA1.toByte(), 0xA1.toByte(), 0x42)))
    }

    @Test
    fun `only the head of a large file is read`() {
        val file = folder.newFile("큰글.log")
        file.writeText("가".repeat(100)) // 300바이트
        val head = PreviewText.read(file, limit = 100)
        assertNotNull(head)
        assertTrue(head!!.truncated)
        assertEquals("가".repeat(33), head.text)

        val whole = PreviewText.read(file, limit = 300)!!
        assertFalse(whole.truncated)
        assertEquals("가".repeat(100), whole.text)
    }
}
