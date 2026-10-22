package tv.frostline.feature.chat

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one clock every animated emote draws from. It ticks at [frameMs] (about 12 fps) only while it is
 * [setActive] (panel shown, player playing, setting on) and at least one emote is subscribed (on screen),
 * so an idle or hidden chat costs nothing. All emotes read [tick] in their draw phase, so a tick redraws
 * the emote canvases only: not composition, not layout, not the rest of the window.
 */
@Stable
class EmoteClock(private val scope: CoroutineScope, private val frameMs: Long = FRAME_MS) {
    /** Incremented once per frame. Read it inside a draw block. */
    var tick: Long by mutableLongStateOf(0L)
        private set
    private var active = false
    private var subscribers = 0
    private var job: Job? = null

    val running: Boolean get() = job != null

    fun setActive(value: Boolean) {
        active = value
        update()
    }

    fun subscribe() {
        subscribers++
        update()
    }

    fun unsubscribe() {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        update()
    }

    private fun update() {
        if (active && subscribers > 0) {
            if (job == null) {
                job = scope.launch {
                    while (isActive) {
                        delay(frameMs)
                        tick++
                    }
                }
            }
        } else {
            job?.cancel()
            job = null
        }
    }

    companion object {
        /** 80 ms = 12.5 fps. */
        const val FRAME_MS = 80L
    }
}

/** Null = animated emotes are not allowed here (setting off, or a still-frame context such as the picker). */
val LocalEmoteClock = staticCompositionLocalOf<EmoteClock?> { null }

/** Draws an AnimatedImageDrawable into a small software bitmap, so the display list never holds a self-animating op. */
private class FrameRenderer(private val drawable: Drawable) {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var renderedStill = false

    fun render(w: Int, h: Int, still: Boolean): Bitmap {
        val existing = bitmap
        if (existing != null && existing.width == w && existing.height == h && still && renderedStill) return existing
        val bmp = if (existing != null && existing.width == w && existing.height == h) existing else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it; canvas = Canvas(it) }
        }
        bmp.eraseColor(0)
        fit(drawable, w, h)
        drawable.draw(canvas!!)
        renderedStill = still
        return bmp
    }
}

private fun fit(d: Drawable, w: Int, h: Int) {
    val iw = d.intrinsicWidth
    val ih = d.intrinsicHeight
    if (iw <= 0 || ih <= 0) {
        d.setBounds(0, 0, w, h)
        return
    }
    val s = minOf(w.toFloat() / iw, h.toFloat() / ih)
    val dw = (iw * s).toInt().coerceAtLeast(1)
    val dh = (ih * s).toInt().coerceAtLeast(1)
    val l = (w - dw) / 2
    val t = (h - dh) / 2
    d.setBounds(l, t, l + dw, t + dh)
}

/**
 * An emote. Static images draw normally. Animated ones (WebP/GIF) are driven by the shared [LocalEmoteClock]:
 * one frame per tick, drawn into a bitmap, so they run at about 12 fps in lockstep and stop with the clock.
 * Without a clock (setting off, picker) an animated emote shows its first frame and never ticks.
 */
@Composable
fun EmoteImage(url: String, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clock = LocalEmoteClock.current
    var drawable by remember(url) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(url) {
        val result = runCatching {
            SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).build())
        }.getOrNull()
        drawable = (result as? SuccessResult)?.image?.asDrawable(context.resources)
    }
    val d = drawable
    val animated = d is AnimatedImageDrawable
    val renderer = remember(d) { if (d is AnimatedImageDrawable) FrameRenderer(d) else null }
    DisposableEffect(d, clock) {
        if (d is AnimatedImageDrawable && clock != null) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
            clock.subscribe()
            onDispose {
                clock.unsubscribe()
                d.stop()
            }
        } else {
            onDispose { }
        }
    }
    ComposeCanvas(modifier.semantics { contentDescription = description }) {
        val drawn = drawable ?: return@ComposeCanvas
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@ComposeCanvas
        if (animated && renderer != null) {
            if (clock != null) clock.tick // the only state read here: a tick redraws this canvas and nothing else
            drawImage(renderer.render(w, h, still = clock == null).asImageBitmap())
        } else {
            fit(drawn, w, h)
            drawIntoCanvas { drawn.draw(it.nativeCanvas) }
        }
    }
}
