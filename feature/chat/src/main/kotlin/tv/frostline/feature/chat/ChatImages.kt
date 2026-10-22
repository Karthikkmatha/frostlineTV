package tv.frostline.feature.chat

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * The app-wide Coil loader. Animated emotes (WebP/GIF) decode through the platform ImageDecoder, which
 * streams frames instead of holding them all, and the caches are bounded so chat cannot grow memory.
 */
object ChatImages {
    fun loader(context: Context): ImageLoader {
        val app = context.applicationContext
        val http = OkHttpClient.Builder().build()
        return ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
