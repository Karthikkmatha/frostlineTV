package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import tv.frostline.core.twitch.Durations
import tv.frostline.core.ui.FrostColors
import androidx.compose.foundation.layout.size
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostSecondaryButton
import tv.frostline.core.ui.rememberUiFps
import tv.frostline.core.ui.tryFocus
import tv.frostline.feature.chat.ChatActions
import tv.frostline.feature.chat.ChatLayout
import tv.frostline.feature.chat.ChatReplayViewModel
import tv.frostline.feature.chat.ChatUiModel
import tv.frostline.feature.chat.ConnState
import tv.frostline.feature.chat.PickerData
import tv.frostline.feature.chat.rememberChatController

/**
 * Past broadcast or clip. The same chrome, quality sheet and chat panel as the live player; the video is a
 * SurfaceView layer. [chat] is the read-only replay (null for clips). Skipping is by the buttons only:
 * Left and Right on the bare player do nothing here (Right enters the chat panel when it is shown).
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    viewModel: VodPlayerViewModel,
    chat: ChatReplayViewModel?,
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val aspect by viewModel.videoAspect.collectAsStateWithLifecycle()
    val skip by viewModel.skip.state.collectAsStateWithLifecycle()
    val debug by viewModel.debugOverlay.collectAsStateWithLifecycle(initialValue = false)
    val showFps by viewModel.showFps.collectAsStateWithLifecycle(initialValue = false)
    tv.frostline.core.ui.DropImageMemoryWhileShown()
    val fps = rememberUiFps(debug && showFps)
    val jank = tv.frostline.core.ui.rememberJankLine(debug)
    val metrics by viewModel.metrics.state.collectAsStateWithLifecycle()
    val channelId by viewModel.channelId.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(false) }
    // The shown position is only read by the seek bar (controls): refresh it at 1 Hz only while they or the debug overlay are shown.
    LaunchedEffect(controlsVisible, debug) { viewModel.setPositionObserved(controlsVisible || debug) }
    var qualityOpen by remember { mutableStateOf(false) }

    // ---- chat replay ----
    val chatSettings = chat?.settings?.collectAsStateWithLifecycle()?.value
    val chatLines = chat?.lines?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val chatStatus = chat?.status?.collectAsStateWithLifecycle()?.value ?: ""
    val chatDebug = chat?.debug?.collectAsStateWithLifecycle()?.value.orEmpty()
    val chatVisible = chat != null && chatSettings?.enabled == true
    val chatController = rememberChatController()
    val playerRoot = remember { FocusRequester() }
    var returnTick by remember { mutableIntStateOf(0) }
    val returnToPlayer = { controlsVisible = false; returnTick++ }
    LaunchedEffect(returnTick) {
        if (returnTick > 0) {
            withFrameNanos { }
            runCatching { playerRoot.requestFocus() }
        }
    }
    if (chat != null) {
        LifecycleEventEffect(Lifecycle.Event.ON_START) { chat.start() }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chat.stop() }
        DisposableEffect(chat) {
            chat.start()
            onDispose { chat.stop() }
        }
        LaunchedEffect(ui.channelLogin, channelId) {
            if (ui.channelLogin.isNotEmpty() || channelId != null) chat.setChannel(ui.channelLogin, channelId)
        }
    }
    val chatPlaying = ui.playing
    val chatModel = remember(chatLines, chatStatus, chatSettings, chatPlaying) {
        ChatUiModel(
            chatLines, ConnState.Connected, chatStatus, canSend = false, signedIn = false,
            settings = chatSettings ?: tv.frostline.core.data.ChatSettings(), picker = PickerData(),
            replay = true, playerPlaying = chatPlaying,
        )
    }
    val chatActions = remember(chat) {
        ChatActions(
            send = { false },
            update = { t -> chat?.update(t) },
            hide = { chat?.setEnabled(false); returnToPlayer() },
            loadPicker = {},
            leave = { returnToPlayer() },
        )
    }
    val chatHasFocus = chatVisible && chatController.hasFocus

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop() }
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    BackHandler {
        if (qualityOpen) qualityOpen = false else onBack()
    }

    val prompt = ui.resumePromptMs
    // A pending skip previews on the bar; otherwise the bar follows the playhead.
    val shownPosition = if (skip.active) skip.targetMs else ui.positionMs
    val controls = VodControls(
        kindLabel = if (ui.isClip) "Clip" else "Past broadcast",
        positionMs = shownPosition,
        durationMs = ui.durationMs,
        skipCounter = skipLabel(skip.deltaMs),
        onSkip = viewModel::pressSkip,
    )

    ChatLayout(
        chatVisible = chatVisible,
        model = chatModel,
        actions = chatActions,
        controller = chatController,
        modifier = Modifier.background(FrostColors.VideoBackdrop),
    ) {
        Box(Modifier.fillMaxSize()) {
            player?.let { p ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerSurface(p, Modifier.aspectRatio(aspect), SURFACE_TYPE_SURFACE_VIEW)
                }
            }
            PlayerChrome(
                controlsVisible = controlsVisible,
                onShowControls = { controlsVisible = true },
                onHideControls = { controlsVisible = false },
                channelName = ui.channelName,
                title = ui.title,
                badge = LiveBadge("", LiveState.Unknown),
                playing = ui.playing,
                chatVisible = chatVisible,
                onPlayPause = viewModel::togglePlay,
                onRefresh = viewModel::refresh,
                onToggleChat = { chat?.setEnabled(!chatVisible) },
                onChannel = {
                    if (ui.channelLogin.isNotEmpty()) onOpenChannel(ui.channelLogin) else viewModel.showNotice("Channel not loaded yet")
                },
                onQuality = { qualityOpen = true },
                focusLocked = qualityOpen || ui.error != null || chatHasFocus || prompt != null,
                onEnterChat = if (chatVisible) {
                    {
                        controlsVisible = false
                        chatController.enter()
                    }
                } else null,
                rootRequester = playerRoot,
                rootFocusable = prompt == null,
                vod = controls,
                showChatToggle = chat != null,
            )

            if (ui.loading && ui.error == null) {
                Text("Loading...", style = MaterialTheme.typography.bodyLarge, color = FrostColors.Muted, modifier = Modifier.align(Alignment.Center))
            }
            ui.error?.let { msg ->
                VodError(msg, onRetry = viewModel::refresh, modifier = Modifier.align(Alignment.Center))
            }
            if (prompt != null && ui.error == null) {
                ResumePrompt(prompt, viewModel::resumeFromSaved, viewModel::startOver, Modifier.align(Alignment.Center))
            }
            ui.notice?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                        .background(FrostColors.SurfaceFocused, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (debug) DebugOverlay(metrics, null, if (showFps) fps else null, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 120.dp), jank = jank, extra = listOfNotNull(chatDebug.ifEmpty { null }))
            if (qualityOpen) {
                QualitySheet(
                    variants = ui.variants,
                    selected = ui.quality,
                    onSelect = { v ->
                        viewModel.selectQuality(v)
                        qualityOpen = false
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** "Resume from 12:30 / Start over", shown on open when a position is stored (over 30 s, under 95 %). */
@Composable
fun ResumePrompt(positionMs: Long, onResume: () -> Unit, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            if (first.tryFocus()) return@LaunchedEffect
        }
    }
    Column(
        modifier.background(FrostColors.Surface, RoundedCornerShape(14.dp)).padding(24.dp).testTag("resume_prompt"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Continue where you left off?", style = MaterialTheme.typography.titleMedium, color = FrostColors.OnSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FrostButton("Resume from ${Durations.clock(positionMs)}", onResume, Modifier.focusRequester(first).testTag("resume_btn"))
            FrostSecondaryButton("Start over", onStartOver, Modifier.testTag("start_over_btn"))
        }
    }
}

@Composable
private fun VodError(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { retry.requestFocus() } }
    Column(
        modifier.background(FrostColors.Surface, RoundedCornerShape(14.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.tv.material3.Icon(androidx.compose.ui.res.painterResource(tv.frostline.core.ui.StateIcons.Error), contentDescription = null, tint = FrostColors.Muted, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, color = FrostColors.OnSurface, modifier = Modifier.testTag("player_error"))
        FrostButton("Try again", onRetry, Modifier.focusRequester(retry))
        Text("Press Back to leave", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
    }
}
