package tv.frostline.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmoteClockTest {
    @Test fun doesNotRunWithoutAnActiveFlagAndASubscriber() = runTest {
        val clock = EmoteClock(backgroundScope, 80)
        assertFalse(clock.running)
        clock.subscribe() // on screen, but the panel is hidden / player paused / setting off
        assertFalse(clock.running)
        clock.setActive(true)
        assertTrue(clock.running)
        clock.setActive(false)
        assertFalse(clock.running)
        clock.setActive(true)
        clock.unsubscribe() // the last emote left the screen
        assertFalse(clock.running)
    }

    @Test fun ticksOnceEveryFrameForAllSubscribers() = runTest {
        val clock = EmoteClock(backgroundScope, 80)
        clock.setActive(true)
        clock.subscribe()
        clock.subscribe()
        advanceTimeBy(81)
        assertEquals(1L, clock.tick)
        advanceTimeBy(80 * 11)
        assertEquals(12L, clock.tick) // 12.5 fps, one shared counter however many emotes
    }

    @Test fun stoppedClockDoesNotTick() = runTest {
        val clock = EmoteClock(backgroundScope, 80)
        clock.subscribe()
        advanceTimeBy(1_000)
        assertEquals(0L, clock.tick)
        clock.setActive(true)
        advanceTimeBy(161)
        assertEquals(2L, clock.tick)
        clock.setActive(false)
        advanceTimeBy(1_000)
        assertEquals(2L, clock.tick)
    }

    @Test fun defaultFrameRateIsInTheTwelveToFifteenRange() {
        val fps = 1000.0 / EmoteClock.FRAME_MS
        assertTrue(fps in 12.0..15.0)
    }

    @Test fun unsubscribeNeverGoesNegative() = runTest {
        val clock = EmoteClock(backgroundScope, 80)
        clock.unsubscribe()
        clock.setActive(true)
        clock.subscribe()
        assertTrue(clock.running)
    }
}
