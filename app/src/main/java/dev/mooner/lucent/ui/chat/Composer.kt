package dev.mooner.lucent.ui.chat

import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import coil.load
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.data.ExpressionKind
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.data.Expressions
import dev.mooner.lucent.databinding.ItemAttachmentChipBinding
import dev.mooner.lucent.databinding.ItemCompletionBinding
import dev.mooner.lucent.databinding.ViewComposerBinding
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.ui.common.ExpressionImage
import dev.mooner.lucent.ui.common.FileIcons
import dev.mooner.lucent.ui.common.Format
import dev.mooner.lucent.ui.common.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 글 칸, 파일 올리기, 보내기 단추(사양 12.4). 상황에 맞춰 바뀐다(docs/design.md 7장).
 *
 * - 보내기 단추는 글이나 첨부가 있을 때만 나타난다(비활성 회색 단추를 두지 않는다). 오프라인이면 흐려지고 눌러도 보내지 않는다.
 * - 글 칸에 초점이 있으면 상자의 윤곽선이 하늘색으로 켜진다. 첨부 칩은 상자 밖(위)에 놓인다.
 * - 안내 글(hint)은 지금 상황이 정한다: 평소, 승인 대기 중, 오프라인. 글은 오프라인에서도 쓸 수 있다.
 * - + 단추는 "더하기" 시트를 연다(사진·파일, 권한 모드·주 모델). 연결이 없어도 열린다.
 * - 글 칸 오른쪽의 이모지 단추는 키보드 자리에 이모지·스티커 판을 연다(다른 메신저와 같은 자리). 이모지·스티커를 다루지 않는
 *   데몬이면 단추가 없다.
 * - 글을 쓰다 `:` 다음에 두 글자를 넣으면 맞는 이모지가 상자 위에 놓인다. 고르면 `:name:`이 담긴다. 입력창 안에서는 글자
 *   그대로 보이고, 보낼 때도 그 글이 간다(app-expressions.md 7장).
 * - 파일은 고르는 즉시 `POST /v1/files`로 올려 ID를 받고, 말을 보낼 때 `message.files`에 실는다. 올리는 중이거나
 *   올리지 못한 파일이 있으면 말을 보내지 않는다. 올리지 못한 칩을 누르면 다시 올린다.
 */
class Composer(
    private val binding: ViewComposerBinding,
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val hub: LucentHub,
    /** + 단추: "더하기" 시트를 연다. */
    private val onAdd: () -> Unit,
    /** 이모지 단추: 이모지·스티커 판을 열거나 닫는다. */
    private val onEmotes: () -> Unit,
    private val onSend: suspend (text: String, files: List<FileRef>) -> LucentHub.SendResult,
    /** 쓰던 글·첨부가 생기거나 없어졌을 때. 화면이 상황 자리를 다시 정한다. */
    private val onDraftChanged: () -> Unit,
    /** 글 칸이 초점을 얻었을 때. 이모지·스티커 판이 열려 있었다면 화면이 닫는다. */
    private val onInputFocused: () -> Unit,
) {
    private class Attachment(
        val uri: Uri, val name: String, val size: Long,
        /** 그림이면 칩에 작게 보인다. */
        val image: Boolean,
        /** 붙여넣은 것은 받은 묶음이 살아 있는 동안만 읽을 수 있다. 다 올릴 때까지 쥐고 있는다. */
        val keep: Any?,
    ) {
        var percent = 0
        var ref: FileRef? = null
        var error: String? = null
        var job: Job? = null
        /** 올리는 중. 끝났거나(성공·실패) 아직 시작하지 않았으면 아니다. */
        val uploading get() = job?.isActive == true && ref == null && error == null
    }

    private val attachments = mutableListOf<Attachment>()
    private var sending = false
    private var online = true
    private var hadDraft = false
    private var appearing = false
    private val motion = Motion.enabled(activity)

    private var emotesAvailable = false

    init {
        binding.input.doAfterTextChanged {
            updateSend()
            updateCompletions()
        }
        // 글 칸에 초점이 있으면 상자의 윤곽선이 하늘색으로 켜진다.
        binding.input.setOnFocusChangeListener { _, focused ->
            binding.box.isActivated = focused
            if (focused) onInputFocused()
        }
        // 초점이 오갈 때 윤곽선이 한 번에 바뀌지 않고 번지듯 켜지고 꺼진다.
        if (motion) (binding.box.background as? StateListDrawable)?.run {
            setEnterFadeDuration(FOCUS_MS)
            setExitFadeDuration(FOCUS_MS)
        }
        binding.send.setOnClickListener { submit() }
        binding.attach.setOnClickListener { onAdd() }
        binding.emote.setOnClickListener { onEmotes() }
        // 붙여넣은 그림, 키보드가 넣는 그림·GIF, 끌어다 놓은 파일을 첨부로 받는다. 글은 글 칸이 평소대로 받는다.
        ViewCompat.setOnReceiveContentListener(binding.input, RECEIVED_TYPES) { _, payload ->
            val split = payload.partition { it.uri != null }
            split.first?.let { files ->
                val uris = (0 until files.clip.itemCount).mapNotNull { files.clip.getItemAt(it).uri }
                addUris(uris, keep = files)
            }
            split.second
        }
        updateSend()
    }

    val hasDraft: Boolean get() = binding.input.text.isNotBlank() || attachments.isNotEmpty()

    /** 지금 상황에 맞는 안내 글과 보내기 가능 여부. */
    fun setContext(online: Boolean, hint: CharSequence) {
        this.online = online
        if (binding.input.hint?.toString() != hint.toString()) binding.input.hint = hint
        updateSend()
    }

    fun setText(text: String) {
        binding.input.setText(text)
        binding.input.setSelection(text.length)
    }

    /** 쓰던 글 뒤에 잇는다(다른 앱이 공유한 글). 쓰던 글이 없으면 그 글이 된다. */
    fun appendText(text: String) {
        val draft = binding.input.text.toString()
        setText(if (draft.isBlank()) text else draft.trimEnd() + "\n" + text)
    }

    /** 이모지·스티커를 다루는 데몬일 때만(`welcome.expressions`가 있을 때만) 단추와 자동 완성이 있다. */
    fun setEmotesAvailable(available: Boolean) {
        if (emotesAvailable == available) return
        emotesAvailable = available
        binding.emote.isVisible = available
        updateCompletions()
    }

    /** 이모지·스티커 판이 열려 있으면 단추가 "키보드로 돌아가기"가 된다. */
    fun setEmotesOpen(open: Boolean) {
        binding.emote.setImageResource(if (open) R.drawable.ic_keyboard else R.drawable.ic_emote)
        ImageViewCompat.setImageTintList(
            binding.emote,
            android.content.res.ColorStateList.valueOf(activity.getColor(if (open) R.color.sky_deep else R.color.ink_muted)),
        )
        binding.emote.contentDescription = activity.getString(if (open) R.string.emote_keyboard else R.string.emote_title)
    }

    /** 커서 자리에 글을 끼워 넣는다(이모지의 `:이름:`). 고른 글이 있으면 그 자리를 바꾼다. */
    fun insert(text: String) {
        val editable = binding.input.text
        val start = binding.input.selectionStart.coerceAtLeast(0)
        val end = binding.input.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, text)
        binding.input.setSelection(start + text.length)
    }

    // ------------------------------------------------------------------ 이모지 자동 완성

    /** 목록이나 그림이 바뀌었을 때도 불린다(후보의 그림이 도착했을 수 있다). */
    fun updateCompletions() {
        val text = binding.input.text
        val cursor = binding.input.selectionEnd
        val completing = if (emotesAvailable && cursor >= 0) ExpressionText.completing(text, cursor) else null
        val found = completing?.let { hub.expressions.current.value.search(ExpressionKind.EMOJI, it.second, COMPLETION_MAX) }.orEmpty()
        binding.completions.removeAllViews()
        binding.completionScroll.isVisible = found.isNotEmpty()
        if (found.isEmpty()) return
        val inflater = LayoutInflater.from(activity)
        for (emoji in found) {
            val chip = ItemCompletionBinding.inflate(inflater, binding.completions, false)
            val file = hub.expressions.file(emoji)
            chip.image.isVisible = file != null
            if (file != null) ExpressionImage.load(chip.image, file) { chip.image.isVisible = false }
            chip.name.text = emoji.label.ifBlank { ":${emoji.name}:" }
            chip.root.contentDescription = activity.getString(R.string.emoji_spoken, Expressions.title(emoji))
            chip.root.setOnClickListener {
                // 쓰던 `:글`을 `:name:`으로 바꾼다. 그사이 글이 바뀌었으면 지금 자리를 다시 찾는다.
                val now = ExpressionText.completing(binding.input.text, binding.input.selectionEnd) ?: return@setOnClickListener
                val token = ":${emoji.name}: "
                binding.input.text.replace(now.first, binding.input.selectionEnd, token)
                binding.input.setSelection(now.first + token.length)
                hub.expressions.markUsed(ExpressionKind.EMOJI, emoji)
            }
            binding.completions.addView(chip.root)
        }
        binding.completionScroll.scrollTo(0, 0)
    }

    /** 글 칸에 초점을 주고 키보드를 올린다. */
    fun focus() {
        binding.input.requestFocus()
        WindowCompat.getInsetsController(activity.window, binding.input).show(WindowInsetsCompat.Type.ime())
    }

    // ------------------------------------------------------------------ 파일 올리기

    /** 고른 파일들을 올리기 시작한다. [keep]은 올리는 동안 놓지 말아야 할 것(붙여넣은 묶음)이다. */
    fun addUris(uris: List<Uri>, keep: Any? = null) {
        val caps = hub.welcome.value?.capabilities
        if (caps != null && !caps.attachments) return toast(R.string.attach_unsupported)
        val maxFiles = caps?.maxMessageFiles ?: 10
        val maxBytes = caps?.maxUploadBytes ?: 31_457_280L
        for (uri in uris) {
            if (attachments.size >= maxFiles) {
                Toast.makeText(activity, activity.getString(R.string.send_too_many_files, maxFiles), Toast.LENGTH_SHORT).show()
                break
            }
            val (name, size) = describe(uri)
            if (size == 0L) {
                toast(R.string.attach_empty)
                continue
            }
            if (size > maxBytes) {
                Toast.makeText(activity, activity.getString(R.string.attach_too_large, name, Format.size(activity, maxBytes)), Toast.LENGTH_LONG).show()
                continue
            }
            val image = runCatching { activity.contentResolver.getType(uri) }.getOrNull()?.startsWith("image/") == true
            val attachment = Attachment(uri, name, size, image, keep)
            attachments += attachment
            upload(attachment)
        }
        render()
    }

    private fun upload(a: Attachment) {
        a.error = null
        a.percent = 0
        a.job = scope.launch {
            try {
                a.ref = hub.api.upload(
                    name = a.name,
                    size = a.size,
                    open = { openForUpload(a.uri) },
                    onProgress = { sent ->
                        if (a.size > 0) {
                            val p = (sent * 100 / a.size).toInt().coerceIn(0, 100)
                            if (p != a.percent) {
                                a.percent = p
                                binding.root.post { render() }
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                a.error = when {
                    e is ApiException && e.code == "too_large" -> activity.getString(R.string.attach_too_large_server)
                    e is ApiException -> e.message
                    else -> activity.getString(R.string.attach_upload_failed)
                }
            } finally {
                render()
            }
        }
    }

    /**
     * 올릴 파일을 연다. 읽을 권한이 사라진 주소(다른 앱이 공유한 뒤 거둔 경우 등)는 `SecurityException`을 던지는데,
     * 올리는 스레드에서 그대로 터지면 앱이 죽는다. 올리지 못한 것으로 다루도록 `IOException`으로 바꾼다.
     */
    private fun openForUpload(uri: Uri): java.io.InputStream = try {
        activity.contentResolver.openInputStream(uri)
    } catch (e: SecurityException) {
        throw java.io.IOException("파일을 읽을 권한이 없어요", e)
    } ?: throw java.io.IOException("파일을 열 수 없어요")

    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return (name ?: uri.lastPathSegment ?: "file") to size
    }

    private fun render() {
        binding.attachments.removeAllViews()
        binding.attachmentsScroll.isVisible = attachments.isNotEmpty()
        val inflater = LayoutInflater.from(activity)
        for (a in attachments) {
            val chip = ItemAttachmentChipBinding.inflate(inflater, binding.attachments, false)
            chip.name.text = a.name
            chip.progress.isVisible = a.uploading
            chip.icon.isVisible = !a.uploading
            if (a.image) {
                // 그림은 그림으로 알아본다. 아이콘 자리는 올리는 중(고리)이거나 올리지 못했을 때(주의)만 쓴다.
                chip.thumb.isVisible = true
                chip.thumb.load(a.uri) { crossfade(false) }
                chip.iconSlot.isVisible = a.uploading || a.error != null
                chip.root.updatePaddingRelative(start = activity.resources.getDimensionPixelSize(R.dimen.space_xs))
            } else {
                chip.icon.setImageResource(FileIcons.of(FileRef(id = "", name = a.name)))
            }
            chip.progress.setProgressCompat(a.percent, false)
            if (a.error != null) {
                chip.icon.setImageResource(R.drawable.ic_warning)
                ImageViewCompat.setImageTintList(chip.icon, android.content.res.ColorStateList.valueOf(activity.getColor(R.color.error)))
                chip.name.setTextColor(activity.getColor(R.color.error))
                chip.root.setOnClickListener { upload(a); render() }
                chip.root.contentDescription = activity.getString(R.string.attachment_retry, a.name)
            } else if (a.ref != null) {
                chip.icon.setImageResource(R.drawable.ic_check)
            }
            chip.remove.setOnClickListener {
                a.job?.cancel()
                attachments.remove(a)
                render()
            }
            binding.attachments.addView(chip.root)
        }
        updateSend()
    }

    // ------------------------------------------------------------------ 보내기

    /** 보내기 단추는 보낼 것이 있을 때만 있다. 생길 때 작게 튀어나오고, 없어지면 자리도 비운다. */
    private fun updateSend() {
        val hasContent = hasDraft
        val target = if (online) 1f else OFFLINE_ALPHA
        binding.send.isEnabled = !sending
        if (hasContent != hadDraft) {
            hadDraft = hasContent
            binding.send.animate().cancel()
            appearing = false
            binding.send.isVisible = hasContent
            if (hasContent && motion) {
                appearing = true
                binding.send.alpha = 0f
                binding.send.scaleX = SEND_FROM_SCALE
                binding.send.scaleY = SEND_FROM_SCALE
                binding.send.animate().alpha(target).scaleX(1f).scaleY(1f).setDuration(SEND_MS)
                    .withEndAction {
                        appearing = false
                        binding.send.alpha = if (online) 1f else OFFLINE_ALPHA
                    }
                    .start()
            }
            onDraftChanged()
        }
        if (!appearing) {
            binding.send.alpha = target
            binding.send.scaleX = 1f
            binding.send.scaleY = 1f
        }
    }

    private fun submit() {
        if (sending) return
        if (!online) return toast(R.string.send_offline)
        if (attachments.any { it.uploading }) return toast(R.string.attach_wait_upload)
        if (attachments.any { it.error != null }) return toast(R.string.attach_has_failed)
        val files = attachments.mapNotNull { it.ref }
        val text = binding.input.text.toString()
        sending = true
        updateSend()
        scope.launch {
            try {
                when (val result = onSend(text, files)) {
                    LucentHub.SendResult.Sent -> clear()
                    is LucentHub.SendResult.Rejected -> Toast.makeText(activity, result.reason, Toast.LENGTH_SHORT).show()
                }
            } finally {
                sending = false
                updateSend()
            }
        }
    }

    private fun clear() {
        binding.input.setText("")
        attachments.clear()
        render()
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val OFFLINE_ALPHA = 0.4f
        const val SEND_FROM_SCALE = 0.6f
        const val SEND_MS = 140L
        const val FOCUS_MS = 160
        const val COMPLETION_MAX = 12
        /** 글 칸이 글 말고 받는 것. 키보드는 이 목록을 보고 그림·GIF를 넣을 수 있는지 안다. */
        val RECEIVED_TYPES = arrayOf("image/*")
    }
}
