package tv.frostline.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.twitchinternal.StreamVariant
import tv.frostline.core.ui.FrostlineTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ControlsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var plays = 0
    private var refreshes = 0
    private var channelClicks = 0
    private var qualityClicks = 0
    private var chatOn = false
    private var visibleNow = false

    private fun show(autoHideMs: Long = 0) {
        rule.setContent {
            FrostlineTheme {
                var visible by remember { mutableStateOf(false) }
                var chat by remember { mutableStateOf(false) }
                visibleNow = visible
                PlayerChrome(
                    controlsVisible = visible,
                    onShowControls = { visible = true },
                    onHideControls = { visible = false },
                    channelName = "SomeStreamer",
                    title = "Playing a game",
                    badge = liveBadge(3_000),
                    playing = true,
                    chatVisible = chat,
                    onPlayPause = { plays++ },
                    onRefresh = { refreshes++ },
                    onToggleChat = { chat = !chat; chatOn = chat },
                    onChannel = { channelClicks++ },
                    onQuality = { qualityClicks++ },
                    modifier = Modifier,
                    autoHideMs = autoHideMs,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNodeWithTag(ControlTags.Root).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** TV surfaces are activated with OK on the focused element. */
    private fun activate(tag: String) {
        rule.onNodeWithTag(tag).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag).assertIsFocused()

    @Test fun controlsAreHiddenUntilOkAndThenFocusPlayPause() {
        show()
        rule.onNodeWithTag(ControlTags.PlayPause).assertDoesNotExistCompat()
        press(Key.DirectionCenter)
        focused(ControlTags.PlayPause)
    }

    @Test fun dpadReachesEveryControl() {
        show()
        press(Key.DirectionCenter)
        focused(ControlTags.PlayPause)
        press(Key.DirectionUp); focused(ControlTags.Channel)
        press(Key.DirectionRight); focused(ControlTags.Quality)
        press(Key.DirectionLeft); focused(ControlTags.Channel)
        press(Key.DirectionDown); focused(ControlTags.PlayPause)
        press(Key.DirectionDown); focused(ControlTags.Refresh)
        press(Key.DirectionRight); focused(ControlTags.Chat)
        press(Key.DirectionLeft); focused(ControlTags.Refresh)
        press(Key.DirectionUp); focused(ControlTags.PlayPause)
    }

    @Test fun focusCannotEscapeTheRows() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionLeft); focused(ControlTags.PlayPause)
        press(Key.DirectionRight); focused(ControlTags.PlayPause)
        press(Key.DirectionUp); press(Key.DirectionUp); focused(ControlTags.Channel)
        press(Key.DirectionRight); press(Key.DirectionRight); focused(ControlTags.Quality)
    }

    @Test fun okActivatesTheFocusedControl() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter) // play/pause
        assertEquals(1, plays)
        press(Key.DirectionUp); press(Key.DirectionCenter)
        assertEquals(1, channelClicks)
        press(Key.DirectionRight); press(Key.DirectionCenter)
        assertEquals(1, qualityClicks)
        press(Key.DirectionLeft); press(Key.DirectionDown); press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(1, refreshes)
        press(Key.DirectionRight); press(Key.DirectionCenter)
        assertEquals(true, chatOn)
    }

    @Test fun backHidesControlsAndThenFallsThrough() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.PlayPause).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(ControlTags.PlayPause).assertDoesNotExistCompat()
        assertEquals(false, visibleNow)
        focused(ControlTags.Root)
        press(Key.DirectionCenter) // OK shows them again
        focused(ControlTags.PlayPause)
    }

    @Test fun controlsAutoHideAfterInactivity() {
        show(autoHideMs = 3_000)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.PlayPause).assertExists()
        rule.mainClock.advanceTimeBy(3_500)
        rule.waitForIdle()
        rule.onNodeWithTag(ControlTags.PlayPause).assertDoesNotExistCompat()
    }

    @Test fun liveBadgeShowsInTheTopRow() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("live_badge").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    // ---- quality sheet and raid banner ----

    private val variants = listOf(
        StreamVariant("1080p60 (Source)", "chunked", 1920, 1080, 60.0, 8_000_000, true, false),
        StreamVariant("720p60", "720p60", 1280, 720, 60.0, 3_400_000, false, false),
    )

    @Test fun qualitySheetListsAutoPlusVariantsAndSelects() {
        var picked: StreamVariant? = variants[0]
        rule.setContent { FrostlineTheme { QualitySheet(variants, selected = "720p60", onSelect = { picked = it }) } }
        rule.waitForIdle()
        rule.onNodeWithTag("q_auto").assertExists()
        rule.onNodeWithTag("q_chunked").assertExists()
        focused("q_720p60") // opens on the current choice
        activate("q_chunked")
        assertEquals("chunked", picked?.groupId)
        activate("q_auto")
        assertEquals(null, picked)
    }

    @Test fun raidBannerShowsCountdownAndCancelTakesFocus() {
        var cancelled = 0
        rule.setContent { FrostlineTheme { RaidBanner(RaidNotice("r", "foo", "Foo", 7), { cancelled++ }) } }
        rule.waitForIdle()
        rule.onNodeWithTag("raid_text").assertExists()
        focused("raid_cancel")
        activate("raid_cancel")
        assertEquals(1, cancelled)
    }
}
