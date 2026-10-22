package tv.frostline.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.data.ChatOptions
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.TvChoiceRow
import tv.frostline.core.ui.TvToggleRow
import tv.frostline.core.ui.brandName

@Composable
fun SettingsScreen(
    viewModel: AccountViewModel,
    versionName: String,
    onLinkWebSession: () -> Unit,
    onSignIn: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsContent(
        ui = ui,
        versionName = versionName,
        onLinkWebSession = onLinkWebSession,
        onSignIn = onSignIn,
        onSignOut = viewModel::signOut,
        onUnlink = viewModel::unlinkWebSession,
        onFollowRaids = viewModel::setFollowRaids,
        onDebugOverlay = viewModel::setDebugOverlay,
        onShowFps = viewModel::setShowFps,
        onChatChange = viewModel::updateChat,
    )
}

@Composable
fun SettingsContent(
    ui: AccountUi,
    versionName: String,
    onLinkWebSession: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onUnlink: () -> Unit,
    onFollowRaids: (Boolean) -> Unit,
    onDebugOverlay: (Boolean) -> Unit,
    onChatChange: ((ChatSettings) -> ChatSettings) -> Unit = {},
    onShowFps: (Boolean) -> Unit = {},
) {
    LazyColumn(
        // Overscan-safe: 48 dp on the right, 27 dp top and bottom.
        Modifier.fillMaxSize().padding(start = 16.dp, end = 48.dp, top = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Heading("Account") }
        item {
            Text(
                when {
                    ui.signedIn -> if (ui.login != null) "Signed in as ${ui.login}" else "Signed in"
                    else -> "Watching as guest"
                },
                fontSize = 14.sp,
                color = FrostColors.OnSurface,
            )
        }
        item {
            if (ui.signedIn) TvButton("Sign out", onClick = onSignOut, modifier = Modifier.testTag("btn_sign_out"))
            else TvButton("Sign in", onClick = onSignIn, modifier = Modifier.testTag("btn_sign_in"))
        }
        item {
            val status = when {
                ui.webExpired -> "Web session expired. Link it again for ad-free playback on Turbo or subscribed channels."
                ui.webLinked -> "Web session linked (used for Turbo and subscriber playback)."
                else -> "Web session not linked. Link it to get ad-free playback on Turbo or subscribed channels."
            }
            Text(status, fontSize = 13.sp, color = if (ui.webExpired) FrostColors.Live else FrostColors.Muted, modifier = Modifier.widthIn(max = 560.dp))
        }
        item {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Link web session", onClick = onLinkWebSession, modifier = Modifier.testTag("btn_link_web"))
                if (ui.webLinked) TvButton("Unlink", onClick = onUnlink)
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("Playback") }
        item {
            TvToggleRow(
                label = "Follow raids",
                description = "Switch to the raided channel after a 10 second notice (you can cancel).",
                checked = ui.followRaids,
                onToggle = onFollowRaids,
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_raids"),
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("Chat") }
        item {
            TvToggleRow(
                label = "Show chat",
                description = "Open the player with the chat panel beside the video.",
                checked = ui.chat.enabled,
                onToggle = { v -> onChatChange { it.copy(enabled = v) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_chat"),
            )
        }
        item {
            TvChoiceRow(
                label = "Panel width",
                value = ChatOptions.widthNames[ui.chat.widthIndex.coerceIn(0, 3)],
                onClick = { onChatChange { it.copy(widthIndex = ChatOptions.nextIndex(it.widthIndex, ChatOptions.widthFractions.size)) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_width"),
            )
        }
        item {
            TvChoiceRow(
                label = "Font size",
                value = ChatOptions.fontNames[ui.chat.fontIndex.coerceIn(0, 3)],
                onClick = { onChatChange { it.copy(fontIndex = ChatOptions.nextIndex(it.fontIndex, ChatOptions.fontSizesSp.size)) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_font"),
            )
        }
        item {
            TvToggleRow(
                label = "Timestamps",
                description = null,
                checked = ui.chat.timestamps,
                onToggle = { v -> onChatChange { it.copy(timestamps = v) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_chat_time"),
            )
        }
        item {
            TvChoiceRow(
                label = "Animated emotes",
                value = if (ui.chat.animatedEmotes) "On" else "Off (still frame)",
                description = "Animated emotes play at about 12 frames a second, and only while chat is shown and the video is playing.",
                onClick = { onChatChange { it.copy(animatedEmotes = !it.animatedEmotes) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_animated"),
            )
        }
        item {
            TvToggleRow(
                label = "7TV emotes",
                description = null,
                checked = ui.chat.show7tv,
                onToggle = { v -> onChatChange { it.copy(show7tv = v) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_chat_7tv"),
            )
        }
        item {
            TvToggleRow(
                label = "BTTV emotes",
                description = null,
                checked = ui.chat.showBttv,
                onToggle = { v -> onChatChange { it.copy(showBttv = v) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_chat_bttv"),
            )
        }
        item {
            TvToggleRow(
                label = "FFZ emotes",
                description = null,
                checked = ui.chat.showFfz,
                onToggle = { v -> onChatChange { it.copy(showFfz = v) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_chat_ffz"),
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("Developer") }
        item {
            TvToggleRow(
                label = "Debug overlay",
                description = "Show startup time, dropped frames, rebuffers and bitrate over the player.",
                checked = ui.debugOverlay,
                onToggle = onDebugOverlay,
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_debug"),
            )
        }
        item {
            TvToggleRow(
                label = "Show UI frame rate",
                description = "Adds the measured UI frame rate to the debug overlay, to check that an idle screen stays idle.",
                checked = ui.showFps,
                onToggle = onShowFps,
                modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_fps"),
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("About") }
        item { Text(brandName(), fontSize = 16.sp, color = FrostColors.OnSurface) }
        item { Text("Version $versionName", fontSize = 13.sp, color = FrostColors.Muted) }
        item { Text("An independent Twitch client for Android TV. Not affiliated with Twitch.", fontSize = 13.sp, color = FrostColors.Muted) }
        item { Spacer(Modifier.height(27.dp)) }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
