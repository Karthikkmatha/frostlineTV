package tv.frostline.feature.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import tv.frostline.core.twitchinternal.StreamVariant

/** Quality pinning for Twitch HLS (live and VOD): the master playlist's variants vs. the tracks ExoPlayer exposes. */
@OptIn(UnstableApi::class)
internal object QualityTracks {
    /** Back to adaptive. */
    fun clear(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
    }

    /** Pins the track closest to [v] (same height first, then nearest bitrate). False if the tracks are not known yet. */
    fun pin(p: ExoPlayer, v: StreamVariant): Boolean {
        val group = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 } ?: return false
        var best = -1
        var bestScore = Long.MAX_VALUE
        for (i in 0 until group.length) {
            val f = group.getTrackFormat(i)
            val heightPenalty = if (v.height > 0 && f.height != v.height) 1_000_000_000L else 0L
            val score = heightPenalty + kotlin.math.abs((f.bitrate.takeIf { it > 0 } ?: 0).toLong() - v.bandwidth)
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return false
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(best))).build()
        return true
    }
}
