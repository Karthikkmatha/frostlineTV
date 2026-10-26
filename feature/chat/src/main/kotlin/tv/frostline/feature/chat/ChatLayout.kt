package tv.frostline.feature.chat

import tv.frostline.core.data.ChatOptions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tv.frostline.core.ui.tryFocus

/**
 * Video on the left, chat panel on the right, menu and emote picker as overlays.
 *
 * FOCUS MODEL (documented in the README too)
 *  - Two zones: PLAYER (the player root / its controls) and CHAT (panel + overlays). Only one has focus.
 *  - PLAYER -> CHAT: Right on the bare player (controls hidden) or Right on the controls' Chat / gear button
 *    calls ChatController.enter(), which focuses the message list.
 *  - CHAT -> PLAYER: Left from the leftmost element of each row (menu, list, field, Emotes) or Back from the
 *    panel calls ChatActions.leave(); the host puts focus back on the player root with controls hidden.
 *  - Inside CHAT everything focusable is at the bottom: list, then banners, field, and the button row
 *    (Emotes, Send, Rewards, Menu; guest: Menu, Sign in; replay: Menu). Up/Down on the list scroll history and
 *    Down at the newest line moves to the bottom section; Up from its top returns to the list, following live.
 *  - Sheets (menu, emotes, rewards, prediction, poll) anchor above the button row and trap focus; Back returns
 *    to the button or banner that opened them. "Resize panel" (menu) enters resize mode: Left/Right change the
 *    width, OK saves, Back reverts.
 *
 * The video box keeps one position in the Row; only its weight changes with the panel, so the SurfaceView
 * inside is resized but never recreated.
 */
@Composable
fun ChatLayout(
    chatVisible: Boolean,
    model: ChatUiModel,
    actions: ChatActions,
    controller: ChatController,
    modifier: Modifier = Modifier,
    video: @Composable BoxScope.() -> Unit,
) {
    // Resize mode previews live; after saving, the saved value holds until the settings flow catches up.
    val savedPct = model.settings.widthPct
    LaunchedEffect(savedPct, controller.widthOverride) {
        if (controller.widthOverride == savedPct) controller.widthOverride = null
    }
    val pct = if (controller.resizing) controller.resizePct else controller.widthOverride ?: savedPct
    val frac = ChatOptions.clampWidth(pct) / 100f
    val scope = rememberCoroutineScope()
    val f = controller.focus

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = maxWidth * frac
        // Sheets anchor just above the bottom button row.
        val anchorBottom = with(LocalDensity.current) { controller.buttonRowHeightPx.toDp() }.coerceAtLeast(48.dp) + 8.dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (chatVisible) 1f - frac else 1f).fillMaxHeight(), content = video)
            if (chatVisible) ChatPanel(model, actions, controller, Modifier.weight(frac).fillMaxHeight())
        }
        if (chatVisible && controller.menuOpen) {
            ChatMenu(
                model.settings, controller, actions,
                onJumpToLive = { scope.launch { controller.resumeLive() } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = panelWidth + 8.dp, bottom = anchorBottom).width(300.dp)
                    .heightIn(max = (maxHeight - anchorBottom - 8.dp).coerceAtLeast(120.dp)),
            )
        }
        if (chatVisible && controller.pickerOpen) {
            // The drawer has its own clock: it animates whether or not chat has focus and whatever the chat rate is.
            // Only the setting (Animated emotes: Off) turns it off.
            val drawerScope = rememberCoroutineScope()
            val drawerClock = remember { EmoteClock(drawerScope) }
            LaunchedEffect(model.settings.animatedEmotes) { drawerClock.setActive(model.settings.animatedEmotes) }
            CompositionLocalProvider(
                LocalEmoteClock provides if (model.settings.animatedEmotes) drawerClock else null,
                LocalEmoteGate provides EmoteGate { true },
            ) {
            EmotePicker(
                model.picker, controller,
                Modifier.align(Alignment.BottomEnd).padding(bottom = anchorBottom).fillMaxHeight().width(if (panelWidth > 380.dp) panelWidth else 380.dp),
            )
            }
        }

        val eng = model.engagement
        val sheetModifier = Modifier.align(Alignment.BottomEnd).padding(bottom = anchorBottom).fillMaxHeight().width(if (panelWidth > 420.dp) panelWidth else 420.dp)
        if (chatVisible && controller.rewardsOpen) RewardsSheet(eng, actions.engagement, controller, sheetModifier)
        if (chatVisible && controller.predictionOpen) PredictionSheet(eng, actions.engagement, controller, sheetModifier)
        if (chatVisible && controller.pollOpen) PollSheet(eng, actions.engagement, controller, sheetModifier)

        // Closing an overlay returns focus to its opener.
        var menuWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.menuOpen) {
            if (!controller.menuOpen && menuWas && !controller.resizing) runCatching { f.menu.requestFocus() }
            menuWas = controller.menuOpen
        }
        var pickerWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.pickerOpen) {
            if (!controller.pickerOpen && pickerWas) runCatching { f.emotes.requestFocus() }
            pickerWas = controller.pickerOpen
        }

        var resizingWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.resizing) {
            // Save or cancel hands focus back to the Menu button that opened resize mode.
            if (!controller.resizing && resizingWas) f.menu.tryFocus()
            resizingWas = controller.resizing
        }

        var rewardsWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.rewardsOpen) {
            // The Rewards button is gone if rewards failed to load; Menu is the neighbour then.
            if (!controller.rewardsOpen && rewardsWas && !f.rewards.tryFocus()) f.menu.tryFocus()
            rewardsWas = controller.rewardsOpen
        }
        var predictionWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.predictionOpen) {
            if (!controller.predictionOpen && predictionWas && !f.bannerPrediction.tryFocus()) f.list.tryFocus()
            predictionWas = controller.predictionOpen
        }
        var pollWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.pollOpen) {
            if (!controller.pollOpen && pollWas && !f.bannerPoll.tryFocus()) f.list.tryFocus()
            pollWas = controller.pollOpen
        }

        // Composed after the video (and its controls), so it wins over their Back handlers while chat has focus.
        BackHandler(enabled = chatVisible && (controller.hasFocus || controller.overlayOpen)) {
            when {
                controller.resizing -> controller.cancelResize()
                controller.rewardsOpen -> controller.backFromRewards(eng, actions.engagement)
                controller.predictionOpen -> controller.backFromPrediction(eng, actions.engagement)
                controller.pollOpen -> controller.backFromPoll(eng, actions.engagement)
                controller.pickerOpen -> controller.pickerOpen = false
                controller.menuOpen -> controller.menuOpen = false
                else -> actions.leave()
            }
        }
    }
}
