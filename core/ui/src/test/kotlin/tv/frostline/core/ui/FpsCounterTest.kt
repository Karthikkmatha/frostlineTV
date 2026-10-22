package tv.frostline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsCounterTest {
    @Test fun countsFramesPerSecondAndResets() {
        val c = FpsCounter()
        repeat(50) { c.onFrame() }
        assertEquals(50, c.sample(1_000))
        assertEquals(0, c.sample(1_000))
    }

    @Test fun scalesToTheElapsedTime() {
        val c = FpsCounter()
        repeat(30) { c.onFrame() }
        assertEquals(60, c.sample(500))
        assertEquals(0, c.sample(0))
    }
}
