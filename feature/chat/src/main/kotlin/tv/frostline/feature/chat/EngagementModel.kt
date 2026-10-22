package tv.frostline.feature.chat

import androidx.compose.runtime.Immutable
import tv.frostline.core.twitchinternal.CustomReward
import tv.frostline.core.twitchinternal.PollChoice
import tv.frostline.core.twitchinternal.PollStatus
import tv.frostline.core.twitchinternal.PredictionOutcome
import tv.frostline.core.twitchinternal.PredictionStatus
import java.util.Locale

/** The state of a button-driven request inside a sheet. */
sealed interface ActionState {
    data object Idle : ActionState
    data object Working : ActionState
    data class Done(val message: String) : ActionState
    data class Failed(val message: String) : ActionState
}

enum class RewardsLoad { Idle, Loading, Ready, Failed }

@Immutable
data class PointsUi(val balance: Long, val name: String?, val iconUrl: String?)

enum class BetOutcome { Won, Lost, Refunded }

/** What the viewer's own prediction did: [points] is what they won (Won), lost or got back. */
@Immutable
data class BetResult(val kind: BetOutcome, val points: Long)

@Immutable
data class PredictionUi(
    val id: String,
    val title: String,
    val status: PredictionStatus,
    val outcomes: List<PredictionOutcome>,
    /** Device-clock time the prediction window closes, or null when unknown. */
    val endsAtMs: Long?,
    val winningOutcomeId: String?,
    val myOutcomeId: String?,
    val myPoints: Long,
    val result: BetResult?,
) {
    val totalPoints: Long get() = outcomes.sumOf { it.totalPoints }
    val open: Boolean get() = status == PredictionStatus.Active
    val ended: Boolean get() = status == PredictionStatus.Resolved || status == PredictionStatus.Canceled
}

@Immutable
data class PollUi(
    val id: String,
    val title: String,
    val status: PollStatus,
    val choices: List<PollChoice>,
    val endsAtMs: Long?,
    /** Cost of an extra vote with channel points, or null when the poll does not offer it. */
    val pointsVoteCost: Int?,
    val myChoiceId: String?,
) {
    val totalVotes: Long get() = choices.sumOf { it.votes }
    val open: Boolean get() = status == PollStatus.Active
    val ended: Boolean get() = !open
}

/** Everything channel points, rewards, predictions and polls need to draw. All empty for guests. */
@Immutable
data class EngagementUi(
    /** Signed in and the channel-points context loaded: shows the balance and the Rewards button. */
    val available: Boolean = false,
    val points: PointsUi? = null,
    val toast: String? = null,
    val rewardsLoad: RewardsLoad = RewardsLoad.Idle,
    val rewards: List<CustomReward> = emptyList(),
    val redeem: ActionState = ActionState.Idle,
    val prediction: PredictionUi? = null,
    val predictionAction: ActionState = ActionState.Idle,
    val poll: PollUi? = null,
    val pollAction: ActionState = ActionState.Idle,
)

@Immutable
class EngagementActions(
    val loadRewards: () -> Unit = {},
    val redeem: (CustomReward, String?) -> Unit = { _, _ -> },
    val resetRedeem: () -> Unit = {},
    val predict: (outcomeId: String, amount: Long) -> Unit = { _, _ -> },
    val resetPrediction: () -> Unit = {},
    val vote: (choiceId: String, withPoints: Boolean) -> Unit = { _, _ -> },
    val resetPoll: () -> Unit = {},
)

/** Pure helpers behind the engagement UI, kept free of Compose so they are unit-testable. */
object EngagementMath {
    const val MIN_PREDICTION = 10L
    const val MAX_PREDICTION = 250_000L

    fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else Math.round(part * 100.0 / total).toInt()

    /** 999, 1.2K, 12K, 1.5M. */
    fun compact(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 10_000 -> trim(n / 1_000.0) + "K"
        n < 1_000_000 -> (n / 1_000).toString() + "K"
        n < 10_000_000 -> trim(n / 1_000_000.0) + "M"
        else -> (n / 1_000_000).toString() + "M"
    }

    private fun trim(d: Double): String = String.format(Locale.US, "%.1f", d).removeSuffix(".0")

    fun grouped(n: Long): String = String.format(Locale.US, "%,d", n)

    /** "1:05"; never negative. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Largest amount the viewer may stake: their balance, capped at Twitch's limit. */
    fun maxAmount(balance: Long): Long = balance.coerceIn(0, MAX_PREDICTION)

    fun clampAmount(amount: Long, balance: Long): Long {
        val max = maxAmount(balance)
        if (max < MIN_PREDICTION) return 0
        return amount.coerceIn(MIN_PREDICTION, max)
    }

    /** +/- step: 10 below 100, then 100, 1000, 10000. */
    fun step(amount: Long): Long = when {
        amount < 100 -> 10
        amount < 1_000 -> 100
        amount < 10_000 -> 1_000
        else -> 10_000
    }

    fun increase(amount: Long, balance: Long): Long = clampAmount(amount + step(amount), balance)

    fun decrease(amount: Long, balance: Long): Long = clampAmount(amount - step((amount - 1).coerceAtLeast(1)), balance)

    /** The 10 / 100 / 1k / max presets that the balance can pay; "Max" is always last. */
    fun presets(balance: Long): List<Pair<String, Long>> {
        val max = maxAmount(balance)
        if (max < MIN_PREDICTION) return emptyList()
        return listOf("10" to 10L, "100" to 100L, "1k" to 1_000L).filter { it.second < max } + ("Max" to max)
    }

    /** Why [r] cannot be redeemed right now, or null when it can. */
    fun rewardBlock(r: CustomReward, balance: Long, nowMs: Long): String? {
        val cooldown = r.cooldownExpiresAtMs
        val perUser = r.maxPerUserPerStream
        val perStream = r.maxPerStream
        return when {
            !r.isEnabled -> "Unavailable"
            r.isPaused -> "Paused by the streamer"
            !r.isInStock -> "Out of stock"
            cooldown != null && cooldown > nowMs -> "On cooldown, " + clock(cooldown - nowMs)
            perUser != null && r.redeemedThisStream >= perUser -> "Limit reached this stream"
            perStream != null && r.redeemedThisStream >= perStream -> "Limit reached this stream"
            r.cost > balance -> "Need ${grouped(r.cost - balance)} more"
            else -> null
        }
    }

    fun redeemError(code: String): String = when (code) {
        "NOT_ENOUGH_POINTS", "INSUFFICIENT_POINTS" -> "Not enough points."
        "REWARD_ON_COOLDOWN", "COOLDOWN", "GLOBAL_COOLDOWN", "ON_COOLDOWN" -> "That reward is on cooldown."
        "OUT_OF_STOCK", "MAX_PER_STREAM", "MAX_PER_USER_PER_STREAM", "REWARD_LIMIT_REACHED" -> "That reward has run out for this stream."
        "REWARD_PAUSED", "PAUSED" -> "That reward is paused."
        "DISABLED_REWARD", "REWARD_DISABLED", "REWARD_NOT_FOUND" -> "That reward is no longer available."
        "NOT_OFFERED" -> "That reward is not available here."
        "INVALID_TEXT_INPUT", "TEXT_INPUT_REQUIRED" -> "The message was not accepted."
        else -> "Twitch refused it ($code)."
    }

    fun predictionError(code: String): String = when (code) {
        "NOT_ENOUGH_POINTS", "INSUFFICIENT_POINTS" -> "Not enough points."
        "EVENT_LOCKED", "LOCKED", "EVENT_NOT_ACTIVE", "PREDICTION_LOCKED" -> "Predictions are closed."
        "ALREADY_PREDICTED_OTHER_OUTCOME", "CANNOT_CHANGE_OUTCOME" -> "You already predicted the other outcome."
        "MIN_POINTS", "BELOW_MINIMUM" -> "The minimum is 10 points."
        "MAX_POINTS", "ABOVE_MAXIMUM" -> "That is above the maximum."
        else -> "Twitch refused it ($code)."
    }

    fun voteError(code: String): String = when (code) {
        "NOT_ENOUGH_POINTS", "INSUFFICIENT_POINTS" -> "Not enough points."
        "POLL_NOT_ACTIVE", "POLL_ENDED", "POLL_COMPLETED", "POLL_TERMINATED" -> "The poll has ended."
        "ALREADY_VOTED", "USER_ALREADY_VOTED" -> "You have already voted."
        "SUBSCRIBER_ONLY", "SUBS_ONLY" -> "Only subscribers can vote in this poll."
        else -> "Twitch refused it ($code)."
    }
}
