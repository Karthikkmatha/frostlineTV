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
)

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
            out += StreamVariant(label, group, w, h, a["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0, bandwidth, source, audioOnly)
        }
        return out
    }

    /** Selectable video qualities, best first. */
    fun videoVariants(text: String): List<StreamVariant> =
        parse(text).filter { !it.isAudioOnly }.sortedByDescending { it.bandwidth }
}
