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
 * - Otherwise the queue is topped up by cursor (falling back to offsets) once it holds less than [lowWaterSec] of chat
 *   past the position, up to [aheadSec], at most one request per [minGapMs].
 * - Only comments whose offset is at or before the position are emitted, once each, in order.
 * - A failed request is retried with exponential back-off from [retryDelayMs]; a refusal ([ReplayUnavailableException]) disables the
 *   pager, which then returns nothing (the chat panel says replay is unavailable).
 */
class ChatReplayPager(
    private val api: VideoCommentsApi,
    private val videoId: String,
    private val lookbackSec: Int = 25,
    /** Fill the buffer up to this much chat past the playhead... */
    private val aheadSec: Int = 60,
    /** ...and top it up again once it falls below this. */
    private val lowWaterSec: Int = 30,
    private val jumpToleranceMs: Long = 3_000,
    private val maxPending: Int = 800,
    private val retryDelayMs: Long = 2_000,
    private val maxRetryDelayMs: Long = 30_000,
    /** Minimum time between two requests, so a busy chat cannot hammer the endpoint. */
    private val minGapMs: Long = 250,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Batch(val messages: List<VodComment>, val reset: Boolean)

    private val pending = ArrayDeque<VodComment>()
    private val seen = HashSet<String>()
    private var cursor: String? = null
    private var hasNext = false
    private var primed = false
    private var filling = false
    private var lastPositionMs = -1L
    private var retryAt = 0L
    private var nextRequestAt = 0L
    private var failures = 0
    private var offsetPaging = false
    private var lastFetchedOffset = 0
    private var stalledPages = 0

    /** The endpoint refused: replay cannot work for this video. */
    var unavailable = false
        private set

    /** The last request failed and will be retried. */
    var failing = false
        private set

    /** Class and HTTP status (or message of an I/O failure) of the last failure; never headers or tokens. */
    var lastError: String? = null
        private set

    /** Comments fetched but not yet due. */
    val queued: Int get() = pending.size

    /** One line for the debug overlay. */
    fun debugLine(): String = when {
        unavailable -> "replay  unavailable${lastError?.let { " ($it)" } ?: ""}"
        failing -> "replay  ERR ${lastError ?: "?"} (retry #$failures)"
        else -> "replay  ok q=${pending.size}${if (offsetPaging) " offset-paging" else ""}"
    }

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
            filling = false
            retryAt = 0
            nextRequestAt = 0
            stalledPages = 0
            offsetPaging = false
        }
        if (clock() >= retryAt) fetch(positionMs)
        val due = ArrayList<VodComment>()
        while (pending.isNotEmpty() && pending.first().offsetSeconds * 1000L <= positionMs) due += pending.removeFirst()
        return Batch(due, jumped)
    }

    private suspend fun fetch(positionMs: Long) {
        try {
            var burst = false
            if (!primed) {
                val from = (positionMs / 1000 - lookbackSec).coerceAtLeast(0).toInt()
                accept(api.byOffset(videoId, from))
                primed = true
                burst = true // a seek or the first load fills up to a few pages at once; after that, one page per gap
            }
            var pages = 0
            while (hasNext && pending.size < maxPending && pages < (if (burst) BURST_PAGES else 1)) {
                val buffered = bufferedMs(positionMs)
                if (buffered < lowWaterSec * 1000L) filling = true else if (buffered >= aheadSec * 1000L) filling = false
                if (!filling) break
                if (!burst && clock() < nextRequestAt) break
                nextPage()
                pages++
                nextRequestAt = clock() + minGapMs
            }
            failing = false
            lastError = null
            failures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReplayUnavailableException) {
            unavailable = true
            lastError = describe(e)
        } catch (e: Exception) {
            failing = true
            failures++
            lastError = describe(e)
            retryAt = clock() + (retryDelayMs shl (failures - 1).coerceAtMost(5)).coerceAtMost(maxRetryDelayMs)
        }
    }

    /** The next page: by cursor; if the cursor request is rejected, by offset from the last comment we have. */
    private suspend fun nextPage() {
        if (!offsetPaging) {
            try {
                accept(api.byCursor(videoId, cursor ?: return))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReplayUnavailableException) {
                throw e
            } catch (e: Exception) {
                offsetPaging = true // remembered for this pager; the error stays visible only if offsets fail too
            }
        }
        val before = seen.size
        // Same-second comments may spill over a page edge; ids de-duplicate, and a page of nothing new moves on a second.
        val from = lastFetchedOffset + stalledPages
        accept(api.byOffset(videoId, from))
        stalledPages = if (seen.size == before) stalledPages + 1 else 0
    }

    private fun bufferedMs(positionMs: Long): Long =
        if (pending.isEmpty()) 0 else (pending.last().offsetSeconds * 1000L - positionMs).coerceAtLeast(0)

    private fun describe(e: Exception): String {
        val name = e::class.simpleName ?: "Error"
        return if (e is java.io.IOException) "$name: ${e.message.orEmpty().take(100)}" else name
    }

    private fun accept(page: tv.frostline.core.twitchinternal.CommentsPage) {
        for (c in page.comments) {
            if (seen.add(c.id)) pending.addLast(c)
            if (c.offsetSeconds > lastFetchedOffset) lastFetchedOffset = c.offsetSeconds
        }
        cursor = page.nextCursor
        hasNext = page.hasNext
        if (seen.size > MAX_SEEN) seen.clear().also { pending.forEach { seen.add(it.id) } }
    }

    private companion object {
        const val MAX_SEEN = 5_000
        const val BURST_PAGES = 3
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
