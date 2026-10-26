package tv.frostline.core.twitchinternal

import java.io.IOException

/**
 * A channel-points, prediction or poll request failed. Carries only what is safe to show in the debug overlay:
 * the HTTP status and/or the messages Twitch's GQL returned. Never headers, tokens or request bodies.
 */
class EngagementException(
    message: String,
    val httpStatus: Int? = null,
    val gqlMessages: List<String> = emptyList(),
) : IOException(message)

/** One short line for the debug overlay: HTTP status, GQL error messages, or the exception class. */
fun Throwable.engagementDebugText(): String = when {
    this is EngagementException && httpStatus != null -> "HTTP $httpStatus"
    this is EngagementException && gqlMessages.isNotEmpty() -> "GQL: " + gqlMessages.joinToString("; ").take(120)
    else -> javaClass.simpleName
}

/** The signed-in viewer's points in one channel, and how that channel names and draws them. */
data class PointsInfo(
    val channelId: String,
    val balance: Long,
    /** Id of the bonus chest waiting to be claimed, if any. */
    val availableClaimId: String?,
    /** The channel's own name for its points ("Hype Points"), or null for the default. */
    val pointsName: String?,
    val iconUrl: String?,
)

/** A channel-defined reward that costs channel points. Nothing here can cost real money. */
data class CustomReward(
    val id: String,
    val title: String,
    val prompt: String,
    val cost: Long,
    val iconUrl: String?,
    val isEnabled: Boolean,
    val isInStock: Boolean,
    val isPaused: Boolean,
    val requiresInput: Boolean,
    /** Epoch ms when the cooldown ends; null when there is none. */
    val cooldownExpiresAtMs: Long?,
    val maxPerStream: Int?,
    val maxPerUserPerStream: Int?,
    /** Redemptions so far this stream across the channel (what Twitch counts against [maxPerStream]); NOT this viewer's own count. */
    val redeemedThisStream: Int,
    /** Only subscribers may redeem it. Whether this viewer is one is not known here, so it is a hint, not a block. */
    val isSubOnly: Boolean = false,
    /** Length of the channel-wide cooldown after each redemption, when the reward has one. */
    val globalCooldownSeconds: Int? = null,
)

/** Result of a mutation. [errorCode] is Twitch's own code (for example NOT_ENOUGH_POINTS); null means success. */
data class ActionResult(val errorCode: String? = null, val pointsEarned: Long? = null) {
    val ok: Boolean get() = errorCode == null
}

enum class PredictionStatus { Active, Locked, ResolvePending, Resolved, Canceled, Unknown }

data class PredictionOutcome(
    val id: String,
    val title: String,
    /** "BLUE", "PINK" or null. */
    val color: String?,
    val totalPoints: Long,
    val totalUsers: Long,
)

data class PredictionEvent(
    val id: String,
    val title: String,
    val status: PredictionStatus,
    val outcomes: List<PredictionOutcome>,
    val windowSeconds: Int,
    val createdAtMs: Long?,
    /** Twitch's clock at the time of the message, when it carries one; used to count the time left without clock skew. */
    val serverTimeMs: Long?,
    val winningOutcomeId: String?,
)

enum class PollStatus { Active, Completed, Terminated, Archived, Unknown }

data class PollChoice(val id: String, val title: String, val votes: Long)

data class PollInfo(
    val id: String,
    val title: String,
    val status: PollStatus,
    val choices: List<PollChoice>,
    val durationSeconds: Int,
    val startedAtMs: Long?,
    val serverTimeMs: Long?,
    /** Cost of one extra vote paid with channel points, or null when the poll does not offer that. Bits are never read. */
    val pointsVoteCost: Int?,
) {
    val totalVotes: Long get() = choices.sumOf { it.votes }
}

/** What the user's own `community-points-user-v1` topic says. */
sealed interface PointsEvent {
    data class Balance(val balance: Long, val gained: Long?) : PointsEvent
    data class ClaimAvailable(val claimId: String) : PointsEvent
    data class PredictionMade(val eventId: String, val outcomeId: String, val points: Long) : PointsEvent
    /** [type] is WIN, LOSE or REFUND. */
    data class PredictionResult(val eventId: String, val outcomeId: String?, val points: Long, val type: String, val pointsWon: Long) : PointsEvent
}
