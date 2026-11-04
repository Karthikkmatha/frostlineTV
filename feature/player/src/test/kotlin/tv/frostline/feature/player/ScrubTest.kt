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

    private var t = 1_000L
    private var pos = 600_000L
    private val seeks = mutableListOf<Long>()
    private fun controller(duration: Long = hour) =
        ScrubController(position = { pos }, duration = { duration }, seekTo = { seeks += it }, now = { t })

    /** Runs the clock forward [ms] in 50 ms ticks (the ViewModel's ticker), stopping early when the hold is over. */
    private fun advance(c: ScrubController, ms: Long) {
        val end = t + ms
        while (t < end) { t += Scrub.TICK_MS; c.tick() }
    }

    // ---- speed ----

    @Test fun theSpeedClimbsWithHoldTimeAndIsCappedByTheBroadcast() {
        val d = 46 * hour
        assertEquals(20L, Scrub.speed(0, d))
        assertEquals(60L, Scrub.speed(Scrub.RUNG_MS, d))
        assertEquals(180L, Scrub.speed(2 * Scrub.RUNG_MS, d))
        assertEquals(600L, Scrub.speed(3 * Scrub.RUNG_MS, d))
        assertEquals(60_000L, Scrub.speed(60_000, 1000 * hour))
        assertEquals(20L, Scrub.speed(-5, d))
        assertEquals(d / 4000, Scrub.maxSpeed(d))
        assertEquals(900L, Scrub.maxSpeed(hour + 1)) // over an hour: duration / 4 s, which is above the 5 min/s floor
        assertEquals(1800L, Scrub.maxSpeed(2 * hour))
        assertEquals(300L, Scrub.maxSpeed(0)) // unknown duration: 5 min/s
        assertTrue(Scrub.maxSpeed(hour + 1) >= 300L)
        assertEquals(225L, Scrub.maxSpeed(15 * min))
        assertEquals(225L, Scrub.speed(60_000, 15 * min))
    }

    @Test fun theLabelsReadAsSpeeds() {
        assertEquals(listOf("20 s/s", "1 min/s", "3 min/s", "10 min/s", "30 min/s", "100 min/s", "300 min/s", "1000 min/s"), Scrub.SPEEDS.map(Scrub::speedLabel))
        assertEquals("\u25B6\u25B6 10 min/s", Scrub.stepText(1, 600))
        assertEquals("\u25C0\u25C0 10 s", Scrub.stepText(-1, 0))
        assertEquals("\u25B6\u25B6 10 s", Scrub.stepText(1, 0))
        assertEquals("3.8 min/s", Scrub.speedLabel(225))
    }

    @Test fun tapTargetMovesTenSecondsAndClamps() {
        assertEquals(110_000L, Scrub.tapTarget(100_000, 1, hour))
        assertEquals(90_000L, Scrub.tapTarget(100_000, -1, hour))
        assertEquals(0L, Scrub.tapTarget(5_000, -1, hour))
        assertEquals(hour, Scrub.tapTarget(hour - 3_000, 1, hour))
        assertEquals(20_000L, Scrub.tapTarget(10_000, 1, 0)) // unknown duration: only 0 clamps
    }

    // ---- the Google TV Streamer remote: DOWN+UP pairs about 350 ms apart ----

    private val streamerGaps = longArrayOf(320, 350, 330, 380, 350, 340, 360, 330)

    /** Presses Right the way the Streamer remote reports a hold: DOWN+UP at the same instant, every ~350 ms; the ticker runs between. */
    private fun holdLikeTheStreamer(c: ScrubController, forMs: Long) {
        val start = t
        var i = 0
        c.key(1, 0); c.release()
        while (t - start < forMs) {
            val next = t + streamerGaps[i++ % streamerGaps.size]
            while (t < next && t - start < forMs) { t = minOf(t + Scrub.TICK_MS, next); c.tick() }
            if (t == next) { c.key(1, 0); c.release() }
        }
    }

    @Test fun theStreamerRemotesDownUpPairsAreOneContinuousHold() {
        val h = KeyHold()
        var tt = 0L
        assertFalse(h.down(1, 0, tt)) // the press
        for (gap in streamerGaps) {
            tt += gap
            assertTrue("gap $gap", h.down(1, 0, tt))
            assertTrue(h.holding(tt))
        }
        assertEquals(streamerGaps.size + 1, h.events)
    }

    @Test fun aSingleDownUpIsOneTenSecondStep() {
        val c = controller()
        c.key(1, 0); c.release()
        assertEquals(610_000L, c.preview.value)
        assertEquals(ScrubStatus(1, 0L), c.status.value)
        advance(c, 2_000) // nothing else comes: the ticker never moves it
        assertEquals(610_000L, c.preview.value)
        assertFalse(c.isHolding())
        assertTrue(seeks.isEmpty())
    }

    @Test fun holdingOnTheStreamerRemoteCrossesAtLeastAQuarterOfA46HourVodInSixSeconds() {
        val d = 46 * hour
        pos = 0
        val c = controller(d)
        holdLikeTheStreamer(c, 6_000)
        val p = c.preview.value!!
        println("46 h VOD, 6 s hold: preview at ${p / hour} h (${100 * p / d} %), speed ${c.status.value}")
        assertTrue("reached $p of $d", p >= d / 4)
        assertTrue(c.status.value!!.speed > 600L)
    }

    @Test fun theLabelShowsTheSpeedWhileHoldingAndTenSecondsForATap() {
        val c = controller(10 * hour)
        pos = 0
        holdLikeTheStreamer(c, 2_500)
        val speed = c.status.value!!.speed
        assertTrue(speed > 20L)
        assertEquals("\u25B6\u25B6 " + Scrub.speedLabel(speed), Scrub.stepText(1, speed))
        t += 2_000; c.tick() // released: back to the tap label
        assertEquals(ScrubStatus(1, 0L), c.status.value)
        assertEquals("\u25B6\u25B6 10 s", Scrub.stepText(1, c.status.value!!.speed))
    }

    @Test fun theHoldEndsSixHundredMillisecondsAfterTheLastDownAndTheNextPressIsATapAgain() {
        val c = controller(10 * hour)
        pos = 0
        holdLikeTheStreamer(c, 3_000)
        assertTrue(c.isHolding())
        val at = c.preview.value!!
        advance(c, 1_000)
        assertFalse(c.isHolding())
        val after = c.preview.value!!
        // it coasts for at most 400 ms after the last event, then holds still
        assertTrue("released: back near the last DOWN, $after vs $at", after <= at + 60_000L * Scrub.COAST_MS)
        advance(c, 1_000)
        assertEquals(after, c.preview.value)
        c.key(1, 0)
        assertEquals(minOf(after + 10_000L, 10 * hour), c.preview.value)
        assertEquals(ScrubStatus(1, 0L), c.status.value)
    }

    @Test fun releasingAtTopSpeedOn46HoursLandsWithinOneIntervalOfTheLastDown() {
        val d = 46 * hour
        pos = 0
        val c = controller(d)
        holdLikeTheStreamer(c, 6_000)
        val lastDown = c.preview.value!! // read at the last tick; find the preview at the last DOWN below
        // replay: hold, then record the preview right after the final DOWN
        pos = 0; t = 1_000
        val c2 = controller(d)
        var i = 0
        c2.key(1, 0)
        var atLastDown = 0L
        var speedAtLastDown = 0L
        val start = t
        var lastDownT = t
        while (t - start < 6_000) {
            val next = t + streamerGaps[i++ % streamerGaps.size]
            while (t < next) { t = minOf(t + Scrub.TICK_MS, next); c2.tick() }
            c2.key(1, 0)
            atLastDown = c2.preview.value!!; speedAtLastDown = c2.status.value!!.speed; lastDownT = t
        }
        advance(c2, 1_500) // released: the hold times out
        assertFalse(c2.isHolding())
        val fin = c2.preview.value!!
        val oneInterval = speedAtLastDown * 350
        println("46 h release: at last DOWN ${atLastDown / 1000} s, final ${fin / 1000} s, allowed ${oneInterval / 1000} s past, speed $speedAtLastDown")
        assertTrue("speed $speedAtLastDown", speedAtLastDown > 10_000)
        assertTrue(fin >= atLastDown && fin - atLastDown <= oneInterval)
        assertEquals(atLastDown + oneInterval, fin)
        assertTrue(lastDown > 0 && lastDownT > start)
        advance(c2, 1_000)
        assertEquals(fin, c2.preview.value) // stays put
    }

    @Test fun theTickerNeverRunsMoreThanFourHundredMillisecondsPastTheLastDown() {
        val c = controller(46 * hour)
        pos = 0
        c.key(1, 0)
        t += 300; c.key(1, 0)
        val at = c.preview.value!!
        var maxSeen = at
        repeat(10) { t += 50; c.tick(); if (t - 1_300 < 600) maxSeen = maxOf(maxSeen, c.preview.value!!) }
        val speed = Scrub.speed(1_000, 46 * hour)
        assertTrue("moved ${maxSeen - at} at $at max $maxSeen speed $speed", maxSeen - at <= speed * Scrub.COAST_MS)
    }

    @Test fun aTrueRepeatStreamStillWorks() {
        val d = 10 * hour
        pos = 0
        val c = controller(d)
        c.key(1, 0)
        var n = 0
        val start = t
        while (t - start < 6_000) { t += 40; c.key(1, ++n); c.tick() }
        assertTrue("moved ${c.preview.value}", c.preview.value!! >= d / 4)
    }

    @Test fun aRepeatStreamWithTheInitialRepeatDelayContinuesTheHold() {
        val c = controller(10 * hour)
        c.key(1, 0)
        t += 450; c.key(1, 1)   // the first repeat 450 ms after the press
        assertTrue(c.isHolding())
        t += 40; c.key(1, 2)
        assertTrue(c.isHolding())
    }

    @Test fun aDirectionChangeIsANewTap() {
        val c = controller(10 * hour)
        pos = 5 * hour
        holdLikeTheStreamer(c, 2_000)
        val p = c.preview.value!!
        t += 100
        c.key(-1, 0)
        assertEquals(p - 10_000L, c.preview.value)
        assertEquals(ScrubStatus(-1, 0L), c.status.value)
        assertFalse(c.isHolding())
    }

    @Test fun holdingBackwardsClampsAtZeroAndForwardsAtTheEnd() {
        val c = controller(hour)
        pos = 60_000
        c.key(-1, 0)
        repeat(60) { t += 100; c.key(-1, 0); c.tick() }
        assertEquals(0L, c.preview.value)
        val d = controller(hour)
        pos = hour - 60_000
        d.key(1, 0)
        repeat(60) { t += 100; d.key(1, 0); d.tick() }
        assertEquals(hour, d.preview.value)
    }

    @Test fun anyOtherActionEndsTheHold() {
        val c = controller(10 * hour)
        holdLikeTheStreamer(c, 1_500)
        c.cancel()
        assertFalse(c.isHolding())
        assertNull(c.preview.value)
        assertFalse(c.tick())
        assertNull(c.preview.value)
    }

    @Test fun theDebugLineShowsSpeedHoldAndEvents() {
        assertEquals("scrub speed 10 min/s held 3.2 s events 18", ScrubInfo(600, 3_200, 18).line())
        assertEquals("scrub speed tap held 0.0 s events 1", ScrubInfo(0, 0, 1).line())
    }

    @Test fun commitSeeksOnceAndClears() {
        val c = controller()
        c.key(1, 0); c.release(); t += 1_000; c.key(1, 0)
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

    @Test fun previewIsClampedToTheVideo() {
        val c = controller(duration = 605_000)
        c.key(1, 0)
        assertEquals(605_000L, c.preview.value)
        pos = 3_000
        val d = controller()
        d.key(-1, 0)
        assertEquals(0L, d.preview.value)
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
