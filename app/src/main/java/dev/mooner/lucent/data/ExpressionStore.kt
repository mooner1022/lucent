package dev.mooner.lucent.data

import android.content.Context
import android.util.Log
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.net.GatewayApi
import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.ExpressionCatalog
import dev.mooner.lucent.protocol.LucentJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 이모지·스티커 목록과 그림의 기기 쪽 사본(app-expressions.md 3장).
 *
 * - 목록은 `GET /v1/expressions`로 받아 파일 하나에 둔다. 앱을 다시 켜면 그 사본부터 보이고, 서버의 판(`version`)이 다르면
 *   새로 받는다(`If-None-Match`로 물어 그대로면 본문 없이 끝난다).
 * - 그림은 `file.id`를 이름으로 한 파일에 둔다. ID가 내용 해시라 바뀌지 않으므로 한 번 받으면 다시 받지 않는다.
 *   받을 때는 토큰이 든 머리가 필요해서 이미지 라이브러리에 주소를 넘기지 않고 직접 받는다.
 * - 화면은 [current]와 [revision]을 본다. 목록이 바뀌거나 그림이 새로 도착하면 [revision]이 오른다.
 */
class ExpressionStore(context: Context, private val api: GatewayApi, private val scope: CoroutineScope) {

    private val dir = File(context.filesDir, "expressions")
    private val images = File(dir, "files")
    private val catalogFile = File(dir, "catalog.json")
    private val prefs = context.getSharedPreferences("expressions", Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(Expressions.EMPTY)
    /** 지금의 목록. 받은 적이 없으면 비어 있다. */
    val current: StateFlow<Expressions> = _current.asStateFlow()

    private val _revision = MutableStateFlow(0)
    /** 목록이나 그림이 바뀔 때마다 오른다. 화면이 이걸 보고 다시 그린다. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val syncLock = Mutex()
    private val fetching = ConcurrentHashMap<String, Job>()
    /** 서버가 없다고 답한 그림(404). 목록을 새로 받기 전에는 다시 묻지 않는다. */
    private val missing = ConcurrentHashMap.newKeySet<String>()
    /** 디스크에 있는 것을 확인한 그림. 그릴 때마다 파일을 다시 살피지 않는다. */
    private val present = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch(Dispatchers.IO) { loadCached() }
    }

    private fun loadCached() {
        val catalog = runCatching { LucentJson.decodeFromString(ExpressionCatalog.serializer(), catalogFile.readText()) }.getOrNull() ?: return
        if (_current.value.version.isEmpty()) publish(Expressions(catalog))
    }

    private fun publish(expressions: Expressions) {
        _current.value = expressions
        _revision.update { it + 1 }
        prefetch(expressions)
    }

    /**
     * 서버의 판이 [remoteVersion]이라고 들었을 때 부른다. 가진 것과 같으면 아무것도 하지 않고, 다르면(또는 [force]면) 새로 받는다.
     * 이 기능이 없는 데몬(501)이면 가진 사본을 그대로 둔다.
     */
    suspend fun sync(remoteVersion: String?, force: Boolean = false) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val have = _current.value.version.takeIf { it.isNotEmpty() && catalogFile.isFile }
            if (!force && have != null && have == remoteVersion) {
                prefetch(_current.value)
                return@withLock
            }
            try {
                val catalog = api.expressions(known = have)
                if (catalog == null) {
                    prefetch(_current.value)
                    return@withLock
                }
                dir.mkdirs()
                val tmp = File(dir, "catalog.json.tmp")
                tmp.writeText(LucentJson.encodeToString(ExpressionCatalog.serializer(), catalog))
                if (!tmp.renameTo(catalogFile)) tmp.copyTo(catalogFile, overwrite = true)
                missing.clear()
                publish(Expressions(catalog))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code != "unsupported") Log.w(TAG, "이모지·스티커 목록을 받지 못함: ${e.code}")
            } catch (e: Exception) {
                Log.w(TAG, "이모지·스티커 목록을 받지 못함", e)
            }
        }
    }

    // ------------------------------------------------------------------ 그림

    /** 이미 받아 둔 그림 파일. 아직 없으면 null이고, 받기를 시작해 둔다(도착하면 [revision]이 오른다). */
    fun file(expression: Expression): File? {
        val id = expression.file.id
        if (!Expressions.isFileId(id)) return null
        val file = File(images, id)
        if (id in present) return file
        if (file.isFile && file.length() > 0) {
            present += id
            return file
        }
        fetch(id)
        return null
    }

    private fun prefetch(expressions: Expressions) {
        // 글 속에 바로 쓰이는 이모지부터 받는다.
        for (e in expressions.emoji + expressions.stickers) {
            val id = e.file.id
            if (Expressions.isFileId(id) && !File(images, id).isFile) fetch(id)
        }
    }

    private fun fetch(id: String) {
        if (id in missing || fetching.containsKey(id)) return
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                images.mkdirs()
                val tmp = File(images, "$id.part")
                api.expressionFile(id, tmp, MAX_IMAGE_BYTES)
                if (tmp.renameTo(File(images, id))) _revision.update { it + 1 } else tmp.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 없는 파일이거나 목록에서 빠진 지 오래된 파일이다. 대체 표시로 그린다(5.3).
                if (e.status == 404) missing += id
            } catch (e: Exception) {
                Log.w(TAG, "그림을 받지 못함", e)
            } finally {
                File(images, "$id.part").delete()
                fetching.remove(id)
            }
        }
        if (fetching.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }

    // ------------------------------------------------------------------ 최근 쓴 것

    /** 최근 쓴 것(7장). 기기에만 둔다. 목록에서 빠진 것은 걸러서 돌려준다. */
    fun recent(kind: ExpressionKind): List<Expression> {
        val now = _current.value
        val pool = now.of(kind).toHashSet()
        return recentIds(kind).mapNotNull { now.byId(it) }.filter { it in pool }
    }

    fun markUsed(kind: ExpressionKind, expression: Expression) {
        val ids = (listOf(expression.id) + recentIds(kind).filter { it != expression.id }).take(RECENT_MAX)
        prefs.edit().putString(recentKey(kind), ids.joinToString(",")).apply()
    }

    private fun recentIds(kind: ExpressionKind): List<String> =
        prefs.getString(recentKey(kind), "").orEmpty().split(',').filter { it.isNotEmpty() }

    private fun recentKey(kind: ExpressionKind) = if (kind == ExpressionKind.EMOJI) "recent_emoji" else "recent_stickers"

    // ------------------------------------------------------------------ 지우기

    /** 연결 해제: 목록, 그림, 최근 쓴 것을 모두 지운다. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        syncLock.withLock {
            fetching.values.forEach { it.cancel() }
            dir.deleteRecursively()
            prefs.edit().clear().apply()
            missing.clear()
            present.clear()
            _current.value = Expressions.EMPTY
            _revision.update { it + 1 }
        }
    }

    private companion object {
        const val TAG = "ExpressionStore"
        const val RECENT_MAX = 20
        /** 스티커 파일 한도(512 KiB)보다 넉넉하게. 이보다 큰 것은 받지 않는다. */
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
    }
}
