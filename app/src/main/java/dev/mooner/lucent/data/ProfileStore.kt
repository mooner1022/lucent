package dev.mooner.lucent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.mooner.lucent.R
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.protocol.Avatar
import dev.mooner.lucent.ui.common.Plain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 루시아가 정한 프로필 그림을 화면에 그릴 수 있게 읽어 둔 것. 그림이 없으면(null) 앱의 기본 모습을 쓴다.
 *
 * 원본은 한 변이 2048px까지라 그대로 올려 두지 않는다. 큰 초상의 크기([large])와 아바타의 크기([small]) 둘로 줄여 둔다.
 * 둘 다 정사각형이다. [file]은 받은 그대로의 원본이고, 얼굴을 눌러 크게 볼 때 쓴다.
 */
class Face(val id: String, val large: Bitmap, val small: Bitmap, val file: File)

/**
 * 루시아의 프로필 그림의 기기 쪽 사본(사양 8.7, 8.8).
 *
 * - 그림은 `id`를 이름으로 한 파일 하나로 둔다(`files/profile/<id>`). `id`가 내용 해시라 같은 `id`는 다시 받지 않는다.
 *   받을 때는 토큰이 든 머리가 필요해서 이미지 라이브러리에 주소를 넘기지 않고 직접 받는다.
 * - 화면에 쓰는 두 크기로 줄인 사본을 그 옆에 둔다(`<id>.<한 변>.png`). 앱을 켤 때는 이 작은 사본을 바로 읽어, 첫 화면부터
 *   루시아의 얼굴이 보인다(큰 원본을 읽는 동안 기본 모습이 먼저 보였다가 바뀌지 않게). 사본이 없거나 화면 크기가 달라졌으면
 *   원본에서 다시 만든다.
 * - 새 그림을 다 받을 때까지는 보이던 그림을 그대로 보인다(기본 모습이 잠깐 끼어들지 않게). 받지 못하면 보이던 것이
 *   남고, 다음 `welcome`에서 다시 받는다. 그림이 없어졌다고 들으면(`avatar: null`) 바로 기본 모습으로 돌아간다.
 * - 연결이 없어도 마지막으로 받은 그림을 보인다. 무엇이 지금 그림인지는 따로 적어 두지 않고, 남아 있는 파일이 말한다.
 * - 화면은 [face]만 본다.
 * - 보이는 이름(결정 0064)도 여기 둔다: 사용자가 관리 페이지에서 정한 이름이고, 화면은 [name]만 본다(null이면 앱의 기본
 *   이름). 연결하지 못해도 마지막으로 받은 이름을 보이도록 파일 하나에 적어 둔다.
 */
class ProfileStore(
    context: Context,
    private val scope: CoroutineScope,
    /** 그림을 받아 파일에 쓴다([GatewayApi.avatarFile]). 서버가 답한 오류는 [ApiException], 닿지 못했으면 [IOException]이다. */
    private val download: suspend (id: String, dest: File, maxBytes: Long) -> Unit,
    /** 화면에 쓰는 두 크기(px): 큰 초상, 아바타. */
    private val sides: Pair<Int, Int> = sides(context),
    /** 원본을 한 변이 주어진 크기인 정사각형으로 읽는다. 그림이 아니면 null이다. */
    private val decode: (File, Int) -> Bitmap? = ::decodeSquare,
    private val retryMs: List<Long> = RETRY_MS,
) {

    private val dir = File(context.filesDir, "profile")
    private val nameFile = File(context.filesDir, "profile-name")

    private val _name = MutableStateFlow(runCatching { cleanName(nameFile.takeIf { it.isFile }?.readText()) }.getOrNull())
    /** 지금 보일 이름. null이면 앱의 기본 이름이다. */
    val name: StateFlow<String?> = _name.asStateFlow()

    private val _face = MutableStateFlow<Face?>(null)
    /** 지금 보일 그림. null이면 앱의 기본 모습이다. */
    val face: StateFlow<Face?> = _face.asStateFlow()

    private val lock = Mutex()
    /** 받아 둔 그림을 올렸는가. 줄인 사본이 있으면 만들 때 바로 끝나고, 없으면 뒤에서 원본을 읽는다. */
    @Volatile private var restored = false
    /** 데몬이 말한 지금의 그림. 아직 받는 중이면 [face]와 다르다. */
    private var wanted: String? = null
    private var fetching: Pair<String, Job>? = null
    private val serial = AtomicInteger()

    init {
        // 줄인 사본은 작아서(수십 KB) 그 자리에서 읽는다. 화면이 처음 그려질 때 이미 얼굴이 있다.
        restored = runCatching { restoreQuickly() }.getOrDefault(false)
        if (!restored) scope.launch(Dispatchers.IO) { lock.withLock { restoreFromOriginals() } }
    }

    private fun originals(): List<File> =
        dir.listFiles { f -> f.isFile && Avatar(id = f.name).usable }.orEmpty().sortedByDescending { it.lastModified() }

    private fun copy(id: String, side: Int) = File(dir, "$id.$side.png")

    /** 가장 새 원본의 줄인 사본 둘을 읽는다. 원본이 없으면 그림이 없는 것이고(끝), 사본이 없으면 false다. */
    private fun restoreQuickly(): Boolean {
        val id = originals().firstOrNull()?.name ?: return true
        val large = BitmapFactory.decodeFile(copy(id, sides.first).path) ?: return false
        val small = BitmapFactory.decodeFile(copy(id, sides.second).path) ?: return false
        _face.value = Face(id, large, small, File(dir, id))
        return true
    }

    /** 줄인 사본이 없을 때: 원본에서 다시 만든다. 원본이 둘 이상이면(바꾸던 중에 꺼졌다) 가장 새것만 남긴다. */
    private fun restoreFromOriginals() {
        if (restored) return
        restored = true
        for (file in originals()) {
            val made = read(file)
            if (made == null) {
                file.delete()
                continue
            }
            show(file.name, made)
            return
        }
    }

    private fun read(file: File): Pair<Bitmap, Bitmap>? {
        val large = decode(file, sides.first) ?: return null
        val small = decode(file, sides.second) ?: return null
        return large to small
    }

    /** [id]의 그림을 보이고 줄인 사본을 적어 둔다. 다른 그림의 파일은 지운다. */
    private fun show(id: String, bitmaps: Pair<Bitmap, Bitmap>) {
        dir.listFiles { f -> f.name != id }?.forEach { it.delete() }
        for ((bitmap, side) in listOf(bitmaps.first to sides.first, bitmaps.second to sides.second)) {
            runCatching { copy(id, side).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        // 파일을 다 적은 뒤에 알린다(얼굴이 보이는 때에는 원본과 사본이 제자리에 있다).
        _face.value = Face(id, bitmaps.first, bitmaps.second, File(dir, id))
    }

    /**
     * 데몬이 알려 준 지금의 그림(`welcome.profile.avatar`, `profile` 프레임의 `avatar`). 정한 그림이 없거나, 프로필 기능이
     * 없는 예전 데몬이면 null을 넘긴다: 기본 모습으로 돌아가고 서버에 묻지 않는다.
     */
    suspend fun apply(avatar: Avatar?) = withContext(Dispatchers.IO) {
        lock.withLock {
            restoreFromOriginals()
            val id = avatar?.takeIf { it.usable }?.id
            wanted = id
            if (id == null) {
                stopFetching()
                _face.value = null
                dir.deleteRecursively()
                return@withLock
            }
            if (_face.value?.id == id) {
                stopFetching()
                return@withLock
            }
            fetching?.let { (fetchingId, job) -> if (fetchingId == id && job.isActive) return@withLock }
            stopFetching()
            val job = scope.launch(Dispatchers.IO) { fetch(id) }
            fetching = id to job
        }
    }

    /**
     * 데몬이 알려 준 이름(`welcome.profile.name`, `profile` 프레임의 `name`). 이름이 붙기 전의 데몬이거나 프로필 기능이 없는
     * 데몬이면 null을 넘긴다: 앱의 기본 이름으로 돌아간다.
     */
    fun applyName(raw: String?) {
        val name = cleanName(raw)
        synchronized(nameFile) {
            if (name == _name.value) return
            _name.value = name
            runCatching { if (name == null) nameFile.delete() else nameFile.writeText(name) }
        }
    }

    private fun stopFetching() {
        fetching?.second?.cancel()
        fetching = null
    }

    /** 서버에 닿지 못하면 몇 번 다시 해 본다. 서버가 없다고 답했거나 그릴 수 없는 그림이면 그만둔다. */
    private suspend fun fetch(id: String) {
        var attempt = 0
        while (true) {
            // 같은 그림을 받던 앞선 시도가 아직 정리 중일 수 있어, 받는 파일의 이름은 시도마다 다르게 한다.
            val part = File(dir, "$id.${serial.incrementAndGet()}.part")
            try {
                dir.mkdirs()
                download(id, part, Avatar.MAX_BYTES)
                // 그릴 수 없는 파일을 내용 해시 이름으로 두면 다시 받을 길이 없다. 두기 전에 읽어 본다.
                val bitmaps = read(part)
                if (bitmaps == null) {
                    Log.w(TAG, "프로필 그림을 읽을 수 없음")
                    return
                }
                lock.withLock {
                    if (wanted != id) return
                    if (!part.renameTo(File(dir, id))) return
                    show(id, bitmaps)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 그사이 지워진 id(404)이거나 기능이 없는 데몬(501)이다. 보이던 것을 그대로 둔다.
                Log.w(TAG, "프로필 그림을 받지 못함: ${e.code}")
                return
            } catch (e: IOException) {
                val wait = retryMs.getOrNull(attempt++) ?: return
                delay(wait)
            } catch (e: Exception) {
                Log.w(TAG, "프로필 그림을 받지 못함", e)
                return
            } finally {
                part.delete()
            }
        }
    }

    /** 연결 해제, 다른 클라이언트로 바꿈: 그림을 지우고 기본 모습으로 돌아간다. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            restored = true
            wanted = null
            stopFetching()
            _face.value = null
            dir.deleteRecursively()
        }
        applyName(null)
    }

    companion object {
        private const val TAG = "ProfileStore"
        private val RETRY_MS = listOf(3_000L, 15_000L, 60_000L)

        /** 이름의 상한(14장): 한 줄 24자. */
        const val NAME_MAX = 24

        /**
         * 화면에 보일 이름으로 다듬는다. 앱이 그리는 글이라 이모지를 지우고(`Plain`), 줄바꿈과 이어진 공백은 하나로 줄이고,
         * 24자를 넘으면 자른다. 남는 것이 없으면 null(기본 이름)이다.
         */
        fun cleanName(raw: String?): String? {
            if (raw == null) return null
            val line = Plain.line(raw).replace(WHITESPACE, " ").trim()
            if (line.isEmpty()) return null
            val end = if (line.codePointCount(0, line.length) > NAME_MAX) line.offsetByCodePoints(0, NAME_MAX) else line.length
            return line.substring(0, end).trim().ifEmpty { null }
        }

        private val WHITESPACE = Regex("""\s+""")

        /** 그림의 한 변이 이보다 크면 읽지 않는다(사양의 상한은 2048px). */
        private const val MAX_SIDE = 8_192

        /** 화면에 쓰는 두 크기: 큰 초상(빈 화면), 머리말의 아바타(대화 줄과 알림은 이것을 조금 줄여 쓴다). */
        fun sides(context: Context): Pair<Int, Int> {
            val res = context.resources
            return res.getDimensionPixelSize(R.dimen.avatar_hero) to res.getDimensionPixelSize(R.dimen.presence_size)
        }

        /**
         * 그림을 한 변이 [side]인 정사각형으로 읽는다. 읽으면서 반씩 줄이고(픽셀을 고르게 섞어 줄인다) 나머지를 맞춘다.
         * 정사각형이 아니면 가운데를 쓰고, 사진에 적힌 방향(EXIF)대로 돌린다. 작은 그림은 늘리지 않는다.
         * 그림이 아니거나 너무 크면 null이다.
         */
        fun decodeSquare(file: File, side: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return@runCatching null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(minOf(w, h), side) }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@runCatching null
            val edge = minOf(decoded.width, decoded.height)
            val turn = orientation(file)
            val scale = if (edge > side) side.toFloat() / edge else 1f
            if (decoded.width == decoded.height && turn == null && scale == 1f) return@runCatching decoded
            val matrix = (turn ?: Matrix()).apply { postScale(scale, scale) }
            Bitmap.createBitmap(decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge, matrix, true)
        }.getOrNull()

        /** 사진에 적힌 방향(EXIF)을 바로 세우는 변환. 바로 서 있거나 적힌 것이 없으면 null이다. */
        private fun orientation(file: File): Matrix? {
            val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return null
            val degrees = exif.rotationDegrees
            val flipped = exif.isFlipped
            if (degrees == 0 && !flipped) return null
            return Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                if (degrees != 0) postRotate(degrees.toFloat())
            }
        }

        /** [from]을 반씩 줄여도 [to]보다 작아지지 않는 가장 큰 배율(1, 2, 4, …). */
        fun sampleSize(from: Int, to: Int): Int {
            var sample = 1
            while (to > 0 && from / (sample * 2) >= to) sample *= 2
            return sample
        }
    }
}
