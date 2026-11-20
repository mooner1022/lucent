package dev.mooner.lucent.data

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.protocol.Avatar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dir = File(app.filesDir, "profile")
    private lateinit var scope: CoroutineScope

    /** 서버에 있는 그림(id → 내용). 없는 id는 404다. */
    private val served = HashMap<String, ByteArray>()
    private val asked = CopyOnWriteArrayList<String>()
    /** 다음 몇 번은 서버에 닿지 못한다. */
    private var unreachable = 0
    /** 켜면 받기가 이 신호를 기다린다(받는 중을 만든다). */
    private var gate: CompletableDeferred<Unit>? = null

    private val a = "a_" + "a".repeat(24)
    private val b = "a_" + "b".repeat(24)

    @Before fun setUp() {
        dir.deleteRecursively()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        served[a] = "IMG-a".toByteArray()
        served[b] = "IMG-b".toByteArray()
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun download(id: String, dest: File, maxBytes: Long) {
        asked += id
        gate?.await()
        if (unreachable > 0) {
            unreachable--
            throw IOException("닿지 못함")
        }
        val data = served[id] ?: throw ApiException(404, "not_found", "없는 그림")
        if (data.size > maxBytes) throw IOException("그림이 너무 커요")
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)
    }

    /** 원본을 읽은 횟수(줄인 사본으로 켰는지 본다). */
    private val decoded = CopyOnWriteArrayList<String>()

    /** 시험용 읽개: "IMG"로 시작하는 파일만 그림이다. */
    private fun decode(file: File, side: Int): Bitmap? {
        decoded += file.name
        return if (file.readBytes().decodeToString().startsWith("IMG")) Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888) else null
    }

    private fun store(sides: Pair<Int, Int> = 8 to 4) = ProfileStore(app, scope, ::download, sides, ::decode, retryMs = listOf(1L, 1L))

    private fun avatar(id: String) = Avatar(id = id, mime = "image/png", width = 512, height = 512, size = 5)

    private fun ProfileStore.awaitFace(id: String?) {
        runBlocking { withTimeout(5_000) { face.first { it?.id == id } } }
    }

    /** 뒤에서 도는 받기가 끝날 때까지 잠깐 기다린다(끝났는지 알려 주는 신호가 없는 경우). */
    private fun settle() = Thread.sleep(200)

    /** 받아 둔 원본과 받다 만 조각(줄인 사본은 뺀다). */
    private fun files() = dir.list().orEmpty().filterNot { it.endsWith(".png") }.sorted()

    private fun copies() = dir.list().orEmpty().filter { it.endsWith(".png") }.sorted()

    @Test fun `그림을 받아 id를 이름으로 두고 보인다 - 같은 id는 다시 받지 않는다`() = runBlocking<Unit> {
        val store = store()
        assertNull(store.face.value)
        store.apply(avatar(a))
        store.awaitFace(a)
        assertEquals(listOf(a), files())
        assertEquals("IMG-a", File(dir, a).readText())
        // 크게 볼 때 쓰는 원본을 가리킨다.
        assertEquals(File(dir, a), store.face.value?.file)

        store.apply(avatar(a))
        store.apply(avatar(a))
        settle()
        assertEquals(listOf(a), asked.toList())
    }

    @Test fun `앱을 다시 켜면 서버에 묻지 않고 받아 둔 그림을 보인다`() = runBlocking<Unit> {
        store().apply { apply(avatar(a)); awaitFace(a) }
        asked.clear()

        // 연결하지 못한 채 다시 켰다.
        val offline = store()
        offline.awaitFace(a)
        assertTrue(asked.isEmpty())

        // 연결되어 같은 id를 들으면 다시 받지 않는다.
        offline.apply(avatar(a))
        settle()
        assertTrue(asked.isEmpty())
    }

    @Test fun `다시 켤 때는 줄인 사본을 바로 읽는다 - 원본을 읽는 동안 기본 모습이 먼저 보이지 않게`() = runBlocking<Unit> {
        store().apply { apply(avatar(a)); awaitFace(a) }
        assertEquals(listOf("$a.4.png", "$a.8.png"), copies())
        decoded.clear()

        // 만들자마자(기다리지 않고) 얼굴이 있다. 원본은 읽지 않았다.
        val again = store()
        assertEquals(a, again.face.value?.id)
        assertEquals(8, again.face.value?.large?.width)
        assertEquals(4, again.face.value?.small?.width)
        assertTrue(decoded.isEmpty())
    }

    @Test fun `줄인 사본이 없거나 화면 크기가 달라졌으면 원본에서 다시 만든다`() = runBlocking<Unit> {
        store().apply { apply(avatar(a)); awaitFace(a) }
        asked.clear()

        // 화면 크기가 달라졌다(다른 크기의 사본을 찾는다).
        val resized = store(sides = 12 to 6)
        resized.awaitFace(a)
        assertEquals(12, resized.face.value?.large?.width)
        assertEquals(listOf("$a.12.png", "$a.6.png"), copies())

        // 사본이 깨졌다.
        File(dir, "$a.12.png").writeText("broken")
        val repaired = store(sides = 12 to 6)
        repaired.awaitFace(a)
        assertEquals(12, repaired.face.value?.large?.width)
        assertTrue(asked.isEmpty()) // 서버에는 다시 묻지 않는다
    }

    @Test fun `그림이 바뀌면 새것을 다 받을 때까지 보이던 것을 보이고, 받은 뒤 예전 것을 지운다`() = runBlocking<Unit> {
        val store = store()
        store.apply(avatar(a))
        store.awaitFace(a)

        gate = CompletableDeferred()
        store.apply(avatar(b))
        settle()
        assertEquals(a, store.face.value?.id) // 받는 중: 기본 모습이 끼어들지 않는다
        gate!!.complete(Unit)
        store.awaitFace(b)
        assertEquals(listOf(b), files())
        assertEquals(listOf("$b.4.png", "$b.8.png"), copies())
    }

    @Test fun `그림이 없어지면 바로 기본 모습으로 돌아가고 파일을 지운다`() = runBlocking<Unit> {
        val store = store()
        store.apply(avatar(a))
        store.awaitFace(a)
        store.apply(null)
        assertNull(store.face.value)
        assertTrue(files().isEmpty() && copies().isEmpty())
        // 다시 켜도 기본 모습이다.
        val again = store()
        settle()
        assertNull(again.face.value)
    }

    @Test fun `프로필 기능이 없는 데몬이면 서버에 묻지 않는다`() = runBlocking<Unit> {
        val store = store()
        store.apply(null)
        settle()
        assertTrue(asked.isEmpty())
        assertNull(store.face.value)
    }

    @Test fun `받는 중에 없어졌다고 들으면 늦게 도착한 그림을 쓰지 않는다`() = runBlocking<Unit> {
        val store = store()
        gate = CompletableDeferred()
        store.apply(avatar(a))
        settle()
        store.apply(null)
        gate!!.complete(Unit)
        settle()
        assertNull(store.face.value)
        assertTrue(files().isEmpty())
    }

    @Test fun `서버에 없는 그림이면(404) 보이던 것을 그대로 두고, 다음 welcome에서 다시 받는다`() = runBlocking<Unit> {
        val store = store()
        store.apply(avatar(a))
        store.awaitFace(a)

        val gone = "a_" + "c".repeat(24)
        store.apply(avatar(gone))
        settle()
        assertEquals(a, store.face.value?.id)
        assertEquals(listOf(a, gone), asked.toList()) // 404는 다시 해 보지 않는다
        assertEquals(listOf(a), files())

        // 다음 연결에서 그 그림이 생겨 있으면 받는다.
        served[gone] = "IMG-c".toByteArray()
        store.apply(avatar(gone))
        store.awaitFace(gone)
    }

    @Test fun `서버에 닿지 못하면 몇 번 다시 해 보고, 그래도 안 되면 그만둔다`() = runBlocking<Unit> {
        val store = store()
        unreachable = 2
        store.apply(avatar(a))
        store.awaitFace(a)
        assertEquals(3, asked.size)

        asked.clear()
        unreachable = 10
        store.apply(avatar(b))
        settle()
        assertEquals(3, asked.size) // 처음 한 번과 다시 두 번
        assertEquals(a, store.face.value?.id)
        // 다음 welcome에서 다시 받는다.
        unreachable = 0
        store.apply(avatar(b))
        store.awaitFace(b)
    }

    @Test fun `그릴 수 없는 파일과 너무 큰 파일은 두지 않는다`() = runBlocking<Unit> {
        val store = store()
        served[a] = "html".toByteArray()
        store.apply(avatar(a))
        settle()
        assertNull(store.face.value)
        assertTrue(files().isEmpty())

        served[b] = ByteArray(Avatar.MAX_BYTES.toInt() + 1)
        store.apply(avatar(b))
        settle()
        assertNull(store.face.value)
        assertTrue(files().isEmpty())
    }

    @Test fun `쓸 수 없는 id는 그림이 없는 것으로 다룬다 - 파일 이름과 주소에 넣지 않는다`() = runBlocking<Unit> {
        val store = store()
        store.apply(avatar(a))
        store.awaitFace(a)
        store.apply(avatar("../../shared_prefs/lucent"))
        assertNull(store.face.value)
        assertEquals(listOf(a), asked.toList())
    }

    @Test fun `연결을 해제하면 그림을 지운다`() = runBlocking<Unit> {
        val store = store()
        store.apply(avatar(a))
        store.awaitFace(a)
        store.clear()
        assertNull(store.face.value)
        assertFalse(dir.exists())
    }

    @Test fun `바꾸던 중에 꺼져 파일이 둘이면 새것만 남기고, 받다 만 조각도 치운다`() = runBlocking<Unit> {
        dir.mkdirs()
        File(dir, a).apply { writeText("IMG-a"); setLastModified(1_000_000) }
        File(dir, b).apply { writeText("IMG-b"); setLastModified(2_000_000) }
        File(dir, "$a.7.part").writeText("IMG")
        val store = store()
        store.awaitFace(b)
        settle()
        assertEquals(listOf(b), files())
        assertEquals(listOf("$b.4.png", "$b.8.png"), copies())
    }

    @Test fun `반씩 줄이는 배율은 원하는 크기보다 작아지지 않는 가장 큰 값이다`() {
        assertEquals(1, ProfileStore.sampleSize(128, 264))
        assertEquals(1, ProfileStore.sampleSize(500, 264))
        assertEquals(2, ProfileStore.sampleSize(800, 264))
        assertEquals(4, ProfileStore.sampleSize(2048, 264))
        assertEquals(16, ProfileStore.sampleSize(2048, 120))
        assertEquals(1, ProfileStore.sampleSize(2048, 0))
    }
}
