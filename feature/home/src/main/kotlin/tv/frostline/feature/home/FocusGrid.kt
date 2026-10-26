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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.frostline.core.ui.FocusMemory
import tv.frostline.core.ui.LocalFocusRingEnabled
import tv.frostline.core.ui.tryFocus
import androidx.compose.ui.focus.onFocusChanged

/** Frames a restore keeps trying (about 3 s at 60 fps), and how long a missing saved card is waited for (about 2.5 s). */
internal const val RESTORE_FRAMES = 180
internal const val GIVE_UP_FRAMES = 150

val OverscanX: Dp = 48.dp
val OverscanY: Dp = 27.dp

/** Brings an entry of the enclosing [FocusGrid] into composition (it is a lazy grid), so it can be given focus. */
fun interface GridScroller { suspend fun scrollTo(key: String) }

val LocalGridScroller = androidx.compose.runtime.staticCompositionLocalOf { GridScroller { } }

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
    val saved = memory.key
    val savedPresent = saved != null && entries.any { it.focusable && it.key == saved }
    // A saved key whose card is not in the list yet (the list is still loading) is waited for, not replaced by the
    // first focusable entry: otherwise the fallback focus overwrites the memory and the opened card is lost for good.
    val waitingForSaved = saved != null && !savedPresent
    var gaveUp by remember { mutableStateOf(false) }
    val target = when {
        savedPresent -> saved
        waitingForSaved && !gaveUp -> null
        else -> entries.firstOrNull { it.focusable }?.key
    }
    val settled = LocalFocusRingEnabled.current

    val tick = signal.tick
    // A grid that is composed again (coming back from the player or a channel page) must put focus back on the
    // entry that was opened, even though the signal it already consumed has not moved on: without this, focus
    // falls to the first focusable thing on screen, the side rail.
    val firstRun = remember { booleanArrayOf(true) }
    // The attempt is retried every frame until the card holds focus while the screen transition has finished: a
    // request made earlier can be granted to a card of a screen that is still fading out, or before the card is laid out.
    LaunchedEffect(tick, target, settled) {
        if (!(firstRun[0] || tick > signal.consumed)) return@LaunchedEffect
        if (target == null) {
            if (!waitingForSaved) return@LaunchedEffect
            repeat(GIVE_UP_FRAMES) { withFrameNanos { } }
            gaveUp = true
            memory.key = null
            return@LaunchedEffect
        }
        val index = entries.indexOfFirst { it.key == target }
        val visible = state.layoutInfo.visibleItemsInfo.any { it.key == target }
        if (index >= 0 && !visible) state.scrollToItem(index)
        repeat(RESTORE_FRAMES) {
            withFrameNanos { }
            if (requester.tryFocus() && settled) {
                signal.consumed = tick
                firstRun[0] = false
                return@LaunchedEffect
            }
        }
        if (settled) { firstRun[0] = false; signal.consumed = tick }
    }

    val scroller = GridScroller { key ->
        val i = entries.indexOfFirst { it.key == key }
        if (i >= 0) state.scrollToItem(i)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalGridScroller provides scroller) {
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
                    .onFocusChanged { if (it.hasFocus && (saved == null || savedPresent || gaveUp)) memory.key = entry.key }
            } else {
                Modifier
            }
            entry.content(m)
        }
    }
    }
}
