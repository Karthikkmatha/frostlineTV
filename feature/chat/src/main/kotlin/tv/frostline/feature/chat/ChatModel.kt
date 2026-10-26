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
    /** Arrival order (process-wide, increasing): lets the newest lines be told apart without their list index. */
    val seq: Long = 0L,
    /** The signed-in user's login when this message @mentions them (lower case), else null. */
    val mentionLogin: String? = null,
) : ChatLine {
    @Volatile private var prepared: PreparedLine? = null

    /**
     * The drawable form of this line (annotated text, inline-image slots) for the given size and timestamp setting.
     * Built once, normally by the chat view model off the main thread before the line is published, and then shared;
     * a settings change builds it again.
     */
    fun prepared(fontSp: Int, timestamps: Boolean): PreparedLine {
        prepared?.let { if (it.fontSp == fontSp && it.timestamps == timestamps) return it }
        return LineBuilder.prepare(this, fontSp, timestamps).also { prepared = it }
    }
}

@androidx.compose.runtime.Immutable
class SystemLine(
    override val key: String,
    val time: String,
    val text: String,
    val kind: ChatEvent.System.Kind,
) : ChatLine

object ChatText {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Messages arrive many per second but the label only changes once a minute: keep the last one.
    @Volatile private var lastMinute = Long.MIN_VALUE
    @Volatile private var lastLabel = ""

    fun time(ms: Long): String {
        val minute = Math.floorDiv(ms, 60_000L)
        if (minute == lastMinute) return lastLabel
        val label = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        lastLabel = label
        lastMinute = minute
        return label
    }

    private val palette = intArrayOf(
        0xFFFF6B6B.toInt(), 0xFFFFA45C.toInt(), 0xFFFFD866.toInt(), 0xFF9BE564.toInt(), 0xFF5CE0B8.toInt(),
        0xFF5CC8FF.toInt(), 0xFF8FA8FF.toInt(), 0xFFB794F6.toInt(), 0xFFF58BE0.toInt(), 0xFFFF8FA3.toInt(),
    )

    /** A stable fallback colour for users without a chosen one. */
    fun defaultColor(login: String): Int = palette[(login.hashCode() and 0x7fffffff) % palette.size]

    /**
     * Contrast floor for names on the #121018 chat background: a colour whose relative luminance is below 0.25 (about 6:1)
     * is lightened in HSL, keeping its hue and saturation. Brighter colours are returned unchanged.
     */
    fun readable(argb: Int): Int {
        val c = argb or 0xFF000000.toInt()
        if (luminance(c) >= 0.25) return c
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var l = (mx + mn) / 2f
        val s = if (d == 0f) 0f else d / (1f - Math.abs(2f * l - 1f))
        val h = when {
            d == 0f -> 0f
            mx == r -> (((g - b) / d) % 6f + 6f) % 6f
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } // hue in sixths of the circle
        var out = c
        repeat(24) {
            l = (l + 0.04f).coerceAtMost(0.95f)
            out = hslToArgb(h, s, l)
            if (luminance(out) >= 0.25) return out
        }
        return out
    }

    private fun hslToArgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs(h % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** [own] (lower case) when [text] contains `@own` as a whole word (case-insensitive), else null. */
    fun mentionOf(text: String, own: String?): String? {
        if (own.isNullOrEmpty()) return null
        var from = 0
        while (true) {
            val i = text.indexOf('@', from)
            if (i < 0) return null
            val end = i + 1 + own.length
            if (end <= text.length && text.regionMatches(i + 1, own, 0, own.length, ignoreCase = true) &&
                (end == text.length || !(text[end].isLetterOrDigit() || text[end] == '_'))
            ) return own.lowercase()
            from = i + 1
        }
    }

    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

}

/** What the builder needs to resolve a message. Swapped atomically when emotes/badges arrive or settings change. */
class ResolveContext(
    val thirdParty: Map<String, Emote>,
    val badgeUrls: Map<String, String>,
    val maxBadges: Int = 3,
    /** The signed-in user's login: messages that @mention it get a highlighted row. */
    val ownLogin: String? = null,
) {
    /** Shortest and longest emote code: a word outside this range cannot be an emote, so it is never cut out for a lookup. */
    val minCodeLen: Int
    val maxCodeLen: Int

    init {
        var lo = Int.MAX_VALUE
        var hi = 0
        for (k in thirdParty.keys) { if (k.length < lo) lo = k.length; if (k.length > hi) hi = k.length }
        minCodeLen = lo
        maxCodeLen = hi
    }
}

object MessageBuilder {
    private val seqGen = java.util.concurrent.atomic.AtomicLong()

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
            parts = tokenize(raw.text, raw.emotes, ctx.thirdParty, fallback, ctx.minCodeLen, ctx.maxCodeLen),
            action = raw.action,
            reply = raw.reply,
            header = raw.systemHeader,
            highlight = raw.highlighted || raw.announcement,
            mine = mine,
            seq = seqGen.incrementAndGet(),
            mentionLogin = if (!mine) ChatText.mentionOf(raw.text, ctx.ownLogin) else null,
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
        /** Length bounds of [thirdParty] codes; applied only when [fallback] is empty. */
        minCode: Int = 0,
        maxCode: Int = Int.MAX_VALUE,
    ): List<Part> {
        val lenFilter = fallback.isEmpty()
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
                if (lenFilter && (j - i < minCode || j - i > maxCode)) { pending.append(seg, i, j); i = j; continue }
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
