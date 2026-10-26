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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
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
    /** Text on the [Live] red (the LIVE pill). */
    val OnLive = Color.White
    /** Behind the video surface. */
    val VideoBackdrop = Color.Black
    /** Top and bottom scrims over the video (black at 70 %). */
    val Scrim = Color.Black.copy(alpha = 0.7f)
    /** Dark see-through plate behind badges and debug text: the Background colour at 80 %. */
    val Plate = Background.copy(alpha = 0.8f)
    /** Round controls over the video: [Container] a little see-through. */
    val OverlayContainer = Container.copy(alpha = 0.8f)
    /** Unfilled part of a progress track over the video. */
    val Track = OnSurface.copy(alpha = 0.4f)
    /** Side-panel sheet surface: [SurfaceAlt] at 96 %. */
    val Sheet = SurfaceAlt.copy(alpha = 0.96f)
    /** Prediction outcome colours (Twitch's blue and pink, then two more). */
    val OutcomeBlue = Color(0xFF4DA3FF)
    val OutcomePink = Color(0xFFFF6FB5)
    val OutcomeGreen = Color(0xFF8FD3A0)
    val OutcomeGold = Color(0xFFE8C468)
}

/** "frostline" in a regular weight followed by a bold "TV". */
fun brandName(): AnnotatedString = buildAnnotatedString {
    append("frostline")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("TV") }
}

/** Screen safe area (5% overscan on a 960x540 dp screen). The player video itself stays full-bleed. */
object FrostLayout {
    val SafeX = 48.dp
    val SafeY = 27.dp
    /** Gap between the collapsed rail and the content. */
    val RailGutter = 16.dp
    /** Gap between rows of cards and between cards of a row. */
    val CardGap = 14.dp
}

/** The one type scale. System font; sizes in sp. */
val FrostTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = FrostColors.Muted),
)

@Composable
fun FrostlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = FrostTypography,
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
