package tv.frostline.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Channels whose live stream Twitch refused to play here (restricted), learned when playback hit it. Cards show a lock
 * badge for these. In memory only, bounded, and forgotten as soon as the channel plays again.
 */
object RestrictedStreams {
    private const val MAX = 64
    private val _logins = MutableStateFlow<Set<String>>(emptySet())
    val logins: StateFlow<Set<String>> = _logins

    fun mark(login: String) {
        val l = login.lowercase()
        _logins.update { cur -> if (l in cur) cur else (cur + l).let { if (it.size > MAX) it.drop(it.size - MAX).toSet() else it } }
    }

    fun clear(login: String) {
        val l = login.lowercase()
        _logins.update { if (l in it) it - l else it }
    }

    fun isRestricted(login: String) = login.lowercase() in _logins.value
}
