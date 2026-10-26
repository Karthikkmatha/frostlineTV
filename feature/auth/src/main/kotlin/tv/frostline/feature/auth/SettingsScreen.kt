package tv.frostline.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.data.ChatOptions
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.data.LiveMode
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostSecondaryButton
import tv.frostline.core.ui.TvChoiceRow
import tv.frostline.core.ui.TvStepperRow
import tv.frostline.core.ui.TvToggleRow
import tv.frostline.core.ui.brandName

@Composable
fun SettingsScreen(
    viewModel: AccountViewModel,
    versionName: String,
    onLinkWebSession: () -> Unit,
    onSignIn: () -> Unit,
    commit: String = "",
    firstFocus: FocusRequester = FocusRequester(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsContent(
        ui = ui,
        versionName = versionName,
        commit = commit,
        onClearCache = viewModel::clearCache,
        onLinkWebSession = onLinkWebSession,
        onSignIn = onSignIn,
        onSignOut = viewModel::signOut,
        onUnlink = viewModel::unlinkWebSession,
        onFollowRaids = viewModel::setFollowRaids,
        onDebugOverlay = viewModel::setDebugOverlay,
        onShowFps = viewModel::setShowFps,
        onAutoClaim = viewModel::setAutoClaimPoints,
        onLiveMode = viewModel::setLiveMode,
        onChatChange = viewModel::updateChat,
        firstFocus = firstFocus,
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
    onAutoClaim: (Boolean) -> Unit = {},
    onLiveMode: (LiveMode) -> Unit = {},
    firstFocus: FocusRequester = FocusRequester(),
    onClearCache: () -> Unit = {},
    commit: String = "",
) {
    LazyColumn(
        // Overscan-safe: 48 dp on the right, 27 dp top and bottom (as list padding, so rows scroll to the safe edge).
        Modifier.fillMaxSize().padding(start = 16.dp, end = 48.dp).testTag("settings_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Heading("Account") }
        item {
            Text(
                when {
                    ui.signedIn -> if (ui.login != null) "Signed in as ${ui.login}" else "Signed in"
                    else -> "Watching as guest"
                },
                style = MaterialTheme.typography.bodySmall,
                color = FrostColors.OnSurface,
            )
        }
        item {
            var confirming by remember { mutableStateOf(false) }
            val cancel = remember { FocusRequester() }
            LaunchedEffect(confirming) { if (confirming) runCatching { cancel.requestFocus() } }
            if (ui.signedIn && confirming) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Sign out of this account?", style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface)
                    FrostSecondaryButton("Cancel", onClick = { confirming = false }, modifier = Modifier.focusRequester(cancel).testTag("btn_sign_out_cancel"))
                    FrostButton("Yes, sign out", onClick = { confirming = false; onSignOut() }, modifier = Modifier.testTag("btn_sign_out_confirm"))
                }
            } else if (ui.signedIn) {
                FrostButton("Sign out", onClick = { confirming = true }, modifier = Modifier.focusRequester(firstFocus).testTag("btn_sign_out"))
            } else {
                FrostButton("Sign in", onClick = onSignIn, modifier = Modifier.focusRequester(firstFocus).testTag("btn_sign_in"))
            }
        }
        item {
            val status = when {
                ui.webExpired -> "Web session expired. Link it again for ad-free playback on Turbo or subscribed channels."
                ui.webLinked -> "Web session linked (used for Turbo and subscriber playback)."
                else -> "Web session not linked. Link it to get ad-free playback on Turbo or subscribed channels."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (ui.webExpired) FrostColors.Live else FrostColors.Muted, modifier = Modifier.widthIn(max = 560.dp))
        }
        item {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrostButton("Link web session", onClick = onLinkWebSession, modifier = Modifier.testTag("btn_link_web"))
                if (ui.webLinked) FrostSecondaryButton("Unlink", onClick = onUnlink)
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

        item {
            TvChoiceRow(
                label = "Live mode",
                value = ui.liveMode.label,
                description = if (ui.liveMode == LiveMode.Stable) {
                    "Stable: about 12-14 s behind live with a few seconds of video buffered ahead. Smooth, no rebuffering. Applies the next time you open a stream."
                } else {
                    "Low latency: about 4-8 s behind live (5 s target) with a small buffer and a slight speed-up to catch up. May rebuffer on a weak link. Applies the next time you open a stream."
                },
                onClick = { onLiveMode(if (ui.liveMode == LiveMode.Stable) LiveMode.LowLatency else LiveMode.Stable) },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_live_mode"),
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
            TvStepperRow(
                label = "Panel width",
                value = "${ui.chat.widthPct}%",
                description = "Press OK, then Left and Right resize the chat panel (20-45% of the screen). Also in the chat Menu > Resize panel.",
                onStep = { dir, repeat -> onChatChange { it.copy(widthPct = ChatOptions.stepWidth(it.widthPct, dir, repeat)) } },
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
                description = "Animated emotes play from pre-decoded frames at about 15 frames a second in slow chat, only the newest lines in busy chat, and as still frames in very fast chat. Off overrides everything.",
                onClick = { onChatChange { it.copy(animatedEmotes = !it.animatedEmotes) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_animated"),
            )
        }
        item {
            TvChoiceRow(
                label = "Sync chat with video",
                value = if (ui.chat.syncChat) "On" else "Off (immediate)",
                description = "Live chat is held back by about the video's delay behind live (up to 12 s), so a line shows when the picture it is about happens, and its emotes are ready before it appears. Off shows every line at once.",
                onClick = { onChatChange { it.copy(syncChat = !it.syncChat) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_sync"),
            )
        }
        item {
            TvChoiceRow(
                label = "Emote atlas",
                value = if (ui.chat.emoteAtlas) "On" else "Off (bitmap per emote)",
                description = "Debug. Packs emotes into a few shared 512 px texture pages. Off gives every emote its own bitmap.",
                onClick = { onChatChange { it.copy(emoteAtlas = !it.emoteAtlas) } },
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_chat_atlas"),
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

        if (ui.signedIn) {
            item { Spacer(Modifier.height(12.dp)) }
            item { Heading("Channel points") }
            item {
                TvToggleRow(
                    label = "Auto-claim bonus points",
                    description = "Claim the bonus chest as soon as it appears while you watch.",
                    checked = ui.autoClaimPoints,
                    onToggle = onAutoClaim,
                    modifier = Modifier.widthIn(max = 560.dp).testTag("toggle_auto_claim"),
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { Heading("Storage") }
        item {
            TvChoiceRow(
                label = "Clear image and emote cache",
                value = if (ui.cacheBytes >= 0) formatBytes(ui.cacheBytes) else "...",
                description = "Removes downloaded thumbnails, avatars and emote files. They download again when needed.",
                onClick = onClearCache,
                modifier = Modifier.widthIn(max = 560.dp).testTag("choice_clear_cache"),
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
        item { Text(brandName(), style = MaterialTheme.typography.bodyLarge, color = FrostColors.OnSurface) }
        item { Text("Version $versionName" + if (commit.isNotBlank()) " (commit $commit)" else "", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("about_version")) }
        item { Text("An independent Twitch client for Android TV. Not affiliated with Twitch.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted) }
        item { Spacer(Modifier.height(27.dp)) }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)

internal fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1048576.0)
}
