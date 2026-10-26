package tv.frostline.feature.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.ui.FocusMemory

/** Back from a stream must put focus on the exact card that was opened, in every grid built on [FocusGrid]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class FocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    private fun cards(n: Int) = (0 until n).map { i ->
        GridEntry("ts:$i") { m -> Box(m.height(90.dp).focusable().semantics(mergeDescendants = true) { }) { Text("card $i") } }
    }

    @Test fun backRestoresTheExactCardDeepInAScrolledGrid() {
        val memory = FocusMemory()
        val signal = FocusSignal()
        var show by mutableStateOf(true)
        val entries = cards(60)
        rule.setContent { if (show) FocusGrid(entries, memory, signal) }
        rule.waitForIdle()
        rule.onNodeWithText("card 0").assertIsFocused() // first visit: the first card
        rule.onNodeWithText("card 0").requestFocus()
        rule.runOnUiThread { memory.key = "ts:37" } // the user had walked down to card 37 (recorded by the focus handler)
        signal.request() // what a card click does before navigating away
        rule.runOnUiThread { show = false } // the player is on top
        rule.waitForIdle()
        rule.runOnUiThread { show = true } // Back
        rule.waitForIdle()
        rule.onNodeWithText("card 37").assertIsFocused()
        assertEquals("ts:37", memory.key)
    }

    @Test fun backWaitsForACardThatIsNotInTheListYetInsteadOfLosingIt() {
        val memory = FocusMemory().also { it.key = "ts:5" }
        val signal = FocusSignal()
        var list by mutableStateOf(emptyList<GridEntry>())
        rule.mainClock.autoAdvance = false
        rule.setContent { FocusGrid(list, memory, signal) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("ts:5", memory.key) // nothing else took the memory while the list was empty
        rule.runOnUiThread { list = cards(12) } // the list arrives after the screen is back
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("card 5").assertIsFocused()
        rule.mainClock.autoAdvance = true
    }

    @Test fun aSavedCardThatIsGoneFallsBackToTheFirstOne() {
        val memory = FocusMemory().also { it.key = "ts:99" }
        val signal = FocusSignal()
        rule.setContent { FocusGrid(cards(6), memory, signal) }
        rule.mainClock.advanceTimeBy(6_000)
        rule.waitForIdle()
        rule.onNodeWithText("card 0").assertIsFocused()
    }

    private fun rowOf(prefix: String, n: Int) = CardRowBlock(
        prefix, "Row $prefix",
        (0 until n).map { i -> GridEntry("$prefix:$i") { m -> Box(m.height(90.dp).width(200.dp).focusable().semantics(mergeDescendants = true) { }) { Text("$prefix card $i") } } },
    )

    @Test fun backRestoresTheExactCardDeepInAHorizontalRowOfASecondRow() {
        val memory = FocusMemory()
        val signal = FocusSignal()
        var show by mutableStateOf(true)
        val blocks = listOf(rowOf("a", 30), rowOf("b", 60))
        rule.setContent { if (show) FocusRows(blocks, memory, signal) }
        rule.waitForIdle()
        rule.onNodeWithText("a card 0").assertIsFocused()
        rule.runOnUiThread { memory.key = "b:41" }
        signal.request()
        rule.runOnUiThread { show = false }
        rule.waitForIdle()
        rule.runOnUiThread { show = true }
        rule.waitForIdle()
        rule.onNodeWithText("b card 41").assertIsFocused()
        assertEquals("b:41", memory.key)
    }

    @Test fun rowsWaitForALateListInsteadOfLosingTheSavedCard() {
        val memory = FocusMemory().also { it.key = "a:5" }
        val signal = FocusSignal()
        var blocks by mutableStateOf(emptyList<RowBlock>())
        rule.mainClock.autoAdvance = false
        rule.setContent { FocusRows(blocks, memory, signal) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("a:5", memory.key)
        rule.runOnUiThread { blocks = listOf(rowOf("a", 12)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("a card 5").assertIsFocused()
        rule.mainClock.autoAdvance = true
    }
}
