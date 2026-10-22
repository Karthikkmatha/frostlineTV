package tv.frostline.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton

@Composable
fun SignInScreen(viewModel: AuthViewModel, onSignedIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state is SignInState.Done) onSignedIn() }
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { button.requestFocus() } }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sign in to Twitch", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        Spacer(Modifier.height(12.dp))
        Text("On your phone or computer, open", fontSize = 14.sp, color = FrostColors.Muted)
        Text("twitch.tv/activate", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = FrostColors.Ice)
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is SignInState.ShowCode -> {
                Text(
                    s.code.userCode,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp,
                    color = FrostColors.OnSurface,
                )
                val remaining by produceState(initialValue = remainingSeconds(s), key1 = s) {
                    while (true) {
                        value = remainingSeconds(s)
                        delay(500)
                    }
                }
                Text(
                    "Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                    fontSize = 14.sp,
                    color = FrostColors.Muted,
                )
            }
            SignInState.Loading -> Text("Getting a code...", fontSize = 18.sp, color = FrostColors.Muted)
            SignInState.Expired -> Text("That code expired.", fontSize = 18.sp, color = FrostColors.Muted)
            SignInState.Denied -> Text("Sign-in was declined on Twitch.", fontSize = 18.sp, color = FrostColors.Muted)
            is SignInState.Error -> Text("Something went wrong: ${s.message}", fontSize = 16.sp, color = FrostColors.Muted)
            SignInState.Done -> Text("Signed in", fontSize = 18.sp, color = FrostColors.Muted)
        }
        Spacer(Modifier.height(24.dp))
        TvButton("Get a new code", onClick = viewModel::start, modifier = Modifier.focusRequester(button))
    }
}

private fun remainingSeconds(s: SignInState.ShowCode): Long =
    ((s.code.expiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
