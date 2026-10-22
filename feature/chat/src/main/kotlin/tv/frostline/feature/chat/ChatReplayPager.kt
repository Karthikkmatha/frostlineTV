package tv.frostline.feature.chat

import kotlinx.coroutines.CancellationException
import tv.frostline.core.twitchinternal.ReplayUnavailableException
import tv.frostline.core.twitchinternal.VideoCommentsApi
import tv.frostline.core.twitchinternal.VodComment

/**
 * Pages a past broadcast's chat around the playhead. Pure logic: the caller asks [advance] with the current
 * position every tick and gets the messages that became due.
 *
 * - A *jump* (first call, position going backwards, or forward by more than [jumpToleranceMs]) clears
 *   everything and refetches by offset starting [lookbackSec] before the position, so a seek shows the
 *   chat that was on screen at that moment. The batch says `reset = true`: the caller clears its list.
 * - Otherwise the queue is topped up by cursor whenever it holds less than [aheadSec] of chat past the position.
 * - Only comments whose offset is at or before the position are emitted, once each, in order.
 * - A failed request is retried after [retryDelayMs]; a refusal ([ReplayUnavailableException]) disables the
 *   pager, which then returns nothing (the chat panel says replay is unavailable).
 */
class ChatReplayPager(
    private val api: VideoCommentsApi,
    private val videoId: String,
    private val lookbackSec: Int = 25,
    private val aheadSec: Int = 30,
    private val jumpToleranceMs: Long = 3_000,
    private val maxPending: Int = 600,
    private val retryDelayMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Batch(val messages: List<VodComment>, val reset: Boolean)

    private val pending = ArrayDeque<VodComment>()
    private val seen = HashSet<String>()
    private var cursor: String? = null
    private var hasNext = false
    private var primed = false
    private var lastPositionMs = -1L
    private var retryAt = 0L

    /** The endpoint refused: replay cannot work for this video. */
    var unavailable = false
        private set

    /** The last request failed and will be retried. */
    var failing = false
        private set

    /** Comments fetched but not yet due. */
    val queued: Int get() = pending.size

    suspend fun advance(positionMs: Long): Batch {
        if (unavailable) return Batch(emptyList(), false)
        val jumped = lastPositionMs < 0 || positionMs < lastPositionMs || positionMs - lastPositionMs > jumpToleranceMs
        lastPositionMs = positionMs
        if (jumped) {
            pending.clear()
            seen.clear()
            cursor = null
            hasNext = false
            primed = false
            retryAt = 0
        }
        if (clock() >= retryAt) fetch(positionMs)
        val due = ArrayList<VodComment>()
        while (pending.isNotEmpty() && pending.first().offsetSeconds * 1000L <= positionMs) due += pending.removeFirst()
        return Batch(due, jumped)
    }

    private suspend fun fetch(positionMs: Long) {
        try {
            if (!primed) {
                val from = (positionMs / 1000 - lookbackSec).coerceAtLeast(0).toInt()
                accept(api.byOffset(videoId, from))
                primed = true
            }
            var pages = 0
            while (hasNext && pending.size < maxPending && needsMore(positionMs) && pages++ < MAX_PAGES_PER_CALL) {
                accept(api.byCursor(videoId, cursor ?: break))
            }
            failing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReplayUnavailableException) {
            unavailable = true
        } catch (e: Exception) {
            failing = true
            retryAt = clock() + retryDelayMs
        }
    }

    private fun needsMore(positionMs: Long): Boolean =
        pending.isEmpty() || pending.last().offsetSeconds * 1000L < positionMs + aheadSec * 1000L

    private fun accept(page: tv.frostline.core.twitchinternal.CommentsPage) {
        for (c in page.comments) if (seen.add(c.id)) pending.addLast(c)
        cursor = page.nextCursor
        hasNext = page.hasNext
        if (seen.size > MAX_SEEN) seen.clear().also { pending.forEach { seen.add(it.id) } }
    }

    private companion object {
        const val MAX_PAGES_PER_CALL = 3
        const val MAX_SEEN = 5_000
    }
}

/** Replayed comment -> the raw message the live chat path already knows how to resolve. */
internal fun VodComment.toRaw(): RawMessage {
    val text = StringBuilder()
    val ranges = ArrayList<EmoteRange>()
    var cp = 0
    for (f in fragments) {
        val len = f.text.codePointCount(0, f.text.length)
        if (f.emoteId != null && len > 0) ranges += EmoteRange(f.emoteId!!, cp, cp + len - 1)
        text.append(f.text)
        cp += len
    }
    return RawMessage(
        id = id,
        tsMs = offsetSeconds * 1000L,
        userId = userId,
        login = login,
        displayName = displayName,
        colorArgb = ChatEventParser.parseColor(colorHex),
        badges = badges.map { Badge(it.set, it.version) },
        text = text.toString(),
        action = false,
        emotes = ranges,
        reply = null,
    )
}
