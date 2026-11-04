package tv.frostline.core.twitchinternal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One quality of a past broadcast's seek-preview storyboard: [images] are sprite sheets (absolute URLs) of [cols] x [rows]
 * frames, each [frameWidth] x [frameHeight] px, one frame per [intervalSec] of video, [count] frames in all.
 */
class StoryboardSheetSet(
    val quality: String,
    val images: List<String>,
    val cols: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val intervalSec: Double,
    val count: Int,
) {
    val perSheet: Int get() = cols * rows

    /** Frames that really exist (the last sheet is usually not full). */
    val totalFrames: Int get() = when {
        images.isEmpty() -> 0
        images.size == 1 -> if (count in 1..perSheet) count else perSheet
        count > perSheet -> count.coerceAtMost(images.size * perSheet)
        else -> images.size * perSheet
    }

    /** Frame number nearest [positionMs]: one frame per interval, clamped to the frames that exist. */
    fun frameIndex(positionMs: Long): Int {
        val last = totalFrames - 1
        if (last < 0) return 0
        return Math.floorDiv(positionMs.coerceAtLeast(0L), (intervalSec * 1000).toLong().coerceAtLeast(1L)).toInt().coerceIn(0, last)
    }

    fun sheetOf(frame: Int): Int = (frame / perSheet).coerceIn(0, (images.size - 1).coerceAtLeast(0))

    /** Column and row of [frame] inside its sheet. */
    fun cellOf(frame: Int): Pair<Int, Int> {
        val inSheet = frame % perSheet
        return (inSheet % cols) to (inSheet / cols)
    }

    override fun toString(): String = "Storyboard($quality, ${images.size} sheets, ${cols}x$rows of ${frameWidth}x$frameHeight, every ${intervalSec}s)"
}

/** Storyboard JSON parsing. Defensive: any malformed or missing part yields null (no thumbnails), never an exception. */
object Storyboards {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [text] is the JSON behind GQL `video(id:) { seekPreviewsURL }`: an array of quality entries (an object holding such an array
     * is accepted too) with `images`, `cols`, `rows`, `width`, `height`, `interval` and `count`, numbers possibly as strings.
     * [infoUrl] is where it was fetched, to resolve relative image names. [durationSec] fills a missing `interval`.
     * Prefers the "low" quality (small sheets), else the entry with the smallest frames.
     */
    fun parse(text: String, infoUrl: String, durationSec: Long = 0): StoryboardSheetSet? {
        val root: JsonElement = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val entries: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root) + root.values.flatMap { v -> (v as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }
            else -> emptyList()
        }
        val sets = entries.mapNotNull { entry(it, infoUrl, durationSec) }
        if (sets.isEmpty()) return null
        return sets.firstOrNull { it.quality.equals("low", ignoreCase = true) }
            ?: sets.minByOrNull { it.frameWidth.toLong() * it.frameHeight }
    }

    private fun entry(o: JsonObject, infoUrl: String, durationSec: Long): StoryboardSheetSet? {
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            .map { absolute(infoUrl, it) }
        if (images.isEmpty()) return null
        val cols = int(o["cols"]) ?: return null
        val rows = int(o["rows"]) ?: return null
        val w = int(o["width"]) ?: return null
        val h = int(o["height"]) ?: return null
        if (cols <= 0 || rows <= 0 || w <= 0 || h <= 0) return null
        val count = int(o["count"]) ?: 0
        val perSheet = cols * rows
        val frames = when {
            images.size == 1 -> if (count in 1..perSheet) count else perSheet
            count > perSheet -> count
            else -> images.size * perSheet
        }
        val interval = num(o["interval"])?.takeIf { it > 0 }
            ?: if (durationSec > 0 && frames > 0) durationSec.toDouble() / frames else return null
        return StoryboardSheetSet(
            quality = (o["quality"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            images = images, cols = cols, rows = rows, frameWidth = w, frameHeight = h, intervalSec = interval, count = count,
        )
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
    private fun int(e: JsonElement?): Int? = num(e)?.takeIf { it.isFinite() && it < Int.MAX_VALUE }?.toInt()

    /** The field names of the first quality entry (comma-separated, at most 12): shows a format surprise in the debug overlay. */
    fun fieldNames(text: String): String {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return "notjson"
        val first = when (root) {
            is JsonArray -> root.firstOrNull() as? JsonObject
            is JsonObject -> root
            else -> null
        } ?: return "noobject"
        return first.keys.take(12).joinToString(",")
    }

    /** An image name relative to the info document's directory, or already absolute. */
    fun absolute(infoUrl: String, name: String): String {
        if (name.startsWith("http://") || name.startsWith("https://")) return name
        if (name.startsWith("//")) return "https:$name"
        val base = infoUrl.substringBefore('?').substringBeforeLast('/', "")
        return if (name.startsWith("/")) infoUrl.substringBefore("://") + "://" + infoUrl.substringAfter("://").substringBefore('/') + name
        else "$base/$name"
    }

}
