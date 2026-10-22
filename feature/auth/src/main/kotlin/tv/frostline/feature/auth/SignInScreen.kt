package tv.frostline.feature.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.brandName

@Composable
fun SignInScreen(viewModel: AuthViewModel, onSignedIn: () -> Unit, onGuest: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.step) { if (ui.step == Step.Done) onSignedIn() }
    val webUrl = ui.webUrl
    if ((ui.step == Step.WebLogin || ui.step == Step.WebAuthorize) && webUrl != null) {
        // The landing content is not composed while the WebView is up, so D-pad focus cannot leak behind it.
        WebSignInOverlay(
            url = webUrl,
            authorizing = ui.step == Step.WebAuthorize,
            canSkip = true,
            onClose = viewModel::closeWeb,
            onSkip = viewModel::skipLogin,
        )
    } else {
        SignInLanding(
            ui = ui,
            onWebSignIn = viewModel::webSignIn,
            onGuest = { viewModel.skipAsGuest(onGuest) },
            onNewCode = viewModel::newCode,
        )
    }
}

/** Settings > Account > Link web session: the WebView login only. */
@Composable
fun LinkWebSessionScreen(viewModel: AuthViewModel, onDone: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.webSignIn() }
    LaunchedEffect(ui.step) {
        if (ui.step == Step.Done) onDone()
        if (ui.step == Step.Landing) onDone() // Back / Cancel
    }
    val url = ui.webUrl
    if (url != null && ui.step == Step.WebLogin) {
        WebSignInOverlay(url = url, authorizing = false, canSkip = false, onClose = viewModel::closeWeb, onSkip = {})
    }
}

@Composable
fun SignInLanding(ui: SignInUi, onWebSignIn: () -> Unit, onGuest: () -> Unit, onNewCode: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(brandName(), fontSize = 40.sp, color = FrostColors.OnSurface)
            Text("A native Twitch client for your TV.", fontSize = 15.sp, color = FrostColors.Muted)
            Spacer(Modifier.height(16.dp))
            TvButton("Sign in with Twitch", onWebSignIn, Modifier.focusRequester(first).testTag("btn_web_signin"))
            TvButton("Skip — watch as guest", onGuest, Modifier.testTag("btn_guest"))
            Text(
                "Signing in unlocks your follows and chat. As a guest you can browse Top, Categories and Search and watch streams.",
                fontSize = 12.sp,
                color = FrostColors.Muted,
            )
            ui.message?.let { Text(it, fontSize = 13.sp, color = FrostColors.Live) }
        }
        CodePanel(ui, onNewCode, Modifier.width(300.dp))
    }
}

@Composable
private fun CodePanel(ui: SignInUi, onNewCode: () -> Unit, modifier: Modifier) {
    Column(
        modifier.background(FrostColors.Surface, RoundedCornerShape(14.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Or sign in on your phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        val code = ui.code
        if (code != null && ui.codeStatus == CodeStatus.Ready) {
            QrCode(code.verificationUri, Modifier.size(180.dp).testTag("qr"))
            Text("Scan, or go to twitch.tv/activate and enter", fontSize = 12.sp, color = FrostColors.Muted)
            Text(
                code.userCode,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                color = FrostColors.Lavender,
                modifier = Modifier.testTag("device_code"),
            )
            val remaining by produceState(initialValue = remainingSeconds(code.expiresAtMs), key1 = code) {
                while (true) {
                    value = remainingSeconds(code.expiresAtMs)
                    delay(500)
                }
            }
            Text("Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", fontSize = 12.sp, color = FrostColors.Muted)
        } else {
            Text(
                when (val s = ui.codeStatus) {
                    CodeStatus.Loading, CodeStatus.Ready -> "Getting a code..."
                    CodeStatus.Expired -> "That code expired."
                    CodeStatus.Denied -> "Sign-in was declined on Twitch."
                    is CodeStatus.Error -> "Something went wrong: ${s.message}"
                },
                fontSize = 13.sp,
                color = FrostColors.Muted,
                modifier = Modifier.testTag("code_status"),
            )
        }
        TvButton("Get a new code", onNewCode, Modifier.testTag("btn_new_code"))
    }
}

private fun remainingSeconds(expiresAtMs: Long): Long = ((expiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.background(Color(0xFFEEEBF5), RoundedCornerShape(8.dp)).padding(10.dp)) {
        val n = modules.size
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (modules[y][x]) {
                drawRect(Color(0xFF050507), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
