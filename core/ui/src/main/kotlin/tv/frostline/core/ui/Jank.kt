package tv.frostline.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.delay

/**
 * Janky frames over a sliding window (10 s by default): fed one frame at a time by [JankStats], read once a second for the
 * debug overlay. A ring of the last [capacity] frames; frames older than the window are ignored. Thread-safe, pure.
 */
class JankWindow(private val windowMs: Long = 10_000L, private val capacity: Int = 2048) {
    private val times = LongArray(capacity)
    private val janky = BooleanArray(capacity)
    private var head = 0
    private var count = 0

    @Synchronized
    fun record(frameStartMs: Long, isJank: Boolean) {
        times[head] = frameStartMs
        janky[head] = isJank
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** (frames, janky frames) within the window that ends at [nowMs]. */
    @Synchronized
    fun counts(nowMs: Long): Pair<Int, Int> {
        var frames = 0
        var bad = 0
        var i = head
        for (k in 0 until count) {
            i = (i - 1 + capacity) % capacity
            if (nowMs - times[i] > windowMs) break
            frames++
            if (janky[i]) bad++
        }
        return frames to bad
    }

    /** "jank     3.2% (19/596 frames, 10 s)", or a dash line while no frame has been drawn in the window. */
    fun line(nowMs: Long): String {
        val (frames, bad) = counts(nowMs)
        if (frames == 0) return "jank     - (no frames drawn in ${windowMs / 1000} s)"
        return "jank     ${"%.1f".format(bad * 100.0 / frames)}% ($bad/$frames frames, ${windowMs / 1000} s)"
    }
}

/**
 * Janky-frame percentage over the last 10 s for the debug overlay, refreshed once a second. Uses androidx JankStats on this
 * window; tracking is on only while [enabled] (the overlay is shown), so a normal run pays nothing.
 */
@Composable
fun rememberJankLine(enabled: Boolean): State<String> {
    val line = remember { mutableStateOf("jank     -") }
    val view = LocalView.current
    val window = remember { JankWindow() }
    DisposableEffect(enabled, view) {
        val w = if (enabled) view.context.findWindow() else null
        if (w == null) {
            onDispose { }
        } else {
            val stats = JankStats.createAndTrack(w, JankStats.OnFrameListener { frame -> window.record(frame.frameStartNanos / 1_000_000L, frame.isJank) })
            onDispose { stats.isTrackingEnabled = false }
        }
    }
    LaunchedEffect(enabled) {
        while (enabled) {
            line.value = window.line(System.nanoTime() / 1_000_000L)
            delay(1_000)
        }
    }
    return line
}

/**
 * Work that is not needed to show the first screen (the integrity token, the global emote preload) waits here: it runs
 * once the first frame has been drawn plus [AFTER_FIRST_FRAME_MS], so cold start to Following is not competing with it.
 */
object StartupGate {
    const val AFTER_FIRST_FRAME_MS = 2_000L
    private val first = kotlinx.coroutines.CompletableDeferred<Unit>()

    val firstFrameDrawn: Boolean get() = first.isCompleted

    /** Called by the activity once the first frame is on screen. */
    fun onFirstFrame() {
        first.complete(Unit)
    }

    /** Suspends until the first frame is drawn, then [extraMs] more. */
    suspend fun awaitIdle(extraMs: Long = AFTER_FIRST_FRAME_MS) {
        first.await()
        delay(extraMs)
    }
}
