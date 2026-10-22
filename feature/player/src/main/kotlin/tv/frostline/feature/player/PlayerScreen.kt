package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tv.frostline.core.ui.TvButton

/** Share of the width the chat column takes when shown. */
const val CHAT_WIDTH_FRACTION = 0.28f

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val aspect by viewModel.videoAspect.collectAsStateWithLifecycle()
    val raid by viewModel.raid.notice.collectAsStateWithLifecycle()
    val debug by viewModel.debugOverlay.collectAsStateWithLifecycle(initialValue = false)
    val metrics by viewModel.metrics.state.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(false) }
    var chatVisible by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }

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

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(if (chatVisible) 1f - CHAT_WIDTH_FRACTION else 1f).fillMaxHeight()) {
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
                badge = liveBadge(ui.liveOffsetMs),
                playing = ui.playing,
                chatVisible = chatVisible,
                onPlayPause = viewModel::togglePlay,
                onRefresh = viewModel::refresh,
                onToggleChat = { chatVisible = !chatVisible },
                onChannel = { viewModel.showNotice("Channel page arrives in a later update") },
                onQuality = { qualityOpen = true },
                focusLocked = qualityOpen || raid != null || ui.error != null,
            )

            if (ui.loading && ui.error == null) {
                Text("Loading...", fontSize = 16.sp, color = FrostColors.Muted, modifier = Modifier.align(Alignment.Center))
            }
            ui.error?.let { msg ->
                ErrorPanel(msg, onRetry = viewModel::refresh, modifier = Modifier.align(Alignment.Center))
            }
            ui.notice?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) }
            if (debug) DebugOverlay(metrics, ui.liveOffsetMs, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 120.dp))

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
        if (chatVisible) ChatPlaceholder(Modifier.weight(CHAT_WIDTH_FRACTION).fillMaxHeight())
    }
}

/** Stage 3 replaces this with the live chat. */
@Composable
fun ChatPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier.background(FrostColors.Surface).padding(horizontal = 16.dp, vertical = 27.dp).testTag("chat_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chat", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        Text("Live chat arrives in the next update.", fontSize = 13.sp, color = FrostColors.Muted)
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
        Text(message, fontSize = 18.sp, color = FrostColors.OnSurface, modifier = Modifier.testTag("player_error"))
        TvButton("Try again", onRetry, Modifier.focusRequester(retry))
        Text("Press Back to leave", fontSize = 12.sp, color = FrostColors.Muted)
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 14.sp,
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
        Text("Raiding ${notice.targetName} in ${notice.secondsLeft} s", fontSize = 16.sp, color = FrostColors.OnSurface, modifier = Modifier.testTag("raid_text"))
        TvButton("Cancel", onCancel, Modifier.focusRequester(cancel).testTag("raid_cancel"))
    }
}

@Composable
private fun DebugOverlay(m: MetricsSnapshot, liveOffsetMs: Long?, modifier: Modifier) {
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
        )
        lines.forEach { Text(it, fontSize = 12.sp, color = FrostColors.Lavender, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
    }
}
