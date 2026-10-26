package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import tv.frostline.core.twitch.Durations
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostSurface
import tv.frostline.core.ui.FrostTokens
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostSecondaryButton

/** Test tags of the control rows. */
object ControlTags {
    const val Root = "player_root"
    const val Channel = "ctl_channel"
    const val Quality = "ctl_quality"
    const val PlayPause = "ctl_playpause"
    const val Refresh = "ctl_refresh"
    const val Chat = "ctl_chat"
    const val SkipBack = "ctl_skip_back"
    const val SkipForward = "ctl_skip_fwd"
    const val SkipCounter = "skip_counter"
    const val SeekBar = "seek_bar"
    const val Elapsed = "seek_elapsed"
    const val Total = "seek_total"
}

/**
 * Extra controls of a past broadcast or clip: skip buttons, a seek bar with elapsed/total time and the skip
 * counter. The bar only shows progress: skipping is done with the buttons, never with Left/Right.
 */
class VodControls(
    val kindLabel: String,
    /** Where the bar points: the playhead, or the target of a skip in progress. */
    val positionMs: Long,
    val durationMs: Long,
    /** "+00:30" while a skip is pending, else null. */
    val skipCounter: String?,
    /** [deltaMs] is the step; [repeat] marks the auto-repeat events of a held button. */
    val onSkip: (deltaMs: Long, repeat: Boolean) -> Unit,
)

private fun isSelect(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

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
    /** Non-null while the chat panel is shown: Right on the bare player or on the right-most controls enters chat. */
    onEnterChat: (() -> Unit)? = null,
    /** Lets the host put focus back on the bare player (controls hidden) when it leaves chat. */
    rootRequester: FocusRequester? = null,
    /** False while an overlay owns focus, so the bare player cannot take it by a stray D-pad move. */
    rootFocusable: Boolean = true,
    /** Non-null for a past broadcast or clip. */
    vod: VodControls? = null,
    /** False hides the chat toggle (clips have no chat). */
    showChatToggle: Boolean = true,
) {
    val root = rootRequester ?: remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val channel = remember { FocusRequester() }
    val gear = remember { FocusRequester() }
    val refresh = remember { FocusRequester() }
    val chat = remember { FocusRequester() }
    val skipBack = remember { FocusRequester() }
    val skipForward = remember { FocusRequester() }
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
                } else if (!controlsVisible && onEnterChat != null && it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                    if (it.nativeKeyEvent.repeatCount == 0) onEnterChat()
                    true
                } else {
                    false
                }
            }
            .focusRequester(root)
            .focusable(enabled = !controlsVisible && rootFocusable),
    ) {
        if (controlsVisible) {
            val stop = FocusRequester.Cancel
            // Right on the right-most controls hands focus to the chat panel (when it is shown).
            val enterChat = Modifier.onPreviewKeyEvent {
                if (onEnterChat != null && it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                    // A held Right that moved focus here must not carry on into chat: only a fresh press enters.
                    if (it.nativeKeyEvent.repeatCount == 0) onEnterChat()
                    true
                } else {
                    false
                }
            }
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
                        if (vod == null) LiveBadgeView(badge)
                        else Text(vod.kindLabel, fontSize = 13.sp, color = FrostColors.Lavender, modifier = Modifier.testTag("vod_kind"))
                    }
                    FrostSecondaryButton(
                        "Channel",
                        onChannel,
                        Modifier.focusRequester(channel).testTag(ControlTags.Channel)
                            .focusProperties { up = stop; left = stop; right = gear; down = play },
                    )
                    IconControl(
                        R.drawable.ic_gear, "Quality", onQuality, 44,
                        enterChat.focusRequester(gear).testTag(ControlTags.Quality)
                            .focusProperties { up = stop; right = stop; left = channel; down = play },
                    )
                }
            }
            if (vod == null) {
                IconControl(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) "Pause" else "Play",
                    onPlayPause, 72,
                    enterChat.align(Alignment.Center).focusRequester(play).testTag(ControlTags.PlayPause)
                        .focusProperties { up = channel; down = refresh; left = stop; right = stop },
                )
            } else {
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkipButton(
                        "\u221210 s", "Back 10 seconds", { r -> vod.onSkip(SkipController.BACK_MS, r) },
                        Modifier.focusRequester(skipBack).testTag(ControlTags.SkipBack)
                            .focusProperties { up = channel; down = refresh; left = stop; right = play },
                    )
                    IconControl(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        if (playing) "Pause" else "Play",
                        onPlayPause, 72,
                        Modifier.focusRequester(play).testTag(ControlTags.PlayPause)
                            .focusProperties { up = channel; down = refresh; left = skipBack; right = skipForward },
                    )
                    SkipButton(
                        "+30 s", "Forward 30 seconds", { r -> vod.onSkip(SkipController.FORWARD_MS, r) },
                        enterChat.focusRequester(skipForward).testTag(ControlTags.SkipForward)
                            .focusProperties { up = channel; down = if (showChatToggle) chat else refresh; left = play; right = stop },
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC050507))))
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vod != null) {
                        vod.skipCounter?.let { c ->
                            Text(
                                c, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface,
                                modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ControlTags.SkipCounter),
                            )
                        }
                        SeekBar(vod.positionMs, vod.durationMs)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconControl(
                            R.drawable.ic_refresh, "Refresh stream", onRefresh, 44,
                            Modifier.focusRequester(refresh).testTag(ControlTags.Refresh)
                                .focusProperties { up = play; down = stop; left = stop; right = if (showChatToggle) chat else stop },
                        )
                        if (showChatToggle) {
                            IconControl(
                                R.drawable.ic_chat, if (chatVisible) "Hide chat" else "Show chat", onToggleChat, 44,
                                enterChat.focusRequester(chat).testTag(ControlTags.Chat)
                                    .focusProperties { up = play; down = stop; left = refresh; right = stop },
                                active = chatVisible,
                            )
                            Text(if (chatVisible) "Chat on" else "Chat off", fontSize = 13.sp, color = FrostColors.Muted)
                        }
                    }
                }
            }        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().testTag(ControlTags.SeekBar),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Durations.clock(positionMs), fontSize = 14.sp, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Elapsed))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x66FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(FrostColors.Violet))
        }
        Text(Durations.clock(durationMs), fontSize = 14.sp, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Total))
    }
}

/**
 * A text button that accumulates presses. OK on the key (including the repeats of a held key) is handled here, in
 * the preview pass, so the surface never also fires its own click for the same press; [onClick] serves pointer input.
 */
@Composable
private fun SkipButton(label: String, description: String, onPress: (repeat: Boolean) -> Unit, modifier: Modifier) {
    FrostSurface(
        onClick = { onPress(false) },
        modifier = modifier
            .height(FrostTokens.ButtonHeight)
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { e ->
                if (!isSelect(e.key)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) onPress(e.nativeKeyEvent.repeatCount > 0)
                true
            },
        shape = FrostTokens.PillShape,
        containerColor = OverlayContainer,
        focusedContainerColor = FrostColors.PrimaryFocused,
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = FrostTokens.HorizontalPadding), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Container of round controls over the video: the Container colour, a little see-through so the picture shows. */
private val OverlayContainer = Color(0xCC262033)

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

@Composable
fun IconControl(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    size: Int,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    FrostSurface(
        onClick = onClick,
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        containerColor = if (active) FrostColors.Primary else OverlayContainer,
        focusedContainerColor = FrostColors.PrimaryFocused,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size((size * 0.5f).dp))
        }
    }
}
