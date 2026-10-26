package tv.frostline.feature.chat

/** How much of the chat may animate, decided by how fast messages arrive. */
enum class EmoteMode(val label: String) {
    /** Under about 3 messages a second: every visible animated emote plays. */
    Slow("slow"),
    /** About 3 to 8 a second: only the newest few lines animate. */
    Busy("busy"),
    /** Over about 8 a second: still frames only. */
    Fast("fast"),
}

/**
 * Messages per second over a rolling window, turned into an [EmoteMode] with hysteresis: the mode only changes after
 * the rate has stayed in the new band for [holdMs]. Thread-safe; time is passed in so tests do not sleep.
 */
class EmoteRateTracker(private val windowMs: Long = 5_000L, private val holdMs: Long = 5_000L) {
    private val times = LongArray(MAX_EVENTS)
    private var head = 0
    private var count = 0
    private var current = EmoteMode.Slow
    private var candidate = EmoteMode.Slow
    private var candidateSince = 0L

    @Synchronized
    fun record(nowMs: Long) {
        times[head] = nowMs
        head = (head + 1) % MAX_EVENTS
        if (count < MAX_EVENTS) count++
    }

    /** Messages per second over the last [windowMs]. */
    @Synchronized
    fun rate(nowMs: Long): Float {
        var n = 0
        var i = head
        for (k in 0 until count) {
            i = (i - 1 + MAX_EVENTS) % MAX_EVENTS
            if (nowMs - times[i] <= windowMs) n++ else break
        }
        return n * 1000f / windowMs
    }

    @Synchronized
    fun update(nowMs: Long): EmoteMode {
        val band = bandOf(rate(nowMs))
        when {
            band == current -> candidate = current
            band != candidate -> { candidate = band; candidateSince = nowMs }
            nowMs - candidateSince >= holdMs -> current = band
        }
        return current
    }

    @Synchronized fun mode(): EmoteMode = current

    companion object {
        private const val MAX_EVENTS = 512
        const val BUSY_FROM = 3f
        const val FAST_ABOVE = 8f
        fun bandOf(ratePerSec: Float): EmoteMode = when {
            ratePerSec < BUSY_FROM -> EmoteMode.Slow
            ratePerSec <= FAST_ABOVE -> EmoteMode.Busy
            else -> EmoteMode.Fast
        }
    }
}

/**
 * Least-recently-used map bounded by total weight (bytes), not by entry count. [onEvict] runs for every entry pushed
 * out or replaced. Thread-safe.
 */
class WeightedLru<K : Any, V : Any>(
    private val capacity: Long,
    private val weigh: (V) -> Long,
    /** Entries for which this is true are never pushed out (e.g. emotes on screen); the cap may be exceeded by them. */
    private val protect: (V) -> Boolean = { false },
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private class Node<V>(val value: V, val weight: Long)

    private val map = LinkedHashMap<K, Node<V>>(64, 0.75f, true)
    private var total = 0L

    val bytes: Long @Synchronized get() = total
    val size: Int @Synchronized get() = map.size

    /** Returns the value and marks it most recently used. */
    @Synchronized fun get(key: K): V? = map[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        val node = Node(value, weigh(value))
        map.put(key, node)?.let { old ->
            total -= old.weight
            if (old.value !== value) onEvict(key, old.value)
        }
        total += node.weight
        val it = map.entries.iterator()
        while (total > capacity && it.hasNext()) {
            val e = it.next()
            if (e.key == key || protect(e.value.value)) continue // the entry just added is the newest; keep it
            total -= e.value.weight
            it.remove()
            onEvict(e.key, e.value.value)
        }
    }

    /** Drops the least recently used entry (calling [onEvict]). False when empty. */
    fun evictOldest(): Boolean {
        val victim: Pair<K, V>
        synchronized(this) {
            val it = map.entries.iterator()
            var e: MutableMap.MutableEntry<K, Node<V>>? = null
            while (it.hasNext()) { val c = it.next(); if (!protect(c.value.value)) { e = c; break } }
            if (e == null) return false
            total -= e.value.weight
            it.remove()
            victim = e.key to e.value.value
        }
        onEvict(victim.first, victim.second)
        return true
    }

    @Synchronized
    fun clear() {
        val copy = map.entries.map { it.key to it.value.value }
        map.clear()
        total = 0
        copy.forEach { (k, v) -> onEvict(k, v) }
    }
}

/** Which frame of an [n]-frame strip to draw at clock tick [tick]; all emotes share one tick so they stay in step. */
fun frameIndex(tick: Long, n: Int): Int = if (n <= 1) 0 else ((tick % n) + n).toInt() % n
