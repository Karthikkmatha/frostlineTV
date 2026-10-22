package tv.frostline.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object FrostColors {
    val Background = Color(0xFF0B1118)
    val Surface = Color(0xFF16202B)
    val SurfaceFocused = Color(0xFF22313F)
    val Ice = Color(0xFF8FD3FF)
    val OnSurface = Color(0xFFE8EEF4)
    val Muted = Color(0xFF9AA9B8)
    val Live = Color(0xFFE5484D)
}

@Composable
fun FrostlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FrostColors.Ice,
            onPrimary = FrostColors.Background,
            surface = FrostColors.Surface,
            onSurface = FrostColors.OnSurface,
            surfaceVariant = FrostColors.SurfaceFocused,
            onSurfaceVariant = FrostColors.Muted,
            background = FrostColors.Background,
            onBackground = FrostColors.OnSurface,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(FrostColors.Background)) { content() }
    }
}
