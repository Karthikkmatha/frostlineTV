package tv.frostline.feature.player

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.frostline.core.twitchinternal.RaidEvent

class LiveBadgeTest {
    @Test fun lowLatencyShowsSeconds() {
        val b = liveBadge(3_200)
        assertEquals(LiveState.LowLatency, b.state)
        assertEquals("LIVE  3.2 s", b.text.replace(',', '.'))
    }

    @Test fun farBehindIsFlagged() {
        val b = liveBadge(14_900)
        assertEquals(LiveState.Behind, b.state)
        assertEquals("14 s behind live", b.text)
    }

    @Test fun unknownOffsetStillSaysLive() {
        assertEquals(LiveState.Unknown, liveBadge(null).state)
        assertEquals("LIVE", liveBadge(-1).text)
    }
}

class LiveModeConfigTest {
    @Test fun stableSitsTwelveToFourteenSecondsBehind() {
        val c = LiveOffsets.configFor(tv.frostline.core.data.LiveMode.Stable)
        assertEquals(13_000L, c.targetOffsetMs)
        assertEquals(12_000L, c.minOffsetMs)
        assertEquals(14_000L, c.maxOffsetMs)
    }

    @Test fun lowLatencyTargetsAboutFourSeconds() {
        val c = LiveOffsets.configFor(tv.frostline.core.data.LiveMode.LowLatency)
        assertEquals(4_000L, c.targetOffsetMs)
        assertEquals(3_000L, c.minOffsetMs)
    }

    @Test fun stableBadgeCallsThirteenSecondsLiveNotBehind() {
        assertEquals(LiveState.LowLatency, liveBadge(13_000, STABLE_EDGE_MS).state)
        assertEquals(LiveState.Behind, liveBadge(13_000).state)
    }
}

class MetricsAccumulatorTest {
    private var now = 1_000L
    private val m = MetricsAccumulator { now }

    @Test fun startupIsLoadToFirstFrame() {
        m.onLoadStart()
        now += 840
        m.onFirstFrame()
        now += 5_000
        m.onFirstFrame() // later frames do not change it
        assertEquals(840L, m.snapshot.startupMs)
    }

    @Test fun reloadResetsStartup() {
        m.onLoadStart(); now += 100; m.onFirstFrame()
        m.onLoadStart()
        assertNull(m.snapshot.startupMs)
        now += 300; m.onFirstFrame()
        assertEquals(300L, m.snapshot.startupMs)
    }

    @Test fun initialBufferingIsNotARebuffer() {
        m.onLoadStart()
        m.onPlaybackState(Player.STATE_BUFFERING)
        m.onPlaybackState(Player.STATE_READY)
        assertEquals(0, m.snapshot.rebuffers)
    }

    @Test fun rebuffersAreCountedWithDuration() {
        m.onLoadStart()
        m.onPlaybackState(Player.STATE_READY)
        m.onPlaybackState(Player.STATE_BUFFERING)
        m.onPlaybackState(Player.STATE_BUFFERING) // duplicate event
        now += 700
        m.onPlaybackState(Player.STATE_READY)
        m.onPlaybackState(Player.STATE_BUFFERING)
        now += 300
        m.onPlaybackState(Player.STATE_READY)
        assertEquals(2, m.snapshot.rebuffers)
        assertEquals(1_000L, m.snapshot.rebufferMs)
    }

    @Test fun droppedFramesAndBitrateAccumulate() {
        m.onDropped(3); m.onDropped(4)
        m.onVideoFormat(6_000_000, 1920, 1080)
        m.onBandwidth(12_000_000)
        val s = m.snapshot
        assertEquals(7, s.droppedFrames)
        assertEquals(6_000_000, s.videoBitrate)
        assertEquals(1080, s.height)
        assertEquals(12_000_000L, s.bandwidthBps)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RaidCountdownTest {
    private fun update(id: String = "r1", target: String = "foo") = RaidEvent.Update(id, target, target.uppercase(), 100)

    @Test fun countsDownThenSwitches() = runTest {
        val switched = mutableListOf<String>()
        val c = RaidCountdown(this, 10) { switched += it }
        c.onEvent(update())
        runCurrent()
        assertEquals(10, c.notice.value!!.secondsLeft)
        assertEquals("FOO", c.notice.value!!.targetName)
        advanceTimeBy(4_500)
        assertEquals(6, c.notice.value!!.secondsLeft)
        assertEquals(emptyList<String>(), switched)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(listOf("foo"), switched)
        assertNull(c.notice.value)
    }

    @Test fun cancelStopsAndIgnoresRepeatedUpdates() = runTest {
        val switched = mutableListOf<String>()
        val c = RaidCountdown(this, 10) { switched += it }
        c.onEvent(update())
        runCurrent()
        advanceTimeBy(3_000)
        c.cancel()
        assertNull(c.notice.value)
        c.onEvent(update()) // Twitch re-sends updates; the cancelled raid must stay dismissed
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(c.notice.value)
        assertEquals(emptyList<String>(), switched)
        // A different raid is a fresh notice.
        c.onEvent(update(id = "r2", target = "bar"))
        runCurrent()
        assertNotNull(c.notice.value)
        c.reset()
    }

    @Test fun goEventSwitchesImmediately() = runTest {
        val switched = mutableListOf<String>()
        val c = RaidCountdown(this, 10) { switched += it }
        c.onEvent(update())
        runCurrent()
        c.onEvent(RaidEvent.Go("r1", "foo"))
        assertEquals(listOf("foo"), switched)
        assertNull(c.notice.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, switched.size) // countdown did not fire a second switch
    }

    @Test fun cancelEventDismissesWithoutSwitching() = runTest {
        val switched = mutableListOf<String>()
        val c = RaidCountdown(this, 10) { switched += it }
        c.onEvent(update())
        runCurrent()
        c.onEvent(RaidEvent.Cancel("r1"))
        assertNull(c.notice.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(emptyList<String>(), switched)
    }
}
