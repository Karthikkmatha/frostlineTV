package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementEventsTest {
    @Test fun topicsNameTheUserAndChannel() {
        assertEquals("community-points-user-v1.7", PointsEvents.topic("7"))
        assertEquals("predictions-channel-v1.9", PredictionEvents.topic("9"))
        assertEquals("polls.9", PollEvents.topic("9"))
    }

    @Test fun pointsEarnedCarriesTheNewBalance() {
        val e = PointsEvents.parse("""{"type":"points-earned","data":{"timestamp":"t","channel_id":"9","point_gain":{"total_points":50,"reason_code":"CLAIM"},"balance":{"user_id":"7","channel_id":"9","balance":1250}}}""")
        assertEquals(PointsEvent.Balance(1250, 50), e)
    }

    @Test fun claimAvailableAndPredictionLifecycle() {
        assertEquals(PointsEvent.ClaimAvailable("c1"), PointsEvents.parse("""{"type":"claim-available","data":{"claim":{"id":"c1","channel_id":"9"}}}"""))
        assertEquals(PointsEvent.PredictionMade("e", "o", 100), PointsEvents.parse("""{"type":"prediction-made","data":{"prediction":{"event_id":"e","outcome_id":"o","points":100}}}"""))
        val r = PointsEvents.parse("""{"type":"prediction-result","data":{"prediction":{"event_id":"e","outcome_id":"o","points":100,"result":{"type":"WIN","points_won":190}}}}""")
        assertEquals(PointsEvent.PredictionResult("e", "o", 100, "WIN", 190), r)
        assertNull(PointsEvents.parse("""{"type":"other","data":{}}"""))
        assertNull(PointsEvents.parse("garbage"))
    }

    @Test fun predictionEventParses() {
        val p = PredictionEvents.parse(
            """{"type":"event-updated","data":{"timestamp":"2026-10-22T12:00:30Z","event":{"id":"e1","title":"Win?","status":"ACTIVE","created_at":"2026-10-22T12:00:00Z","prediction_window_seconds":120,
            "outcomes":[{"id":"a","title":"Yes","color":"BLUE","total_points":300,"total_users":3},{"id":"b","title":"No","color":"PINK","total_points":100,"total_users":1}]}}}""",
        )!!
        assertEquals("e1", p.id)
        assertEquals(PredictionStatus.Active, p.status)
        assertEquals(120, p.windowSeconds)
        assertEquals(2, p.outcomes.size)
        assertEquals(300L, p.outcomes[0].totalPoints)
        assertEquals(30_000L, p.serverTimeMs!! - p.createdAtMs!!)
        assertNull(PredictionEvents.parse("""{"type":"nope","data":{}}"""))
    }

    @Test fun resolvedPredictionHasWinner() {
        val p = PredictionEvents.parse("""{"type":"event-updated","data":{"event":{"id":"e","title":"t","status":"RESOLVED","winning_outcome_id":"b","outcomes":[]}}}""")!!
        assertEquals(PredictionStatus.Resolved, p.status)
        assertEquals("b", p.winningOutcomeId)
    }

    @Test fun pollParsesPointsVotingAndIgnoresBits() {
        val p = PollEvents.parse(
            """{"type":"POLL_UPDATE","data":{"poll":{"poll_id":"p1","title":"Pick","status":"ACTIVE","duration_seconds":60,"started_at":"2026-10-22T12:00:00Z",
            "settings":{"bits_votes":{"is_enabled":true,"cost":10},"channel_points_votes":{"is_enabled":true,"cost":200}},
            "choices":[{"choice_id":"1","title":"A","votes":{"total":4,"bits":2}},{"choice_id":"2","title":"B","votes":{"total":1}}]}}}""",
        )!!
        assertEquals(200, p.pointsVoteCost)
        assertEquals(5L, p.totalVotes)
        assertEquals(PollStatus.Active, p.status)
        val none = PollEvents.parse("""{"type":"POLL_CREATE","data":{"poll":{"poll_id":"p","title":"t","status":"ACTIVE","settings":{"bits_votes":{"is_enabled":true,"cost":10}},"choices":[]}}}""")!!
        assertNull(none.pointsVoteCost)
        assertEquals(PollStatus.Completed, PollEvents.parse("""{"type":"POLL_COMPLETE","data":{"poll":{"poll_id":"p","title":"t","status":"COMPLETED","choices":[]}}}""")!!.status)
    }

    @Test fun subscribeFrameCarriesAuthOnlyWhenGiven() {
        val with = HermesProtocol.subscribe("community-points-user-v1.7", "n", "s", "t", "SECRET")
        assertTrue(with.contains("\"auth\":{\"type\":\"bearer\",\"key\":\"SECRET\"}"))
        assertFalse(HermesProtocol.subscribe("t", "n", "s", "ts").contains("auth"))
    }
}

class GqlEngagementApiTest {
    private fun withServer(vararg bodies: String, block: suspend (EngagementApi, MockWebServer) -> Unit) = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
            block(TwitchInternal.engagement(server.url("/gql").toString()), server)
        } finally {
            server.shutdown()
        }
    }

    private val pointsJson = """{"data":{"community":{"id":"9","channel":{"id":"9","communityPointsSettings":{"name":"Hype","image":{"url":"http://i/1.png","url2x":"http://i/2.png"}},"self":{"communityPoints":{"balance":4321,"availableClaim":{"id":"claim1"}}}}}}}"""

    @Test fun pointsContextParsesAndSendsOperationAndAuthHeaderOnly() = withServer(pointsJson) { api, server ->
        val p = api.pointsContext("chan", "TOKEN123")
        assertEquals(PointsInfo("9", 4321, "claim1", "Hype", "http://i/2.png"), p)
        val req = server.takeRequest()
        assertEquals("OAuth TOKEN123", req.getHeader("Authorization"))
        assertEquals(GqlWebSessionValidator.WEB_CLIENT_ID, req.getHeader("Client-Id"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"operationName\":\"ChannelPointsContext\""))
        assertTrue(body.contains("\"channelLogin\":\"chan\""))
        assertFalse(body.contains("TOKEN123"))
    }

    @Test fun noClaimMeansNullClaim() = withServer("""{"data":{"community":{"id":"9","channel":{"id":"9","self":{"communityPoints":{"balance":5}}}}}}""") { api, _ ->
        val p = api.pointsContext("c", "t")
        assertNull(p.availableClaimId)
        assertNull(p.pointsName)
    }

    @Test fun httpFailureReportsOnlyTheStatus() = withServer() { api, server ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret body"))
        val e = runCatching { api.pointsContext("c", "TOKEN") }.exceptionOrNull() as EngagementException
        assertEquals(401, e.httpStatus)
        assertEquals("HTTP 401", e.engagementDebugText())
        assertFalse(e.message!!.contains("TOKEN") || e.message!!.contains("secret"))
    }

    @Test fun gqlErrorMessagesAreSurfaced() = withServer("""{"errors":[{"message":"service timeout"}]}""") { api, _ ->
        val e = runCatching { api.pointsContext("c", "t") }.exceptionOrNull() as EngagementException
        assertEquals(listOf("service timeout"), e.gqlMessages)
        assertEquals("GQL: service timeout", e.engagementDebugText())
    }

    @Test fun schemaErrorFallsBackToTheSmallerQuery() = withServer(
        """{"errors":[{"message":"Cannot query field \"communityPointsSettings\" on type \"Channel\"."}]}""",
        pointsJson,
    ) { api, server ->
        assertEquals(4321L, api.pointsContext("c", "t").balance)
        assertEquals(2, server.requestCount)
    }

    @Test fun nonSchemaErrorDoesNotRetry() = withServer("""{"errors":[{"message":"unauthorized"}]}""", pointsJson) { api, server ->
        assertTrue(runCatching { api.pointsContext("c", "t") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun rewardsDropRealMoneyAndFreeItems() = withServer(
        """{"data":{"community":{"id":"9","channel":{"id":"9","communityPointsSettings":{"customRewards":[
        {"id":"r1","title":"Hydrate","prompt":"drink","cost":100,"isEnabled":true,"isInStock":true,"isPaused":false,"isUserInputRequired":false,"cooldownExpiresAt":"2026-10-22T12:00:00Z","image":{"url":"http://i/r.png"}},
        {"id":"r2","title":"Unlock with Bits","prompt":"","cost":500},
        {"id":"r3","title":"Gift a sub","prompt":"","cost":500},
        {"id":"r4","title":"Say something","prompt":"What?","cost":50,"isUserInputRequired":true,"isPaused":true,"isInStock":false},
        {"id":"r5","title":"Free","prompt":"","cost":0}]}}}}}""",
    ) { api, _ ->
        val r = api.rewards("c", "t")
        assertEquals(listOf("r1", "r4"), r.map { it.id })
        assertTrue(r[0].cooldownExpiresAtMs!! > 0)
        assertTrue(r[1].requiresInput && r[1].isPaused && !r[1].isInStock)
    }

    @Test fun rewardsReadEveryAvailabilityFieldAndTolerateNulls() = withServer(
        """{"data":{"community":{"id":"9","channel":{"id":"9","communityPointsSettings":{"customRewards":[
        {"id":"a","title":"Always on","prompt":"p","cost":200,"isEnabled":true,"isInStock":true,"isPaused":false,"isSubOnly":false,"isUserInputRequired":false,"cooldownExpiresAt":null,
         "maxPerStreamSetting":{"isEnabled":false,"maxPerStream":0},"maxPerUserPerStreamSetting":{"isEnabled":false,"maxPerUserPerStream":0},
         "globalCooldownSetting":{"isEnabled":false,"globalCooldownSeconds":0},"redemptionsRedeemedCurrentStream":null},
        {"id":"b","title":"Limited","prompt":"p","cost":300,"isEnabled":false,"isInStock":true,"isPaused":false,"isSubOnly":true,
         "maxPerStreamSetting":{"isEnabled":true,"maxPerStream":5},"maxPerUserPerStreamSetting":{"isEnabled":true,"maxPerUserPerStream":2},
         "globalCooldownSetting":{"isEnabled":true,"globalCooldownSeconds":600},"redemptionsRedeemedCurrentStream":4}]}}}}}""",
    ) { api, _ ->
        val (a, b) = api.rewards("c", "t")
        assertTrue(a.isEnabled && a.isInStock && !a.isPaused && !a.isSubOnly)
        assertNull(a.cooldownExpiresAtMs); assertNull(a.maxPerStream); assertNull(a.maxPerUserPerStream); assertNull(a.globalCooldownSeconds)
        assertEquals(0, a.redeemedThisStream)
        assertTrue(!b.isEnabled && b.isSubOnly)
        assertEquals(5, b.maxPerStream); assertEquals(2, b.maxPerUserPerStream); assertEquals(600, b.globalCooldownSeconds); assertEquals(4, b.redeemedThisStream)
    }

    @Test fun redeemSendsPointsPricingAndTextAndMapsErrors() = withServer("""{"data":{"redeemCommunityPointsCustomReward":{"error":{"code":"NOT_ENOUGH_POINTS"}}}}""") { api, server ->
        val reward = CustomReward("r", "Say", "p", 50, null, true, true, false, true, null, null, null, 0)
        val res = api.redeem("9", reward, "hello", "tx1", "t")
        assertEquals("NOT_ENOUGH_POINTS", res.errorCode)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"pricingType\":\"POINTS\""))
        assertTrue(body.contains("\"textInput\":\"hello\""))
        assertTrue(body.contains("\"transactionID\":\"tx1\""))
        assertTrue(body.contains("\"operationName\":\"RedeemCustomReward\""))
    }

    @Test fun redeemRefusesBitsRewardWithoutCallingTwitch() = withServer() { api, server ->
        val bits = CustomReward("r", "Unlock with Bits", "", 50, null, true, true, false, false, null, null, null, 0)
        assertEquals("NOT_OFFERED", api.redeem("9", bits, null, "tx", "t").errorCode)
        assertEquals(0, server.requestCount)
    }

    @Test fun claimOk() = withServer("""{"data":{"claimCommunityPoints":{"claim":{"id":"c1","pointsEarnedTotal":50}}}}""") { api, _ ->
        val r = api.claim("9", "c1", "t")
        assertTrue(r.ok)
        assertEquals(50L, r.pointsEarned)
    }

    @Test fun predictionsListActiveAndLocked() = withServer(
        """{"data":{"community":{"id":"9","channel":{"id":"9","activePredictionEvents":[{"id":"e1","title":"T","status":"ACTIVE","createdAt":"2026-10-22T12:00:00Z","predictionWindowSeconds":90,"outcomes":[{"id":"a","title":"Y","color":"BLUE","totalPoints":10,"totalUsers":1}]}],"lockedPredictionEvents":[]}}}}""",
    ) { api, _ ->
        val l = api.predictions("c", "t")
        assertEquals(1, l.size)
        assertEquals(90, l[0].windowSeconds)
        assertEquals(10L, l[0].outcomes[0].totalPoints)
    }

    @Test fun makePredictionVariables() = withServer("""{"data":{"makePrediction":{"error":null}}}""") { api, server ->
        assertTrue(api.predict("e1", "a", 250, "tx", "t").ok)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"eventID\":\"e1\"") && body.contains("\"outcomeID\":\"a\"") && body.contains("\"points\":250"))
    }

    @Test fun pollAndVote() = withServer(
        """{"data":{"channel":{"id":"9","viewablePoll":{"id":"p1","title":"Q","status":"ACTIVE","durationSeconds":60,"choices":[{"id":"c1","title":"A","votes":{"total":3}}],"settings":{"communityPointsVotes":{"isEnabled":true,"cost":100}}}}}}""",
        """{"data":{"voteInPoll":{"error":null}}}""",
    ) { api, server ->
        val p = api.poll("c", "t")!!
        assertEquals(100, p.pointsVoteCost)
        assertEquals(3L, p.totalVotes)
        assertTrue(api.vote("p1", "c1", "7", "v1", 100, "t").ok)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"channelPoints\":100"))
        assertFalse(body.contains("bits", ignoreCase = true))
    }

    @Test fun noPollIsNull() = withServer("""{"data":{"channel":{"id":"9","viewablePoll":null}}}""") { api, _ ->
        assertNull(api.poll("c", "t"))
    }

    @Test fun realMoneyFilter() {
        assertTrue(RealMoney.costsRealMoney("Cheer 100", ""))
        assertTrue(RealMoney.costsRealMoney("Emote unlock", "Unlock with Bits"))
        assertTrue(RealMoney.costsRealMoney("Gift 5 subs", "gift a sub to chat"))
        assertFalse(RealMoney.costsRealMoney("Hydrate", "Drink water"))
        assertFalse(RealMoney.costsRealMoney("Substitute my name", ""))
    }
}
