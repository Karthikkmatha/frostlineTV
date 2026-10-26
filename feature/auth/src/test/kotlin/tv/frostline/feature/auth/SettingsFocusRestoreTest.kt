package tv.frostline.feature.auth

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FrostlineTheme

/** Settings, as Home hosts it: Right from the rail returns to the row that had focus last (focusRestorer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SettingsFocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rightFromTheRailReturnsToTheLastFocusedRow() {
        val first = FocusRequester()
        rule.setContent {
            FrostlineTheme {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.size(48.dp).fillMaxHeight().testTag("rail").focusable())
                    Box(Modifier.fillMaxSize().focusRestorer { first }) {
                        SettingsContent(AccountUi(login = "me", signedIn = true), "0.6.0", {}, {}, {}, {}, {}, {}, firstFocus = first)
                    }
                }
            }
        }
        rule.waitForIdle()
        fun key(k: Key) { rule.onNodeWithTag("rail").performKeyInput { pressKey(k) }; rule.waitForIdle() }
        // The first time: the fallback, the first row (Sign out, signed in).
        rule.onNodeWithTag("rail").requestFocus(); rule.waitForIdle()
        rule.onNodeWithTag("rail").assertIsFocused()
        key(Key.DirectionRight)
        rule.onNodeWithTag("btn_sign_out").assertIsFocused()
        // Move on to the Follow raids row, then back to the rail.
        key(Key.DirectionDown); rule.onNodeWithTag("btn_link_web").assertIsFocused()
        key(Key.DirectionDown); rule.onNodeWithTag("toggle_raids").assertIsFocused()
        key(Key.DirectionLeft)
        rule.onNodeWithTag("rail").assertIsFocused()
        key(Key.DirectionRight)
        rule.onNodeWithTag("toggle_raids").assertIsFocused() // the row we left, not the first row (Account) again
    }
}
