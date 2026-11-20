package dev.mooner.lucent.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 프로필 그림을 화면 크기로 읽는 것([ProfileStore.decodeSquare]). 실제 디코더로 본다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceDecodeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, paint: (Bitmap) -> Unit = {}): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        paint(bitmap)
        return File(app.cacheDir, "face-$width-$height.${format.name}").also { file ->
            file.outputStream().use { bitmap.compress(format, 95, it) }
        }
    }

    @Test fun `큰 그림은 원하는 크기의 정사각형으로 줄여 읽는다`() {
        val face = ProfileStore.decodeSquare(picture(2048, 2048), 264)
        assertNotNull(face)
        assertEquals(264, face!!.width)
        assertEquals(264, face.height)
        assertEquals(Color.BLUE, face.getPixel(132, 132))
        val jpeg = ProfileStore.decodeSquare(picture(800, 800, Bitmap.CompressFormat.JPEG), 120)
        assertEquals(120, jpeg!!.width)
    }

    @Test fun `작은 그림은 늘리지 않는다`() {
        val face = ProfileStore.decodeSquare(picture(128, 128), 264)
        assertEquals(128, face!!.width)
        assertEquals(128, face.height)
    }

    @Test fun `정사각형이 아니면 가운데를 쓴다`() {
        // 가로로 긴 그림: 왼쪽 끝은 빨강, 가운데는 파랑.
        val file = picture(600, 300) { bitmap -> for (x in 0 until 100) for (y in 0 until 300) bitmap.setPixel(x, y, Color.RED) }
        val face = ProfileStore.decodeSquare(file, 100)
        assertEquals(100, face!!.width)
        assertEquals(100, face.height)
        assertEquals(Color.BLUE, face.getPixel(2, 50))
    }

    @Test fun `사진에 적힌 방향대로 세운다`() {
        // 누운 채 저장된 사진: 픽셀로는 왼쪽 절반이 빨강이지만, 시계 방향으로 90도 돌려 보라고 적혀 있다(위가 빨강이 된다).
        val file = picture(400, 400, Bitmap.CompressFormat.JPEG) { bitmap ->
            for (x in 0 until 200) for (y in 0 until 400) bitmap.setPixel(x, y, Color.RED)
        }
        fun red(color: Int) = Color.red(color) > 200 && Color.blue(color) < 60
        fun blue(color: Int) = Color.blue(color) > 200 && Color.red(color) < 60
        val flat = ProfileStore.decodeSquare(file, 100)!!
        assertTrue(red(flat.getPixel(20, 50)) && blue(flat.getPixel(80, 50)))

        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val upright = ProfileStore.decodeSquare(file, 100)!!
        assertEquals(100, upright.width)
        assertTrue(red(upright.getPixel(50, 20)) && blue(upright.getPixel(50, 80)))
    }

    @Test fun `그림이 아니면 읽지 않는다`() {
        val file = File(app.cacheDir, "not-a-picture").apply { writeText("<html>not an image</html>") }
        assertNull(ProfileStore.decodeSquare(file, 264))
        assertNull(ProfileStore.decodeSquare(File(app.cacheDir, "missing"), 264))
    }
}
