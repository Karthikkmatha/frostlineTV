package tv.frostline.feature.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.twitchinternal.CommentsPage
import tv.frostline.core.twitchinternal.ReplayUnavailableException
import tv.frostline.core.twitchinternal.VideoCommentsApi
import tv.frostline.core.twitchinternal.VodBadge
import tv.frostline.core.twitchinternal.VodComment
import tv.frostline.core.twitchinternal.VodFragment
import java.io.IOException

/** A fake replay endpoint over a fixed list of comments: pages of [pageSize], cursors are the last id. */
private class FakeComments(
    val all: List<VodComment>,
    val pageSize: Int = 5,
) : VideoCommentsApi {
    val offsetCalls = mutableListOf<Int>()
    val cursorCalls = mutableListOf<String>()
    var failNext = 0
    var refuse = false

    private fun page(from: Int): CommentsPage {
        if (refuse) throw ReplayUnavailableException("nope")
        if (failNext > 0) { failNext--; throw IOException("boom") }
        val slice = all.drop(from).take(pageSize)
        val end = from + slice.size
        return CommentsPage(slice, slice.lastOrNull()?.id, end < all.size && slice.isNotEmpty())
    }

    override suspend fun byOffset(videoId: String, offsetSeconds: Int): CommentsPage {
        offsetCalls += offsetSeconds
        return page(all.indexOfFirst { it.offsetSeconds >= offsetSeconds }.let { if (it < 0) all.size else it })
    }

    override suspend fun byCursor(videoId: String, cursor: String): CommentsPage {
        cursorCalls += cursor
        return page(all.indexOfFirst { it.id == cursor } + 1)
    }
}

class ChatReplayPagerTest {
    private fun c(i: Int, offset: Int) = VodComment("c$i", offset, "u$i", "user$i", "User$i", null, emptyList(), listOf(VodFragment("msg $i", null)))

    /** One comment every 2 s, c0 at 0 s ... c99 at 198 s. */
    private val chat = (0 until 100).map { c(it, it * 2) }

    private var now = 0L
    private fun pager(api: VideoCommentsApi) = ChatReplayPager(api, "v1", clock = { now })

    private fun ids(b: ChatReplayPager.Batch) = b.messages.map { it.id }

    @Test fun firstCallStartsLookbackBeforeThePositionAndEmitsOnlyDueMessages() = runBlocking {
        val api = FakeComments(chat)
        val p = pager(api)
        val b = p.advance(60_000) // 60 s: asks from 35 s
        assertEquals(listOf(35), api.offsetCalls)
        assertTrue(b.reset)
        assertTrue(b.messages.all { it.offsetSeconds <= 60 })
        assertEquals("c18", b.messages.first().id) // first comment at or after 35 s is c18 (36 s)
        assertEquals("c30", b.messages.last().id) // the last one at or before 60 s
    }

    @Test fun messagesAreEmittedOnceInOrderAsThePlayheadAdvances() = runBlocking {
        val api = FakeComments(chat)
        val p = pager(api)
        p.advance(0)
        val seen = mutableListOf<String>()
        var pos = 0L
        while (pos < 40_000) {
            pos += 200
            val b = p.advance(pos)
            assertFalse("no reset while playing", b.reset)
            assertTrue(b.messages.all { it.offsetSeconds * 1000L <= pos })
            seen += ids(b)
        }
        // c0 was emitted by the first call (offset 0 <= 0); everything up to 40 s follows without gaps or repeats.
        assertEquals((1..20).map { "c$it" }, seen)
    }

    @Test fun nothingAfterThePlayheadIsEmittedBeforeItsTime() = runBlocking {
        val p = pager(FakeComments(chat))
        p.advance(10_000)
        val b = p.advance(10_200)
        assertTrue(b.messages.isEmpty() || b.messages.all { it.offsetSeconds <= 10 })
        assertEquals(emptyList<String>(), ids(p.advance(10_400)))
    }

    @Test fun topsUpByCursorWhenTheQueueRunsLow() = runBlocking {
        val api = FakeComments(chat, pageSize = 5)
        val p = pager(api)
        p.advance(0)
        var pos = 0L
        while (pos < 30_000) { pos += 200; p.advance(pos) }
        assertEquals(1, api.offsetCalls.size)
        assertTrue("cursor pages were used", api.cursorCalls.size >= 2)
        // It reads ahead: at least ~30 s of chat past the playhead is queued, but not the whole video.
        assertTrue(p.queued in 1..60)
        // The cursor handed back is always the last comment of the previous page.
        assertTrue(api.cursorCalls.all { it.startsWith("c") })
    }

    @Test fun seekingBackClearsAndRefetchesFromBeforeTheNewPosition() = runBlocking {
        val api = FakeComments(chat)
        val p = pager(api)
        p.advance(100_000)
        p.advance(100_200)
        val b = p.advance(20_000)
        assertTrue(b.reset)
        assertEquals(listOf(75, 0), api.offsetCalls) // 100 s - 25 s, then 20 s - 25 s -> 0
        assertTrue(b.messages.all { it.offsetSeconds <= 20 })
        assertEquals("c10", b.messages.last().id)
    }

    @Test fun seekingForwardPastTheToleranceResets() = runBlocking {
        val api = FakeComments(chat)
        val p = pager(api)
        p.advance(10_000)
        assertFalse(p.advance(10_500).reset)
        assertTrue(p.advance(40_500).reset)
        assertEquals(2, api.offsetCalls.size)
    }

    @Test fun aSmallStepForwardKeepsPlaying() = runBlocking {
        val p = pager(FakeComments(chat))
        p.advance(10_000)
        assertFalse(p.advance(12_500).reset)
    }

    @Test fun overlappingPagesDoNotRepeatAMessage() = runBlocking {
        val dup = object : VideoCommentsApi {
            override suspend fun byOffset(videoId: String, offsetSeconds: Int) = CommentsPage(listOf(c(1, 1), c(2, 2)), "c2", true)
            override suspend fun byCursor(videoId: String, cursor: String) = CommentsPage(listOf(c(2, 2), c(3, 3)), "c3", false)
        }
        val p = pager(dup)
        val out = ids(p.advance(0)) + ids(p.advance(10_000).also { assertTrue(it.reset) })
        assertEquals(listOf("c1", "c2", "c3"), out)
    }

    @Test fun aFailedRequestIsRetriedAfterTheDelay() = runBlocking {
        val api = FakeComments(chat).also { it.failNext = 1 }
        val p = pager(api)
        assertTrue(p.advance(30_000).messages.isEmpty())
        assertTrue(p.failing)
        now = 1_000
        p.advance(30_200)
        assertEquals("not retried yet", 1, api.offsetCalls.size)
        now = 6_000
        val b = p.advance(30_400)
        assertFalse(p.failing)
        assertEquals(2, api.offsetCalls.size)
        assertTrue(b.messages.isNotEmpty())
        assertFalse("a retry is not a seek", b.reset)
    }

    @Test fun aRefusedReplayDisablesThePager() = runBlocking {
        val api = FakeComments(chat).also { it.refuse = true }
        val p = pager(api)
        p.advance(10_000)
        assertTrue(p.unavailable)
        api.refuse = false
        assertTrue(p.advance(10_200).messages.isEmpty())
        assertEquals(1, api.offsetCalls.size)
    }

    @Test fun anEmptyVideoJustYieldsNothing() = runBlocking {
        val p = pager(FakeComments(emptyList()))
        assertTrue(p.advance(5_000).messages.isEmpty())
        assertFalse(p.unavailable)
        assertFalse(p.failing)
    }

    @Test fun replayedCommentsBecomeRawMessagesWithEmoteRanges() {
        val vc = VodComment(
            "id1", 61, "7", "bob", "Bob", "#00FF00", listOf(VodBadge("subscriber", "3")),
            listOf(VodFragment("hi ", null), VodFragment("Kappa", "25"), VodFragment(" there", null)),
        )
        val raw = vc.toRaw()
        assertEquals("hi Kappa there", raw.text)
        assertEquals(listOf(EmoteRange("25", 3, 7)), raw.emotes)
        assertEquals(61_000L, raw.tsMs)
        assertEquals(Badge("subscriber", "3"), raw.badges.single())
        assertEquals(0xFF00FF00.toInt(), raw.colorArgb)
        val msg = MessageBuilder.build(raw, ResolveContext(emptyMap(), emptyMap()), timeLabel = "1:01")
        assertEquals("1:01", msg.time)
        assertEquals(3, msg.parts.size)
        assertTrue(msg.parts[1] is Part.EmoteRef)
        assertNull(vc.copy(colorHex = null).toRaw().colorArgb)
    }
}
