package tv.frostline.feature.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A piece of a message body: plain text or a shared [Emote]. */
sealed interface Part {
    class Text(val text: String) : Part
    class EmoteRef(val emote: Emote) : Part
}

/** Badge image resolved to a URL. */
class BadgeImage(val set: String, val url: String)

@androidx.compose.runtime.Immutable
sealed interface ChatLine {
    /** Stable LazyColumn key. */
    val key: String
}

/**
 * A fully prepared message. Built once on a background thread: the text is tokenised into [parts]
 * (emote objects are shared, never copied) so the UI does no parsing work while scrolling.
 */
@androidx.compose.runtime.Immutable
class ChatMessage(
    override val key: String,
    val tsMs: Long,
    val time: String,
    val userId: String,
    val login: String,
    val displayName: String,
    val colorArgb: Int,
    val badges: List<BadgeImage>,
    val parts: List<Part>,
    val action: Boolean,
    val reply: ReplyInfo?,
    val header: String?,
    val highlight: Boolean,
    val mine: Boolean = false,
) : ChatLine

@androidx.compose.runtime.Immutable
class SystemLine(
    override val key: String,
    val time: String,
    val text: String,
    val kind: ChatEvent.System.Kind,
) : ChatLine

object ChatText {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    fun time(ms: Long): String = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private val palette = intArrayOf(
        0xFFFF6B6B.toInt(), 0xFFFFA45C.toInt(), 0xFFFFD866.toInt(), 0xFF9BE564.toInt(), 0xFF5CE0B8.toInt(),
        0xFF5CC8FF.toInt(), 0xFF8FA8FF.toInt(), 0xFFB794F6.toInt(), 0xFFF58BE0.toInt(), 0xFFFF8FA3.toInt(),
    )

    /** A stable fallback colour for users without a chosen one. */
    fun defaultColor(login: String): Int = palette[(login.hashCode() and 0x7fffffff) % palette.size]

    /** Brightens colours too dark to read on the near-black chat background (WCAG-style relative luminance). */
    fun readable(argb: Int): Int {
        var c = argb or 0xFF000000.toInt()
        repeat(6) {
            if (luminance(c) >= 0.25) return c
            c = mix(c, 0xFFFFFFFF.toInt(), 0.22f)
        }
        return c
    }

    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun m(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}

/** What the builder needs to resolve a message. Swapped atomically when emotes/badges arrive or settings change. */
class ResolveContext(
    val thirdParty: Map<String, Emote>,
    val badgeUrls: Map<String, String>,
    val maxBadges: Int = 3,
)

object MessageBuilder {
    fun build(
        raw: RawMessage,
        ctx: ResolveContext,
        mine: Boolean = false,
        fallback: Map<String, Emote> = emptyMap(),
        /** Replaces the wall-clock time label (chat replay shows the position in the video). */
        timeLabel: String? = null,
    ): ChatMessage =
        ChatMessage(
            key = raw.id,
            tsMs = raw.tsMs,
            time = timeLabel ?: ChatText.time(raw.tsMs),
            userId = raw.userId,
            login = raw.login,
            displayName = raw.displayName,
            colorArgb = ChatText.readable(raw.colorArgb ?: ChatText.defaultColor(raw.login)),
            badges = raw.badges.mapNotNull { b -> ctx.badgeUrls["${b.set}/${b.version}"]?.let { BadgeImage(b.set, it) } }.take(ctx.maxBadges),
            parts = tokenize(raw.text, raw.emotes, ctx.thirdParty, fallback),
            action = raw.action,
            reply = raw.reply,
            header = raw.systemHeader,
            highlight = raw.highlighted || raw.announcement,
            mine = mine,
        )

    /**
     * Splits [text] into plain text and emotes. Twitch ranges (code-point indices, end inclusive) win;
     * remaining words are looked up in [thirdParty]. Adjacent plain text is merged so a message with no
     * emotes is a single [Part.Text].
     */
    fun tokenize(
        text: String,
        ranges: List<EmoteRange>,
        thirdParty: Map<String, Emote>,
        fallback: Map<String, Emote> = emptyMap(),
    ): List<Part> {
        if (text.isEmpty()) return emptyList()
        val parts = ArrayList<Part>(4)
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                parts += Part.Text(pending.toString())
                pending.setLength(0)
            }
        }
        val cpCount = text.codePointCount(0, text.length)

        fun addWords(seg: String) {
            if (seg.isEmpty()) return
            if (thirdParty.isEmpty() && fallback.isEmpty()) {
                pending.append(seg)
                return
            }
            var i = 0
            while (i < seg.length) {
                if (seg[i] == ' ') {
                    pending.append(' ')
                    i++
                    continue
                }
                var j = i
                while (j < seg.length && seg[j] != ' ') j++
                val word = seg.substring(i, j)
                val e = thirdParty[word] ?: fallback[word]
                if (e != null) {
                    flush()
                    parts += Part.EmoteRef(e)
                } else {
                    pending.append(word)
                }
                i = j
            }
        }

        var cp = 0
        var charPos = 0
        for (r in ranges) {
            if (r.start < cp || r.end >= cpCount) continue // overlapping or out-of-range: ignore
            val startChar = text.offsetByCodePoints(charPos, r.start - cp)
            val endChar = text.offsetByCodePoints(startChar, r.end - r.start + 1)
            addWords(text.substring(charPos, startChar))
            flush()
            parts += Part.EmoteRef(twitchEmote(r.id, text.substring(startChar, endChar)))
            charPos = endChar
            cp = r.end + 1
        }
        addWords(text.substring(charPos))
        flush()
        return parts
    }

    // Twitch emotes are tiny and the same ids repeat constantly: intern them so each id is one object.
    private val twitchCache = object : LinkedHashMap<String, Emote>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Emote>?) = size > 1024
    }

    @Synchronized
    private fun twitchEmote(id: String, code: String): Emote =
        twitchCache.getOrPut(id) { Emote(id, code, TwitchEmotes.url(id), EmoteProvider.Twitch) }
}
