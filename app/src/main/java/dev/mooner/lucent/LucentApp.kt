package dev.mooner.lucent

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class LucentApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        LucentHub.get(this).attach()
    }

    /** 움직이는 스티커(GIF, 움직이는 WebP)를 돌릴 수 있게 한다. 오래된 기기에서는 GIF만 움직인다. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
}
