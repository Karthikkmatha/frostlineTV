package tv.frostline.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.frostline.core.twitchinternal.RaidEvent

data class RaidNotice(val raidId: String, val targetLogin: String, val targetName: String, val secondsLeft: Int)

/**
 * "Raiding X in 10 s" with Cancel. An update starts the countdown; when it reaches zero (or Twitch sends
 * the go event) [onSwitch] is called. Cancel, or a cancel event, dismisses it and ignores that raid.
 */
class RaidCountdown(
    private val scope: CoroutineScope,
    private val seconds: Int = 10,
    private val onSwitch: (String) -> Unit,
) {
    private val _notice = MutableStateFlow<RaidNotice?>(null)
    val notice: StateFlow<RaidNotice?> = _notice.asStateFlow()
    private val dismissed = HashSet<String>()
    private var job: Job? = null

    fun onEvent(e: RaidEvent) {
        when (e) {
            is RaidEvent.Update -> {
                if (e.raidId in dismissed || _notice.value?.raidId == e.raidId) return
                start(RaidNotice(e.raidId, e.targetLogin, e.targetName, seconds))
            }
            is RaidEvent.Go -> {
                if (e.raidId in dismissed) return
                finish(e.targetLogin)
            }
            is RaidEvent.Cancel -> if (_notice.value?.raidId == e.raidId) {
                job?.cancel()
                _notice.value = null
            }
        }
    }

    /** The user pressed Cancel. */
    fun cancel() {
        val n = _notice.value ?: return
        dismissed += n.raidId
        job?.cancel()
        _notice.value = null
    }

    fun reset() {
        job?.cancel()
        _notice.value = null
    }

    private fun start(n: RaidNotice) {
        job?.cancel()
        _notice.value = n
        job = scope.launch {
            for (left in seconds downTo 1) {
                _notice.value = n.copy(secondsLeft = left)
                delay(1_000)
            }
            finish(n.targetLogin)
        }
    }

    private fun finish(target: String) {
        job?.cancel()
        _notice.value = null
        onSwitch(target)
    }
}
