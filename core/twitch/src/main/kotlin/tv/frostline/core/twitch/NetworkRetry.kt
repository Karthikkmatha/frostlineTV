package tv.frostline.core.twitch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Network trouble handling shared by every screen: what counts as "no connection", the friendly message, the retry
 * backoff (2 s, 5 s, 15 s, then every 30 s) and a signal that fires when the network comes back.
 */
object NetworkRetry {
    const val MESSAGE = "No connection, retrying…"

    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits each time the default network becomes available (after an outage). Silent until [start] ran. */
    val available: SharedFlow<Unit> = _available.asSharedFlow()

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _available.tryEmit(Unit) }
            })
        }
    }

    /** True for DNS, connect, timeout, reset and TLS-handshake failures. HTTP error statuses are not network errors. */
    fun isNetworkError(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 6) {
            if (t is UnknownHostException || t is ConnectException || t is SocketTimeoutException || t is SocketException || t is SSLException) return true
            if (t is IOException && t.javaClass == IOException::class.java && t.message?.contains("unexpected end of stream", true) == true) return true
            t = t.cause
        }
        return false
    }

    /** The text the user sees: the friendly message for network errors, else [e]'s own message or [fallback]. */
    fun userMessage(e: Throwable, fallback: String): String = if (isNetworkError(e)) MESSAGE else (e.message ?: fallback)

    /** Delay before retry number [attempt] (0-based). */
    fun delayMs(attempt: Int): Long = when (attempt) { 0 -> 2_000L; 1 -> 5_000L; 2 -> 15_000L; else -> 30_000L }
}
