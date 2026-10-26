package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostSecondaryButton
import tv.frostline.core.ui.rememberUiFps
import kotlinx.coroutines.flow.MutableStateFlow
import tv.frostline.feature.chat.ChatActions
import tv.frostline.feature.chat.EngagementActions
import tv.frostline.feature.chat.EngagementUi
import tv.frostline.feature.chat.EngagementViewModel
import tv.frostline.feature.chat.ChatLayout
import tv.frostline.feature.chat.ChatUiModel
import tv.frostline.feature.chat.ChatViewModel
import tv.frostline.feature.chat.rememberChatController

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    chat: ChatViewModel,
    onBack: () -> Unit,
    /** Opens the channel page of the given login. */
    onOpenChannel: (String) -> Unit = {},
    /** Guests: the chat's Sign in button. */
    onSignIn: () -> Unit = {},
    /** Channel points, rewards, predictions and polls (signed in only). Null = none of it. */
    engagement: EngagementViewModel? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val aspect by viewModel.videoAspect.collectAsStateWithLifecycle()
    val raid by viewModel.raid.notice.collectAsStateWithLifecycle()
    val debug by viewModel.debugOverlay.collectAsStateWithLifecycle(initialValue = false)
    val metrics by viewModel.metrics.state.collectAsStateWithLifecycle()
    val showFps by viewModel.showFps.collectAsStateWithLifecycle(initialValue = false)
    val fps = rememberUiFps(debug && showFps)
    val jank = tv.frostline.core.ui.rememberJankLine(debug)
    val emoteLine by produceState("", debug) {
        while (debug) {
            value = tv.frostline.feature.chat.EmoteEngine.current?.debugLine().orEmpty()
            kotlinx.coroutines.delay(1_000)
        }
        value = ""
    }

    val bitmapLine by produceState("", debug) {
        while (debug) {
            value = tv.frostline.feature.chat.EmoteEngine.current?.bitmapLine().orEmpty()
            kotlinx.coroutines.delay(2_000)
        }
        value = ""
    }
    // Home's thumbnails are off screen now: free their decoded images.
    tv.frostline.core.ui.DropImageMemoryWhileShown()

    // PSS breakdown every 5 s while the debug overlay is on (Debug.getMemoryInfo is not free).
    val memLine by produceState("", debug) {
        while (debug) {
            value = memoryBreakdown()
            kotlinx.coroutines.delay(5_000)
        }
        value = ""
    }

    // Uptime, threads, fds, sockets and the emote caches' sizes: creep here over hours means a leak.
    val healthLine by produceState("", debug) {
        while (debug) {
            value = tv.frostline.core.ui.HealthStats.line(tv.frostline.feature.chat.EmoteEngine.current?.let { ", ${it.cacheCounts()}" }.orEmpty())
            kotlinx.coroutines.delay(5_000)
        }
        value = ""
    }

    var controlsVisible by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }
    // The live offset is only read by the controls' LIVE badge and the debug overlay: tick (1 Hz) only while one is shown.
    LaunchedEffect(controlsVisible, debug) { viewModel.setOffsetObserved(controlsVisible || debug) }

    // ---- chat ----
    val chatSettings by chat.settings.collectAsStateWithLifecycle()
    val chatLines by chat.lines.collectAsStateWithLifecycle()
    val chatConn by chat.connection.collectAsStateWithLifecycle()
    val chatRoom by chat.room.collectAsStateWithLifecycle()
    val chatCanSend by chat.canSend.collectAsStateWithLifecycle()
    val chatSignedIn by chat.signedIn.collectAsStateWithLifecycle()
    val chatPicker by chat.picker.collectAsStateWithLifecycle()
    val chatVisible = chatSettings.enabled
    val engUi by remember(engagement) { engagement?.engine?.ui ?: MutableStateFlow(EngagementUi()) }.collectAsStateWithLifecycle()
    val integrityLine by tv.frostline.core.twitchinternal.IntegrityStatus.line.collectAsStateWithLifecycle()
    val profileLine by tv.frostline.core.ui.ProfileStatus.line.collectAsStateWithLifecycle()
    val webViewLine by tv.frostline.core.twitchinternal.WebViewRenderers.line.collectAsStateWithLifecycle()
    val engDebug by remember(engagement) { engagement?.engine?.debug ?: MutableStateFlow(emptyList<String>()) }.collectAsStateWithLifecycle()
    val raidLine by viewModel.raidLine.collectAsStateWithLifecycle(initialValue = "idle")
    val chatController = rememberChatController()
    val playerRoot = remember { FocusRequester() }
    var returnTick by remember { mutableIntStateOf(0) }
    // Focus goes back to the bare player (controls hidden). Waits one frame so the root is focusable again.
    val returnToPlayer = { controlsVisible = false; returnTick++ }
    LaunchedEffect(returnTick) {
        if (returnTick > 0) {
            withFrameNanos { }
            runCatching { playerRoot.requestFocus() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { chat.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chat.stop() }
    DisposableEffect(chat) {
        chat.start()
        onDispose { chat.stop() }
    }
    LaunchedEffect(ui.login) { chat.setChannel(ui.login) }
    LaunchedEffect(ui.login, engagement) { engagement?.setChannel(ui.login) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { engagement?.setChannel(ui.login) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { engagement?.engine?.stop() }
    val chatPlaying = ui.playing
    val chatModel = remember(chatLines, chatConn, chatRoom, chatCanSend, chatSignedIn, chatSettings, chatPicker, chatPlaying, engUi) {
        ChatUiModel(chatLines, chatConn, chatRoom, chatCanSend, chatSignedIn, chatSettings, chatPicker, playerPlaying = chatPlaying, engagement = engUi)
    }
    val signIn by rememberUpdatedState(onSignIn)
    val chatActions = remember(chat, engagement) {
        ChatActions(
            send = chat::send,
            update = chat::update,
            hide = { chat.setEnabled(false); returnToPlayer() },
            loadPicker = chat::ensurePickerLoaded,
            leave = { returnToPlayer() },
            engagement = engagement?.engine?.actions() ?: EngagementActions(),
            signIn = { signIn() },
        )
    }
    val chatHasFocus = chatVisible && chatController.hasFocus

    // Decoder lifecycle: acquire when started, release in onStop and when leaving the screen.
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

    // Back: the quality sheet closes first; PlayerChrome's own handler hides the controls next
    // (it is registered later, so it wins while the controls are shown); otherwise leave the screen.
    BackHandler {
        if (qualityOpen) qualityOpen = false else onBack()
    }

    ChatLayout(
        chatVisible = chatVisible,
        model = chatModel,
        actions = chatActions,
        controller = chatController,
        modifier = Modifier.background(Color.Black),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Video is a SurfaceView layer; nothing is ever rendered through Compose.
            player?.let { p ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerSurface(p, Modifier.aspectRatio(aspect), SURFACE_TYPE_SURFACE_VIEW)
                }
            }
            PlayerChrome(
                controlsVisible = controlsVisible,
                onShowControls = { controlsVisible = true },
                onHideControls = { controlsVisible = false },
                channelName = ui.displayName,
                title = ui.title,
                badge = liveBadge(ui.liveOffsetMs, if (ui.liveMode == tv.frostline.core.data.LiveMode.Stable) STABLE_EDGE_MS else LIVE_EDGE_MS),
                playing = ui.playing,
                chatVisible = chatVisible,
                onPlayPause = viewModel::togglePlay,
                onRefresh = viewModel::refresh,
                onToggleChat = { chat.setEnabled(!chatVisible) },
                onChannel = { onOpenChannel(ui.login) },
                onQuality = { qualityOpen = true },
                focusLocked = qualityOpen || raid != null || ui.error != null || chatHasFocus,
                onEnterChat = if (chatVisible) {
                    {
                        controlsVisible = false
                        chatController.enter()
                    }
                } else null,
                rootRequester = playerRoot,
            )

            if (ui.loading && ui.error == null) {
                Text("Loading...", style = MaterialTheme.typography.bodyLarge, color = FrostColors.Muted, modifier = Modifier.align(Alignment.Center))
            }
            ui.error?.let { msg ->
                ErrorPanel(msg, onRetry = viewModel::refresh, modifier = Modifier.align(Alignment.Center))
            }
            ui.notice?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) }
            if (debug) DebugOverlay(metrics, ui.liveOffsetMs, if (showFps) fps else null, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 120.dp), jank = jank, extra = listOf("live     ${ui.liveMode.label}, target ${LiveOffsets.targetSeconds(ui.liveMode)} s") + engDebug.map { "engage   $it" } + "hm-raid   $raidLine" + "integrity $integrityLine" + "webview  $webViewLine" + "profile  $profileLine" + listOfNotNull(healthLine.takeIf { it.isNotEmpty() }) + listOfNotNull(emoteLine.takeIf { it.isNotEmpty() }, bitmapLine.takeIf { it.isNotEmpty() }, memLine.takeIf { it.isNotEmpty() }, ui.errorDetail?.let { "error    $it" }))

            raid?.let { n ->
                RaidBanner(n, onCancel = viewModel.raid::cancel, modifier = Modifier.align(Alignment.TopCenter).padding(top = 27.dp))
            }
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

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { retry.requestFocus() } }
    Column(
        modifier.background(FrostColors.Surface, RoundedCornerShape(14.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = FrostColors.OnSurface, modifier = Modifier.testTag("player_error"))
        FrostButton("Try again", onRetry, Modifier.focusRequester(retry))
        Text("Press Back to leave", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = FrostColors.OnSurface,
        modifier = modifier.background(FrostColors.SurfaceFocused, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun RaidBanner(notice: RaidNotice, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(notice.raidId) { runCatching { cancel.requestFocus() } }
    Row(
        modifier.background(FrostColors.SurfaceFocused, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 10.dp).testTag("raid_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Raiding ${notice.targetName} in ${notice.secondsLeft} s", style = MaterialTheme.typography.bodyLarge, color = FrostColors.OnSurface, modifier = Modifier.testTag("raid_text"))
        FrostSecondaryButton("Cancel", onCancel, Modifier.focusRequester(cancel).testTag("raid_cancel"))
    }
}

/** [fps] is the measured UI frame rate (read here, so only the overlay recomposes), or null when that line is off. */
@Composable
internal fun DebugOverlay(m: MetricsSnapshot, liveOffsetMs: Long?, fps: androidx.compose.runtime.State<Int>?, modifier: Modifier, extra: List<String> = emptyList(), jank: androidx.compose.runtime.State<String>? = null) {
    Column(
        modifier.background(Color(0xB3050507), RoundedCornerShape(8.dp)).padding(10.dp).testTag("debug_overlay"),
    ) {
        val lines = listOf(
            "startup  ${m.startupMs?.let { "$it ms" } ?: "-"}",
            "dropped  ${m.droppedFrames}",
            "rebuffer ${m.rebuffers} (${m.rebufferMs} ms)",
            "video    ${m.width}x${m.height} @ ${m.videoBitrate / 1000} kbps",
            "net      ${m.bandwidthBps / 1000} kbps",
            "latency  ${liveOffsetMs?.let { "$it ms" } ?: "-"}",
        ) + listOfNotNull(fps?.let { "ui fps   ${it.value}" }, jank?.value) + extra
        lines.forEach { Text(it, fontSize = 12.sp, color = FrostColors.Lavender, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
    }
}

/** "memory   PSS 300 MB: java 53, native 60, graphics 114, code 52" from Debug.getMemoryInfo. */
internal fun memoryBreakdown(): String {
    val info = android.os.Debug.MemoryInfo()
    android.os.Debug.getMemoryInfo(info)
    fun mb(key: String) = ((info.getMemoryStat(key)?.toLongOrNull() ?: 0L) / 1024)
    return "memory   PSS ${info.totalPss / 1024} MB: java ${mb("summary.java-heap")}, native ${mb("summary.native-heap")}, graphics ${mb("summary.graphics")}, code ${mb("summary.code")}"
}
