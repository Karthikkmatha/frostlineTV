package tv.frostline.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.frostline.core.ui.FocusMemory
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostLayout
import tv.frostline.core.ui.LocalFocusRingEnabled
import tv.frostline.core.ui.tryFocus

/** One block of a [FocusRows] screen: a single full-width item, or a titled horizontal row of cards. */
sealed interface RowBlock { val key: String }

class SingleBlock(val entry: GridEntry) : RowBlock { override val key: String get() = entry.key }

/** A titled horizontal row. [items] are cards (focusable) or skeletons (not). */
class CardRowBlock(override val key: String, val title: String, val items: List<GridEntry>) : RowBlock

/** Focus pivot like Google TV: the focused item sits [fraction] of the container from its start edge. */
@OptIn(ExperimentalFoundationApi::class)
internal class PivotSpec(private val fraction: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * fraction
}

internal val RowPivot = PivotSpec(0.04f)
internal val ColumnPivot = PivotSpec(0.15f)

/**
 * Google TV style screen: a vertical list of blocks, each a title over a horizontal row of cards. Like [FocusGrid] it
 * remembers the scroll position and the last focused entry in [memory] and gives that entry focus back when the
 * [signal] asks (Back from the player). Each row restores the card that had focus when focus re-enters it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FocusRows(
    blocks: List<RowBlock>,
    memory: FocusMemory,
    signal: FocusSignal,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(memory.scrollIndex, memory.scrollOffset)
    val rowStates = remember { HashMap<String, LazyListState>() }
    DisposableEffect(state) {
        onDispose {
            memory.scrollIndex = state.firstVisibleItemIndex
            memory.scrollOffset = state.firstVisibleItemScrollOffset
            rowStates.forEach { (k, rs) -> memory.rowScroll[k] = rs.firstVisibleItemIndex to rs.firstVisibleItemScrollOffset }
        }
    }
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) {
        val saved = memory.rowScroll[key]
        LazyListState(saved?.first ?: 0, saved?.second ?: 0)
    }

    val requester = remember { FocusRequester() }
    val saved = memory.key
    fun contains(b: RowBlock, k: String) = when (b) {
        is SingleBlock -> b.entry.focusable && b.entry.key == k
        is CardRowBlock -> b.items.any { it.focusable && it.key == k }
    }
    val savedPresent = saved != null && blocks.any { contains(it, saved) }
    val waitingForSaved = saved != null && !savedPresent
    var gaveUp by remember { mutableStateOf(false) }
    val firstFocusable = blocks.firstNotNullOfOrNull { b ->
        when (b) {
            is SingleBlock -> b.entry.key.takeIf { b.entry.focusable }
            is CardRowBlock -> b.items.firstOrNull { it.focusable }?.key
        }
    }
    val target = when {
        savedPresent -> saved
        waitingForSaved && !gaveUp -> null
        else -> firstFocusable
    }
    val settled = LocalFocusRingEnabled.current
    val tick = signal.tick
    val firstRun = remember { booleanArrayOf(true) }

    LaunchedEffect(tick, target, settled) {
        if (!(firstRun[0] || tick > signal.consumed)) return@LaunchedEffect
        if (target == null) {
            if (!waitingForSaved) return@LaunchedEffect
            repeat(GIVE_UP_FRAMES) { withFrameNanos { } }
            gaveUp = true
            memory.key = null
            return@LaunchedEffect
        }
        val col = blocks.indexOfFirst { contains(it, target) }
        if (col >= 0) {
            if (state.layoutInfo.visibleItemsInfo.none { it.index == col }) state.scrollToItem(col)
            val block = blocks[col]
            if (block is CardRowBlock) {
                val idx = block.items.indexOfFirst { it.key == target }
                withFrameNanos { }
                val rs = rowState(block.key)
                if (idx >= 0 && rs.layoutInfo.visibleItemsInfo.none { it.key == target }) rs.scrollToItem(idx)
            }
        }
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

    fun entryModifier(e: GridEntry): Modifier =
        if (e.focusable) {
            (if (e.key == target) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { if (it.hasFocus && (saved == null || savedPresent || gaveUp)) memory.key = e.key }
        } else Modifier

    CompositionLocalProvider(LocalBringIntoViewSpec provides ColumnPivot) {
        LazyColumn(
            state = state,
            modifier = modifier,
            contentPadding = PaddingValues(top = FrostLayout.SafeY, bottom = FrostLayout.SafeY),
            verticalArrangement = Arrangement.spacedBy(FrostLayout.CardGap),
        ) {
            items(blocks, key = { it.key }) { block ->
                when (block) {
                    is SingleBlock -> Column(Modifier.padding(start = FrostLayout.RailGutter, end = FrostLayout.SafeX)) {
                        block.entry.content(entryModifier(block.entry))
                    }
                    is CardRowBlock -> Column {
                        Text(
                            block.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = FrostColors.OnSurface,
                            modifier = Modifier.padding(start = FrostLayout.RailGutter, bottom = 12.dp),
                        )
                        CompositionLocalProvider(LocalBringIntoViewSpec provides RowPivot) {
                            LazyRow(
                                state = rowState(block.key),
                                modifier = Modifier.focusRestorer().focusGroup(),
                                // Room for the focus scale, ring and glow, which the row would otherwise clip.
                                contentPadding = PaddingValues(start = FrostLayout.RailGutter, end = FrostLayout.SafeX, top = 10.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(FrostLayout.CardGap),
                            ) {
                                items(block.items, key = { it.key }) { e -> e.content(entryModifier(e)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
