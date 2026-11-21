package dev.mooner.lucent.ui.handover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.ActivityHandoverBinding
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.Handover
import dev.mooner.lucent.protocol.HandoverInput
import dev.mooner.lucent.protocol.HandoverState
import dev.mooner.lucent.protocol.HandoverTab
import dev.mooner.lucent.ui.common.Plain
import dev.mooner.lucent.ui.common.enableAppEdgeToEdge
import dev.mooner.lucent.ui.common.sideInset
import dev.mooner.lucent.ui.sky.PageSky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.max

/**
 * 브라우저 화면 넘겨받기(사양 6.7·8.9, 결정 0063). 로그인 도중 사이트가 사람 확인(캡차 등)을 띄웠을 때, 데몬의 브라우저
 * 화면을 보고 눌러서 푼다. 토큰에 `browser_handover` 범위가 있을 때만 들어올 수 있다([Handover.available]).
 *
 * - 화면이 앞에 있는 동안만 넘겨받는다. 앞에 오면 시작하고(`start`), 떠나면 끝낸다(`end`). 넘겨받는 동안 루시아는
 *   브라우저를 쓰지 못하므로, 끝내는 일은 허브가 쥐고 답을 받을 때까지 다시 해 본다([LucentHub.endHandover]).
 * - 화면은 1초쯤마다 한 장씩, 앞의 것을 다 받은 뒤에 받는다. 입력을 보낸 뒤에는 0.4초 뒤에 바로 다시 받는다.
 * - 화면에는 사용자의 계정 정보가 보일 수 있다. 그림은 메모리에만 두고, 창에 보안 표시를 걸어 화면 캡처와 최근 앱
 *   화면에 잡히지 않게 한다. 넣는 글(비밀번호일 수 있다)은 보내는 즉시 칸에서 지우고 어디에도 남기지 않는다.
 * - 데몬이 먼저 끝냈으면(최대 15분, `not_active`) 화면을 닫는다.
 */
class HandoverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandoverBinding
    private lateinit var sky: PageSky
    private val hub by lazy { LucentHub.get(this) }

    private var session: Job? = null
    private var tabs: List<HandoverTab> = emptyList()
    private var tab: String? = null
    /** 넘겨받기가 끝나는 때([SystemClock.elapsedRealtime] 기준). */
    private var endsAt: Long? = null
    /** 입력을 보냈거나 탭을 바꿨다: 기다리지 않고 화면을 다시 받는다. */
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableAppEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityHandoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        sky = PageSky(this, binding.pageHeader)
        sky.refresh(animate = false)

        binding.pageHeader.pageTitle.setText(R.string.handover_title)
        binding.pageHeader.back.setOnClickListener { finish() }
        binding.done.setOnClickListener { finish() }
        binding.retry.setOnClickListener { begin() }
        binding.tabs.setOnClickListener { chooseTab() }
        binding.frame.onTap = { x, y -> send(HandoverInput.Click(x, y)) }
        binding.send.setOnClickListener { sendText() }
        binding.text.setOnEditorActionListener { _, actionId, event ->
            // 화면 키보드의 보내기와, 붙여 쓰는 키보드의 Enter(누를 때 한 번).
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            when {
                actionId == EditorInfo.IME_ACTION_SEND || (enter && event.action == KeyEvent.ACTION_DOWN) -> { sendText(); true }
                else -> enter
            }
        }
        fillKeys()
    }

    override fun onStart() {
        super.onStart()
        sky.refresh()
        begin()
    }

    override fun onStop() {
        super.onStop()
        session?.cancel()
        session = null
        binding.frame.show(null)
        binding.text.text?.clear()
        // 화면을 돌리는 것은 떠나는 것이 아니다. 새로 만들어진 화면이 이어서 쓴다.
        if (!isChangingConfigurations) hub.endHandover()
    }

    private fun applyInsets() {
        val content = binding.pageHeader.headerContent
        val headerStart = content.paddingStart
        val headerEnd = content.paddingEnd
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        val below = resources.getDimensionPixelSize(R.dimen.space_s)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val side = sideInset(root.width - bars.left - bars.right, resources)
            root.updatePadding(left = bars.left, right = bars.right)
            content.updatePadding(left = headerStart + side, top = bars.top, right = headerEnd + side)
            binding.pageHeader.sky.contentTop = bars.top
            binding.pageHeader.sky.contentSide = side
            binding.controls.updatePadding(left = gutter + side, right = gutter + side, bottom = below + max(ime.bottom, bars.bottom))
            insets
        }
    }

    // ------------------------------------------------------------------ 넘겨받기

    private fun begin() {
        session?.cancel()
        notice(getString(R.string.handover_starting), retry = false)
        session = lifecycleScope.launch {
            val state = try {
                hub.startHandover()
            } catch (e: LucentHub.CallFailed) {
                notice(e.message ?: getString(R.string.handover_failed), retry = true)
                return@launch
            }
            apply(state)
            if (!state.active || tab == null) {
                notice(getString(R.string.handover_no_tab), retry = true)
                return@launch
            }
            launch { frames() }
            launch { states() }
            launch { clock() }
        }
    }

    /** 화면 받기. 앞의 것을 다 받은 뒤에 다음을 받는다. */
    private suspend fun frames() {
        while (lifecycleScope.isActive) {
            val id = tab
            if (id != null) {
                try {
                    val bytes = hub.api.handoverFrame(id, MAX_FRAME_BYTES)
                    val bitmap = withContext(Dispatchers.Default) { decode(bytes) }
                    if (bitmap != null && id == tab) {
                        binding.frame.show(bitmap)
                        binding.notice.isVisible = false
                    }
                } catch (e: ApiException) {
                    if (e.code == ErrorCodes.NOT_ACTIVE) return ended()
                    // 없는 탭(닫혔다)이면 탭 목록을 다시 본다. 그 밖은 다음 차례에 다시 해 본다.
                    if (e.status == 404) runCatching { apply(hub.handoverState()) }
                } catch (_: IOException) {
                    // 잠깐 닿지 못했다. 보이던 화면은 그대로 두고 다음 차례에 다시 받는다.
                }
            }
            withTimeoutOrNull(FRAME_EVERY_MS) { refresh.receive() }
        }
    }

    /** 새 탭이 생겼는지, 얼마나 남았는지 몇 초마다 본다. */
    private suspend fun states() {
        while (lifecycleScope.isActive) {
            delay(STATE_EVERY_MS)
            try {
                val state = hub.handoverState()
                if (!state.active) return ended()
                apply(state)
            } catch (e: LucentHub.CallFailed) {
                if (e.code == ErrorCodes.NOT_ACTIVE) return ended()
            }
        }
    }

    private suspend fun clock() {
        while (lifecycleScope.isActive) {
            // 끝나는 때를 모르면(-1) 비워 둔다.
            val left: Int = endsAt?.let { ((it - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0).toInt() } ?: -1
            binding.left.text = when {
                left < 0 -> ""
                left >= 60 -> getString(R.string.handover_left_minutes, left / 60, left % 60)
                else -> getString(R.string.handover_left_seconds, left)
            }
            delay(1_000)
        }
    }

    private fun apply(state: HandoverState) {
        tabs = state.tabs.filter { it.id.isNotEmpty() }
        if (tabs.none { it.id == tab }) {
            tab = tabs.firstOrNull()?.id
            binding.frame.show(null)
        }
        endsAt = state.leftSeconds?.let { SystemClock.elapsedRealtime() + it * 1000L }
        binding.tabs.isVisible = tabs.size > 1
        binding.tabs.text = getString(R.string.handover_tabs, tabs.size)
    }

    /** 새 창(본인인증 팝업 등)은 탭으로 남는다. 둘 이상이면 골라 본다. */
    private fun chooseTab() {
        PopupMenu(this, binding.tabs).apply {
            for (index in tabs.indices) {
                val number: Int = index + 1
                val name = Plain.text(tabs[index].title).ifBlank { tabs[index].url }
                menu.add(0, index, index, if (name.isBlank()) getString(R.string.handover_tab_unnamed, number) else name)
            }
            setOnMenuItemClickListener { item ->
                tabs.getOrNull(item.itemId)?.let {
                    if (it.id != tab) {
                        tab = it.id
                        binding.frame.show(null)
                        refresh.trySend(Unit)
                    }
                }
                true
            }
            show()
        }
    }

    /** 데몬이 넘겨받기를 끝냈다(시간이 다 됐거나 다른 곳에서 끝냈다). */
    private fun ended() {
        hub.handoverEnded()
        Toast.makeText(this, R.string.handover_ended, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun notice(text: String, retry: Boolean) {
        binding.noticeText.text = text
        binding.retry.isVisible = retry
        binding.notice.isVisible = true
    }

    // ------------------------------------------------------------------ 입력

    private fun send(input: HandoverInput) {
        val id = tab ?: return
        if (session?.isActive != true) return
        lifecycleScope.launch {
            try {
                hub.handoverInput(id, input)
                // 화면이 바뀔 틈을 준 뒤 다시 받는다(6.7).
                delay(AFTER_INPUT_MS)
                refresh.trySend(Unit)
            } catch (e: LucentHub.CallFailed) {
                if (e.code == ErrorCodes.NOT_ACTIVE) ended()
                else Toast.makeText(this@HandoverActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 초점이 있는 칸에 글을 넣는다. 글은 다듬지 않고, 보내는 즉시 칸에서 지운다. */
    private fun sendText() {
        val text = binding.text.text?.toString().orEmpty()
        if (text.isEmpty()) return
        if (text.codePointCount(0, text.length) > Handover.MAX_TEXT_CHARS) {
            Toast.makeText(this, getString(R.string.prompt_too_long, Handover.MAX_TEXT_CHARS), Toast.LENGTH_SHORT).show()
            return
        }
        binding.text.text?.clear()
        send(HandoverInput.Text(text))
    }

    private fun fillKeys() {
        val inflater = LayoutInflater.from(this)
        val keys = listOf(
            R.string.handover_scroll_up to HandoverInput.Scroll(-SCROLL_STEP),
            R.string.handover_scroll_down to HandoverInput.Scroll(SCROLL_STEP),
            R.string.handover_key_enter to HandoverInput.Key("enter"),
            R.string.handover_key_tab to HandoverInput.Key("tab"),
            R.string.handover_key_backspace to HandoverInput.Key("backspace"),
            R.string.handover_key_escape to HandoverInput.Key("escape"),
            R.string.handover_key_up to HandoverInput.Key("up"),
            R.string.handover_key_down to HandoverInput.Key("down"),
        )
        for ((label, input) in keys) {
            val chip = inflater.inflate(R.layout.item_handover_key, binding.keys, false) as TextView
            chip.setText(label)
            chip.setOnClickListener { send(input) }
            binding.keys.addView(chip)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_FRAME_SIDE || bounds.outHeight !in 1..MAX_FRAME_SIDE) return null
        // 줄여 풀지 않는다: 그림의 화소 좌표가 곧 누르는 좌표다.
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private companion object {
        const val FRAME_EVERY_MS = 1_000L
        const val AFTER_INPUT_MS = 400L
        const val STATE_EVERY_MS = 5_000L
        const val SCROLL_STEP = 400
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val MAX_FRAME_SIDE = 4096
    }
}
