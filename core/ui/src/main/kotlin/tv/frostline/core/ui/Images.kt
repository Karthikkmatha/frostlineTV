package tv.frostline.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size

/**
 * An image decoded at exactly its display size ([widthDp] x [heightDp] in pixels at this screen's density), never the
 * source size, as a hardware bitmap (pixels live in GPU memory, no copy on the Java heap). For thumbnails and avatars
 * whose size is fixed; images that fill a flexible cell use plain AsyncImage, which sizes the request from its layout.
 */
@Composable
fun SizedImage(
    model: String?,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val density = LocalDensity.current
    val context = LocalPlatformContext.current
    val request = remember(model, widthDp, heightDp, density.density) {
        ImageRequest.Builder(context)
            .data(model)
            .size(Size(with(density) { widthDp.roundToPx() }, with(density) { heightDp.roundToPx() }))
            .allowHardware(true)
            .build()
    }
    AsyncImage(request, contentDescription, modifier, contentScale = contentScale)
}

/**
 * While a full-screen player is shown, Home's thumbnails and avatars are off screen: drop the decoded images from Coil's
 * memory cache (they come back from the disk cache when Home is shown again).
 */
@Composable
fun DropImageMemoryWhileShown() {
    val context = LocalPlatformContext.current
    LaunchedEffect(Unit) { runCatching { SingletonImageLoader.get(context).memoryCache?.clear() } }
}
