package dev.mooner.lucent.ui.common

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.mooner.lucent.databinding.SheetTextBinding

/**
 * 글 한 덩이를 펼쳐 보이고 일부를 고를 수 있게 하는 시트. 대화의 말풍선에서는 길게 누르면 할 일이 뜨므로 글을 고를 수
 * 없다. 일부만 복사하려면 여기로 온다(다른 메신저의 "부분 복사"와 같은 자리).
 */
object TextSheet {

    /** [fill]이 글을 채운다(마크다운으로 풀거나, 글 그대로). */
    fun show(activity: AppCompatActivity, title: String, fill: (TextView) -> Unit): SheetDialog {
        val binding = SheetTextBinding.inflate(LayoutInflater.from(activity))
        val dialog = SheetDialog(activity)
        dialog.setContentView(binding.root)
        dialog.closeWith(activity)
        binding.head.sheetTitle.text = title
        binding.head.sheetClose.setOnClickListener { dialog.dismiss() }
        fill(binding.text)
        // 글을 채우는 쪽(마크다운)이 고를 수 없게 바꿔 놓았을 수 있다. 고를 수 있게, 그러면서 링크도 눌리게 한다.
        binding.text.setTextIsSelectable(true)
        binding.text.movementMethod = LinkMovementMethod.getInstance()
        dialog.show()
        return dialog
    }
}
