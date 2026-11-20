package dev.mooner.lucent.ui.common

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.SheetReplyAskBinding
import dev.mooner.lucent.tools.ReplyAsk

/**
 * 루시아가 알림에 답장하기 전에 묻는 시트(`notifications.reply`, 설정의 "답장 전에 묻기").
 *
 * 물음은 허브가 쥔다([LucentHub.replyAsk]). 화면마다 이 클래스 하나를 두고, 화면이 앞에 있는 동안 [watch]를 부른다. 물음이
 * 끝나면(답했거나 늦었다) 시트를 닫는다. 사용자가 시트를 내리거나 닫으면 "보내지 않기"다. 화면을 돌리거나 다른 화면으로
 * 넘어가며 시트가 닫히는 것은 답이 아니다(물음은 남고, 앞에 온 화면이 다시 보인다).
 */
class ReplyAskSheet(private val activity: AppCompatActivity, private val hub: LucentHub) {

    private var shown: ReplyAsk? = null
    private var dialog: SheetDialog? = null

    /** 물음을 지켜본다. `repeatOnLifecycle(STARTED)` 안에서 부른다. */
    suspend fun watch() {
        hub.replyAsk.collect { ask ->
            if (ask != shown) close()
            if (ask != null && dialog == null) open(ask)
        }
    }

    private fun open(ask: ReplyAsk) {
        val binding = SheetReplyAskBinding.inflate(LayoutInflater.from(activity))
        val sheet = SheetDialog(activity)
        var answered = false
        fun answer(send: Boolean) {
            if (answered) return
            answered = true
            hub.answerReply(ask, send)
        }
        sheet.setContentView(binding.root)
        sheet.closeWith(activity) {
            // 사용자가 내렸다(화면이 앞에 있다). 화면이 사라지며 닫힌 것은 답이 아니다.
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && dialog === sheet) answer(false)
            if (dialog === sheet) {
                dialog = null
                shown = null
            }
        }
        binding.head.sheetTitle.setText(R.string.reply_ask_title)
        binding.head.sheetClose.setOnClickListener { sheet.dismiss() }
        binding.message.text = activity.getString(R.string.reply_ask_message, ask.app, ask.to)
        binding.quote.text = ask.text
        binding.send.setOnClickListener {
            answer(true)
            sheet.dismiss()
        }
        binding.skip.setOnClickListener {
            answer(false)
            sheet.dismiss()
        }
        shown = ask
        dialog = sheet
        sheet.show()
    }

    /** 물음이 끝났다. 답하지 않고 닫는다. */
    private fun close() {
        val sheet = dialog ?: return
        dialog = null
        shown = null
        sheet.dismiss()
    }
}
