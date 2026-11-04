package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import tv.frostline.core.ui.FrostIconButton
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
    const val Timeline = "timeline"
    const val TimelineThumb = "timeline_thumb"
    const val TimelineHint = "timeline_hint"
    const val StepText = "scrub_step"
    const val PreviewTime = "scrub_time"
    const val PreviewCard = "scrub_thumb_card"
    const val Elapsed = "seek_elapsed"
    const val Total = "seek_total"
}

/**
 * Extra controls of a past broadcast or clip: skip buttons, a seek bar with elapsed/total time and the skip
 * counter. For a past broadcast ([seekable]) the bar is a focusable timeline: Left/Right move a preview ([previewMs]),
 * OK seeks to it, Back or leaving drops it. A clip keeps a plain progress bar. On the bare player and on the buttons,
 * Left and Right never skip.
 */
class VodControls(
    val kindLabel: String,
    /** Where the bar points: the playhead, or the target of a skip in progress. */
    val positionMs: Long,
    val durationMs: Long,
    /** "+00:30" while a skip is pending, else null. */
    val skipCounter: String?,
    /** True for a past broadcast: the bar can take focus and scrub. False for a clip. */
    val seekable: Boolean = false,
    /** The preview position while scrubbing (the bar and the elapsed label show it), else null. */
    val previewMs: Long? = null,
    /** "\u25B6\u25B6 10 min": direction and step while scrubbing, else null. */
    val stepText: String? = null,
    /** The storyboard frame nearest the preview, shown in a card above the thumb; null = no card. */
    val thumb: Thumb? = null,
    /** Left (-1) / Right (+1) KeyDown on the timeline; [repeatCount] is the event's own (may be 0 on a held key). */
    val onScrub: (dir: Int, repeatCount: Int) -> Unit = { _, _ -> },
    /** KeyUp of Left/Right on the timeline: the hold is over. */
    val onScrubRelease: () -> Unit = {},
    /** OK on the timeline: seek to the preview. */
    val onScrubCommit: () -> Unit = {},
    /** Back, or focus leaving the timeline: drop the preview. */
    val onScrubCancel: () -> Unit = {},
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
    val timeline = remember { FocusRequester() }
    var interaction by remember { mutableIntStateOf(0) }
    var timelineFocused by remember { mutableStateOf(false) }
    val timelineOn = vod != null && vod.seekable

    // Back drops a timeline preview first, then hides the controls; with them hidden (or something else open) it falls through to the screen.
    BackHandler(enabled = controlsVisible && !focusLocked) {
        if (vod?.previewMs != null) vod.onScrubCancel() else onHideControls()
    }

    LaunchedEffect(controlsVisible, focusLocked) {
        if (focusLocked) return@LaunchedEffect
        runCatching { if (controlsVisible) play.requestFocus() else root.requestFocus() }
    }
    // Controls going away takes the timeline's focus with them: nothing is left previewing.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            timelineFocused = false
            vod?.onScrubCancel()
        }
    }
    // The controls stay while the timeline has focus (the preview is being chosen); the countdown starts again on blur.
    LaunchedEffect(controlsVisible, interaction, focusLocked, timelineFocused) {
        if (controlsVisible && autoHideMs > 0 && !focusLocked && !timelineFocused) {
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
                    .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(channelName, style = MaterialTheme.typography.headlineSmall, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vod == null) LiveBadgeView(badge)
                        else Text(vod.kindLabel, style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender, modifier = Modifier.testTag("vod_kind"))
                    }
                    FrostSecondaryButton(
                        "Channel",
                        onChannel,
                        Modifier.focusRequester(channel).testTag(ControlTags.Channel)
                            .focusProperties { up = stop; left = stop; right = gear; down = if (timelineOn) timeline else play },
                    )
                    IconControl(
                        R.drawable.ic_gear, "Quality", onQuality, 48,
                        enterChat.focusRequester(gear).testTag(ControlTags.Quality)
                            .focusProperties { up = stop; right = stop; left = channel; down = if (timelineOn) timeline else play },
                    )
                }
            }
            if (vod == null) {
                IconControl(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) "Pause" else "Play",
                    onPlayPause, 48,
                    enterChat.align(Alignment.Center).focusRequester(play).testTag(ControlTags.PlayPause)
                        .focusProperties { up = channel; down = refresh; left = stop; right = stop },
                )
            } else if (!timelineOn) {
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
                        onPlayPause, 48,
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
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                    .padding(horizontal = 48.dp, vertical = 27.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vod != null) {
                        vod.skipCounter?.let { c ->
                            Text(
                                c, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface,
                                modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ControlTags.SkipCounter),
                            )
                        }
                        if (vod.seekable) {
                            Timeline(
                                vod, timelineFocused,
                                onFocused = { f ->
                                    timelineFocused = f
                                    if (!f) vod.onScrubCancel()
                                },
                                modifier = Modifier.focusRequester(timeline)
                                    .focusProperties { up = channel; left = stop; right = stop; down = play },
                            )
                        } else {
                            SeekBar(vod.positionMs, vod.durationMs)
                        }
                    }
                    if (timelineOn && vod != null) {
                        // A past broadcast keeps every button in ONE row under the timeline: Up from any of them reaches the timeline,
                        // Down from the timeline returns to play/pause, Up from the timeline reaches Channel and the gear.
                        val rightmost = if (showChatToggle) chat else refresh
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkipButton(
                                "\u221210 s", "Back 10 seconds", { r -> vod.onSkip(SkipController.BACK_MS, r) },
                                Modifier.focusRequester(skipBack).testTag(ControlTags.SkipBack)
                                    .focusProperties { up = timeline; down = stop; left = stop; right = play },
                            )
                            IconControl(
                                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                                if (playing) "Pause" else "Play",
                                onPlayPause, 48,
                                Modifier.focusRequester(play).testTag(ControlTags.PlayPause)
                                    .focusProperties { up = timeline; down = stop; left = skipBack; right = skipForward },
                            )
                            SkipButton(
                                "+30 s", "Forward 30 seconds", { r -> vod.onSkip(SkipController.FORWARD_MS, r) },
                                Modifier.focusRequester(skipForward).testTag(ControlTags.SkipForward)
                                    .focusProperties { up = timeline; down = stop; left = play; right = refresh },
                            )
                            Spacer(Modifier.width(24.dp))
                            IconControl(
                                R.drawable.ic_refresh, "Refresh stream", onRefresh, 48,
                                (if (showChatToggle) Modifier else enterChat).focusRequester(refresh).testTag(ControlTags.Refresh)
                                    .focusProperties { up = timeline; down = stop; left = skipForward; right = if (showChatToggle) chat else stop },
                            )
                            if (showChatToggle) {
                                IconControl(
                                    R.drawable.ic_chat, if (chatVisible) "Hide chat" else "Show chat", onToggleChat, 48,
                                    enterChat.focusRequester(chat).testTag(ControlTags.Chat)
                                        .focusProperties { up = timeline; down = stop; left = refresh; right = stop },
                                    active = chatVisible,
                                )
                                Text(if (chatVisible) "Chat on" else "Chat off", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
                            }
                        }
                    } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconControl(
                                R.drawable.ic_refresh, "Refresh stream", onRefresh, 48,
                                Modifier.focusRequester(refresh).testTag(ControlTags.Refresh)
                                    .focusProperties { up = if (timelineOn) timeline else play; down = stop; left = stop; right = if (showChatToggle) chat else stop },
                            )
                            if (showChatToggle) {
                                IconControl(
                                    R.drawable.ic_chat, if (chatVisible) "Hide chat" else "Show chat", onToggleChat, 48,
                                    enterChat.focusRequester(chat).testTag(ControlTags.Chat)
                                        .focusProperties { up = if (timelineOn) timeline else play; down = stop; left = refresh; right = stop },
                                    active = chatVisible,
                                )
                                Text(if (chatVisible) "Chat on" else "Chat off", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
                            }
                        }
                    }
                }
            }        }
    }
}

/**
 * The focusable timeline of a past broadcast. Focused: the round-1 ring and scale, a thicker track and a thumb at the
 * previewed (or current) position, and a hint underneath. Left/Right preview, OK seeks, Back (handled by the chrome) cancels.
 * Everything it reads is already state of the controls, so it adds no per-frame work.
 */
@Composable
private fun Timeline(vod: VodControls, focused: Boolean, onFocused: (Boolean) -> Unit, modifier: Modifier) {
    val shown = vod.previewMs ?: vod.positionMs
    val fraction = if (vod.durationMs > 0) (shown.toFloat() / vod.durationMs).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .testTag(ControlTags.Timeline)
            .graphicsLayer { val sc = if (focused) FrostTokens.RowFocusScale else 1f; scaleX = sc; scaleY = sc }
            .then(if (focused) Modifier.border(FrostTokens.FocusBorderWidth, FrostColors.Violet, shape) else Modifier)
            .onKeyEvent { e ->
                when {
                    e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                        // Every KeyDown counts (a remote may never set repeatCount); the KeyUp ends the hold.
                        when (e.type) {
                            KeyEventType.KeyDown -> vod.onScrub(if (e.key == Key.DirectionLeft) -1 else 1, e.nativeKeyEvent.repeatCount)
                            KeyEventType.KeyUp -> vod.onScrubRelease()
                            else -> Unit
                        }
                        true
                    }
                    isSelect(e.key) -> {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) vod.onScrubCommit()
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { onFocused(it.isFocused) }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (focused) {
            // One row above the bar on a dark pill: the time the preview points at, then the speed. Plain text (no animation).
            // The slot has a fixed height, so the controls do not shift when a preview starts.
            Box(Modifier.fillMaxWidth().height(PillHeight), contentAlignment = Alignment.Center) {
                if (vod.previewMs != null && vod.stepText != null) {
                    Row(
                        Modifier.height(PillHeight).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(PillHeight / 2)).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(Durations.clock(vod.previewMs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface, maxLines = 1, modifier = Modifier.testTag(ControlTags.PreviewTime))
                        Text(vod.stepText, style = MaterialTheme.typography.bodyLarge, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag(ControlTags.StepText))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().testTag(ControlTags.SeekBar),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Durations.clock(shown), style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Elapsed))
            val trackHeight = if (focused) 10.dp else 6.dp
            Box(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(trackHeight / 2)).background(FrostColors.Track)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(FrostColors.Violet))
                }
                if (focused) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.offset(x = (maxWidth - 18.dp) * fraction).size(18.dp)
                                .background(FrostColors.Lavender, CircleShape).testTag(ControlTags.TimelineThumb),
                        )
                        // The storyboard frame nearest the preview, above the thumb. Its parameter changes only when the frame index does.
                        val t = vod.thumb
                        if (t != null && vod.previewMs != null) {
                            val x = (maxWidth * fraction - PreviewCardWidth / 2).coerceIn(0.dp, (maxWidth - PreviewCardWidth).coerceAtLeast(0.dp))
                            PreviewCard(t, Modifier.offset(x = x, y = -(PreviewCardHeight + PillHeight + 14.dp)))
                        }
                    }
                }
            }
            Text(Durations.clock(vod.durationMs), style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Total))
        }
        if (focused) {
            Text(
                "\u25C0 \u25B6 choose time \u00B7 hold to move faster \u00B7 OK seek \u00B7 Back cancel",
                style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ControlTags.TimelineHint),
            )
        }
    }
}

private val PillHeight = 44.dp
private val PreviewCardWidth = 220.dp
private val PreviewCardHeight = 124.dp

/** The seek-preview frame: 220 x 124 dp. [thumb] is stable, so this only recomposes when the frame index changes. */
@Composable
private fun PreviewCard(thumb: Thumb, modifier: Modifier) {
    val image = remember(thumb.index, thumb.bitmap) { thumb.bitmap.asImageBitmap() }
    Image(
        bitmap = image, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.size(PreviewCardWidth, PreviewCardHeight)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, FrostColors.Violet, RoundedCornerShape(10.dp))
            .testTag(ControlTags.PreviewCard),
    )
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().testTag(ControlTags.SeekBar),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Durations.clock(positionMs), style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Elapsed))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(FrostColors.Track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(FrostColors.Violet))
        }
        Text(Durations.clock(durationMs), style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag(ControlTags.Total))
    }
}

/**
 * A text button that accumulates presses. OK on the key (including the repeats of a held key) is handled here, in
 * the preview pass, so the surface never also fires its own click for the same press; [onClick] serves pointer input.
 */
@Composable
private fun SkipButton(label: String, description: String, onPress: (repeat: Boolean) -> Unit, modifier: Modifier) {
    FrostSecondaryButton(
        label,
        onClick = { onPress(false) },
        modifier = modifier
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { e ->
                if (!isSelect(e.key)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) onPress(e.nativeKeyEvent.repeatCount > 0)
                true
            },
    )
}

/** Black at 70 % at the screen edge, fading to nothing: the top and bottom scrims. */
private val Scrim = FrostColors.Scrim

/** Container of round controls over the video: the Container colour, a little see-through so the picture shows. */
private val OverlayContainer = FrostColors.OverlayContainer

@Composable
fun LiveBadgeView(badge: LiveBadge, modifier: Modifier = Modifier) {
    val dot = when (badge.state) {
        LiveState.LowLatency -> FrostColors.Live
        LiveState.Behind -> FrostColors.Muted
        LiveState.Unknown -> FrostColors.Live
    }
    Row(modifier.testTag("live_badge"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(badge.text, style = MaterialTheme.typography.bodySmall, color = if (badge.state == LiveState.Behind) FrostColors.Muted else FrostColors.Live)
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
    FrostIconButton(
        onClick = onClick,
        modifier = modifier,
        size = size.dp,
        active = active,
        containerColor = if (active) FrostColors.Primary else OverlayContainer,
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size((size * 0.5f).dp))
    }
}
