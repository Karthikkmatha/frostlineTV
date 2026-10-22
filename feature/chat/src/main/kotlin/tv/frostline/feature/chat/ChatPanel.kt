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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
class ChatUiModel(
    val lines: List<ChatLine> = emptyList(),
    val connection: ConnState = ConnState.Idle,
    val room: String = "",
    val canSend: Boolean = false,
    val signedIn: Boolean = false,
    val settings: ChatSettings = ChatSettings(),
    val picker: PickerData = PickerData(),
)

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
    Column(
        modifier
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
                Text("Chat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
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
        if (model.signedIn) {
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

@Composable
private fun MessageList(model: ChatUiModel, c: ChatController, fontSp: Int, actions: ChatActions, modifier: Modifier) {
    val state = c.listState
    val scope = rememberCoroutineScope()
    val f = c.focus
    var focused by remember { mutableStateOf(false) }
    // Newest-first data with reverseLayout: index 0 is the live edge and stays pinned to the bottom.
    val atLive by remember { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
    val step = with(LocalDensity.current) { (fontSp * 4.8f).sp.toPx() }

    // While scrolled back, remember the newest line at the moment we left live so we can count what arrived since.
    var pausedAtKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(atLive) { pausedAtKey = if (atLive) null else model.lines.firstOrNull()?.key }
    val newCount = if (atLive) 0 else pausedAtKey?.let { k -> model.lines.indexOfFirst { it.key == k }.takeIf { it >= 0 } } ?: 0

    Box(
        modifier
            .padding(horizontal = 4.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionUp -> if (state.canScrollForward) {
                        scope.launch { state.scrollBy(step) }
                        true
                    } else false // nothing older: let focus move up to the menu
                    e.key == Key.DirectionDown -> if (!atLive) {
                        scope.launch {
                            state.scrollBy(-step)
                            // Snap the last partial step so "live" is reachable exactly.
                            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < step) state.scrollToItem(0)
                        }
                        true
                    } else false
                    e.key == Key.DirectionLeft -> {
                        actions.leave()
                        true
                    }
                    isOkKey(e.key) -> {
                        if (!atLive) scope.launch { state.scrollToItem(0) }
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
            userScrollEnabled = false, // scrolled by the D-pad handler above (and programmatically)
            modifier = Modifier.fillMaxSize(),
        ) {
            items(model.lines, key = { it.key }, contentType = { it is ChatMessage }) { line ->
                ChatLineItem(line, fontSp, model.settings.timestamps)
            }
        }
        if (!atLive) {
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
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                "Emotes",
                {
                    actions.loadPicker()
                    c.pickerOpen = true
                },
                Modifier.onDpad(Key.DirectionLeft) { actions.leave(); true }.focusRequester(f.emotes).testTag("chat_emotes_btn"),
            )
            TvButton("Send", ::submit, Modifier.focusRequester(f.send).testTag("chat_send_btn"), enabled = model.canSend)
        }
    }
}
