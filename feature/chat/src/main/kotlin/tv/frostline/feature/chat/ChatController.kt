package tv.frostline.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** Every focus stop in the chat zone. */
class ChatFocus {
    val list = FocusRequester()
    val menu = FocusRequester()
    val input = FocusRequester()
    val emotes = FocusRequester()
    val send = FocusRequester()
    val menuFirst = FocusRequester()
    val pickerFirstTab = FocusRequester()
}

/**
 * UI state of the chat zone that must survive recomposition but is not data: which overlay is open, the
 * draft text, whether focus is inside the zone, and the scroll state of the one chat list.
 */
@Stable
class ChatController(val listState: LazyListState) {
    val focus = ChatFocus()
    var menuOpen by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    var draft by mutableStateOf("")
    /** True only after OK on the text field (the keyboard is allowed); focusing the field never sets it. */
    var inputEditing by mutableStateOf(false)
    /** True = pinned to the newest message. Only an explicit Up on the list clears it. */
    var following by mutableStateOf(true)
    var panelFocused by mutableStateOf(false)
    var overlayFocused by mutableStateOf(false)
    /** True while focus is anywhere in the panel or its overlays. */
    val hasFocus: Boolean get() = panelFocused || overlayFocused

    val overlayOpen: Boolean get() = menuOpen || pickerOpen

    /** Moves focus into the chat list (the entry point from the player). False if the panel is not composed. */
    fun enter(): Boolean = runCatching { focus.list.requestFocus() }.isSuccess

    fun insertEmote(code: String) {
        val sep = if (draft.isEmpty() || draft.endsWith(" ")) "" else " "
        draft = draft + sep + code + " "
    }

    /** Resume live following and show the newest message. */
    suspend fun resumeLive() {
        following = true
        listState.scrollToItem(0)
    }

    fun closeOverlays() {
        menuOpen = false
        pickerOpen = false
    }
}

@Composable
fun rememberChatController(): ChatController {
    val state = rememberLazyListState()
    return remember { ChatController(state) }
}
