package tv.frostline.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitchinternal.CustomReward
import tv.frostline.core.twitchinternal.EngagementApi
import tv.frostline.core.twitchinternal.HermesClient
import tv.frostline.core.twitchinternal.PointsEvent
import tv.frostline.core.twitchinternal.PointsEvents
import tv.frostline.core.twitchinternal.PointsInfo
import tv.frostline.core.twitchinternal.PollEvents
import tv.frostline.core.twitchinternal.PollInfo
import tv.frostline.core.twitchinternal.PollStatus
import tv.frostline.core.twitchinternal.PredictionEvent
import tv.frostline.core.twitchinternal.PredictionEvents
import tv.frostline.core.twitchinternal.PredictionStatus
import tv.frostline.core.twitchinternal.engagementDebugText
import java.util.UUID

/** The viewer's web session. [toString] never shows the token. */
class EngagementSession(val authToken: String, val userId: String) {
    override fun toString() = "EngagementSession(userId=$userId)"
}

/**
 * Channel points, rewards, predictions and polls for the channel being watched. Signed-in only: with no
 * [session] nothing starts and [ui] stays empty. Every part fails on its own and quietly (the matching UI
 * simply is not shown); errors go to [debug] as HTTP status, exception class or GQL message only.
 */
class EngagementEngine(
    private val scope: CoroutineScope,
    private val api: EngagementApi,
    private val hermes: HermesClient,
    private val session: suspend () -> EngagementSession?,
    private val noSession: suspend () -> String = { "not signed in" },
    private val autoClaim: Flow<Boolean>,
    private val channelIdOf: suspend (login: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollEveryMs: Long = 30_000L,
    private val hermesFreshMs: Long = 90_000L,
    private val endedShownMs: Long = 15_000L,
    private val toastMs: Long = 3_000L,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _ui = MutableStateFlow(EngagementUi())
    val ui: StateFlow<EngagementUi> = _ui.asStateFlow()

    private val debugMap = LinkedHashMap<String, String>()
    private val _debug = MutableStateFlow(renderDebug(idleDebug()))
    /**
     * One line per part (points, rewards, prediction, poll, and the three Hermes topics hm-points, hm-preds, hm-polls), always present, for the debug overlay:
     * idle, loading, ok, "hidden: reason", or an error as HTTP status, exception class or GQL message. No tokens.
     */
    val debug: StateFlow<List<String>> = _debug.asStateFlow()

    private fun idleDebug() = linkedMapOf("points" to "idle", "rewards" to "idle", "prediction" to "idle", "poll" to "idle", "hm-points" to "idle", "hm-preds" to "idle", "hm-polls" to "idle")
    private fun renderDebug(m: Map<String, String>) = m.map { "${it.key.padEnd(11)}${it.value}" }

    private fun setDebug(source: String, text: String) {
        synchronized(debugMap) {
            if (debugMap.isEmpty()) debugMap.putAll(idleDebug())
            debugMap[source] = text
            _debug.value = renderDebug(debugMap)
        }
    }

    private fun resetDebug(all: String = "idle") {
        synchronized(debugMap) {
            debugMap.clear(); debugMap.putAll(idleDebug().mapValues { all })
            _debug.value = renderDebug(debugMap)
        }
    }

    private var job: Job? = null
    private var toastJob: Job? = null
    private var rewardsJob: Job? = null
    private var predEndJob: Job? = null
    private var pollEndJob: Job? = null

    private var token: String? = null
    private var userId: String? = null
    private var login: String = ""
    private var channelId: String? = null
    private var lastUserEventAt = Long.MIN_VALUE / 2
    private var lastClaimTried: String? = null
    private val pendingClaim = MutableStateFlow<String?>(null)
    private val bets = HashMap<String, Pair<String, Long>>()
    private val betResults = HashMap<String, BetResult>()
    private val votes = HashMap<String, String>()

    fun setChannel(newLogin: String) {
        val l = newLogin.lowercase()
        job?.cancel()
        toastJob?.cancel(); rewardsJob?.cancel(); predEndJob?.cancel(); pollEndJob?.cancel()
        _ui.value = EngagementUi()
        resetDebug()
        token = null; userId = null; channelId = null
        bets.clear(); betResults.clear(); votes.clear()
        pendingClaim.value = null; lastClaimTried = null; lastUserEventAt = Long.MIN_VALUE / 2
        login = l
        if (l.isEmpty()) return
        job = scope.launch { run(l) }
    }

    /** Debug: cancel and restart the current channel's run. */
    fun reconnect() = setChannel(login)

    fun stop() {
        job?.cancel()
        toastJob?.cancel(); rewardsJob?.cancel(); predEndJob?.cancel(); pollEndJob?.cancel()
        job = null
    }

    private fun fail(source: String, e: Throwable) {
        if (e is CancellationException) throw e
        val part = when {
            source == "hermes-points" -> "hm-points"
            source == "hermes-predictions" -> "hm-preds"
            source == "hermes-polls" -> "hm-polls"
            source == "claim" -> "points"
            source == "redeem" -> "rewards"
            source == "predictions" || source == "predict" -> "prediction"
            source == "vote" -> "poll"
            else -> source
        }
        setDebug(part, e.engagementDebugText())
    }

    private suspend fun run(forLogin: String) {
        val s = session()
        if (s == null) { // guest, or no web session linked: none of this exists
            resetDebug("hidden: ${noSession()}")
            return
        }
        resetDebug("loading")
        token = s.authToken
        userId = s.userId
        val info = try {
            api.pointsContext(forLogin, s.authToken)
        } catch (e: Throwable) {
            fail("points", e); null
        }
        val chId = info?.channelId ?: runCatching { channelIdOf(forLogin) }.getOrNull()
        channelId = chId
        if (info != null) { applyPoints(info); setDebug("points", "ok") }
        setDebug("rewards", if (info != null) "idle (opens with Rewards)" else "hidden: no points")
        coroutineScope {
            if (info != null) {
                launch { userTopic(s) }
                launch { pointsPolling(forLogin, s) }
                launch { claimLoop() }
            }
            if (chId != null) {
                launch { predictions(forLogin, chId, s) }
                launch { polls(forLogin, chId, s) }
            }
        }
    }

    // ---- channel points ----

    private fun applyPoints(info: PointsInfo) {
        _ui.update { it.copy(available = true, points = PointsUi(info.balance, info.pointsName ?: it.points?.name, info.iconUrl ?: it.points?.iconUrl)) }
        if (info.availableClaimId != null) pendingClaim.value = info.availableClaimId
    }

    private fun setBalance(balance: Long) {
        _ui.update { u -> u.points?.let { u.copy(points = it.copy(balance = balance)) } ?: u }
    }

    /** Mirrors one topic's own Hermes state into its debug line (subscribed, rejected: reason, ok (events), ...). */
    private fun CoroutineScope.mirrorTopic(key: String, topic: String, note: String = "") {
        launch {
            hermes.topicStatus.collect { m -> m[topic]?.let { setDebug(key, it + note) } }
        }
    }

    private suspend fun userTopic(s: EngagementSession) {
        try {
            setDebug("hm-points", "connecting")
            // The topic must name the user of the web session, which can differ from the Helix sign-in.
            val webId = runCatching { api.webUserId(s.authToken) }.getOrNull()
            val uid = webId ?: s.userId
            val note = if (webId != null && webId != s.userId) " (web user differs from sign-in)" else ""
            val topic = PointsEvents.topic(uid)
            kotlinx.coroutines.coroutineScope {
                mirrorTopic("hm-points", topic, note)
                hermes.events(listOf(topic), s.authToken).collect { ev ->
                    lastUserEventAt = clock()
                    PointsEvents.parse(ev.payload)?.let(::onPoints)
                }
            }
        } catch (e: Throwable) {
            fail("hermes-points", e)
        }
    }

    private fun onPoints(e: PointsEvent) {
        when (e) {
            is PointsEvent.Balance -> setBalance(e.balance)
            is PointsEvent.ClaimAvailable -> pendingClaim.value = e.claimId
            is PointsEvent.PredictionMade -> {
                bets[e.eventId] = e.outcomeId to e.points
                refreshPredictionUi(e.eventId)
            }
            is PointsEvent.PredictionResult -> {
                val kind = when (e.type) {
                    "WIN" -> BetResult(BetOutcome.Won, e.pointsWon)
                    "REFUND" -> BetResult(BetOutcome.Refunded, e.points)
                    else -> BetResult(BetOutcome.Lost, e.points)
                }
                betResults[e.eventId] = kind
                refreshPredictionUi(e.eventId)
            }
        }
    }

    /** Fallback while the user topic is quiet or down: ask for the context now and then. */
    private suspend fun pointsPolling(forLogin: String, s: EngagementSession) {
        while (true) {
            delay(pollEveryMs)
            if (clock() - lastUserEventAt < hermesFreshMs) continue
            try {
                applyPoints(api.pointsContext(forLogin, s.authToken))
            } catch (e: Throwable) {
                fail("points", e)
            }
        }
    }

    private suspend fun claimLoop() {
        combine(autoClaim, pendingClaim) { on, id -> if (on) id else null }.collect { id ->
            if (id != null && id != lastClaimTried) claim(id)
        }
    }

    private suspend fun claim(claimId: String) {
        lastClaimTried = claimId
        val t = token ?: return
        val ch = channelId ?: return
        val before = _ui.value.points?.balance
        try {
            val r = api.claim(ch, claimId, t)
            if (!r.ok) { setDebug("points", "claim: GQL ${r.errorCode}"); return }
            pendingClaim.value = null
            val gained = r.pointsEarned ?: run {
                val now = runCatching { api.pointsContext(login, t) }.getOrNull()?.also { applyPoints(it) }
                if (now != null && before != null) now.balance - before else null
            }
            showToast(if (gained != null && gained > 0) "+$gained" else "Bonus claimed")
        } catch (e: Throwable) {
            fail("claim", e)
        }
    }

    private fun showToast(text: String) {
        toastJob?.cancel()
        _ui.update { it.copy(toast = text) }
        toastJob = scope.launch {
            delay(toastMs)
            _ui.update { it.copy(toast = null) }
        }
    }

    // ---- rewards ----

    fun loadRewards() {
        val t = token ?: return
        rewardsJob?.cancel()
        _ui.update { it.copy(rewardsLoad = if (it.rewards.isEmpty()) RewardsLoad.Loading else it.rewardsLoad) }
        setDebug("rewards", "loading")
        rewardsJob = scope.launch {
            try {
                val list = api.rewards(login, t)
                val now = clock()
                val blocked = list.count { EngagementMath.rewardBlock(it, Long.MAX_VALUE, now) != null }
                setDebug("rewards", "ok (${list.size}, off ${list.count { !it.isEnabled }}, paused ${list.count { it.isPaused }}, stock ${list.count { !it.isInStock }}, blocked $blocked)")
                _ui.update { it.copy(rewards = list, rewardsLoad = RewardsLoad.Ready) }
            } catch (e: Throwable) {
                fail("rewards", e)
                _ui.update { it.copy(rewardsLoad = RewardsLoad.Failed) }
            }
        }
    }

    fun redeem(reward: CustomReward, text: String?) {
        val t = token ?: return
        val ch = channelId ?: return
        if (_ui.value.redeem == ActionState.Working) return
        _ui.update { it.copy(redeem = ActionState.Working) }
        scope.launch {
            try {
                val r = api.redeem(ch, reward, text?.trim()?.ifEmpty { null }, newId(), t)
                if (r.ok) {
                    _ui.update { u ->
                        u.copy(
                            redeem = ActionState.Done("Redeemed ${reward.title}"),
                            points = u.points?.let { it.copy(balance = (it.balance - reward.cost).coerceAtLeast(0)) },
                        )
                    }
                    loadRewards()
                } else {
                    _ui.update { it.copy(redeem = ActionState.Failed(EngagementMath.redeemError(r.errorCode.orEmpty()))) }
                }
            } catch (e: Throwable) {
                fail("redeem", e)
                _ui.update { it.copy(redeem = ActionState.Failed("Could not reach Twitch. Try again.")) }
            }
        }
    }

    fun resetRedeem() = _ui.update { it.copy(redeem = ActionState.Idle) }

    // ---- predictions ----

    private suspend fun predictions(forLogin: String, chId: String, s: EngagementSession) = coroutineScope {
        launch {
            try {
                val list = api.predictions(forLogin, s.authToken)
                setDebug("prediction", if (list.isEmpty()) "ok (none)" else "ok (${list.size})")
                (list.firstOrNull { it.status == PredictionStatus.Active } ?: list.firstOrNull())?.let(::applyPrediction)
            } catch (e: Throwable) {
                fail("predictions", e)
            }
        }
        try {
            mirrorTopic("hm-preds", PredictionEvents.topic(chId))
            hermes.events(listOf(PredictionEvents.topic(chId))).collect { ev ->
                PredictionEvents.parse(ev.payload)?.let(::applyPrediction)
            }
        } catch (e: Throwable) {
            fail("hermes-predictions", e)
        }
    }

    private fun applyPrediction(ev: PredictionEvent) {
        if (ev.status == PredictionStatus.Unknown) return
        val now = clock()
        val cur = _ui.value.prediction
        val terminal = ev.status == PredictionStatus.Resolved || ev.status == PredictionStatus.Canceled
        // An old event finishing must not replace the one on screen.
        if (cur != null && cur.id != ev.id && terminal && !cur.ended) return
        val created = ev.createdAtMs
        val endsAt = if (ev.windowSeconds > 0 && created != null) now + ev.windowSeconds * 1000L - ((ev.serverTimeMs ?: now) - created) else cur?.takeIf { it.id == ev.id }?.endsAtMs
        val bet = bets[ev.id]
        val derived = betResults[ev.id] ?: when {
            ev.status == PredictionStatus.Canceled && bet != null -> BetResult(BetOutcome.Refunded, bet.second)
            ev.status == PredictionStatus.Resolved && bet != null && ev.winningOutcomeId != null ->
                if (bet.first == ev.winningOutcomeId) BetResult(BetOutcome.Won, 0) else BetResult(BetOutcome.Lost, bet.second)
            else -> null
        }
        val ui = PredictionUi(ev.id, ev.title, ev.status, ev.outcomes, endsAt, ev.winningOutcomeId, bet?.first, bet?.second ?: 0, derived)
        _ui.update { it.copy(prediction = ui, predictionAction = if (cur?.id == ev.id) it.predictionAction else ActionState.Idle) }
        predEndJob?.cancel()
        if (terminal) {
            predEndJob = scope.launch {
                delay(endedShownMs)
                _ui.update { if (it.prediction?.id == ev.id) it.copy(prediction = null, predictionAction = ActionState.Idle) else it }
            }
        }
    }

    private fun refreshPredictionUi(eventId: String) {
        _ui.update { u ->
            val p = u.prediction ?: return@update u
            if (p.id != eventId) return@update u
            val bet = bets[eventId]
            u.copy(prediction = p.copy(myOutcomeId = bet?.first, myPoints = bet?.second ?: 0, result = betResults[eventId] ?: p.result))
        }
    }

    fun predict(outcomeId: String, amount: Long) {
        val t = token ?: return
        val p = _ui.value.prediction ?: return
        val balance = _ui.value.points?.balance ?: 0
        val msg = when {
            _ui.value.predictionAction == ActionState.Working -> return
            !p.open -> "Predictions are closed."
            amount < EngagementMath.MIN_PREDICTION -> "The minimum is 10 points."
            amount > balance -> "Not enough points."
            amount > EngagementMath.MAX_PREDICTION -> "That is above the maximum."
            p.myOutcomeId != null && p.myOutcomeId != outcomeId -> "You already predicted the other outcome."
            else -> null
        }
        if (msg != null) { _ui.update { it.copy(predictionAction = ActionState.Failed(msg)) }; return }
        _ui.update { it.copy(predictionAction = ActionState.Working) }
        scope.launch {
            try {
                val r = api.predict(p.id, outcomeId, amount, newId(), t)
                if (r.ok) {
                    val prev = bets[p.id]?.second ?: 0
                    bets[p.id] = outcomeId to prev + amount
                    _ui.update { it.copy(predictionAction = ActionState.Done("Prediction placed"), points = it.points?.let { pt -> pt.copy(balance = (pt.balance - amount).coerceAtLeast(0)) }) }
                    refreshPredictionUi(p.id)
                } else {
                    _ui.update { it.copy(predictionAction = ActionState.Failed(EngagementMath.predictionError(r.errorCode.orEmpty()))) }
                }
            } catch (e: Throwable) {
                fail("predict", e)
                _ui.update { it.copy(predictionAction = ActionState.Failed("Could not reach Twitch. Try again.")) }
            }
        }
    }

    fun resetPrediction() = _ui.update { it.copy(predictionAction = ActionState.Idle) }

    // ---- polls ----

    private suspend fun polls(forLogin: String, chId: String, s: EngagementSession) = coroutineScope {
        launch {
            try {
                val pl = api.poll(forLogin, s.authToken)
                setDebug("poll", if (pl == null) "ok (none)" else "ok")
                pl?.let(::applyPoll)
            } catch (e: Throwable) {
                fail("poll", e)
            }
        }
        try {
            mirrorTopic("hm-polls", PollEvents.topic(chId))
            hermes.events(listOf(PollEvents.topic(chId))).collect { ev ->
                PollEvents.parse(ev.payload)?.let(::applyPoll)
            }
        } catch (e: Throwable) {
            fail("hermes-polls", e)
        }
    }

    private fun applyPoll(p: PollInfo) {
        if (p.status == PollStatus.Unknown) return
        val now = clock()
        val cur = _ui.value.poll
        if (p.status == PollStatus.Archived) {
            // Archived follows completed/terminated; keep showing the results until their timer runs out.
            if (cur == null || cur.id != p.id || cur.open) _ui.update { if (it.poll?.id == p.id) it.copy(poll = null) else it }
            return
        }
        val started = p.startedAtMs
        val endsAt = if (p.durationSeconds > 0 && started != null) now + p.durationSeconds * 1000L - ((p.serverTimeMs ?: now) - started) else cur?.takeIf { it.id == p.id }?.endsAtMs
        val ui = PollUi(p.id, p.title, p.status, p.choices, endsAt, p.pointsVoteCost ?: cur?.takeIf { it.id == p.id }?.pointsVoteCost, votes[p.id])
        _ui.update { it.copy(poll = ui, pollAction = if (cur?.id == p.id) it.pollAction else ActionState.Idle) }
        pollEndJob?.cancel()
        if (ui.ended) {
            pollEndJob = scope.launch {
                delay(endedShownMs)
                _ui.update { if (it.poll?.id == p.id) it.copy(poll = null, pollAction = ActionState.Idle) else it }
            }
        }
    }

    fun vote(choiceId: String, withPoints: Boolean) {
        val t = token ?: return
        val uid = userId ?: return
        val p = _ui.value.poll ?: return
        val cost = if (withPoints) p.pointsVoteCost else null
        val balance = _ui.value.points?.balance ?: 0
        val msg = when {
            _ui.value.pollAction == ActionState.Working -> return
            !p.open -> "The poll has ended."
            withPoints && cost == null -> "This poll does not take extra votes."
            cost != null && cost > balance -> "Not enough points."
            else -> null
        }
        if (msg != null) { _ui.update { it.copy(pollAction = ActionState.Failed(msg)) }; return }
        _ui.update { it.copy(pollAction = ActionState.Working) }
        scope.launch {
            try {
                val r = api.vote(p.id, choiceId, uid, newId(), cost, t)
                if (r.ok) {
                    votes[p.id] = choiceId
                    _ui.update { u ->
                        u.copy(
                            pollAction = ActionState.Done("Vote cast"),
                            poll = u.poll?.takeIf { it.id == p.id }?.copy(myChoiceId = choiceId) ?: u.poll,
                            points = if (cost != null) u.points?.let { pt -> pt.copy(balance = (pt.balance - cost).coerceAtLeast(0)) } else u.points,
                        )
                    }
                } else {
                    _ui.update { it.copy(pollAction = ActionState.Failed(EngagementMath.voteError(r.errorCode.orEmpty()))) }
                }
            } catch (e: Throwable) {
                fail("vote", e)
                _ui.update { it.copy(pollAction = ActionState.Failed("Could not reach Twitch. Try again.")) }
            }
        }
    }

    fun resetPoll() = _ui.update { it.copy(pollAction = ActionState.Idle) }

    fun actions() = EngagementActions(
        loadRewards = ::loadRewards, redeem = ::redeem, resetRedeem = ::resetRedeem,
        predict = ::predict, resetPrediction = ::resetPrediction, vote = ::vote, resetPoll = ::resetPoll,
    )
}

/**
 * Signed in AND a linked web session (the `auth-token` from the WebView sign-in; the Helix device-code token does not
 * work for GQL). The user id comes from the stored tokens, else from [fetchId]. Null means the feature stays hidden.
 */
internal suspend fun resolveEngagementSession(web: String?, signedIn: Boolean, storedUserId: String?, fetchId: suspend () -> String?): EngagementSession? {
    if (!signedIn || web.isNullOrEmpty()) return null
    val id = storedUserId ?: fetchId() ?: return null
    return EngagementSession(web, id)
}

/** Wires the engine to the app: the web session from the vault, the channel id from Helix, auto-claim from Settings. */
class EngagementViewModel(
    initialLogin: String,
    api: EngagementApi,
    hermes: HermesClient,
    vault: TokenVault,
    helix: HelixRepository,
    settings: SettingsStore,
) : ViewModel() {
    val engine = EngagementEngine(
        scope = viewModelScope,
        api = api,
        hermes = hermes,
        session = {
            val tokens = vault.currentTokens()
            resolveEngagementSession(vault.currentWebSession(), tokens != null, tokens?.userId) { runCatching { helix.me().id }.getOrNull() }
        },
        noSession = {
            when {
                vault.currentTokens() == null -> "not signed in"
                vault.currentWebSession().isNullOrEmpty() -> "no web session linked (Settings > Link web session)"
                else -> "user id unknown"
            }
        },
        autoClaim = settings.autoClaimPoints,
        channelIdOf = { l -> helix.userByLogin(l)?.id },
    )

    fun setChannel(newLogin: String) = engine.setChannel(newLogin)

    init {
        // Debug deep link "reconnect": restart the engagement run (drops and re-opens the Hermes socket).
        tv.frostline.core.ui.DebugHooks.register("reconnect-hermes") { engine.reconnect() }
    }

    override fun onCleared() {
        tv.frostline.core.ui.DebugHooks.unregister("reconnect-hermes")
        engine.stop()
    }
}
