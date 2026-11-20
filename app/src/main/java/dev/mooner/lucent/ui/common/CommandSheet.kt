package dev.mooner.lucent.ui.common

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.LucentHub.ModelSwitch
import dev.mooner.lucent.R
import dev.mooner.lucent.data.Commands
import dev.mooner.lucent.data.CompactStep
import dev.mooner.lucent.databinding.ItemSheetOptionBinding
import dev.mooner.lucent.databinding.SheetOptionsBinding
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.protocol.CommandInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 명령 하나의 값을 고르는 시트(사양 6.3·6.4: 권한 모드, 주 모델, 그 밖에 데몬이 더하는 것). "더하기" 시트와 설정 화면이
 * 같은 시트를 쓴다.
 *
 * - 지금 값은 `commands`의 `current`다([Commands.current]). 열 때마다, 바꾼 뒤마다 목록을 다시 읽는다(다른 곳에서도
 *   바뀌고, 모르는 값을 보내도 오류 없이 안내 글이 오기 때문이다). 표시는 다시 읽은 값으로만 옮긴다.
 * - 선택지를 누르면 바로 바꾼다. 이 토큰이 바꿀 수 없거나 연결이 없으면 선택지는 보기만 하고, 그 이유를 아래에 적는다.
 * - **주 모델**은 누른 뒤에 한 번 더 묻는다(이 데몬이 대화 정리를 기다려 줄 때): 바꾸기 전에 대화를 정리할지. 정리는
 *   20~75초쯤 걸리므로 그동안 선택지 자리에 기다리는 모습이 놓인다. 이 일은 화면이 아니라 허브가 쥐고 있어서
 *   ([LucentHub.switchModel]) 시트를 닫아도 이어지고, 다시 열면 그 모습이 다시 보인다.
 */
object CommandSheet {

    /** 사람이 읽는 이름. 서버의 명령 이름은 짧은 낱말("권한", "모델")이라 화면에서는 풀어 쓴다. 모르는 명령은 이름 그대로. */
    fun title(activity: AppCompatActivity, command: CommandInfo): String = when (command.name) {
        Commands.MODE -> activity.getString(R.string.settings_mode)
        Commands.MODEL -> activity.getString(R.string.settings_model)
        else -> command.name
    }

    /** 시트를 여는 행 앞의 아이콘. 권한은 방패, 모델은 별, 모르는 명령은 정보 표시다. */
    fun icon(command: CommandInfo): Int = when (command.name) {
        Commands.MODE -> R.drawable.ic_shield
        Commands.MODEL -> R.drawable.ic_sparkle
        else -> R.drawable.ic_info
    }

    /**
     * 시트를 여는 행("더하기" 시트, 설정)의 오른쪽에 적는 지금 값. 주 모델을 바꾸는 중이면 그렇다고 적는다(시트를 닫아도
     * 그 일은 이어지므로, 행을 다시 누르면 기다리는 모습으로 돌아간다).
     */
    fun value(activity: AppCompatActivity, hub: LucentHub, command: CommandInfo): String {
        val switch = hub.modelSwitch.value
        if (command.name == Commands.MODEL) when (switch) {
            // 정리하지 못해 물음이 기다리고 있다. 행을 누르면 그 물음이 뜬다.
            is ModelSwitch.Asking -> return activity.getString(R.string.model_row_asking)
            is ModelSwitch.Compacting, is ModelSwitch.Switching -> return activity.getString(R.string.model_row_busy)
            else -> Unit
        }
        val text = hub.commandTexts.value[command.name]
        val current = Commands.current(command, text)
        return when {
            current != null -> Commands.optionLabel(command, current)
            command.current == null && text == null -> activity.getString(R.string.mode_loading)
            else -> activity.getString(R.string.command_see)
        }
    }

    fun show(activity: AppCompatActivity, hub: LucentHub, initial: CommandInfo, onChanged: () -> Unit = {}) {
        val binding = SheetOptionsBinding.inflate(LayoutInflater.from(activity))
        val dialog = SheetDialog(activity)
        dialog.setContentView(binding.root)
        binding.options.clipToOutline = true
        binding.head.sheetTitle.text = title(activity, initial)
        binding.head.sheetClose.setOnClickListener { dialog.dismiss() }
        val markdown = Markdown(activity)
        val isModel = initial.name == Commands.MODEL

        /** 바꾸는 중인 선택지(주 모델이 아닌 명령). */
        var busy: String? = null
        var failure: String? = null
        /** 주 모델: 누른 뒤 정리할지 묻는 중인 선택지. */
        var confirming: String? = null
        /** 주 모델: 방금 끝난 일의 글(정리의 결과와 바꾼 결과). */
        var result: String? = null

        // 다시 그리는 함수는 아래에서 정한다(선택지를 누르는 쪽이 먼저 정의되어 서로를 부른다).
        var renderAll: () -> Unit = {}

        fun command(): CommandInfo = hub.commandList.value.firstOrNull { it.name == initial.name } ?: initial

        fun label(option: String) = Commands.optionLabel(command(), option)

        /** 목록을 다시 읽어 표시를 옮긴다. 읽지 못해도 조용히 둔다(아는 값이 보인 채다). */
        suspend fun refresh() {
            try {
                hub.commands()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        fun change(option: String) {
            failure = null
            result = null
            if (isModel) {
                // 정리를 기다려 주는 데몬이면 한 번 더 묻고, 아니면 바로 바꾼다.
                if (hub.canCompact) confirming = option else hub.switchModel(option, compactFirst = false)
                return
            }
            busy = option
            activity.lifecycleScope.launch {
                try {
                    hub.command(initial.name, option)
                    refresh()
                    if (Commands.applied(command(), option) == false) failure = activity.getString(R.string.command_not_applied)
                    onChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: activity.getString(R.string.command_change_failed)
                }
                busy = null
                if (dialog.isShowing) renderAll()
            }
        }

        fun renderList(command: CommandInfo, online: Boolean) {
            val text = hub.commandTexts.value[command.name]
            val current = Commands.current(command, text)
            val changeable = command.canChange && online

            binding.message.isVisible = true
            val shown = result ?: text
            if (shown == null) binding.message.setText(R.string.mode_loading)
            else markdown.render(binding.message, Plain.text(shown))

            binding.options.isVisible = true
            binding.options.removeAllViews()
            command.options.forEachIndexed { index, option ->
                if (index > 0) LayoutInflater.from(activity).inflate(R.layout.view_sheet_divider, binding.options, true)
                val row = ItemSheetOptionBinding.inflate(LayoutInflater.from(activity), binding.options, true)
                row.optionTitle.text = label(option)
                // 바꾸는 중인 줄에는 표시 자리에 고리가 돈다.
                row.optionCheck.isInvisible = option != current
                row.optionBusy.isVisible = busy == option
                if (busy == option) row.optionCheck.isVisible = false
                val enabled = changeable && busy == null && option != current
                row.root.isEnabled = enabled
                row.root.isClickable = enabled
                row.root.alpha = if (changeable || option == current) 1f else LOCKED_ALPHA
                if (enabled) row.root.setOnClickListener { change(option); renderAll() }
            }

            val note = when {
                failure != null -> failure
                !online -> activity.getString(R.string.command_offline)
                !command.canChange -> activity.getString(R.string.mode_locked_value)
                command.name == Commands.MODE -> activity.getString(R.string.mode_confirm_message)
                command.name == Commands.MODEL -> activity.getString(R.string.model_note)
                else -> null
            }
            binding.note.isVisible = note != null
            binding.note.text = note
            binding.note.setTextColor(activity.getColor(if (failure != null) R.color.error else R.color.ink_faint))
        }

        /** 선택지 자리에 놓이는 한 가지 일. 단추의 글이 null이면 그 단추는 없다. */
        fun renderPanel(
            title: String, body: String, waiting: Boolean,
            primary: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>?, cancel: Pair<String, () -> Unit>?,
            note: String? = null,
        ) {
            binding.message.isVisible = false
            binding.options.isVisible = false
            binding.panel.isVisible = true
            binding.panelRing.isVisible = waiting
            binding.panelTitle.text = title
            binding.panelBody.text = body
            for ((view, action) in listOf(binding.panelPrimary to primary, binding.panelSecondary to secondary, binding.panelCancel to cancel)) {
                view.isVisible = action != null
                view.text = action?.first
                view.setOnClickListener { action?.second?.invoke() }
            }
            binding.note.isVisible = note != null
            binding.note.text = note
            binding.note.setTextColor(activity.getColor(R.color.ink_faint))
        }

        renderAll = render@{
            val command = command()
            val online = hub.link.value is LinkState.Online
            val switch = if (isModel) hub.modelSwitch.value else null
            binding.panel.isVisible = false
            when {
                switch is ModelSwitch.Done -> {
                    // 끝났다: 서버의 글을 보이고 선택지로 돌아간다. 표시는 다시 읽은 지금 값이 정한다.
                    result = switch.text
                    failure = if (switch.changed == false) activity.getString(R.string.command_not_applied) else null
                    hub.clearModelSwitch()
                    onChanged()
                    renderList(command, online)
                }
                switch is ModelSwitch.Compacting -> renderPanel(
                    title = activity.getString(R.string.model_compacting_title),
                    body = activity.getString(
                        when (switch.step) {
                            CompactStep.RETRY_LATER -> R.string.model_compacting_busy
                            CompactStep.WAIT_ONLINE -> R.string.model_compacting_offline
                            else -> R.string.model_compacting_body
                        },
                        label(switch.option),
                    ),
                    waiting = true, primary = null, secondary = null,
                    cancel = activity.getString(R.string.model_stop_waiting) to { hub.cancelModelSwitch() },
                    note = activity.getString(R.string.model_stop_waiting_note),
                )
                switch is ModelSwitch.Switching -> renderPanel(
                    title = activity.getString(R.string.model_switching_title), body = label(switch.option),
                    waiting = true, primary = null, secondary = null, cancel = null,
                )
                switch is ModelSwitch.Asking -> renderPanel(
                    title = activity.getString(R.string.model_ask_title),
                    body = activity.getString(R.string.model_ask_body, switch.reason, label(switch.option)),
                    waiting = false,
                    primary = activity.getString(R.string.model_switch_anyway) to { hub.confirmModelSwitch(anyway = true) },
                    secondary = activity.getString(R.string.model_give_up) to { hub.confirmModelSwitch(anyway = false) },
                    cancel = null,
                )
                confirming != null -> {
                    val option = confirming!!
                    // 더 큰 모델로 갈 때만 정리를 앞세운다. 정리는 지금의 모델이 하므로 작은 모델로 갈 때는 얻는 것이 적다.
                    val recommend = Commands.compactFirstByDefault(Commands.current(command, null), option)
                    val compact = activity.getString(R.string.model_compact_then_switch) to {
                        confirming = null
                        hub.switchModel(option, compactFirst = true)
                    }
                    val now = activity.getString(R.string.model_switch_now) to {
                        confirming = null
                        hub.switchModel(option, compactFirst = false)
                    }
                    renderPanel(
                        title = activity.getString(R.string.model_confirm_title, label(option)),
                        body = activity.getString(if (recommend) R.string.model_confirm_body else R.string.model_confirm_body_smaller),
                        waiting = false,
                        primary = if (recommend) compact else now,
                        secondary = if (recommend) now else compact,
                        cancel = activity.getString(R.string.cancel) to { confirming = null; renderAll() },
                        note = activity.getString(R.string.model_note),
                    )
                }
                else -> renderList(command, online)
            }
        }

        // 목록, 답의 글, 연결, 진행 중인 일이 바뀔 때마다 다시 그린다.
        val watching = activity.lifecycleScope.launch {
            combine(hub.commandList, hub.commandTexts, hub.link, hub.modelSwitch) { _, _, _, _ -> }.collect { renderAll() }
        }
        // 화면이 사라질 때 닫힘 알림이 오지 않더라도 "보고 있다"는 표시가 남지 않게 한다(남으면 결과를 알리지 않게 된다).
        val unwatch = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY && isModel) hub.modelSwitchWatched = false }
        if (isModel) {
            hub.modelSwitchWatched = true
            activity.lifecycle.addObserver(unwatch)
        }
        dialog.closeWith(activity) {
            watching.cancel()
            activity.lifecycle.removeObserver(unwatch)
            if (isModel) hub.modelSwitchWatched = false
        }
        dialog.show()
        renderAll()

        // 열 때마다 지금 값을 다시 묻는다(다른 곳에서 바뀌었을 수 있다). 답의 글은 지금 상태의 설명으로 위에 보인다.
        if (hub.link.value is LinkState.Online) activity.lifecycleScope.launch {
            refresh()
            try {
                hub.command(initial.name, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (hub.commandTexts.value[initial.name] == null) failure = e.message ?: activity.getString(R.string.command_load_failed)
            }
            if (dialog.isShowing) renderAll()
        }
    }

    const val MODE_COMMAND = Commands.MODE
    private const val LOCKED_ALPHA = 0.5f
}
