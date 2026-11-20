package dev.mooner.lucent.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import coil.decode.BitmapFactoryDecoder
import coil.load
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import dev.mooner.lucent.R
import dev.mooner.lucent.data.PreviewImage
import dev.mooner.lucent.databinding.ActivityImageViewerBinding
import java.io.File

/**
 * 그림 한 장을 크게 보는 화면. 미리보기 시트의 그림이나 PDF의 쪽, 루시아의 얼굴을 누르면 열린다.
 *
 * 시트는 그림을 화면 폭에 맞춰 줄여 보이므로 긴 화면 갈무리나 작은 글씨는 읽기 어렵다. 여기서는 그림을 조각내어 보이는
 * 만큼만 제 해상도로 풀기 때문에(큰 그림도 메모리를 넘기지 않는다) 키워도 또렷하다. 움직이는 그림(GIF 등)은 조각낼 수
 * 없어 통째로 그리고, 키울 수는 없다.
 *
 * 앱이 받아 둔 파일만 연다: 앱의 캐시 폴더와 루시아의 프로필 그림 폴더([FaceViewer]) 밖의 경로는 받지 않는다.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableAppEdgeToEdge()
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let(::File)?.takeIf(::isOurs)
        if (file == null) {
            finish()
            return
        }
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 바탕은 창이 칠한다(테마의 종이색 위에 한 겹 더 칠하지 않는다).
        window.setBackgroundDrawable(getColor(R.color.viewer_backdrop).toDrawable())
        // 바탕이 늘 어두우므로 시스템 막대의 아이콘은 밝게 둔다.
        WindowCompat.getInsetsController(window, binding.root).run {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.bar.updatePadding(top = bars.top)
            binding.root.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        binding.title.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.close.setOnClickListener { finish() }

        if (isAnimated(file)) showWhole(file) else showZoomable(file)
    }

    private fun isOurs(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        file.isFile && listOf(cacheDir, FaceViewer.profileDir(this)).any { path.startsWith(it.canonicalPath + File.separator) }
    }.getOrDefault(false)

    private fun isAnimated(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(PreviewImage.HEAD_BYTES)
            val read = input.read(head)
            PreviewImage.isAnimated(if (read > 0) head.copyOf(read) else ByteArray(0))
        }
    }.getOrDefault(false)

    /** 멈춘 그림: 조각내어 올린다. 이 뷰가 풀지 못하는 종류면 통째로 그리는 쪽으로 넘긴다. */
    private fun showZoomable(file: File) {
        val view = binding.zoom
        view.isVisible = true
        view.orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
        view.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        view.setDoubleTapZoomDuration(if (Motion.enabled(this)) ZOOM_MS else 1)
        view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() {
                if (view.width <= 0 || view.sWidth <= 0) return
                val fitWidth = view.width.toFloat() / view.sWidth
                // 실제 화소의 몇 배까지 키울 수 있다. 작은 그림도 화면 폭까지는 키울 수 있게 한다.
                view.maxScale = maxOf(MAX_SCALE, fitWidth)
                view.setDoubleTapZoomScale(maxOf(fitWidth * DOUBLE_TAP, 1f).coerceAtMost(view.maxScale))
                // 세로로 긴 그림(긴 화면 갈무리)은 전체를 줄여 보이면 실처럼 가늘다. 폭에 맞추고 맨 위부터 보인다.
                if (view.sHeight * fitWidth > view.height) {
                    view.setScaleAndCenter(fitWidth, PointF(view.sWidth / 2f, view.height / 2f / fitWidth))
                } else {
                    // 작은 그림은 뷰가 처음에 제 한도(두 배)까지만 키워 놓는다. 한도를 올린 뒤 다시 맞추지 않으면
                    // 커지면서 가운데에서 밀려난다.
                    view.resetScaleAndCenter()
                }
            }

            override fun onImageLoadError(e: Exception) {
                view.isVisible = false
                showWhole(file)
            }
        })
        view.setImage(ImageSource.uri(Uri.fromFile(file)))
    }

    /** 움직이는 그림과 조각낼 수 없는 그림: 화면에 맞춰 통째로 그린다. */
    private fun showWhole(file: File) {
        val view = binding.still
        view.isVisible = true
        view.contentDescription = binding.title.text
        view.load(file) {
            crossfade(false)
            if (!Motion.enabled(this@ImageViewerActivity)) decoderFactory(BitmapFactoryDecoder.Factory())
            listener(onError = { _, _ ->
                Toast.makeText(this@ImageViewerActivity, R.string.preview_unreadable, Toast.LENGTH_SHORT).show()
                finish()
            })
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP = 2.5f
        private const val ZOOM_MS = 220

        fun start(context: Context, file: File, title: String) {
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_PATH, file.path).putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
