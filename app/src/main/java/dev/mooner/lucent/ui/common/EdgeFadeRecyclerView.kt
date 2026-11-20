package dev.mooner.lucent.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import dev.mooner.lucent.R

/**
 * 위아래 끝에서 내용이 스러지는 목록(docs/design.md 7장 "그 밖").
 *
 * 대화 목록은 머리말 뒤까지 이어지고 아래는 입력창에 닿는다. 그 두 끝에서 말풍선이 칼로 자른 듯 끊기지 않고 옅어지며
 * 들어가게 한다. 더 굴릴 것이 없는 쪽(대화의 맨 처음, 맨 끝)은 스러지지 않는다.
 *
 * 스러지는 자리는 목록의 안쪽 여백이 아니라 눈에 보이는 끝이다: 위는 머리말의 아랫선([topGap]만큼 여백보다 위),
 * 아래는 목록의 맨 아래(아래 여백 안으로 굴러 들어온 줄도 같이 스러진다).
 *
 * 뷰가 본래 가진 "스러지는 가장자리"를 쓰지 않고 직접 그린다: 그것은 스러지는 자리를 여백 기준으로만 잡고, 일부 기기에서
 * 목록 전체의 색이 달라진다. 여기서는 스러질 것이 있을 때만 목록을 따로 그려 두 끝을 옅게 지운다.
 */
class EdgeFadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    /** 위 여백 가운데 머리말 아래로 나와 있는 틈. 스러짐은 그 틈의 위(머리말의 아랫선)에서 끝난다. */
    var topGap = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fade = resources.getDimensionPixelSize(R.dimen.edge_fade)

    /** 높이 1짜리 "불투명 → 투명". 그릴 때 길이와 방향을 맞춘다. 그려 둔 내용을 이만큼 지운다. */
    private val shader = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val eraser = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = this@EdgeFadeRecyclerView.shader
    }

    /**
     * 맨 처음 줄이 제자리에 있으면 0, 머리말 밑으로 들어간 만큼 짙어진다. 목록이 어림하는 스크롤 값은 줄의 높이가
     * 들쭉날쭉하면 맞지 않으므로, 놓인 줄의 자리를 직접 본다.
     */
    private fun topStrength(): Float {
        val first = getChildAt(0) ?: return 0f
        if (getChildAdapterPosition(first) > 0) return 1f
        return ((paddingTop - first.top).toFloat() / fade).coerceIn(0f, 1f)
    }

    /** 마지막 줄이 다 보이면 0, 아래로 넘어간 만큼 짙어진다. */
    private fun bottomStrength(): Float {
        val last = getChildAt(childCount - 1) ?: return 0f
        val count = adapter?.itemCount ?: 0
        if (getChildAdapterPosition(last) < count - 1) return 1f
        return ((last.bottom - (height - paddingBottom)).toFloat() / fade).coerceIn(0f, 1f)
    }

    /** 줄들을 그리는 자리에서 한다(배경이 없는 목록은 `draw`를 건너뛰고 곧장 여기로 온다). */
    override fun dispatchDraw(canvas: Canvas) {
        val topLength = fade * topStrength()
        val bottomLength = fade * bottomStrength()
        if (topLength < 1f && bottomLength < 1f) return super.dispatchDraw(canvas)

        val line = (paddingTop - topGap).toFloat()
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        if (topLength >= 1f) {
            // 머리말의 아랫선에서 다 지워지고, 아래로 내려오며 되살아난다.
            matrix.setScale(1f, topLength)
            matrix.postTranslate(0f, line)
            shader.setLocalMatrix(matrix)
            canvas.drawRect(0f, line, width.toFloat(), line + topLength, eraser)
        }
        if (bottomLength >= 1f) {
            // 목록의 맨 아래에서 다 지워지고, 위로 올라가며 되살아난다.
            matrix.setScale(1f, -bottomLength)
            matrix.postTranslate(0f, height.toFloat())
            shader.setLocalMatrix(matrix)
            canvas.drawRect(0f, height - bottomLength, width.toFloat(), height.toFloat(), eraser)
        }
        canvas.restoreToCount(saved)
    }
}
