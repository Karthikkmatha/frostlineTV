package tv.frostline.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Hidden screen behind `frostline://debug/showcase` (only with Settings > Debug overlay on): every button, chip, card, list
 * row and toggle of the app, built by the same wrappers the app uses. Walk the D-pad over the live ones to see the
 * focused state; the "static" strips next to them draw the token look (focused container, 2 dp border, scale) without
 * needing focus, so one screenshot shows unfocused, focused and pressed side by side.
 */
@Composable
fun ShowcaseScreen() {
    var toggleA by remember { mutableStateOf(true) }
    var toggleB by remember { mutableStateOf(false) }
    var chipSel by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 48.dp).testTag("showcase"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 27.dp),
    ) {
        item { Text("Showcase (debug)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface) }

        item { Section("Buttons: live (D-pad over them), disabled, static focused and pressed") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                FrostButton("Primary", {}, Modifier.testTag("sc_btn_primary"))
                FrostSecondaryButton("Secondary", {}, Modifier.testTag("sc_btn_secondary"))
                FrostButton("With icon", {}, icon = { Box(Modifier.size(14.dp).background(FrostColors.OnSurface, CircleShape)) })
                FrostButton("Disabled", {}, enabled = false)
                Static("Primary focused", FrostColors.PrimaryFocused, FrostTokens.PillShape, FrostTokens.SmallButtonFocusScale)
                Static("Primary pressed", FrostColors.PrimaryFocused, FrostTokens.PillShape, FrostTokens.PressedScale)
                Static("Secondary focused", FrostColors.ContainerFocused, FrostTokens.PillShape, FrostTokens.SmallButtonFocusScale)
            }
        }

        item { Section("Chips") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Streams", "Categories", "Videos").forEachIndexed { i, l -> FrostChip(l, chipSel == i, { chipSel = i }) }
                FrostChip("Disabled", false, {}, enabled = false)
                Static("Chip focused", FrostColors.ContainerFocused, FrostTokens.PillShape, FrostTokens.SmallButtonFocusScale)
                Static("Selected focused", FrostColors.PrimaryFocused, FrostTokens.PillShape, FrostTokens.SmallButtonFocusScale)
            }
        }

        item { Section("Cards") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                FrostCard({}, Modifier.width(220.dp).testTag("sc_card")) { CardBody() }
                FrostCard({}, Modifier.width(220.dp)) { CardBody() }
                Box(Modifier.width(220.dp).scale(FrostTokens.CardFocusScale)) {
                    Column(
                        Modifier.fillMaxWidth().background(FrostColors.ContainerFocused, FrostTokens.CardShape)
                            .border(FrostTokens.FocusBorderWidth, FrostColors.Violet, FrostTokens.CardShape),
                    ) { CardBody() }
                }
            }
        }

        item { Section("List rows and toggles") }
        item {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FrostListItem({}, Modifier.fillMaxWidth().testTag("sc_row")) { Text("List row", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium) }
                FrostListItem({}, Modifier.fillMaxWidth(), supporting = { Text("With a supporting line", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted) }) {
                    Text("List row with description", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                }
                FrostListItem({}, Modifier.fillMaxWidth(), selected = true) { Text("Selected row", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium) }
                TvToggleRow("Toggle on", "Switch with a description", toggleA, { toggleA = it }, Modifier.testTag("sc_toggle_on"))
                TvToggleRow("Toggle off", null, toggleB, { toggleB = it })
                TvChoiceRow("Choice row", "Stable", {})
                TvStepperRow("Stepper row", "30%", { _, _ -> })
                TvTextField(text, { text = it }, "Text field (OK to type)")
            }
        }

        item { Section("Round controls (player)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                FrostSurface({}, Modifier.size(44.dp), shape = CircleShape) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("II", style = MaterialTheme.typography.bodySmall) } }
                FrostSurface({}, Modifier.size(72.dp), shape = CircleShape, containerColor = FrostColors.Primary, focusedContainerColor = FrostColors.PrimaryFocused) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(">", style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CardBody() {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(FrostColors.SurfaceAlt))
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Channel name", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        Text("Stream title goes here", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
    }
}

/** A non-interactive drawing of a state from the tokens (so it can be shown without focus). */
@Composable
private fun Static(label: String, container: androidx.compose.ui.graphics.Color, shape: Shape, scale: Float) {
    Box(
        Modifier.height(FrostTokens.ButtonHeight).scale(scale)
            .background(container, shape)
            .then(if (scale >= 1f) Modifier.border(BorderStroke(FrostTokens.FocusBorderWidth, FrostColors.Violet), shape) else Modifier)
            .padding(horizontal = FrostTokens.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = FrostColors.OnSurface, maxLines = 1) }
}
