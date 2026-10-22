package tv.frostline.feature.chat

/** One parsed IRC line: `@tags :prefix COMMAND params... :trailing`. */
class IrcMessage(
    val tags: Map<String, String>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
    val trailing: String?,
) {
    /** The nick of `nick!user@host`, or the bare prefix (server names). */
    val nick: String? get() = prefix?.substringBefore('!')?.takeIf { it.isNotEmpty() }
    override fun toString(): String = "IrcMessage($command ${params.joinToString(" ")})"
}

object IrcParser {
    /** Splits a frame that may hold several CRLF-separated lines and parses each; blank/garbled lines are skipped. */
    fun parseFrame(frame: String): List<IrcMessage> =
        frame.split("\r\n", "\n").mapNotNull { if (it.isBlank()) null else parse(it) }

    fun parse(raw: String): IrcMessage? {
        var s = raw.trimEnd('\r', '\n')
        if (s.isEmpty()) return null
        var tags: Map<String, String> = emptyMap()
        if (s[0] == '@') {
            val sp = s.indexOf(' ')
            if (sp < 0) return null
            tags = parseTags(s.substring(1, sp))
            s = s.substring(sp + 1).trimStart()
        }
        var prefix: String? = null
        if (s.startsWith(":")) {
            val sp = s.indexOf(' ')
            if (sp < 0) return null
            prefix = s.substring(1, sp)
            s = s.substring(sp + 1).trimStart()
        }
        if (s.isEmpty()) return null
        var trailing: String? = null
        val t = s.indexOf(" :")
        val head = if (t >= 0) {
            trailing = s.substring(t + 2)
            s.substring(0, t)
        } else {
            s
        }
        val parts = head.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        // A line like ":tmi PING" with no space-colon trailing is fine; "PING :tmi.twitch.tv" has trailing.
        return IrcMessage(tags, prefix, parts[0].uppercase(), parts.drop(1), trailing)
    }

    fun parseTags(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split(';')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) out[pair] = "" else out[pair.substring(0, eq)] = unescape(pair.substring(eq + 1))
        }
        return out
    }

    /** IRCv3 tag value escapes: `\:` `;`, `\s` space, `\\` backslash, `\r`, `\n`. */
    fun unescape(v: String): String {
        if (v.indexOf('\\') < 0) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    ':' -> sb.append(';')
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '\\') {
                i++ // dangling backslash is dropped
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
