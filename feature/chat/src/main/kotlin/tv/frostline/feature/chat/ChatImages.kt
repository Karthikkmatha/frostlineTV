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
    /** Upper bound of the decoded-image memory cache. */
    const val MEMORY_CACHE_BYTES = 32L * 1024 * 1024

    fun loader(context: Context): ImageLoader {
        val app = context.applicationContext
        val http = OkHttpClient.Builder().build()
        return ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                add(AnimatedImageDecoder.Factory())
            }
            // A fixed budget, not a share of the heap: decoded emotes and thumbnails must not grow with the device.
            .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
