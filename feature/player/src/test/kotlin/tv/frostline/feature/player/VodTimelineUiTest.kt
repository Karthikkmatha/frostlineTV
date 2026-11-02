package tv.frostline.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FrostlineTheme

/** The timeline of a past broadcast, driven by the real [ScrubController] with a fake player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class VodTimelineUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val seeks = mutableListOf<Long>()
    private var plays = 0
    private var visibleNow = false
    private val scrub = ScrubController(
        position = { 125_000L }, duration = { 3_725_000L }, seekTo = { seeks += it }, now = { System.nanoTime() / 1_000_000 },
    )

    private fun show(seekable: Boolean = true, autoHideMs: Long = 0) {
        rule.setContent {
            FrostlineTheme {
                var visible by remember { mutableStateOf(false) }
                visibleNow = visible
                val preview by scrub.preview.collectAsState()
                PlayerChrome(
                    controlsVisible = visible,
                    onShowControls = { visible = true },
                    onHideControls = { visible = false },
                    channelName = "Streamer", title = "A past broadcast", badge = liveBadge(null),
                    playing = true, chatVisible = false,
                    onPlayPause = { plays++ }, onRefresh = {}, onToggleChat = {}, onChannel = {}, onQuality = {},
                    modifier = Modifier, autoHideMs = autoHideMs,
                    vod = VodControls(
                        "Past broadcast", preview ?: 125_000L, 3_725_000L, null,
                        seekable = seekable, previewMs = preview,
                        onScrub = scrub::step, onScrubCommit = { scrub.commit() }, onScrubCancel = scrub::cancel,
                    ) { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNodeWithTag(ControlTags.Root).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag).assertIsFocused()

    private fun toTimeline() {
        press(Key.DirectionCenter)           // controls, on play
        focused(ControlTags.PlayPause)
        press(Key.DirectionDown)             // refresh
        focused(ControlTags.Refresh)
        press(Key.DirectionUp)               // timeline
        focused(ControlTags.Timeline)
    }

    @Test fun upFromTheButtonRowFocusesTheTimelineAndDownReturnsToPlay() {
        show()
        toTimeline()
        rule.onNodeWithTag(ControlTags.TimelineHint).assertTextEquals("◀ ▶ choose time · hold to move faster · OK seek · Back cancel")
        rule.onNodeWithTag(ControlTags.TimelineThumb).assertExists()
        press(Key.DirectionDown)
        focused(ControlTags.PlayPause)
    }

    @Test fun upFromTheChatButtonAlsoReachesIt() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionRight)
        focused(ControlTags.Chat)
        press(Key.DirectionUp)
        focused(ControlTags.Timeline)
    }

    @Test fun leftAndRightMovePreviewOnlyAndNeverSeek() {
        show()
        toTimeline()
        press(Key.DirectionRight)
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("2:15")
        press(Key.DirectionLeft); press(Key.DirectionLeft)
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("1:55")
        assertTrue(seeks.isEmpty())
        focused(ControlTags.Timeline)
    }

    @Test fun okSeeksToThePreviewOnceAndClearsIt() {
        show()
        toTimeline()
        press(Key.DirectionRight); press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(145_000L), seeks)
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("2:05") // back to following the playhead
        press(Key.DirectionCenter) // OK without a preview does nothing
        assertEquals(1, seeks.size)
    }

    @Test fun backCancelsAPreviewWithoutSeekingAndWithoutHidingTheControls() {
        show()
        toTimeline()
        press(Key.DirectionRight)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(seeks.isEmpty())
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("2:05")
        assertTrue("first Back only cancels the preview", visibleNow)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse("with no preview Back behaves as before", visibleNow)
    }

    @Test fun leavingTheTimelineDropsThePreview() {
        show()
        toTimeline()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        focused(ControlTags.PlayPause)
        assertEquals(null, scrub.preview.value)
        assertTrue(seeks.isEmpty())
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("2:05")
    }

    @Test fun controlsDoNotAutoHideWhileTheTimelineIsFocused() {
        show(autoHideMs = 5_000)
        toTimeline()
        rule.mainClock.advanceTimeBy(8_000)
        rule.waitForIdle()
        assertTrue(visibleNow)
        press(Key.DirectionDown) // blur: the countdown resumes
        rule.mainClock.advanceTimeBy(8_000)
        rule.waitForIdle()
        assertFalse(visibleNow)
    }

    @Test fun aClipKeepsAPlainProgressBar() {
        show(seekable = false)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.Timeline).assertDoesNotExist()
        rule.onNodeWithTag(ControlTags.SeekBar).assertExists()
        press(Key.DirectionDown)
        focused(ControlTags.Refresh)
        press(Key.DirectionUp)
        focused(ControlTags.PlayPause)
    }
}
