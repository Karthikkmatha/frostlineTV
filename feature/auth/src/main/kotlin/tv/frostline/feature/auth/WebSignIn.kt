package tv.frostline.feature.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the sign-in WebView window needs from the running flow. Held in a process-wide slot because the window is a
 * separate Activity; it reads [ui] directly, so it keeps following the flow while the Compose screen is stopped behind it.
 */
class WebSignInSession(
    val ui: StateFlow<SignInUi>,
    val canSkip: Boolean,
    val onClose: () -> Unit,
    val onSkip: () -> Unit,
)

object WebSignInBridge {
    @Volatile var session: WebSignInSession? = null
    @Volatile var open: Boolean = false
}

/** True while the flow wants the twitch.tv WebView on screen. */
fun SignInUi.webVisible(): Boolean = (step == Step.WebLogin || step == Step.WebAuthorize) && webUrl != null

/**
 * Shows the twitch.tv WebView in [WebSignInActivity] while [ui] says so. Composes nothing itself: a WebView inside a
 * Compose layer (or a nav transition) rendered as a white page on the TV, so it lives in its own window.
 */
@Composable
fun WebSignInHost(ui: StateFlow<SignInUi>, visible: Boolean, canSkip: Boolean, onClose: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val close by rememberUpdatedState(onClose)
    val skip by rememberUpdatedState(onSkip)
    LaunchedEffect(visible) {
        if (visible) {
            WebSignInBridge.session = WebSignInSession(ui, canSkip, { close() }, { skip() })
            if (!WebSignInBridge.open) context.startActivity(Intent(context, WebSignInActivity::class.java))
        } else {
            WebSignInBridge.session = null
        }
    }
    DisposableEffect(Unit) { onDispose { WebSignInBridge.session = null } }
}

private const val CHROME_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/**
 * Plain Activity hosting a full-screen WebView for twitch.tv (login page, then the activation page).
 * D-pad: the WebView holds focus; Up from the top of the page reaches the button bar. Back cancels.
 * The debug line shows only HTTP status, exception/error class names and hostnames.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebSignInActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var debugLine: TextView
    private lateinit var status: TextView
    private lateinit var skipButton: Button
    private var shownUrl: String? = null
    private var uaMode = 0 // 0 = WebView default, 1 = desktop Chrome
    private var consoleErrors = 0
    private var lastDebug = "no events yet"

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun styled(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        isFocusable = true
        val normal = GradientDrawable().apply { setColor(0xFF4E2E99.toInt()); cornerRadius = dp(8).toFloat() }
        val focused = GradientDrawable().apply {
            setColor(0xFF262033.toInt()); cornerRadius = dp(8).toFloat(); setStroke(dp(3), 0xFF8F74E8.toInt())
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebSignInBridge.open = true
        val session = WebSignInBridge.session
        if (session == null) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050507.toInt())
            setPadding(dp(32), dp(10), dp(32), dp(10))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cancel = styled("Cancel") { cancelFlow() }
        skipButton = styled("Skip login, go to authorize") { WebSignInBridge.session?.onSkip?.invoke() }
        val uaButton = styled("Browser mode: default") {}.also { b ->
            b.setOnClickListener {
                uaMode = 1 - uaMode
                b.text = if (uaMode == 0) "Browser mode: default" else "Browser mode: Chrome"
                web.settings.userAgentString = if (uaMode == 0) defaultUa else CHROME_UA
                shownUrl?.let { web.loadUrl(it) }
            }
        }
        status = TextView(this).apply {
            setTextColor(0xFFA49FB8.toInt()); textSize = 13f
            setPadding(dp(12), 0, 0, 0)
        }
        bar.addView(cancel); bar.addView(skipButton, spaced()); bar.addView(uaButton, spaced())
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        debugLine = TextView(this).apply { setTextColor(0xFF8FD3FF.toInt()); textSize = 11f; text = "debug: $lastDebug" }

        web = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 125
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    // Count only; page console text can carry tokens, so it is never logged or shown.
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) { consoleErrors++; setDebug(lastDebug) }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                    consoleErrors = 0
                    setDebug("loading ${host(u)}")
                }
                override fun onPageFinished(view: WebView, u: String?) {
                    setDebug("loaded ${host(u)}")
                    view.requestFocus()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                    setDebug("error ${host(request.url.toString())} code ${e.errorCode}")
                    if (request.isForMainFrame) status.text = "Page failed to load. Try Browser mode, or use the QR code on the previous screen."
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
                    setDebug("HTTP ${r.statusCode} ${host(request.url.toString())}")
                }
            }
        }
        defaultUa = web.settings.userAgentString
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(debugLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelFlow()
        })

        lifecycleScope.launch {
            session.ui.collect { ui ->
                if (!ui.webVisible()) { finish(); return@collect }
                val authorizing = ui.step == Step.WebAuthorize
                skipButton.visibility = if (session.canSkip && !authorizing) View.VISIBLE else View.GONE
                status.text = if (authorizing) "Press Authorize on twitch.tv to finish signing in."
                else "Sign in on twitch.tv. frostlineTV never sees your password."
                val url = ui.webUrl!!
                if (url != shownUrl) { shownUrl = url; web.loadUrl(url) }
            }
        }
        web.requestFocus()
    }

    private fun spaced() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { marginStart = dp(10) }

    private fun cancelFlow() {
        WebSignInBridge.session?.onClose?.invoke()
        finish()
    }

    private fun setDebug(s: String) {
        lastDebug = s
        debugLine.text = "debug: $s, console errors $consoleErrors, UA ${if (uaMode == 0) "default" else "Chrome"}"
    }

    private fun host(u: String?): String = runCatching { android.net.Uri.parse(u).host }.getOrNull() ?: "?"

    override fun onDestroy() {
        WebSignInBridge.open = false
        if (::web.isInitialized) {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private var defaultUa: String = ""
}
