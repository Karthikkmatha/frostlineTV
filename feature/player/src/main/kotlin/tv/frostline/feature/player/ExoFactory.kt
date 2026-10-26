package tv.frostline.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
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
internal fun buildLoadControl(lowLatency: Boolean, live: Boolean = true): DefaultLoadControl =
    DefaultLoadControl.Builder().apply {
        // Nothing seeks back into played media on a live stream, so keep none of it; a VOD keeps 10 s for the -10 s button.
        setBackBuffer(if (live) 0 else 10_000, false)
        if (lowLatency) {
            // Small buffer: stay close to the live edge (offset target 5 s, range 4-8 s), start quickly. 3 s is the floor the
            // player tops up from, 9 s the ceiling; playback resumes after a stall at 2.5 s.
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

/**
 * Renderers for what Twitch streams carry: video and audio only. Text, metadata (ID3 timed metadata), camera-motion and image
 * renderers are never built, so no thread-handler or renderer is created for tracks the app never shows.
 * Speed changes (the live catch-up) are done by the platform AudioTrack, not by a Java Sonic processor on the playback
 * thread, and a codec failure falls back to the next decoder.
 */
@OptIn(UnstableApi::class)
private class LeanRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        setEnableAudioTrackPlaybackParams(true)
    }

    override fun buildTextRenderers(
        context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>,
    ) = Unit

    override fun buildMetadataRenderers(
        context: Context, output: MetadataOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>,
    ) = Unit

    override fun buildCameraMotionRenderers(context: Context, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit
}

/** The app-wide single-player slot. Shared by every [PlayerViewModel], so two players can never coexist. */
@OptIn(UnstableApi::class)
fun createPlayerSlot(context: Context): ExclusiveSlot<ExoPlayer> {
    val app = context.applicationContext
    return ExclusiveSlot(
        create = {
            val lowLatency = PlayerProfile.forLive && PlayerProfile.liveMode == LiveMode.LowLatency
            val (selector, meter) = steadyAdaptation(app)
            ExoPlayer.Builder(app, LeanRenderersFactory(app))
                .setLoadControl(buildLoadControl(lowLatency, PlayerProfile.forLive))
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
