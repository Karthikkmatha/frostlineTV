package tv.frostline.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import tv.frostline.core.data.LiveMode

/**
 * What the next player is built for. The player is created when a player screen starts, so a changed Live mode setting
 * applies to the next stream opened. Set by the view models just before they acquire the player.
 */
object PlayerProfile {
    /** Latest Live mode setting (kept current by the app). */
    @Volatile var liveMode: LiveMode = LiveMode.Stable
    /** True for a live stream, false for a VOD or clip (always the stable buffer profile). */
    @Volatile var forLive: Boolean = true
    /** The last throughput the player measured (bits/s), 0 = none yet. Seeds the next player's estimate. */
    @Volatile var lastBandwidthBps: Long = 0L
    /** Start estimate when nothing was measured yet: high, so playback starts at Source and steps down if the link cannot hold it. */
    const val DEFAULT_START_BPS = 25_000_000L
}

/** Memory budget of the buffer: about 8-15 s of a 1080p60 stream. */
private const val STABLE_BUFFER_BYTES = 24 * 1024 * 1024

@OptIn(UnstableApi::class)
internal fun buildLoadControl(lowLatency: Boolean): DefaultLoadControl =
    DefaultLoadControl.Builder().apply {
        if (lowLatency) {
            // Small buffer: stay close to the live edge, start quickly.
            setBufferDurationsMs(3_000, 9_000, 1_500, 2_500)
        } else {
            // Keep 8-15 s downloaded ahead; resume after a stall only once 4 s are back.
            setBufferDurationsMs(8_000, 15_000, 2_500, 4_000)
            setTargetBufferBytes(STABLE_BUFFER_BYTES)
            setPrioritizeTimeOverSizeThresholds(true)
        }
    }.build()

/**
 * Steady Auto quality: step down only when the buffer is actually running low (under 8 s) or after a stall, never on one
 * slow segment, and step up only once 12 s are buffered (the link has been steady for a while). A heavier, slower
 * bandwidth average smooths the estimate.
 */
@OptIn(UnstableApi::class)
internal fun steadyAdaptation(context: Context): Pair<DefaultTrackSelector, DefaultBandwidthMeter> {
    val meter = DefaultBandwidthMeter.Builder(context)
        .setSlidingWindowMaxWeight(4_000)
        // Start at the best quality the last measurement supports (Source when unknown), not at 480p.
        .setInitialBitrateEstimate(PlayerProfile.lastBandwidthBps.takeIf { it > 0 } ?: PlayerProfile.DEFAULT_START_BPS)
        .build()
    val factory = AdaptiveTrackSelection.Factory(
        /* minDurationForQualityIncreaseMs = */ 12_000,
        /* maxDurationForQualityDecreaseMs = */ 8_000,
        /* minDurationToRetainAfterDiscardMs = */ 12_000,
        /* bandwidthFraction = */ 0.7f,
    )
    return DefaultTrackSelector(context, factory) to meter
}

/** The app-wide single-player slot. Shared by every [PlayerViewModel], so two players can never coexist. */
@OptIn(UnstableApi::class)
fun createPlayerSlot(context: Context): ExclusiveSlot<ExoPlayer> {
    val app = context.applicationContext
    return ExclusiveSlot(
        create = {
            val lowLatency = PlayerProfile.forLive && PlayerProfile.liveMode == LiveMode.LowLatency
            val (selector, meter) = steadyAdaptation(app)
            ExoPlayer.Builder(app)
                .setLoadControl(buildLoadControl(lowLatency))
                .setTrackSelector(selector)
                .setBandwidthMeter(meter)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        },
        destroy = { it.release() },
    )
}
