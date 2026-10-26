package tv.frostline.feature.chat

/** A badge as carried in the `badges` tag: set id plus version, e.g. subscriber/12. */
data class Badge(val set: String, val version: String)

/** `emotes` tag range, in Unicode code points of the message text, end inclusive. */
data class EmoteRange(val id: String, val start: Int, val end: Int)

data class ReplyInfo(val messageId: String, val login: String, val displayName: String, val body: String)

/** A chat message before emote/badge resolution. */
class RawMessage(
    val id: String,
    val tsMs: Long,
    val userId: String,
    val login: String,
    val displayName: String,
    val colorArgb: Int?,
    val badges: List<Badge>,
    val text: String,
    val action: Boolean,
    val emotes: List<EmoteRange>,
    val reply: ReplyInfo?,
    val firstMessage: Boolean = false,
    val highlighted: Boolean = false,
    /** For USERNOTICE-carried messages (resub text, announcement), the system header. */
    val systemHeader: String? = null,
    val announcement: Boolean = false,
)

data class RoomState(
    val emoteOnly: Boolean? = null,
    val followersOnlyMinutes: Int? = null,
    val subsOnly: Boolean? = null,
    val slowSeconds: Int? = null,
    val r9k: Boolean? = null,
)

data class UserState(
    val displayName: String,
    val colorArgb: Int?,
    val badges: List<Badge>,
)

sealed interface ChatEvent {
    data class Message(val message: RawMessage) : ChatEvent
    data class System(val id: String, val tsMs: Long, val text: String, val kind: Kind) : ChatEvent {
        enum class Kind { Sub, Raid, Notice, Moderation, Info }
    }
    /** CLEARCHAT. [login] null = whole chat cleared; otherwise a timeout ([durationSec]) or ban (null). */
    data class ClearChat(val login: String?, val userId: String?, val durationSec: Int?, val tsMs: Long) : ChatEvent
    data class ClearMsg(val targetMessageId: String, val login: String?) : ChatEvent
    data class Room(val state: RoomState) : ChatEvent
    data class User(val state: UserState) : ChatEvent
    /** Server asked us to reconnect (RECONNECT). */
    data object Reconnect : ChatEvent
    data object Welcome : ChatEvent
    data class AuthFailed(val text: String) : ChatEvent
    data class Ping(val payload: String) : ChatEvent
}

object ChatEventParser {
    private val seq = java.util.concurrent.atomic.AtomicLong()
    internal fun localId(prefix: String) = "$prefix-${seq.getAndIncrement()}"

    fun parse(m: IrcMessage, nowMs: Long = System.currentTimeMillis()): ChatEvent? = when (m.command) {
        "PING" -> ChatEvent.Ping(m.trailing ?: m.params.firstOrNull() ?: "tmi.twitch.tv")
        "RECONNECT" -> ChatEvent.Reconnect
        "001" -> ChatEvent.Welcome
        "PRIVMSG" -> privmsg(m, nowMs)
        "USERNOTICE" -> usernotice(m, nowMs)
        "CLEARCHAT" -> clearChat(m, nowMs)
        "CLEARMSG" -> m.tags["target-msg-id"]?.let { ChatEvent.ClearMsg(it, m.tags["login"]) }
        "ROOMSTATE" -> ChatEvent.Room(roomState(m.tags))
        "USERSTATE" ->
            ChatEvent.User(UserState(m.tags["display-name"].orEmpty(), parseColor(m.tags["color"]), parseBadges(m.tags["badges"])))
        "NOTICE" -> notice(m, nowMs)
        else -> null
    }

    private fun notice(m: IrcMessage, nowMs: Long): ChatEvent? {
        val text = m.trailing.orEmpty()
        if (text.contains("Login authentication failed", true) || text.contains("Improperly formatted auth", true)) return ChatEvent.AuthFailed(text)
        if (text.isBlank()) return null
        return ChatEvent.System(localId("notice"), nowMs, text, ChatEvent.System.Kind.Notice)
    }

    private fun clearChat(m: IrcMessage, nowMs: Long): ChatEvent {
        val target = m.trailing?.takeIf { it.isNotBlank() }
        val ts = m.tags["tmi-sent-ts"]?.toLongOrNull() ?: nowMs
        return ChatEvent.ClearChat(target, m.tags["target-user-id"], m.tags["ban-duration"]?.toIntOrNull(), ts)
    }

    private fun privmsg(m: IrcMessage, nowMs: Long): ChatEvent? {
        val login = m.nick ?: return null
        var text = m.trailing ?: return null
        var action = false
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            action = true
            text = text.substring(8, text.length - 1)
        } else if (text.startsWith("\u0001ACTION ")) {
            action = true
            text = text.substring(8)
        }
        val t = m.tags
        val reply = t["reply-parent-msg-id"]?.let { pid ->
            ReplyInfo(
                pid,
                t["reply-parent-user-login"].orEmpty(),
                t["reply-parent-display-name"].orEmpty().ifEmpty { t["reply-parent-user-login"].orEmpty() },
                t["reply-parent-msg-body"].orEmpty(),
            )
        }
        return ChatEvent.Message(
            RawMessage(
                id = t["id"] ?: localId("m"),
                tsMs = t["tmi-sent-ts"]?.toLongOrNull() ?: nowMs,
                userId = t["user-id"].orEmpty(),
                login = login,
                displayName = t["display-name"].orEmpty().ifEmpty { login },
                colorArgb = parseColor(t["color"]),
                badges = parseBadges(t["badges"]),
                text = text,
                action = action,
                emotes = parseEmotes(t["emotes"]),
                reply = reply,
                firstMessage = t["first-msg"] == "1",
                highlighted = t["msg-id"] == "highlighted-message",
            ),
        )
    }

    private fun usernotice(m: IrcMessage, nowMs: Long): ChatEvent? {
        val t = m.tags
        val id = t["id"] ?: localId("n")
        val ts = t["tmi-sent-ts"]?.toLongOrNull() ?: nowMs
        val type = t["msg-id"].orEmpty()
        val sys = t["system-msg"].orEmpty()
        val body = m.trailing
        return when (type) {
            "raid" -> {
                val who = t["msg-param-displayName"].orEmpty().ifEmpty { t["msg-param-login"].orEmpty() }
                val n = t["msg-param-viewerCount"].orEmpty()
                ChatEvent.System(id, ts, "$who is raiding with $n viewers", ChatEvent.System.Kind.Raid)
            }
            "unraid" -> ChatEvent.System(id, ts, sys.ifEmpty { "Raid cancelled" }, ChatEvent.System.Kind.Raid)
            "announcement" -> {
                val login = t["login"].orEmpty()
                ChatEvent.Message(
                    RawMessage(
                        id, ts, t["user-id"].orEmpty(), login, t["display-name"].orEmpty().ifEmpty { login },
                        parseColor(t["color"]), parseBadges(t["badges"]), body.orEmpty(), false, parseEmotes(t["emotes"]), null,
                        systemHeader = "Announcement", announcement = true,
                    ),
                )
            }
            "sub", "resub", "subgift", "submysterygift", "giftpaidupgrade", "anongiftpaidupgrade",
            "primepaidupgrade", "extendsub", "standardpayforward", "communitypayforward", "bitsbadgetier", "ritual" -> {
                val header = sys.ifEmpty { type }
                if (!body.isNullOrBlank() && (type == "resub" || type == "sub")) {
                    val login = t["login"].orEmpty()
                    ChatEvent.Message(
                        RawMessage(
                            id, ts, t["user-id"].orEmpty(), login, t["display-name"].orEmpty().ifEmpty { login },
                            parseColor(t["color"]), parseBadges(t["badges"]), body, false, parseEmotes(t["emotes"]), null,
                            systemHeader = header,
                        ),
                    )
                } else {
                    ChatEvent.System(id, ts, header, ChatEvent.System.Kind.Sub)
                }
            }
            else -> if (sys.isNotEmpty()) ChatEvent.System(id, ts, sys, ChatEvent.System.Kind.Info) else null
        }
    }

    fun roomState(tags: Map<String, String>) = RoomState(
        emoteOnly = tags["emote-only"]?.let { it == "1" },
        followersOnlyMinutes = tags["followers-only"]?.toIntOrNull(),
        subsOnly = tags["subs-only"]?.let { it == "1" },
        slowSeconds = tags["slow"]?.toIntOrNull(),
        r9k = tags["r9k"]?.let { it == "1" },
    )

    /** `broadcaster/1,subscriber/12` -> badges. */
    fun parseBadges(raw: String?): List<Badge> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(',').mapNotNull {
            val i = it.indexOf('/')
            if (i <= 0) null else Badge(it.substring(0, i), it.substring(i + 1))
        }
    }

    /** `25:0-4,12-16/1902:6-10` -> ranges sorted by start. Malformed pieces are skipped. */
    fun parseEmotes(raw: String?): List<EmoteRange> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<EmoteRange>()
        for (group in raw.split('/')) {
            val c = group.indexOf(':')
            if (c <= 0) continue
            val id = group.substring(0, c)
            for (r in group.substring(c + 1).split(',')) {
                val d = r.indexOf('-')
                if (d <= 0) continue
                val a = r.substring(0, d).toIntOrNull() ?: continue
                val b = r.substring(d + 1).toIntOrNull() ?: continue
                if (a in 0..b) out += EmoteRange(id, a, b)
            }
        }
        out.sortBy { it.start }
        return out
    }

    /** `#RRGGBB` -> opaque ARGB int, else null. */
    fun parseColor(raw: String?): Int? {
        if (raw == null || raw.length != 7 || raw[0] != '#') return null
        val rgb = raw.substring(1).toIntOrNull(16) ?: return null
        return 0xFF000000.toInt() or rgb
    }
}
