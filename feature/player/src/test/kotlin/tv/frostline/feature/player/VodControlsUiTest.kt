package tv.frostline.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FrostlineTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class VodControlsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Skip(val delta: Long, val repeat: Boolean)

    private val skips = mutableListOf<Skip>()
    private var enters = 0
    private var plays = 0
    private var visibleNow = false

    private fun show(counter: String? = null, chatShown: Boolean = false, showChatToggle: Boolean = true) {
        rule.setContent {
            FrostlineTheme {
                var visible by remember { mutableStateOf(false) }
                visibleNow = visible
                PlayerChrome(
                    controlsVisible = visible,
                    onShowControls = { visible = true },
                    onHideControls = { visible = false },
                    channelName = "Streamer",
                    title = "A past broadcast",
                    badge = liveBadge(null),
                    playing = true,
                    chatVisible = chatShown,
                    onPlayPause = { plays++ },
                    onRefresh = {},
                    onToggleChat = {},
                    onChannel = {},
                    onQuality = {},
                    modifier = Modifier,
                    autoHideMs = 0,
                    onEnterChat = if (chatShown) ({ enters++; visible = false }) else null,
                    vod = VodControls("Past broadcast", 125_000, 3_725_000, counter) { d, r -> skips += Skip(d, r) },
                    showChatToggle = showChatToggle,
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

    @Test fun leftAndRightOnTheBarePlayerNeverSkip() {
        show()
        press(Key.DirectionLeft); press(Key.DirectionRight)
        press(Key.DirectionLeft); press(Key.DirectionRight)
        assertTrue(skips.isEmpty())
        assertEquals(false, visibleNow)
    }

    @Test fun rightOnTheBarePlayerEntersChatInsteadOfSkipping() {
        show(chatShown = true)
        press(Key.DirectionLeft)
        assertEquals(0, enters)
        press(Key.DirectionRight)
        assertEquals(1, enters)
        assertTrue(skips.isEmpty())
    }

    @Test fun leftAndRightWithControlsShownOnlyMoveFocus() {
        show()
        press(Key.DirectionCenter)
        focused(ControlTags.PlayPause)
        press(Key.DirectionLeft); focused(ControlTags.SkipBack)
        press(Key.DirectionLeft); focused(ControlTags.SkipBack) // edge
        press(Key.DirectionRight); focused(ControlTags.PlayPause)
        press(Key.DirectionRight); focused(ControlTags.SkipForward)
        press(Key.DirectionRight); focused(ControlTags.SkipForward)
        assertTrue("moving focus never skips", skips.isEmpty())
        assertEquals(0, plays)
    }

    @Test fun okOnTheSkipButtonsSkipsByTenBackAndThirtyForward() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        press(Key.DirectionRight); press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(-10_000L, 30_000L), skips.map { it.delta })
        assertTrue(skips.none { it.repeat })
    }

    @Test fun aHeldOkReportsRepeatsAndNeverAlsoClicks() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionRight); press(Key.DirectionRight)
        focused(ControlTags.SkipForward)
        rule.onNodeWithTag(ControlTags.Root).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(800) // the dispatcher auto-repeats a held key
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertTrue("the press plus its repeats, no extra click on release: ${skips.size}", skips.size >= 3)
        assertEquals(false, skips.first().repeat)
        assertTrue(skips.drop(1).all { it.repeat })
        assertTrue(skips.all { it.delta == 30_000L })
    }

    @Test fun seekBarShowsElapsedAndTotal() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.Elapsed).assertTextEquals("2:05")
        rule.onNodeWithTag(ControlTags.Total).assertTextEquals("1:02:05")
        rule.onNodeWithTag(ControlTags.SeekBar).assertExists()
        rule.onNodeWithTag("vod_kind").assertTextEquals("Past broadcast")
    }

    @Test fun theCounterShowsWhileASkipIsPending() {
        show(counter = "+01:30")
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.SkipCounter).assertTextEquals("+01:30")
    }

    @Test fun noCounterWhenNothingIsPending() {
        show(counter = null)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.SkipCounter).assertDoesNotExist()
    }

    @Test fun clipsHaveNoChatToggle() {
        show(showChatToggle = false)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.Chat).assertDoesNotExist()
        rule.onNodeWithTag(ControlTags.Refresh).assertExists()
    }

    @Test fun playPauseStillWorksBetweenTheSkipButtons() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(1, plays)
    }
}
