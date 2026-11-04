package tv.frostline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugHooksTest {
    @Test fun openVodTakesTheChannelOfThePlayerInFront() {
        assertEquals("xqc", DebugHooks.openVodChannel("player/{login}", "xqc", "home"))
    }

    @Test fun openVodOnAVodUsesTheLivePlayerBehindItSoTheVodIsReplaced() {
        assertEquals("xqc", DebugHooks.openVodChannel("vod/{id}", null, "xqc"))
    }

    @Test fun openVodElsewhereDoesNothingSoScreensCannotPileUp() {
        assertNull(DebugHooks.openVodChannel("settings", null, "xqc"))
        assertNull(DebugHooks.openVodChannel("home", null, "xqc"))
        assertNull(DebugHooks.openVodChannel(null, null, null))
    }

    @Test fun theStatsLineSaysWhichPlayerIsInFrontAndWhetherItPlays() {
        val owner = Any()
        DebugPlayerState.clear(owner)
        assertEquals("none", DebugPlayerState.text())
        DebugPlayerState.report(owner, "vod", true)
        assertEquals("vod:playing", DebugPlayerState.text())
        val newer = Any()
        DebugPlayerState.report(newer, "live", false)
        DebugPlayerState.clear(owner) // the replaced player stopping must not clear the new one
        assertEquals("live:idle", DebugPlayerState.text())
        DebugPlayerState.clear(newer)
        assertEquals("none", DebugPlayerState.text())
    }
}
