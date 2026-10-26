package tv.frostline.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

const val TARGET_PACKAGE = "tv.frostline.app"

private const val WAIT_MS = 15_000L

/**
 * The user journeys shared by the baseline profile generator and the benchmarks. Everything is driven with D-pad key
 * presses (UiDevice) and selected by test tag (the app sets testTagsAsResourceId, so a tag is a resource id).
 * The device must be signed in (or have continued as guest) so the app starts on Home.
 */
object Journeys {
    private val streamCard = By.res("stream_card")
    private val anyCard = By.res(Pattern.compile("stream_card|video_.*"))

    /** Cold start until Following is on screen (the rail is the first thing Home draws). */
    fun MacrobenchmarkScope.coldStartToFollowing() {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res("rail_following")), WAIT_MS)
        device.waitForIdle()
    }

    /** D-pad down through the Following list, then Top. */
    fun MacrobenchmarkScope.dpadFollowingAndTop() {
        coldStartToFollowing()
        // From the rail Right enters the content; walk down and back up.
        device.pressDPadRight()
        repeat(8) { device.pressDPadDown(); device.waitForIdle() }
        repeat(4) { device.pressDPadUp(); device.waitForIdle() }
        // Left to the rail, down to Top, OK.
        repeat(2) { device.pressDPadLeft() }
        device.pressDPadDown()
        device.pressDPadCenter()
        device.wait(Until.hasObject(streamCard), WAIT_MS)
        device.pressDPadRight()
        repeat(6) { device.pressDPadDown(); device.waitForIdle() }
        repeat(3) { device.pressDPadRight(); device.waitForIdle() }
        repeat(6) { device.pressDPadUp(); device.waitForIdle() }
    }

    /** Opens the first Top stream and watches it for [watchMs] with chat on. */
    fun MacrobenchmarkScope.openTopStream(watchMs: Long = 40_000L) {
        coldStartToFollowing()
        device.pressDPadLeft()
        device.pressDPadDown() // Top
        device.pressDPadCenter()
        device.wait(Until.hasObject(streamCard), WAIT_MS)
        device.pressDPadRight() // into the grid, first card
        device.pressDPadCenter() // open the stream
        device.wait(Until.hasObject(By.res("chat_panel")), WAIT_MS)
        Thread.sleep(watchMs)
        device.pressBack()
        device.waitForIdle()
    }

    /** Long-press a stream card to reach its channel page, open the first video there, play for a moment. */
    fun MacrobenchmarkScope.openVodFromChannel() {
        coldStartToFollowing()
        device.pressDPadLeft()
        device.pressDPadDown() // Top
        device.pressDPadCenter()
        device.wait(Until.hasObject(streamCard), WAIT_MS)
        device.pressDPadRight()
        device.executeShellCommand("input keyevent --longpress KEYCODE_DPAD_CENTER") // channel page
        device.wait(Until.hasObject(By.res("channel_header")), WAIT_MS)
        // Videos is the default tab: down from the tab row to the kind chips, then the grid.
        var vod = device.wait(Until.findObject(By.res(Pattern.compile("video_.*"))), 5_000L)
        var tries = 0
        while (vod == null && tries++ < 4) {
            device.pressDPadDown(); device.waitForIdle()
            vod = device.findObject(By.res(Pattern.compile("video_.*")))
        }
        device.pressDPadCenter()
        Thread.sleep(8_000L)
        device.pressDPadDown() // controls
        Thread.sleep(1_500L)
        device.pressBack()
        device.pressBack()
        device.waitForIdle()
    }
}
