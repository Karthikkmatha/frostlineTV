package tv.frostline.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDecoder(val id: Int) { var released = false }

class LifecycleTest {
    private var created = 0
    private var alive = 0
    private var maxAlive = 0
    private val all = mutableListOf<FakeDecoder>()

    private fun slot() = ExclusiveSlot(
        create = {
            alive++
            maxAlive = maxOf(maxAlive, alive)
            FakeDecoder(++created).also { all += it }
        },
        destroy = {
            assertFalse("double release", it.released)
            it.released = true
            alive--
        },
    )

    @Test fun acquiringTwiceReleasesTheFirst() {
        val s = slot()
        val a = s.acquire()
        val b = s.acquire()
        assertTrue(a.released)
        assertFalse(b.released)
        assertSame(b, s.current.value)
        assertEquals(1, maxAlive)
    }

    @Test fun releaseOnlyAffectsTheNamedInstance() {
        val s = slot()
        val a = s.acquire()
        val stale = FakeDecoder(99)
        s.release(stale)
        assertFalse(a.released)
        s.release(a)
        assertTrue(a.released)
        assertNull(s.current.value)
        s.release() // idempotent
        assertEquals(0, alive)
    }

    @Test fun stopReleasesAndStartRecreates() {
        val s = slot()
        val lc = PlayerLifecycle(s, {}, {})
        val first = lc.start()
        assertSame(first, lc.start()) // idempotent
        assertTrue(lc.isActive)
        lc.stop() // onStop
        assertTrue(first.released)
        assertFalse(lc.isActive)
        lc.stop() // leaving after onStop: no double release
        val second = lc.start() // back in the foreground
        assertNotSame(first, second)
        lc.stop() // leaving the screen
        assertEquals(0, alive)
        assertEquals(1, maxAlive)
    }

    @Test fun hooksRunBeforeRelease() {
        val s = slot()
        val log = mutableListOf<String>()
        val lc = PlayerLifecycle(s, { log += "acquired${it.id}" }, { log += "releasing${it.id}:${it.released}" })
        lc.start()
        lc.stop()
        assertEquals(listOf("acquired1", "releasing1:false"), log)
    }

    @Test fun anotherScreenTakingTheSlotNeverOverlapsAndOldStopIsHarmless() {
        val s = slot()
        val oldScreen = PlayerLifecycle(s, {}, {})
        val newScreen = PlayerLifecycle(s, {}, {})
        val a = oldScreen.start()
        val b = newScreen.start() // new screen arrives while the old one is still fading out
        assertTrue(a.released)
        assertEquals(1, maxAlive)
        oldScreen.stop() // late dispose of the old screen must not kill the new decoder
        assertFalse(b.released)
        assertSame(b, s.current.value)
        newScreen.stop()
        assertEquals(0, alive)
    }

    @Test fun manyForegroundCyclesNeverLeakDecoders() {
        val s = slot()
        val lc = PlayerLifecycle(s, {}, {})
        repeat(25) {
            lc.start()
            lc.stop()
        }
        assertEquals(0, alive)
        assertEquals(1, maxAlive)
        assertEquals(25, all.count { it.released })
    }
}
