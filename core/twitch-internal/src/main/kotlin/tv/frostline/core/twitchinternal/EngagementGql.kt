package tv.frostline.core.twitchinternal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.frostline.core.twitchinternal.J.arr
import tv.frostline.core.twitchinternal.J.bool
import tv.frostline.core.twitchinternal.J.instantMs
import tv.frostline.core.twitchinternal.J.int
import tv.frostline.core.twitchinternal.J.long
import tv.frostline.core.twitchinternal.J.obj
import tv.frostline.core.twitchinternal.J.str
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Channel points, custom rewards, predictions and polls over Twitch's web GQL. All calls need the viewer's
 * web session (`auth-token` cookie value) as [authToken]; it is sent only as the Authorization header.
 * Failures throw [EngagementException]; callers hide the feature.
 */
interface EngagementApi {
    suspend fun pointsContext(login: String, authToken: String): PointsInfo
    suspend fun rewards(login: String, authToken: String): List<CustomReward>
    suspend fun claim(channelId: String, claimId: String, authToken: String): ActionResult
    suspend fun redeem(channelId: String, reward: CustomReward, textInput: String?, transactionId: String, authToken: String): ActionResult

    /** Active and locked predictions of the channel. */
    suspend fun predictions(login: String, authToken: String): List<PredictionEvent>
    suspend fun predict(eventId: String, outcomeId: String, points: Long, transactionId: String, authToken: String): ActionResult

    /** The user id of the web session itself (it can differ from the Helix sign-in); null when unknown. Hermes topics need this one. */
    suspend fun webUserId(authToken: String): String? = null

    /** The poll the viewer can currently see, or null. */
    suspend fun poll(login: String, authToken: String): PollInfo?

    /** [pointsCost] null = the free vote; otherwise the extra vote paid with that many channel points. */
    suspend fun vote(pollId: String, choiceId: String, userId: String, voteId: String, pointsCost: Int?, authToken: String): ActionResult
}

/** Rewards that would cost real money (Bits, subs, gifts) never reach the UI, whatever Twitch sends. */
object RealMoney {
    private val pattern = Regex(
        "\\bbits?\\b|\\bcheer(s|ing|ed)?\\b|\\bgift(s|ed|ing)?\\s+(a\\s+|some\\s+)?sub|\\bsub(scription)?s?\\s+gift|\\bgift\\s+subs?\\b|real money|\\$\\s?\\d",
        RegexOption.IGNORE_CASE,
    )

    fun costsRealMoney(title: String, prompt: String): Boolean = pattern.containsMatchIn(title) || pattern.containsMatchIn(prompt)
}

internal object EngagementParser {
    fun gqlErrors(root: JsonObject): List<String> =
        root["errors"].arr().orEmpty().map { it.obj()?.get("message").str() ?: "error" }

    private fun user(root: JsonObject, key: String) = root["data"].obj()?.get(key).obj()

    fun points(root: JsonObject): PointsInfo {
        val community = user(root, "community") ?: throw EngagementException("Channel not found")
        val channel = community["channel"].obj()
        val id = channel?.get("id").str() ?: community["id"].str() ?: throw EngagementException("Channel not found")
        val cp = channel?.get("self").obj()?.get("communityPoints").obj() ?: throw EngagementException("No channel points here")
        val settings = channel?.get("communityPointsSettings").obj()
        val img = settings?.get("image").obj()
        return PointsInfo(
            channelId = id,
            balance = cp["balance"].long() ?: 0,
            availableClaimId = cp["availableClaim"].obj()?.get("id").str(),
            pointsName = settings?.get("name").str()?.trim()?.ifEmpty { null },
            iconUrl = img?.get("url2x").str() ?: img?.get("url").str(),
        )
    }

    fun rewards(root: JsonObject): List<CustomReward> {
        val community = user(root, "community") ?: throw EngagementException("Channel not found")
        val list = community["channel"].obj()?.get("communityPointsSettings").obj()?.get("customRewards").arr() ?: return emptyList()
        return list.mapNotNull { e ->
            val r = e.obj() ?: return@mapNotNull null
            val id = r["id"].str() ?: return@mapNotNull null
            val title = r["title"].str().orEmpty()
            val prompt = r["prompt"].str().orEmpty()
            val cost = r["cost"].long() ?: return@mapNotNull null
            if (cost <= 0 || RealMoney.costsRealMoney(title, prompt)) return@mapNotNull null
            val image = r["image"].obj() ?: r["defaultImage"].obj()
            val perStream = r["maxPerStreamSetting"].obj()?.takeIf { it["isEnabled"].bool() == true }?.get("maxPerStream").int()
            val perUser = r["maxPerUserPerStreamSetting"].obj()?.takeIf { it["isEnabled"].bool() == true }?.get("maxPerUserPerStream").int()
            CustomReward(
                id = id, title = title, prompt = prompt, cost = cost,
                iconUrl = image?.get("url2x").str() ?: image?.get("url").str(),
                isEnabled = r["isEnabled"].bool() ?: true,
                isInStock = r["isInStock"].bool() ?: true,
                isPaused = r["isPaused"].bool() ?: false,
                requiresInput = r["isUserInputRequired"].bool() ?: false,
                cooldownExpiresAtMs = instantMs(r["cooldownExpiresAt"].str()),
                maxPerStream = perStream,
                maxPerUserPerStream = perUser,
                redeemedThisStream = r["redemptionsRedeemedCurrentStream"].int() ?: 0,
            )
        }
    }

    /** Reads `data.<field>.error.code` (and `claim.pointsEarnedTotal` when present). */
    fun action(root: JsonObject, field: String): ActionResult {
        val node = root["data"].obj()?.get(field).obj() ?: throw EngagementException("Unexpected answer", gqlMessages = gqlErrors(root))
        val code = node["error"].obj()?.get("code").str()
        return ActionResult(code, node["claim"].obj()?.get("pointsEarnedTotal").long())
    }

    private fun predictionNode(o: JsonObject): PredictionEvent? {
        val id = o["id"].str() ?: return null
        val outcomes = o["outcomes"].arr().orEmpty().mapNotNull { x ->
            val oo = x.obj() ?: return@mapNotNull null
            PredictionOutcome(oo["id"].str() ?: return@mapNotNull null, oo["title"].str().orEmpty(), oo["color"].str(), oo["totalPoints"].long() ?: 0, oo["totalUsers"].long() ?: 0)
        }
        val status = when (o["status"].str()?.uppercase()) {
            "ACTIVE" -> PredictionStatus.Active
            "LOCKED" -> PredictionStatus.Locked
            "RESOLVE_PENDING" -> PredictionStatus.ResolvePending
            "RESOLVED" -> PredictionStatus.Resolved
            "CANCELED" -> PredictionStatus.Canceled
            else -> PredictionStatus.Unknown
        }
        return PredictionEvent(id, o["title"].str().orEmpty(), status, outcomes, o["predictionWindowSeconds"].int() ?: 0, instantMs(o["createdAt"].str()), null, o["winningOutcome"].obj()?.get("id").str())
    }

    fun predictions(root: JsonObject): List<PredictionEvent> {
        val channel = user(root, "community")?.get("channel").obj() ?: return emptyList()
        return listOf("activePredictionEvents", "lockedPredictionEvents").flatMap { k ->
            channel[k].arr().orEmpty().mapNotNull { it.obj()?.let(::predictionNode) }
        }
    }

    fun poll(root: JsonObject): PollInfo? {
        val p = user(root, "channel")?.get("viewablePoll").obj() ?: return null
        val id = p["id"].str() ?: return null
        val choices = p["choices"].arr().orEmpty().mapNotNull { c ->
            val co = c.obj() ?: return@mapNotNull null
            PollChoice(co["id"].str() ?: return@mapNotNull null, co["title"].str().orEmpty(), co["votes"].obj()?.get("total").long() ?: co["totalVoters"].long() ?: 0)
        }
        val cp = p["settings"].obj()?.get("communityPointsVotes").obj()
        val cost = if (cp?.get("isEnabled").bool() == true) cp?.get("cost").int()?.takeIf { it > 0 } else null
        val status = when (p["status"].str()?.uppercase()) {
            "ACTIVE" -> PollStatus.Active
            "COMPLETED" -> PollStatus.Completed
            "TERMINATED" -> PollStatus.Terminated
            "ARCHIVED" -> PollStatus.Archived
            else -> PollStatus.Unknown
        }
        return PollInfo(id, p["title"].str().orEmpty(), status, choices, p["durationSeconds"].int() ?: 0, instantMs(p["startedAt"].str()), null, cost)
    }
}

internal class GqlEngagementApi(
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    /** Integrity token + device id captured from the web page; sent with every call. */
    private val integrity: IntegrityProvider = IntegrityProvider.None,
) : EngagementApi {

    override suspend fun webUserId(authToken: String): String? = runCatching {
        post("CurrentUser", "query CurrentUser { currentUser { id } }", buildJsonObject { }, authToken, false)["data"].obj()?.get("currentUser").obj()?.get("id").str()
    }.getOrNull()

    override suspend fun pointsContext(login: String, authToken: String): PointsInfo =
        EngagementParser.points(run("ChannelPointsContext", POINTS_QUERIES, buildJsonObject { put("channelLogin", login) }, authToken))

    override suspend fun rewards(login: String, authToken: String): List<CustomReward> =
        EngagementParser.rewards(run("ChannelPointsRewards", REWARDS_QUERIES, buildJsonObject { put("channelLogin", login) }, authToken))

    override suspend fun claim(channelId: String, claimId: String, authToken: String): ActionResult =
        EngagementParser.action(
            run("ClaimCommunityPoints", CLAIM_QUERIES, input { put("channelID", channelId); put("claimID", claimId) }, authToken),
            "claimCommunityPoints",
        )

    override suspend fun redeem(channelId: String, reward: CustomReward, textInput: String?, transactionId: String, authToken: String): ActionResult {
        // Defence in depth: this client can only ever pay with channel points.
        if (RealMoney.costsRealMoney(reward.title, reward.prompt)) return ActionResult("NOT_OFFERED")
        return EngagementParser.action(
            run(
                "RedeemCustomReward", listOf(REDEEM),
                input {
                    put("channelID", channelId)
                    put("cost", reward.cost)
                    put("pricingType", "POINTS")
                    put("prompt", reward.prompt)
                    put("rewardID", reward.id)
                    if (textInput != null) put("textInput", textInput)
                    put("title", reward.title)
                    put("transactionID", transactionId)
                },
                authToken,
            ),
            "redeemCommunityPointsCustomReward",
        )
    }

    override suspend fun predictions(login: String, authToken: String): List<PredictionEvent> =
        EngagementParser.predictions(run("ChannelPointsPredictionContext", PREDICTION_QUERIES, buildJsonObject { put("channelLogin", login) }, authToken))

    override suspend fun predict(eventId: String, outcomeId: String, points: Long, transactionId: String, authToken: String): ActionResult =
        EngagementParser.action(
            run("MakePrediction", listOf(MAKE_PREDICTION), input { put("eventID", eventId); put("outcomeID", outcomeId); put("points", points); put("transactionID", transactionId) }, authToken),
            "makePrediction",
        )

    override suspend fun poll(login: String, authToken: String): PollInfo? =
        EngagementParser.poll(run("ChannelPollContext_GetViewablePoll", POLL_QUERIES, buildJsonObject { put("login", login) }, authToken))

    override suspend fun vote(pollId: String, choiceId: String, userId: String, voteId: String, pointsCost: Int?, authToken: String): ActionResult =
        EngagementParser.action(
            run(
                "ChannelPollContext_VoteInPoll", listOf(VOTE),
                input {
                    put("pollID", pollId); put("choiceID", choiceId); put("userID", userId); put("voteID", voteId)
                    if (pointsCost != null) put("tokens", buildJsonObject { put("channelPoints", pointsCost) }) else put("tokens", JsonNull)
                },
                authToken,
            ),
            "voteInPoll",
        )

    private fun input(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("input", buildJsonObject(block)) }

    /**
     * Posts the first query that Twitch's schema accepts. A later (smaller) variant is tried only when Twitch
     * answers with a schema validation error, never after an auth or runtime error.
     */
    private suspend fun run(operation: String, queries: List<String>, variables: JsonObject, authToken: String): JsonObject {
        var last: EngagementException? = null
        for (q in queries) {
            var root = post(operation, q, variables, authToken, false)
            var errors = EngagementParser.gqlErrors(root)
            if (IntegrityTokens.isIntegrityError(errors)) { // refresh the credentials once and retry
                root = post(operation, q, variables, authToken, true)
                errors = EngagementParser.gqlErrors(root)
            }
            if (errors.isEmpty()) return root
            // A mutation that returned data plus a soft error still counts as an answer.
            if (root["data"].obj()?.values?.any { it !is JsonNull } == true && errors.none(::isSchemaError)) return root
            last = EngagementException("GQL error", gqlMessages = errors)
            if (!errors.any(::isSchemaError)) break
        }
        throw last ?: EngagementException("Empty answer")
    }

    private fun isSchemaError(m: String) =
        m.contains("Cannot query field", true) || m.contains("Unknown type", true) || m.contains("Unknown argument", true) || m.contains("validation", true)

    private suspend fun post(operation: String, query: String, variables: JsonObject, authToken: String, refreshIntegrity: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val creds = runCatching { integrity.current(refreshIntegrity) }.getOrNull()
        val body = buildJsonObject {
            put("operationName", operation)
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(gqlUrl)
            .header("Client-Id", creds?.clientId ?: GqlWebSessionValidator.WEB_CLIENT_ID)
            .header("Authorization", "OAuth $authToken")
            .apply {
                if (creds != null) {
                    header("Client-Integrity", creds.token)
                    header("X-Device-Id", creds.deviceId)
                }
            }
            .post(body)
            .build()
        val text = try {
            client.newCall(req).execute().use { r ->
                if (r.code !in 200..299) throw EngagementException("HTTP ${r.code}", httpStatus = r.code)
                r.body.string()
            }
        } catch (e: EngagementException) {
            throw e
        } catch (e: IOException) {
            // Message text of a network exception can carry a URL; keep only the class.
            throw EngagementException(e.javaClass.simpleName)
        }
        J.parse(text) ?: throw EngagementException("Unreadable answer")
    }

    companion object {
        private const val PE_FULL = "id title status createdAt lockedAt predictionWindowSeconds outcomes { id title color totalPoints totalUsers }"
        private const val PE_CORE = "id title status createdAt predictionWindowSeconds outcomes { id title totalPoints }"
        private const val REWARD_CORE = "id title prompt cost isEnabled isInStock isPaused isUserInputRequired backgroundColor cooldownExpiresAt image { url url2x } defaultImage { url url2x }"
        private const val REWARD_FULL = "$REWARD_CORE maxPerStreamSetting { isEnabled maxPerStream } maxPerUserPerStreamSetting { isEnabled maxPerUserPerStream } redemptionsRedeemedCurrentStream"

        val POINTS_QUERIES = listOf(
            "query ChannelPointsContext(\$channelLogin: String!) { community: user(login: \$channelLogin) { id channel { id communityPointsSettings { name image { url url2x } } self { communityPoints { balance availableClaim { id } } } } } }",
            "query ChannelPointsContext(\$channelLogin: String!) { community: user(login: \$channelLogin) { id channel { id self { communityPoints { balance availableClaim { id } } } } } }",
        )
        val REWARDS_QUERIES = listOf(REWARD_FULL, REWARD_CORE).map {
            "query ChannelPointsRewards(\$channelLogin: String!) { community: user(login: \$channelLogin) { id channel { id communityPointsSettings { customRewards { $it } } } } }"
        }
        val CLAIM_QUERIES = listOf(
            "mutation ClaimCommunityPoints(\$input: ClaimCommunityPointsInput!) { claimCommunityPoints(input: \$input) { claim { id pointsEarnedTotal } error { code } } }",
            "mutation ClaimCommunityPoints(\$input: ClaimCommunityPointsInput!) { claimCommunityPoints(input: \$input) { claim { id } } }",
        )
        const val REDEEM = "mutation RedeemCustomReward(\$input: RedeemCommunityPointsCustomRewardInput!) { redeemCommunityPointsCustomReward(input: \$input) { error { code } } }"
        val PREDICTION_QUERIES = listOf(PE_FULL, PE_CORE).map {
            "query ChannelPointsPredictionContext(\$channelLogin: String!) { community: user(login: \$channelLogin) { id channel { id activePredictionEvents { $it } lockedPredictionEvents { $it } } } }"
        }
        const val MAKE_PREDICTION = "mutation MakePrediction(\$input: MakePredictionInput!) { makePrediction(input: \$input) { error { code } } }"
        val POLL_QUERIES = listOf(
            "query ChannelPollContext_GetViewablePoll(\$login: String!) { channel: user(login: \$login) { id viewablePoll { id title status durationSeconds startedAt choices { id title votes { total } } settings { communityPointsVotes { isEnabled cost } } } } }",
            "query ChannelPollContext_GetViewablePoll(\$login: String!) { channel: user(login: \$login) { id viewablePoll { id title status choices { id title votes { total } } } } }",
        )
        const val VOTE = "mutation ChannelPollContext_VoteInPoll(\$input: VoteInPollInput!) { voteInPoll(input: \$input) { error { code } } }"
    }
}
