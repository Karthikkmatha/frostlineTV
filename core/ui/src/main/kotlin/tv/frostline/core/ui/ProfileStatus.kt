package tv.frostline.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Baseline profile state for the debug overlay: "compiled", "awaiting" (installed, compiles when idle) or "none". Set by the app. */
object ProfileStatus {
    private val _line = MutableStateFlow("unknown")
    val line: StateFlow<String> = _line
    fun set(s: String) { _line.value = s }
}

/**
 * What the debug overlay's Profile line says. [verifierCompiled]: ProfileVerifier saw the profile installed by the
 * profile installer and compiled. [verifierEnqueued]: installed and waiting for the idle compile. [oatFilter]: the
 * compiler filter recorded in the app's own odex file (null when there is none); it is the ground truth, because a
 * profile compiled by `cmd package compile -m speed-profile` is invisible to ProfileVerifier.
 */
object ProfileState {
    private val compiledFilters = setOf("speed-profile", "speed", "everything", "space-profile", "space", "everything-profile")

    fun text(verifierCompiled: Boolean, verifierEnqueued: Boolean, oatFilter: String?): String = when {
        verifierCompiled -> "compiled"
        oatFilter != null && oatFilter in compiledFilters -> "compiled (cmd)"
        verifierEnqueued -> "awaiting"
        else -> "none"
    }

    /** The compiler filter in the first bytes of an odex/oat file (its key-value store holds `compiler-filter` then the value), or null. */
    fun filterIn(header: ByteArray): String? {
        val text = String(header, Charsets.ISO_8859_1)
        val key = text.indexOf("compiler-filter\u0000")
        if (key < 0) return null
        val start = key + "compiler-filter\u0000".length
        val end = text.indexOf('\u0000', start)
        if (end <= start) return null
        return text.substring(start, end).takeIf { v -> v.length < 40 && v.all { it.isLetter() || it == '-' } }
    }
}
