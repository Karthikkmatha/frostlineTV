package tv.frostline.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Baseline profile state for the debug overlay: "compiled", "awaiting" (installed, compiles when idle) or "none". Set by the app. */
object ProfileStatus {
    private val _line = MutableStateFlow("unknown")
    val line: StateFlow<String> = _line
    fun set(s: String) { _line.value = s }
}
