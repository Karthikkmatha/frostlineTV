package tv.frostline.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.twitchinternal.VideoCodec

class ScrubTest {
    private val min = 60_000L
    private val hour = 3_600_000L

    // ---- the ladder over time ----

    @Test fun theLadderClimbsOneRungPerEightHundredMillisecondsOfHold() {
        val d = 10 * hour
        val expect = listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L)
        expect.forEachIndexed { i, step ->
            assertEquals("rung $i", step, Scrub.stepMs(i * Scrub.RUNG_MS, d))
            assertEquals("rung $i just before the next", step, Scrub.stepMs((i + 1) * Scrub.RUNG_MS - 1, d))
        }
        assertEquals(1_800_000L, Scrub.stepMs(60_000, d))
        assertEquals(10_000L, Scrub.stepMs(0, d))
        assertEquals(10_000L, Scrub.stepMs(-5, d))
    }

    @Test fun theLabelsReadAsSecondsAndMinutes() {
        assertEquals(listOf("10 s", "30 s", "1 min", "2 min", "5 min", "10 min", "30 min"), Scrub.LADDER_MS.map(Scrub::stepLabel))
        assertEquals("\u25B6\u25B6 10 min", Scrub.stepText(1, 600_000))
        assertEquals("\u25C0\u25C0 30 s", Scrub.stepText(-1, 30_000))
    }

    // ---- the cap per duration ----

    @Test fun theTopRungIsAboutDurationOverFortyRoundedUpToTheLadder() {
        assertEquals(2 * min, Scrub.topRungMs(hour))            // 1.5 min -> 2 min
        assertEquals(5 * min, Scrub.topRungMs(3 * hour))        // 4.5 min -> 5 min
        assertEquals(30 * min, Scrub.topRungMs(10 * hour))      // 15 min -> 30 min
        assertEquals(10 * min, Scrub.topRungMs(6 * hour))       // 9 min -> 10 min
        assertEquals(30 * min, Scrub.topRungMs(40 * hour))      // never above the ladder
    }

    @Test fun overAnHourNeverBelowFiveMinutes() {
        assertEquals(5 * min, Scrub.topRungMs(hour + 1))
        assertEquals(5 * min, Scrub.topRungMs(90 * min))
        assertTrue(Scrub.topRungMs(2 * hour) >= 5 * min)
    }

    @Test fun shortBroadcastsKeepSmallSteps() {
        assertEquals(10_000L, Scrub.topRungMs(5 * min))
        assertEquals(30_000L, Scrub.topRungMs(15 * min))
        assertEquals(5 * min, Scrub.topRungMs(0)) // unknown
        assertEquals(30_000L, Scrub.stepMs(60_000, 15 * min)) // the cap holds however long the key is held
    }

    @Test fun scrubTargetMovesByTheStepAndClamps() {
        assertEquals(110_000L, Scrub.scrubTarget(100_000, 1, 0, hour))
        assertEquals(90_000L, Scrub.scrubTarget(100_000, -1, 0, hour))
        assertEquals(130_000L, Scrub.scrubTarget(100_000, 1, 800, hour))
        assertEquals(0L, Scrub.scrubTarget(5_000, -1, 0, hour))
        assertEquals(0L, Scrub.scrubTarget(100_000, -1, 5_000, hour))
        assertEquals(hour, Scrub.scrubTarget(hour - 3_000, 1, 0, hour))
        assertEquals(hour, Scrub.scrubTarget(hour - 50_000, 1, 5_000, hour))
    }

    @Test fun unknownDurationOnlyClampsAtZero() {
        assertEquals(10_000L + 300_000L, Scrub.scrubTarget(10_000, 1, 60_000, 0))
        assertEquals(0L, Scrub.scrubTarget(10_000, -1, 5_000, 0))
    }

    private var t = 0L
    private var pos = 600_000L
    private val seeks = mutableListOf<Long>()
    private fun controller(duration: Long = hour) =
        ScrubController(position = { pos }, duration = { duration }, seekTo = { seeks += it }, now = { t })

    /** Holds Right from the start of the broadcast with a key-repeat every [everyMs]; returns how long the preview took to reach the end. */
    private fun holdToTheEnd(duration: Long, everyMs: Long = 40, withRepeatCount: Boolean = true): Long {
        t = 0; pos = 0
        val c = controller(duration)
        var n = 0
        c.key(1, 0)
        while (c.preview.value!! < duration && t < 60_000) {
            t += everyMs; n++
            c.key(1, if (withRepeatCount) n else 0)
        }
        return t
    }

    @Test fun aFullHoldCrossesTheWholeBroadcastInAFewSecondsWhateverItsLength() {
        val oneHour = holdToTheEnd(hour)
        val threeHours = holdToTheEnd(3 * hour)
        val tenHours = holdToTheEnd(10 * hour)
        println("hold to cross: 1 h ${oneHour} ms, 3 h ${threeHours} ms, 10 h ${tenHours} ms")
        for (ms in listOf(oneHour, threeHours, tenHours)) assertTrue("crossed in $ms ms", ms in 4_000..8_000)
    }

    @Test fun holdingWorksWithoutAnyRepeatCount() {
        val withCount = holdToTheEnd(3 * hour, withRepeatCount = true)
        val without = holdToTheEnd(3 * hour, withRepeatCount = false)
        assertEquals(withCount, without)
    }

    @Test fun slowRepeatsOfOneHundredMillisecondsStillCrossAndAreNotThrottledAway() {
        val ms = holdToTheEnd(3 * hour, everyMs = 100, withRepeatCount = false)
        assertTrue("crossed in $ms ms", ms in 4_000..10_000)
    }

    // ---- taps, throttle, hold detection ----

    @Test fun aTapMovesTheBarTenSecondsWithoutSeeking() {
        val c = controller()
        assertNull(c.preview.value)
        c.key(1, 0); c.release()
        assertEquals(610_000L, c.preview.value)
        t = 500
        c.key(-1, 0); c.release()
        t = 1_000
        c.key(-1, 0); c.release()
        assertEquals(590_000L, c.preview.value)
        assertTrue("the video keeps playing where it is", seeks.isEmpty())
    }

    @Test fun repeatsInsideTheThrottleAreIgnored() {
        val c = controller()
        c.key(1, 0)
        t = 50; c.key(1, 1)
        assertEquals(610_000L, c.preview.value)
        t = 130; c.key(1, 2)
        assertEquals(620_000L, c.preview.value)
    }

    @Test fun releasingResetsToTheTapStep() {
        val c = controller(10 * hour)
        c.key(1, 0)
        var n = 0
        while (t < 3_000) { t += 40; c.key(1, ++n) }
        assertEquals(Scrub.stepMs(3_000, 10 * hour), c.status.value!!.stepMs)
        assertTrue(c.status.value!!.stepMs > 10_000)
        c.release()
        assertEquals(10_000L, c.status.value!!.stepMs)
        val before = c.preview.value!!
        t += 1_000; c.key(1, 0)
        assertEquals(before + 10_000L, c.preview.value)
    }

    @Test fun aGapOfThreeHundredMillisecondsWithoutAKeyUpEndsTheHold() {
        val c = controller(10 * hour)
        c.key(1, 0)
        var n = 0
        while (t < 3_000) { t += 40; c.key(1, 0); n++ }
        assertTrue(c.info.value!!.stepMs > 10_000)
        t += 350; c.key(1, 0) // no KeyUp was seen, but nothing came for 350 ms: this is a new press
        assertEquals(10_000L, c.info.value!!.stepMs)
        assertEquals(0, c.info.value!!.repeats)
    }

    @Test fun theFirstRepeatMayComeFourToFiveHundredMillisecondsAfterThePress() {
        val c = controller(10 * hour)
        c.key(1, 0)
        t = 450; c.key(1, 0)      // the remote's initial repeat delay
        t = 570; c.key(1, 0)
        assertEquals(2, c.info.value!!.repeats)
        assertEquals(570L, c.info.value!!.heldMs)
    }

    @Test fun aDirectionChangeStartsANewHold() {
        val c = controller(10 * hour)
        c.key(1, 0)
        t = 1_000; c.key(1, 1)
        val p = c.preview.value!!
        t = 1_130; c.key(-1, 0)
        assertEquals(p - 10_000L, c.preview.value)
        assertEquals(-1, c.status.value!!.dir)
    }

    @Test fun theDebugLineShowsStepHoldAndRepeats() {
        assertEquals("scrub step 5 min held 3.2 s repeats 18", ScrubInfo(300_000, 3_200, 18).line())
        val c = controller(3 * hour)
        c.key(1, 0)
        var n = 0
        while (t < 4_000) { t += 40; c.key(1, ++n) }
        val line = c.info.value!!.line()
        assertTrue(line, line.startsWith("scrub step 5 min held 4.0") && line.contains("repeats "))
    }

    @Test fun previewIsClampedToTheVideo() {
        val c = controller(duration = 605_000)
        c.key(1, 0)
        assertEquals(605_000L, c.preview.value)
        pos = 3_000
        val d = controller()
        d.key(-1, 0)
        assertEquals(0L, d.preview.value)
    }

    @Test fun commitSeeksOnceAndClears() {
        val c = controller()
        c.key(1, 0); c.release(); t += 500; c.key(1, 0)
        assertTrue(c.commit())
        assertEquals(listOf(620_000L), seeks)
        assertNull(c.preview.value)
        assertNull(c.status.value)
        assertFalse("nothing left to commit", c.commit())
        assertEquals(1, seeks.size)
    }

    @Test fun cancelDropsThePreviewWithoutSeeking() {
        val c = controller()
        c.key(-1, 0)
        c.cancel()
        assertNull(c.preview.value)
        assertTrue(seeks.isEmpty())
        t += 1_000
        c.key(1, 0) // a fresh preview starts from the playhead again
        assertEquals(610_000L, c.preview.value)
    }
}

class CodecLabelTest {
    @Test fun bufferIsThirtyTwoMegabytes() {
        assertEquals(32 * 1024 * 1024, STABLE_BUFFER_BYTES)
    }

    @Test fun codecNamesFromMimeTypes() {
        assertEquals("hevc", codecName("video/hevc"))
        assertEquals("h264", codecName("video/avc"))
        assertEquals("av1", codecName("video/av01"))
        assertEquals("", codecName(null))
        assertEquals(VideoCodec.Hevc.mime, "video/hevc")
    }

    @Test fun overlayVideoLineShowsCodecAndDecoder() {
        val m = MetricsSnapshot(videoBitrate = 12_000_000, width = 2560, height = 1440, codec = "hevc", decoder = "c2.mtk.hevc.decoder")
        assertEquals("video    2560x1440 @ 12000 kbps hevc (c2.mtk.hevc.decoder)", videoLine(m))
        assertEquals("video    1920x1080 @ 6000 kbps", videoLine(MetricsSnapshot(videoBitrate = 6_000_000, width = 1920, height = 1080)))
    }
}
