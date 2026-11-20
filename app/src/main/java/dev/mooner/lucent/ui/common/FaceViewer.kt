package dev.mooner.lucent.ui.common

import android.annotation.SuppressLint
import android.content.Context
import dev.mooner.lucent.R
import dev.mooner.lucent.data.Face
import java.io.File

/**
 * 루시아의 얼굴을 눌렀을 때: 프로필 그림을 크게 본다([ImageViewerActivity]).
 *
 * 루시아가 정한 그림이 있으면 받아 둔 원본을, 없으면 앱의 기본 초상을 보인다. 보기만 한다(앱이 그림을 정하거나 올리는
 * 길은 없다, 사양 8.7).
 */
object FaceViewer {

    /** 루시아가 정한 프로필 그림의 원본이 있는 폴더(`ProfileStore`). 뷰어가 여는 것을 허락하는 자리다. */
    fun profileDir(context: Context) = File(context.filesDir, "profile")

    fun open(context: Context, face: Face?) {
        val file = face?.file?.takeIf { it.isFile } ?: defaultPortrait(context) ?: return
        ImageViewerActivity.start(context, file, context.getString(R.string.assistant_name))
    }

    /**
     * 기본 초상은 앱에 든 그림이라 파일이 아니다. 뷰어가 열 수 있게 캐시에 한 번 꺼내 둔다.
     * 그림 파일로 된 drawable(여기서는 webp)은 `openRawResource`로 그대로 읽을 수 있다. 린트는 raw 자원만 받는다고 본다.
     */
    @SuppressLint("ResourceType")
    private fun defaultPortrait(context: Context): File? = runCatching {
        // 앱을 새로 깔 때마다 이름이 달라진다. 앱에 든 초상이 바뀌어도 예전에 꺼내 둔 그림이 계속 열리지 않게 한다.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.cacheDir, "faces")
        val file = File(dir, "lucia_portrait.$installed.webp")
        if (!file.isFile || file.length() == 0L) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            context.resources.openRawResource(R.drawable.lucia_portrait).use { input -> file.outputStream().use(input::copyTo) }
        }
        file
    }.getOrNull()
}
