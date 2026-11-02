package tv.frostline.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.twitchinternal.VideoCodec

class ScrubTest {
    private val hour = 3_600_000L

    @Test fun stepsGrowWithHoldTime() {
        assertEquals(10_000L, Scrub.stepMs(0))
        assertEquals(10_000L, Scrub.stepMs(1_499))
        assertEquals(60_000L, Scrub.stepMs(1_500))
        assertEquals(60_000L, Scrub.stepMs(3_999))
        assertEquals(300_000L, Scrub.stepMs(4_000))
        assertEquals(300_000L, Scrub.stepMs(60_000))
    }

    @Test fun scrubTargetMovesByTheStepAndClamps() {
        assertEquals(110_000L, Scrub.scrubTarget(100_000, 1, 0, hour))
        assertEquals(90_000L, Scrub.scrubTarget(100_000, -1, 0, hour))
        assertEquals(160_000L, Scrub.scrubTarget(100_000, 1, 2_000, hour))
        assertEquals(400_000L, Scrub.scrubTarget(100_000, 1, 5_000, hour))
        assertEquals(0L, Scrub.scrubTarget(5_000, -1, 0, hour))
        assertEquals(0L, Scrub.scrubTarget(100_000, -1, 5_000, hour))
        assertEquals(hour, Scrub.scrubTarget(hour - 3_000, 1, 0, hour))
        assertEquals(hour, Scrub.scrubTarget(hour - 200_000, 1, 5_000, hour))
    }

    @Test fun unknownDurationOnlyClampsAtZero() {
        assertEquals(310_000L, Scrub.scrubTarget(10_000, 1, 5_000, 0))
        assertEquals(0L, Scrub.scrubTarget(10_000, -1, 5_000, 0))
    }

    private var t = 0L
    private var pos = 600_000L
    private val seeks = mutableListOf<Long>()
    private fun controller(duration: Long = hour) =
        ScrubController(position = { pos }, duration = { duration }, seekTo = { seeks += it }, now = { t })

    @Test fun aTapMovesTheBarTenSecondsWithoutSeeking() {
        val c = controller()
        assertNull(c.preview.value)
        c.step(1, repeat = false)
        assertEquals(610_000L, c.preview.value)
        c.step(-1, repeat = false)
        c.step(-1, repeat = false)
        assertEquals(590_000L, c.preview.value)
        assertTrue("the video keeps playing where it is", seeks.isEmpty())
    }

    @Test fun holdingAcceleratesAndRepeatsAreThrottled() {
        val c = controller()
        c.step(1, repeat = false)           // t=0: +10 s
        t = 50; c.step(1, repeat = true)    // inside 120 ms: ignored
        assertEquals(610_000L, c.preview.value)
        t = 130; c.step(1, repeat = true)   // +10 s
        assertEquals(620_000L, c.preview.value)
        t = 1_600; c.step(1, repeat = true) // held 1.6 s: +60 s
        assertEquals(680_000L, c.preview.value)
        t = 4_100; c.step(1, repeat = true) // held 4.1 s: +300 s
        assertEquals(980_000L, c.preview.value)
        t = 4_150; c.step(1, repeat = true) // throttled
        assertEquals(980_000L, c.preview.value)
    }

    @Test fun aNewPressResetsTheHold() {
        val c = controller()
        c.step(1, false)
        t = 5_000; c.step(1, true)
        assertEquals(610_000L + 300_000L, c.preview.value)
        t = 6_000; c.step(1, false) // key released and pressed again
        assertEquals(920_000L, c.preview.value)
        t = 6_200; c.step(-1, true) // direction change also restarts the hold
        assertEquals(910_000L, c.preview.value)
    }

    @Test fun previewIsClampedToTheVideo() {
        val c = controller(duration = 605_000)
        c.step(1, false)
        assertEquals(605_000L, c.preview.value)
        pos = 3_000
        val d = controller()
        d.step(-1, false)
        assertEquals(0L, d.preview.value)
    }

    @Test fun commitSeeksOnceAndClears() {
        val c = controller()
        c.step(1, false); c.step(1, false)
        assertTrue(c.commit())
        assertEquals(listOf(620_000L), seeks)
        assertNull(c.preview.value)
        assertFalse("nothing left to commit", c.commit())
        assertEquals(1, seeks.size)
    }

    @Test fun cancelDropsThePreviewWithoutSeeking() {
        val c = controller()
        c.step(-1, false)
        c.cancel()
        assertNull(c.preview.value)
        assertTrue(seeks.isEmpty())
        c.step(1, false) // a fresh preview starts from the playhead again
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
