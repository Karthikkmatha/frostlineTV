package tv.frostline.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder

data class RailItem(val id: String, val label: String, @DrawableRes val icon: Int)

val RailCollapsedWidth: Dp = 64.dp
val RailExpandedWidth: Dp = 220.dp

/**
 * Icon rail that is always collapsed and slides open only while it holds focus (Left from content).
 * It overlays the content, so the content never reflows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SideRail(
    items: List<RailItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupRequester: FocusRequester = remember { FocusRequester() },
    onFocusChange: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) RailExpandedWidth else RailCollapsedWidth,
        animationSpec = tween(180),
        label = "railWidth",
    )
    val requesters = remember(items) { items.associate { it.id to FocusRequester() } }

    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(Brush.horizontalGradient(listOf(Color(0xF20B1118), Color(0xE60B1118), Color(0x000B1118))))
            .onFocusChanged {
                expanded = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .focusRequester(groupRequester)
            .focusProperties { enter = { requesters[selectedId] ?: FocusRequester.Default } }
            .focusGroup()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            val selected = item.id == selectedId
            Surface(
                onClick = { onSelect(item.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .focusRequester(requesters.getValue(item.id))
                    .testTag("rail_${item.id}"),
                shape = cardShape(RoundedCornerShape(22.dp)),
                border = focusRingBorder(RoundedCornerShape(22.dp)),
                scale = cardScale(1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) FrostColors.SurfaceFocused else Color.Transparent,
                    contentColor = if (selected) FrostColors.Ice else FrostColors.OnSurface,
                    focusedContainerColor = FrostColors.SurfaceFocused,
                    focusedContentColor = FrostColors.Ice,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.width(22.dp).height(22.dp))
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(item.label, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
