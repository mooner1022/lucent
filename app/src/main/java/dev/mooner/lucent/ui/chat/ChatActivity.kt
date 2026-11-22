package dev.mooner.lucent.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.mooner.lucent.BuildConfig
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.data.ExpressionKind
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.data.Face
import dev.mooner.lucent.data.Route
import dev.mooner.lucent.data.StoredItem
import dev.mooner.lucent.databinding.ActivityChatBinding
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.protocol.CardButton
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.Handover
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.tools.ReplyAsk
import dev.mooner.lucent.ui.common.EmojiSpans
import dev.mooner.lucent.ui.common.FaceViewer
import dev.mooner.lucent.ui.common.FileActions
import dev.mooner.lucent.ui.common.FilePreview
import dev.mooner.lucent.ui.common.FormDialog
import dev.mooner.lucent.ui.common.Format
import dev.mooner.lucent.ui.common.Markdown
import dev.mooner.lucent.ui.common.Motion
import dev.mooner.lucent.ui.common.Peeks
import dev.mooner.lucent.ui.common.Plain
import dev.mooner.lucent.ui.common.PromptDialog
import dev.mooner.lucent.ui.common.ReplyAskSheet
import dev.mooner.lucent.ui.common.SheetDialog
import dev.mooner.lucent.ui.common.TextSheet
import dev.mooner.lucent.ui.common.enableAppEdgeToEdge
import dev.mooner.lucent.ui.common.sideInset
import dev.mooner.lucent.ui.handover.HandoverActivity
import dev.mooner.lucent.ui.settings.SettingsActivity
import dev.mooner.lucent.ui.setup.SetupActivity
import dev.mooner.lucent.ui.sky.Mood
import dev.mooner.lucent.ui.sky.Moon
import dev.mooner.lucent.ui.sky.SkyPhase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.max

/**
 * 루시아와 나누는 대화(앱의 주 화면). Discord와 같은 루시아, 같은 기억이다(사양 1장).
 *
 * 화면은 저장소와 허브의 상태를 보이기만 한다. 상황에 따라 바뀌는 곳은 모두 [ChatScene] 하나에서 나온다
 * (docs/design.md 7장): 연결·쓰는 중·진행 안내·승인 대기·쓰던 글·스크롤 위치·시각을 넣으면 머리말의 하늘과 고리와 상태 줄,
 * 입력창 위의 상황 자리, 입력창의 안내 글이 정해진다. 같은 장면이면 다시 그리지 않는다.
 */
class ChatActivity : AppCompatActivity(), ChatActions {

    private lateinit var binding: ActivityChatBinding
    private val hub by lazy { LucentHub.get(this) }

    /** 루시아가 알림에 답장하기 전의 물음(휴대폰 기능, 사양 7.2). */
    private val replyAsk by lazy { ReplyAskSheet(this, hub) }
    private lateinit var adapter: ChatAdapter
    private lateinit var composer: Composer

    private var firstLoad = true
    private var loadingOlder = false
    private var anchor: Anchor? = null

    /** 오래된 줄을 더 부르기 전에 보던 자리: 맨 위에 보이던 줄의 열쇠와 위쪽 좌표, 그때 목록에서의 차례. */
    private class Anchor(val key: String, val top: Int, val index: Int)
    private var lastKey: String? = null
    private var pendingSave: FileRef? = null
    private var preview: SheetDialog? = null
    private var pendingCards: List<Row.CardRow> = emptyList()
    private var lastItemAt: Long? = null

    /** 지금 그려 둔 장면과 하늘. */
    private var scene: ChatScene? = null
    private var phase: SkyPhase? = null
    private var suggestedFor: SkyPhase? = null
    private var imeShown = false
    /** 목록의 끝을 보고 있었다. 입력창 쪽이 커져 목록이 줄어들 때 끝을 놓치지 않게 한다. */
    private var pinnedToEnd = true
    /** 사람이 손으로 굴리는 중이다(놓은 뒤 미끄러지는 동안도). 앱이 끝으로 데려가는 움직임과 가른다. */
    private var byHand = false
    /** 올려 보는 사이 새 말이 왔고 아직 끝으로 가지 않았다. */
    private var unseen = false
    /** 끝에서 위로 올린 거리(화소). 끝에 닿으면 0으로 돌아간다. */
    private var scrolledUp = 0

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) composer.addUris(uris)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) composer.addUris(uris)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) saveTo(file, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDebugNight()
        enableAppEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (!hub.settings.isConfigured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileActions.trim(this)
        // 저장할 곳을 고르는 동안 화면이 다시 만들어졌어도(회전, 메모리 회수) 무엇을 저장하려 했는지 잊지 않는다.
        pendingSave = savedInstanceState?.getString(KEY_PENDING_SAVE)?.let {
            runCatching { LucentJson.decodeFromString(FileRef.serializer(), it) }.getOrNull()
        }

        applyInsets()
        setUpList()
        setUpComposer()
        setUpHeader()
        updateSky(animate = false)
        observe()
        handleShare(intent)
        debugReplyAsk(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSave?.let { outState.putString(KEY_PENDING_SAVE, LucentJson.encodeToString(FileRef.serializer(), it)) }
    }

    // 대화 화면이 정말 앞에 있는 동안만 받은 것을 봤다고 알린다(사양 12.6). 그 위에 다른 화면(설정, 넘겨받기, 크게 보기)이
    // 덮이면 멈춘다. 처음 설정으로 넘어간 경우(binding 전)에도 허브에는 알려도 된다(볼 대화가 없다).
    override fun onResume() {
        super.onResume()
        hub.chatShown(true)
    }

    override fun onPause() {
        hub.chatShown(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        debugReplyAsk(intent)
    }

    // ------------------------------------------------------------------ 틀

    /**
     * 머리말은 상태 표시줄 아래로, 입력창은 키보드·내비게이션 막대 위로 올린다. 키보드가 올라오면 머리말이 이름 한 줄로 줄고,
     * 넓은 화면에서는 본문 폭을 묶는다. 목록은 머리말 뒤까지 이어지므로 위 여백을 머리말 높이에 맞춘다.
     */
    private fun applyInsets() {
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        val headerStart = resources.getDimensionPixelSize(R.dimen.space_m)
        val headerEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
        val small = resources.getDimensionPixelSize(R.dimen.space_s)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboard != imeShown) {
                imeShown = keyboard
                // 키보드와 이모지 판은 같은 자리를 쓴다. 판 안에서 찾는 중일 때만 둘이 함께 떠 있다(격자가 낮아진다).
                if (::emotePanel.isInitialized) {
                    if (keyboard && !emotePanel.isSearching) showEmotes(false)
                    binding.root.post { emotePanel.onKeyboard(keyboard) }
                }
                renderSoon()
            }
            val side = sideInset(binding.root.width - bars.left - bars.right, resources)

            binding.root.updatePadding(left = bars.left, right = bars.right)
            binding.headerContent.updatePadding(left = headerStart + side, top = bars.top, right = headerEnd + side)
            binding.sky.contentTop = bars.top
            binding.sky.contentSide = side
            binding.messages.updatePadding(left = side, right = side)
            binding.bottomArea.updatePadding(left = gutter + side, right = gutter + side, bottom = max(ime.bottom, bars.bottom) + small)
            binding.statusRow.isVisible = !imeShown
            binding.emptyAvatar.isVisible = !imeShown
            insets
        }
        // 화면 폭이 바뀌면(회전, 접기) 좌우 묶음을 다시 잰다.
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) ViewCompat.requestApplyInsets(binding.root)
        }
        // 배치 도중에 다시 배치를 청하면 버려질 수 있으므로 다음 차례로 미룬다.
        binding.header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) binding.messages.post { binding.messages.updatePadding(top = bottom - top + small) }
        }
        // 목록의 위 여백은 머리말 높이에 틈을 더한 것이다. 말풍선은 그 틈이 아니라 머리말의 아랫선에서 스러진다.
        binding.messages.topGap = small
    }

    private fun setUpList() {
        emojiSpans = EmojiSpans(this, hub.expressions)
        markdown = Markdown(this, emojiSpans)
        adapter = ChatAdapter(lifecycleScope, markdown, this, { hub.welcome.value?.conversation }, hub.expressions, emojiSpans)
        val layout = LinearLayoutManager(this)
        binding.messages.layoutManager = layout
        binding.messages.adapter = adapter
        binding.messages.itemAnimator = null
        binding.messages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                when (state) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> byHand = true
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        byHand = false
                        // 멈춘 자리가 끝인지 다시 잰다(카드로 데려간 움직임은 끝이 아닌 데서 멈춘다).
                        pinnedToEnd = isNearBottom()
                        renderSoon()
                    }
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 배치가 끝날 때도 (0, 0)으로 불린다. 끝을 보고 있었는지는 사람이 실제로 움직였을 때만 다시 잰다.
                // 앱이 끝으로 데려가는 도중(아직 끝이 아니다)에는 "끝을 보는 중"이 그대로다.
                if (byHand && (dx != 0 || dy != 0)) pinnedToEnd = isNearBottom()
                // 끝에서 얼마나 올라와 있는지는 움직인 만큼을 더해 잰다(목록이 어림하는 값은 줄의 높이가 들쭉날쭉하면 맞지 않는다).
                scrolledUp = (scrolledUp - dy).coerceAtLeast(0)
                // 끝에 닿으면 그사이 온 말은 본 것이다. 단추를 감추는 일은 장면이 한다(배치 도중에 바로 바꾸지 않는다).
                if (isNearBottom()) {
                    unseen = false
                    scrolledUp = 0
                }
                if (dy < 0 && layout.findFirstVisibleItemPosition() <= LOAD_OLDER_AT) loadOlder()
                renderSoon()
            }
        })
        // 키보드나 상황 자리 때문에 목록이 줄어들어도, 끝을 보고 있었다면 끝에 머문다.
        binding.messages.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // 손으로 굴리는 중에는 끝으로 되돌리지 않는다(대기 카드가 화면을 벗어나 상황 자리가 생기는 순간 목록이 줄어드는데,
            // 그때 끝으로 데려가면 그 카드에서 벗어날 수가 없다).
            if (bottom - top < oldBottom - oldTop && pinnedToEnd && !byHand) binding.messages.post { scrollToBottom(smooth = false) }
        }
        binding.jump.setOnClickListener {
            unseen = false
            scrollToBottom(smooth = true)
            renderSoon()
        }
        binding.dock.setOnClickListener { showPending() }
        binding.linkAction.setOnClickListener { hub.gateway.wake(byUser = true) }
    }

    private lateinit var emotePanel: ExpressionPanel
    private lateinit var emojiSpans: EmojiSpans
    private lateinit var markdown: Markdown

    /** 판이 열려 있으면 뒤로 가기는 판부터 닫는다. */
    private val closeEmotesOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showEmotes(false)
    }

    private fun setUpComposer() {
        emotePanel = ExpressionPanel(binding.emotePanel, window, hub.expressions, ::onExpressionPicked)
        onBackPressedDispatcher.addCallback(this, closeEmotesOnBack)
        composer = Composer(
            binding = binding.composer,
            activity = this,
            scope = lifecycleScope,
            hub = hub,
            onAdd = ::showAddSheet,
            onEmotes = ::toggleEmotes,
            onSend = { text, files -> hub.sendMessage(text, files).also { if (it == LucentHub.SendResult.Sent) scrollToBottom(true) } },
            onDraftChanged = ::render,
            // 글 칸을 다시 잡으면 판은 물러난다(키보드가 그 자리를 쓴다).
            onInputFocused = { if (emotePanel.isShown) showEmotes(false) },
        )
    }

    /** + 단추: 대화에 붙일 것(사진, 파일)과 루시아가 일하는 방식(권한 모드, 주 모델)을 한 시트에서 고른다. */
    private fun showAddSheet() {
        AddSheet.show(
            this, hub,
            onPhotos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFiles = { pickFiles.launch(arrayOf("*/*")) },
        )
    }

    /**
     * 이모지 단추: 키보드 자리에 이모지·스티커 판을 연다. 열려 있을 때 누르면 키보드로 돌아간다.
     * 판과 키보드는 함께 떠 있지 않는다: 판을 열면 키보드를 내리고, 키보드가 올라오면 판을 닫는다. 판 안에서 찾는
     * 동안만 둘이 함께 떠 있다.
     */
    private fun toggleEmotes() {
        if (emotePanel.isShown) {
            showEmotes(false)
            composer.focus()
        } else {
            WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
            showEmotes(true)
        }
    }

    private fun showEmotes(show: Boolean) {
        if (show == emotePanel.isShown) return
        if (show) emotePanel.show() else emotePanel.hide()
        composer.setEmotesOpen(show)
        closeEmotesOnBack.isEnabled = show
        renderSoon()
    }

    /**
     * 이모지는 쓰던 글에 `:name:`으로 끼워 넣고, 스티커는 그 한 장을 바로 보낸다(`message.sticker`). 쓰던 글은 그대로 둔다
     * (app-expressions.md 7장).
     */
    private fun onExpressionPicked(kind: ExpressionKind, expression: Expression) {
        if (kind == ExpressionKind.EMOJI) {
            composer.insert(":${expression.name}:")
            hub.expressions.markUsed(ExpressionKind.EMOJI, expression)
            return
        }
        lifecycleScope.launch {
            when (val result = hub.sendSticker(expression)) {
                LucentHub.SendResult.Sent -> scrollToBottom(true)
                is LucentHub.SendResult.Rejected -> Toast.makeText(this@ChatActivity, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setUpHeader() {
        binding.avatar.setOnClickListener { onFace() }
        binding.emptyAvatar.setOnClickListener { onFace() }
        binding.more.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, MENU_REFRESH, 0, R.string.menu_refresh_history)
                // 토큰에 범위가 있고 브라우저를 켠 데몬일 때만 보인다(6.7).
                if (Handover.available(hub.welcome.value)) menu.add(0, MENU_HANDOVER, 1, R.string.menu_handover)
                menu.add(0, MENU_SETTINGS, 2, R.string.menu_settings)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        MENU_REFRESH -> refreshHistory()
                        MENU_HANDOVER -> startActivity(Intent(this@ChatActivity, HandoverActivity::class.java))
                        MENU_SETTINGS -> startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
                    }
                    true
                }
                show()
            }
        }
        binding.authAction.setOnClickListener { openSetup() }
        binding.sky.onBandChanged = { applyBand() }
        binding.glow.background = glow
        binding.sky.onLookChanged = ::paintGlow
        applyBand()
    }

    // ------------------------------------------------------------------ 관찰

    @OptIn(FlowPreview::class)
    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(hub.store.items, hub.typing, hub.pressing, hub.welcome, hub.activity) { items, typing, pressing, welcome, activity ->
                        lastItemAt = items.lastOrNull()?.at
                        buildRows(
                            items, typing, pressing.keys, Format.zone(), welcome?.conversation,
                            working = activity != null,
                            forms = welcome?.capabilities?.forms == true,
                        )
                    }.collect(::showRows)
                }
                launch { combine(hub.link, hub.typing, hub.activity) { _, _, _ -> }.collect { render() } }
                launch { hub.link.collect { if (it is LinkState.Online) askNotificationPermissionOnce() } }
                // 이모지·스티커를 다루는 데몬일 때만 고르는 단추가 있다(`welcome.expressions`가 없으면 숨긴다).
                launch {
                    hub.welcome.collect { welcome ->
                        val available = welcome?.expressions != null
                        composer.setEmotesAvailable(available)
                        if (!available) showEmotes(false)
                    }
                }
                // 목록이 바뀌거나 그림이 도착하면, 이미 그려 둔 글과 스티커를 다시 그린다.
                launch { hub.expressions.revision.drop(1).debounce(EXPRESSIONS_SETTLE_MS).collect { onExpressionsChanged() } }
                launch { hub.notices.collect { Peeks.show(this@ChatActivity, it) } }
                launch { replyAsk.watch() }
                // 루시아가 정한 프로필 그림. 없으면 앱의 기본 모습이다. 얼굴을 누르면 크게 본다(onFace).
                launch { hub.profile.face.collect(::showFace) }
                // 사용자가 관리 페이지에서 정한 보이는 이름(결정 0064). 없으면 앱의 기본 이름이다.
                launch { hub.profile.name.collect { showName() } }
                // 시각이 정하는 것: 하늘의 장면, 다시 연결까지 남은 시간, "한동안 조용했다"는 판단.
                launch {
                    while (isActive) {
                        updateSky(animate = true)
                        render()
                        (scene?.slot as? ChatScene.Slot.Reconnect)?.let(::showReconnect)
                        delay(TICK_MS)
                    }
                }
            }
        }
    }

    private fun showRows(rows: List<Row>) {
        pendingCards = rows.filterIsInstance<Row.CardRow>().filter { it.item.card?.isPending == true }

        // 끝을 보던 중이면(끝으로 가는 도중이거나, 방금 실린 그림이 끝을 밀어냈어도) 새 말을 따라간다.
        val atBottom = pinnedToEnd || isNearBottom()
        // 보던 자리는 위에 줄이 실제로 붙은 때에만 되돌린다. 기다리는 사이 다른 까닭(입력 중 표시 등)으로 목록이 바뀐
        // 것에 써 버리면, 정작 오래된 줄이 붙을 때 화면이 밀린다.
        val savedAnchor = anchor?.takeIf { a -> rows.indexOfFirst { it.key == a.key } > a.index }
        val newestKey = rows.lastOrNull { it !is Row.Typing }?.key
        val grew = newestKey != lastKey && lastKey != null
        lastKey = newestKey
        // 끝을 보고 있을 때 온 새 말은 살짝 떠오르며 들어온다.
        if (grew && atBottom) adapter.arriving = newestKey

        adapter.submitList(rows) {
            when {
                firstLoad && rows.isNotEmpty() -> {
                    firstLoad = false
                    scrollToBottom(smooth = false)
                }
                savedAnchor != null -> {
                    // 위에 오래된 줄이 붙어도 보던 자리가 그대로 있게 한다.
                    val index = adapter.currentList.indexOfFirst { it.key == savedAnchor.key }
                    if (index >= 0) (binding.messages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, savedAnchor.top)
                    anchor = null
                }
                atBottom -> scrollToBottom(smooth = true)
                grew -> unseen = true
            }
            binding.messages.post { render() }
        }
    }

    // ------------------------------------------------------------------ 장면

    private val renderLater = Runnable { render() }

    /** 보이는 이름: 사용자가 정한 이름, 없으면 앱의 기본 이름. */
    private fun displayName(): String = hub.profile.name.value ?: getString(R.string.assistant_name)

    /** 이름이 보이는 자리(머리말, 입력창 안내, "쓰는 중"을 읽어 주는 글)를 맞춘다. */
    private fun showName() {
        binding.title.text = displayName()
        adapter.name = displayName()
        scene?.let { composer.setContext(it.canSend, hintOf(it.hint)) }
    }

    private fun hintOf(hint: ChatScene.Hint): String = when (hint) {
        ChatScene.Hint.OFFLINE -> getString(R.string.composer_hint_offline)
        ChatScene.Hint.PENDING -> getString(R.string.composer_hint_pending)
        ChatScene.Hint.NORMAL -> getString(R.string.composer_hint, displayName())
    }

    /** 루시아의 얼굴이 보이는 자리 셋(머리말, 대화 줄, 빈 화면의 큰 초상)을 같은 그림으로 맞춘다. */
    private fun showFace(face: Face?) {
        binding.avatar.setFace(face?.small)
        adapter.face = face?.small
        if (face != null) binding.emptyAvatar.setImageBitmap(face.large)
        else binding.emptyAvatar.setImageResource(R.drawable.ic_portrait_placeholder)
    }

    /**
     * 이모지·스티커의 목록이나 그림이 바뀌었다. 대화는 목록보다 먼저 그려질 수 있으므로(처음 연결, 새 스티커 등록),
     * 풀어 둔 글을 버리고 보이는 줄을 다시 그린다.
     */
    private fun onExpressionsChanged() {
        emojiSpans.clear()
        markdown.invalidate()
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        // 글이던 자리가 그림이 되면 줄의 높이가 달라진다. 끝을 보고 있었다면 끝에 머문다.
        if (pinnedToEnd) binding.messages.post { scrollToBottom(smooth = false) }
        emotePanel.refresh()
        composer.updateCompletions()
    }

    /**
     * 목록이 스크롤을 알리는 때는 배치 도중일 수 있다. 그때 보이기·숨기기를 바꾸면 다시 배치하라는 요청이 버려져
     * 자리만 차지하고 그려지지 않는 뷰가 생기므로, 장면은 배치가 끝난 뒤에 다시 정한다.
     */
    private fun renderSoon() {
        binding.root.removeCallbacks(renderLater)
        binding.root.post(renderLater)
    }

    /** 지금 상태로 장면을 다시 정하고, 달라진 것만 그린다. */
    private fun render() {
        if (!::composer.isInitialized) return
        val first = pendingCards.firstOrNull()
        val card = first?.item?.card
        val next = ChatScene.of(
            ChatScene.Input(
                link = hub.link.value,
                typing = hub.typing.value,
                // 상태 줄에는 그림을 그리지 않는다. 이모지 표기는 빼고 보인다.
                activity = hub.activity.value?.let(Plain::text)?.let { text ->
                    ExpressionText.without(text) { hub.expressions.current.value.emoji(it) != null }
                },
                pendingCount = pendingCards.size,
                pendingTitle = Plain.text(card?.title?.ifBlank { card.headline }.orEmpty()),
                pendingOnScreen = first != null && isOnScreen(first.key),
                hasDraft = composer.hasDraft,
                atBottom = isNearBottom(),
                keyboard = imeShown || emotePanel.isShown,
                lastItemAt = lastItemAt,
                now = System.currentTimeMillis(),
                farFromEnd = isFarFromEnd(),
                unseen = unseen,
            ),
        )
        val previous = scene
        val empty = adapter.itemCount == 0
        if (next == previous && binding.empty.isVisible == empty) return
        scene = next

        if (next.mood != previous?.mood) showMood(next.mood)
        if (next.status != previous?.status) showStatus(next.status)
        if (next.header != previous?.header) showHeader(next.header, animate = previous != null)
        if (next.slot != previous?.slot) showSlot(next.slot, previous?.slot)
        if (next.blockedCode != previous?.blockedCode) showBlocked(next.blockedCode)
        if (next.jump != previous?.jump) showJump(next.jump)
        composer.setContext(next.canSend, hintOf(next.hint))
        showEmpty(empty, next.canSend)
    }

    /** 하늘의 장면은 지금 시각이, 달은 오늘의 위상이 정한다. */
    private fun updateSky(animate: Boolean) {
        val now = Calendar.getInstance()
        val minute = debugMinute() ?: (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
        val next = SkyPhase.at(minute)
        binding.sky.moonPhase = debugMoon() ?: Moon.phase(now.timeInMillis)
        if (next == phase) return
        phase = next
        binding.sky.show(next, scene?.mood ?: Mood.CONNECTING, animate)
        applyBand()
        // 권하는 말과 인사는 시간대를 따른다.
        if (scene?.slot == ChatScene.Slot.Suggest) fillSuggestions(next)
        if (scene?.header == ChatScene.Header.GREETING) binding.greeting.setText(greetingNow())
    }

    /**
     * 조용하다 돌아왔으면 머리말이 커지며 시간대 인사를 건네고, 대화가 시작되면 이름 한 줄로 돌아간다.
     * 머리말이 커지면 달도 더 많이 드러난다.
     */
    private fun showHeader(header: ChatScene.Header, animate: Boolean) {
        val greet = header == ChatScene.Header.GREETING
        if (greet) binding.greeting.setText(greetingNow())
        if (binding.greeting.isVisible == greet) return
        if (animate && Motion.enabled(this) && binding.header.isLaidOut) {
            val change = TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(ChangeBounds())
                .addTransition(Fade())
                .setDuration(HEADER_MS)
            TransitionManager.beginDelayedTransition(binding.header, change)
        }
        binding.greeting.isVisible = greet
    }

    private fun greetingNow(): Int {
        val hour = debugMinute()?.div(MINUTES_PER_HOUR) ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> R.string.empty_morning
            in 11..16 -> R.string.empty_day
            in 17..21 -> R.string.empty_evening
            else -> R.string.empty_night
        }
    }

    private val glow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT))

    /**
     * 머리말의 하늘빛을 그 아래 종이에 옅게 번지게 한다. 대화를 내려 읽는 동안에도 지금이 어느 때인지 느껴지게 하려는 것이다.
     * 어두운 띠의 빛은 밝은 종이 위에서 조금만 비쳐도 짙으므로 더 옅게 둔다.
     */
    private fun paintGlow(bottom: Int) {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val strength = when {
            night -> GLOW_NIGHT_THEME
            binding.sky.isBandDark -> GLOW_DARK_BAND
            else -> GLOW_LIGHT_BAND
        }
        val tint = ColorUtils.setAlphaComponent(bottom, (255 * strength).toInt())
        glow.colors = intArrayOf(tint, ColorUtils.setAlphaComponent(bottom, 0))
    }

    private fun showMood(mood: Mood) {
        binding.sky.show(phase ?: SkyPhase.NIGHT, mood)
        applyBand()
    }

    /** 머리말의 글자와 상태 표시줄 아이콘은 테마가 아니라 지금 띠의 밝기를 따른다. */
    private fun applyBand() {
        val dark = binding.sky.isBandDark
        binding.title.setTextColor(getColor(if (dark) R.color.band_white else R.color.band_ink))
        binding.subtitle.setTextColor(getColor(if (dark) R.color.band_white_muted else R.color.band_ink_muted))
        binding.greeting.setTextColor(getColor(if (dark) R.color.band_white else R.color.band_ink))
        ImageViewCompat.setImageTintList(binding.more, ColorStateList.valueOf(getColor(if (dark) R.color.band_white else R.color.band_ink)))
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = !dark
        binding.avatar.show(scene?.mood ?: Mood.CONNECTING, dark)
        scene?.status?.let(::tintStatus)
    }

    private fun showStatus(status: ChatScene.Status) {
        val text = when (status) {
            is ChatScene.Status.Online ->
                getString(R.string.status_online, getString(if (status.route == Route.HOME) R.string.route_home else R.string.route_public))
            is ChatScene.Status.Working -> status.note
            ChatScene.Status.Typing -> getString(R.string.status_typing)
            is ChatScene.Status.Pending -> getString(R.string.status_pending, status.count)
            ChatScene.Status.Connecting -> getString(R.string.status_connecting)
            is ChatScene.Status.Offline -> getString(R.string.status_offline)
            is ChatScene.Status.Throttled -> getString(R.string.status_throttled)
            is ChatScene.Status.Blocked -> getString(R.string.status_blocked)
        }
        val busy = status is ChatScene.Status.Working || status == ChatScene.Status.Typing
        binding.statusIcon.setImageResource(if (busy) R.drawable.ic_sparkle_small else R.drawable.bg_status_dot)
        tintStatus(status)
        if (binding.subtitle.text.toString() == text) return
        binding.subtitle.text = text
        if (Motion.enabled(this) && binding.statusRow.isLaidOut) {
            binding.statusRow.animate().cancel()
            binding.statusRow.alpha = 0f
            binding.statusRow.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun tintStatus(status: ChatScene.Status) {
        val dark = binding.sky.isBandDark
        val color = when (status) {
            is ChatScene.Status.Online, is ChatScene.Status.Working, ChatScene.Status.Typing ->
                if (dark) R.color.band_sky_on_dark else R.color.band_sky_on_light
            is ChatScene.Status.Pending -> if (dark) R.color.band_gold_on_dark else R.color.band_gold_on_light
            is ChatScene.Status.Blocked -> if (dark) R.color.band_error_on_dark else R.color.band_error_on_light
            else -> if (dark) R.color.band_white_muted else R.color.band_ink_muted
        }
        ImageViewCompat.setImageTintList(binding.statusIcon, ColorStateList.valueOf(getColor(color)))
    }

    // ------------------------------------------------------------------ 상황 자리

    /** 입력창 위의 한 칸. 지금 필요한 것 하나로 바뀌고, 필요한 것이 없으면 자리째 사라진다. */
    private fun showSlot(slot: ChatScene.Slot, previous: ChatScene.Slot?) {
        // 같은 종류 안에서 글만 바뀌는 경우(남은 시간, 건수)는 움직이지 않는다.
        val sameKind = previous != null && previous::class == slot::class
        if (!sameKind && Motion.enabled(this) && binding.bottomArea.isLaidOut)
            TransitionManager.beginDelayedTransition(binding.bottomArea, AutoTransition().setDuration(SLOT_MS))

        binding.slot.isVisible = slot != ChatScene.Slot.None
        binding.linkBar.isVisible = slot is ChatScene.Slot.Reconnect
        binding.dock.isVisible = slot is ChatScene.Slot.Approval
        binding.suggestScroll.isVisible = slot == ChatScene.Slot.Suggest
        when (slot) {
            is ChatScene.Slot.Reconnect -> showReconnect(slot)
            is ChatScene.Slot.Approval -> {
                binding.dockTitle.text = getString(R.string.dock_title, slot.count)
                binding.dockSub.text = slot.title
                binding.dockSub.isVisible = slot.title.isNotBlank()
            }
            ChatScene.Slot.Suggest -> fillSuggestions(phase ?: SkyPhase.DAY)
            ChatScene.Slot.None -> Unit
        }
    }

    private fun showReconnect(slot: ChatScene.Slot.Reconnect) {
        val seconds = Format.secondsUntil(slot.retryAt)
        binding.linkText.text = when {
            slot.throttled -> getString(R.string.slot_throttled_in, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
            seconds > 0 -> getString(R.string.slot_reconnect_in, seconds)
            else -> getString(R.string.slot_reconnecting)
        }
    }

    /** 시간대에 맞춰 권하는 말. 누르면 입력창에 담기고(바로 보내지 않는다) 키보드가 올라온다. */
    private fun fillSuggestions(phase: SkyPhase) {
        if (suggestedFor == phase && binding.suggestions.isNotEmpty()) return
        suggestedFor = phase
        val texts = resources.getStringArray(
            when (phase) {
                SkyPhase.DAWN -> R.array.suggest_dawn
                SkyPhase.DAY -> R.array.suggest_day
                SkyPhase.DUSK -> R.array.suggest_dusk
                SkyPhase.NIGHT -> R.array.suggest_night
            },
        )
        binding.suggestions.removeAllViews()
        binding.suggestScroll.scrollTo(0, 0)
        for (text in texts) {
            val chip = layoutInflater.inflate(R.layout.item_suggestion, binding.suggestions, false) as TextView
            chip.text = text
            chip.setOnClickListener {
                composer.setText(text)
                composer.focus()
            }
            binding.suggestions.addView(chip)
        }
    }

    /**
     * 목록의 끝으로 가는 단추. 끝에서 멀리 올라와 있으면 화살표만, 그사이 새 말이 왔으면 "새 메시지"라고 적힌다.
     */
    private fun showJump(jump: ChatScene.Jump) {
        binding.jump.isVisible = jump != ChatScene.Jump.NONE
        if (jump == ChatScene.Jump.NONE) return
        val labelled = jump == ChatScene.Jump.NEW
        binding.jump.text = if (labelled) getString(R.string.chat_jump_latest) else ""
        binding.jump.contentDescription = getString(if (labelled) R.string.chat_jump_latest else R.string.chat_jump_end)
        // 글자가 없을 때는 화살표가 네모난 단추의 가운데에 오게 한다.
        val arrow = binding.jump.compoundDrawablesRelative[0]?.intrinsicWidth ?: 0
        val square = (resources.getDimensionPixelSize(R.dimen.chip_height) - arrow) / 2
        binding.jump.compoundDrawablePadding = if (labelled) resources.getDimensionPixelSize(R.dimen.space_s) else 0
        binding.jump.setPaddingRelative(
            if (labelled) resources.getDimensionPixelSize(R.dimen.space_m) else square, 0,
            if (labelled) resources.getDimensionPixelSize(R.dimen.space_l) else square, 0,
        )
    }

    /** 토큰이 문제면 입력창 자리에 "다시 입력"이 나온다. */
    private fun showBlocked(code: String?) {
        binding.authBar.isVisible = code != null
        binding.composer.root.isVisible = code == null
        if (code != null) showEmotes(false)
        if (code == null) return
        binding.authText.setText(
            when (code) {
                ErrorCodes.UNAUTHORIZED -> R.string.banner_unauthorized
                ErrorCodes.UNSUPPORTED_ROLE -> R.string.banner_unsupported_role
                else -> R.string.banner_no_address
            },
        )
    }

    /** 빈 화면: 큰 초상과 한 줄. 인사는 머리말이 한다. */
    private fun showEmpty(empty: Boolean, online: Boolean) {
        binding.empty.isVisible = empty
        if (!empty) return
        binding.emptyBody.text =
            if (online) getString(R.string.empty_body)
            else getString(R.string.empty_offline_title) + "\n" + getString(R.string.empty_offline_body)
    }

    /** 그 줄이 화면에 온전히 보이는가. 이미 보고 있는 카드를 가리키는 띠는 군더더기다. */
    private fun isOnScreen(key: String): Boolean {
        val lm = binding.messages.layoutManager as? LinearLayoutManager ?: return false
        val position = adapter.currentList.indexOfFirst { it.key == key }
        if (position < 0) return false
        val view = lm.findViewByPosition(position) ?: return false
        // 목록은 머리말 뒤까지 이어지므로, 머리말에 가린 부분은 보이는 것이 아니다.
        return view.top >= binding.messages.paddingTop - view.paddingTop && view.bottom <= binding.messages.height
    }

    private fun showPending() {
        val first = pendingCards.firstOrNull() ?: return
        val position = adapter.currentList.indexOfFirst { it.key == first.key }
        if (position >= 0) binding.messages.smoothScrollToPosition(position)
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true))
    }

    /** 개발 빌드에서만: 하늘의 시각과 달의 위상을 인텐트로 정해 볼 수 있다(`--ei sky_minute 1290`, `--ef moon 0.2`). */
    private fun debugMinute(): Int? =
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_SKY_MINUTE)) intent.getIntExtra(EXTRA_SKY_MINUTE, 0) else null

    /**
     * 개발 빌드에서만: 어두운·밝은 테마를 이 앱 안에서만 강제한다(`--ez night true`). 기기의 테마 설정은 건드리지 않는다.
     */
    private fun applyDebugNight() {
        if (!BuildConfig.DEBUG || !intent.hasExtra(EXTRA_NIGHT)) return
        AppCompatDelegate.setDefaultNightMode(
            if (intent.getBooleanExtra(EXTRA_NIGHT, false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
        // 창(배경, 시스템 막대)이 만들어지기 전에 바뀐 테마를 자원에 반영한다.
        delegate.applyDayNight()
    }

    /**
     * 개발 빌드에서만: 답장 물음 시트를 띄워 본다(`--es dev_reply_ask "보낼 글"`). 알림 접근 없이 시트의 모양과 답을 볼 때 쓴다.
     * 답은 로그로만 남고 아무것도 보내지 않는다.
     */
    private fun debugReplyAsk(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val text = intent.getStringExtra(EXTRA_DEV_REPLY_ASK) ?: return
        intent.removeExtra(EXTRA_DEV_REPLY_ASK)
        hub.debugAskReply(ReplyAsk("카카오톡", "민수", text))
    }

    private fun debugMoon(): Float? =
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_MOON)) intent.getFloatExtra(EXTRA_MOON, 0.5f) else null

    // ------------------------------------------------------------------ 스크롤

    /**
     * 대화의 끝을 보고 있는가: 마지막 줄이 화면에 있고, 그 줄의 끝이 화면 아래로 조금만(화면의 4분의 1) 넘어가 있다.
     * 마지막 줄이 화면에 걸쳐 있기만 한 것으로는 모자라다(파일이 여럿 붙은 긴 줄은 그 위쪽만 보고 있을 수 있다).
     */
    private fun isNearBottom(): Boolean {
        val lm = binding.messages.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter.itemCount
        if (count == 0) return true
        val last = lm.findViewByPosition(count - 1) ?: return false
        val list = binding.messages
        return last.bottom <= list.height - list.paddingBottom + list.height / NEAR_END_PART
    }

    /** 끝에서 한 화면 넘게 올라와 있는가. */
    private fun isFarFromEnd(): Boolean = scrolledUp > binding.messages.height

    private fun scrollToBottom(smooth: Boolean) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        pinnedToEnd = true
        if (!smooth) scrolledUp = 0
        if (smooth) binding.messages.smoothScrollToPosition(last) else binding.messages.scrollToPosition(last)
    }

    /** 맨 위에 닿으면 더 오래된 줄을 보인다. 저장된 것이 먼저, 없으면 기록을 서버에서 더 받는다. */
    private fun loadOlder() {
        if (loadingOlder) return
        val lm = binding.messages.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val row = adapter.currentList.getOrNull(first) ?: return
        // scrollToPositionWithOffset의 오프셋은 위 여백(머리말 높이) 뒤부터 잰다.
        val top = (lm.findViewByPosition(first)?.top ?: binding.messages.paddingTop) - binding.messages.paddingTop
        loadingOlder = true
        lifecycleScope.launch {
            anchor = Anchor(row.key, top, first)
            val more = hub.loadOlder()
            if (!more) anchor = null
            loadingOlder = false
        }
    }

    private fun refreshHistory() {
        lifecycleScope.launch {
            hub.refreshHistory().fold(
                onSuccess = { Toast.makeText(this@ChatActivity, getString(R.string.history_refreshed, it), Toast.LENGTH_SHORT).show() },
                onFailure = { Toast.makeText(this@ChatActivity, R.string.history_refresh_failed, Toast.LENGTH_SHORT).show() },
            )
        }
    }

    // ------------------------------------------------------------------ ChatActions

    override fun onPress(ref: String, button: CardButton) {
        val prompt = button.prompt
        val form = button.form
        when {
            // 여러 칸을 받는 버튼(로그인 정보 등). 그릴 수 있는 것만 여기까지 온다(Forms.buttons).
            form != null -> FormDialog.show(this, form) { inputs -> hub.press(ref, button.action, inputs = inputs) }
            prompt != null -> PromptDialog.show(this, prompt) { input -> hub.press(ref, button.action, input) }
            else -> hub.press(ref, button.action)
        }
    }

    override fun onResend(item: StoredItem) {
        lifecycleScope.launch {
            val result = hub.resend(item)
            if (result is LucentHub.SendResult.Rejected) Toast.makeText(this@ChatActivity, result.reason, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDiscard(item: StoredItem) {
        lifecycleScope.launch { hub.discard(item) }
    }

    /**
     * 앱이 보일 수 있는 파일은 시트에 미리 보이고, 그 밖은 받아서 다른 앱에 넘긴다. 받는 동안은 어느 쪽이든 같은 시트가
     * 떠서 얼마나 받았는지 보이고, 닫으면 받기를 그만둔다([FilePreview]).
     */
    override fun onOpenFile(file: FileRef) {
        if (!available(file)) return
        // 잇달아 두 번 눌러도 시트는 하나만 뜬다.
        if (preview?.isShowing == true) return
        preview = FilePreview.show(this, file, ::onSaveFile)
    }

    /**
     * 기록에 보인 파일 가운데 보관 기간이 지나 지워졌거나 이 기기에서 받을 수 없는 것(다른 앱이 짧게 둔 파일, 범위가 없는
     * Discord DM의 파일, 사양 6.2)은 받으러 가지 않고 까닭을 알린다.
     */
    private fun available(file: FileRef): Boolean {
        if (file.available) return true
        Toast.makeText(this, if (file.gone) R.string.file_gone else R.string.file_unreachable, Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onSaveFile(file: FileRef) {
        if (!available(file)) return
        pendingSave = file
        createDocument.launch(file.name)
    }

    private fun saveTo(file: FileRef, uri: android.net.Uri) {
        lifecycleScope.launch {
            val local = fetchWithToast(file) ?: return@launch
            runCatching { FileActions.copyTo(this@ChatActivity, local, uri) }
                .onSuccess { Toast.makeText(this@ChatActivity, R.string.file_saved, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@ChatActivity, R.string.file_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private suspend fun fetchWithToast(file: FileRef): java.io.File? {
        Toast.makeText(this, getString(R.string.file_downloading, file.name), Toast.LENGTH_SHORT).show()
        return try {
            FileActions.fetch(this, file)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(this, FileActions.describeFailure(this, e), Toast.LENGTH_LONG).show()
            null
        }
    }

    /** 말을 길게 누르면 그 말로 할 수 있는 일이 곁에 뜬다: 통째로 복사하거나, 일부만 고를 수 있게 펼쳐 보인다. */
    override fun onMessageMenu(anchor: android.view.View, item: StoredItem) {
        if (item.body.isBlank()) return
        val mine = item.sender == dev.mooner.lucent.data.Sender.USER
        PopupMenu(this, anchor, if (mine) android.view.Gravity.END else android.view.Gravity.START).apply {
            menu.add(0, MENU_COPY, 0, R.string.message_copy)
            menu.add(0, MENU_SELECT, 1, R.string.message_select)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_COPY -> copy(item.body)
                    MENU_SELECT -> TextSheet.show(this@ChatActivity, getString(R.string.message_select)) { view ->
                        // 대화에 보이던 모양 그대로 펼친다. 고른 글은 글자로 복사된다(이모지는 `:이름:`).
                        if (mine) view.text = emojiSpans.plain(item.body) else markdown.render(view, item.body)
                    }
                }
                true
            }
            show()
        }
    }

    override fun onFace() = FaceViewer.open(this, hub.profile.face.value, displayName())

    override fun onRowResized() {
        if (pinnedToEnd && !byHand) binding.messages.post { scrollToBottom(smooth = false) }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        // 안드로이드 13부터는 시스템이 복사했다고 알린다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 그 밖

    /** 다른 앱에서 공유한 글·파일을 입력창에 담는다. */
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        // 쓰던 글이 있으면 덮어쓰지 않고 그 뒤에 잇는다.
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(composer::appendText)
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let(::add)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
        }
        if (uris.isNotEmpty()) composer.addUris(uris)
        composer.focus()
        // 같은 공유를 회전 등으로 다시 처리하지 않게 한다.
        intent.action = Intent.ACTION_MAIN
    }

    /** 처음 연결됐을 때 한 번만 알림 권한을 묻는다(안드로이드 13 이상). */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hub.settings.askedNotificationPermission) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        hub.settings.askedNotificationPermission = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val MAX_PICK = 10
        const val LOAD_OLDER_AT = 3
        const val NEAR_END_PART = 4
        const val MENU_REFRESH = 1
        const val MENU_SETTINGS = 2
        const val MENU_HANDOVER = 3
        const val MENU_COPY = 3
        const val MENU_SELECT = 4
        const val TICK_MS = 1_000L
        /** 그림이 잇달아 도착할 때 한 번만 다시 그리도록 모으는 시간. */
        const val EXPRESSIONS_SETTLE_MS = 150L
        const val FADE_MS = 180L
        const val SLOT_MS = 160L
        const val SECONDS_PER_MINUTE = 60
        const val MINUTES_PER_HOUR = 60
        const val EXTRA_SKY_MINUTE = "sky_minute"
        const val EXTRA_MOON = "moon"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_DEV_REPLY_ASK = "dev_reply_ask"
        const val HEADER_MS = 240L
        const val GLOW_LIGHT_BAND = 0.6f
        const val GLOW_DARK_BAND = 0.13f
        const val GLOW_NIGHT_THEME = 0.55f
        const val KEY_PENDING_SAVE = "pending_save"
    }
}
