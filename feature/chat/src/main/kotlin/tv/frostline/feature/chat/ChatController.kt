package tv.frostline.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import tv.frostline.core.data.ChatOptions
import tv.frostline.core.twitchinternal.CustomReward

enum class Banner { Prediction, Poll }

/** Every focus stop in the chat zone. */
class ChatFocus {
    val list = FocusRequester()
    val menu = FocusRequester()
    val input = FocusRequester()
    val emotes = FocusRequester()
    val send = FocusRequester()
    val menuFirst = FocusRequester()
    val pickerFirstTab = FocusRequester()
    val rewards = FocusRequester()
    val bannerPrediction = FocusRequester()
    val bannerPoll = FocusRequester()
    val signIn = FocusRequester()
    /** The resize hint, which takes focus (and the Left/Right keys) while the panel is being resized. */
    val resize = FocusRequester()
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
    var rewardsOpen by mutableStateOf(false)
    var predictionOpen by mutableStateOf(false)
    var pollOpen by mutableStateOf(false)
    /** Rewards sheet: the reward on the confirm step (null = browsing), the last one picked, and the typed text. */
    var rewardPick by mutableStateOf<CustomReward?>(null)
    var rewardLast by mutableStateOf<String?>(null)
    var rewardText by mutableStateOf("")
    /** Prediction sheet: chosen outcome, stake, and whether the confirm step is showing. */
    var predOutcome by mutableStateOf<String?>(null)
    var predAmount by mutableLongStateOf(0L)
    var predConfirm by mutableStateOf(false)
    /** Poll sheet: the choice on the confirm step (null = browsing). */
    var pollChoice by mutableStateOf<String?>(null)
    /** Which banner holds focus, so it can be handed on when that banner goes away. */
    var bannerFocused by mutableStateOf<Banner?>(null)
    var draft by mutableStateOf("")
    /** True only after OK on the text field (the keyboard is allowed); focusing the field never sets it. */
    var inputEditing by mutableStateOf(false)
    /** True = pinned to the newest message. Only an explicit Up on the list clears it. */
    var following by mutableStateOf(true)
    var panelFocused by mutableStateOf(false)
    var overlayFocused by mutableStateOf(false)
    /** True while focus is anywhere in the panel or its overlays. */
    val hasFocus: Boolean get() = panelFocused || overlayFocused

    val overlayOpen: Boolean get() = resizing || menuOpen || pickerOpen || rewardsOpen || predictionOpen || pollOpen

    /** Moves focus into the chat list (the entry point from the player). False if the panel is not composed. */
    fun enter(): Boolean {
        following = true // entering the list always follows live
        return runCatching { focus.list.requestFocus() }.isSuccess
    }

    // ---- resize mode (Chat menu > Resize panel) ----
    /** True while the panel is being resized with Left/Right; the hint is showing and the video follows [resizePct]. */
    var resizing by mutableStateOf(false)
    var resizePct by mutableIntStateOf(ChatOptions.DEFAULT_WIDTH_PCT)
    /** The width just saved, used until the settings flow catches up so the layout does not jump back. */
    var widthOverride by mutableStateOf<Int?>(null)
    private var resizeOrigin = ChatOptions.DEFAULT_WIDTH_PCT
    /** Height of the bottom button row (px): sheets anchor above it. */
    var buttonRowHeightPx by mutableIntStateOf(0)

    fun startResize(currentPct: Int) {
        resizeOrigin = currentPct
        resizePct = currentPct
        resizing = true
    }

    /** Left makes the panel wider, Right narrower (the panel's left edge follows the key). */
    fun resizeStep(dir: Int, repeat: Int) { resizePct = ChatOptions.stepWidth(resizePct, dir, repeat) }

    /** OK: returns the width to save. */
    fun finishResize(): Int {
        resizing = false
        widthOverride = resizePct
        return resizePct
    }

    /** Back: reverts to the width the resize started from. */
    fun cancelResize() {
        resizePct = resizeOrigin
        resizing = false
    }

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
        rewardsOpen = false
        predictionOpen = false
        pollOpen = false
    }

    fun openRewards(act: EngagementActions) {
        rewardPick = null
        act.resetRedeem()
        act.loadRewards()
        rewardsOpen = true
    }

    fun openPrediction(act: EngagementActions) {
        predConfirm = false
        act.resetPrediction()
        predictionOpen = true
    }

    fun openPoll(act: EngagementActions) {
        pollChoice = null
        act.resetPoll()
        pollOpen = true
    }

    fun closePrediction(act: EngagementActions) {
        predConfirm = false
        act.resetPrediction()
        predictionOpen = false
    }

    fun closePoll(act: EngagementActions) {
        pollChoice = null
        act.resetPoll()
        pollOpen = false
    }

    /** Back inside a sheet steps out of its confirm/result step first, then closes it. */
    fun backFromRewards(ui: EngagementUi, act: EngagementActions) {
        when {
            ui.redeem is ActionState.Done || ui.redeem is ActionState.Failed -> { act.resetRedeem(); rewardPick = null }
            rewardPick != null -> rewardPick = null
            else -> rewardsOpen = false
        }
    }

    fun backFromPrediction(ui: EngagementUi, act: EngagementActions) {
        when {
            ui.predictionAction is ActionState.Done || ui.predictionAction is ActionState.Failed -> { act.resetPrediction(); predConfirm = false }
            predConfirm -> predConfirm = false
            else -> closePrediction(act)
        }
    }

    fun backFromPoll(ui: EngagementUi, act: EngagementActions) {
        when {
            ui.pollAction is ActionState.Done || ui.pollAction is ActionState.Failed -> { act.resetPoll(); pollChoice = null }
            pollChoice != null -> pollChoice = null
            else -> closePoll(act)
        }
    }
}

@Composable
fun rememberChatController(): ChatController {
    val state = rememberLazyListState()
    return remember { ChatController(state) }
}
