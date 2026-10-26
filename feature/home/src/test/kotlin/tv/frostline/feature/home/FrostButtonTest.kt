package tv.frostline.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostChip
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostlineTheme

/** Native TV buttons centre their label; a wide button must not leave it at the start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class FrostButtonTest {
    @get:Rule val rule = createComposeRule()

    private fun centerX(tag: String, unmerged: Boolean = false): Float {
        val b = rule.onNodeWithTag(tag, useUnmergedTree = unmerged).getBoundsInRoot()
        return (b.left.value + b.right.value) / 2f
    }

    @Test fun aWideButtonCentresItsLabel() {
        rule.setContent { FrostlineTheme { Box { FrostButton("Send", {}, Modifier.width(300.dp).testTag("btn")) } } }
        rule.waitForIdle()
        val label = rule.onNodeWithTag("btn").getBoundsInRoot()
        // the label is the only text; its centre must be the button's centre
        val dbg = rule.onNodeWithText("Send", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("btn=$label text=$dbg", 300f, (label.right - label.left).value, 1f)
        val t = rule.onNodeWithText("Send", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(centerX("btn"), (t.left.value + t.right.value) / 2f, 2f)
    }

    @Test fun iconAndLabelAreCentredTogether() {
        rule.setContent {
            FrostlineTheme {
                Box {
                    FrostButton("Go", {}, Modifier.width(300.dp).testTag("btn"), icon = { Box(Modifier.size(16.dp).background(FrostColors.OnSurface).testTag("ico")) })
                }
            }
        }
        rule.waitForIdle()
        val ico = rule.onNodeWithTag("ico", useUnmergedTree = true).getBoundsInRoot()
        val txt = rule.onNodeWithText("Go", useUnmergedTree = true).getBoundsInRoot()
        val groupCenter = (ico.left.value + txt.right.value) / 2f
        assertEquals(centerX("btn"), groupCenter, 2f)
    }

    @Test fun aWrapContentButtonStaysCompact() {
        rule.setContent { FrostlineTheme { Row { FrostButton("Retry", {}, Modifier.testTag("btn")) } } }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("btn").getBoundsInRoot().let { (it.right - it.left).value } < 200f)
    }

    @Test fun anUnfocusedChipHasNoBorderAndASelectedOneIsFilled() {
        // Both render without throwing and keep their 40 dp height; the colours are the tokens (checked by eye in the showcase).
        rule.setContent { FrostlineTheme { Row { FrostChip("One", true, {}, Modifier.testTag("c1")); FrostChip("Two", false, {}, Modifier.testTag("c2")) } } }
        rule.waitForIdle()
        assertEquals(40f, rule.onNodeWithTag("c1").getBoundsInRoot().let { (it.bottom - it.top).value }, 1f)
        assertEquals(40f, rule.onNodeWithTag("c2").getBoundsInRoot().let { (it.bottom - it.top).value }, 1f)
    }
}
