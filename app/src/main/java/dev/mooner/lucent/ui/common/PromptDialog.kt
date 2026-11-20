package dev.mooner.lucent.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.DialogPromptBinding
import dev.mooner.lucent.protocol.CardPrompt

/**
 * 값을 받는 버튼(`prompt`가 있는 버튼, 인증 코드 등)을 눌렀을 때의 입력창(사양 9.1).
 *
 * 입력값은 저장하거나 로그에 남기지 않는다(13장). 화면 캡처에도 잡히지 않게 창에 보안 표시를 건다.
 * 길이는 서버가 알린 범위([CardPrompt.minLen], [CardPrompt.maxLen])를 앱이 먼저 확인한다.
 */
object PromptDialog {

    fun show(context: Context, prompt: CardPrompt, onSubmit: (String) -> Unit) {
        val binding = DialogPromptBinding.inflate(LayoutInflater.from(context))
        val max = prompt.maxLen.coerceAtLeast(1)
        val min = prompt.minLen.coerceIn(0, max)
        binding.inputLayout.hint = prompt.label.ifBlank { context.getString(R.string.prompt_default_label) }
        binding.inputLayout.counterMaxLength = max
        prompt.placeholder?.let { binding.inputLayout.placeholderText = it }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle(prompt.title.ifBlank { context.getString(R.string.prompt_default_title) })
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()

        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        fun submit() {
            val value = binding.input.text.toString().trim()
            val length = value.codePointCount(0, value.length)
            when {
                length < min -> binding.inputLayout.error = context.getString(R.string.prompt_too_short, min)
                length > max -> binding.inputLayout.error = context.getString(R.string.prompt_too_long, max)
                else -> {
                    dialog.dismiss()
                    onSubmit(value)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            binding.input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        binding.input.doAfterTextChanged { binding.inputLayout.error = null }
        dialog.show()
    }
}
