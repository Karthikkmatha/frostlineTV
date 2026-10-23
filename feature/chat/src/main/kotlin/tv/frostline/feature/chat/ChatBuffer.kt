package tv.frostline.feature.chat

/**
 * The bounded backlog. Applies parsed events to a list of at most [capacity] lines (oldest first inside,
 * newest first in [snapshot], which is what the reversed LazyColumn wants). Thread-safe.
 */
class ChatBuffer(val capacity: Int = 200) {
    private val lines = ArrayDeque<ChatLine>()
    private var sysSeq = 0L

    val size: Int @Synchronized get() = lines.size

    /** Returns true if the visible list changed. */
    @Synchronized
    fun apply(ev: ChatEvent, ctx: ResolveContext, fallback: Map<String, Emote> = emptyMap()): Boolean = when (ev) {
        is ChatEvent.Message -> {
            val m = MessageBuilder.build(ev.message, ctx, fallback = fallback)
            add(m)
            EmoteEngine.current?.noteMessage(m) // message rate, emote usage and the hot set
            true
        }
        is ChatEvent.System -> {
            add(SystemLine(ev.id, ChatText.time(ev.tsMs), ev.text, ev.kind))
            true
        }
        is ChatEvent.ClearMsg -> lines.removeAll { it is ChatMessage && it.key == ev.targetMessageId }
        is ChatEvent.ClearChat -> {
            if (ev.login == null) {
                lines.clear()
                add(SystemLine("sys-${sysSeq++}", ChatText.time(ev.tsMs), "Chat was cleared by a moderator", ChatEvent.System.Kind.Moderation))
                true
            } else {
                val removed = lines.removeAll { it is ChatMessage && it.login.equals(ev.login, ignoreCase = true) }
                if (removed) {
                    val what = if (ev.durationSec != null) "timed out for ${ev.durationSec} s" else "banned"
                    add(SystemLine("sys-${sysSeq++}", ChatText.time(ev.tsMs), "${ev.login} was $what", ChatEvent.System.Kind.Moderation))
                }
                removed
            }
        }
        else -> false
    }

    @Synchronized
    fun addLocal(message: ChatMessage) = add(message)

    @Synchronized
    fun addInfo(text: String, nowMs: Long = System.currentTimeMillis()) {
        add(SystemLine("sys-${sysSeq++}", ChatText.time(nowMs), text, ChatEvent.System.Kind.Info))
    }

    @Synchronized
    fun clear() = lines.clear()

    /** Newest first. A fresh list (at most [capacity] references), safe to hand to the UI. */
    @Synchronized
    fun snapshot(): List<ChatLine> = ArrayList<ChatLine>(lines.size).also { out ->
        for (i in lines.indices.reversed()) out += lines[i]
    }

    private fun add(line: ChatLine) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }
}
