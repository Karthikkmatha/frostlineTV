package tv.frostline.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.frostline.core.twitch.Durations

/** A skip in progress: the signed total so far and where it will land. */
data class SkipState(val deltaMs: Long = 0, val targetMs: Long = 0) {
    val active: Boolean get() = deltaMs != 0L
}

/** "+00:30" / "−01:10": a sign and MM:SS (minutes may pass 59). Null when nothing is pending. */
fun skipLabel(deltaMs: Long): String? = when {
    deltaMs > 0 -> "+" + Durations.minutesSeconds(deltaMs)
    deltaMs < 0 -> "−" + Durations.minutesSeconds(-deltaMs)
    else -> null
}

/**
 * The skip buttons of the VOD/clip player. Presses (and the repeats of a held button) add up into one pending
 * total, shown as a single counter; the seek happens once, [idleMs] after the last press. A held key repeats
 * faster than anyone can read, so repeats add at most one step per [repeatStepMs].
 *
 * The total is measured from the position at the first press and clamped to [0, duration], so the counter
 * never shows a skip the player cannot make and only grows in the direction being pressed.
 */
class SkipController(
    private val scope: CoroutineScope,
    private val position: () -> Long,
    private val duration: () -> Long,
    private val seekTo: (Long) -> Unit,
    private val now: () -> Long,
    private val idleMs: Long = IDLE_MS,
    private val repeatStepMs: Long = REPEAT_STEP_MS,
) {
    private val _state = MutableStateFlow(SkipState())
    val state: StateFlow<SkipState> = _state.asStateFlow()

    private var base = 0L
    private var lastStepAt = Long.MIN_VALUE
    private var job: Job? = null

    /** [deltaMs] is -10 000 or +30 000; [repeat] is true for the auto-repeat events of a held key. */
    fun press(deltaMs: Long, repeat: Boolean = false) {
        val t = now()
        if (!(repeat && _state.value.active && t - lastStepAt < repeatStepMs)) {
            if (!_state.value.active) base = position()
            val dur = duration()
            val upper = if (dur > 0) dur else Long.MAX_VALUE
            val target = (base + _state.value.deltaMs + deltaMs).coerceIn(0L, upper)
            _state.value = SkipState(target - base, target)
            lastStepAt = t
        }
        restartIdle()
    }

    private fun restartIdle() {
        job?.cancel()
        job = scope.launch {
            delay(idleMs)
            apply()
        }
    }

    private fun apply() {
        val s = _state.value
        _state.value = SkipState()
        job = null
        if (s.active) seekTo(s.targetMs)
    }

    /** Drops a pending skip without seeking (leaving the screen). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SkipState()
    }

    companion object {
        const val IDLE_MS = 600L
        const val REPEAT_STEP_MS = 250L
        const val BACK_MS = -10_000L
        const val FORWARD_MS = 30_000L
    }
}

/** When to offer "Resume from ..." and what to keep in the database. */
object ResumePolicy {
    const val MIN_MS = 30_000L
    const val DONE_FRACTION = 0.95
    const val SAVE_EVERY_MS = 10_000L

    /** Past the first 30 s and not (nearly) finished. */
    fun resumable(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs > MIN_MS && positionMs < durationMs * DONE_FRACTION

    sealed interface Action {
        data object Keep : Action
        data object Remove : Action
        data class Save(val positionMs: Long) : Action
    }

    /** Save while resumable; drop the entry when finished or restarted; do nothing while the duration is unknown. */
    fun decide(positionMs: Long, durationMs: Long): Action = when {
        durationMs <= 0 -> Action.Keep
        resumable(positionMs, durationMs) -> Action.Save(positionMs)
        else -> Action.Remove
    }
}
