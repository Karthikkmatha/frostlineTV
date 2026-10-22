package tv.frostline.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.twitchinternal.ActionResult
import tv.frostline.core.twitchinternal.CustomReward
import tv.frostline.core.twitchinternal.EngagementApi
import tv.frostline.core.twitchinternal.EngagementException
import tv.frostline.core.twitchinternal.HermesClient
import tv.frostline.core.twitchinternal.HermesEvent
import tv.frostline.core.twitchinternal.PointsInfo
import tv.frostline.core.twitchinternal.PollChoice
import tv.frostline.core.twitchinternal.PollInfo
import tv.frostline.core.twitchinternal.PollStatus
import tv.frostline.core.twitchinternal.PredictionEvent
import tv.frostline.core.twitchinternal.PredictionOutcome
import tv.frostline.core.twitchinternal.PredictionStatus
import java.time.Instant

class EngagementMathTest {
    @Test fun percentAndCompact() {
        assertEquals(0, EngagementMath.percent(5, 0))
        assertEquals(63, EngagementMath.percent(5, 8))
        assertEquals("999", EngagementMath.compact(999))
        assertEquals("1.2K", EngagementMath.compact(1_234))
        assertEquals("1K", EngagementMath.compact(1_000))
        assertEquals("12K", EngagementMath.compact(12_345))
        assertEquals("1.5M", EngagementMath.compact(1_500_000))
        assertEquals("12,345", EngagementMath.grouped(12_345))
        assertEquals("1:05", EngagementMath.clock(65_000))
        assertEquals("0:00", EngagementMath.clock(-5))
    }

    @Test fun amountNeverExceedsBalanceOrTwitchCap() {
        assertEquals(0L, EngagementMath.clampAmount(100, 5))
        assertEquals(50L, EngagementMath.clampAmount(100, 50))
        assertEquals(10L, EngagementMath.clampAmount(1, 500))
        assertEquals(250_000L, EngagementMath.maxAmount(9_000_000))
        assertEquals(50L, EngagementMath.increase(40, 50))
        assertEquals(50L, EngagementMath.increase(50, 50))
        assertEquals(10L, EngagementMath.decrease(10, 500))
        assertEquals(90L, EngagementMath.decrease(100, 500))
        assertEquals(200L, EngagementMath.increase(100, 500))
    }

    @Test fun presetsAreTenHundredThousandMaxAndStayWithinBalance() {
        assertEquals(listOf("10", "100", "1k", "Max"), EngagementMath.presets(5_000).map { it.first })
        assertEquals(5_000L, EngagementMath.presets(5_000).last().second)
        assertEquals(listOf("10", "Max"), EngagementMath.presets(50).map { it.first })
        assertEquals(listOf("Max"), EngagementMath.presets(10).map { it.first })
        assertTrue(EngagementMath.presets(9).isEmpty())
        assertTrue(EngagementMath.presets(1_000_000).all { it.second <= 250_000 })
    }

    private fun reward(
        cost: Long = 100, enabled: Boolean = true, stock: Boolean = true, paused: Boolean = false,
        cooldown: Long? = null, perUser: Int? = null, used: Int = 0,
    ) = CustomReward("r", "t", "p", cost, null, enabled, stock, paused, false, cooldown, null, perUser, used)

    @Test fun rewardsSayWhyTheyAreBlocked() {
        assertNull(EngagementMath.rewardBlock(reward(), 500, 0))
        assertEquals("Need 50 more", EngagementMath.rewardBlock(reward(cost = 150), 100, 0))
        assertEquals("Paused by the streamer", EngagementMath.rewardBlock(reward(paused = true), 500, 0))
        assertEquals("Out of stock", EngagementMath.rewardBlock(reward(stock = false), 500, 0))
        assertEquals("Unavailable", EngagementMath.rewardBlock(reward(enabled = false), 500, 0))
        assertEquals("On cooldown, 1:00", EngagementMath.rewardBlock(reward(cooldown = 60_000), 500, 0))
        assertNull(EngagementMath.rewardBlock(reward(cooldown = 1_000), 500, 2_000))
        assertEquals("Limit reached this stream", EngagementMath.rewardBlock(reward(perUser = 1, used = 1), 500, 0))
    }
}

private class FakeHermes : HermesClient {
    val bus = MutableSharedFlow<HermesEvent>(extraBufferCapacity = 64)
    val asked = mutableListOf<List<String>>()
    var auth: String? = null
    override fun events(topics: List<String>): Flow<HermesEvent> { asked += topics; return bus.filter { it.topic in topics } }
    override fun events(topics: List<String>, authToken: String?): Flow<HermesEvent> { auth = authToken; return events(topics) }
    suspend fun emit(topic: String, payload: String) = bus.emit(HermesEvent(topic, payload))
}

private class FakeApi : EngagementApi {
    var points: PointsInfo? = PointsInfo("9", 1_000, null, "Hype", "http://icon")
    var pointsError: Throwable? = null
    var pointsCalls = 0
    var rewards = emptyList<CustomReward>()
    var claimResult = ActionResult(null, 50)
    val claims = mutableListOf<String>()
    var redeemResult = ActionResult()
    val redeems = mutableListOf<Triple<String, Long, String?>>()
    var predictions = emptyList<PredictionEvent>()
    var predictResult = ActionResult()
    val predicts = mutableListOf<Triple<String, String, Long>>()
    var poll: PollInfo? = null
    var voteResult = ActionResult()
    val votes = mutableListOf<Pair<String, Int?>>()

    override suspend fun pointsContext(login: String, authToken: String): PointsInfo {
        pointsCalls++
        pointsError?.let { throw it }
        return points!!
    }
    override suspend fun rewards(login: String, authToken: String) = rewards
    override suspend fun claim(channelId: String, claimId: String, authToken: String): ActionResult { claims += claimId; return claimResult }
    override suspend fun redeem(channelId: String, reward: CustomReward, textInput: String?, transactionId: String, authToken: String): ActionResult {
        redeems += Triple(reward.id, reward.cost, textInput); return redeemResult
    }
    override suspend fun predictions(login: String, authToken: String) = predictions
    override suspend fun predict(eventId: String, outcomeId: String, points: Long, transactionId: String, authToken: String): ActionResult {
        predicts += Triple(eventId, outcomeId, points); return predictResult
    }
    override suspend fun poll(login: String, authToken: String) = poll
    override suspend fun vote(pollId: String, choiceId: String, userId: String, voteId: String, pointsCost: Int?, authToken: String): ActionResult {
        votes += choiceId to pointsCost; return voteResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementEngineTest {
    private val base = 1_800_000_000_000L
    private val api = FakeApi()
    private val hermes = FakeHermes()
    private val auto = MutableStateFlow(true)

    private fun TestScope.engine(signedIn: Boolean = true) = EngagementEngine(
        scope = backgroundScope, api = api, hermes = hermes,
        session = { if (signedIn) EngagementSession("tok", "7") else null },
        autoClaim = auto, channelIdOf = { "9" },
        clock = { base + testScheduler.currentTime },
        pollEveryMs = 30_000, hermesFreshMs = 90_000, endedShownMs = 15_000, toastMs = 3_000,
        newId = { "tx" },
    )

    private fun iso(offsetMs: Long = 0) = Instant.ofEpochMilli(base + offsetMs).toString()

    private fun predictionPayload(status: String, window: Int = 120, winner: String? = null, a: Long = 300, b: Long = 100, id: String = "e1") =
        """{"type":"event-updated","data":{"timestamp":"${iso()}","event":{"id":"$id","title":"Will he win?","status":"$status","created_at":"${iso()}","prediction_window_seconds":$window,${if (winner != null) "\"winning_outcome_id\":\"$winner\"," else ""}"outcomes":[{"id":"a","title":"Yes","color":"BLUE","total_points":$a,"total_users":3},{"id":"b","title":"No","color":"PINK","total_points":$b,"total_users":1}]}}}"""

    private fun pollPayload(type: String = "POLL_UPDATE", status: String = "ACTIVE", cost: Int = 100, a: Int = 3, b: Int = 1) =
        """{"type":"$type","data":{"timestamp":"${iso()}","poll":{"poll_id":"p1","title":"Pick one","status":"$status","duration_seconds":60,"started_at":"${iso()}","settings":{"bits_votes":{"is_enabled":true,"cost":10},"channel_points_votes":{"is_enabled":true,"cost":$cost}},"choices":[{"choice_id":"1","title":"A","votes":{"total":$a}},{"choice_id":"2","title":"B","votes":{"total":$b}}]}}}"""

    @Test fun guestsGetNothingAndNoRequestsAreMade() = runTest {
        val e = engine(signedIn = false)
        e.setChannel("chan")
        runCurrent()
        assertEquals(EngagementUi(), e.ui.value)
        assertEquals(0, api.pointsCalls)
        assertTrue(hermes.asked.isEmpty())
    }

    @Test fun guestDebugLinesAreAlwaysFiveAndSayWhyHidden() = runTest {
        val e = EngagementEngine(backgroundScope, api, hermes, { null }, { "no web session linked" }, auto, { "9" })
        assertEquals(listOf("points", "rewards", "prediction", "poll", "hermes"), e.debug.value.map { it.trim().substringBefore(" ") })
        e.setChannel("chan"); runCurrent()
        assertEquals(5, e.debug.value.size)
        assertTrue(e.debug.value.all { it.endsWith("hidden: no web session linked") })
    }

    @Test fun sessionWithoutWebTokenStaysHiddenButWithOneIsVisible() = runTest {
        assertNull(resolveEngagementSession(null, true, "7") { "7" })
        assertNull(resolveEngagementSession("", true, "7") { "7" })
        assertNull(resolveEngagementSession("web", false, "7") { "7" })
        assertNull(resolveEngagementSession("web", true, null) { null })
        assertEquals("7", resolveEngagementSession("web", true, null) { "7" }!!.userId)
        assertEquals("tok", resolveEngagementSession("tok", true, "7") { null }!!.authToken)
    }

    @Test fun signedInPathFromSessionToRewardsButtonCondition() = runTest {
        val s = resolveEngagementSession("tok", true, null) { "7" }
        val e = EngagementEngine(backgroundScope, api, hermes, { s }, { "n/a" }, auto, { "9" })
        e.setChannel("chan"); runCurrent()
        val u = e.ui.value
        // The panel shows Rewards when available and rewards have not failed.
        assertTrue(u.available && u.rewardsLoad != RewardsLoad.Failed)
        assertEquals(1_000L, u.points!!.balance)
        assertEquals("ok", e.debug.value.first { it.startsWith("points") }.removePrefix("points").trim())
        assertTrue(e.debug.value.first { it.startsWith("hermes") }.endsWith("connecting"))
    }

    @Test fun showsBalanceWithChannelNameAndIcon() = runTest {
        val e = engine()
        e.setChannel("chan")
        runCurrent()
        val u = e.ui.value
        assertTrue(u.available)
        assertEquals(PointsUi(1_000, "Hype", "http://icon"), u.points)
        assertEquals("tok", hermes.auth)
        assertTrue(hermes.asked.any { it == listOf("community-points-user-v1.7") })
        assertTrue(hermes.asked.any { it == listOf("predictions-channel-v1.9") })
        assertTrue(hermes.asked.any { it == listOf("polls.9") })
    }

    @Test fun hermesBalanceUpdatesLive() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("community-points-user-v1.7", """{"type":"points-earned","data":{"point_gain":{"total_points":10},"balance":{"balance":1010}}}""")
        runCurrent()
        assertEquals(1_010L, e.ui.value.points!!.balance)
    }

    @Test fun pollingFallbackKicksInWhenHermesIsQuiet() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        api.points = api.points!!.copy(balance = 1_300)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(1_300L, e.ui.value.points!!.balance)
    }

    @Test fun pollingStaysQuietWhileHermesIsFresh() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        advanceTimeBy(20_000)
        hermes.emit("community-points-user-v1.7", """{"type":"points-earned","data":{"balance":{"balance":1050}}}""")
        runCurrent()
        val calls = api.pointsCalls
        api.points = api.points!!.copy(balance = 9)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(calls, api.pointsCalls)
        assertEquals(1_050L, e.ui.value.points!!.balance)
    }

    @Test fun autoClaimsTheChestAndToastsTheGain() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("community-points-user-v1.7", """{"type":"claim-available","data":{"claim":{"id":"c1"}}}""")
        runCurrent()
        assertEquals(listOf("c1"), api.claims)
        assertEquals("+50", e.ui.value.toast)
        advanceTimeBy(3_100); runCurrent()
        assertNull(e.ui.value.toast)
    }

    @Test fun claimAlreadyWaitingAtStartIsClaimedOnce() = runTest {
        api.points = api.points!!.copy(availableClaimId = "c0")
        val e = engine()
        e.setChannel("chan"); runCurrent()
        assertEquals(listOf("c0"), api.claims)
    }

    @Test fun autoClaimOffLeavesTheChest() = runTest {
        auto.value = false
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("community-points-user-v1.7", """{"type":"claim-available","data":{"claim":{"id":"c1"}}}""")
        runCurrent()
        assertTrue(api.claims.isEmpty())
        auto.value = true
        runCurrent()
        assertEquals(listOf("c1"), api.claims)
    }

    @Test fun failedContextHidesPointsButReportsOnlyTheClassOrStatus() = runTest {
        api.pointsError = EngagementException("HTTP 401", httpStatus = 401)
        val e = engine()
        e.setChannel("chan"); runCurrent()
        assertFalse(e.ui.value.available)
        assertEquals("points     HTTP 401", e.debug.value.first { it.startsWith("points") })
        assertEquals(5, e.debug.value.size)
    }

    private val chatty = CustomReward("r1", "Say hi", "Type", 200, null, true, true, false, true, null, null, null, 0)

    @Test fun redeemSendsTextAndUpdatesBalance() = runTest {
        api.rewards = listOf(chatty)
        val e = engine()
        e.setChannel("chan"); runCurrent()
        e.loadRewards(); runCurrent()
        assertEquals(RewardsLoad.Ready, e.ui.value.rewardsLoad)
        e.redeem(chatty, " hello "); runCurrent()
        assertEquals(listOf(Triple("r1", 200L, "hello")), api.redeems)
        assertTrue(e.ui.value.redeem is ActionState.Done)
        assertEquals(800L, e.ui.value.points!!.balance)
    }

    @Test fun redeemErrorIsPutInWords() = runTest {
        api.redeemResult = ActionResult("NOT_ENOUGH_POINTS")
        val e = engine()
        e.setChannel("chan"); runCurrent()
        e.redeem(chatty, "x"); runCurrent()
        assertEquals(ActionState.Failed("Not enough points."), e.ui.value.redeem)
        assertEquals(1_000L, e.ui.value.points!!.balance)
    }

    @Test fun activePredictionShowsTimeAndOutcomes() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("ACTIVE"))
        runCurrent()
        val p = e.ui.value.prediction!!
        assertEquals("Will he win?", p.title)
        assertTrue(p.open)
        assertEquals(base + 120_000, p.endsAtMs)
        assertEquals(75, EngagementMath.percent(p.outcomes[0].totalPoints, p.totalPoints))
    }

    @Test fun initialGqlPredictionIsShownToo() = runTest {
        api.predictions = listOf(PredictionEvent("e0", "Early", PredictionStatus.Locked, listOf(PredictionOutcome("a", "Y", null, 1, 1)), 60, base, null, null))
        val e = engine()
        e.setChannel("chan"); runCurrent()
        assertEquals("Early", e.ui.value.prediction!!.title)
        assertEquals(PredictionStatus.Locked, e.ui.value.prediction!!.status)
    }

    @Test fun predictSendsTheStakeAndKeepsItBelowTheBalance() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("ACTIVE")); runCurrent()
        e.predict("a", 5_000); runCurrent()
        assertTrue(api.predicts.isEmpty())
        assertEquals(ActionState.Failed("Not enough points."), e.ui.value.predictionAction)
        e.resetPrediction()
        e.predict("a", 400); runCurrent()
        assertEquals(listOf(Triple("e1", "a", 400L)), api.predicts)
        assertEquals(600L, e.ui.value.points!!.balance)
        assertEquals("a", e.ui.value.prediction!!.myOutcomeId)
        assertEquals(400L, e.ui.value.prediction!!.myPoints)
        // Switching sides after betting is refused locally.
        e.resetPrediction()
        e.predict("b", 10); runCurrent()
        assertEquals(1, api.predicts.size)
    }

    @Test fun lockedPredictionRefusesNewStakes() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("LOCKED")); runCurrent()
        e.predict("a", 100); runCurrent()
        assertTrue(api.predicts.isEmpty())
        assertEquals(ActionState.Failed("Predictions are closed."), e.ui.value.predictionAction)
    }

    @Test fun resolvedShowsWinOrLossForTheViewerAndThenGoesAway() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("ACTIVE")); runCurrent()
        e.predict("b", 100); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("RESOLVED", winner = "a")); runCurrent()
        val p = e.ui.value.prediction!!
        assertEquals(BetResult(BetOutcome.Lost, 100), p.result)
        assertEquals("You lost 100", betResultText(p.result))
        advanceTimeBy(15_100); runCurrent()
        assertNull(e.ui.value.prediction)
    }

    @Test fun userTopicResultOverridesWithPointsWon() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("ACTIVE")); runCurrent()
        hermes.emit("community-points-user-v1.7", """{"type":"prediction-made","data":{"prediction":{"event_id":"e1","outcome_id":"a","points":100}}}"""); runCurrent()
        assertEquals("a", e.ui.value.prediction!!.myOutcomeId)
        hermes.emit("predictions-channel-v1.9", predictionPayload("RESOLVED", winner = "a")); runCurrent()
        hermes.emit("community-points-user-v1.7", """{"type":"prediction-result","data":{"prediction":{"event_id":"e1","outcome_id":"a","points":100,"result":{"type":"WIN","points_won":133}}}}"""); runCurrent()
        assertEquals(BetResult(BetOutcome.Won, 133), e.ui.value.prediction!!.result)
        assertEquals("You won +133", betResultText(e.ui.value.prediction!!.result))
    }

    @Test fun canceledRefundsTheStake() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("ACTIVE")); runCurrent()
        e.predict("a", 50); runCurrent()
        hermes.emit("predictions-channel-v1.9", predictionPayload("CANCELED")); runCurrent()
        assertEquals(BetResult(BetOutcome.Refunded, 50), e.ui.value.prediction!!.result)
    }

    @Test fun pollShowsVotesAndVotesWithPointsOnlyWhenOffered() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("polls.9", pollPayload()); runCurrent()
        val p = e.ui.value.poll!!
        assertEquals(100, p.pointsVoteCost)
        assertEquals(4L, p.totalVotes)
        e.vote("1", true); runCurrent()
        assertEquals(listOf("1" to 100), api.votes)
        assertEquals(900L, e.ui.value.points!!.balance)
        assertEquals("1", e.ui.value.poll!!.myChoiceId)
        e.resetPoll()
        e.vote("2", false); runCurrent()
        assertEquals("2" to null, api.votes.last())
    }

    @Test fun pollWithoutPointsVotingRefusesPaidVotes() = runTest {
        api.poll = PollInfo("p1", "Q", PollStatus.Active, listOf(PollChoice("1", "A", 0)), 60, base, null, null)
        val e = engine()
        e.setChannel("chan"); runCurrent()
        assertNull(e.ui.value.poll!!.pointsVoteCost)
        e.vote("1", true); runCurrent()
        assertTrue(api.votes.isEmpty())
        assertTrue(e.ui.value.pollAction is ActionState.Failed)
    }

    @Test fun pollResultsStayThenDisappear() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("polls.9", pollPayload()); runCurrent()
        hermes.emit("polls.9", pollPayload("POLL_COMPLETE", "COMPLETED", a = 7, b = 2)); runCurrent()
        assertTrue(e.ui.value.poll!!.ended)
        assertEquals(9L, e.ui.value.poll!!.totalVotes)
        advanceTimeBy(15_100); runCurrent()
        assertNull(e.ui.value.poll)
    }

    @Test fun changingChannelClearsEverything() = runTest {
        val e = engine()
        e.setChannel("chan"); runCurrent()
        hermes.emit("polls.9", pollPayload()); runCurrent()
        assertNotNull(e.ui.value.poll)
        e.setChannel("other")
        assertNull(e.ui.value.poll)
        assertNull(e.ui.value.points)
        assertFalse(e.ui.value.available)
    }
}
