package dev.mooner.lucent.ui.common

import android.graphics.Bitmap
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.decode.BitmapFactoryDecoder
import coil.load
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import dev.mooner.lucent.R
import dev.mooner.lucent.data.MarkdownBlocks
import dev.mooner.lucent.data.PreviewKind
import dev.mooner.lucent.data.PreviewText
import dev.mooner.lucent.databinding.ItemPreviewPageBinding
import dev.mooner.lucent.databinding.SheetFilePreviewBinding
import dev.mooner.lucent.protocol.FileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 대화로 오간 파일을 앱 안에서 보이는 시트(StarLight의 `FilePreview`를 옮겼다).
 *
 * - 보일 수 있는 것은 그림, 마크다운, 글 파일, PDF다([PreviewKind]).
 * - 시트는 누르자마자 뜨고, 파일은 그 안에서 받는다. 받지 못한 까닭(보관 기간이 지남, 연결 없음)도 시트 안에 적고,
 *   잠깐의 일이면 "다시 시도"가 같이 놓인다.
 * - 글은 앞부분([PreviewText.MAX_BYTES])만 읽는다. 글로 읽히지 않거나 그림·PDF를 풀지 못하면 안내 한 줄로 바뀌고,
 *   아래의 "다른 앱"이 남는다.
 * - 파일의 글은 대화가 아니므로 커스텀 이모지를 그리지 않는다. 링크는 대화와 같이 주소를 보이고 확인을 받는다.
 * - 그림과 PDF의 쪽은 누르면 크게 보는 화면([ImageViewerActivity])이 열린다.
 * - 앱이 보일 수 없는 파일(Office 문서 등)도 받는 동안은 이 시트가 뜬다. 다 받으면 다른 앱에 넘기고 시트는 닫힌다.
 *   이미 받아 둔 것이면 시트 없이 바로 넘긴다.
 */
object FilePreview {

    /**
     * [ref]를 연다. 시트를 띄웠으면 그 시트를, 시트 없이 다른 앱에 넘겼으면 null을 돌려준다.
     * [onSave]는 저장할 곳을 묻는 화면을 연다.
     */
    fun show(activity: AppCompatActivity, ref: FileRef, onSave: (FileRef) -> Unit): SheetDialog? {
        val kind = PreviewKind.of(ref)
        if (kind == null) {
            FileActions.cached(activity, ref)?.let {
                FileActions.open(activity, ref, it)
                return null
            }
        }
        return Sheet(activity, ref, kind, onSave).dialog
    }

    private class Sheet(
        private val activity: AppCompatActivity,
        private val ref: FileRef,
        /** 앱이 보일 수 없는 파일이면 null. 받는 동안만 시트가 떠 있다. */
        private val kind: PreviewKind?,
        onSave: (FileRef) -> Unit,
    ) {
        private val binding = SheetFilePreviewBinding.inflate(LayoutInflater.from(activity))
        val dialog = SheetDialog(activity)
        private var local: File? = FileActions.cached(activity, ref)
        private var job: Job? = null
        private var pdf: PdfPages? = null

        init {
            dialog.setContentView(binding.root)
            binding.head.sheetTitle.text = ref.name
            binding.head.sheetClose.setOnClickListener { dialog.dismiss() }
            binding.meta.text = Format.fileMeta(activity, ref)
            binding.share.setOnClickListener { local?.let { FileActions.share(activity, ref, it) } }
            binding.openWith.setOnClickListener { local?.let { FileActions.open(activity, ref, it) } }
            binding.save.setOnClickListener { onSave(ref) }
            binding.retry.setOnClickListener { load() }
            load()
            dialog.closeWith(activity) {
                job?.cancel()
                pdf?.close()
                // 닫힌 시트를 화면이 잠시 더 쥐고 있어도, 그려 둔 쪽과 그림은 바로 놓는다.
                binding.pages.adapter = null
                binding.image.setImageDrawable(null)
            }
            dialog.show()
        }

        /** 할 일 셋은 받은 파일이 있어야 한다. 받는 중이거나 받지 못했으면 흐리다. */
        private fun setActions(ready: Boolean) {
            for (action in listOf(binding.share, binding.save, binding.openWith)) {
                action.isEnabled = ready
                action.alpha = if (ready) 1f else DISABLED_ALPHA
            }
        }

        private fun note(text: CharSequence) {
            binding.note.text = text
            binding.note.isVisible = true
        }

        /** 내용 없이 안내만 남는다(받지 못함, 보일 수 없음). 안내가 아래의 선에 붙지 않게 내용이 있던 자리를 조금 남긴다. */
        private fun noteOnly(text: CharSequence) {
            binding.loading.isVisible = false
            binding.scroll.isVisible = false
            binding.pages.isVisible = false
            binding.gap.isVisible = true
            note(text)
        }

        private fun unreadable() = noteOnly(activity.getString(R.string.preview_unreadable))

        /** 받고, 보인다. 받지 못했을 때 "다시 시도"가 이것을 다시 부른다. */
        private fun load() {
            binding.note.isVisible = false
            binding.retry.isVisible = false
            binding.gap.isVisible = false
            binding.scroll.isVisible = true
            // 받아 둔 파일은 곧바로 보인다. 고리는 받아야 할 때만 돈다.
            binding.loading.isVisible = local == null
            binding.progress.isIndeterminate = true
            setActions(local != null)
            job?.cancel()
            job = activity.lifecycleScope.launch {
                val file = local ?: try {
                    FileActions.fetch(activity, ref) { received, total ->
                        if (total > 0) binding.progress.post { binding.progress.setProgressCompat((received * 100 / total).toInt(), true) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    noteOnly(FileActions.describeFailure(activity, e))
                    // 서버가 지운 파일은 다시 해 봐도 같다.
                    binding.retry.isVisible = !FileActions.isPermanent(e)
                    return@launch
                }
                local = file
                setActions(true)
                when (kind) {
                    null -> handOff(file)
                    PreviewKind.IMAGE -> showImage(file)
                    PreviewKind.MARKDOWN -> showMarkdown(file)
                    PreviewKind.TEXT -> showText(file)
                    PreviewKind.PDF -> showPdf(file)
                }
            }
        }

        /**
         * 앱이 보일 수 없는 파일을 다 받았다. 사용자가 아직 이 시트를 보고 있을 때만 다른 앱에 넘긴다(그사이 다른 화면으로
         * 갔다면 느닷없이 다른 앱을 띄우지 않고, 시트에 할 일만 남긴다).
         */
        private fun handOff(file: File) {
            binding.loading.isVisible = false
            if (dialog.isShowing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                FileActions.open(activity, ref, file)
                dialog.dismiss()
            } else {
                unreadable()
            }
        }

        // -------------------------------------------------------------- 그림

        /**
         * 그림은 시트의 폭에 맞춘다. 세로로 아주 긴 그림은 화면 몇 장 높이까지만 풀어 둔다(그보다 큰 비트맵은 그릴 수 없다).
         * 제 해상도로는 크게 보는 화면에서 본다. 움직이는 그림은 "움직임 줄이기"가 켜져 있으면 첫 장면만 보인다.
         */
        private fun showImage(file: File) {
            val view = binding.image
            val metrics = view.resources.displayMetrics
            view.isVisible = true
            view.contentDescription = ref.name
            view.load(file) {
                crossfade(false)
                size(Dimension(metrics.widthPixels), Dimension(metrics.heightPixels * MAX_IMAGE_SCREENS))
                scale(Scale.FIT)
                // 그 크기는 넘지 말라는 뜻이다. 작은 그림을 거기까지 키워 풀지 않는다.
                precision(Precision.INEXACT)
                if (!Motion.enabled(view.context)) decoderFactory(BitmapFactoryDecoder.Factory())
                listener(
                    onSuccess = { _, result ->
                        binding.loading.visibility = View.GONE
                        // 작은 그림(아이콘 등)을 시트 폭까지 늘리면 뭉개지기만 한다. 몇 배까지만 키우고 가운데에 둔다.
                        // 받아 둔 그림은 시트가 놓이기도 전에 실린다. 폭은 놓인 뒤에 잰다.
                        val widest = result.drawable.intrinsicWidth * MAX_UPSCALE
                        (view.parent as View).doOnLayout { parent ->
                            val room = parent.width - parent.paddingLeft - parent.paddingRight
                            if (widest in 1 until room) view.updateLayoutParams { width = widest }
                        }
                        view.contentDescription = activity.getString(R.string.preview_zoom, ref.name)
                        view.setOnClickListener { ImageViewerActivity.start(activity, file, ref.name) }
                    },
                    onError = { _, _ -> unreadable() },
                )
            }
        }

        // -------------------------------------------------------------- 글

        private suspend fun read(file: File): PreviewText.Head? = try {
            withContext(Dispatchers.IO) { PreviewText.read(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        /** 읽은 글에 따라 붙는 안내. 보일 글이 없으면 false. */
        private fun describe(head: PreviewText.Head): Boolean {
            binding.loading.isVisible = false
            when {
                head.text.isBlank() -> note(activity.getString(R.string.preview_empty))
                head.truncated -> note(
                    activity.getString(R.string.preview_truncated, Format.size(activity, PreviewText.MAX_BYTES.toLong())),
                )
            }
            return head.text.isNotBlank()
        }

        /** 글 파일은 고정폭으로 글자 그대로. 기록·표·코드는 줄을 꺾지 않고 가로로 굴려 본다([PreviewText.wraps]). */
        private suspend fun showText(file: File) {
            val head = read(file) ?: return unreadable()
            if (!describe(head)) return
            if (PreviewText.wraps(ref.name, head.text)) {
                binding.code.isVisible = true
                binding.code.text = head.text
            } else {
                binding.codeScroll.isVisible = true
                binding.codeWide.text = head.text
            }
        }

        /** 풀어 둔 마크다운의 한 덩이: 글이거나 표다. */
        private sealed interface Prepared {
            class Text(val text: Spanned) : Prepared
            class Table(val aligns: List<MarkdownBlocks.Align>, val header: List<Spanned>, val rows: List<List<Spanned>>) : Prepared
        }

        /** 마크다운은 대화의 글과 같은 규칙으로 푼다. 표는 따로 떼어 가로로 구르는 표로 그린다. */
        private suspend fun showMarkdown(file: File) {
            val markdown = Markdown(activity)
            val head = read(file) ?: return unreadable()
            if (!describe(head)) return
            // 긴 글을 푸는 일은 화면 밖에서 한다.
            val prepared = withContext(Dispatchers.Default) {
                MarkdownBlocks.split(head.text).map { block ->
                    when (block) {
                        is MarkdownBlocks.Block.Text -> Prepared.Text(markdown.prepare(block.markdown))
                        is MarkdownBlocks.Block.Table -> Prepared.Table(
                            block.aligns,
                            block.header.map(markdown::prepare),
                            block.rows.map { row -> row.map(markdown::prepare) },
                        )
                    }
                }
            }
            binding.blocks.removeAllViews()
            binding.blocks.isVisible = true
            val gap = activity.resources.getDimensionPixelSize(R.dimen.space_m)
            for (block in prepared) {
                val view = when (block) {
                    is Prepared.Text -> textView(markdown, block.text)
                    is Prepared.Table -> tableView(markdown, block)
                }
                val first = binding.blocks.isEmpty()
                binding.blocks.addView(
                    view,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (!first) topMargin = gap
                    },
                )
            }
        }

        private fun textView(markdown: Markdown, text: Spanned) = TextView(activity, null, 0, R.style.Text_Body).apply {
            val side = resources.getDimensionPixelSize(R.dimen.space_s)
            setPadding(side, 0, side, 0)
            markdown.show(this, text)
            setTextIsSelectable(true)
            // 고를 수 있는 글은 링크를 누르지 못하게 되므로 다시 누를 수 있게 한다(고르기는 길게 눌러서 한다).
            movementMethod = LinkMovementMethod.getInstance()
        }

        /**
         * 표: 면도 테두리도 없이, 줄 사이의 가는 선과 굵은 머리 줄로만 짠다(시트 → 내용, 한 겹). 좁은 표는 시트 폭을 채우고
         * 넓은 표는 제 폭대로 가로로 구른다.
         */
        private fun tableView(markdown: Markdown, table: Prepared.Table): View {
            val r = activity.resources
            val side = r.getDimensionPixelSize(R.dimen.space_s)
            val between = r.getDimensionPixelSize(R.dimen.space_m)
            val vertical = r.getDimensionPixelSize(R.dimen.space_s)
            val cellMax = r.getDimensionPixelSize(R.dimen.table_cell_max_width)
            val bold = ResourcesCompat.getFont(activity, R.font.wantedsans_bold)
            val layout = TableLayout(activity).apply {
                isStretchAllColumns = true
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = ContextCompat.getDrawable(activity, R.drawable.divider_line)
            }

            fun row(cells: List<Spanned>, header: Boolean) = TableRow(activity).apply {
                cells.forEachIndexed { column, cell ->
                    addView(
                        TextView(activity, null, 0, R.style.Text_BodyS).apply {
                            if (header) typeface = bold
                            maxWidth = cellMax
                            gravity = when (table.aligns.getOrNull(column)) {
                                MarkdownBlocks.Align.CENTER -> Gravity.CENTER_HORIZONTAL
                                MarkdownBlocks.Align.END -> Gravity.END
                                else -> Gravity.START
                            }
                            // 칸 사이는 간격으로만 나눈다. 표의 양 끝은 글의 여백에 맞춘다.
                            setPadding(if (column == 0) side else between / 2, vertical, if (column == cells.lastIndex) side else between / 2, vertical)
                            markdown.show(this, cell)
                        },
                    )
                }
            }
            layout.addView(row(table.header, header = true))
            for (cells in table.rows) layout.addView(row(cells, header = false))
            return HorizontalScrollView(activity).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(layout)
            }
        }

        // -------------------------------------------------------------- PDF

        /** PDF는 쪽마다 그림으로 그려 세로로 늘어놓는다. 보이는 쪽만 그린다. */
        private suspend fun showPdf(file: File) {
            val pages = PdfPages.open(file, MAX_PDF_PAGES) ?: return unreadable()
            // 여는 사이 시트가 닫혔으면(닫힐 때는 아직 연 것이 없었다) 연 것을 도로 닫는다.
            if (!dialog.isShowing) return pages.close()
            pdf = pages
            binding.loading.isVisible = false
            binding.meta.text = activity.getString(R.string.file_meta_more, binding.meta.text, activity.getString(R.string.preview_pages, pages.total))
            if (pages.total > pages.sizes.size) note(activity.getString(R.string.preview_pages_truncated, pages.sizes.size))
            binding.scroll.isVisible = false
            binding.pages.isVisible = true
            binding.pages.layoutManager = LinearLayoutManager(activity)
            binding.pages.adapter = PageAdapter(pages)
        }

        private inner class PageAdapter(private val pages: PdfPages) : RecyclerView.Adapter<PageHolder>() {
            /** 그려 둔 쪽. 되돌아 올려 볼 때 다시 그리지 않게 몇 쪽만 쥐고 있는다. */
            private val drawn = object : LruCache<Int, Bitmap>(PAGE_CACHE_BYTES) {
                override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
            }
            private var zooming = false

            override fun getItemCount() = pages.sizes.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                PageHolder(ItemPreviewPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                val view = holder.binding.page
                val size = pages.sizes[position]
                val list = binding.pages
                val width = (list.width.takeIf { it > 0 } ?: list.resources.displayMetrics.widthPixels) - list.paddingLeft - list.paddingRight
                view.updateLayoutParams { height = (width.toLong() * size.height / size.width).toInt() }
                view.contentDescription = activity.getString(R.string.preview_zoom, activity.getString(R.string.preview_page, position + 1))
                view.setOnClickListener { zoom(position, width) }
                holder.job?.cancel()
                val ready = drawn.get(position)
                view.setImageBitmap(ready)
                if (ready != null) return
                holder.job = activity.lifecycleScope.launch {
                    val bitmap = pages.render(position, width.coerceAtMost(MAX_PAGE_WIDTH)) ?: return@launch
                    drawn.put(position, bitmap)
                    view.setImageBitmap(bitmap)
                }
            }

            override fun onViewRecycled(holder: PageHolder) {
                holder.job?.cancel()
                holder.binding.page.setImageDrawable(null)
            }

            /** 쪽을 누르면 더 촘촘히 그려 크게 보는 화면으로 넘긴다(시트 폭으로 그린 쪽은 키우면 흐리다). */
            private fun zoom(position: Int, width: Int) {
                if (zooming) return
                zooming = true
                activity.lifecycleScope.launch {
                    try {
                        val bitmap = pages.render(position, (width * ZOOM_FACTOR).coerceAtMost(MAX_ZOOM_WIDTH)) ?: return@launch
                        val target = File(FileActions.cachedFile(activity, ref).parentFile, "page-${position + 1}.jpg")
                        withContext(Dispatchers.IO) {
                            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, ZOOM_QUALITY, it) }
                            bitmap.recycle()
                        }
                        if (dialog.isShowing) {
                            ImageViewerActivity.start(activity, target, activity.getString(R.string.preview_page_title, ref.name, position + 1))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 크게 그리지 못하면 그대로 둔다(시트의 쪽은 보이고 있다).
                    } finally {
                        zooming = false
                    }
                }
            }
        }

        private class PageHolder(val binding: ItemPreviewPageBinding) : RecyclerView.ViewHolder(binding.root) {
            var job: Job? = null
        }
    }

    private const val DISABLED_ALPHA = 0.4f
    private const val MAX_IMAGE_SCREENS = 3
    private const val MAX_UPSCALE = 3

    /** 쪽이 이보다 많은 PDF는 앞쪽만 보인다. */
    private const val MAX_PDF_PAGES = 200
    private const val MAX_PAGE_WIDTH = 1600
    private const val PAGE_CACHE_BYTES = 48 * 1024 * 1024
    private const val ZOOM_FACTOR = 3
    private const val MAX_ZOOM_WIDTH = 2400
    private const val ZOOM_QUALITY = 92
}
