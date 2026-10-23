package tv.frostline.core.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A past broadcast, highlight or upload. [type] is "archive", "highlight" or "upload". */
@Serializable
data class HelixVideo(
    val id: String,
    @SerialName("stream_id") val streamId: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_login") val userLogin: String = "",
    @SerialName("user_name") val userName: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("view_count") val viewCount: Long = 0,
    val type: String = "",
    /** Helix format: "3h2m1s", "45m10s", "12s". Empty or odd while a broadcast is still being recorded. */
    val duration: String = "",
    /** Helix says "public" for every video it lists; anything else would mean a restricted one. */
    val viewable: String = "public",
) {
    val durationMs: Long get() = Durations.parseHelix(duration)

    /** Locked: Helix says so, or playing it already failed as subscriber-only in this session. */
    val restricted: Boolean get() = (viewable.isNotEmpty() && viewable != "public") || VodRestrictions.isSubscriberOnly(id)
}

@Serializable
data class HelixClip(
    val id: String,
    val url: String = "",
    @SerialName("broadcaster_id") val broadcasterId: String = "",
    @SerialName("broadcaster_name") val broadcasterName: String = "",
    @SerialName("creator_name") val creatorName: String = "",
    @SerialName("video_id") val videoId: String = "",
    @SerialName("game_id") val gameId: String = "",
    val title: String = "",
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val duration: Double = 0.0,
)

object Durations {
    private val part = Regex("""(\d+)([hms])""")

    /** "1h2m3s" -> milliseconds. Anything unparseable is 0. */
    fun parseHelix(text: String): Long {
        if (text.isBlank()) return 0
        var total = 0L
        for (m in part.findAll(text)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            total += when (m.groupValues[2]) {
                "h" -> n * 3_600
                "m" -> n * 60
                else -> n
            }
        }
        return total * 1000
    }

    /** 3_725_000 -> "1:02:05"; 125_000 -> "2:05". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** MM:SS with minutes allowed past 59, for the skip counter. */
    fun minutesSeconds(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(total / 60, total % 60)
    }
}

object Thumbnails {
    /** Twitch serves a placeholder for videos that have no thumbnail (yet). Treat those URLs as missing. */
    fun isMissing(url: String?): Boolean =
        url.isNullOrBlank() || url.contains("_404") || url.contains("404_processing") ||
            // A live archive can come back with the channel's avatar; blown up to card size it is just a blur.
            url.contains("jtv_user_pictures") || url.contains("profile_image")

    /** A sized Helix thumbnail URL, or null when the video has no usable one (use a placeholder). */
    fun video(url: String?, width: Int, height: Int): String? =
        if (isMissing(url)) null else url!!.sized(width, height)

    /**
     * A thumbnail for a video, falling back to the channel's offline image (a live archive has a blank
     * thumbnail) and then to null, which the UI draws as a placeholder.
     */
    fun videoOrOffline(url: String?, offlineImage: String?, width: Int, height: Int): String? =
        video(url, width, height) ?: offlineImage?.takeIf { !isMissing(it) }?.sized(width, height)

    /** Clip thumbnails are fixed-size URLs; same missing rule. */
    fun clip(url: String?): String? = if (isMissing(url)) null else url
}

enum class ClipPeriod(val label: String, val days: Long?) {
    Week("Top 7 days", 7),
    Month("Top 30 days", 30),
    All("All time", null);

    /** RFC 3339 start of the period relative to [now], or null for all time. */
    fun startedAt(now: Instant): String? = days?.let { now.minus(it, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC).toInstant().toString() }
}

enum class VideoKind(val label: String, val helixType: String) {
    Broadcasts("Past broadcasts", "archive"),
    Highlights("Highlights", "highlight"),
    Uploads("Uploads", "upload"),
}

/** One page of a paged list plus the cursor for the next one. */
data class PagedResult<T>(val items: List<T>, val cursor: String?)

object Counts {
    /** 950 -> "950", 1_234 -> "1.2K", 15_300 -> "15K", 2_400_000 -> "2.4M". */
    fun compact(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}K"
        n >= 1_000 -> "%.1fK".format(n / 1000.0)
        else -> n.toString()
    }

    /** ISO timestamp -> "Oct 20, 2026" (UTC); the input back when it is not a timestamp. */
    fun date(iso: String): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
            .format(Instant.parse(iso).atOffset(ZoneOffset.UTC))
    }.getOrDefault(iso)
}

/** Videos that failed to play as subscriber-only in this session, so the channel grid can show a lock on them. */
object VodRestrictions {
    private val subOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun markSubscriberOnly(id: String) { subOnly.add(id) }
    fun isSubscriberOnly(id: String): Boolean = id in subOnly
}
