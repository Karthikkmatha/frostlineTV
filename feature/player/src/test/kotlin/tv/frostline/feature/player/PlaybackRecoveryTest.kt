package tv.frostline.feature.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.feature.player.PlaybackRecovery.Action

class PlaybackRecoveryTest {
    @Test fun classifiesErrorsByWhatToDoAboutThem() {
        val r = PlaybackRecovery()
        assertEquals(Action.SeekToLive, r.classify(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(Action.RebuildPlayer, r.classify(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(Action.RebuildPlayer, r.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(Action.Reload, r.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(Action.Reload, r.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(Action.Reload, r.classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertEquals(Action.Reload, r.classify(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun retriesBackOffFromOneToThirtySecondsThenGiveUp() {
        val r = PlaybackRecovery(maxAttempts = 8)
        val waits = (1..8).map { val a = r.onFailure(Action.Reload); assertEquals(Action.Reload, a); r.delayMs(a) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals(Action.GiveUp, r.onFailure(Action.Reload))
    }

    @Test fun aFallenBehindLiveWindowSeeksAtOnceAFewTimesThenReloads() {
        val r = PlaybackRecovery()
        repeat(3) { val a = r.onFailure(Action.SeekToLive); assertEquals(Action.SeekToLive, a); assertEquals(0L, r.delayMs(a)) }
        assertEquals(Action.Reload, r.onFailure(Action.SeekToLive))
    }

    @Test fun steadyPlaybackResetsTheCounter() {
        val r = PlaybackRecovery()
        repeat(4) { r.onFailure(Action.Reload) }
        assertTrue(r.recovering)
        r.onSteady()
        assertFalse(r.recovering)
        assertEquals(0, r.attempts)
        assertEquals(1_000L, r.delayMs(r.onFailure(Action.Reload)))
    }
}
