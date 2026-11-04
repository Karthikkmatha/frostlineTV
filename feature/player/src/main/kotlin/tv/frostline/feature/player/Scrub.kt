package tv.frostline.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Timeline scrubbing of a past broadcast: Left/Right move a PREVIEW position, the video keeps playing where it is,
 * and OK seeks to the preview. A tap is 10 s. A hold (a second same-direction KeyDown within [HOLD_GAP_MS]) scrubs by TIME:
 * a ticker advances the preview by speed x elapsed, the speed climbing with hold time and capped so one full hold crosses the
 * whole broadcast in about 5 s, whatever the remote's event rate.
 */
object Scrub {
    const val TAP_MS = 10_000L

    /** A same-direction KeyDown this soon after the previous one continues a hold. */
    const val HOLD_GAP_MS = 600L

    /** The preview stops moving this long after the last KeyDown (the Streamer remote repeats every 320-380 ms), the hold ends at [HOLD_GAP_MS]. */
    const val COAST_MS = 400L

    /** The ticker period. */
    const val TICK_MS = 50L

    /** Hold time per speed rung. */
    const val RUNG_MS = 600L

    /** Scrub speeds in video seconds per real second: 20 s/s, 1, 3, 10, 30, 100, 300, 1000 min/s. */
    val SPEEDS: LongArray = longArrayOf(20L, 60L, 180L, 600L, 1_800L, 6_000L, 18_000L, 60_000L)

    private const val ONE_HOUR_MS = 3_600_000L
    private const val LONG_FLOOR_SPS = 300L

    /** Fastest speed for a broadcast of [durationMs]: duration / 4 s (over an hour never below 5 min/s). Unknown duration: 5 min/s. */
    fun maxSpeed(durationMs: Long): Long {
        if (durationMs <= 0) return LONG_FLOOR_SPS
        var cap = durationMs / 4_000L
        if (durationMs > ONE_HOUR_MS) cap = cap.coerceAtLeast(LONG_FLOOR_SPS)
        return cap.coerceAtLeast(10L)
    }

    /** Video seconds per real second after [heldMs] of hold. */
    fun speed(heldMs: Long, durationMs: Long): Long {
        val rung = (heldMs.coerceAtLeast(0L) / RUNG_MS).coerceAtMost((SPEEDS.size - 1).toLong()).toInt()
        return minOf(SPEEDS[rung], maxSpeed(durationMs))
    }

    /** Where one tap from [positionMs] lands: [dir] is -1 or +1 (sign is used), clamped to [0, durationMs]. */
    fun tapTarget(positionMs: Long, dir: Int, durationMs: Long): Long {
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (positionMs + (if (dir < 0) -TAP_MS else TAP_MS)).coerceIn(0L, upper)
    }

    /** "10 s/s", "1 min/s", "3 min/s", "10 min/s", "690 min/s". */
    fun speedLabel(sps: Long): String = if (sps < 60) "$sps s/s" else {
        val m = sps / 60.0
        if (m >= 10 || sps % 60 == 0L) "${Math.round(m)} min/s" else "${"%.1f".format(java.util.Locale.US, m)} min/s"
    }

    /** "▶▶ 10 s" for a tap ([sps] 0), "▶▶ 10 min/s" while holding; "◀◀" backwards. */
    fun stepText(dir: Int, sps: Long): String = (if (dir < 0) "\u25C0\u25C0 " else "\u25B6\u25B6 ") + (if (sps <= 0) "10 s" else speedLabel(sps))
}

/**
 * Tells a held Left/Right key from taps without trusting `repeatCount` or KeyUp. The Google TV Streamer remote sends a held key
 * as a separate DOWN+UP pair every ~350 ms, others send DOWN with repeats. A KeyDown in the same direction within [gapMs] of the
 * previous one continues the hold, whatever repeatCount says; KeyUp is ignored. The hold ends when [gapMs] pass without a
 * same-direction KeyDown (see [holding]) or the direction changes.
 */
class KeyHold(private val gapMs: Long = Scrub.HOLD_GAP_MS) {
    private var active = false
    private var dir = 0
    private var startedAt = 0L
    private var lastAt = 0L

    /** KeyDown events seen in this hold (the first one is the press). */
    var events = 0
        private set

    /** One KeyDown. True when it continues the current hold (this is at least the second event), false when it starts a new one. */
    fun down(d: Int, @Suppress("UNUSED_PARAMETER") repeatCount: Int, t: Long): Boolean {
        val gap = t - lastAt
        val continuing = active && d == dir && gap in 0 until gapMs
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

    /** True at [t] while a hold (two or more events) is in force. */
    fun holding(t: Long): Boolean = active && events >= 2 && t - lastAt < gapMs

    /** True at [t] while the last KeyDown is recent enough that the key is probably still down. */
    fun recent(t: Long, coastMs: Long = Scrub.COAST_MS): Boolean = active && t - lastAt < coastMs

    fun lastDownAt(): Long = lastAt

    fun end() {
        active = false
        events = 0
    }

    fun heldMs(t: Long): Long = if (active) (t - startedAt).coerceAtLeast(0L) else 0L
}

/** The state shown in the label: direction and speed in video s per real s (0 = a tap, 10 s). */
data class ScrubStatus(val dir: Int, val speed: Long)

/** For the debug overlay: `scrub speed 10 min/s held 3.2 s events 18`. */
data class ScrubInfo(val speed: Long, val heldMs: Long, val events: Int) {
    fun line(): String = "scrub speed ${if (speed <= 0) "tap" else Scrub.speedLabel(speed)} held ${"%.1f".format(java.util.Locale.US, heldMs / 1000.0)} s events $events"
}

/**
 * The preview state behind the timeline. [position] is the playhead (the start of a preview), [seekTo] applies it.
 * Nothing here touches the player until [commit]. While a hold is in force the owner calls [tick] every [Scrub.TICK_MS].
 */
class ScrubController(
    private val position: () -> Long,
    private val duration: () -> Long,
    private val seekTo: (Long) -> Unit,
    private val now: () -> Long,
) {
    private val _preview = MutableStateFlow<Long?>(null)

    /** The previewed position, or null when no preview exists. */
    val preview: StateFlow<Long?> = _preview.asStateFlow()

    private val _status = MutableStateFlow<ScrubStatus?>(null)

    /** Direction and speed, for the label above the bar. Null when no preview exists. */
    val status: StateFlow<ScrubStatus?> = _status.asStateFlow()

    private val _info = MutableStateFlow<ScrubInfo?>(null)

    /** Speed, hold time and event count, for the debug overlay. Collected only while the overlay is on. */
    val info: StateFlow<ScrubInfo?> = _info.asStateFlow()

    private val hold = KeyHold()
    private var dir = 1
    private var lastTickAt = 0L
    private var tickedAt = 0L

    /** True while a hold is in force: the owner should be ticking. */
    fun isHolding(): Boolean = hold.holding(now())

    /** One Left/Right KeyDown. [repeatCount] is the event's own, which may be 0 on every event of a held key. KeyUp is not needed. */
    fun key(d: Int, repeatCount: Int) {
        val t = now()
        dir = if (d < 0) -1 else 1
        val continuing = hold.down(dir, repeatCount, t)
        if (continuing) {
            if (hold.events == 2) lastTickAt = t // the hold starts: the ticker takes over
            return
        }
        // A press that continues nothing is a tap: exactly 10 s.
        val from = _preview.value ?: position().coerceAtLeast(0L)
        _preview.value = Scrub.tapTarget(from, dir, duration())
        _status.value = ScrubStatus(dir, 0L)
        _info.value = ScrubInfo(0L, 0L, 1)
    }

    /** Advances the preview by speed x elapsed while a hold is in force. False once the hold is over (stop ticking). */
    fun tick(): Boolean {
        val t = now()
        if (!hold.holding(t)) {
            if (_status.value?.speed != 0L) _status.value?.let { _status.value = ScrubStatus(it.dir, 0L) }
            return false
        }
        val dt = t - lastTickAt
        lastTickAt = t
        if (!hold.recent(t)) return true // the key is probably up: freeze until the hold times out
        val dur = duration()
        val held = hold.heldMs(t)
        val sps = Scrub.speed(held, dur)
        val upper = if (dur > 0) dur else Long.MAX_VALUE
        val from = _preview.value ?: position().coerceAtLeast(0L)
        _preview.value = (from + dir * dt * sps).coerceIn(0L, upper)
        val st = _status.value
        if (st == null || st.speed != sps || st.dir != dir) _status.value = ScrubStatus(dir, sps)
        // The debug line only needs a refresh when the speed or the event count moved (no allocation on the other ticks).
        val inf = _info.value
        if (inf == null || inf.speed != sps || inf.events != hold.events) _info.value = ScrubInfo(sps, held, hold.events)
        return true
    }

    /** KeyUp of Left/Right: ignored on purpose (the Streamer remote sends one after every KeyDown). */
    fun release() = Unit

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
        hold.end()
    }
}
