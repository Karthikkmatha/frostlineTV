package tv.frostline.core.ui

import androidx.compose.foundation.border
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

/** False while a screen transition is running, so no focus border flashes on a half-moved screen. */
val LocalFocusRingEnabled = compositionLocalOf { true }

/** Remembers where focus last was inside a screen so it can be restored after navigating back. */
class FocusMemory {
    var key: String? = null
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
    /** First visible item and offset of each horizontal row of a rows screen, by row key. */
    val rowScroll: HashMap<String, Pair<Int, Int>> = HashMap()
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
 * The focus border (2 dp, #8F74E8) for the few focus targets that are not tv-material components (the chat message
 * list, the resize hint). Every button, chip, card and row gets the same border from [FrostTokens.border].
 */
@Composable
fun Modifier.frostFocusBorder(focused: Boolean, shape: androidx.compose.ui.graphics.Shape = FrostTokens.RowShape): Modifier =
    if (focused && LocalFocusRingEnabled.current) border(FrostTokens.FocusBorderWidth, FrostColors.Violet, shape) else this
