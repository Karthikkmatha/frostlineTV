package tv.frostline.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Timeline scrubbing of a past broadcast: Left/Right move a PREVIEW position, the video keeps playing where it is,
 * and OK seeks to the preview. A tap is 10 s. Holding climbs a ladder, one rung per [RUNG_MS], 10 s, 30 s, 1 min, 2 min,
 * 5 min, 10 min, 30 min, but never above a top rung scaled to the broadcast so that one full hold crosses all of it in
 * a few seconds whatever its length.
 */
object Scrub {
    const val TAP_MS = 10_000L

    /** The step sizes, smallest first. */
    val LADDER_MS: LongArray = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L)

    /** Hold time per rung (0.8 s: the ladder is climbed in about 5 s). */
    const val RUNG_MS = 800L

    /** A held key repeats far faster than anyone can follow: one step per this long. */
    const val THROTTLE_MS = 120L

    /** The cap is about duration / this many steps. */
    const val CROSS_STEPS = 40

    private const val ONE_HOUR_MS = 3_600_000L
    private const val LONG_FLOOR_MS = 300_000L

    /** Top rung for a broadcast of [durationMs]: the smallest ladder value at least duration/40, at least 5 min over an hour. Unknown duration: 5 min. */
    fun topRungMs(durationMs: Long): Long {
        if (durationMs <= 0) return LONG_FLOOR_MS
        var want = (durationMs + CROSS_STEPS - 1) / CROSS_STEPS
        if (durationMs > ONE_HOUR_MS) want = want.coerceAtLeast(LONG_FLOOR_MS)
        return LADDER_MS.firstOrNull { it >= want } ?: LADDER_MS.last()
    }

    /** Step size for a key held for [heldMs] in a broadcast of [durationMs]. */
    fun stepMs(heldMs: Long, durationMs: Long): Long {
        val top = LADDER_MS.indexOf(topRungMs(durationMs))
        val rung = (heldMs.coerceAtLeast(0L) / RUNG_MS).coerceAtMost(top.toLong()).toInt()
        return LADDER_MS[rung]
    }

    /** Where one step from [positionMs] lands: [dir] is -1 or +1 (sign is used), the result is clamped to [0, durationMs]. */
    fun scrubTarget(positionMs: Long, dir: Int, heldMs: Long, durationMs: Long): Long {
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val d = if (dir < 0) -1 else 1
        return (positionMs + d * stepMs(heldMs, durationMs)).coerceIn(0L, upper)
    }

    /** "10 s", "30 s", "1 min", "2 min", "5 min", "10 min", "30 min". */
    fun stepLabel(stepMs: Long): String {
        val s = stepMs / 1000
        return if (s < 60) "$s s" else "${s / 60} min"
    }

    /** "▶▶ 10 min" forward, "◀◀ 10 min" back. */
    fun stepText(dir: Int, stepMs: Long): String = (if (dir < 0) "◀◀ " else "▶▶ ") + stepLabel(stepMs)
}

/**
 * Tells a held Left/Right key from taps without trusting `repeatCount`: some remotes never set it, some send the first
 * repeat only 400-500 ms after the press. A hold starts at a KeyDown that does not continue the previous one and lasts
 * until a KeyUp or a gap between KeyDown events of [gapMs] (the second event may take [firstGapMs], the initial repeat delay).
 * An event that does carry repeatCount > 0 is a repeat by definition and continues the hold (within [maxGapMs]).
 */
class KeyHold(
    private val gapMs: Long = 300L,
    private val firstGapMs: Long = 600L,
    private val maxGapMs: Long = 2_000L,
) {
    private var active = false
    private var dir = 0
    private var startedAt = 0L
    private var lastAt = 0L

    /** KeyDown events seen in this hold (the first one is the press). */
    var events = 0
        private set

    /** Repeat events after the press. */
    val repeats: Int get() = (events - 1).coerceAtLeast(0)

    /** One KeyDown. True when it continues the current hold, false when it starts a new one. */
    fun down(d: Int, repeatCount: Int, t: Long): Boolean {
        val gap = t - lastAt
        val limit = if (events <= 1) firstGapMs else gapMs
        val continuing = active && d == dir && gap >= 0 && (gap < limit || (repeatCount > 0 && gap < maxGapMs))
        if (!continuing) {
            active = true
            dir = d
            startedAt = t
            events = 0
        }
        events++
        lastAt = t
        return continuing
    }

    /** KeyUp: the hold is over, the next press is a tap again. */
    fun up() {
        active = false
        events = 0
    }

    fun heldMs(t: Long): Long = if (active) (t - startedAt).coerceAtLeast(0L) else 0L
}

/** The step in force while scrubbing: its direction and size (changes only when a rung changes or the key is released). */
data class ScrubStatus(val dir: Int, val stepMs: Long)

/** For the debug overlay: `scrub step 5 min held 3.2 s repeats 18`. */
data class ScrubInfo(val stepMs: Long, val heldMs: Long, val repeats: Int) {
    fun line(): String = "scrub step ${Scrub.stepLabel(stepMs)} held ${"%.1f".format(java.util.Locale.US, heldMs / 1000.0)} s repeats $repeats"
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

    private val _status = MutableStateFlow<ScrubStatus?>(null)

    /** Direction and step size, for the label under the bar. Null when no preview exists. */
    val status: StateFlow<ScrubStatus?> = _status.asStateFlow()

    private val _info = MutableStateFlow<ScrubInfo?>(null)

    /** Step, hold time and repeat count of the last step, for the debug overlay. Collected only while the overlay is on. */
    val info: StateFlow<ScrubInfo?> = _info.asStateFlow()

    private val hold = KeyHold()
    private var lastStepAt = Long.MIN_VALUE

    /** One Left/Right KeyDown. [repeatCount] is the event's own, which may be 0 on every event of a held key. */
    fun key(dir: Int, repeatCount: Int) {
        val t = now()
        val d = if (dir < 0) -1 else 1
        val continuing = hold.down(d, repeatCount, t)
        if (continuing && t - lastStepAt < throttleMs) return
        lastStepAt = t
        val held = hold.heldMs(t)
        val dur = duration()
        val step = Scrub.stepMs(held, dur)
        val from = _preview.value ?: position().coerceAtLeast(0L)
        _preview.value = Scrub.scrubTarget(from, d, held, dur)
        _status.value = ScrubStatus(d, step)
        _info.value = ScrubInfo(step, held, hold.repeats)
    }

    /** KeyUp of Left/Right: the next press starts at the tap step again. */
    fun release() {
        hold.up()
        _status.value?.let { _status.value = it.copy(stepMs = Scrub.TAP_MS) }
    }

    /** OK: seeks to the preview and clears it. False when there was no preview. */
    fun commit(): Boolean {
        val target = _preview.value ?: return false
        clear()
        seekTo(target)
        return true
    }

    /** Back or focus leaving: drops the preview without seeking. */
    fun cancel() = clear()

    private fun clear() {
        _preview.value = null
        _status.value = null
        _info.value = null
        hold.up()
    }
}
