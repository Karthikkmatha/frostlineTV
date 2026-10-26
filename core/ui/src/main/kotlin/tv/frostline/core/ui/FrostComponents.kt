package tv.frostline.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text

/**
 * Phase C design tokens. Every button, chip, card and row of the app is built by one of the wrappers below, which
 * configure the tv-material components (Button, FilterChip, Card, ListItem, Surface) with these values. Motion is the
 * tv-material default (scale and colour tween on focus change only); nothing here animates while idle.
 */
object FrostTokens {
    /** Pill: buttons and chips. */
    val PillShape: Shape = RoundedCornerShape(percent = 50)
    /** Cards, 12 dp corners. */
    val CardShape: Shape = RoundedCornerShape(12.dp)
    /** List rows and sheet rows. */
    val RowShape: Shape = RoundedCornerShape(12.dp)

    val ButtonHeight: Dp = 40.dp
    val RowMinHeight: Dp = 56.dp
    val HorizontalPadding: Dp = 20.dp
    val IconGap: Dp = 8.dp
    val LabelSize = 15.sp
    val LabelWeight = FontWeight.Medium

    val FocusBorderWidth: Dp = 2.dp
    const val SmallButtonFocusScale = 1.1f
    const val CardFocusScale = 1.05f
    /** Full-width rows: a 1.05 scale would push past the sheet that holds them. */
    const val RowFocusScale = 1.02f
    const val PressedScale = 0.95f

    private val glowColor = FrostColors.Violet.copy(alpha = 0.35f)
    val FocusGlow = Glow(elevationColor = glowColor, elevation = 6.dp)

    /** The focused border, or none while a screen transition is running ([LocalFocusRingEnabled]). */
    @Composable
    fun border(shape: Shape): Border =
        if (LocalFocusRingEnabled.current) Border(BorderStroke(FocusBorderWidth, FrostColors.Violet), shape = shape) else Border.None
}

enum class FrostButtonStyle { Primary, Secondary }

/** A pill button, 40 dp high, label Medium 15 sp, optional leading icon 8 dp from the label. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FrostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: FrostButtonStyle = FrostButtonStyle.Primary,
    icon: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = FrostTokens.HorizontalPadding,
) {
    val primary = style == FrostButtonStyle.Primary
    val ring = FrostTokens.border(FrostTokens.PillShape)
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier.height(FrostTokens.ButtonHeight),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = FrostTokens.PillShape),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) FrostColors.Primary else FrostColors.Container,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = if (primary) FrostColors.PrimaryFocused else FrostColors.ContainerFocused,
            focusedContentColor = FrostColors.OnSurface,
            pressedContainerColor = if (primary) FrostColors.PrimaryFocused else FrostColors.ContainerFocused,
            pressedContentColor = FrostColors.OnSurface,
            disabledContainerColor = FrostColors.SurfaceAlt,
            disabledContentColor = FrostColors.Muted,
        ),
        scale = ButtonDefaults.scale(focusedScale = FrostTokens.SmallButtonFocusScale, pressedScale = FrostTokens.PressedScale),
        border = ButtonDefaults.border(focusedBorder = ring, pressedBorder = ring),
        glow = ButtonDefaults.glow(focusedGlow = FrostTokens.FocusGlow),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(FrostTokens.IconGap))
        }
        Text(text, fontSize = FrostTokens.LabelSize, fontWeight = FrostTokens.LabelWeight, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** The quieter button next to a primary one: Back, Cancel, Close. */
@Composable
fun FrostSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = FrostTokens.HorizontalPadding,
) = FrostButton(text, onClick, modifier, enabled, FrostButtonStyle.Secondary, icon, horizontalPadding)

/** A selectable pill chip (tabs, filters), 40 dp high. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FrostChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ring = FrostTokens.border(FrostTokens.PillShape)
    androidx.tv.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(FrostTokens.ButtonHeight),
        enabled = enabled,
        shape = FilterChipDefaults.shape(shape = FrostTokens.PillShape),
        colors = FilterChipDefaults.colors(
            containerColor = FrostColors.Container,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.ContainerFocused,
            focusedContentColor = FrostColors.OnSurface,
            pressedContainerColor = FrostColors.ContainerFocused,
            pressedContentColor = FrostColors.OnSurface,
            selectedContainerColor = FrostColors.Primary,
            selectedContentColor = FrostColors.OnSurface,
            disabledContainerColor = FrostColors.SurfaceAlt,
            disabledContentColor = FrostColors.Muted,
            focusedSelectedContainerColor = FrostColors.PrimaryFocused,
            focusedSelectedContentColor = FrostColors.OnSurface,
            pressedSelectedContainerColor = FrostColors.PrimaryFocused,
            pressedSelectedContentColor = FrostColors.OnSurface,
        ),
        scale = FilterChipDefaults.scale(
            focusedScale = FrostTokens.SmallButtonFocusScale,
            pressedScale = FrostTokens.PressedScale,
            focusedSelectedScale = FrostTokens.SmallButtonFocusScale,
            pressedSelectedScale = FrostTokens.PressedScale,
        ),
        border = FilterChipDefaults.border(
            focusedBorder = ring, pressedBorder = ring, focusedSelectedBorder = ring, pressedSelectedBorder = ring,
        ),
        glow = FilterChipDefaults.glow(focusedGlow = FrostTokens.FocusGlow, focusedSelectedGlow = FrostTokens.FocusGlow),
    ) {
        Text(
            label,
            fontSize = FrostTokens.LabelSize, fontWeight = FrostTokens.LabelWeight, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** A clickable card with 12 dp corners. The content is a column (thumbnail, then text). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FrostCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = FrostTokens.CardFocusScale,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ring = FrostTokens.border(FrostTokens.CardShape)
    androidx.tv.material3.Card(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape = FrostTokens.CardShape),
        colors = CardDefaults.colors(
            containerColor = FrostColors.Container,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.ContainerFocused,
            focusedContentColor = FrostColors.OnSurface,
            pressedContainerColor = FrostColors.ContainerFocused,
            pressedContentColor = FrostColors.OnSurface,
        ),
        scale = CardDefaults.scale(focusedScale = focusedScale, pressedScale = if (focusedScale == 1f) 1f else FrostTokens.PressedScale),
        border = CardDefaults.border(focusedBorder = ring, pressedBorder = ring),
        glow = CardDefaults.glow(focusedGlow = FrostTokens.FocusGlow),
        content = content,
    )
}

/**
 * A list row (Settings, sheets, menus): at least 56 dp high, 12 dp corners, optional supporting line, leading and
 * trailing content. [selected] shows the primary container.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FrostListItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    val ring = FrostTokens.border(FrostTokens.RowShape)
    androidx.tv.material3.ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = headline,
        modifier = modifier.heightIn(min = FrostTokens.RowMinHeight),
        enabled = enabled,
        onLongClick = onLongClick,
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = trailing,
        shape = ListItemDefaults.shape(shape = FrostTokens.RowShape),
        colors = ListItemDefaults.colors(
            containerColor = FrostColors.Container,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.ContainerFocused,
            focusedContentColor = FrostColors.OnSurface,
            pressedContainerColor = FrostColors.ContainerFocused,
            pressedContentColor = FrostColors.OnSurface,
            selectedContainerColor = FrostColors.Primary,
            selectedContentColor = FrostColors.OnSurface,
            disabledContainerColor = FrostColors.Container,
            disabledContentColor = FrostColors.Muted,
            focusedSelectedContainerColor = FrostColors.PrimaryFocused,
            focusedSelectedContentColor = FrostColors.OnSurface,
            pressedSelectedContainerColor = FrostColors.PrimaryFocused,
            pressedSelectedContentColor = FrostColors.OnSurface,
        ),
        scale = ListItemDefaults.scale(
            focusedScale = FrostTokens.RowFocusScale, pressedScale = FrostTokens.PressedScale,
            focusedSelectedScale = FrostTokens.RowFocusScale,
        ),
        border = ListItemDefaults.border(
            focusedBorder = ring, pressedBorder = ring, focusedSelectedBorder = ring,
        ),
        glow = ListItemDefaults.glow(focusedGlow = FrostTokens.FocusGlow, focusedSelectedGlow = FrostTokens.FocusGlow),
    )
}

/**
 * The generic clickable surface for things that are none of the above (rail icons, emote cells, banners, round
 * player controls). Same border, glow, press scale and colours as the other wrappers; [focusedScale] defaults to the
 * small-button scale, so pass 1f for a wide banner.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FrostSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = FrostTokens.RowShape,
    focusedScale: Float = FrostTokens.SmallButtonFocusScale,
    containerColor: Color = FrostColors.Container,
    focusedContainerColor: Color = FrostColors.ContainerFocused,
    contentColor: Color = FrostColors.OnSurface,
    focusedContentColor: Color = FrostColors.OnSurface,
    content: @Composable BoxScope.() -> Unit,
) {
    val ring = FrostTokens.border(shape)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = focusedContentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = FrostColors.Muted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = if (focusedScale == 1f) 1f else FrostTokens.PressedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = ring, pressedBorder = ring),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = FrostTokens.FocusGlow),
        content = content,
    )
}
