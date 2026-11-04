package tv.frostline.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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

/** What the odex probe found: the compiler [filter] if any method could read it, whether an odex/vdex/art of this app [present]ly exists, and which [method] answered. */
class OdexReading(val filter: String?, val present: Boolean, val method: String)

/**
 * What the debug overlay's Profile line says. [verifierCompiled]: ProfileVerifier saw the profile installed by the
 * profile installer and compiled. [verifierEnqueued]: installed and waiting for the idle compile. [reading]: what the odex
 * probe found. The compiler filter recorded for the app is the ground truth, because a profile compiled by
 * `cmd package compile -m speed-profile` is invisible to ProfileVerifier. "awaiting" is only said when the verifier
 * really says enqueued and no odex exists at all; anything unclear is "unknown". The method that answered is appended.
 */
object ProfileState {
    private val compiledFilters = setOf("speed-profile", "speed", "everything", "space-profile", "space", "everything-profile")

    fun text(verifierCompiled: Boolean, verifierEnqueued: Boolean, reading: OdexReading?): String {
        val filter = reading?.filter
        val via = reading?.method?.let { " via $it" }.orEmpty()
        return when {
            filter != null && filter in compiledFilters -> "compiled ($filter)$via"
            verifierCompiled -> "compiled (profile) via verifier"
            filter != null -> "not compiled ($filter)$via"
            reading != null && reading.present -> "odex present, filter unknown$via"
            verifierEnqueued && reading != null -> "awaiting (no odex) via verifier"
            else -> "unknown"
        }
    }

    /**
     * Finds this app's own odex facts, in this order: (1) /proc/self/maps lines ending in base.odex, base.vdex or base.art
     * under the app's own directory; (2) `<sourceDir parent>/oat/<isa>/base.odex` existing with a readable header;
     * (3) reflection on the hidden DexFile optimisation status. Every step is injected so tests can fake it.
     */
    fun probe(
        pkg: String,
        mapsLines: Sequence<String>,
        sourceDirParent: File?,
        isas: List<String>,
        readHeader: (File) -> ByteArray?,
        exists: (File) -> Boolean = { it.exists() },
        reflect: () -> String? = { null },
    ): OdexReading {
        var present = false
        // 1. maps
        val mapped = artifactPathsFromMaps(mapsLines, pkg)
        if (mapped.isNotEmpty()) {
            present = true
            for (path in mapped) {
                val odex = File(File(path).parentFile, "base.odex")
                val f = readHeader(odex)?.let { filterIn(it) }
                if (f != null) return OdexReading(f, true, "maps")
            }
        }
        // 2. the file beside the apk
        val oat = sourceDirParent?.let { File(it, "oat") }
        if (oat != null) {
            for (isa in isas) {
                val odex = File(File(oat, isa), "base.odex")
                if (!exists(odex)) continue
                present = true
                val f = readHeader(odex)?.let { filterIn(it) }
                if (f != null) return OdexReading(f, true, "file")
            }
        }
        // 3. reflection
        reflect()?.takeIf { it.isNotBlank() && it.length < 40 && it.all { c -> c.isLetter() || c == '-' } }?.let { return OdexReading(it, present, "reflect") }
        return OdexReading(null, present, if (mapped.isNotEmpty()) "maps" else if (present) "file" else "none")
    }

    /** Paths of this app's base.odex / base.vdex / base.art as the process has them mapped (a /proc/self/maps line ends with the path). */
    fun artifactPathsFromMaps(maps: Sequence<String>, pkg: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in maps) {
            val i = line.indexOf('/')
            if (i < 0) continue
            val path = line.substring(i).trim()
            if ((path.endsWith("/base.odex") || path.endsWith("/base.vdex") || path.endsWith("/base.art")) && path.contains("/$pkg-")) out += path
        }
        return out.toList()
    }

    /** The path of this app's own `base.odex` as the process has it mapped, or null. [pkg] keeps WebView's or another app's odex out of the answer. */
    fun odexPathFromMaps(maps: Sequence<String>, pkg: String): String? =
        artifactPathsFromMaps(maps, pkg).firstOrNull { it.endsWith("/base.odex") }

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
