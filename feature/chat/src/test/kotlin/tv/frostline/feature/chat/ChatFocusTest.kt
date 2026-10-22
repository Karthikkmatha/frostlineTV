package tv.frostline.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.ui.FrostlineTheme

/**
 * Remote-only traversal of the chat zone: everything the user must be able to do with D-pad, OK and Back.
 * "video_root" stands in for the player root that ChatActions.leave() hands focus back to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ChatFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: ChatController
    private var leaves = 0
    private var hides = 0
    private val sent = mutableListOf<String>()
    private var settingsNow = ChatSettings()
    private var created = 0
    private var disposed = 0

    private fun msg(i: Int) = ChatMessage(
        "k$i", 0L, "00:00", "u$i", "user$i", "User$i", 0xFFFFFFFF.toInt(), emptyList(),
        listOf(Part.Text("hello number $i")), false, null, null, false,
    )

    private val emotes = listOf("Alpha", "Beta", "Gamma", "Delta", "Eps").map { Emote("id$it", it, "https://example.invalid/$it.png", EmoteProvider.Twitch) }

    private fun show(
        signedIn: Boolean = true,
        count: Int = 80,
        visible: Boolean = true,
        initial: ChatSettings = ChatSettings(),
    ) {
        val lines = (count - 1 downTo 0).map { msg(it) }
        rule.setContent {
            FrostlineTheme {
                controller = rememberChatController()
                var s by remember { mutableStateOf(initial) }
                settingsNow = s
                ChatLayout(
                    chatVisible = visible,
                    model = ChatUiModel(
                        lines, ConnState.Connected, "Slow 5 s", canSend = signedIn, signedIn = signedIn,
                        settings = s, picker = PickerData(twitch = emotes),
                    ),
                    actions = ChatActions(
                        send = { sent += it; true },
                        update = { t -> s = t(s) },
                        hide = { hides++ },
                        loadPicker = {},
                        leave = { leaves++ },
                    ),
                    controller = controller,
                ) {
                    DisposableEffect(Unit) {
                        created++
                        onDispose { disposed++ }
                    }
                    Box(Modifier.fillMaxSize().testTag("video_root").focusable())
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(k: Key) {
        rule.onNodeWithTag("video_root").performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag).assertIsFocused()
    private fun SemanticsNodeInteraction.focus() = also { requestFocus(); rule.waitForIdle() }
    private fun enter() {
        rule.runOnUiThread { assertTrue(controller.enter()) }
        rule.waitForIdle()
    }
    private fun index() = controller.listState.firstVisibleItemIndex

    // ---- player <-> chat ----

    @Test fun enterPutsFocusOnTheMessageList() {
        show()
        enter()
        focused("chat_list")
        assertTrue(controller.hasFocus)
    }

    @Test fun leftFromEveryLeftEdgeReturnsToThePlayer() {
        show()
        enter(); press(Key.DirectionLeft)
        assertEquals(1, leaves)
        rule.onNodeWithTag("chat_menu_btn").focus(); press(Key.DirectionLeft)
        assertEquals(2, leaves)
        rule.onNodeWithTag("chat_input").focus(); press(Key.DirectionLeft)
        assertEquals(3, leaves)
        rule.onNodeWithTag("chat_emotes_btn").focus(); press(Key.DirectionLeft)
        assertEquals(4, leaves)
    }

    @Test fun backFromThePanelReturnsToThePlayer() {
        show()
        enter()
        back()
        assertEquals(1, leaves)
    }

    @Test fun chatDoesNotClaimFocusWhileThePlayerHasIt() {
        show()
        rule.onNodeWithTag("video_root").focus()
        assertFalse(controller.hasFocus)
    }

    // ---- history ----

    @Test fun upScrollsHistoryOkJumpsBackToLive() {
        show()
        enter()
        rule.onNodeWithTag("chat_paused").assertDoesNotExistCompat()
        press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp)
        assertTrue("scrolled back, index=${index()}", index() > 0)
        rule.onNodeWithTag("chat_paused").assertExists()
        focused("chat_list")
        press(Key.DirectionCenter)
        assertEquals(0, index())
        rule.onNodeWithTag("chat_paused").assertDoesNotExistCompat()
    }

    @Test fun downScrollsTowardLiveBeforeLeavingTheList() {
        show()
        enter()
        press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp)
        val back = index()
        assertTrue(back > 0)
        press(Key.DirectionDown)
        assertTrue(index() < back)
        focused("chat_list")
        var guard = 0
        while (!(index() == 0 && controller.listState.firstVisibleItemScrollOffset == 0) && guard++ < 30) press(Key.DirectionDown)
        assertEquals(0, index())
        focused("chat_list")
        press(Key.DirectionDown)
        focused("chat_input") // at live, Down moves on to the field
    }

    @Test fun upAtTheOldestLineMovesFocusToTheMenuButton() {
        show(count = 30)
        enter()
        var guard = 0
        while (controller.listState.canScrollForward && guard++ < 100) press(Key.DirectionUp)
        assertFalse(controller.listState.canScrollForward)
        focused("chat_list")
        press(Key.DirectionUp)
        focused("chat_menu_btn")
        press(Key.DirectionDown)
        focused("chat_list")
    }

    @Test fun okOnLiveListFocusesTheTextField() {
        show()
        enter()
        press(Key.DirectionCenter)
        focused("chat_input")
    }

    // ---- input row ----

    @Test fun inputRowTraversal() {
        show()
        enter()
        press(Key.DirectionDown); focused("chat_input")
        press(Key.DirectionRight); focused("chat_emotes_btn")
        press(Key.DirectionRight); focused("chat_send_btn")
        press(Key.DirectionRight); focused("chat_send_btn") // nothing further right
        press(Key.DirectionLeft); focused("chat_emotes_btn")
        press(Key.DirectionUp); focused("chat_input")
        press(Key.DirectionUp); focused("chat_list")
    }

    @Test fun sendButtonSendsTheDraftAndClearsIt() {
        show()
        rule.runOnUiThread { controller.draft = "hello chat" }
        rule.onNodeWithTag("chat_send_btn").focus()
        press(Key.DirectionCenter)
        assertEquals(listOf("hello chat"), sent)
        assertEquals("", controller.draft)
    }

    @Test fun emptyDraftIsNotSent() {
        show()
        rule.onNodeWithTag("chat_send_btn").focus()
        press(Key.DirectionCenter)
        assertTrue(sent.isEmpty())
    }

    @Test fun guestsSeeASignInNoteAndNoInput() {
        show(signedIn = false)
        rule.onNodeWithTag("chat_guest_note").assertExists()
        rule.onNodeWithTag("chat_input").assertDoesNotExistCompat()
        enter()
        press(Key.DirectionDown)
        focused("chat_list")
    }

    // ---- menu ----

    @Test fun menuOpensCyclesSettingsTrapsFocusAndBackReturnsToMenuButton() {
        show()
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("chat_menu").assertExists()
        focused("chat_menu_width")
        press(Key.DirectionCenter)
        assertEquals(2, settingsNow.widthIndex)
        press(Key.DirectionRight); focused("chat_menu_width") // sideways is closed
        press(Key.DirectionLeft); focused("chat_menu_width")
        press(Key.DirectionUp); focused("chat_menu_width") // first row: up is closed
        press(Key.DirectionDown); focused("chat_menu_font")
        press(Key.DirectionCenter)
        assertEquals(2, settingsNow.fontIndex)
        press(Key.DirectionDown); focused("chat_menu_time")
        press(Key.DirectionCenter)
        assertTrue(settingsNow.timestamps)
        back()
        rule.onNodeWithTag("chat_menu").assertDoesNotExistCompat()
        focused("chat_menu_btn")
        assertEquals(0, leaves)
    }

    @Test fun menuReachesEveryRowAndCloseIsLast() {
        show()
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown); focused("chat_menu_live")
        press(Key.DirectionDown); focused("chat_menu_hide")
        press(Key.DirectionDown); focused("chat_menu_close")
        press(Key.DirectionDown); focused("chat_menu_close")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("chat_menu").assertDoesNotExistCompat()
        focused("chat_menu_btn")
    }

    @Test fun widthCyclesThroughAllPresetsAndWraps() {
        show()
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        val seen = mutableListOf(settingsNow.widthIndex)
        repeat(4) { press(Key.DirectionCenter); seen += settingsNow.widthIndex }
        assertEquals(listOf(1, 2, 3, 0, 1), seen)
    }

    @Test fun jumpToLiveFromTheMenu() {
        show()
        enter()
        press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp)
        assertTrue(index() > 0)
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("chat_menu_live").focus()
        press(Key.DirectionCenter)
        assertEquals(0, index())
        rule.onNodeWithTag("chat_menu").assertDoesNotExistCompat()
    }

    @Test fun hideChatFromTheMenuCallsHide() {
        show()
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("chat_menu_hide").focus()
        press(Key.DirectionCenter)
        assertEquals(1, hides)
    }

    // ---- emote picker ----

    @Test fun pickerTabsGridPickAndBack() {
        show()
        rule.onNodeWithTag("chat_emotes_btn").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("emote_picker").assertExists()
        focused("emote_tab_Twitch")
        press(Key.DirectionRight); focused("emote_tab_SevenTv")
        press(Key.DirectionRight); focused("emote_tab_Bttv")
        press(Key.DirectionRight); focused("emote_tab_Ffz")
        press(Key.DirectionRight); focused("emote_close")
        press(Key.DirectionRight); focused("emote_close") // edge closed
        press(Key.DirectionUp); focused("emote_close")
        rule.onNodeWithTag("emote_tab_Twitch").focus()
        press(Key.DirectionLeft); focused("emote_tab_Twitch")
        press(Key.DirectionDown)
        focused("emote_Alpha")
        press(Key.DirectionCenter)
        assertEquals("Alpha ", controller.draft)
        press(Key.DirectionRight); press(Key.DirectionCenter)
        assertEquals("Alpha Beta ", controller.draft)
        press(Key.DirectionLeft); press(Key.DirectionLeft); focused("emote_Alpha") // left edge closed
        press(Key.DirectionUp); focused("emote_tab_Twitch")
        back()
        rule.onNodeWithTag("emote_picker").assertDoesNotExistCompat()
        focused("chat_emotes_btn")
        assertEquals(0, leaves)
    }

    @Test fun pickerSkipsSpacingWhenDraftAlreadyEndsInASpace() {
        show()
        rule.runOnUiThread {
            controller.draft = "hi"
            controller.insertEmote("Kappa")
            controller.insertEmote("LUL")
        }
        assertEquals("hi Kappa LUL ", controller.draft)
    }

    @Test fun closeButtonClosesThePickerAndReturnsToEmotesButton() {
        show()
        rule.onNodeWithTag("chat_emotes_btn").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("emote_close").focus()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("emote_picker").assertDoesNotExistCompat()
        focused("chat_emotes_btn")
    }

    @Test fun emptyTabShowsAMessageAndKeepsFocusInTheTabs() {
        show()
        rule.onNodeWithTag("chat_emotes_btn").focus()
        press(Key.DirectionCenter)
        press(Key.DirectionRight); press(Key.DirectionCenter) // 7TV tab, empty in this fixture
        rule.onNodeWithTag("emote_empty").assertExists()
        press(Key.DirectionDown); focused("emote_tab_SevenTv")
    }

    // ---- layout ----

    @Test fun togglingAndResizingThePanelNeverRecreatesTheVideoSlot() {
        show()
        assertEquals(1, created)
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionCenter)
        repeat(3) { press(Key.DirectionCenter) } // cycle the width through the presets
        back()
        assertEquals(1, created)
        assertEquals(0, disposed)
    }

    @Test fun hiddenPanelHasNoChatNodes() {
        show(visible = false)
        rule.onNodeWithTag("chat_panel").assertDoesNotExistCompat()
        assertEquals(1, created)
    }

    private fun SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
