package tv.frostline.core.ui

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.file.Files

/** Debug overlay: how long the process has run and the counters that would creep up if something leaked. */
object HealthStats {
    /** Live counts read from /proc/self: threads, open file descriptors and how many of those are sockets. */
    data class Counts(val threads: Int, val fds: Int, val sockets: Int)

    fun counts(): Counts {
        var fds = 0
        var sockets = 0
        runCatching {
            File("/proc/self/fd").list()?.forEach { n ->
                fds++
                val target = runCatching { Files.readSymbolicLink(File("/proc/self/fd/$n").toPath()).toString() }.getOrNull()
                if (target != null && target.startsWith("socket:")) sockets++
            }
        }
        val threads = runCatching { File("/proc/self/task").list()?.size }.getOrNull() ?: Thread.getAllStackTraces().size
        return Counts(threads, fds, sockets)
    }

    private var statsSeq = 0

    /** What `frostline://debug/stats` logs (tag FrostlineDebug): the shell user cannot read this app's /proc/<pid>/fd, the app can. */
    fun statsLogLine(screen: String = ""): String {
        val c = counts()
        return "stats n=${++statsSeq} threads=${c.threads} fds=${c.fds} sockets=${c.sockets}" +
            (if (screen.isNotEmpty()) " screen=$screen" else "") + " player=${DebugPlayerState.text()}"
    }

    /** "uptime 3h12m, 61 threads, 180 fds (14 sockets)". Reads /proc, so call it every few seconds at most. */
    fun line(extra: String = ""): String {
        val up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val c = counts()
        val t = if (up >= 3600) "${up / 3600}h${(up % 3600) / 60}m" else "${up / 60}m${up % 60}s"
        return "uptime   $t, ${c.threads} threads, ${c.fds} fds (${c.sockets} sockets)" + extra
    }
}

/**
 * Which player is in front and whether it is playing, for `frostline://debug/stats` (tools/stress.ps1 checks it before every
 * sample). The owner token keeps a player that is being replaced from clearing the state of the one that replaced it.
 */
object DebugPlayerState {
    private class State(val owner: Any, val kind: String, val playing: Boolean)
    @Volatile private var state: State? = null

    fun report(owner: Any, kind: String, playing: Boolean) { state = State(owner, kind, playing) }
    fun clear(owner: Any) { if (state?.owner === owner) state = null }

    /** "none", "live:playing", "live:idle", "vod:playing", "vod:idle". */
    fun text(): String = state?.let { it.kind + ":" + (if (it.playing) "playing" else "idle") } ?: "none"
}
