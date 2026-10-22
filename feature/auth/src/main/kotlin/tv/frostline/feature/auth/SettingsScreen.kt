package tv.frostline.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.TvTextField

@Composable
fun SettingsScreen(viewModel: AccountViewModel, versionName: String) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var cookie by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Heading("Account") }
        item {
            Text(
                if (ui.login != null) "Signed in as ${ui.login}" else "Signed in",
                fontSize = 14.sp,
                color = FrostColors.OnSurface,
            )
        }
        item {
            // The app watches the vault and navigates to sign-in once the tokens are gone.
            TvButton("Sign out", onClick = viewModel::signOut)
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("Web session") }
        item {
            val status = when {
                ui.webExpired -> "Expired. Paste a fresh auth-token to link again."
                ui.webLinked -> "Linked."
                else -> "Not linked. Paste the auth-token cookie from your own twitch.tv session."
            }
            Text(status, fontSize = 14.sp, color = if (ui.webExpired) FrostColors.Live else FrostColors.Muted)
        }
        item {
            TvTextField(
                value = cookie,
                onValueChange = { cookie = it },
                placeholder = "auth-token cookie value",
                secret = true,
                modifier = Modifier.widthIn(max = 560.dp),
                onImeAction = { if (cookie.isNotBlank()) { viewModel.link(cookie); cookie = "" } },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    if (busy) "Checking..." else "Link",
                    onClick = { if (cookie.isNotBlank()) { viewModel.link(cookie); cookie = "" } },
                    enabled = !busy,
                )
                if (ui.webLinked) TvButton("Unlink", onClick = viewModel::unlink)
            }
        }
        message?.let { item { Text(it, fontSize = 13.sp, color = FrostColors.Ice) } }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("About") }
        item { Text("FrostlineTV $versionName", fontSize = 14.sp, color = FrostColors.OnSurface) }
        item { Text("An independent Twitch client for Android TV. Not affiliated with Twitch.", fontSize = 13.sp, color = FrostColors.Muted) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
