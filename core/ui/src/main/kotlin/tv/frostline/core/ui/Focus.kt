package tv.frostline.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.ExperimentalTvMaterial3Api

/** False while a screen transition is running, so no focus ring flashes on a half-moved screen. */
val LocalFocusRingEnabled = compositionLocalOf { true }

val CardShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun focusRingBorder(shape: androidx.compose.ui.graphics.Shape = CardShape): ClickableSurfaceBorder {
    val enabled = LocalFocusRingEnabled.current
    return ClickableSurfaceDefaults.border(
        focusedBorder = if (enabled) Border(BorderStroke(3.dp, FrostColors.Violet), shape = shape) else Border.None,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun cardShape(shape: androidx.compose.ui.graphics.Shape = CardShape): ClickableSurfaceShape =
    ClickableSurfaceDefaults.shape(shape = shape)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun cardScale(focused: Float = 1.04f): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = focused)

/** Remembers where focus last was inside a screen so it can be restored after navigating back. */
class FocusMemory {
    var key: String? = null
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
}

fun Modifier.rememberFocusIn(key: String, memory: FocusMemory): Modifier =
    onFocusChanged { if (it.hasFocus) memory.key = key }

@Composable
fun FocusRingScope(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFocusRingEnabled provides enabled, content = content)
}

@Composable
fun rememberHasFocus(): Pair<Boolean, Modifier> {
    var focused by remember { mutableStateOf(false) }
    return focused to Modifier.onFocusChanged { focused = it.isFocused }
}

/** Requests focus and says whether it was granted (false while the target is not composed or attached yet). */
fun FocusRequester.tryFocus(): Boolean {
    val result: Any? = runCatching { requestFocus() }.getOrNull()
    return result == true || result == Unit
}

/**
 * The one focus style of the app: a 3 dp violet (#8F74E8) ring drawn over the element whenever it, or something inside
 * it, has focus. Components pair it with the lighter focused surface ([FrostColors.SurfaceFocused]) in their own colours.
 * Apply it to the outside of a clickable surface so the ring can never be hidden by the surface's own content.
 */
@Composable
fun Modifier.frostFocus(shape: androidx.compose.ui.graphics.Shape = CardShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val enabled = LocalFocusRingEnabled.current
    return this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused && enabled) Modifier.border(3.dp, FrostColors.Violet, shape) else Modifier)
}
