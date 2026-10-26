package tv.frostline.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Palette. Every text/background pairing used by the app is checked for WCAG AA in ContrastTest. */
object FrostColors {
    val Background = Color(0xFF050507)
    val Surface = Color(0xFF121019)
    val SurfaceAlt = Color(0xFF1B1726)
    /** Resting container of buttons, chips, cards and list rows (Phase C design token). */
    val Container = Color(0xFF262033)
    /** The same container while focused: lighter, with the violet border on top. */
    val ContainerFocused = Color(0xFF3A2F57)
    /** Alias of [ContainerFocused], kept for call sites that mean "a focused surface". */
    val SurfaceFocused = ContainerFocused

    /** Buttons, selected state, live/progress bars. Too dark for text or rings on the dark background. */
    val Primary = Color(0xFF4E2E99)
    /** A focused button: lighter than [Primary], with the violet ring on top. */
    val PrimaryFocused = Color(0xFF6A47C7)
    /** Focus rings, links, highlighted text on dark surfaces. */
    val Violet = Color(0xFF8F74E8)
    /** Subtle text accents. */
    val Lavender = Color(0xFFC9BCF5)
    /** Info and the emote-menu active tab. */
    val Ice = Color(0xFF8FD3FF)

    val OnSurface = Color(0xFFEEEBF5)
    val Muted = Color(0xFFA49FB8)
    val Live = Color(0xFFFF4D5E)
}

/** "frostline" in a regular weight followed by a bold "TV". */
fun brandName(): AnnotatedString = buildAnnotatedString {
    append("frostline")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("TV") }
}

@Composable
fun FrostlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FrostColors.Primary,
            onPrimary = FrostColors.OnSurface,
            primaryContainer = FrostColors.Primary,
            onPrimaryContainer = FrostColors.OnSurface,
            secondary = FrostColors.Violet,
            surface = FrostColors.Surface,
            onSurface = FrostColors.OnSurface,
            surfaceVariant = FrostColors.SurfaceFocused,
            onSurfaceVariant = FrostColors.Muted,
            background = FrostColors.Background,
            onBackground = FrostColors.OnSurface,
            border = FrostColors.Violet,
            error = FrostColors.Live,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(FrostColors.Background)) { content() }
    }
}
