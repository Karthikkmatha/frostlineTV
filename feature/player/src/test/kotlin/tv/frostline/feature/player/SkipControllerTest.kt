package tv.frostline.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkipControllerTest {
    private var pos = 100_000L
    private val seeks = mutableListOf<Long>()

    private fun TestScope.controller(duration: Long = 3_600_000L) = SkipController(
        scope = backgroundScope,
        position = { pos },
        duration = { duration },
        seekTo = { seeks += it },
        now = { testScheduler.currentTime },
    )

    @Test fun aSingleTapSeeksOnceAfterTheIdleTimeNotBefore() = runTest {
        val c = controller()
        c.press(SkipController.BACK_MS)
        assertEquals(-10_000L, c.state.value.deltaMs)
        assertEquals("−00:10", skipLabel(c.state.value.deltaMs))
        advanceTimeBy(599)
        assertTrue(seeks.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(90_000L), seeks)
        assertFalse(c.state.value.active)
    }

    @Test fun tapsAccumulateIntoOneSeek() = runTest {
        val c = controller()
        repeat(3) { c.press(SkipController.FORWARD_MS); advanceTimeBy(100) }
        assertEquals("+01:30", skipLabel(c.state.value.deltaMs))
        assertEquals(190_000L, c.state.value.targetMs)
        assertTrue("nothing yet", seeks.isEmpty())
        advanceTimeBy(600)
        assertEquals(listOf(190_000L), seeks)
    }

    @Test fun everyPressRestartsTheIdleTimer() = runTest {
        val c = controller()
        c.press(SkipController.FORWARD_MS)
        advanceTimeBy(500)
        c.press(SkipController.FORWARD_MS)
        advanceTimeBy(500) // 1000 ms after the first press, 500 after the second
        assertTrue(seeks.isEmpty())
        advanceTimeBy(101)
        assertEquals(listOf(160_000L), seeks)
    }

    @Test fun aHeldButtonAddsOneStepPerRepeatIntervalAndOnlyGrows() = runTest {
        val c = controller()
        c.press(SkipController.FORWARD_MS, repeat = false)
        val seen = mutableListOf(c.state.value.deltaMs)
        var t = 0
        while (t < 1_000) {
            advanceTimeBy(50); t += 50
            c.press(SkipController.FORWARD_MS, repeat = true) // key auto-repeat, 20 per second
            seen += c.state.value.deltaMs
        }
        // 1 press + a step at 250, 500, 750 and 1000 ms: five steps, not twenty-one.
        assertEquals(150_000L, c.state.value.deltaMs)
        assertEquals("+02:30", skipLabel(c.state.value.deltaMs))
        assertEquals("never shrinks while held", seen.sorted(), seen)
        assertTrue(seeks.isEmpty())
        advanceTimeBy(601)
        assertEquals(listOf(250_000L), seeks)
    }

    @Test fun oppositeDirectionsNetOut() = runTest {
        val c = controller()
        c.press(SkipController.FORWARD_MS)
        c.press(SkipController.BACK_MS)
        assertEquals("+00:20", skipLabel(c.state.value.deltaMs))
        c.press(SkipController.BACK_MS); c.press(SkipController.BACK_MS); c.press(SkipController.BACK_MS)
        assertEquals("−00:10", skipLabel(c.state.value.deltaMs))
        advanceTimeBy(601)
        assertEquals(listOf(90_000L), seeks)
    }

    @Test fun theTotalIsClampedToTheVideo() = runTest {
        pos = 5_000
        val c = controller(duration = 120_000)
        c.press(SkipController.BACK_MS)
        assertEquals(-5_000L, c.state.value.deltaMs)
        c.press(SkipController.BACK_MS)
        assertEquals("the counter does not grow past the start", -5_000L, c.state.value.deltaMs)
        advanceTimeBy(601)
        assertEquals(listOf(0L), seeks)

        pos = 100_000
        c.press(SkipController.FORWARD_MS)
        assertEquals(20_000L, c.state.value.deltaMs)
        advanceTimeBy(601)
        assertEquals(listOf(0L, 120_000L), seeks)
    }

    @Test fun eachSkipStartsFromTheCurrentPosition() = runTest {
        val c = controller()
        c.press(SkipController.FORWARD_MS)
        advanceTimeBy(601)
        pos = 130_000
        c.press(SkipController.BACK_MS)
        advanceTimeBy(601)
        assertEquals(listOf(130_000L, 120_000L), seeks)
    }

    @Test fun cancelDropsAPendingSkipWithoutSeeking() = runTest {
        val c = controller()
        c.press(SkipController.FORWARD_MS)
        c.cancel()
        advanceTimeBy(2_000)
        assertTrue(seeks.isEmpty())
        assertFalse(c.state.value.active)
    }

    @Test fun counterFormat() {
        assertNull(skipLabel(0))
        assertEquals("+00:30", skipLabel(30_000))
        assertEquals("−00:10", skipLabel(-10_000))
        assertEquals("+75:00", skipLabel(4_500_000))
        assertEquals("−01:05", skipLabel(-65_000))
    }

    @Test fun resumeIsOfferedOnlyOverThirtySecondsAndUnderNinetyFivePercent() {
        val d = 1_000_000L
        assertFalse(ResumePolicy.resumable(30_000, d))
        assertTrue(ResumePolicy.resumable(30_001, d))
        assertTrue(ResumePolicy.resumable(949_999, d))
        assertFalse(ResumePolicy.resumable(950_000, d))
        assertFalse(ResumePolicy.resumable(500_000, 0))
        assertFalse(ResumePolicy.resumable(0, d))
    }

    @Test fun savePolicyKeepsRemovesOrSaves() {
        val d = 1_000_000L
        assertEquals(ResumePolicy.Action.Save(600_000), ResumePolicy.decide(600_000, d))
        assertEquals(ResumePolicy.Action.Remove, ResumePolicy.decide(10_000, d)) // started over
        assertEquals(ResumePolicy.Action.Remove, ResumePolicy.decide(990_000, d)) // finished
        assertEquals(ResumePolicy.Action.Keep, ResumePolicy.decide(600_000, 0)) // duration unknown
    }
}
