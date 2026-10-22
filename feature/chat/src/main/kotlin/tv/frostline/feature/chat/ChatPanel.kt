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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
)

internal fun isOkKey(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/** Handles [key] on key-down before the focus target sees it. */
internal fun Modifier.onDpad(key: Key, block: () -> Boolean): Modifier =
    onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == key) block() else false }

/**
 * The chat panel. Focus map (all D-pad):
 *
 *     [Menu]            Left -> player      Down -> list
 *     [ message list ]  Up/Down scroll history (Down at live moves on), OK jumps to live, Left -> player
 *     [ text field  ]   OK opens the keyboard, Left -> player, Up -> list
 *     [Emotes] [Send]   Left from Emotes -> player
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
    CompositionLocalProvider(LocalEmoteClock provides if (model.settings.animatedEmotes) clock else null) {
    Column(
        modifier
            // Its own layer: new lines and emote frames redraw this panel, not the rest of the window.
            .graphicsLayer()
            .background(FrostColors.Surface)
            .onFocusChanged { c.panelFocused = it.hasFocus }
            .focusGroup()
            .testTag("chat_panel"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvButton(
                "Menu",
                { c.menuOpen = true },
                Modifier.onDpad(Key.DirectionLeft) { actions.leave(); true }.focusRequester(f.menu).testTag("chat_menu_btn"),
            )
            Column(Modifier.weight(1f)) {
                Text(if (model.replay) "Chat replay" else "Chat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface, modifier = Modifier.testTag("chat_title"))
                val status = when (val s = model.connection) {
                    is ConnState.Connected -> model.room
                    is ConnState.Reconnecting -> "Reconnecting..."
                    ConnState.Connecting -> "Connecting..."
                    ConnState.Idle -> ""
                }
                if (status.isNotEmpty()) Text(status, fontSize = 11.sp, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag("chat_status"))
            }
        }
        MessageList(model, c, fontSp, actions, Modifier.weight(1f).fillMaxWidth())
        if (model.replay) {
            Text(
                "Chat replay. Sending is disabled.",
                fontSize = 12.sp,
                color = FrostColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("chat_replay_note"),
            )
        } else if (model.signedIn) {
            InputArea(model, actions, c)
        } else {
            Text(
                "Sign in to chat",
                fontSize = 12.sp,
                color = FrostColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("chat_guest_note"),
            )
        }
    }
    }
}

@Composable
private fun MessageList(model: ChatUiModel, c: ChatController, fontSp: Int, actions: ChatActions, modifier: Modifier) {
    val state = c.listState
    val scope = rememberCoroutineScope()
    val f = c.focus
    var focused by remember { mutableStateOf(false) }
    val following = c.following
    val step = with(LocalDensity.current) { (fontSp * 4.8f).sp.toPx() }

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
                    e.key == Key.DirectionUp -> if (state.canScrollForward) {
                        c.following = false // the one thing that pauses live
                        scope.launch { state.scrollBy(step) }
                        true
                    } else false // nothing older: let focus move up to the menu
                    e.key == Key.DirectionDown -> if (!following) {
                        scope.launch {
                            state.scrollBy(-step)
                            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < step) c.resumeLive()
                        }
                        true
                    } else if (model.signedIn) {
                        runCatching { f.input.requestFocus() }
                        true
                    } else false
                    e.key == Key.DirectionLeft -> {
                        actions.leave()
                        true
                    }
                    isOkKey(e.key) -> {
                        if (!following) scope.launch { c.resumeLive() }
                        else if (model.signedIn) runCatching { f.input.requestFocus() }
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
private fun InputArea(model: ChatUiModel, actions: ChatActions, c: ChatController) {
    val f = c.focus
    fun submit() {
        if (c.draft.isNotBlank() && actions.send(c.draft)) c.draft = ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvTextField(
            value = c.draft,
            onValueChange = { c.draft = it.take(500) },
            placeholder = if (model.canSend) "Send a message" else "Connecting...",
            imeAction = ImeAction.Send,
            onImeAction = ::submit,
            // A focused text field eats Left/Right as cursor moves, so route them explicitly.
            fieldModifier = Modifier
                .onDpad(Key.DirectionLeft) { actions.leave(); true }
                .onDpad(Key.DirectionRight) { runCatching { f.emotes.requestFocus() }; true }
                .onDpad(Key.DirectionUp) { runCatching { f.list.requestFocus() }; true }
                .onDpad(Key.DirectionDown) { runCatching { f.emotes.requestFocus() }; true }
                .focusRequester(f.input)
                .testTag("chat_input"),
            onEditingChange = { c.inputEditing = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                "Emotes",
                {
                    actions.loadPicker()
                    c.pickerOpen = true
                },
                Modifier.onDpad(Key.DirectionLeft) { actions.leave(); true }
                    .onDpad(Key.DirectionRight) { runCatching { f.send.requestFocus() }; true }
                    .onDpad(Key.DirectionUp) { runCatching { f.input.requestFocus() }; true }
                    .focusRequester(f.emotes).testTag("chat_emotes_btn"),
            )
            TvButton(
                "Send", ::submit,
                Modifier.onDpad(Key.DirectionLeft) { runCatching { f.emotes.requestFocus() }; true }
                    .onDpad(Key.DirectionUp) { runCatching { f.input.requestFocus() }; true }
                    .onDpad(Key.DirectionRight) { true }
                    .focusRequester(f.send).testTag("chat_send_btn"),
            )
        }
    }
}
