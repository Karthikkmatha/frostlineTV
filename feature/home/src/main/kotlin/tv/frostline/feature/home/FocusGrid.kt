package tv.frostline.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.frostline.core.ui.FocusMemory
import tv.frostline.core.ui.rememberFocusIn

val OverscanX: Dp = 48.dp
val OverscanY: Dp = 27.dp

/**
 * One item of a [FocusGrid]. [content] receives a modifier that must be applied to the item's single
 * focus stop (it records focus for restoration and carries the restore FocusRequester).
 */
class GridEntry(
    val key: String,
    val fullSpan: Boolean = false,
    val focusable: Boolean = true,
    val content: @Composable (Modifier) -> Unit,
)

/**
 * Lazy grid that remembers its scroll position and last-focused entry in [memory] and, when the
 * [signal] asks for it, moves focus to that entry (or the first focusable one). This is what makes
 * focus survive a trip to the player and back.
 */
@Composable
fun FocusGrid(
    entries: List<GridEntry>,
    memory: FocusMemory,
    signal: FocusSignal,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 190.dp,
) {
    val state = rememberLazyGridState(memory.scrollIndex, memory.scrollOffset)
    DisposableEffect(state) {
        onDispose {
            memory.scrollIndex = state.firstVisibleItemIndex
            memory.scrollOffset = state.firstVisibleItemScrollOffset
        }
    }
    val requester = remember { FocusRequester() }
    val target = entries.firstOrNull { it.focusable && it.key == memory.key }?.key
        ?: entries.firstOrNull { it.focusable }?.key

    val tick = signal.tick
    LaunchedEffect(tick, target) {
        if (target != null && tick > signal.consumed) {
            val index = entries.indexOfFirst { it.key == target }
            if (index >= 0) state.scrollToItem(index)
            repeat(6) {
                withFrameNanos { }
                val result: Any? = runCatching { requester.requestFocus() }.getOrNull()
                if (result == true || result is Unit) {
                    signal.consumed = tick
                    return@LaunchedEffect
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCellWidth),
        state = state,
        modifier = modifier,
        // 5% overscan on a 960x540 dp screen: 48 dp sides, 27 dp top and bottom.
        contentPadding = PaddingValues(start = 16.dp, end = OverscanX, top = OverscanY, bottom = OverscanY),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(
            items = entries,
            key = { it.key },
            span = { GridItemSpan(if (it.fullSpan) maxLineSpan else 1) },
        ) { entry ->
            val m = if (entry.focusable) {
                (if (entry.key == target) Modifier.focusRequester(requester) else Modifier)
                    .rememberFocusIn(entry.key, memory)
            } else {
                Modifier
            }
            entry.content(m)
        }
    }
}
