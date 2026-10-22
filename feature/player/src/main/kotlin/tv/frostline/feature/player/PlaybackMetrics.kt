package tv.frostline.feature.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MetricsSnapshot(
    val startupMs: Long? = null,
    val droppedFrames: Int = 0,
    val rebuffers: Int = 0,
    val rebufferMs: Long = 0,
    val videoBitrate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bandwidthBps: Long = 0,
)

/** Pure bookkeeping behind the analytics listener, so it is unit-testable without a player. */
class MetricsAccumulator(private val clock: () -> Long = System::currentTimeMillis) {
    private var loadStartedAt = -1L
    private var firstFrame = false
    private var hasBeenReady = false
    private var bufferingSince = -1L
    private var s = MetricsSnapshot()

    val snapshot: MetricsSnapshot get() = s

    /** A (re)load began: the next first frame defines the startup time. */
    fun onLoadStart() {
        loadStartedAt = clock()
        firstFrame = false
        hasBeenReady = false
        bufferingSince = -1L
        s = s.copy(startupMs = null)
    }

    fun onFirstFrame() {
        if (firstFrame || loadStartedAt < 0) return
        firstFrame = true
        s = s.copy(startupMs = clock() - loadStartedAt)
    }

    fun onDropped(frames: Int) {
        s = s.copy(droppedFrames = s.droppedFrames + frames)
    }

    fun onPlaybackState(state: Int) {
        when (state) {
            Player.STATE_READY -> {
                if (bufferingSince >= 0) {
                    s = s.copy(rebufferMs = s.rebufferMs + (clock() - bufferingSince))
                    bufferingSince = -1L
                }
                hasBeenReady = true
            }
            Player.STATE_BUFFERING -> if (hasBeenReady && bufferingSince < 0) {
                bufferingSince = clock()
                s = s.copy(rebuffers = s.rebuffers + 1)
            }
            else -> Unit
        }
    }

    fun onVideoFormat(bitrate: Int, width: Int, height: Int) {
        s = s.copy(videoBitrate = bitrate, width = width, height = height)
    }

    fun onBandwidth(bps: Long) {
        s = s.copy(bandwidthBps = bps)
    }
}

/** Logs startup time, dropped frames, rebuffers and the selected bitrate. Never logs URLs or tokens. */
@OptIn(UnstableApi::class)
class PlaybackMetrics(private val acc: MetricsAccumulator = MetricsAccumulator()) : AnalyticsListener {
    private val _state = MutableStateFlow(acc.snapshot)
    val state: StateFlow<MetricsSnapshot> = _state.asStateFlow()

    fun onLoadStart() {
        acc.onLoadStart()
        publish()
    }

    private fun publish() {
        _state.value = acc.snapshot
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        acc.onFirstFrame()
        publish()
        Log.i(TAG, "startup=${acc.snapshot.startupMs}ms")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        acc.onDropped(droppedFrames)
        publish()
        Log.i(TAG, "dropped=$droppedFrames in ${elapsedMs}ms total=${acc.snapshot.droppedFrames}")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val before = acc.snapshot.rebuffers
        acc.onPlaybackState(state)
        publish()
        if (acc.snapshot.rebuffers != before) Log.i(TAG, "rebuffer #${acc.snapshot.rebuffers}")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        acc.onVideoFormat(format.bitrate.coerceAtLeast(0), format.width, format.height)
        publish()
        Log.i(TAG, "video ${format.width}x${format.height} bitrate=${format.bitrate}")
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        acc.onBandwidth(bitrateEstimate)
        publish()
    }

    private companion object {
        const val TAG = "FrostlinePlayer"
    }
}
