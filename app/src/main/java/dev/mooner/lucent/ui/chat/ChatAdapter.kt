package dev.mooner.lucent.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.ImageViewCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.decode.BitmapFactoryDecoder
import coil.load
import coil.size.Precision
import coil.size.Scale
import dev.mooner.lucent.R
import dev.mooner.lucent.data.Delivery
import dev.mooner.lucent.data.ExpressionStore
import dev.mooner.lucent.data.HistoryLines
import dev.mooner.lucent.data.StickerLine
import dev.mooner.lucent.data.StickerRef
import dev.mooner.lucent.data.StoredItem
import dev.mooner.lucent.databinding.ItemCardBinding
import dev.mooner.lucent.databinding.ItemCardFieldBinding
import dev.mooner.lucent.databinding.ItemDateBinding
import dev.mooner.lucent.databinding.ItemFileBinding
import dev.mooner.lucent.databinding.ItemImageBinding
import dev.mooner.lucent.databinding.ItemLuciaBinding
import dev.mooner.lucent.databinding.ItemNoteBinding
import dev.mooner.lucent.databinding.ItemTypingBinding
import dev.mooner.lucent.databinding.ItemUserBinding
import dev.mooner.lucent.protocol.Card
import dev.mooner.lucent.protocol.CardButton
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.Forms
import dev.mooner.lucent.ui.common.EmojiSpans
import dev.mooner.lucent.ui.common.ExpressionImage
import dev.mooner.lucent.ui.common.FileActions
import dev.mooner.lucent.ui.common.FileIcons
import dev.mooner.lucent.ui.common.Format
import dev.mooner.lucent.ui.common.Markdown
import dev.mooner.lucent.ui.common.Motion
import dev.mooner.lucent.ui.common.Plain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 어댑터가 화면에 알리는 사용자 동작. */
interface ChatActions {
    fun onPress(ref: String, button: CardButton)
    fun onResend(item: StoredItem)
    fun onDiscard(item: StoredItem)
    fun onOpenFile(file: FileRef)
    fun onSaveFile(file: FileRef)

    /** 말을 길게 눌렀다. [anchor] 곁에 그 말로 할 수 있는 일(복사, 글 고르기)을 보인다. */
    fun onMessageMenu(anchor: View, item: StoredItem)

    /** 루시아의 얼굴을 눌렀다. 프로필 그림을 크게 보인다. */
    fun onFace()

    /** 그림이 실려 줄의 높이가 달라졌다. 끝을 보고 있었다면 화면이 끝에 머물게 한다. */
    fun onRowResized()
}

/**
 * 대화 목록. 줄 종류마다 레이아웃이 하나씩 있고(날짜, 내 말, 루시아의 말, 진행 안내, 카드, 쓰는 중), [Row.key]가 같으면 같은
 * 줄로 보고 내용이 바뀐 것만 다시 그린다. 카드는 `card_closed`가 오면 같은 줄이 닫힌 모양으로 바뀐다.
 */
class ChatAdapter(
    private val scope: CoroutineScope,
    private val markdown: Markdown,
    private val actions: ChatActions,
    private val ownConversation: () -> String?,
    private val expressions: ExpressionStore,
    private val emoji: EmojiSpans,
) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        is Row.Day -> TYPE_DAY
        is Row.Message -> if (row.item.sender == dev.mooner.lucent.data.Sender.USER) TYPE_USER else TYPE_LUCIA
        is Row.Note -> TYPE_NOTE
        is Row.CardRow -> TYPE_CARD
        Row.Typing -> TYPE_TYPING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DAY -> DayHolder(ItemDateBinding.inflate(inflater, parent, false))
            TYPE_USER -> UserHolder(ItemUserBinding.inflate(inflater, parent, false))
            TYPE_LUCIA -> LuciaHolder(ItemLuciaBinding.inflate(inflater, parent, false))
            TYPE_NOTE -> NoteHolder(ItemNoteBinding.inflate(inflater, parent, false))
            TYPE_CARD -> CardHolder(ItemCardBinding.inflate(inflater, parent, false))
            else -> TypingHolder(ItemTypingBinding.inflate(inflater, parent, false))
        }
    }

    // ------------------------------------------------------------------ 루시아의 얼굴

    /**
     * 루시아가 정한 프로필 그림(사양 8.7). null이면 앱의 기본 모습이다. 바뀌면 이미 그려 둔 줄의 얼굴만 바꿔 그린다
     * (줄의 나머지는 건드리지 않는다).
     */
    var face: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, FACE)
        }

    /** 보이는 이름(결정 0064). "쓰는 중" 줄을 읽어 줄 때 쓴다. */
    var name: String = ""
        set(value) {
            if (field == value) return
            field = value
            val typing = currentList.indexOf(Row.Typing)
            if (typing >= 0) notifyItemChanged(typing)
        }

    private fun showFace(view: ImageView) {
        val bitmap = face
        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(R.drawable.ic_avatar_placeholder)
    }

    private inner class TypingHolder(private val b: ItemTypingBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.avatar.setOnClickListener { actions.onFace() }
        }

        fun bind() {
            showFace(b.avatar)
            b.bubble.contentDescription = b.root.context.getString(R.string.chat_typing, name)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === FACE }) {
            when (holder) {
                is LuciaHolder -> holder.bindFace()
                is TypingHolder -> holder.bind()
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Day -> (holder as DayHolder).bind(row)
            is Row.Message -> if (holder is UserHolder) holder.bind(row) else (holder as LuciaHolder).bind(row)
            is Row.Note -> (holder as NoteHolder).bind(row)
            is Row.CardRow -> (holder as CardHolder).bind(row)
            Row.Typing -> (holder as TypingHolder).bind()
        }
    }

    // ------------------------------------------------------------------ 날짜, 진행 안내

    private class DayHolder(private val b: ItemDateBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Day) {
            b.date.text = Format.day(row.epochDay)
        }
    }

    /** 잇달아 온 안내는 한 줄이다. 마지막 것만 보이고, 앞의 것이 있으면 눌러서 펼친다. */
    private inner class NoteHolder(private val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Note) {
            val ctx = b.root.context
            // 진행 안내의 글에도 이모지 표기가 올 수 있다(app-expressions.md 4.2).
            b.note.text = emoji.plain(Plain.text(row.latest.body))

            val earlier = row.items.dropLast(1)
            val open = row.key in expanded
            b.count.isVisible = earlier.isNotEmpty() && !open
            b.count.text = ctx.getString(R.string.note_more, earlier.size)
            b.earlier.isVisible = earlier.isNotEmpty() && open
            b.earlier.text = emoji.plain(earlier.joinToString("\n") { Plain.text(it.body) })
            if (earlier.isNotEmpty()) {
                b.root.setOnClickListener { toggle(row.key, this) }
            } else {
                b.root.setOnClickListener(null)
                b.root.isClickable = false
            }

            // 지금 하는 일이면 별이 깜박이고 글이 한 단계 짙다.
            b.spark.live = row.live
            b.note.setTextColor(ctx.getColor(if (row.live) R.color.ink_muted else R.color.ink_faint))
        }
    }

    // ------------------------------------------------------------------ 내 말

    /** 묶음의 첫 줄 위는 space_m, 묶음 안의 다음 줄 위는 space_xs. 같은 쪽의 말이 이어질 때만 가까워진다. */
    private fun spaceAbove(root: View, groupStart: Boolean) {
        val r = root.resources
        root.updatePadding(top = r.getDimensionPixelSize(if (groupStart) R.dimen.space_m else R.dimen.space_xs))
    }

    private inner class UserHolder(private val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Message) {
            spaceAbove(b.root, row.avatar)
            val item = row.item
            val ctx = b.root.context
            // 다른 기기에서 한 내 말(결정 0067)이면 묶음의 첫 줄 위에 그 기기를 작게 적는다.
            val device = deviceOf(item, ownConversation())
            b.source.isVisible = device != null && row.avatar
            b.source.text = when (device) {
                null -> ""
                is Device.Named -> ctx.getString(R.string.sent_from, device.name)
                Device.Other -> ctx.getString(R.string.sent_from_other)
                Device.Discord -> ctx.getString(R.string.sent_from, ctx.getString(R.string.source_discord))
            }
            // 함께 보낸 고른 글은 글이 아니라 표지 한 줄로만 보인다(고른 글 자체는 오지 않는다).
            b.context.isVisible = item.contextNote != null
            b.context.text = item.contextNote?.let { Plain.line(HistoryLines.contextLabel(it)) }.orEmpty()
            // 스티커는 말풍선 아래에 그림 한 장으로 놓인다. 보내는 중·실패 표시와 다시 보내기는 그대로 그 아래에 붙는다.
            bindSticker(b.sticker, b.stickerFallback, item.sticker, below = item.body.isNotBlank())
            b.text.isVisible = item.body.isNotBlank()
            // 내 말은 마크다운 없이 글 그대로 보이되, 글 속의 `:name:`은 이모지로 그린다.
            b.text.text = emoji.plain(item.body, jumbo = true)
            b.text.setOnLongClickListener { actions.onMessageMenu(b.text, item); true }
            bindFiles(b.files, item.files)

            val failed = item.delivery == Delivery.FAILED
            val unconfirmed = item.delivery == Delivery.UNCONFIRMED
            val text = when (item.delivery) {
                Delivery.SENDING -> ctx.getString(R.string.message_sending)
                Delivery.UNCONFIRMED -> ctx.getString(R.string.message_unconfirmed)
                Delivery.FAILED -> ctx.getString(R.string.message_failed)
                Delivery.SENT -> if (row.time) Format.time(item.at) else ""
            }
            b.statusRow.isVisible = text.isNotEmpty()
            b.status.text = text
            b.status.setTextColor(ctx.getColor(if (failed) R.color.error else if (unconfirmed) R.color.warn else R.color.ink_faint))
            b.resend.isVisible = failed || unconfirmed
            b.discard.isVisible = failed || unconfirmed
            b.resend.setOnClickListener { actions.onResend(item) }
            b.discard.setOnClickListener { actions.onDiscard(item) }
        }
    }

    // ------------------------------------------------------------------ 루시아의 말

    private inner class LuciaHolder(private val b: ItemLuciaBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // 묶음의 첫 줄에만 보인다. 보이지 않는 자리는 눌리지 않는다.
            b.avatar.setOnClickListener { actions.onFace() }
        }

        fun bindFace() = showFace(b.avatar)

        fun bind(row: Row.Message) {
            spaceAbove(b.root, row.avatar)
            val item = row.item
            val ctx = b.root.context
            b.avatar.visibility = if (row.avatar) View.VISIBLE else View.INVISIBLE
            bindFace()

            // 긴 글은 말풍선이 반대쪽 여백 없이 폭을 다 쓴다.
            val r = b.root.resources
            b.root.updatePaddingRelative(end = r.getDimensionPixelSize(if (row.wide) R.dimen.gutter else R.dimen.bubble_gap))
            val width = if (row.wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            if (b.bubble.layoutParams.width != width) b.bubble.updateLayoutParams { this.width = width }

            val source = sourceOf(item, ownConversation())
            b.source.isVisible = source != null && row.avatar
            b.source.text = when (source) {
                Source.DISCORD -> ctx.getString(R.string.source_discord)
                Source.DAEMON -> ctx.getString(R.string.source_daemon)
                null -> ""
            }

            // 스티커만 있는 줄(`sticker` 프레임)은 말풍선이 없다.
            b.bubble.isVisible = item.body.isNotBlank() || item.files.isNotEmpty()
            bindSticker(b.sticker, b.stickerFallback, item.sticker, below = b.bubble.isVisible)
            b.text.isVisible = item.body.isNotBlank()
            if (item.body.isNotBlank()) markdown.render(b.text, item.body, jumbo = true)
            // 할 일은 글 곁에 뜬다(말풍선에는 파일도 들어 있어 그 위쪽이 화면 밖일 수 있다).
            b.bubble.setOnLongClickListener { if (item.body.isNotBlank()) actions.onMessageMenu(b.text, item); true }
            // 글 안의 링크를 누를 수 있게 하면 글이 긴 누름을 가져간다. 말풍선과 같은 일을 하게 한다.
            b.text.setOnLongClickListener { actions.onMessageMenu(b.text, item); true }
            bindFiles(b.files, item.files)

            b.time.isVisible = row.time
            b.time.text = Format.time(item.at)
        }
    }

    // ------------------------------------------------------------------ 스티커

    /**
     * 스티커 한 장을 싣는다(app-expressions.md 5.3, 5.4). 지금의 목록에서 찾고(ID 먼저, 없으면 이름), 그릴 수 없으면
     * (목록에 없음, 그림을 아직 못 받음, 디코더가 받지 못함) 작은 상자의 `[스티커: 이름]`으로 대신한다.
     * 그림이 나중에 도착하면 화면이 이 줄을 다시 그린다.
     */
    private fun bindSticker(view: ImageView, fallback: TextView, ref: StickerRef?, below: Boolean) {
        // 같은 말의 글 아래에 놓일 때만 사이를 띄운다(묶음 안의 줄 사이와 같은 간격).
        val gap = if (below) view.resources.getDimensionPixelSize(R.dimen.space_xs) else 0
        for (v in arrayOf<View>(view, fallback)) {
            val params = v.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin != gap) v.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = gap }
        }
        if (ref == null) {
            view.isVisible = false
            fallback.isVisible = false
            ExpressionImage.clear(view)
            return
        }
        val ctx = view.context
        val sticker = expressions.current.value.sticker(ref)
        val file = sticker?.let(expressions::file)
        val name = sticker?.name ?: ref.name
        fallback.text = StickerLine.fallback(name)
        fallback.isVisible = file == null
        view.isVisible = file != null
        if (sticker == null || file == null) {
            ExpressionImage.clear(view)
            return
        }
        val spoken = ctx.getString(R.string.sticker_spoken, name)
        view.contentDescription = if (sticker.description.isBlank()) spoken else "$spoken, ${sticker.description}"
        ExpressionImage.load(view, file) {
            view.isVisible = false
            fallback.isVisible = true
        }
        // 길게 누르면 이름과 설명을 보인다.
        view.setOnLongClickListener {
            val about = if (sticker.description.isBlank()) name else "$name\n${sticker.description}"
            android.widget.Toast.makeText(ctx, about, android.widget.Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ------------------------------------------------------------------ 파일

    /** 이미지는 이미지 그대로, 그 밖의 파일은 줄로 보인다. 둘 다 상자 안의 상자를 만들지 않는다. */
    private fun bindFiles(container: LinearLayout, files: List<FileRef>) {
        container.removeAllViews()
        container.isVisible = files.isNotEmpty()
        if (files.isEmpty()) return
        val inflater = LayoutInflater.from(container.context)
        for (file in files) {
            // 기록에 보인 파일 가운데 지워졌거나 이 기기에서 받을 수 없는 것은 이름만 있는 줄이다(사양 6.2).
            if (file.isImage && file.available) {
                val ib = ItemImageBinding.inflate(inflater, container, false)
                ib.image.contentDescription = file.name
                ib.image.setOnClickListener { actions.onOpenFile(file) }
                ib.image.setOnLongClickListener { actions.onSaveFile(file); true }
                loadImage(ib, file)
                container.addView(ib.root)
            } else {
                val fb = ItemFileBinding.inflate(inflater, container, false)
                fb.thumb.setImageResource(FileIcons.of(file))
                fb.name.text = file.name
                fb.meta.text = Format.fileMeta(container.context, file)
                fb.save.isVisible = file.available
                fb.root.setOnClickListener { actions.onOpenFile(file) }
                fb.save.setOnClickListener { actions.onSaveFile(file) }
                container.addView(fb.root)
            }
        }
    }

    /** 받아 둔 그림과 그 크기(화소, 돌려 찍은 사진은 바로 세운 크기). 크기를 읽지 못했으면 0이다. */
    private class Picture(val file: File, val width: Int, val height: Int)

    /**
     * 그림 받기는 줄(뷰)이 아니라 목록이 쥔다. 줄은 자주 다시 그려지는데(다음 말이 와서 시각이 옮겨 가는 등), 그때마다
     * 받던 것을 버리고 처음부터 다시 받지 않게 하려는 것이다. 받지 못한 것은 지워 두어 다음에 그릴 때 다시 받아 본다.
     */
    private val pictures = HashMap<String, Deferred<Picture?>>()

    /** 한 번 잰 그림의 크기. 줄이 다시 그려질 때 자리부터 제 크기로 잡아, 줄의 높이가 출렁이지 않게 한다. */
    private val pictureSizes = LruCache<String, Size>(PICTURE_SIZES)

    private fun picture(ctx: Context, file: FileRef): Deferred<Picture?> = pictures.getOrPut(file.id) {
        scope.async {
            val local = try {
                FileActions.fetch(ctx, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@async null
            }
            val size = withContext(Dispatchers.IO) { runCatching { sizeOf(local) }.getOrNull() }
            Picture(local, size?.width ?: 0, size?.height ?: 0)
        }
    }

    /** 그림을 풀지 않고 크기만 읽는다. 사진기가 돌려 저장한 사진은 가로·세로를 바꿔 준다. */
    private fun sizeOf(file: File): Size? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val turned = when (runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }.getOrDefault(0)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
        }
        return if (turned) Size(bounds.outHeight, bounds.outWidth) else Size(bounds.outWidth, bounds.outHeight)
    }

    /**
     * 이미지는 내려받아 보인다. 내려받지 못하면 자리표시(하늘 안개 면)만 남는다. 자리의 크기는 [ImageBox]가 정한다:
     * 길쭉한 그림은 자리를 채우고 넘치는 쪽을 자르고, 작은 그림은 조금만 키워 가운데에 둔다.
     */
    private fun loadImage(ib: ItemImageBinding, file: FileRef) {
        val view = ib.image
        val ctx = view.context
        val known = pictureSizes.get(file.id)
        place(view, known)
        var shown = false
        fun show(): Job = scope.launch {
            val waiting = picture(ctx, file)
            val picture = waiting.await()
            if (picture == null) {
                pictures.remove(file.id, waiting)
                return@launch
            }
            val size = Size(picture.width, picture.height)
            if (known == null) {
                pictureSizes.put(file.id, size)
                val before = view.layoutParams.height
                val box = place(view, size)
                if (box.height != before) actions.onRowResized()
            }
            val box = boxOf(view, size)
            view.load(picture.file) {
                crossfade(known == null)
                // 자리에 맞는 만큼만 푼다. 자르는 그림은 자리를 덮을 만큼만, 가운데에 두는 작은 그림은 보일 크기에 꼭 맞춰
                // (조금 키워서) 푼다. 뷰는 푼 그대로를 가운데에 그린다.
                size(box.contentWidth, box.contentHeight)
                scale(if (box.crop) Scale.FILL else Scale.FIT)
                precision(if (box.crop) Precision.INEXACT else Precision.EXACT)
                if (!Motion.enabled(ctx)) decoderFactory(BitmapFactoryDecoder.Factory())
            }
            shown = true
        }
        var job = show()
        ib.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // 받는 도중 화면 바로 밖으로 나갔던 줄은 다시 그려지지 않은 채(목록이 쥐고 있던 뷰 그대로) 돌아온다.
            // 그때 기다리기를 다시 시작하지 않으면 빈 자리로 남는다.
            override fun onViewAttachedToWindow(v: View) { if (!shown && !job.isActive) job = show() }
            // 기다리기만 그만둔다. 받는 일은 이어진다.
            override fun onViewDetachedFromWindow(v: View) { job.cancel() }
        })
    }

    private fun boxOf(view: ImageView, size: Size?): ImageBox {
        val r = view.resources
        return ImageBox.fit(
            size?.width ?: 0, size?.height ?: 0,
            minWidth = r.getDimensionPixelSize(R.dimen.image_preview_min_width),
            minHeight = r.getDimensionPixelSize(R.dimen.image_preview_min_height),
            max = r.getDimensionPixelSize(R.dimen.image_preview_max),
        )
    }

    /** 그림의 자리를 잡는다. 크기를 아직 모르면 가장 작은 자리다. */
    private fun place(view: ImageView, size: Size?): ImageBox {
        val box = boxOf(view, size)
        view.updateLayoutParams {
            width = box.width
            height = box.height
        }
        // 모서리를 깎는 뷰는 안쪽 여백을 주면 깎는 자리도 따라 줄어든다. 여백 없이, 그리는 방식으로 가운데에 둔다.
        view.scaleType = if (box.crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER
        return box
    }

    // ------------------------------------------------------------------ 카드

    private inner class CardHolder(private val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(row: Row.CardRow) {
            val item = row.item
            val card: Card = item.card ?: Card(headline = item.body)
            val ctx = b.root.context
            val look = Look.of(card.tone)
            val pending = card.isPending

            b.card.setBackgroundResource(if (pending) R.drawable.bg_card_pending else R.drawable.bg_card)

            // 서버가 붙인 이모지(✅ ⌛ 등)는 지운다. 같은 뜻을 어조 아이콘이 이미 전한다.
            val headline = Plain.line(card.headline)
            val title = Plain.text(card.title)
            b.headline.text = headline
            b.headline.isVisible = headline.isNotBlank()
            b.title.text = title
            b.title.isVisible = title.isNotBlank()
            b.tone.setImageResource(look.icon)
            ImageViewCompat.setImageTintList(b.tone, ColorStateList.valueOf(ctx.getColor(look.tint)))

            val body = Plain.text(card.body)
            val footer = Plain.text(card.footer.orEmpty())
            val ref = item.ref
            val hasButtons = card.buttons.isNotEmpty() && ref != null
            val hasDetails = card.fields.isNotEmpty() || body.isNotBlank() || footer.isNotBlank() || hasButtons

            // 답을 기다리는 카드는 늘 다 보인다(사양 12.5). 닫힌 카드는 한 줄로 접혀 있다가 누르면 펼쳐진다.
            val open = pending || row.key in expanded
            val foldable = !pending && hasDetails
            b.details.isVisible = hasDetails && open
            b.chevron.isVisible = foldable
            b.chevron.rotation = if (open) CHEVRON_OPEN else CHEVRON_CLOSED
            if (foldable) {
                b.head.setBackgroundResource(R.drawable.bg_card_row)
                b.head.setOnClickListener { toggle(row.key, this) }
                b.head.contentDescription = ctx.getString(if (open) R.string.card_collapse else R.string.card_expand, title.ifBlank { headline })
            } else {
                b.head.background = null
                b.head.setOnClickListener(null)
                b.head.isClickable = false
                b.head.contentDescription = null
            }
            if (!b.details.isVisible) return

            bindFields(card)
            b.bodyLine.isVisible = body.isNotBlank() && card.fields.isNotEmpty()
            b.body.isVisible = body.isNotBlank()
            if (body.isNotBlank()) markdown.render(b.body, body)
            b.body.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = if (card.fields.isEmpty()) 0 else ctx.resources.getDimensionPixelSize(R.dimen.space_m)
            }
            b.footer.isVisible = footer.isNotBlank()
            b.footer.text = footer

            bindButtons(item, Forms.buttons(card, row.forms), row.pressing)
        }

        /** 짧은 값(`inline`)은 옆 칸과 나란히 두고, 나머지는 한 줄씩 둔다. */
        private fun bindFields(card: Card) {
            b.fields.removeAllViews()
            b.fields.isVisible = card.fields.isNotEmpty()
            val inflater = LayoutInflater.from(b.root.context)
            var pending: LinearLayout? = null
            for (field in card.fields) {
                // 나란한 칸은 가로 줄을 부모로 삼아 만든다. 부모 없이 만들면 칸의 너비 나눔(0dp, 무게 1)이 버려져
                // 칸이 글 길이만큼만 차지하고 서로 붙는다.
                val rowLayout = if (!field.inline) null else pending ?: LinearLayout(b.root.context).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    b.fields.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val fb = ItemCardFieldBinding.inflate(inflater, rowLayout ?: b.fields, false)
                val name = Plain.text(field.name)
                fb.name.text = name
                fb.name.isVisible = name.isNotBlank()
                markdown.render(fb.value, Plain.text(field.value))
                if (rowLayout != null) {
                    rowLayout.addView(fb.root)
                    pending = if (rowLayout.childCount >= INLINE_PER_ROW) null else rowLayout
                } else {
                    pending = null
                    fb.root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    b.fields.addView(fb.root)
                }
            }
        }

        /** 대기 중인 카드만 버튼이 있다. 누른 뒤 `card_closed`가 올 때까지 잠근다(사양 12.5). */
        private fun bindButtons(item: StoredItem, buttons: List<CardButton>, pressing: Boolean) {
            val ref = item.ref
            b.buttons.removeAllViews()
            b.buttons.isVisible = buttons.isNotEmpty() && ref != null
            if (buttons.isEmpty() || ref == null) return

            val vertical = buttons.size > 2
            b.buttons.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val inflater = LayoutInflater.from(b.root.context)
            for (button in buttons) {
                val view = inflater.inflate(R.layout.item_card_button, b.buttons, false) as TextView
                view.text = Plain.text(button.label)
                if (button.primary) {
                    view.setBackgroundResource(R.drawable.bg_button_primary)
                    view.setTextColor(view.context.getColor(R.color.on_sky))
                }
                val params = view.layoutParams as LinearLayout.LayoutParams
                if (vertical) {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT
                    params.marginStart = 0
                    params.topMargin = view.resources.getDimensionPixelSize(R.dimen.space_s)
                }
                view.isEnabled = !pressing
                view.alpha = if (pressing) PRESSING_ALPHA else 1f
                view.setOnClickListener { actions.onPress(ref, button) }
                b.buttons.addView(view)
            }
        }
    }

    /** 카드의 어조(`tone`)에 따른 아이콘과 색. 색에 기대지 않도록 아이콘 모양도 다르다. 윤곽선은 대기 여부가 정한다. */
    private class Look(val icon: Int, val tint: Int) {
        companion object {
            fun of(tone: String) = when (tone) {
                "pending" -> Look(R.drawable.ic_sparkle, R.color.gold_deep)
                "success" -> Look(R.drawable.ic_check, R.color.ok)
                "danger" -> Look(R.drawable.ic_warning, R.color.error)
                "warning" -> Look(R.drawable.ic_warning, R.color.warn)
                else -> Look(R.drawable.ic_info, R.color.ink_faint)
            }
        }
    }

    // ------------------------------------------------------------------ 등장

    /** 방금 도착한 줄의 열쇠. 그 줄이 화면에 붙는 순간 한 번, 살짝 떠오르며 나타난다. */
    var arriving: String? = null

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || arriving == null || getItem(position).key != arriving) return
        arriving = null
        val view = holder.itemView
        if (!Motion.enabled(view.context)) return
        view.alpha = 0f
        view.translationY = view.resources.getDimension(R.dimen.arrive_rise)
        view.animate().alpha(1f).translationY(0f).setDuration(ARRIVE_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // 떠오르던 줄이 다른 내용으로 다시 쓰일 때 반쯤 투명한 채 남지 않게 한다.
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
    }

    // ------------------------------------------------------------------ 펼침

    /** 펼쳐 둔 줄(닫힌 카드, 합친 진행 안내)의 열쇠. 화면을 떠나면 잊는다. */
    private val expanded = HashSet<String>()

    private fun toggle(key: String, holder: RecyclerView.ViewHolder) {
        val opened = expanded.add(key)
        if (!opened) expanded.remove(key)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val list = holder.itemView.parent as? RecyclerView
        if (list != null && Motion.enabled(list.context)) TransitionManager.beginDelayedTransition(list, AutoTransition().setDuration(EXPAND_MS))
        notifyItemChanged(position)
        // 펼친 내용이 입력창 아래로 넘어가면 보이는 데까지 따라 올린다.
        if (opened) list?.doOnNextLayout { list.smoothScrollToPosition(position) }
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_USER = 1
        const val TYPE_LUCIA = 2
        const val TYPE_NOTE = 3
        const val TYPE_CARD = 4
        const val TYPE_TYPING = 5
        const val INLINE_PER_ROW = 2
        const val PRESSING_ALPHA = 0.45f
        const val CHEVRON_CLOSED = 90f
        const val CHEVRON_OPEN = 270f
        const val EXPAND_MS = 180L
        const val ARRIVE_MS = 240L
        const val PICTURE_SIZES = 300
        /** 얼굴만 바꿔 그리라는 표시(`notifyItemRangeChanged`의 payload). */
        val FACE = Any()
    }
}
