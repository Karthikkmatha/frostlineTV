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
import tv.frostline.core.ui.FrostlineTheme

/** The player side of the focus model: how the remote gets from the video / controls into the chat panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ChatEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var enters = 0

    private fun show(chatShown: Boolean = true, rootFocusable: Boolean = true) {
        rule.setContent {
            FrostlineTheme {
                var visible by remember { mutableStateOf(false) }
                PlayerChrome(
                    controlsVisible = visible,
                    onShowControls = { visible = true },
                    onHideControls = { visible = false },
                    channelName = "SomeStreamer",
                    title = "Playing a game",
                    badge = liveBadge(3_000),
                    playing = true,
                    chatVisible = chatShown,
                    onPlayPause = {},
                    onRefresh = {},
                    onToggleChat = {},
                    onChannel = {},
                    onQuality = {},
                    modifier = Modifier,
                    autoHideMs = 0,
                    onEnterChat = if (chatShown) ({ enters++; visible = false }) else null,
                    rootFocusable = rootFocusable,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNodeWithTag(ControlTags.Root).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun rightOnTheBarePlayerEntersChat() {
        show()
        rule.onNodeWithTag(ControlTags.Root).assertIsFocused()
        press(Key.DirectionRight)
        assertEquals(1, enters)
    }

    @Test fun rightOnPlayPauseEntersChat() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.PlayPause).assertIsFocused()
        press(Key.DirectionRight)
        assertEquals(1, enters)
    }

    @Test fun rightOnTheChatButtonAndGearEntersChat() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ControlTags.Chat).requestFocus()
        rule.waitForIdle()
        press(Key.DirectionRight)
        assertEquals(1, enters)
        press(Key.DirectionCenter) // controls were hidden by the callback: OK shows them again
        rule.onNodeWithTag(ControlTags.Quality).requestFocus()
        rule.waitForIdle()
        press(Key.DirectionRight)
        assertEquals(2, enters)
    }

    @Test fun rightDoesNothingWhileChatIsHidden() {
        show(chatShown = false)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        assertEquals(0, enters)
        rule.onNodeWithTag(ControlTags.PlayPause).assertIsFocused()
    }

    @Test fun otherDirectionsStillWorkWithChatShown() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionDown); rule.onNodeWithTag(ControlTags.Refresh).assertIsFocused()
        press(Key.DirectionRight); rule.onNodeWithTag(ControlTags.Chat).assertIsFocused()
        press(Key.DirectionLeft); rule.onNodeWithTag(ControlTags.Refresh).assertIsFocused()
        assertEquals(0, enters)
    }
}
