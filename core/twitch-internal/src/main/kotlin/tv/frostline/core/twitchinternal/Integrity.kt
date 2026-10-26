package tv.frostline.core.twitchinternal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What Twitch's web page sends with its GQL calls to prove it is a real client: the integrity token and the device id
 * it is bound to. Held in memory only. [toString] never shows the values.
 */
class IntegrityCreds(val token: String, val deviceId: String, val clientId: String, val expiresAtMs: Long) {
    override fun toString() = "IntegrityCreds(expiresAtMs=$expiresAtMs)"
}

/** Source of [IntegrityCreds]. Implementations must never log the values. */
interface IntegrityProvider {
    /** Cached credentials while they are fresh; otherwise captures new ones. Null when none could be had. */
    suspend fun current(forceRefresh: Boolean = false): IntegrityCreds?

    object None : IntegrityProvider {
        override suspend fun current(forceRefresh: Boolean): IntegrityCreds? = null
    }
}

/** Debug overlay line ("integrity ..."): state, age and the last error. Never a value. */
object IntegrityStatus {
    private val _line = MutableStateFlow("idle")
    val line: StateFlow<String> = _line.asStateFlow()
    internal fun set(text: String) { _line.value = text }
}

/**
 * Debug overlay line ("webview ..."): how many hidden integrity WebViews were created and destroyed, how many are alive,
 * and how many WebView renderer (sandboxed) processes this app can still see a few seconds after the last destroy.
 * A healthy app reads "live 0 ... renderers 0" between captures.
 */
object WebViewRenderers {
    private val created = java.util.concurrent.atomic.AtomicInteger()
    private val destroyed = java.util.concurrent.atomic.AtomicInteger()
    private val _line = MutableStateFlow("none created")
    val line: StateFlow<String> = _line.asStateFlow()

    internal fun onCreated() { created.incrementAndGet(); publish(null) }
    internal fun onDestroyed() { destroyed.incrementAndGet(); publish(null) }

    /** Counts the renderer processes (ActivityManager, then /proc where visible) and republishes the line. */
    internal fun refresh(context: Context) = publish(renderers(context))

    private fun publish(renderers: Int?) {
        val live = created.get() - destroyed.get()
        _line.value = "live $live (created ${created.get()}, destroyed ${destroyed.get()})" + (renderers?.let { ", renderer procs $it" } ?: "")
    }

    private fun renderers(context: Context): Int {
        val names = HashSet<String>()
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.forEach { if (it.processName.contains("sandboxed_process") || it.processName.contains("webview", true)) names += "am:${it.pid}" }
        }
        runCatching {
            java.io.File("/proc").listFiles()?.forEach { d ->
                if (d.name.all(Char::isDigit)) {
                    val cmd = java.io.File(d, "cmdline").takeIf { it.canRead() }?.readText().orEmpty()
                    if (cmd.contains("sandboxed_process") || cmd.contains("webview_service")) names += "proc:${d.name}"
                }
            }
        }
        return names.map { it.substringAfter(':') }.toSet().size
    }
}

/** The player tells the integrity capture when a stream is starting, so the hidden WebView never competes with it. */
object IntegrityGate {
    @Volatile private var startingAtMs = 0L
    @Volatile private var startedAtMs = 0L

    const val SETTLE_MS = 30_000L

    fun streamStarting() { startingAtMs = System.currentTimeMillis() }
    fun streamStarted() { startedAtMs = System.currentTimeMillis() }

    /** False while a stream is starting (for at most 60 s) and for [settleMs] after playback began (also after a channel switch). */
    internal fun mayRun(now: Long, settleMs: Long = SETTLE_MS): Boolean {
        val startingNow = startingAtMs > startedAtMs && now - startingAtMs < 60_000L
        return !startingNow && now - startedAtMs >= settleMs
    }
}

/** How often a capture may run: the first one at once, later ones at most hourly, a forced one (integrity error) after 10 minutes. */
internal object IntegrityPolicy {
    const val ROUTINE_GAP_MS = 60 * 60_000L
    const val FORCED_GAP_MS = 10 * 60_000L

    fun mayCapture(nowMs: Long, lastCaptureMs: Long, haveCreds: Boolean, forced: Boolean): Boolean = when {
        !haveCreds || lastCaptureMs == 0L -> true
        forced -> nowMs - lastCaptureMs >= FORCED_GAP_MS
        else -> nowMs - lastCaptureMs >= ROUTINE_GAP_MS
    }
}

/** Cookies of twitch.tv before and after a capture: only the ones the capture added are removed, never the linked session. */
internal object CookieHygiene {
    /** Device identity cookies stay: a stable device id keeps the integrity token bound to the same device. */
    private fun keep(name: String) = name.startsWith("unique_id")

    fun names(cookieHeader: String?): Set<String> =
        cookieHeader.orEmpty().split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }.toSet()

    fun value(cookieHeader: String?, name: String): String? =
        cookieHeader.orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")

    /** Names present after but not before, minus the device identity ones: these are expired again. */
    fun addedByCapture(before: String?, after: String?): Set<String> = names(after) - names(before) - names(after).filter(::keep).toSet()
}

internal object IntegrityTokens {
    const val DEFAULT_TTL_MS = 12 * 60 * 60 * 1000L

    /** Expiry (epoch ms) read from the token's payload (`"exp"`), or now + 12 h. Never throws. */
    fun expiryMs(token: String, now: Long): Long {
        val fallback = now + DEFAULT_TTL_MS
        return runCatching {
            val body = token.split('.').getOrNull(2) ?: return fallback
            val bytes = java.util.Base64.getUrlDecoder().decode(body.padEnd((body.length + 3) / 4 * 4, '='))
            val text = String(bytes, Charsets.ISO_8859_1)
            val raw = Regex("\"exp\"\\s*:\\s*\"?(\\d{9,13})").find(text)?.groupValues?.get(1)?.toLong() ?: return fallback
            val ms = if (raw < 100_000_000_000L) raw * 1000 else raw
            ms.coerceIn(now + 60_000L, fallback)
        }.getOrDefault(fallback)
    }

    fun isIntegrityError(messages: List<String>) = messages.any { it.contains("integrity", ignoreCase = true) }
}

/**
 * Captures the integrity token with a headless WebView: it loads twitch.tv with the linked session's cookie, and
 * `shouldInterceptRequest` reads the headers of the page's own gql.twitch.tv calls. The WebView is 1x1, never attached
 * to a window, and fully destroyed after each run (or after [timeoutMs]). Runs at first use, when the credentials
 * expire, on an integrity error, with backoff after failures, and never while a stream is starting.
 */
class WebViewIntegrityProvider(
    context: Context,
    private val session: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 30_000L,
) : IntegrityProvider {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var cached: IntegrityCreds? = null
    private var capturedAtMs = 0L
    private var failures = 0
    private var retryNotBeforeMs = 0L

    override suspend fun current(forceRefresh: Boolean): IntegrityCreds? {
        val c = cached
        if (!forceRefresh && c != null && clock() < c.expiresAtMs - 5 * 60_000L) return c
        return mutex.withLock {
            val again = cached
            if (!forceRefresh && again != null && clock() < again.expiresAtMs - 5 * 60_000L) return@withLock again
            if (!IntegrityPolicy.mayCapture(clock(), capturedAtMs, again != null, forceRefresh)) {
                status("kept (next capture allowed in ${((if (forceRefresh) IntegrityPolicy.FORCED_GAP_MS else IntegrityPolicy.ROUTINE_GAP_MS) - (clock() - capturedAtMs)) / 60_000 + 1} min)")
                return@withLock again
            }
            val token = session()?.trim()?.takeIf { it.isNotEmpty() } ?: run { status("no web session"); return@withLock null }
            if (clock() < retryNotBeforeMs) { status("backing off after $failures failure(s)"); return@withLock cached }
            while (!IntegrityGate.mayRun(clock())) { status("waiting for the stream to settle"); delay(1_000) }
            status("capturing")
            val got = capture(token)
            if (got != null) {
                cached = got; capturedAtMs = clock(); failures = 0; retryNotBeforeMs = 0
                status("ok")
            } else {
                failures++
                retryNotBeforeMs = clock() + minOf(10 * 60_000L, 30_000L shl minOf(failures - 1, 5))
            }
            cached
        }
    }

    private fun status(state: String, error: String? = null) {
        val c = cached
        val age = if (c != null) ", age ${(clock() - capturedAtMs) / 60_000} min" else ""
        IntegrityStatus.set(state + age + (error?.let { ", last error: $it" } ?: ""))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(authToken: String): IntegrityCreds? {
        val result = CompletableDeferred<IntegrityCreds?>()
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        var cookiesBefore: String? = null
        var authBefore: String? = null
        val destroy = Runnable {
            web?.let { w ->
                runCatching { w.stopLoading(); w.webViewClient = WebViewClient(); w.loadUrl("about:blank"); w.removeAllViews(); w.destroy() }
                WebViewRenderers.onDestroyed()
                // Give the renderer process a moment to exit, then record whether one lingers.
                main.postDelayed({ WebViewRenderers.refresh(appContext) }, 3_000L)
            }
            web = null
        }
        main.post {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cookiesBefore = cm.getCookie("https://www.twitch.tv")
                authBefore = CookieHygiene.value(cookiesBefore, "auth-token")
                val value = authToken.removePrefix("auth-token=").trim('"', '\'', ' ', ';')
                cm.setCookie("https://www.twitch.tv", "auth-token=$value; Domain=.twitch.tv; Path=/; Secure")
                cm.flush()
                val w = WebView(appContext)
                WebViewRenderers.onCreated()
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = true
                w.settings.cacheMode = WebSettings.LOAD_DEFAULT
                // Cheapest capture possible: no images, and the renderer process runs at the lowest priority.
                w.settings.loadsImagesAutomatically = false
                w.settings.blockNetworkImage = true
                if (android.os.Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                w.layout(0, 0, 1, 1)
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val r = request ?: return null
                        if (r.url.host == "gql.twitch.tv" && !result.isCompleted) {
                            val h = r.requestHeaders
                            val tok = h.entries.firstOrNull { it.key.equals("Client-Integrity", true) }?.value
                            if (!tok.isNullOrEmpty()) {
                                val dev = h.entries.firstOrNull { it.key.equals("X-Device-Id", true) || it.key.equals("Device-Id", true) }?.value
                                    ?: deviceIdCookie()
                                val cid = h.entries.firstOrNull { it.key.equals("Client-Id", true) }?.value ?: GqlWebSessionValidator.WEB_CLIENT_ID
                                if (!dev.isNullOrEmpty()) result.complete(IntegrityCreds(tok, dev, cid, IntegrityTokens.expiryMs(tok, clock())))
                            }
                        }
                        return null
                    }
                }
                w.loadUrl("https://www.twitch.tv/")
            } catch (e: Throwable) {
                status("failed", e.javaClass.simpleName)
                result.complete(null)
            }
        }
        val got = withTimeoutOrNull(timeoutMs) { result.await() }
        main.post(destroy)
        // Put the cookie store back as it was: remove only what this capture added and restore a previous auth-token. Never
        // removeAllCookies: the linked web session's own cookies share this store.
        main.post {
            runCatching {
                val cm = CookieManager.getInstance()
                CookieHygiene.addedByCapture(cookiesBefore, cm.getCookie("https://www.twitch.tv")).forEach { name ->
                    cm.setCookie("https://www.twitch.tv", "$name=; Max-Age=0; Domain=.twitch.tv; Path=/")
                }
                val previous = authBefore
                if (previous != null) cm.setCookie("https://www.twitch.tv", "auth-token=$previous; Domain=.twitch.tv; Path=/; Secure")
                cm.flush()
            }
        }
        if (got == null) status("failed", "no integrity token seen in ${timeoutMs / 1000} s")
        return got
    }

    private fun deviceIdCookie(): String? =
        CookieManager.getInstance().getCookie("https://www.twitch.tv")?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("unique_id=") }?.removePrefix("unique_id=")?.takeIf { it.isNotEmpty() }
}
