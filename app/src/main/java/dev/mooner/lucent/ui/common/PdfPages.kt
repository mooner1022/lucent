package dev.mooner.lucent.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF의 쪽을 그림으로 그린다(플랫폼의 `PdfRenderer`. 웹뷰를 쓰지 않는다).
 *
 * 그리는 기계는 한 번에 한 쪽만 열 수 있으므로 모든 일이 [lock]을 거친다. 다 쓰면 [close]로 닫는다(그리던 쪽이 끝난 뒤에
 * 닫힌다).
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** 보이는 쪽들의 크기(포인트). 쪽이 아주 많으면 앞쪽만이다. */
    val sizes: List<Size>,
    /** 파일의 전체 쪽수. */
    val total: Int,
) {
    private val lock = Mutex()
    private var closed = false

    /** [index]번째 쪽을 폭 [width] 화소로 그린다. 닫혔거나 그리지 못하면 null. 종이는 흰색이다. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in sizes.indices || width <= 0) return@withLock null
            runCatching {
                renderer.openPage(index).use { page ->
                    val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                    createBitmap(width, height).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        /**
         * [file]을 연다. PDF가 아니거나, 깨졌거나, 암호가 걸려 있으면 null이다.
         * 쪽의 크기는 앞 [maxPages]쪽까지만 읽는다.
         *
         * 여는 도중에 부른 쪽이 그만두어도(시트를 닫음) 연 것은 끝까지 열어 돌려준다. 도중에 버리면 닫을 길이 없어
         * 파일이 열린 채 남는다. 부른 쪽이 받아서 닫는다.
         */
        suspend fun open(file: File, maxPages: Int): PdfPages? = withContext(NonCancellable + Dispatchers.IO) {
            val descriptor = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
                ?: return@withContext null
            // 깨진 파일은 IOException, 암호가 걸린 파일은 SecurityException이다.
            val renderer = runCatching { PdfRenderer(descriptor) }.getOrNull()
            if (renderer == null) {
                runCatching { descriptor.close() }
                return@withContext null
            }
            val sizes = runCatching {
                (0 until minOf(renderer.pageCount, maxPages)).map { index ->
                    renderer.openPage(index).use { Size(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
                }
            }.getOrNull()
            if (sizes.isNullOrEmpty()) {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                return@withContext null
            }
            PdfPages(descriptor, renderer, sizes, renderer.pageCount)
        }
    }
}
