package tv.frostline.feature.chat

import tv.frostline.core.data.ChatOptions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 *  - Inside CHAT: Menu -> list -> field -> [Emotes][Send] top to bottom. Up/Down on the list scroll history;
 *    Down at the live edge moves on to the field, Up at the oldest line moves to Menu; OK on the list jumps to live.
 *  - Header row: Menu and Rewards (signed in), Left/Right between them, Down to the first banner (prediction, then poll)
 *    or the list. Banners sit above the list; Up from the list reaches the last banner. Banners never take focus on their own.
 *  - Overlays (menu, picker, rewards, prediction, poll) trap focus: every outward edge is closed; Back/Close returns focus to the button
 *    that opened them.
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
    val frac = ChatOptions.widthFractions[model.settings.widthIndex.coerceIn(0, ChatOptions.widthFractions.lastIndex)]
    val scope = rememberCoroutineScope()
    val f = controller.focus

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = maxWidth * frac
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (chatVisible) 1f - frac else 1f).fillMaxHeight(), content = video)
            if (chatVisible) ChatPanel(model, actions, controller, Modifier.weight(frac).fillMaxHeight())
        }
        if (chatVisible && controller.menuOpen) {
            ChatMenu(
                model.settings, controller, actions,
                onJumpToLive = { scope.launch { controller.resumeLive() } },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = panelWidth + 8.dp).width(280.dp),
            )
        }
        if (chatVisible && controller.pickerOpen) {
            EmotePicker(
                model.picker, controller,
                Modifier.align(Alignment.CenterEnd).width(if (panelWidth > 380.dp) panelWidth else 380.dp).fillMaxHeight(),
            )
        }

        val eng = model.engagement
        val sheetModifier = Modifier.align(Alignment.CenterEnd).width(if (panelWidth > 420.dp) panelWidth else 420.dp).fillMaxHeight()
        if (chatVisible && controller.rewardsOpen) RewardsSheet(eng, actions.engagement, controller, sheetModifier)
        if (chatVisible && controller.predictionOpen) PredictionSheet(eng, actions.engagement, controller, sheetModifier)
        if (chatVisible && controller.pollOpen) PollSheet(eng, actions.engagement, controller, sheetModifier)

        // Closing an overlay returns focus to its opener.
        var menuWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.menuOpen) {
            if (!controller.menuOpen && menuWas) runCatching { f.menu.requestFocus() }
            menuWas = controller.menuOpen
        }
        var pickerWas by remember { mutableStateOf(false) }
        LaunchedEffect(controller.pickerOpen) {
            if (!controller.pickerOpen && pickerWas) runCatching { f.emotes.requestFocus() }
            pickerWas = controller.pickerOpen
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
