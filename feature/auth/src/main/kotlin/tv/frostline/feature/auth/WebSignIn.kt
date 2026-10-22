package tv.frostline.feature.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton

/**
 * Full-screen WebView for twitch.tv. D-pad moves between links natively; text entry comes from the
 * Google TV phone keyboard. [url] changes drive navigation (login page, then the activation page).
 * Back closes the overlay.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSignInOverlay(
    url: String,
    authorizing: Boolean,
    canSkip: Boolean,
    onClose: () -> Unit,
    onSkip: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(FrostColors.Background).padding(horizontal = 48.dp, vertical = 16.dp).testTag("web_overlay"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("Cancel", onClose)
            if (canSkip && !authorizing) TvButton("Skip login, go to authorize", onSkip)
            Text(
                when {
                    error != null -> error!!
                    authorizing -> "Press Authorize on twitch.tv to finish signing in."
                    else -> "Sign in on twitch.tv. frostlineTV never sees your password."
                },
                fontSize = 13.sp,
                color = if (error != null) FrostColors.Live else FrostColors.Muted,
                modifier = Modifier.weight(1f),
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 125
                    // Look like a normal browser: drop the "wv" and "Version/4.0" WebView markers.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { error = null }
                        override fun onPageFinished(view: WebView, u: String?) { view.requestFocus() }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                            if (request.isForMainFrame) error = "Page failed to load. Use the QR code on the previous screen if this keeps happening."
                        }
                    }
                }
            },
            update = { wv ->
                if (wv.tag != url) {
                    wv.tag = url
                    wv.loadUrl(url)
                }
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.clearCache(true)
                wv.clearHistory()
                wv.clearFormData()
                wv.destroy()
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
