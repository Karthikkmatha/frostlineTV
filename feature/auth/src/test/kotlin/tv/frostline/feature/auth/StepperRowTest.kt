package tv.frostline.feature.auth

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
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
import tv.frostline.core.ui.TvStepperRow

/** Settings > Chat > Panel width: the value changes only after OK (adjust mode); otherwise Left/Right are plain focus moves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class StepperRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun valueChangesOnlyAfterOkAndLeftOtherwiseIsNotConsumed() {
        val steps = mutableListOf<Int>()
        rule.setContent { FrostlineTheme { TvStepperRow("Panel width", "30%", { d, _ -> steps += d }, androidx.compose.ui.Modifier.testTag("stepper")) } }
        rule.onNodeWithTag("stepper").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(emptyList<Int>(), steps) // not adjusting: nothing changes, focus moves as usual
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionCenter) } // OK: adjust mode
        rule.waitForIdle()
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(listOf(1, -1), steps)
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionCenter) } // OK again: leaves adjust mode
        rule.waitForIdle()
        rule.onNodeWithTag("stepper").performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(1, -1), steps)
    }
}

