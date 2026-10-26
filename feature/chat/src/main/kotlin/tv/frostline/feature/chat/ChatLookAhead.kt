package tv.frostline.feature.chat

/** What the look-ahead queue needs from the emote engine (a fake in tests). */
interface AheadEmotes {
    /**
     * [m] entered the queue and will be shown at [releaseAtMs]: decode its emotes and badges now (earliest release first) and keep
     * them from being evicted until [release].
     */
    fun request(m: ChatMessage, releaseAtMs: Long)

    /** Every picture of [m] can be drawn (or has failed for good, which must not hold the line back). */
    fun isReady(m: ChatMessage): Boolean

    /** [m] left the queue. Its pictures stay protected for [graceMs] more (the line is about to be composed); 0 = at once (dropped). */
    fun release(m: ChatMessage, graceMs: Long)
}

/** What the player says about itself, once a second. */
data class PlaybackSync(val latencyMs: Long? = null, val playing: Boolean = false)

/**
 * How long live chat is held back so it lines up with the picture. Both Live modes put the video behind live, so chat runs
 * ahead of it by the player's live offset.
 */
object HoldPolicy {
    const val MARGIN_MS = 1_500L
    const val MAX_HOLD_MS = 12_000L

    /** The hold wanted now: the live offset less a margin, 0 when the player is not playing or the offset is unknown, or sync is off. */
    fun targetMs(latencyMs: Long?, playing: Boolean, enabled: Boolean): Long =
        if (!enabled || !playing || latencyMs == null || latencyMs <= 0) 0L else (latencyMs - MARGIN_MS).coerceIn(0L, MAX_HOLD_MS)
}

/**
 * Smooths the hold. It may grow at once (a gap in chat) but shrinks by at most half a second per second, so the release time
 * of a new line ([LookAheadQueue.enqueue]) never goes backwards past an older one and chat never jumps. Thread-safe.
 */
class HoldSmoother {
    private var current = 0L
    private var lastMs = Long.MIN_VALUE

    @Synchronized
    fun next(targetMs: Long, nowMs: Long): Long {
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceAtLeast(0L)
        lastMs = nowMs
        current = if (targetMs >= current) targetMs else maxOf(targetMs, current - dt / 2)
        return current
    }

    val value: Long @Synchronized get() = current

    @Synchronized
    fun reset() { current = 0L; lastMs = Long.MIN_VALUE }
}

/**
 * Live chat waits here until it is time to show it. A line is released at `arrival + hold`, never before an earlier line (so the
 * order is the arrival order whatever the hold does), and, if its emotes are still being decoded then, up to [graceMs] more.
 * After that it is shown with whatever is ready and the rest draws the moment it lands. Thread-safe; time is passed in.
 */
class LookAheadQueue(
    private val ahead: () -> AheadEmotes? = { null },
    private val graceMs: Long = 500L,
    private val protectAfterReleaseMs: Long = 3_000L,
) {
    private class Entry(val message: ChatMessage, val releaseAt: Long)

    private val q = ArrayDeque<Entry>()
    private var lastReleaseAt = Long.MIN_VALUE
    private val ring = BooleanArray(RING)
    private var ringCount = 0
    private var ringHead = 0
    private var lateTotal = 0

    val size: Int @Synchronized get() = q.size
    /** Lines released while some picture was still missing (after the grace wait). */
    val late: Int @Synchronized get() = lateTotal

    /** Percentage of the last 200 released lines whose pictures were all ready at release. 100 before any release. */
    val readyPercent: Int
        @Synchronized get() {
            if (ringCount == 0) return 100
            var ok = 0
            for (i in 0 until ringCount) if (ring[i]) ok++
            return ok * 100 / ringCount
        }

    /** Queues [m] to be shown [holdMs] after [nowMs]; asks the engine for its pictures. Returns the release time. */
    @Synchronized
    fun enqueue(m: ChatMessage, nowMs: Long, holdMs: Long): Long {
        val at = maxOf(nowMs + holdMs.coerceAtLeast(0L), if (q.isEmpty()) Long.MIN_VALUE else q.last().releaseAt, lastReleaseAt)
        q.addLast(Entry(m, at))
        ahead()?.request(m, at)
        return at
    }

    /** Release time of the head, or null when empty. */
    @Synchronized
    fun nextDueMs(): Long? = q.firstOrNull()?.releaseAt

    /** The lines that are due at [nowMs], in order. A head line still waiting for pictures holds the ones behind it, for at most [graceMs]. */
    @Synchronized
    fun poll(nowMs: Long): List<ChatMessage> {
        var out: ArrayList<ChatMessage>? = null
        val engine = ahead()
        while (q.isNotEmpty()) {
            val e = q.first()
            if (e.releaseAt > nowMs) break
            val ready = engine?.isReady(e.message) ?: true
            if (!ready && nowMs < e.releaseAt + graceMs) break
            q.removeFirst()
            lastReleaseAt = e.releaseAt
            record(ready)
            engine?.release(e.message, protectAfterReleaseMs)
            val list = out ?: ArrayList<ChatMessage>().also { out = it }
            list.add(e.message)
        }
        return out ?: emptyList()
    }

    /** Everything now, in order (sync switched off). */
    @Synchronized
    fun drain(): List<ChatMessage> {
        val engine = ahead()
        val out = ArrayList<ChatMessage>(q.size)
        while (q.isNotEmpty()) {
            val e = q.removeFirst()
            lastReleaseAt = maxOf(lastReleaseAt, e.releaseAt)
            engine?.release(e.message, protectAfterReleaseMs)
            out += e.message
        }
        return out
    }

    /** CLEARMSG: drops the queued line with this message id. True if there was one. */
    @Synchronized
    fun removeMessage(id: String): Boolean = removeWhere { it.key == id } > 0

    /** CLEARCHAT of one user (timeout or ban): drops that user's queued lines. Returns how many. */
    @Synchronized
    fun removeUser(login: String): Int = removeWhere { it.login.equals(login, ignoreCase = true) }

    /** CLEARCHAT of the whole chat, a channel switch, or leaving. */
    @Synchronized
    fun clear(): Int = removeWhere { true }

    private fun removeWhere(pred: (ChatMessage) -> Boolean): Int {
        val engine = ahead()
        var n = 0
        val it = q.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (pred(e.message)) { it.remove(); engine?.release(e.message, 0L); n++ }
        }
        return n
    }

    private fun record(ready: Boolean) {
        ring[ringHead] = ready
        ringHead = (ringHead + 1) % RING
        if (ringCount < RING) ringCount++
        if (!ready) lateTotal++
    }

    private companion object { const val RING = 200 }
}

/**
 * Where a live chat line goes: straight into the shown buffer (the user's own lines, notices, sub/raid text, or when sync is off
 * or the picture is not behind), or into the look-ahead queue until the picture catches up. Moderation reaches both. All of it
 * runs on one worker; time and the engine hook are passed in so it can be tested.
 */
class ChatIntake(
    val buffer: ChatBuffer,
    val queue: LookAheadQueue,
    val hold: HoldSmoother = HoldSmoother(),
    private val clockMs: () -> Long,
    /** Engine hook: (message, its pictures were already requested by the queue). */
    private val note: (ChatMessage, Boolean) -> Unit = { _, _ -> },
) {
    @Volatile var sync = PlaybackSync()
    @Volatile var enabled = true

    /** Shown immediately whatever the hold: the user's own sent lines. */
    fun own(m: ChatMessage) = buffer.addLocal(m)

    /**
     * A line from the socket. [bypass]: shown at once (USERNOTICE text such as a resub message or an announcement).
     * Returns true when it went into the shown buffer, false when it was queued.
     */
    fun message(m: ChatMessage, bypass: Boolean): Boolean {
        val holdMs = if (bypass || !enabled) 0L else hold.next(HoldPolicy.targetMs(sync.latencyMs, sync.playing, true), clockMs())
        if (holdMs <= 0L && queue.size == 0) {
            buffer.addLocal(m)
            note(m, false)
            return true
        }
        queue.enqueue(m, clockMs(), holdMs)
        note(m, true)
        return false
    }

    /** Moves the lines that are due into the shown buffer. True if there were any. */
    fun release(): Boolean {
        val due = queue.poll(clockMs())
        for (m in due) buffer.addLocal(m)
        return due.isNotEmpty()
    }

    /** Sync was switched off: everything waiting is shown now and the hold starts again from zero. */
    fun flush(): Boolean {
        hold.reset()
        val all = queue.drain()
        for (m in all) buffer.addLocal(m)
        return all.isNotEmpty()
    }

    /** CLEARMSG / CLEARCHAT apply to the queued lines and the shown ones. True if the shown list changed. */
    fun moderate(ev: ChatEvent, ctx: ResolveContext): Boolean {
        val queued = when (ev) {
            is ChatEvent.ClearMsg -> queue.removeMessage(ev.targetMessageId)
            is ChatEvent.ClearChat -> (if (ev.login == null) queue.clear() else queue.removeUser(ev.login)) > 0
            else -> false
        }
        return buffer.apply(ev, ctx, queuedRemoved = queued)
    }

    fun clear() {
        queue.clear()
        buffer.clear()
    }

    /** `chat: hold 12.4 s queue 143 ready 98% late 2` */
    fun line(): String =
        "chat: hold ${String.format(java.util.Locale.US, "%.1f", hold.value / 1000.0)} s queue ${queue.size} ready ${queue.readyPercent}% late ${queue.late}"
}
