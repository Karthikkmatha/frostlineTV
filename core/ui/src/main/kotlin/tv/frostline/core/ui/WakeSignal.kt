package tv.frostline.core.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "The TV just woke up" (screen on, user present after the screensaver or sleep, app back in front). Sockets and timers
 * that ran while the device slept may be dead or stale, so chat, the token refresh and the player each check themselves
 * when this fires. [reason] is for logs only.
 */
object WakeSignal {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun fire(reason: String) { _events.tryEmit(reason) }
}
