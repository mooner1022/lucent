package dev.mooner.lucent.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.mooner.lucent.R
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.data.ExpressionStore
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.ExpressionCatalog
import dev.mooner.lucent.protocol.ExpressionFile
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.ui.common.EmojiSpans
import dev.mooner.lucent.ui.common.Markdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 글 속 이모지 표기를 그리는 자리(docs/app-expressions.md 4.1). 기기에 받아 둔 목록과 그림으로 시험한다
 * (서버에는 닿지 않는다: 주소가 없어 받기는 조용히 실패한다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownEmojiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var spans: EmojiSpans
    private lateinit var markdown: Markdown
    private val view by lazy { TextView(context) }

    private fun emoji(n: Int, name: String) =
        Expression("e_%012x".format(n), name, "보이는 $name", "", emptyList(), "", false, ExpressionFile("x_%024x".format(n), "image/png", 32, 32, 10))

    @Before fun setUp() {
        // 목록에는 셋이 있지만 그림은 둘만 받아 두었다.
        val catalog = ExpressionCatalog("v1", emptyList(), listOf(emoji(1, "lucia_smile"), emoji(2, "spark"), emoji(3, "not_yet")), emptyList())
        val dir = File(context.filesDir, "expressions")
        File(dir, "files").mkdirs()
        File(dir, "catalog.json").writeText(LucentJson.encodeToString(ExpressionCatalog.serializer(), catalog))
        for (n in 1..2) {
            File(dir, "files/" + "x_%024x".format(n)).outputStream().use {
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val store = ExpressionStore(context, GatewayApi(AppSettings(context), OkHttpClient()), scope)
        runBlocking { withTimeout(5_000) { store.current.first { it.version == "v1" } } }
        spans = EmojiSpans(context, store)
        markdown = Markdown(context, spans)
    }

    @After fun tearDown() {
        scope.cancel()
        File(context.filesDir, "expressions").deleteRecursively()
    }

    /** 그려진 글과, 그림으로 바뀐 자리의 글. */
    private fun render(body: String, jumbo: Boolean = false): Pair<String, List<String>> {
        markdown.render(view, body, jumbo)
        val text = view.text as Spanned
        val drawn = text.getSpans(0, text.length, ReplacementSpan::class.java)
            .sortedBy { text.getSpanStart(it) }
            .map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }
        return text.toString() to drawn
    }

    @Test fun `목록에 있고 그림이 있는 이름만 그림이 된다 - 글은 그대로 남는다`() {
        val (text, drawn) = render("좋아요 :lucia_smile:! :not_ours: 10:30:00")
        assertEquals("좋아요 :lucia_smile:! :not_ours: 10:30:00", text)
        assertEquals(listOf(":lucia_smile:"), drawn)
        // 그림을 아직 받지 못한 이모지는 글 그대로다(대체 표시).
        assertTrue(render("아직 :not_yet:").second.isEmpty())
    }

    @Test fun `역슬래시 뒤, 코드 안, 링크 주소는 바꾸지 않는다`() {
        render("막은 것 \\:lucia_smile: 끝").let { (text, drawn) ->
            assertEquals("막은 것 :lucia_smile: 끝", text)
            assertTrue(drawn.isEmpty())
        }
        assertTrue(render("코드 `:lucia_smile:` 안").second.isEmpty())
        assertTrue(render("```\n:lucia_smile:\n```").second.isEmpty())
        assertTrue(render("[여기](https://example.com/:lucia_smile:)").second.isEmpty())
        assertTrue(render("<:lucia_smile:123> <a:lucia_smile:456>").second.isEmpty())
        // 링크의 글 안에서는 그린다.
        assertEquals(listOf(":spark:"), render("[별 :spark:](https://example.com)").second)
    }

    @Test fun `닫는 콜론을 다음 토큰과 나눠 쓰지 않는다`() {
        assertEquals(listOf(":spark:", ":lucia_smile:"), render(":spark::lucia_smile:").second)
        // 꼴이 맞는 `:not_ours:`가 닫는 콜론을 가져간다.
        assertTrue(render(":not_ours:lucia_smile:").second.isEmpty())
        assertEquals(":not_ours:lucia_smile:", render(":not_ours:lucia_smile:").first)
    }

    @Test fun `밑줄이 든 이름이 기울임으로 풀리지 않는다`() {
        val (text, drawn) = render("_강조_ :lucia_smile: 그리고 :lucia_smile:")
        assertEquals("강조 :lucia_smile: 그리고 :lucia_smile:", text)
        assertEquals(2, drawn.size)
    }

    @Test fun `이모지만 있는 말은 크게 그린다 - 세 개까지`() {
        val jumbo = context.resources.getDimensionPixelSize(R.dimen.emoji_jumbo)
        fun sizes(body: String, enlarge: Boolean = true): List<Int> {
            markdown.render(view, body, enlarge)
            val text = view.text as Spanned
            return text.getSpans(0, text.length, ReplacementSpan::class.java).map { it.getSize(view.paint, text, 0, 1, null) }
        }
        assertEquals(listOf(jumbo), sizes(":spark:"))
        assertEquals(listOf(jumbo, jumbo, jumbo), sizes(":spark: :lucia_smile:\n:spark:"))
        assertTrue(sizes(":spark: 좋아요").all { it < jumbo })
        assertTrue(sizes(":spark: :spark: :spark: :spark:").all { it < jumbo })
        // 카드의 글처럼 크게 그리지 않는 자리.
        assertTrue(sizes(":spark:", enlarge = false).all { it < jumbo })
    }

    @Test fun `풀지 않는 글에도 같은 규칙으로 붙는다`() {
        val out = spans.plain("고마워 :lucia_smile: \\:spark: `:spark:` :not_ours:") as Spanned
        assertEquals("고마워 :lucia_smile: :spark: `:spark:` :not_ours:", out.toString())
        val drawn = out.getSpans(0, out.length, ReplacementSpan::class.java).map { out.substring(out.getSpanStart(it), out.getSpanEnd(it)) }
        assertEquals(listOf(":lucia_smile:"), drawn)
        // 이모지가 없는 글은 그대로 돌려준다.
        assertEquals("평범한 글", spans.plain("평범한 글"))
    }
}
