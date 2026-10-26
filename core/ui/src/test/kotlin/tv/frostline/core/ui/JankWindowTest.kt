package tv.frostline.core.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JankWindowTest {
    @Test fun countsFramesAndJankInsideTheWindow() {
        val w = JankWindow(10_000)
        for (i in 0 until 100) w.record(1_000L + i * 16, isJank = i % 10 == 0)
        assertEquals(100 to 10, w.counts(3_000))
        assertEquals("jank     10.0% (10/100 frames, 10 s)", w.line(3_000))
    }

    @Test fun framesOlderThanTheWindowAreForgotten() {
        val w = JankWindow(10_000)
        w.record(0, true)
        w.record(5_000, false)
        w.record(12_000, true)
        assertEquals(2 to 1, w.counts(12_000)) // the frame at 0 is 12 s old
        assertEquals(1 to 1, w.counts(21_000)) // only the one at 12 000 is left
    }

    @Test fun emptyWindowSaysSo() {
        assertTrue(JankWindow().line(0).contains("no frames"))
    }

    @Test fun ringOverwritesTheOldest() {
        val w = JankWindow(1_000_000, capacity = 4)
        for (i in 0 until 10) w.record(i.toLong(), isJank = i < 6)
        assertEquals(4 to 0, w.counts(10)) // frames 6..9, none janky
    }

    @Test fun startupGateWaitsForTheFirstFrameThenTheExtraDelay() = runBlocking {
        val gate = StartupGate
        // Not completed yet in a fresh JVM (other tests never call onFirstFrame).
        assertFalse(gate.firstFrameDrawn)
        gate.onFirstFrame()
        val t0 = System.nanoTime()
        gate.awaitIdle(extraMs = 30)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 25)
        assertTrue(gate.firstFrameDrawn)
    }
}
