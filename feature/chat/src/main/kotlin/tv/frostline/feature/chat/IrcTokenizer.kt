package tv.frostline.feature.chat

/**
 * The chat hot path: turns a websocket frame into [ChatEvent]s without building an intermediate [IrcMessage], a tag map,
 * split lists or regex matches. PRIVMSG (almost all traffic) is read by index: the tag section is scanned once, the keys we
 * use are recognised by length and `regionMatches`, and values are cut out with `substring` only for the fields a
 * message really keeps. Everything else (PING, ROOMSTATE, USERNOTICE, ...) is rare and takes the general
 * [IrcParser] + [ChatEventParser] route, so behaviour is identical.
 *
 * One instance per connection: the span scratch array is reused, so it must not be called from two threads at once
 * (a websocket delivers frames one at a time).
 */
class IrcTokenizer {
    /** start/end pairs (end exclusive, -1 = tag absent) of the tags we read, indexed by the K_* constants. */
    private val spans = IntArray(KEYS * 2)

    /** Parses every CRLF-separated line of [frame] and hands each resulting event to [sink]. */
    fun parseFrame(frame: String, nowMs: Long, sink: (ChatEvent) -> Unit) {
        var start = 0
        val n = frame.length
        while (start <= n) {
            var end = frame.indexOf('\n', start)
            if (end < 0) end = n
            parseLine(frame, start, end, nowMs, sink)
            start = end + 1
        }
    }

    private fun parseLine(f: String, from: Int, to: Int, nowMs: Long, sink: (ChatEvent) -> Unit) {
        var end = to
        while (end > from && (f[end - 1] == '\r' || f[end - 1] == '\n')) end--
        var i = from
        while (i < end && f[i] == ' ') i++ // blank or space-only lines fall out below
        if (i >= end) return
        val lineStart = i
        var tagS = -1
        var tagE = -1
        if (f[i] == '@') {
            val sp = f.indexOf(' ', i)
            if (sp < 0 || sp >= end) return
            tagS = i + 1
            tagE = sp
            i = sp + 1
            while (i < end && f[i] == ' ') i++
        }
        var preS = -1
        var preE = -1
        if (i < end && f[i] == ':') {
            val sp = f.indexOf(' ', i)
            if (sp < 0 || sp >= end) return
            preS = i + 1
            preE = sp
            i = sp + 1
            while (i < end && f[i] == ' ') i++
        }
        if (i >= end) return
        val cmdS = i
        while (i < end && f[i] != ' ') i++
        val isPrivmsg = i - cmdS == 7 && f.regionMatches(cmdS, "PRIVMSG", 0, 7, ignoreCase = true)
        if (!isPrivmsg) {
            // Rare commands: the general parser (it re-parses from the start of the line).
            IrcParser.parse(f.substring(lineStart, end))?.let { ChatEventParser.parse(it, nowMs) }?.let(sink)
            return
        }
        privmsg(f, tagS, tagE, preS, preE, cmdS, end, nowMs)?.let(sink)
    }

    private fun privmsg(f: String, tagS: Int, tagE: Int, preS: Int, preE: Int, cmdS: Int, end: Int, nowMs: Long): ChatEvent? {
        // Nick: the prefix up to '!' (or all of it).
        if (preS < 0) return null
        var nickE = preE
        for (k in preS until preE) if (f[k] == '!') { nickE = k; break }
        if (nickE == preS) return null
        // Text: after the first " :" at or after the command.
        val colon = f.indexOf(" :", cmdS)
        if (colon < 0 || colon + 2 > end) return null
        var text = f.substring(colon + 2, end)
        var action = false
        if (text.startsWith("\u0001ACTION ")) {
            action = true
            text = if (text.endsWith("\u0001")) text.substring(8, text.length - 1) else text.substring(8)
        }
        val login = f.substring(preS, nickE)

        java.util.Arrays.fill(spans, -1)
        if (tagS >= 0) scanTags(f, tagS, tagE)

        val id = value(f, K_ID) ?: ChatEventParser.localId("m")
        val displayName = (value(f, K_DISPLAY_NAME) ?: "").ifEmpty { login }
        val reply = value(f, K_REPLY_ID)?.let { pid ->
            val rl = value(f, K_REPLY_LOGIN).orEmpty()
            ReplyInfo(pid, rl, (value(f, K_REPLY_DISPLAY) ?: "").ifEmpty { rl }, value(f, K_REPLY_BODY).orEmpty())
        }
        val msgId = spans[K_MSG_ID * 2]
        return ChatEvent.Message(
            RawMessage(
                id = id,
                tsMs = longOf(f, spans[K_TS * 2], spans[K_TS * 2 + 1]) ?: nowMs,
                userId = value(f, K_USER_ID).orEmpty(),
                login = login,
                displayName = displayName,
                colorArgb = colorOf(f, spans[K_COLOR * 2], spans[K_COLOR * 2 + 1]),
                badges = badgesOf(f, spans[K_BADGES * 2], spans[K_BADGES * 2 + 1]),
                text = text,
                action = action,
                emotes = emotesOf(f, spans[K_EMOTES * 2], spans[K_EMOTES * 2 + 1]),
                reply = reply,
                firstMessage = isOne(f, K_FIRST),
                highlighted = msgId >= 0 && spans[K_MSG_ID * 2 + 1] - msgId == 19 && f.regionMatches(msgId, "highlighted-message", 0, 19),
            ),
        )
    }

    /** One pass over `k=v;k=v;...` recording the span of each value we use. A repeated key keeps its last value, as in [IrcParser.parseTags]. */
    private fun scanTags(f: String, s: Int, e: Int) {
        var ps = s
        while (ps < e) {
            var pe = f.indexOf(';', ps)
            if (pe < 0 || pe > e) pe = e
            if (pe > ps) {
                var eq = -1
                for (k in ps until pe) if (f[k] == '=') { eq = k; break }
                val keyEnd = if (eq < 0) pe else eq
                val idx = keyIndex(f, ps, keyEnd - ps)
                if (idx >= 0) {
                    spans[idx * 2] = if (eq < 0) pe else eq + 1
                    spans[idx * 2 + 1] = pe
                }
            }
            ps = pe + 1
        }
    }

    private fun keyIndex(f: String, s: Int, len: Int): Int = when (len) {
        2 -> if (f.regionMatches(s, "id", 0, 2)) K_ID else -1
        5 -> if (f.regionMatches(s, "color", 0, 5)) K_COLOR else -1
        6 -> when {
            f.regionMatches(s, "badges", 0, 6) -> K_BADGES
            f.regionMatches(s, "emotes", 0, 6) -> K_EMOTES
            f.regionMatches(s, "msg-id", 0, 6) -> K_MSG_ID
            else -> -1
        }
        7 -> if (f.regionMatches(s, "user-id", 0, 7)) K_USER_ID else -1
        9 -> if (f.regionMatches(s, "first-msg", 0, 9)) K_FIRST else -1
        11 -> if (f.regionMatches(s, "tmi-sent-ts", 0, 11)) K_TS else -1
        12 -> if (f.regionMatches(s, "display-name", 0, 12)) K_DISPLAY_NAME else -1
        19 -> if (f.regionMatches(s, "reply-parent-msg-id", 0, 19)) K_REPLY_ID else -1
        21 -> if (f.regionMatches(s, "reply-parent-msg-body", 0, 21)) K_REPLY_BODY else -1
        23 -> if (f.regionMatches(s, "reply-parent-user-login", 0, 23)) K_REPLY_LOGIN else -1
        25 -> if (f.regionMatches(s, "reply-parent-display-name", 0, 25)) K_REPLY_DISPLAY else -1
        else -> -1
    }

    /** The tag's value (IRCv3 escapes resolved), or null when the tag is absent. */
    private fun value(f: String, key: Int): String? {
        val s = spans[key * 2]
        if (s < 0) return null
        val e = spans[key * 2 + 1]
        for (k in s until e) if (f[k] == '\\') return IrcParser.unescape(f.substring(s, e))
        return f.substring(s, e)
    }

    private fun isOne(f: String, key: Int): Boolean {
        val s = spans[key * 2]
        return s >= 0 && spans[key * 2 + 1] - s == 1 && f[s] == '1'
    }

    private companion object {
        const val K_ID = 0
        const val K_DISPLAY_NAME = 1
        const val K_COLOR = 2
        const val K_BADGES = 3
        const val K_EMOTES = 4
        const val K_USER_ID = 5
        const val K_TS = 6
        const val K_REPLY_ID = 7
        const val K_REPLY_LOGIN = 8
        const val K_REPLY_DISPLAY = 9
        const val K_REPLY_BODY = 10
        const val K_FIRST = 11
        const val K_MSG_ID = 12
        const val KEYS = 13

        /** Non-negative decimal in f[s, e), or null (absent, empty, a sign, non-digit or overflow). */
        fun longOf(f: String, s: Int, e: Int): Long? {
            if (s < 0 || e <= s || e - s > 18) return null
            var v = 0L
            for (k in s until e) {
                val d = f[k] - '0'
                if (d < 0 || d > 9) return null
                v = v * 10 + d
            }
            return v
        }

        fun intOf(f: String, s: Int, e: Int): Int? = longOf(f, s, e)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

        /** `#RRGGBB` -> opaque ARGB, else null (same rules as [ChatEventParser.parseColor]). */
        fun colorOf(f: String, s: Int, e: Int): Int? {
            if (s < 0 || e - s != 7 || f[s] != '#') return null
            var v = 0
            for (k in s + 1 until e) {
                val c = f[k]
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
                v = v * 16 + d
            }
            return 0xFF000000.toInt() or v
        }

        /** `broadcaster/1,subscriber/12`. Same rules as [ChatEventParser.parseBadges]: a piece without a '/' after its first char is skipped. */
        fun badgesOf(f: String, s: Int, e: Int): List<Badge> {
            if (s < 0 || e <= s) return emptyList()
            var out: ArrayList<Badge>? = null
            var ps = s
            while (ps <= e) {
                var pe = f.indexOf(',', ps)
                if (pe < 0 || pe > e) pe = e
                var slash = -1
                for (k in ps until pe) if (f[k] == '/') { slash = k; break }
                if (slash > ps) (out ?: ArrayList<Badge>(3).also { out = it }).add(Badge(f.substring(ps, slash), f.substring(slash + 1, pe)))
                ps = pe + 1
            }
            return out ?: emptyList()
        }

        /** `25:0-4,12-16/1902:6-10`, sorted by start; malformed pieces skipped (same rules as [ChatEventParser.parseEmotes]). */
        fun emotesOf(f: String, s: Int, e: Int): List<EmoteRange> {
            if (s < 0 || e <= s) return emptyList()
            var out: ArrayList<EmoteRange>? = null
            var gs = s
            while (gs <= e) {
                var ge = f.indexOf('/', gs)
                if (ge < 0 || ge > e) ge = e
                var colon = -1
                for (k in gs until ge) if (f[k] == ':') { colon = k; break }
                if (colon > gs) {
                    val id = f.substring(gs, colon)
                    var rs = colon + 1
                    while (rs <= ge) {
                        var re = f.indexOf(',', rs)
                        if (re < 0 || re > ge) re = ge
                        var dash = -1
                        for (k in rs until re) if (f[k] == '-') { dash = k; break }
                        if (dash > rs) {
                            val a = intOf(f, rs, dash)
                            val b = intOf(f, dash + 1, re)
                            if (a != null && b != null && a <= b) (out ?: ArrayList<EmoteRange>(4).also { out = it }).add(EmoteRange(id, a, b))
                        }
                        rs = re + 1
                    }
                }
                gs = ge + 1
            }
            val list = out ?: return emptyList()
            if (list.size > 1) list.sortBy { it.start }
            return list
        }
    }
}
