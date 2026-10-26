package tv.frostline.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostCard
import tv.frostline.core.ui.FrostChip
import tv.frostline.core.ui.FrostListItem
import tv.frostline.core.ui.FrostSecondaryButton
import tv.frostline.core.ui.FrostTokens
import tv.frostline.core.ui.FrostlineTheme
import tv.frostline.core.ui.ShowcaseScreen
import tv.frostline.core.ui.TvToggleRow

/** The Phase C design tokens, measured: 40 dp buttons and chips, 56 dp rows, and the focus walk over the showcase screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class FrostComponentsTest {
    @get:Rule val rule = createComposeRule()

    private fun heightPx(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
    private fun dpPx(v: Float) = with(rule.density) { v.dp.toPx() }

    @Test fun buttonsAndChipsAreFortyDpAndRowsAtLeastFiftySixDp() {
        rule.setContent {
            FrostlineTheme {
                Column {
                    FrostButton("Primary", {}, Modifier.testTag("b"))
                    FrostSecondaryButton("Secondary", {}, Modifier.testTag("s"))
                    FrostChip("Chip", false, {}, Modifier.testTag("c"))
                    FrostListItem({}, Modifier.testTag("r")) { Text("Row") }
                    FrostCard({}, Modifier.testTag("k")) { Text("Card") }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(dpPx(FrostTokens.ButtonHeight.value), heightPx("b"), 1f)
        assertEquals(dpPx(FrostTokens.ButtonHeight.value), heightPx("s"), 1f)
        assertEquals(dpPx(FrostTokens.ButtonHeight.value), heightPx("c"), 1f)
        assertTrue(heightPx("r") >= dpPx(FrostTokens.RowMinHeight.value) - 1f)
    }

    @Test fun toggleRowTogglesOnOkAndShowsTheState() {
        var on by mutableStateOf(false)
        rule.setContent { FrostlineTheme { TvToggleRow("Thing", "desc", on, { on = it }, Modifier.testTag("t")) } }
        rule.onNodeWithTag("t").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("t").assertIsFocused()
        rule.onNodeWithTag("t").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertTrue(on)
    }

    @Test fun showcaseShowsEveryComponentAndFocusWalksAcrossIt() {
        rule.setContent { FrostlineTheme { ShowcaseScreen() } }
        rule.waitForIdle()
        for (tag in listOf("sc_btn_primary", "sc_btn_secondary", "sc_card", "sc_row", "sc_toggle_on")) {
            rule.onNodeWithTag(tag).assertExists()
        }
        rule.onNodeWithTag("sc_btn_primary").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("sc_btn_primary").assertIsFocused()
        rule.onNodeWithTag("sc_btn_primary").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("sc_btn_secondary").assertIsFocused()
    }
}
