package tv.frostline.feature.player

import androidx.media3.common.PlaybackException

/**
 * What to do when live playback fails or stalls, and when to try again: instantly for a fallen-behind live window,
 * otherwise with a doubling delay (1 s up to 30 s). Gives up after [maxAttempts] failures in a row; steady playback resets it.
 */
internal class PlaybackRecovery(private val maxAttempts: Int = 10) {
    enum class Action { SeekToLive, Reload, RebuildPlayer, GiveUp }

    var attempts = 0
        private set

    /** True from the first failure until playback has been steady again: loads that fail meanwhile keep retrying. */
    var recovering = false
        private set

    fun classify(errorCode: Int): Action = when (errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Action.SeekToLive
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> Action.RebuildPlayer
        else -> Action.Reload // network, bad HTTP status (expired token, vanished playlist), parsing, anything else
    }

    /** Registers a failure and returns the action, or [Action.GiveUp] when the attempts are used up. */
    fun onFailure(action: Action): Action {
        recovering = true
        if (action == Action.SeekToLive && attempts < 3) { attempts++; return action } // a few instant seeks, then treat it as a reload
        attempts++
        return if (attempts > maxAttempts) Action.GiveUp else if (action == Action.SeekToLive) Action.Reload else action
    }

    /** Delay before the retry that follows the last [onFailure]. */
    fun delayMs(action: Action): Long =
        if (action == Action.SeekToLive) 0L else minOf(30_000L, 1_000L shl (attempts - 1).coerceIn(0, 5))

    /** Playback has been steady for a while. */
    fun onSteady() { attempts = 0; recovering = false }

    /** A person asked (open, refresh, switch channel): start clean. */
    fun reset() = onSteady()

    companion object {
        /** A buffering spell this long means the playlist or connection is stale. */
        const val STALL_MS = 25_000L
        /** Playing this long without an error counts as steady. */
        const val STEADY_MS = 15_000L
    }
}
