package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.frostline.core.ui.CardShape
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder
import tv.frostline.core.ui.TvButton

/** Test tags of the control rows. */
object ControlTags {
    const val Root = "player_root"
    const val Channel = "ctl_channel"
    const val Quality = "ctl_quality"
    const val PlayPause = "ctl_playpause"
    const val Refresh = "ctl_refresh"
    const val Chat = "ctl_chat"
}

private fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.MediaPlayPause

/**
 * The control layers drawn over the video: top (name/title, Channel, quality gear), centre (play/pause),
 * bottom (refresh, chat toggle). OK on the bare screen shows them focused on play/pause; Back is handled
 * by the caller. Every control is reachable with the D-pad and focus cannot leave the rows while shown.
 *
 * [autoHideMs] 0 disables auto-hide (tests).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerChrome(
    controlsVisible: Boolean,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    channelName: String,
    title: String,
    badge: LiveBadge,
    playing: Boolean,
    chatVisible: Boolean,
    onPlayPause: () -> Unit,
    onRefresh: () -> Unit,
    onToggleChat: () -> Unit,
    onChannel: () -> Unit,
    onQuality: () -> Unit,
    modifier: Modifier = Modifier,
    /** Something else (raid banner, quality sheet) owns focus right now. */
    focusLocked: Boolean = false,
    autoHideMs: Long = 6_000,
) {
    val root = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val channel = remember { FocusRequester() }
    val gear = remember { FocusRequester() }
    val refresh = remember { FocusRequester() }
    val chat = remember { FocusRequester() }
    var interaction by remember { mutableIntStateOf(0) }

    // Back hides the controls first; with them hidden (or something else open) it falls through to the screen.
    BackHandler(enabled = controlsVisible && !focusLocked, onBack = onHideControls)

    LaunchedEffect(controlsVisible, focusLocked) {
        if (focusLocked) return@LaunchedEffect
        runCatching { if (controlsVisible) play.requestFocus() else root.requestFocus() }
    }
    LaunchedEffect(controlsVisible, interaction, focusLocked) {
        if (controlsVisible && autoHideMs > 0 && !focusLocked) {
            delay(autoHideMs)
            onHideControls()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag(ControlTags.Root)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) interaction++
                false
            }
            // Key handling must sit outside (before) the focus target so events bubble up to it.
            .onKeyEvent {
                if (!controlsVisible && isOk(it.key) && it.type == KeyEventType.KeyUp) {
                    onShowControls()
                    true
                } else {
                    false
                }
            }
            .focusRequester(root)
            .focusable(enabled = !controlsVisible),
    ) {
        if (controlsVisible) {
            val stop = FocusRequester.Cancel
            // Scrims keep white text readable over any video.
            Box(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC050507), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(channelName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (title.isNotBlank()) Text(title, fontSize = 14.sp, color = FrostColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LiveBadgeView(badge)
                    }
                    TvButton(
                        "Channel",
                        onChannel,
                        Modifier.focusRequester(channel).testTag(ControlTags.Channel)
                            .focusProperties { up = stop; left = stop; right = gear; down = play },
                    )
                    IconControl(
                        R.drawable.ic_gear, "Quality", onQuality, 44,
                        Modifier.focusRequester(gear).testTag(ControlTags.Quality)
                            .focusProperties { up = stop; right = stop; left = channel; down = play },
                    )
                }
            }
            IconControl(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                onPlayPause, 72,
                Modifier.align(Alignment.Center).focusRequester(play).testTag(ControlTags.PlayPause)
                    .focusProperties { up = channel; down = refresh; left = stop; right = stop },
            )
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC050507))))
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconControl(
                        R.drawable.ic_refresh, "Refresh stream", onRefresh, 44,
                        Modifier.focusRequester(refresh).testTag(ControlTags.Refresh)
                            .focusProperties { up = play; down = stop; left = stop; right = chat },
                    )
                    IconControl(
                        R.drawable.ic_chat, if (chatVisible) "Hide chat" else "Show chat", onToggleChat, 44,
                        Modifier.focusRequester(chat).testTag(ControlTags.Chat)
                            .focusProperties { up = play; down = stop; left = refresh; right = stop },
                        active = chatVisible,
                    )
                    Text(if (chatVisible) "Chat on" else "Chat off", fontSize = 13.sp, color = FrostColors.Muted)
                }
            }
        }
    }
}

@Composable
fun LiveBadgeView(badge: LiveBadge, modifier: Modifier = Modifier) {
    val dot = when (badge.state) {
        LiveState.LowLatency -> FrostColors.Live
        LiveState.Behind -> FrostColors.Muted
        LiveState.Unknown -> FrostColors.Live
    }
    Row(modifier.testTag("live_badge"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(badge.text, fontSize = 13.sp, color = if (badge.state == LiveState.Behind) FrostColors.Muted else FrostColors.Live)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconControl(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    size: Int,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size.dp),
        shape = cardShape(CircleShape),
        border = focusRingBorder(CircleShape),
        scale = cardScale(1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) FrostColors.Primary else Color(0xCC1B1726),
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.Primary,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size((size * 0.5f).dp))
        }
    }
}
