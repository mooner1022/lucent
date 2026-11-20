package dev.mooner.lucent.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.data.PreviewKind
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.protocol.FileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 루시아가 보낸 파일을 받아 여는 일(사양 8.5).
 *
 * 파일은 `GET /v1/files/{id}`로 받아 앱의 캐시 폴더에 둔다. 서버가 `Cache-Control: private, no-store`로 알리는 파일이므로
 * 오래 두지 않고(하루) 연결을 해제하면 모두 지운다. 서버가 30일 뒤 지운 파일은 `410`이다.
 *
 * 앱이 스스로 보일 수 있는 파일은 [FilePreview]가 시트에 보인다. 여기는 받기와, 다른 앱에 넘기는 일(열기·공유·저장)이다.
 */
object FileActions {

    private const val KEEP_MS = 24L * 60 * 60 * 1000

    /** 같은 파일을 두 곳(말풍선의 그림, 미리보기)에서 한꺼번에 받지 않게 파일마다 하나씩 둔다. */
    private val fetching = ConcurrentHashMap<String, Mutex>()

    private fun root(context: Context) = File(context.cacheDir, "files")

    /** 이미 받아 둔 파일. 없으면 null. */
    fun cached(context: Context, ref: FileRef): File? = cachedFile(context, ref).takeIf { it.isFile && it.length() > 0 }

    /** 내려받은 파일의 자리. 이름은 경로로 해석되지 않게 마지막 마디만 쓴다. */
    fun cachedFile(context: Context, ref: FileRef): File {
        val name = File(ref.name).name.replace('/', '_').ifBlank { "file" }
        return File(File(root(context), ref.id.filter { it.isLetterOrDigit() || it == '_' }), name)
    }

    /** 캐시에 없으면 내려받는다. 같은 파일을 이미 받는 중이면 그것이 끝나기를 기다린다. */
    suspend fun fetch(
        context: Context,
        ref: FileRef,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        cached(context, ref)?.let { return it }
        return fetching.getOrPut(ref.id) { Mutex() }.withLock {
            cached(context, ref)?.let { return@withLock it }
            val target = cachedFile(context, ref)
            val part = File(target.parentFile, target.name + ".part")
            try {
                LucentHub.get(context).api.download(ref.id, part, onProgress)
                withContext(Dispatchers.IO) {
                    if (!part.renameTo(target)) throw IOException("파일을 저장하지 못했어요")
                }
            } catch (e: Exception) {
                // 받다가 그만둔 것(시트를 닫음)도 여기로 온다. 그때도 반쪽 파일은 지운다.
                withContext(NonCancellable + Dispatchers.IO) { part.delete() }
                throw e
            }
            target
        }
    }

    fun describeFailure(context: Context, e: Exception): String = when {
        e is ApiException && e.code == "gone" -> context.getString(R.string.file_gone)
        e is ApiException && e.code == "not_found" -> context.getString(R.string.file_not_found)
        e is ApiException -> e.message ?: context.getString(R.string.file_failed)
        else -> context.getString(R.string.file_failed_network)
    }

    /** 다시 해 봐도 받을 수 없는 실패인가(서버가 지웠거나 없는 파일). 아니면 잠깐의 일일 수 있다(연결 끊김 등). */
    fun isPermanent(e: Exception): Boolean = e is ApiException && (e.code == "gone" || e.code == "not_found")

    /** 받은 파일을 다른 앱으로 연다. */
    fun open(context: Context, ref: FileRef, file: File) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(context, file), PreviewKind.viewType(ref))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(view, ref.name))
    }

    /** 받은 파일을 다른 앱에 보낸다(공유). */
    fun share(context: Context, ref: FileRef, file: File) {
        val uri = uriOf(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(PreviewKind.shareType(ref))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, ref.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 고르는 화면이 파일을 미리 보이려면 주소가 여기에도 있어야 한다.
        send.clipData = ClipData.newRawUri(ref.name, uri)
        start(context, Intent.createChooser(send, ref.name))
    }

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** 받은 파일을 사용자가 고른 곳([destination])에 복사한다. */
    suspend fun copyTo(context: Context, file: File, destination: Uri) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination) ?: throw IOException("저장할 곳을 열 수 없어요")
        output.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** 오래된 캐시를 지운다. 앱을 열 때 한 번. */
    fun trim(context: Context) {
        val now = System.currentTimeMillis()
        root(context).listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > KEEP_MS) dir.deleteRecursively()
        }
    }

    fun clear(context: Context) {
        root(context).deleteRecursively()
    }
}
