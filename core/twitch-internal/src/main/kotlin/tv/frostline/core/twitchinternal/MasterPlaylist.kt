package tv.frostline.core.twitchinternal

/** One quality in Twitch's HLS master playlist. */
data class StreamVariant(
    /** Display label, e.g. "1080p60 (Source)". */
    val label: String,
    val groupId: String,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val bandwidth: Long,
    val isSource: Boolean,
    val isAudioOnly: Boolean,
    /** Video codec from the playlist's CODECS attribute ([VideoCodec.Unknown] when absent, e.g. clips). */
    val codec: VideoCodec = VideoCodec.Unknown,
) {
    val isHevc: Boolean get() = codec == VideoCodec.Hevc
}

/** Video codecs a Twitch master playlist can carry. */
enum class VideoCodec(val mime: String?, val tag: String) {
    H264("video/avc", "h264"),
    Hevc("video/hevc", "hevc"),
    Av1("video/av01", "av1"),
    Unknown(null, ""),
    ;

    companion object {
        /** From a CODECS value such as `hvc1.1.6.L150.B0,mp4a.40.2`: hvc1/hev1 = HEVC, avc1/avc3 = H.264, av01 = AV1. */
        fun fromCodecs(codecs: String?): VideoCodec {
            if (codecs.isNullOrBlank()) return Unknown
            for (part in codecs.split(',')) {
                val c = part.trim().lowercase()
                when {
                    c.startsWith("hvc1") || c.startsWith("hev1") -> return Hevc
                    c.startsWith("avc1") || c.startsWith("avc3") -> return H264
                    c.startsWith("av01") -> return Av1
                }
            }
            return Unknown
        }
    }
}

/** What this device can decode. The Android implementation is [DeviceDecoders]; tests pass a fake. */
fun interface DecoderCapability {
    fun supports(codec: VideoCodec, width: Int, height: Int, frameRate: Double): Boolean
}

/** Which codecs the usher request offers, and which variants of the answer are kept. */
object CodecPolicy {
    const val BOTH = "h265,h264"
    const val H264_ONLY = "h264"

    /** `h265,h264` (HEVC first: order matters to Twitch) when the device decodes 1440p60 HEVC in hardware, else `h264`. */
    fun supportedCodecsParam(cap: DecoderCapability): String =
        if (cap.supports(VideoCodec.Hevc, 2560, 1440, 60.0)) BOTH else H264_ONLY

    /**
     * Drops variants the device cannot play smoothly (codec not decodable, or size/rate beyond the decoder); AV1 is never
     * requested so it is always dropped. Variants of the same quality in two codecs collapse to one, HEVC first. Audio-only and
     * unknown-size/codec variants are kept. If nothing would remain, the list is returned unfiltered.
     */
    fun filter(variants: List<StreamVariant>, cap: DecoderCapability): List<StreamVariant> {
        val kept = variants.filter { v ->
            when {
                v.isAudioOnly -> true
                v.codec == VideoCodec.Av1 -> false
                v.codec == VideoCodec.Unknown || v.width <= 0 || v.height <= 0 -> true
                else -> cap.supports(v.codec, v.width, v.height, v.frameRate)
            }
        }
        if (kept.none { !it.isAudioOnly }) return variants
        return kept.sortedBy { if (it.codec == VideoCodec.Hevc) 0 else 1 }.distinctBy { it.groupId }
            .sortedByDescending { it.bandwidth }
    }
}

object MasterPlaylistParser {
    private val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attributes(line: String, prefix: String): Map<String, String> =
        attr.findAll(line.removePrefix(prefix)).associate { m ->
            m.groupValues[1] to m.groupValues[2].removeSurrounding("\"")
        }

    /** All variants in playlist order, audio-only included (flagged). */
    fun parse(text: String): List<StreamVariant> {
        val names = HashMap<String, String>()
        val out = mutableListOf<StreamVariant>()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (l in lines) {
            if (l.startsWith("#EXT-X-MEDIA:")) {
                val a = attributes(l, "#EXT-X-MEDIA:")
                if (a["TYPE"] == "VIDEO") {
                    val id = a["GROUP-ID"] ?: continue
                    names[id] = a["NAME"] ?: id
                }
            }
        }
        for (l in lines) {
            if (!l.startsWith("#EXT-X-STREAM-INF:")) continue
            val a = attributes(l, "#EXT-X-STREAM-INF:")
            val group = a["VIDEO"] ?: continue
            val bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val res = a["RESOLUTION"]?.split('x')
            val w = res?.getOrNull(0)?.toIntOrNull() ?: 0
            val h = res?.getOrNull(1)?.toIntOrNull() ?: 0
            val raw = names[group] ?: group
            val audioOnly = group == "audio_only" || (h == 0 && raw.contains("audio", ignoreCase = true))
            val source = raw.contains("(source)", ignoreCase = true)
            val label = when {
                audioOnly -> "Audio only"
                source -> raw.replace("(source)", "(Source)", ignoreCase = true)
                else -> raw
            }
            out += StreamVariant(label, group, w, h, a["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0, bandwidth, source, audioOnly, VideoCodec.fromCodecs(a["CODECS"]))
        }
        return out
    }

    /** Selectable video qualities, best first. */
    fun videoVariants(text: String): List<StreamVariant> =
        parse(text).filter { !it.isAudioOnly }.sortedByDescending { it.bandwidth }

    /** [videoVariants] without what [cap] cannot decode smoothly. */
    fun videoVariants(text: String, cap: DecoderCapability): List<StreamVariant> =
        CodecPolicy.filter(parse(text), cap).filter { !it.isAudioOnly }
}
