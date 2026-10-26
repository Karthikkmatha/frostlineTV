package tv.frostline.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import tv.frostline.core.twitchinternal.StreamVariant
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostListItem

/** Quality choices: Auto plus the variants of the master playlist. [selected] null = Auto. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun QualitySheet(
    variants: List<StreamVariant>,
    selected: String?,
    onSelect: (StreamVariant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember { FocusRequester() }
    // The list items are composed lazily, so retry for a few frames until the requester is attached.
    LaunchedEffect(Unit) {
        repeat(8) {
            withFrameNanos { }
            val r: Any? = runCatching { initial.requestFocus() }.getOrNull()
            if (r == true || r == Unit) return@LaunchedEffect
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(FrostColors.Surface)
            .padding(horizontal = 16.dp, vertical = 27.dp)
            .testTag("quality_sheet")
            // Keep D-pad focus inside the sheet while it is open.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quality", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "auto") {
                QualityRow("Auto", selected == null, { onSelect(null) }, Modifier.focusRequester(if (selected == null) initial else FocusRequester()).testTag("q_auto"))
            }
            items(variants, key = { it.groupId }) { v ->
                QualityRow(v.label, selected == v.groupId, { onSelect(v) }, Modifier.focusRequester(if (selected == v.groupId) initial else FocusRequester()).testTag("q_${v.groupId}"))
            }
        }
    }
}

@Composable
private fun QualityRow(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FrostListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        selected = isSelected,
        trailing = if (isSelected) ({ Text("\u2713", fontSize = 16.sp, color = FrostColors.Lavender) }) else null,
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}
