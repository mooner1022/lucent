package dev.mooner.lucent.ui.common

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import dev.mooner.lucent.R
import dev.mooner.lucent.databinding.DialogFormBinding
import dev.mooner.lucent.databinding.ItemFormCheckBinding
import dev.mooner.lucent.databinding.ItemFormTextBinding
import dev.mooner.lucent.protocol.CardForm
import dev.mooner.lucent.protocol.FormField
import dev.mooner.lucent.protocol.FormValue
import dev.mooner.lucent.protocol.Forms

/**
 * 여러 칸을 받는 버튼(`form`, 사양 9.1)의 입력창: 로그인 정보(아이디, 비밀번호, 저장 여부) 등.
 *
 * 값은 `press.inputs`로 한 번 보내는 것 말고 어디에도 두지 않는다(13장).
 * - 화면 상태에 저장하지 않고(화면이 다시 만들어지면 입력창은 사라진다), 자동 완성·키보드의 학습에 넘기지 않는다.
 * - 창에 보안 표시를 걸어 화면 캡처와 최근 앱 화면에 잡히지 않게 한다.
 * - 보낸 즉시 칸을 비운다. 보내지 못했으면(연결이 없다) 창을 닫지 않고 넣은 값을 그대로 둔다.
 *
 * 글은 다듬지 않는다(비밀번호의 앞뒤 빈칸도 값이다). 길이는 서버가 알린 범위를 앱이 먼저 확인한다([Forms.check]).
 * 그릴 수 있는 입력창인지는 부르는 쪽이 [Forms.drawable]로 먼저 가린다.
 */
object FormDialog {

    /** [onSubmit]은 보냈으면 true를 돌려준다. false면 창이 남는다. */
    fun show(context: Context, form: CardForm, onSubmit: (Map<String, FormValue>) -> Boolean) {
        val inflater = LayoutInflater.from(context)
        val binding = DialogFormBinding.inflate(inflater)
        val texts = LinkedHashMap<FormField, ItemFormTextBinding>()
        val checks = LinkedHashMap<FormField, ItemFormCheckBinding>()

        for (field in form.fields) {
            if (field.kind == FormField.CHECK) {
                val row = ItemFormCheckBinding.inflate(inflater, binding.fields, false)
                row.check.text = Plain.text(field.label)
                // 처음 값은 사양이 정하지 않았다. 관리 페이지의 입력 링크와 같게 켠 채로 시작한다(12.5).
                row.check.isChecked = true
                binding.fields.addView(row.root)
                checks[field] = row
            } else {
                val row = ItemFormTextBinding.inflate(inflater, binding.fields, false)
                row.inputLayout.hint = Plain.text(field.label)
                val secret = field.kind == FormField.SECRET
                row.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                if (secret) row.inputLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                row.input.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NEXT
                row.input.doAfterTextChanged {
                    row.inputLayout.error = null
                    binding.problem.isVisible = false
                }
                binding.fields.addView(row.root)
                texts[field] = row
            }
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle(Plain.text(form.title).ifBlank { context.getString(R.string.prompt_default_title) })
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        fun clear() = texts.values.forEach { it.input.text?.clear() }

        fun submit() {
            var firstBad: ItemFormTextBinding? = null
            for ((field, row) in texts) {
                val reason = when (val problem = Forms.check(field, row.input.text.toString())) {
                    null -> null
                    is Forms.Problem.TooShort -> context.getString(R.string.prompt_too_short, problem.min)
                    is Forms.Problem.TooLong -> context.getString(R.string.prompt_too_long, problem.max)
                }
                row.inputLayout.error = reason
                if (reason != null && firstBad == null) firstBad = row
            }
            firstBad?.let {
                it.input.requestFocus()
                return
            }
            val values = LinkedHashMap<String, FormValue>()
            for ((field, row) in texts) values[field.key] = FormValue.Text(row.input.text.toString())
            for ((field, row) in checks) values[field.key] = FormValue.Check(row.check.isChecked)
            if (onSubmit(values)) {
                clear()
                dialog.dismiss()
            } else {
                binding.problem.setText(R.string.send_offline)
                binding.problem.isVisible = true
            }
        }

        // 취소하거나 밖을 눌러 닫아도 넣던 값은 남기지 않는다.
        dialog.setOnDismissListener { clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            texts.values.firstOrNull()?.input?.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        texts.values.lastOrNull()?.input?.let { last ->
            last.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
            last.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }
        dialog.show()
    }
}
