package tv.frostline.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Timeline scrubbing of a past broadcast: Left/Right move a PREVIEW position, the video keeps playing where it is,
 * and OK seeks to the preview. A tap is 10 s; holding speeds up to 60 s steps after 1.5 s and 300 s steps after 4 s.
 */
object Scrub {
    const val TAP_MS = 10_000L
    const val MEDIUM_MS = 60_000L
    const val FAST_MS = 300_000L
    const val MEDIUM_AFTER_MS = 1_500L
    const val FAST_AFTER_MS = 4_000L

    /** A held key repeats far faster than anyone can follow: one step per this long. */
    const val THROTTLE_MS = 120L

    /** Step size for a key that has been held for [heldMs]. */
    fun stepMs(heldMs: Long): Long = when {
        heldMs >= FAST_AFTER_MS -> FAST_MS
        heldMs >= MEDIUM_AFTER_MS -> MEDIUM_MS
        else -> TAP_MS
    }

    /** Where one step from [positionMs] lands: [dir] is -1 or +1 (sign is used), the result is clamped to [0, durationMs]. */
    fun scrubTarget(positionMs: Long, dir: Int, heldMs: Long, durationMs: Long): Long {
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val d = if (dir < 0) -1 else 1
        return (positionMs + d * stepMs(heldMs)).coerceIn(0L, upper)
    }
}

/**
 * The preview state behind the timeline. [position] is the playhead (the start of a preview), [seekTo] applies it.
 * Nothing here touches the player until [commit].
 */
class ScrubController(
    private val position: () -> Long,
    private val duration: () -> Long,
    private val seekTo: (Long) -> Unit,
    private val now: () -> Long,
    private val throttleMs: Long = Scrub.THROTTLE_MS,
) {
    private val _preview = MutableStateFlow<Long?>(null)

    /** The previewed position, or null when no preview exists. */
    val preview: StateFlow<Long?> = _preview.asStateFlow()

    private var holdStart = 0L
    private var holdDir = 0
    private var lastStepAt = Long.MIN_VALUE

    /** One Left/Right key event. [repeat] marks the auto-repeat events of a held key, which are throttled. */
    fun step(dir: Int, repeat: Boolean) {
        val t = now()
        val d = if (dir < 0) -1 else 1
        if (!repeat || d != holdDir) {
            holdStart = t
            holdDir = d
        } else if (t - lastStepAt < throttleMs) {
            return
        }
        lastStepAt = t
        val from = _preview.value ?: position().coerceAtLeast(0L)
        _preview.value = Scrub.scrubTarget(from, d, t - holdStart, duration())
    }

    /** OK: seeks to the preview and clears it. False when there was no preview. */
    fun commit(): Boolean {
        val target = _preview.value ?: return false
        _preview.value = null
        seekTo(target)
        return true
    }

    /** Back or focus leaving: drops the preview without seeking. */
    fun cancel() {
        _preview.value = null
    }
}
