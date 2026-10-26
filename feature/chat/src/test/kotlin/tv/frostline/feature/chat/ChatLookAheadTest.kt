package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(id: String, login: String = "u", emotes: List<Emote> = emptyList()) = ChatMessage(
    key = id, tsMs = 0, time = "12:00", userId = "1", login = login, displayName = login, colorArgb = 0xFFFFFFFF.toInt(),
    badges = emptyList(), parts = emotes.map { Part.EmoteRef(it) }, action = false, reply = null, header = null, highlight = false,
)

private fun emote(i: Int) = Emote("e$i", "E$i", "https://cdn.test/e$i", EmoteProvider.SevenTv)

/** An emote engine stand-in: which urls are decoded, how many queued lines protect each. */
private class FakeAhead : AheadEmotes {
    val ready = HashSet<String>()
    val protectedBy = HashMap<String, Int>()
    val requested = mutableListOf<Pair<String, Long>>()
    var neverReady = false
    override fun request(m: ChatMessage, releaseAtMs: Long) {
        requested += m.key to releaseAtMs
        for (u in m.imageUrls) protectedBy.merge(u, 1, Int::plus)
    }
    override fun isReady(m: ChatMessage) = !neverReady && m.imageUrls.all { it in ready }
    override fun release(m: ChatMessage, graceMs: Long) {
        for (u in m.imageUrls) protectedBy.merge(u, -1, Int::plus)
    }
}

class HoldPolicyTest {
    @Test fun holdIsTheLiveOffsetLessAMarginClampedToTwelveSeconds() {
        assertEquals(13_400L, 14_900L - 1_500L) // the Stable mode of the field report
        assertEquals(12_000L, HoldPolicy.targetMs(14_900, playing = true, enabled = true))
        assertEquals(3_500L, HoldPolicy.targetMs(5_000, true, true))
        assertEquals(0L, HoldPolicy.targetMs(1_000, true, true))
    }

    @Test fun noHoldWhenPausedUnknownOrSwitchedOff() {
        assertEquals(0L, HoldPolicy.targetMs(14_900, playing = false, enabled = true))
        assertEquals(0L, HoldPolicy.targetMs(null, true, true))
        assertEquals(0L, HoldPolicy.targetMs(14_900, true, enabled = false))
    }

    @Test fun theHoldGrowsAtOnceButShrinksAtMostHalfASecondPerSecond() {
        val h = HoldSmoother()
        assertEquals(10_000L, h.next(10_000, 0))
        assertEquals(10_000L, h.next(0, 0))
        assertEquals(9_000L, h.next(0, 2_000)) // 2 s later: down by 1 s
        assertEquals(12_000L, h.next(12_000, 2_000)) // growth is immediate
    }
}

class LookAheadQueueTest {
    @Test fun linesComeOutInArrivalOrderWhateverTheLatencyDoes() {
        val q = LookAheadQueue(ahead = { null })
        val h = HoldSmoother()
        var now = 0L
        val order = mutableListOf<String>()
        // 20 lines a second for 60 s while the live offset swings between 3 and 14 s (and a pause).
        var i = 0
        val target = { t: Long -> when ((t / 5_000) % 4) { 0L -> 12_000L; 1L -> 2_000L; 2L -> 0L; else -> 9_000L } }
        while (now < 60_000) {
            q.enqueue(msg("m${i++}"), now, h.next(target(now), now))
            now += 50
            q.poll(now).forEach { order += it.key }
        }
        while (q.size > 0) { now += 50; q.poll(now).forEach { order += it.key } }
        assertEquals((0 until i).map { "m$it" }, order)
    }

    @Test fun aLineIsReleasedAtArrivalPlusHold() {
        val q = LookAheadQueue(ahead = { null })
        val at = q.enqueue(msg("a"), 1_000, 12_000)
        assertEquals(13_000L, at)
        assertTrue(q.poll(12_999).isEmpty())
        assertEquals(listOf("a"), q.poll(13_000).map { it.key })
    }

    @Test fun aShorterHoldNeverOvertakesAnEarlierLine() {
        val q = LookAheadQueue(ahead = { null })
        q.enqueue(msg("a"), 0, 10_000)
        q.enqueue(msg("b"), 100, 0) // hold fell to 0: still behind "a"
        assertTrue(q.poll(9_999).isEmpty())
        assertEquals(listOf("a", "b"), q.poll(10_000).map { it.key })
    }

    @Test fun waitsHalfASecondForMissingEmotesThenShowsTheLine() {
        val ahead = FakeAhead()
        val q = LookAheadQueue(ahead = { ahead })
        val e = emote(1)
        q.enqueue(msg("a", emotes = listOf(e)), 0, 1_000)
        assertTrue(q.poll(1_000).isEmpty())
        assertTrue(q.poll(1_499).isEmpty())
        assertEquals(listOf("a"), q.poll(1_500).map { it.key })
        assertEquals(1, q.late)
        assertEquals(0, q.readyPercent)
    }

    @Test fun aLineWhoseEmotesArriveInTimeIsNotLate() {
        val ahead = FakeAhead()
        val q = LookAheadQueue(ahead = { ahead })
        q.enqueue(msg("a", emotes = listOf(emote(1))), 0, 1_000)
        ahead.ready += "https://cdn.test/e1"
        assertEquals(listOf("a"), q.poll(1_000).map { it.key })
        assertEquals(0, q.late)
        assertEquals(100, q.readyPercent)
    }

    @Test fun theEmotesOfQueuedLinesAreRequestedWhenQueuedAndStayProtectedUntilReleased() {
        val ahead = FakeAhead()
        val q = LookAheadQueue(ahead = { ahead })
        q.enqueue(msg("a", emotes = listOf(emote(1))), 0, 5_000)
        q.enqueue(msg("b", emotes = listOf(emote(1), emote(2))), 10, 5_000)
        assertEquals(listOf("a" to 5_000L, "b" to 5_010L), ahead.requested)
        assertEquals(2, ahead.protectedBy["https://cdn.test/e1"])
        ahead.ready += listOf("https://cdn.test/e1", "https://cdn.test/e2")
        q.poll(5_000)
        assertEquals(1, ahead.protectedBy["https://cdn.test/e1"])
        q.poll(5_010)
        assertEquals(0, ahead.protectedBy["https://cdn.test/e1"])
    }

    @Test fun moderationRemovesQueuedLinesAndReleasesTheirProtection() {
        val ahead = FakeAhead()
        val q = LookAheadQueue(ahead = { ahead })
        q.enqueue(msg("a", "bad", listOf(emote(1))), 0, 5_000)
        q.enqueue(msg("b", "ok"), 0, 5_000)
        q.enqueue(msg("c", "BAD"), 0, 5_000)
        assertEquals(2, q.removeUser("bad"))
        assertEquals(0, ahead.protectedBy["https://cdn.test/e1"])
        assertFalse(q.removeMessage("nope"))
        assertTrue(q.removeMessage("b"))
        assertEquals(0, q.size)
    }

    @Test fun thirtyMessagesASecondWithFourHundredEmotesAreAllReadyAtReleaseWithATenSecondHold() {
        // A fake decoder that finishes 60 emotes a second, in the order they were asked for.
        val ahead = object : AheadEmotes {
            val order = ArrayDeque<String>()
            val seen = HashSet<String>()
            val ready = HashSet<String>()
            override fun request(m: ChatMessage, releaseAtMs: Long) { for (u in m.imageUrls) if (seen.add(u)) order.addLast(u) }
            override fun isReady(m: ChatMessage) = m.imageUrls.all { it in ready }
            override fun release(m: ChatMessage, graceMs: Long) {}
        }
        val q = LookAheadQueue(ahead = { ahead })
        val rnd = kotlin.random.Random(3)
        val pool = (0 until 400).map(::emote)
        var now = 0L
        var released = 0
        repeat(60_000 / 33) { n ->
            now = n * 33L
            q.enqueue(msg("m$n", emotes = List(4) { pool[rnd.nextInt(pool.size)] }), now, 10_000)
            repeat(2) { if (ahead.order.isNotEmpty()) ahead.ready += ahead.order.removeFirst() } // 60 decodes a second
            released += q.poll(now).size
        }
        assertTrue(released > 0)
        assertTrue("ready ${q.readyPercent}% late ${q.late} of $released", q.readyPercent >= 99 && q.late <= released / 100)
    }
}

class ChatIntakeTest {
    private var now = 0L
    private val ahead = FakeAhead()
    private fun intake() = ChatIntake(ChatBuffer(150), LookAheadQueue(ahead = { ahead }), clockMs = { now }).also { it.sync = PlaybackSync(13_500, true) }
    private val ctx = ResolveContext(emptyMap(), emptyMap())
    private fun shown(i: ChatIntake) = i.buffer.snapshot().map { it.key }

    @Test fun liveLinesAreHeldAndThenShown() {
        val i = intake()
        assertFalse(i.message(msg("a"), bypass = false))
        assertEquals(emptyList<String>(), shown(i))
        now = 11_999; assertFalse(i.release())
        now = 12_000; assertTrue(i.release())
        assertEquals(listOf("a"), shown(i))
    }

    @Test fun ownMessagesUserNoticesAndSyncOffAreShownAtOnce() {
        val i = intake()
        i.own(msg("mine"))
        assertTrue(i.message(msg("announce"), bypass = true))
        assertEquals(listOf("announce", "mine"), shown(i)) // newest first
        i.enabled = false
        assertTrue(i.message(msg("x"), bypass = false))
        assertEquals("x", shown(i).first())
    }

    @Test fun noHoldWhenThePictureIsPausedOrItsDelayUnknown() {
        val i = intake()
        i.sync = PlaybackSync(13_500, playing = false)
        assertTrue(i.message(msg("a"), false))
        i.sync = PlaybackSync(null, true)
        assertTrue(i.message(msg("b"), false))
    }

    @Test fun moderationReachesQueuedAndShownLines() {
        val i = intake()
        i.sync = PlaybackSync(null, true)
        i.message(msg("shown1", "spammer"), false) // hold 0: shown
        i.sync = PlaybackSync(13_500, true)
        i.message(msg("queued1", "spammer"), false)
        i.message(msg("queued2", "friend"), false)
        assertTrue(i.moderate(ChatEvent.ClearChat("spammer", "1", 600, 0), ctx))
        assertEquals(1, i.queue.size)
        assertTrue(shown(i).none { it == "shown1" })
        assertTrue(i.moderate(ChatEvent.ClearMsg("queued2", "friend"), ctx))
        assertEquals(0, i.queue.size)
        i.message(msg("queued3"), false)
        i.moderate(ChatEvent.ClearChat(null, null, null, 0), ctx)
        assertEquals(0, i.queue.size)
    }

    @Test fun aQueuedLinesTimeoutStillLeavesANoticeInChat() {
        val i = intake()
        i.message(msg("q", "spammer"), false)
        i.moderate(ChatEvent.ClearChat("spammer", "1", 600, 0), ctx)
        assertTrue(i.buffer.snapshot().any { it is SystemLine && it.text.contains("spammer") })
    }

    @Test fun switchingSyncOffShowsWhatWaitsInOrder() {
        val i = intake()
        i.message(msg("a"), false); now = 10; i.message(msg("b"), false)
        i.enabled = false
        assertTrue(i.flush())
        assertEquals(listOf("b", "a"), shown(i))
        assertEquals(0, i.queue.size)
    }

    @Test fun theOverlayLine() {
        val i = intake()
        i.message(msg("a"), false)
        assertEquals("chat: hold 12.0 s queue 1 ready 100% late 0", i.line())
    }
}
