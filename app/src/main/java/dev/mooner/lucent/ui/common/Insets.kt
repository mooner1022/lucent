package dev.mooner.lucent.ui.common

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import dev.mooner.lucent.R
import dev.mooner.lucent.ui.sky.SkyView
import kotlin.math.max

/**
 * 모든 화면의 시스템 막대 설정. 상태 표시줄과 내비게이션 막대를 투명하게 두고 본문이 그 뒤까지 이어지게 한다. 상태 표시줄
 * 아이콘의 색은 하늘 머리말의 밝기를 따른다([dev.mooner.lucent.ui.sky.PageSky], 대화 화면). 내비게이션 막대를 명시적으로
 * 투명하게 주지 않으면 3버튼 내비게이션에서 창이 막대 위까지만 쓰인다.
 */
fun ComponentActivity.enableAppEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    // 3버튼 내비게이션에서는 시스템이 막대 뒤에 반투명한 가림막을 깐다. 그러면 화면 맨 아래에 종이와 다른 색의 띠가
    // 생기므로 끈다. 막대의 단추 색은 위의 설정이 테마에 맞춘다.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
}

/** 넓은 화면(태블릿·폴더블)에서 본문이 퍼지지 않도록, 가운데 [R.dimen.content_max_width]만 쓰게 하는 좌우 여백. */
fun sideInset(contentWidthPx: Int, resources: android.content.res.Resources): Int {
    val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
    return max(0, (contentWidthPx - maxWidth) / 2)
}

/**
 * 설정·연결 화면의 공통 틀. 본문([body])은 하늘 머리말([header]) 뒤까지 이어지고 머리말이 그 위에 뜬다.
 *
 * - 머리말 줄은 상태 표시줄 아래로 내리고, 달은 그 줄의 높이에 뜬다.
 * - 본문의 위 여백은 머리말 높이다. 아래는 키보드·내비게이션 막대만큼 본문이 줄어든다(그 자리로 내용이 들어가지 않는다).
 * - 넓은 화면에서는 좌우를 묶는다.
 */
fun applyPageInsets(root: View, header: View, headerContent: View, sky: SkyView, body: ScrollView) {
    val res = root.resources
    val below = res.getDimensionPixelSize(R.dimen.space_s)
    val headerStart = headerContent.paddingStart
    val headerEnd = headerContent.paddingEnd
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val side = sideInset(root.width - bars.left - bars.right, res)
        root.updatePadding(left = bars.left, right = bars.right)
        headerContent.updatePadding(left = headerStart + side, top = bars.top, right = headerEnd + side)
        sky.contentTop = bars.top
        sky.contentSide = side
        body.updatePadding(left = side, right = side, bottom = below)
        // 내비게이션 막대(와 키보드) 자리는 본문 밖에 둔다. 막대 뒤가 종이색 그대로라서, 본문이 그 밑까지 이어지면
        // 굴러 온 행이 막대의 단추와 겹친다.
        body.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = max(ime.bottom, bars.bottom) }
        insets
    }
    root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) ViewCompat.requestApplyInsets(root)
    }
    // 배치 도중에 다시 배치를 청하면 버려질 수 있으므로 다음 차례로 미룬다.
    header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) body.post { body.updatePadding(top = bottom - top) }
    }
}
