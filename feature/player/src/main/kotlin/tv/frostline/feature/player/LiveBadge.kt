package tv.frostline.feature.player

enum class LiveState { Unknown, LowLatency, Behind }

data class LiveBadge(val text: String, val state: LiveState)

/** Latency at or under this is "at the live edge". */
const val LIVE_EDGE_MS = 6_000L

/** The low-latency live-edge indicator text for a player's current live offset. */
fun liveBadge(offsetMs: Long?): LiveBadge = when {
    offsetMs == null || offsetMs < 0 -> LiveBadge("LIVE", LiveState.Unknown)
    offsetMs <= LIVE_EDGE_MS -> LiveBadge("LIVE  %.1f s".format(offsetMs / 1000.0), LiveState.LowLatency)
    else -> LiveBadge("%d s behind live".format(offsetMs / 1000), LiveState.Behind)
}
