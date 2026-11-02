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
    /** "hevc", "h264", "av1" or "" while unknown. */
    val codec: String = "",
    /** Name of the decoder the video renderer initialised, "" while unknown. */
    val decoder: String = "",
)

/** The debug overlay's video line: `video    2560x1440 @ 12000 kbps hevc (c2.mtk.hevc.decoder)`. */
fun videoLine(m: MetricsSnapshot): String =
    "video    ${m.width}x${m.height} @ ${m.videoBitrate / 1000} kbps" +
        (if (m.codec.isNotEmpty()) " ${m.codec}" else "") +
        (if (m.decoder.isNotEmpty()) " (${m.decoder})" else "")

/** Short codec name from a sample MIME type. */
fun codecName(mime: String?): String = when (mime) {
    "video/hevc" -> "hevc"
    "video/avc" -> "h264"
    "video/av01" -> "av1"
    null -> ""
    else -> mime.substringAfter('/')
}

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

    fun onVideoFormat(bitrate: Int, width: Int, height: Int, codec: String = "") {
        s = s.copy(videoBitrate = bitrate, width = width, height = height, codec = codec)
    }

    fun onDecoder(name: String) {
        s = s.copy(decoder = name)
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
        acc.onVideoFormat(format.bitrate.coerceAtLeast(0), format.width, format.height, codecName(format.sampleMimeType))
        publish()
        Log.i(TAG, "video ${format.width}x${format.height} bitrate=${format.bitrate}")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        acc.onDecoder(decoderName)
        publish()
        Log.i(TAG, "decoder=$decoderName")
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        acc.onBandwidth(bitrateEstimate)
        if (bitrateEstimate > 0) PlayerProfile.lastBandwidthBps = bitrateEstimate
        publish()
    }

    private companion object {
        const val TAG = "FrostlinePlayer"
    }
}
