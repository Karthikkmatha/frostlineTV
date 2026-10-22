package tv.frostline.core.twitchinternal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Small tolerant accessors: Twitch sends numbers as numbers or strings, and fields come and go. */
internal object J {
    val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != "null" }?.contentOrNull
    fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.contentOrNull?.toLongOrNull() ?: it.doubleOrNull?.toLong() }
    fun JsonElement?.int(): Int? = long()?.toInt()
    fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    fun instantMs(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

private fun predictionStatus(s: String?) = when (s?.uppercase()) {
    "ACTIVE" -> PredictionStatus.Active
    "LOCKED" -> PredictionStatus.Locked
    "RESOLVE_PENDING" -> PredictionStatus.ResolvePending
    "RESOLVED" -> PredictionStatus.Resolved
    "CANCELED", "CANCELLED" -> PredictionStatus.Canceled
    else -> PredictionStatus.Unknown
}

private fun pollStatus(s: String?) = when (s?.uppercase()) {
    "ACTIVE" -> PollStatus.Active
    "COMPLETED" -> PollStatus.Completed
    "TERMINATED" -> PollStatus.Terminated
    "ARCHIVED" -> PollStatus.Archived
    else -> PollStatus.Unknown
}

/** `community-points-user-v1.<userId>`. */
object PointsEvents {
    fun topic(userId: String) = "community-points-user-v1.$userId"

    fun parse(payload: String): PointsEvent? = with(J) {
        val root = J.parse(payload) ?: return null
        val type = root["type"].str() ?: return null
        val data = root["data"].obj() ?: return null
        when (type) {
            "points-earned", "points-spent", "claim-claimed" -> {
                val balance = data["balance"].obj()?.get("balance").long() ?: return null
                PointsEvent.Balance(balance, data["point_gain"].obj()?.get("total_points").long())
            }
            "claim-available" -> data["claim"].obj()?.get("id").str()?.let { PointsEvent.ClaimAvailable(it) }
            "prediction-made" -> {
                val p = data["prediction"].obj() ?: return null
                PointsEvent.PredictionMade(p["event_id"].str() ?: return null, p["outcome_id"].str() ?: return null, p["points"].long() ?: 0)
            }
            "prediction-result" -> {
                val p = data["prediction"].obj() ?: return null
                val r = p["result"].obj() ?: return null
                PointsEvent.PredictionResult(
                    p["event_id"].str() ?: return null, p["outcome_id"].str(), p["points"].long() ?: 0,
                    r["type"].str()?.uppercase() ?: return null, r["points_won"].long() ?: 0,
                )
            }
            else -> null
        }
    }
}

/** `predictions-channel-v1.<channelId>`. */
object PredictionEvents {
    fun topic(channelId: String) = "predictions-channel-v1.$channelId"

    /** Parses one pubsub payload; [nowMs] stands in for a missing server clock. */
    fun parse(payload: String): PredictionEvent? = with(J) {
        val root = J.parse(payload) ?: return null
        val type = root["type"].str() ?: return null
        if (type != "event-created" && type != "event-updated") return null
        val data = root["data"].obj() ?: return null
        event(data["event"].obj() ?: return null, instantMs(data["timestamp"].str()))
    }

    private fun event(e: JsonObject, serverMs: Long?): PredictionEvent? = with(J) {
        val id = e["id"].str() ?: return null
        val outcomes = e["outcomes"].arr().orEmpty().mapNotNull { o ->
            val oo = o.obj() ?: return@mapNotNull null
            PredictionOutcome(
                oo["id"].str() ?: return@mapNotNull null,
                oo["title"].str().orEmpty(),
                oo["color"].str(),
                oo["total_points"].long() ?: 0,
                oo["total_users"].long() ?: 0,
            )
        }
        PredictionEvent(
            id = id,
            title = e["title"].str().orEmpty(),
            status = predictionStatus(e["status"].str()),
            outcomes = outcomes,
            windowSeconds = e["prediction_window_seconds"].int() ?: 0,
            createdAtMs = instantMs(e["created_at"].str()),
            serverTimeMs = serverMs,
            winningOutcomeId = e["winning_outcome_id"].str(),
        )
    }
}

/** `polls.<channelId>`. Bits settings are deliberately never read. */
object PollEvents {
    fun topic(channelId: String) = "polls.$channelId"

    fun parse(payload: String): PollInfo? = with(J) {
        val root = J.parse(payload) ?: return null
        val type = root["type"].str() ?: return null
        if (type !in setOf("POLL_CREATE", "POLL_UPDATE", "POLL_COMPLETE", "POLL_TERMINATE", "POLL_ARCHIVE")) return null
        val data = root["data"].obj() ?: return null
        val p = data["poll"].obj() ?: return null
        val choices = p["choices"].arr().orEmpty().mapNotNull { c ->
            val co = c.obj() ?: return@mapNotNull null
            PollChoice(
                co["choice_id"].str() ?: return@mapNotNull null,
                co["title"].str().orEmpty(),
                co["votes"].obj()?.get("total").long() ?: 0,
            )
        }
        val cp = p["settings"].obj()?.get("channel_points_votes").obj()
        val cost = if (cp?.get("is_enabled").bool() == true) cp?.get("cost").int()?.takeIf { it > 0 } else null
        val status = pollStatus(p["status"].str()).let { if (type == "POLL_ARCHIVE" && it == PollStatus.Unknown) PollStatus.Archived else it }
        PollInfo(
            id = p["poll_id"].str() ?: return null,
            title = p["title"].str().orEmpty(),
            status = status,
            choices = choices,
            durationSeconds = p["duration_seconds"].int() ?: 0,
            startedAtMs = instantMs(p["started_at"].str()),
            serverTimeMs = instantMs(data["timestamp"].str()) ?: instantMs(p["updated_at"].str()),
            pointsVoteCost = cost,
        )
    }
}
