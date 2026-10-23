package tv.frostline.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.twitchinternal.CustomReward
import tv.frostline.core.twitchinternal.PollChoice
import tv.frostline.core.twitchinternal.PollStatus
import tv.frostline.core.twitchinternal.PredictionOutcome
import tv.frostline.core.twitchinternal.PredictionStatus
import tv.frostline.core.ui.FrostlineTheme

/**
 * Remote-only traversal of the channel points, rewards, prediction and poll UI (D-pad, OK, Back).
 * "video_root" stands in for the player root that ChatActions.leave() hands focus back to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class EngagementFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: ChatController
    private var eng by mutableStateOf(EngagementUi())
    private var leaves = 0
    private val redeemed = mutableListOf<Pair<String, String?>>()
    private val predicted = mutableListOf<Pair<String, Long>>()
    private val voted = mutableListOf<Pair<String, Boolean>>()

    private fun reward(id: String, title: String, cost: Long, input: Boolean = false, paused: Boolean = false) =
        CustomReward(id, title, "prompt $id", cost, null, true, true, paused, input, null, null, null, 0)

    private val rewards = listOf(
        reward("r0", "Hydrate", 100),
        reward("r1", "Big spender", 5_000),
        reward("r2", "Say hi", 50, input = true),
        reward("r3", "Paused one", 10, paused = true),
    )

    private fun prediction(status: PredictionStatus = PredictionStatus.Active, winner: String? = null, mine: String? = null, result: BetResult? = null) = PredictionUi(
        "e1", "Will he win?", status,
        listOf(PredictionOutcome("a", "Yes", "BLUE", 300, 3), PredictionOutcome("b", "No", "PINK", 100, 1)),
        System.currentTimeMillis() + 90_000, winner, mine, if (mine != null) 100 else 0, result,
    )

    private fun poll(cost: Int? = 100, status: PollStatus = PollStatus.Active) = PollUi(
        "p1", "Pick one", status, listOf(PollChoice("1", "Alpha", 3), PollChoice("2", "Beta", 1)),
        System.currentTimeMillis() + 60_000, cost, null,
    )

    private val signedIn = EngagementUi(
        available = true,
        points = PointsUi(500, "Hype", null),
        rewardsLoad = RewardsLoad.Ready,
        rewards = rewards,
    )

    private fun msg(i: Int) = ChatMessage(
        "k$i", 0L, "00:00", "u$i", "user$i", "User$i", 0xFFFFFFFF.toInt(), emptyList(),
        listOf(Part.Text("hello number $i")), false, null, null, false,
    )

    private fun show(initial: EngagementUi = signedIn, count: Int = 3) {
        eng = initial
        val lines = (count - 1 downTo 0).map { msg(it) }
        rule.setContent {
            FrostlineTheme {
                CompositionLocalProvider(LocalEngagementTicking provides false) {
                    controller = rememberChatController()
                    val actions = remember {
                        ChatActions(
                            send = { true }, update = {}, hide = {}, loadPicker = {}, leave = { leaves++ },
                            engagement = EngagementActions(
                                loadRewards = {},
                                redeem = { r, t -> redeemed += r.id to t; eng = eng.copy(redeem = ActionState.Done("Redeemed ${r.title}")) },
                                resetRedeem = { eng = eng.copy(redeem = ActionState.Idle) },
                                predict = { o, a -> predicted += o to a; eng = eng.copy(predictionAction = ActionState.Done("Prediction placed")) },
                                resetPrediction = { eng = eng.copy(predictionAction = ActionState.Idle) },
                                vote = { c, pts -> voted += c to pts; eng = eng.copy(pollAction = ActionState.Done("Vote cast")) },
                                resetPoll = { eng = eng.copy(pollAction = ActionState.Idle) },
                            ),
                        )
                    }
                    ChatLayout(
                        chatVisible = true,
                        model = ChatUiModel(lines, ConnState.Connected, "", canSend = true, signedIn = true, settings = ChatSettings(), engagement = eng),
                        actions = actions,
                        controller = controller,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("video_root").focusable())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(k: Key) {
        rule.onNodeWithTag("video_root").performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun ok() = press(Key.DirectionCenter)

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag).assertIsFocused()
    private fun SemanticsNodeInteraction.focus() = also { requestFocus(); rule.waitForIdle() }
    private fun exists(tag: String) = rule.onNodeWithTag(tag).assertExists()
    private fun gone(tag: String) = rule.onNodeWithTag(tag).assertDoesNotExist()
    private fun update(block: (EngagementUi) -> EngagementUi) {
        rule.runOnUiThread { eng = block(eng) }
        rule.waitForIdle()
    }
    private fun enter() {
        rule.runOnUiThread { assertTrue(controller.enter()) }
        rule.waitForIdle()
    }

    // ---- header: Menu <-> Rewards ----

    @Test fun buttonRowReachesRewardsFromMenuAndBack() {
        show()
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionRight)
        focused("chat_menu_btn") // Menu is the last button
        press(Key.DirectionLeft)
        focused("chat_rewards_btn")
        press(Key.DirectionRight)
        focused("chat_menu_btn")
        press(Key.DirectionLeft); press(Key.DirectionLeft); press(Key.DirectionLeft)
        focused("chat_emotes_btn")
        assertEquals(0, leaves)
        press(Key.DirectionLeft)
        assertEquals(1, leaves)
    }

    @Test fun upFromTheButtonRowGoesToTheFieldThenTheListWhenThereIsNoBanner() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        press(Key.DirectionUp)
        focused("chat_input")
        press(Key.DirectionUp)
        focused("chat_list")
    }

    @Test fun balanceAndChannelPointsNameAreInTheHeader() {
        show()
        rule.onNodeWithTag("points_value").assertTextEquals("500")
        rule.onNodeWithTag("points_name").assertTextEquals("Hype")
    }

    @Test fun guestsSeeNoRewardsNoBannersNoBalance() {
        show(EngagementUi())
        gone("chat_rewards_btn")
        gone("points_balance")
        gone("banner_prediction")
        rule.onNodeWithTag("chat_menu_btn").focus()
        press(Key.DirectionLeft)
        focused("chat_send_btn") // Rewards is not in the row
    }

    @Test fun rewardsButtonIsHiddenWhenRewardsFailToLoad() {
        show(signedIn.copy(rewardsLoad = RewardsLoad.Failed, rewards = emptyList()))
        gone("chat_rewards_btn")
        exists("points_balance")
    }

    // ---- rewards sheet -> redeem confirm ----

    @Test fun rewardsSheetOpensOnOkAndOkOnARewardShowsTheConfirmStep() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        exists("rewards_sheet")
        focused("reward_0")
        ok()
        exists("redeem_confirm")
        focused("redeem_ok")
        assertTrue(redeemed.isEmpty())
        ok()
        assertEquals(listOf("r0" to null), redeemed)
        exists("redeem_success")
    }

    @Test fun unaffordableAndPausedRewardsAreDisabledAndSayWhy() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        rule.onNodeWithText("Need 4,500 more").assertExists()
        rule.onNodeWithText("Paused by the streamer").assertExists()
        rule.onNodeWithTag("reward_1").focus()
        ok()
        gone("redeem_confirm")
        rule.onNodeWithTag("reward_3").focus()
        ok()
        gone("redeem_confirm")
    }

    @Test fun rewardNeedingTextHasAFieldAndRedeemStaysOffUntilFilled() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        rule.onNodeWithTag("reward_2").focus()
        ok()
        focused("redeem_input")
        rule.onNodeWithTag("redeem_ok").assertIsNotEnabled()
        rule.runOnUiThread { controller.rewardText = "hello chat" }
        rule.waitForIdle()
        rule.onNodeWithTag("redeem_ok").focus()
        ok()
        assertEquals(listOf("r2" to "hello chat"), redeemed)
    }

    @Test fun redeemErrorIsShown() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        ok()
        update { it.copy(redeem = ActionState.Failed("Not enough points.")) }
        exists("redeem_error")
        rule.onNodeWithText("Not enough points.").assertExists()
    }

    @Test fun rewardsSheetTrapsFocus() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        focused("reward_0")
        press(Key.DirectionLeft); press(Key.DirectionRight)
        focused("reward_0")
        press(Key.DirectionUp)
        focused("rewards_close")
        press(Key.DirectionUp); press(Key.DirectionLeft); press(Key.DirectionRight)
        focused("rewards_close")
        assertEquals(0, leaves)
        assertTrue(controller.hasFocus)
    }

    // ---- Back ----

    @Test fun backStepsOutOfConfirmThenClosesTheSheetAndFocusReturnsToRewards() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        ok()
        exists("redeem_confirm")
        back()
        gone("redeem_confirm")
        exists("rewards_sheet")
        focused("reward_0")
        back()
        gone("rewards_sheet")
        focused("chat_rewards_btn")
        assertEquals(0, leaves)
        back()
        assertEquals(1, leaves)
    }

    @Test fun closeButtonAlsoReturnsFocusToRewards() {
        show()
        rule.onNodeWithTag("chat_rewards_btn").focus()
        ok()
        press(Key.DirectionUp)
        focused("rewards_close")
        ok()
        gone("rewards_sheet")
        focused("chat_rewards_btn")
    }

    // ---- banners ----

    @Test fun aBannerAppearingDoesNotStealFocus() {
        show()
        enter()
        focused("chat_list")
        update { it.copy(prediction = prediction(), poll = poll()) }
        exists("banner_prediction")
        exists("banner_poll")
        focused("chat_list")
    }

    @Test fun downFromTheListReachesTheBannerThenTheFieldAndBack() {
        show(signedIn.copy(prediction = prediction()))
        enter()
        press(Key.DirectionDown)
        focused("banner_prediction")
        press(Key.DirectionDown)
        focused("chat_input")
        press(Key.DirectionUp)
        focused("banner_prediction")
        press(Key.DirectionUp)
        focused("chat_list")
        assertTrue(controller.following)
    }

    @Test fun upFromTheButtonRowGoesToTheFieldAndOnToTheBanner() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("chat_rewards_btn").focus()
        press(Key.DirectionUp)
        focused("chat_input")
        press(Key.DirectionUp)
        focused("banner_prediction")
    }

    @Test fun predictionThenPollBannersChainBetweenTheListAndTheField() {
        show(signedIn.copy(prediction = prediction(), poll = poll()))
        enter()
        press(Key.DirectionDown); focused("banner_prediction")
        press(Key.DirectionDown); focused("banner_poll")
        press(Key.DirectionDown); focused("chat_input")
        press(Key.DirectionUp); focused("banner_poll")
        press(Key.DirectionUp); focused("banner_prediction")
        press(Key.DirectionUp); focused("chat_list")
    }

    @Test fun bannerShowsTitleOutcomesPercentagesAndTotals() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("banner_prediction_title", useUnmergedTree = true).assertTextEquals("Will he win?")
        rule.onNodeWithText("75%").assertExists()
        rule.onNodeWithText("25%").assertExists()
        rule.onNodeWithText("300").assertExists()
        rule.onNodeWithText("100").assertExists()
    }

    @Test fun focusedBannerThatGoesAwayHandsFocusToTheList() {
        show(signedIn.copy(prediction = prediction(PredictionStatus.Resolved, winner = "a")))
        rule.onNodeWithTag("banner_prediction").focus()
        update { it.copy(prediction = null) }
        focused("chat_list")
    }

    // ---- banner -> prediction sheet ----

    @Test fun okOnTheBannerOpensThePredictionSheetAndBackReturnsToTheBanner() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("banner_prediction").focus()
        ok()
        exists("prediction_sheet")
        focused("prediction_outcome_0")
        back()
        gone("prediction_sheet")
        focused("banner_prediction")
        assertEquals(0, leaves)
    }

    @Test fun predictionFlowPicksAmountConfirmsAndSendsIt() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("banner_prediction").focus()
        ok()
        rule.onNodeWithTag("prediction_go").assertIsNotEnabled()
        ok() // OK on the first outcome selects it
        rule.onNodeWithTag("prediction_amount").assertTextEquals("100")
        rule.onNodeWithTag("prediction_inc").focus(); ok()
        rule.onNodeWithTag("prediction_amount").assertTextEquals("200")
        rule.onNodeWithTag("prediction_dec").focus(); ok(); ok()
        rule.onNodeWithTag("prediction_amount").assertTextEquals("90")
        rule.onNodeWithTag("prediction_go").focus(); ok()
        exists("prediction_confirm_text")
        focused("prediction_confirm")
        back()
        gone("prediction_confirm_text")
        rule.onNodeWithTag("prediction_go").focus(); ok()
        ok()
        assertEquals(listOf("a" to 90L), predicted)
        exists("prediction_success")
    }

    @Test fun amountCanNeverExceedTheBalance() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("banner_prediction").focus()
        ok()
        rule.onNodeWithTag("prediction_preset_Max").focus(); ok()
        rule.onNodeWithTag("prediction_amount").assertTextEquals("500")
        rule.onNodeWithTag("prediction_inc").focus(); ok(); ok()
        rule.onNodeWithTag("prediction_amount").assertTextEquals("500")
        rule.onNodeWithTag("prediction_preset_1k").assertDoesNotExist()
        rule.onNodeWithTag("prediction_preset_100").focus(); ok()
        rule.onNodeWithTag("prediction_amount").assertTextEquals("100")
    }

    @Test fun lockedPredictionShowsNoStakingControls() {
        show(signedIn.copy(prediction = prediction(PredictionStatus.Locked)))
        rule.onNodeWithTag("banner_prediction").focus()
        ok()
        exists("prediction_sheet")
        gone("prediction_go")
        gone("prediction_inc")
        focused("prediction_close")
        rule.onNodeWithTag("prediction_status").assertTextEquals("Locked")
    }

    @Test fun resolvedPredictionShowsWonOrLost() {
        show(signedIn.copy(prediction = prediction(PredictionStatus.Resolved, winner = "a", mine = "a", result = BetResult(BetOutcome.Won, 133))))
        rule.onNodeWithTag("banner_prediction_result", useUnmergedTree = true).assertTextEquals("You won +133")
        rule.onNodeWithTag("banner_prediction").focus(); ok()
        rule.onNodeWithTag("prediction_result").assertTextEquals("You won +133")
        update { it.copy(prediction = prediction(PredictionStatus.Resolved, winner = "a", mine = "b", result = BetResult(BetOutcome.Lost, 100))) }
        rule.onNodeWithTag("prediction_result").assertTextEquals("You lost 100")
    }

    @Test fun predictionSheetTrapsFocus() {
        show(signedIn.copy(prediction = prediction()))
        rule.onNodeWithTag("banner_prediction").focus()
        ok()
        press(Key.DirectionLeft); press(Key.DirectionRight)
        assertEquals(0, leaves)
        assertTrue(controller.hasFocus)
        rule.onNodeWithTag("prediction_close").focus()
        press(Key.DirectionUp)
        focused("prediction_close")
    }

    // ---- polls ----

    @Test fun pollBannerOpensTheVoteSheetAndVotesWithPointsOnlyWhenOffered() {
        show(signedIn.copy(poll = poll()))
        rule.onNodeWithTag("banner_poll").focus()
        ok()
        exists("poll_sheet")
        focused("poll_choice_0")
        ok()
        exists("poll_confirm_text")
        rule.onNodeWithTag("poll_vote_points").assertExists()
        rule.onNodeWithTag("poll_vote_points").focus(); ok()
        assertEquals(listOf("1" to true), voted)
        back(); back()
        gone("poll_sheet")
        focused("banner_poll")
    }

    @Test fun pollWithoutPointsVotesOffersOnlyTheFreeVoteAndNeverMentionsBits() {
        show(signedIn.copy(poll = poll(cost = null)))
        rule.onNodeWithTag("banner_poll").focus()
        ok(); ok()
        gone("poll_vote_points")
        rule.onNodeWithTag("poll_vote").assertExists()
        rule.onNodeWithText("Bits", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun endedPollShowsResultsAndNoVoting() {
        show(signedIn.copy(poll = poll(status = PollStatus.Completed)))
        rule.onNodeWithText("Poll results").assertExists()
        rule.onNodeWithTag("banner_poll").focus()
        ok()
        exists("poll_sheet")
        rule.onNodeWithTag("poll_status").assertTextEquals("Poll ended. Results:")
        focused("poll_close")
        rule.onNodeWithTag("poll_choice_0").focus(); ok()
        gone("poll_confirm_text")
    }

    @Test fun backFromPollSheetWithNoBannerLeftFallsBackToTheList() {
        show(signedIn.copy(poll = poll()))
        rule.onNodeWithTag("banner_poll").focus()
        ok()
        update { it.copy(poll = null) }
        back()
        gone("poll_sheet")
        focused("chat_list")
    }
}
