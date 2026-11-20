package dev.mooner.lucent.ui.chat

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.ItemSheetRowBinding
import dev.mooner.lucent.databinding.SheetAddBinding
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.protocol.CommandInfo
import dev.mooner.lucent.ui.common.CommandSheet
import dev.mooner.lucent.ui.common.SheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "더하기": 입력창의 + 단추가 여는 시트(StarLight의 `AddSheet`와 같은 짜임).
 *
 * 위에는 대화에 붙일 것(사진, 파일), 아래에는 루시아가 어떻게 일할지를 정하는 행이 있다. 이모지와 스티커는 여기가
 * 아니라 입력창의 이모지 단추에서 고른다([EmotePanel]). 행은 서버가
 * 알려 준 명령(`commands`, 지금은 권한 모드 하나)에서 만들므로 명령이 늘면 행도 늘어난다. 행을 누르면 값을 고르는 시트가
 * 열린다([CommandSheet]).
 */
object AddSheet {

    fun show(activity: AppCompatActivity, hub: LucentHub, onPhotos: () -> Unit, onFiles: () -> Unit) {
        val binding = SheetAddBinding.inflate(LayoutInflater.from(activity))
        val dialog = SheetDialog(activity)
        dialog.setContentView(binding.root)
        var watching: Job? = null
        dialog.closeWith(activity) { watching?.cancel() }
        binding.group.clipToOutline = true
        binding.head.sheetTitle.setText(R.string.add_title)
        binding.head.sheetClose.setOnClickListener { dialog.dismiss() }

        // 파일은 연결돼 있고 서버가 받을 때만 올릴 수 있다. 안 되면 타일이 흐려지고 이유가 아래에 보인다.
        val online = hub.link.value is LinkState.Online
        val caps = hub.welcome.value?.capabilities
        val blocked = when {
            !online -> R.string.add_attach_offline
            caps != null && !caps.attachments -> R.string.attach_unsupported
            else -> null
        }
        for (tile in listOf(binding.photos, binding.files)) {
            tile.isEnabled = blocked == null
            tile.alpha = if (blocked == null) 1f else DISABLED_ALPHA
        }
        binding.attachNote.isVisible = blocked != null
        blocked?.let(binding.attachNote::setText)

        fun pick(view: View, action: () -> Unit) = view.setOnClickListener {
            dialog.dismiss()
            action()
        }
        pick(binding.photos, onPhotos)
        pick(binding.files, onFiles)

        fun renderCommands(commands: List<CommandInfo>) {
            binding.group.removeAllViews()
            binding.group.isVisible = commands.isNotEmpty()
            val inflater = LayoutInflater.from(activity)
            commands.forEachIndexed { index, command ->
                if (index > 0) inflater.inflate(R.layout.view_sheet_divider, binding.group, true)
                val row = ItemSheetRowBinding.inflate(inflater, binding.group, true)
                row.rowTitle.text = CommandSheet.title(activity, command)
                row.rowIcon.setImageResource(CommandSheet.icon(command))
                row.rowValue.text = CommandSheet.value(activity, hub, command)
                row.root.setOnClickListener {
                    CommandSheet.show(activity, hub, command) { if (dialog.isShowing) renderCommands(hub.commandList.value) }
                }
            }
        }

        // 아는 것부터 보이고, 연결돼 있으면 목록과 지금 값을 새로 읽는다. 값이 바뀌거나(고르는 시트에서, 다른 곳에서)
        // 주 모델을 바꾸는 일이 진행되면 행의 값도 따라 바뀐다.
        watching = activity.lifecycleScope.launch {
            combine(hub.commandList, hub.commandTexts, hub.modelSwitch) { commands, _, _ -> commands }.collect(::renderCommands)
        }
        dialog.show()
        val welcome = hub.welcome.value
        if (online && welcome != null && welcome.canCall("commands") && welcome.canCall("command")) {
            activity.lifecycleScope.launch {
                try {
                    val commands = hub.commands()
                    if (dialog.isShowing) renderCommands(commands)
                    // 지금 값을 알려 주지 않는 예전 데몬이면 명령마다 물어 답의 글에서 읽는다.
                    for (command in commands.filter { it.current == null }) {
                        hub.command(command.name, null)
                        if (dialog.isShowing) renderCommands(commands)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 읽지 못하면 아는 것만 보인 채 둔다. 행을 누르면 그 시트가 이유를 말한다.
                }
            }
        }
    }

    private const val DISABLED_ALPHA = 0.4f
}
