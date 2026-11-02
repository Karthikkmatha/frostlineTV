package tv.frostline.core.twitchinternal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardware video decoders of this device, from [MediaCodecList]. Every answer is cached, so the (slow) codec-list query runs
 * once per codec and size; [warmUp] does the 1440p60 HEVC query off the main thread at startup.
 */
object DeviceDecoders : DecoderCapability {
    private val cache = ConcurrentHashMap<String, Boolean>()
    private val names = ConcurrentHashMap<String, String>()

    /** Starts the 1440p60 HEVC / H.264 queries on a background thread. Safe to call more than once. */
    fun warmUp() {
        Thread {
            runCatching {
                supports(VideoCodec.Hevc, 2560, 1440, 60.0)
                supports(VideoCodec.H264, 2560, 1440, 60.0)
            }
        }.apply { name = "decoder-probe"; isDaemon = true }.start()
    }

    override fun supports(codec: VideoCodec, width: Int, height: Int, frameRate: Double): Boolean {
        val mime = codec.mime ?: return true
        val fps = if (frameRate > 0) frameRate else 30.0
        return cache.getOrPut("$mime/${width}x$height@${fps.toInt()}") { query(mime, width, height, fps) }
    }

    /** Name of the hardware decoder found for [codec], for diagnostics; null if none. */
    fun decoderName(codec: VideoCodec): String? = names[codec.mime ?: return null]

    private fun query(mime: String, width: Int, height: Int, fps: Double): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            if (info.isEncoder || !isHardware(info) || info.supportedTypes.none { it.equals(mime, true) }) return@any false
            val v = info.getCapabilitiesForType(mime).videoCapabilities ?: return@any false
            val ok = v.areSizeAndRateSupported(width, height, fps)
            if (ok) names.putIfAbsent(mime, info.name)
            ok
        }
    } catch (e: Exception) {
        false
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)
}
