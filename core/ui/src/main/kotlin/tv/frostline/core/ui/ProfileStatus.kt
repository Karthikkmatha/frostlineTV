package tv.frostline.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Baseline profile state for the debug overlay: "compiled", "awaiting" (installed, compiles when idle) or "none". Set by the app. */
object ProfileStatus {
    private val _line = MutableStateFlow("unknown")
    val line: StateFlow<String> = _line
    fun set(s: String) { _line.value = s }

    /** Reads the real state (set by the app). Blocking: call [refresh] off the main thread. */
    @Volatile var evaluator: (() -> String)? = null

    /** Re-reads the dexopt state now: `cmd package compile` can run while the app is up, so a value read once at start goes stale. */
    fun refresh() {
        val ev = evaluator ?: return
        runCatching { ev() }.getOrNull()?.let { _line.value = it }
    }
}

/**
 * What the debug overlay's Profile line says. [verifierCompiled]: ProfileVerifier saw the profile installed by the
 * profile installer and compiled. [verifierEnqueued]: installed and waiting for the idle compile. [oatFilter]: the
 * compiler filter recorded in the app's own odex file (null when there is none); it is the ground truth, because a
 * profile compiled by `cmd package compile -m speed-profile` is invisible to ProfileVerifier. [odexMapped]: the odex
 * file of this app is mapped into the process (seen in /proc/self/maps) even though its filter could not be read.
 */
object ProfileState {
    private val compiledFilters = setOf("speed-profile", "speed", "everything", "space-profile", "space", "everything-profile")

    fun text(verifierCompiled: Boolean, verifierEnqueued: Boolean, oatFilter: String?, odexMapped: Boolean = false): String = when {
        oatFilter != null && oatFilter in compiledFilters -> "compiled ($oatFilter)"
        verifierCompiled -> "compiled (profile)"
        oatFilter == null && odexMapped -> "compiled (odex mapped)"
        verifierEnqueued -> "awaiting"
        oatFilter != null -> "not compiled ($oatFilter)"
        else -> "none"
    }

    /**
     * The path of this app's own `base.odex` as the process has it mapped (a /proc/self/maps line ends with the path), or null.
     * [pkg] keeps WebView's or another app's odex out of the answer.
     */
    fun odexPathFromMaps(maps: Sequence<String>, pkg: String): String? {
        for (line in maps) {
            val i = line.indexOf('/')
            if (i < 0) continue
            val path = line.substring(i).trim()
            if (path.endsWith("/base.odex") && path.contains("/$pkg-")) return path
        }
        return null
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
