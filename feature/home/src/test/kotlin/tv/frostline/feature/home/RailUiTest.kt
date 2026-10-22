package tv.frostline.feature.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RailUiTest {
    @get:Rule val rule = createComposeRule()

    private val items = listOf(
        RailItem("following", "Following", R.drawable.ic_following),
        RailItem("top", "Top", R.drawable.ic_top),
        RailItem("search", "Search", R.drawable.ic_search),
        RailItem("settings", "Settings", R.drawable.ic_settings),
    )

    private fun show(selected: String = "following") = rule.setContent {
        FrostlineTheme {
            Row {
                SideRail(items = items, selectedId = selected, onSelect = {})
                Box(Modifier.testTag("content").size(200.dp).focusable())
            }
        }
    }

    @Test fun railIsCollapsedWhileContentHasFocus() {
        show()
        rule.onNodeWithTag("content").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText("Following").assertDoesNotExist()
        rule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test fun railNeverExpandsOnFocusAndContentDoesNotShift() {
        show()
        rule.onNodeWithTag("content").requestFocus()
        rule.waitForIdle()
        val before = rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("rail_top").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText("Top").assertDoesNotExist()
        rule.onNodeWithText("Following").assertDoesNotExist()
        rule.onNodeWithContentDescription("Top").assertExists()
        org.junit.Assert.assertEquals(before, rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun indicatorIsCentredOnItsIconForEveryItemAndSizesMatch() {
        show(selected = "top")
        val tol = with(rule.density) { 1.dp.toPx() }
        var size: androidx.compose.ui.geometry.Size? = null
        items.forEach { item ->
            val ind = rule.onNodeWithTag("rail_${item.id}").fetchSemanticsNode().boundsInRoot
            val icon = rule.onNodeWithTag("rail_icon_${item.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(item.id, ind.center.x, icon.center.x, tol)
            org.junit.Assert.assertEquals(item.id, ind.center.y, icon.center.y, tol)
            if (size == null) size = ind.size else org.junit.Assert.assertEquals(size, ind.size)
        }
        // Evenly spaced: equal gaps between consecutive indicators.
        val tops = items.map { rule.onNodeWithTag("rail_${it.id}").fetchSemanticsNode().boundsInRoot }
        val gaps = tops.zipWithNext { a, b -> b.top - a.bottom }
        gaps.forEach { org.junit.Assert.assertEquals(gaps[0], it, tol) }
    }

    @Test fun leftFromContentEntersRailAtSelectedItem() {
        show(selected = "search")
        rule.onNodeWithTag("content").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("content").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithTag("rail_search").assertIsFocused()
    }
}
