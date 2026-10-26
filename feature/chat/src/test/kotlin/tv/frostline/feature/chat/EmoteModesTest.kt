package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmoteRateTrackerTest {
    private fun feed(t: EmoteRateTracker, perSecond: Int, fromMs: Long, seconds: Int) {
        for (s in 0 until seconds) for (k in 0 until perSecond) t.record(fromMs + s * 1000L + k * (1000L / perSecond))
    }

    @Test fun bandsFollowTheThresholds() {
        assertEquals(EmoteMode.Slow, EmoteRateTracker.bandOf(2.9f))
        assertEquals(EmoteMode.Busy, EmoteRateTracker.bandOf(3f))
        assertEquals(EmoteMode.Busy, EmoteRateTracker.bandOf(8f))
        assertEquals(EmoteMode.Fast, EmoteRateTracker.bandOf(8.1f))
    }

    @Test fun rateIsMessagesPerSecondOverTheWindow() {
        val t = EmoteRateTracker()
        feed(t, 4, 0, 5)
        assertEquals(4f, t.rate(5_000), 0.3f)
        assertEquals(0f, t.rate(60_000), 0.01f)
    }

    @Test fun modeSwitchesOnlyAfterTheNewBandHolds() {
        val t = EmoteRateTracker()
        assertEquals(EmoteMode.Slow, t.update(0))
        feed(t, 12, 0, 12)
        // The window fills past 8/s quickly, but the switch waits 5 s.
        var mode = EmoteMode.Slow
        var firstFast = -1L
        for (sec in 1..12) {
            mode = t.update(sec * 1000L)
            if (mode == EmoteMode.Fast && firstFast < 0) firstFast = sec * 1000L
        }
        assertEquals(EmoteMode.Fast, mode)
        assertTrue("switched too early: $firstFast", firstFast >= 5_000)
    }

    @Test fun aBriefBurstDoesNotChangeTheMode() {
        val t = EmoteRateTracker()
        feed(t, 20, 0, 1) // one second of spam: 4 a second over the window, for under 5 s
        for (sec in 1..20) assertEquals(EmoteMode.Slow, t.update(sec * 1000L))
    }

    @Test fun fastChatSettlingBackToSlowTakesAHoldToo() {
        val t = EmoteRateTracker()
        feed(t, 12, 0, 12)
        for (sec in 1..12) t.update(sec * 1000L)
        assertEquals(EmoteMode.Fast, t.mode())
        // Silence: the rate falls out of the Fast band at about 14 s; the mode drops once that has held for 5 s.
        var dropAt = -1L
        for (sec in 13..40) if (t.update(sec * 1000L) != EmoteMode.Fast && dropAt < 0) dropAt = sec * 1000L
        assertTrue("dropAt=$dropAt", dropAt >= 19_000)
        assertEquals(EmoteMode.Slow, t.mode())
    }
}

class WeightedLruTest {
    @Test fun evictsLeastRecentlyUsedWhenOverTheByteBudget() {
        val evicted = mutableListOf<String>()
        val lru = WeightedLru<String, Int>(100, { it.toLong() }) { k, _ -> evicted += k }
        lru.put("a", 40); lru.put("b", 40)
        lru.get("a") // a is now newer than b
        lru.put("c", 40)
        assertEquals(listOf("b"), evicted)
        assertEquals(80L, lru.bytes)
        assertNull(lru.get("b"))
    }

    @Test fun replacingAKeyDoesNotLeakWeight() {
        val lru = WeightedLru<String, Int>(100, { it.toLong() })
        lru.put("a", 30); lru.put("a", 50)
        assertEquals(50L, lru.bytes)
        assertEquals(1, lru.size)
    }

    @Test fun aSingleOversizedEntryIsKeptButEverythingElseGoes() {
        val lru = WeightedLru<String, Int>(100, { it.toLong() })
        lru.put("a", 30)
        lru.put("big", 500)
        assertEquals(1, lru.size)
        assertEquals(500L, lru.bytes)
    }
}

class FrameIndexTest {
    @Test fun framesLoopWithTheSharedTick() {
        assertEquals(0, frameIndex(0, 5))
        assertEquals(4, frameIndex(4, 5))
        assertEquals(0, frameIndex(5, 5))
        assertEquals(2, frameIndex(12, 5))
        assertEquals(0, frameIndex(99, 1))
    }
}

class WeightedLruTierTest {
    private class V(val name: String, var tier: Int = 0, var protect: Boolean = false)

    @Test fun lowerTiersGoFirstThenLeastRecentlyUsed() {
        val gone = mutableListOf<String>()
        val lru = WeightedLru<String, V>(3, { 1L }, protect = { it.protect }, tier = { it.tier }) { k, _ -> gone += k }
        lru.put("oldCommon", V("oldCommon", tier = 2))
        lru.put("oldRecent", V("oldRecent", tier = 1))
        lru.put("stale", V("stale", tier = 0))
        lru.put("new", V("new", tier = 1)) // over the cap: the stale one goes although it is not the oldest
        assertEquals(listOf("stale"), gone)
        lru.put("new2", V("new2", tier = 1)) // then the oldest of tier 1, never the common one while others remain
        assertEquals(listOf("stale", "oldRecent"), gone)
    }

    @Test fun protectedEntriesAreNeverChosen() {
        val gone = mutableListOf<String>()
        val lru = WeightedLru<String, V>(2, { 1L }, protect = { it.protect }, tier = { it.tier }) { k, _ -> gone += k }
        lru.put("a", V("a", protect = true))
        lru.put("b", V("b", protect = true))
        lru.put("c", V("c"))
        assertEquals(emptyList<String>(), gone) // over the cap, but c is the newest and the others are protected
        assertTrue(lru.evictOldest())
        assertEquals(listOf("c"), gone)
        assertTrue(!lru.evictOldest()) // only protected entries are left
    }

    @Test fun removeIfSkipsProtectedAndCallsOnEvict() {
        val gone = mutableListOf<String>()
        val lru = WeightedLru<String, V>(100, { 1L }, protect = { it.protect }) { k, _ -> gone += k }
        lru.put("a", V("a")); lru.put("b", V("b", protect = true)); lru.put("c", V("c"))
        assertEquals(2, lru.removeIf { true })
        assertEquals(listOf("a", "c"), gone)
        assertEquals(1, lru.size)
    }
}
