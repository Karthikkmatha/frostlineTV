package tv.frostline.feature.chat

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one clock every animated emote draws from. It ticks at [frameMs] (about 15 fps) only while it is
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
        /** 67 ms = about 15 fps. */
        const val FRAME_MS = 67L
    }
}

/** Null = animated emotes are not allowed here (setting off). */
val LocalEmoteClock = staticCompositionLocalOf<EmoteClock?> { null }

/** Which lines may animate. The chat panel provides the engine's rate-based gate; the emote drawer allows everything. */
val LocalEmoteGate = staticCompositionLocalOf { EmoteGate { true } }

/**
 * An emote. It always draws its still frame once decoded (so it never pops in blank); when it is animated, hot
 * (it has a pre-decoded strip in the shared atlas), the setting allows it ([LocalEmoteClock] non-null) and the chat mode lets this
 * line animate ([LocalEmoteGate], by [seq]), it draws strip frame `tick % frames` instead. Nothing is decoded in the
 * draw phase: it only picks a ready bitmap. Without an engine (tests, previews) it draws nothing.
 *
 * [priority] orders background work: the emote drawer asks for animation with [EmoteEngine.PRIO_VISIBLE].
 */
@Composable
fun EmoteImage(
    url: String,
    description: String,
    modifier: Modifier = Modifier,
    seq: Long = 0L,
    priority: Int = EmoteEngine.PRIO_HOT,
) {
    val engine = EmoteEngine.current
    val clock = LocalEmoteClock.current
    val gate = LocalEmoteGate.current
    val entry = remember(url, engine) { engine?.entry(url) }
    DisposableEffect(entry, engine) {
        if (engine != null && entry != null) engine.markVisible(entry)
        onDispose { if (engine != null && entry != null) engine.markHidden(entry) }
    }
    LaunchedEffect(entry, clock != null, engine?.active) {
        if (engine != null && entry != null) {
            engine.ensureStill(entry, EmoteEngine.PRIO_SCREEN)
            if (clock != null && engine.active && engine.mode != EmoteMode.Fast) engine.ensureAnimation(entry, priority)
        }
    }
    val version = entry?.version // composition reads it: a finished decode (or an eviction) recomposes this emote only
    val hasStrip = entry?.strip != null
    val hasStill = entry?.still != null
    DisposableEffect(clock, hasStrip, version) {
        if (clock != null && hasStrip) {
            clock.subscribe()
            onDispose { clock.unsubscribe() }
        } else {
            onDispose { }
        }
    }
    // The still was evicted from the atlas while this emote is still on screen: ask for it again.
    LaunchedEffect(entry, hasStill) {
        if (engine != null && entry != null && !hasStill) engine.ensureStill(entry, EmoteEngine.PRIO_SCREEN)
    }
    ComposeCanvas(modifier.semantics { contentDescription = description }) {
        val e = entry ?: return@ComposeCanvas
        e.version // draw-phase read: a decode that lands later redraws without recomposing
        drawEmoteEntry(e, 0, 0, size.width.toInt(), size.height.toInt(), clock, clock != null && gate.allows(seq), engine)
    }
}

/**
 * Draws [e] centred in the box ([x], [y], [boxW] x [boxH]) from the shared atlas: the still, or when [animate] and a
 * strip is ready, frame `tick % frames` of it. A src rect into a page bitmap; nothing is decoded and nothing allocated
 * here. A picture whose cells were evicted asks the engine for its still again.
 */
internal fun DrawScope.drawEmoteEntry(e: EmoteEntry, x: Int, y: Int, boxW: Int, boxH: Int, clock: EmoteClock?, animate: Boolean, engine: EmoteEngine?) {
    if (boxW <= 0 || boxH <= 0) return
    val strip = e.strip
    val still = e.still
    val animating = strip != null && clock != null && animate
    val region: AtlasRegion = when {
        strip != null && (animating || still == null) -> strip
        still != null -> still
        else -> {
            val direct = e.direct
            if (direct != null) { drawDirect(direct, x, y, boxW, boxH); return }
            engine?.ensureStill(e, EmoteEngine.PRIO_SCREEN); return
        }
    }
    val frame = if (animating && region === strip) frameIndex(clock!!.tick, region.count) else 0
    val iw = region.w
    val ih = region.h
    val s = minOf(boxW.toFloat() / iw, boxH.toFloat() / ih)
    val dw = (iw * s).toInt().coerceAtLeast(1)
    val dh = (ih * s).toInt().coerceAtLeast(1)
    drawImage(
        region.image,
        srcOffset = IntOffset(region.srcX(frame), region.srcY(frame)),
        srcSize = IntSize(iw, ih),
        dstOffset = IntOffset(x + (boxW - dw) / 2, y + (boxH - dh) / 2),
        dstSize = IntSize(dw, dh),
        filterQuality = FilterQuality.Low,
    )
}

/** The atlas had no slot: draw the emote's own decoded bitmap, centred in the box. */
private fun DrawScope.drawDirect(img: androidx.compose.ui.graphics.ImageBitmap, x: Int, y: Int, boxW: Int, boxH: Int) {
    val s = minOf(boxW.toFloat() / img.width, boxH.toFloat() / img.height)
    val dw = (img.width * s).toInt().coerceAtLeast(1)
    val dh = (img.height * s).toInt().coerceAtLeast(1)
    drawImage(img, dstOffset = IntOffset(x + (boxW - dw) / 2, y + (boxH - dh) / 2), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Low)
}
