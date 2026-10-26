package tv.frostline.core.ui

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.file.Files

/** Debug overlay: how long the process has run and the counters that would creep up if something leaked. */
object HealthStats {
    /** "uptime 3h12m, 61 threads, 180 fds (14 sockets)". Reads /proc, so call it every few seconds at most. */
    fun line(extra: String = ""): String {
        val up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val threads = Thread.getAllStackTraces().size
        var fds = 0
        var sockets = 0
        runCatching {
            File("/proc/self/fd").list()?.forEach { n ->
                fds++
                val target = runCatching { Files.readSymbolicLink(File("/proc/self/fd/$n").toPath()).toString() }.getOrNull()
                if (target != null && target.startsWith("socket:")) sockets++
            }
        }
        val t = if (up >= 3600) "${up / 3600}h${(up % 3600) / 60}m" else "${up / 60}m${up % 60}s"
        return "uptime   $t, $threads threads, $fds fds ($sockets sockets)" + extra
    }
}
