package tv.frostline.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostSurface

data class RailItem(val id: String, val label: String, @DrawableRes val icon: Int)

val RailCollapsedWidth: Dp = 72.dp

/** One fixed size for every item's focus/selection indicator; it is centred on its icon. */
val RailIndicatorSize: Dp = 48.dp
val RailIconSize: Dp = 24.dp

/**
 * Icon-only rail. It never expands: content does not shift when it gains focus. Labels are exposed as
 * contentDescription for accessibility only. Each item is a [RailIndicatorSize] circle with its icon centred in it.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SideRail(
    items: List<RailItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupRequester: FocusRequester = remember { FocusRequester() },
    onFocusChange: (Boolean) -> Unit = {},
) {
    val requesters = remember(items) { items.associate { it.id to FocusRequester() } }
    val shape = CircleShape

    Column(
        modifier
            .fillMaxHeight()
            .width(RailCollapsedWidth)
            .background(Brush.horizontalGradient(listOf(FrostColors.Background.copy(alpha = 0.95f), FrostColors.Background.copy(alpha = 0.9f), Color.Transparent)))
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .focusRequester(groupRequester)
            .focusProperties { enter = { requesters[selectedId] ?: FocusRequester.Default } }
            .focusGroup()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            FrostSurface(
                onClick = { onSelect(item.id) },
                modifier = Modifier
                    .size(RailIndicatorSize)
                    .focusRequester(requesters.getValue(item.id))
                    .testTag("rail_${item.id}"),
                shape = shape,
                containerColor = if (selected) FrostColors.Primary.copy(alpha = 0.55f) else Color.Transparent,
                focusedContainerColor = FrostColors.ContainerFocused,
                focusedContentColor = FrostColors.Lavender,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(item.icon),
                        contentDescription = item.label,
                        modifier = Modifier.size(RailIconSize).testTag("rail_icon_${item.id}"),
                    )
                }
            }
        }
    }
}
