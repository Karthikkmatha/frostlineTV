package tv.frostline.feature.chat

import tv.frostline.core.data.ChatOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import tv.frostline.core.ui.tryFocus
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.TvTextField

/** Everything the panel draws. */
@Immutable
class ChatUiModel(
    val lines: List<ChatLine> = emptyList(),
    val connection: ConnState = ConnState.Idle,
    val room: String = "",
    val canSend: Boolean = false,
    val signedIn: Boolean = false,
    val settings: ChatSettings = ChatSettings(),
    val picker: PickerData = PickerData(),
    /** Chat replay of a past broadcast: labelled as such, read-only. */
    val replay: Boolean = false,
    /** False while the player is paused: animated emotes hold still. */
    val playerPlaying: Boolean = true,
    /** Channel points, rewards, predictions and polls. Empty for guests, so none of it is drawn. */
    val engagement: EngagementUi = EngagementUi(),
)

@Immutable
class ChatActions(
    val send: (String) -> Boolean,
    val update: ((ChatSettings) -> ChatSettings) -> Unit,
    /** Turn chat off (the panel disappears). */
    val hide: () -> Unit,
    val loadPicker: () -> Unit,
    /** Focus goes back to the player. */
    val leave: () -> Unit,
    val engagement: EngagementActions = EngagementActions(),
    /** Guests: opens the sign-in screen (the Sign in button). */
    val signIn: () -> Unit = {},
)

internal fun isOkKey(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/** Handles [key] on key-down before the focus target sees it. */
internal fun Modifier.onDpad(key: Key, block: () -> Boolean): Modifier =
    onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == key) block() else false }

/**
 * The chat panel. Everything you interact with sits at the bottom; nothing above the list is focusable.
 *
 *     Chat / Chat replay, room modes, points balance     display only
 *     [ message list ]   Up/Down scroll history; Down at the newest line moves to the bottom section
 *     [prediction] [poll] banners, only while active; OK opens the sheet
 *     [ message field ]  OK opens the keyboard
 *     [Emotes] [Send] [Rewards] [Menu]     Left/Right between them (guest: Menu, Sign in; replay: Menu)
 *
 * Up from the top of the bottom section returns to the list at the newest line, following live. Left or Back
 * from anywhere in chat returns to the player.
 */
@Composable
internal fun ChatPanel(
    model: ChatUiModel,
    actions: ChatActions,
    c: ChatController,
    modifier: Modifier = Modifier,
) {
    val f = c.focus
    val fontSp = ChatOptions.fontSizesSp[model.settings.fontIndex.coerceIn(0, ChatOptions.fontSizesSp.lastIndex)]
    // One clock for every animated emote in the panel; it only runs while the panel is composed, animation
    // is on and the player is playing, and only while an emote is on screen.
    val clockScope = rememberCoroutineScope()
    val clock = remember { EmoteClock(clockScope) }
    val animate = model.settings.animatedEmotes && model.playerPlaying
    LaunchedEffect(animate) { clock.setActive(animate) }
    val eng = model.engagement
    val act = actions.engagement
    val showRewards = eng.available && eng.rewardsLoad != RewardsLoad.Failed
    val pred = eng.prediction
    val poll = eng.poll
    val hasInput = model.signedIn && !model.replay

    // The button row, left to right. Variants: guest = Menu, Sign in; replay = Menu; signed in = Emotes, Send, Rewards, Menu.
    val rowOrder: List<FocusRequester> = when {
        model.replay -> listOf(f.menu)
        !model.signedIn -> listOf(f.menu, f.signIn)
        else -> buildList { add(f.emotes); add(f.send); if (showRewards) add(f.rewards); add(f.menu) }
    }
    val toList: () -> Unit = { c.following = true; c.bannerFocused = null; f.list.tryFocus() }
    // Above the button row: the field, else the last banner, else the list.
    val aboveRow: FocusRequester = when {
        hasInput -> f.input
        poll != null -> f.bannerPoll
        pred != null -> f.bannerPrediction
        else -> f.list
    }
    val belowBanners: FocusRequester = if (hasInput) f.input else rowOrder.first()
    val firstBottom: FocusRequester = when {
        pred != null -> f.bannerPrediction
        poll != null -> f.bannerPoll
        else -> belowBanners
    }
    val upFromField: FocusRequester? = when {
        poll != null -> f.bannerPoll
        pred != null -> f.bannerPrediction
        else -> null
    }

    // A banner that held focus and went away hands focus to the list instead of dropping it.
    LaunchedEffect(pred == null) {
        if (pred == null && c.bannerFocused == Banner.Prediction && !c.overlayOpen) { c.bannerFocused = null; f.list.tryFocus() }
    }
    LaunchedEffect(poll == null) {
        if (poll == null && c.bannerFocused == Banner.Poll && !c.overlayOpen) { c.bannerFocused = null; f.list.tryFocus() }
    }
    LaunchedEffect(c.resizing) { if (c.resizing) f.resize.tryFocus() }

    CompositionLocalProvider(LocalEmoteClock provides if (model.settings.animatedEmotes) clock else null) {
    Box(
        modifier
            // Its own layer: new lines and emote frames redraw this panel, not the rest of the window.
            .graphicsLayer()
            .background(FrostColors.Surface)
            .onFocusChanged { c.panelFocused = it.hasFocus }
            .focusGroup()
            .testTag("chat_panel"),
    ) {
    Column(Modifier.fillMaxSize()) {
        // Top: display only. Never focusable.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (model.replay) "Chat replay" else "Chat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface, modifier = Modifier.testTag("chat_title"))
                val status = when (model.connection) {
                    is ConnState.Connected -> model.room
                    is ConnState.Reconnecting -> "Reconnecting..."
                    ConnState.Connecting -> "Connecting..."
                    ConnState.Idle -> ""
                }
                if (status.isNotEmpty()) Text(status, fontSize = 11.sp, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag("chat_status"))
            }
            eng.points?.let { PointsBadge(it) }
        }
        MessageList(model, c, fontSp, actions, firstBottom, Modifier.weight(1f).fillMaxWidth())

        // Bottom section: banners, message field, button row.
        if (pred != null) {
            PredictionBanner(
                pred,
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    .onDpad(Key.DirectionLeft) { c.bannerFocused = null; actions.leave(); true }
                    .onDpad(Key.DirectionRight) { true }
                    .onDpad(Key.DirectionUp) { toList(); true }
                    .onDpad(Key.DirectionDown) { c.bannerFocused = null; (if (poll != null) f.bannerPoll else belowBanners).tryFocus(); true }
                    .onFocusChanged { if (it.isFocused) c.bannerFocused = Banner.Prediction }
                    .focusRequester(f.bannerPrediction),
                onOpen = { c.openPrediction(act) },
            )
        }
        if (poll != null) {
            PollBanner(
                poll,
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    .onDpad(Key.DirectionLeft) { c.bannerFocused = null; actions.leave(); true }
                    .onDpad(Key.DirectionRight) { true }
                    .onDpad(Key.DirectionUp) { c.bannerFocused = null; (if (pred != null) f.bannerPrediction else f.list).also { if (pred == null) c.following = true }.tryFocus(); true }
                    .onDpad(Key.DirectionDown) { c.bannerFocused = null; belowBanners.tryFocus(); true }
                    .onFocusChanged { if (it.isFocused) c.bannerFocused = Banner.Poll }
                    .focusRequester(f.bannerPoll),
                onOpen = { c.openPoll(act) },
            )
        }
        if (model.replay) {
            Text(
                "Chat replay. Sending is disabled.",
                fontSize = 12.sp,
                color = FrostColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat_replay_note"),
            )
        } else if (!model.signedIn) {
            Text(
                "Sign in to chat",
                fontSize = 12.sp,
                color = FrostColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat_guest_note"),
            )
        }
        if (hasInput) {
            InputField(model, actions, c, upFromField, toList, f.emotes)
        }
        val rowButton: @Composable (String, FocusRequester, String, () -> Unit) -> Unit = { label, req, tag, onClick ->
            val idx = rowOrder.indexOf(req)
            TvButton(
                label,
                onClick,
                Modifier
                    .onDpad(Key.DirectionLeft) { if (idx <= 0) actions.leave() else rowOrder[idx - 1].tryFocus(); true }
                    .onDpad(Key.DirectionRight) { if (idx in 0 until rowOrder.lastIndex) rowOrder[idx + 1].tryFocus(); true }
                    .onDpad(Key.DirectionUp) { if (aboveRow == f.list) toList() else aboveRow.tryFocus(); true }
                    .onDpad(Key.DirectionDown) { true }
                    .focusRequester(req).testTag(tag),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).onSizeChanged { c.buttonRowHeightPx = it.height },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (req in rowOrder) when (req) {
                f.emotes -> rowButton("Emotes", req, "chat_emotes_btn") { actions.loadPicker(); c.pickerOpen = true }
                f.send -> rowButton("Send", req, "chat_send_btn") { if (c.draft.isNotBlank() && actions.send(c.draft)) c.draft = "" }
                f.rewards -> rowButton("Rewards", req, "chat_rewards_btn") { c.openRewards(act) }
                f.signIn -> rowButton("Sign in", req, "chat_signin_btn") { actions.signIn() }
                else -> rowButton("Menu", req, "chat_menu_btn") { c.menuOpen = true }
            }
        }
    }
    eng.toast?.let { PointsToast(it, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) }
    if (c.resizing) ResizeHint(c, onSave = { val w = c.finishResize(); actions.update { s -> s.copy(widthPct = w) } }, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
    }
    }
}

/** On-panel hint of resize mode. It holds focus: Left widens, Right narrows, OK saves; Back (the layout) reverts. */
@Composable
private fun ResizeHint(c: ChatController, onSave: () -> Unit, modifier: Modifier) {
    Text(
        "\u25C0 \u25B6 to resize \u00B7 OK to save \u00B7 Back to cancel   ${c.resizePct}%",
        fontSize = 12.sp,
        color = FrostColors.OnSurface,
        modifier = modifier
            .background(FrostColors.Primary, RoundedCornerShape(10.dp))
            .border(2.dp, FrostColors.Violet, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when {
                    e.key == Key.DirectionLeft -> { if (down) c.resizeStep(+1, e.nativeKeyEvent.repeatCount); true }
                    e.key == Key.DirectionRight -> { if (down) c.resizeStep(-1, e.nativeKeyEvent.repeatCount); true }
                    e.key == Key.DirectionUp || e.key == Key.DirectionDown -> true
                    isOkKey(e.key) -> { if (down) onSave(); true }
                    else -> false
                }
            }
            .focusRequester(c.focus.resize)
            .focusable()
            .testTag("chat_resize_hint"),
    )
}

@Composable
private fun MessageList(model: ChatUiModel, c: ChatController, fontSp: Int, actions: ChatActions, downTarget: FocusRequester, modifier: Modifier) {
    val state = c.listState
    val scope = rememberCoroutineScope()
    val f = c.focus
    var focused by remember { mutableStateOf(false) }
    val following = c.following
    val step = with(LocalDensity.current) { (fontSp * 4.8f).sp.toPx() }
    val hasInput = model.signedIn && !model.replay

    // Following live is the default: keep the newest line pinned to the bottom, even if item sizes
    // change after the first frame (images loading, the first system line).
    LaunchedEffect(following) {
        if (following) {
            state.scrollToItem(0)
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect {
                if (c.following && (it.first != 0 || it.second != 0)) state.scrollToItem(0)
            }
        }
    }
    // Leaving the chat zone resumes live following.
    LaunchedEffect(c.hasFocus) { if (!c.hasFocus) c.resumeLive() }

    // While scrolled back, remember the newest line at the moment we paused so we can count what arrived since.
    var pausedAtKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(following) { pausedAtKey = if (following) null else model.lines.firstOrNull()?.key }
    val newCount = if (following) 0 else pausedAtKey?.let { k -> model.lines.indexOfFirst { it.key == k }.takeIf { it >= 0 } } ?: 0

    Box(
        modifier
            .padding(horizontal = 4.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionUp -> {
                        if (state.canScrollForward) {
                            c.following = false // the one thing that pauses live
                            scope.launch { state.scrollBy(step) }
                        } // else: at the oldest line, nothing above the list is focusable
                        true
                    }
                    e.key == Key.DirectionDown -> if (!following) {
                        scope.launch {
                            state.scrollBy(-step)
                            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < step) c.resumeLive()
                        }
                        true
                    } else {
                        // At the newest message: on to the bottom section (banner, field, button row).
                        downTarget.tryFocus()
                        true
                    }
                    e.key == Key.DirectionLeft -> {
                        actions.leave()
                        true
                    }
                    e.key == Key.DirectionRight -> true
                    isOkKey(e.key) -> {
                        if (!following) scope.launch { c.resumeLive() }
                        else if (hasInput) f.input.tryFocus()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(f.list)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) FrostColors.Violet else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable()
            .testTag("chat_list"),
    ) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 4.dp),
            userScrollEnabled = false, // scrolled by the D-pad handler above (and programmatically)
            modifier = Modifier.fillMaxSize(),
        ) {
            items(model.lines, key = { it.key }, contentType = { it is ChatMessage }) { line ->
                ChatLineItem(line, fontSp, model.settings.timestamps)
            }
        }
        if (!following) {
            Text(
                if (newCount > 0) "Paused - $newCount new. OK: jump to live" else "Paused. OK: jump to live",
                fontSize = 12.sp,
                color = FrostColors.OnSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(FrostColors.Primary, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("chat_paused"),
            )
        }
    }
}

@Composable
private fun InputField(
    model: ChatUiModel,
    actions: ChatActions,
    c: ChatController,
    upTarget: FocusRequester?,
    toList: () -> Unit,
    downTarget: FocusRequester,
) {
    val f = c.focus
    fun submit() {
        if (c.draft.isNotBlank() && actions.send(c.draft)) c.draft = ""
    }
    TvTextField(
        value = c.draft,
        onValueChange = { c.draft = it.take(500) },
        placeholder = if (model.canSend) "Send a message" else "Connecting...",
        imeAction = ImeAction.Send,
        onImeAction = ::submit,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        // A focused text field eats Left/Right as cursor moves, so route them explicitly.
        fieldModifier = Modifier
            .onDpad(Key.DirectionLeft) { actions.leave(); true }
            .onDpad(Key.DirectionRight) { true }
            .onDpad(Key.DirectionUp) { if (upTarget != null) upTarget.tryFocus() else toList(); true }
            .onDpad(Key.DirectionDown) { downTarget.tryFocus(); true }
            .focusRequester(f.input)
            .testTag("chat_input"),
        onEditingChange = { c.inputEditing = it },
    )
}
