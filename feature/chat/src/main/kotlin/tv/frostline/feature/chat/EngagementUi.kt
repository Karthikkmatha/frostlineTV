package tv.frostline.feature.chat

import androidx.compose.foundation.background
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.frostline.core.twitchinternal.CustomReward
import tv.frostline.core.twitchinternal.PredictionStatus
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostSecondaryButton
import tv.frostline.core.ui.TvTextField
import tv.frostline.core.ui.FrostListItem
import tv.frostline.core.ui.FrostSheetSurface
import tv.frostline.core.ui.FrostSheetTitle
import tv.frostline.core.ui.FrostTokens
import tv.frostline.core.ui.FrostSurface
import tv.frostline.core.ui.tryFocus

/** Banners and sheets tick once a second for countdowns; tests switch this off. */
val LocalEngagementTicking = staticCompositionLocalOf { true }

@Composable
internal fun rememberNowMs(active: Boolean): State<Long> {
    val ticking = LocalEngagementTicking.current
    return produceState(System.currentTimeMillis(), active, ticking) {
        value = System.currentTimeMillis()
        if (active && ticking) while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
}

private val Stop = FocusRequester.Cancel

private fun colorOf(name: String?, index: Int): Color = when (name?.uppercase()) {
    "BLUE" -> Color(0xFF4DA3FF)
    "PINK" -> Color(0xFFFF6FB5)
    else -> listOf(Color(0xFF4DA3FF), Color(0xFFFF6FB5), Color(0xFF8FD3A0), Color(0xFFE8C468))[index % 4]
}

// ---------------------------------------------------------------- header badge and toast

/** Balance chip of the chat header: channel icon (when it has one), the number, the channel's name for points. */
@Composable
internal fun PointsBadge(points: PointsUi, modifier: Modifier = Modifier) {
    Row(modifier.testTag("points_balance"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!points.iconUrl.isNullOrEmpty()) EmoteImage(points.iconUrl, points.name ?: "Channel points", Modifier.size(14.dp))
        Text(EngagementMath.grouped(points.balance), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = FrostColors.Lavender, maxLines = 1, modifier = Modifier.testTag("points_value"))
        if (!points.name.isNullOrEmpty()) Text(points.name, style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("points_name"))
    }
}

@Composable
internal fun PointsToast(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface,
        modifier = modifier.background(FrostColors.Primary, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 5.dp).testTag("points_toast"),
    )
}

// ---------------------------------------------------------------- banners

@Composable
private fun BannerSurface(onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    // A wide banner: the border and lighter container show focus, it does not scale (it would crowd the panel).
    FrostSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1f) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun OutcomeLine(color: Color, title: String, pct: Int, points: String, mine: Boolean, winner: Boolean, fontSp: Int = 11) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            (if (winner) "Winner: " else "") + title + if (mine) " (you)" else "",
            style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            fontWeight = if (winner) FontWeight.Bold else FontWeight.Normal,
        )
        Text("$pct%", style = MaterialTheme.typography.bodySmall, color = tv.frostline.core.ui.FrostColors.OnSurface, fontWeight = FontWeight.SemiBold)
        Text(points, style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
    }
}

internal fun predictionStatusLine(p: PredictionUi, remainingMs: Long?): String = when (p.status) {
    PredictionStatus.Active -> if (remainingMs != null) EngagementMath.clock(remainingMs) + " left" else "Open"
    PredictionStatus.Locked, PredictionStatus.ResolvePending -> "Locked"
    PredictionStatus.Resolved -> "Resolved"
    PredictionStatus.Canceled -> "Canceled, points refunded"
    PredictionStatus.Unknown -> ""
}

internal fun betResultText(r: BetResult?): String? = r?.let {
    when (it.kind) {
        BetOutcome.Won -> if (it.points > 0) "You won +${EngagementMath.grouped(it.points)}" else "You won"
        BetOutcome.Lost -> "You lost ${EngagementMath.grouped(it.points)}"
        BetOutcome.Refunded -> "Refunded ${EngagementMath.grouped(it.points)}"
    }
}

/** Compact, non-blocking. It never requests focus; it only takes it when the viewer navigates to it. */
@Composable
internal fun PredictionBanner(p: PredictionUi, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val now by rememberNowMs(p.open)
    val remaining = p.endsAtMs?.let { it - now }
    BannerSurface(onOpen, modifier.testTag("banner_prediction")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Prediction", style = MaterialTheme.typography.bodySmall, color = FrostColors.Ice, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(predictionStatusLine(p, remaining), style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("banner_prediction_time"))
        }
        Text(p.title, style = MaterialTheme.typography.bodySmall, color = tv.frostline.core.ui.FrostColors.OnSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("banner_prediction_title"))
        val total = p.totalPoints
        p.outcomes.take(3).forEachIndexed { i, o ->
            OutcomeLine(colorOf(o.color, i), o.title, EngagementMath.percent(o.totalPoints, total), EngagementMath.compact(o.totalPoints), o.id == p.myOutcomeId, o.id == p.winningOutcomeId && p.status == PredictionStatus.Resolved)
        }
        if (p.outcomes.size > 3) Text("+${p.outcomes.size - 3} more", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
        betResultText(p.result)?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = FrostColors.Lavender, modifier = Modifier.testTag("banner_prediction_result")) }
    }
}

@Composable
internal fun PollBanner(p: PollUi, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val now by rememberNowMs(p.open)
    val remaining = p.endsAtMs?.let { it - now }
    BannerSurface(onOpen, modifier.testTag("banner_poll")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (p.open) "Poll" else "Poll results", style = MaterialTheme.typography.bodySmall, color = FrostColors.Ice, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(if (p.open) (remaining?.let { EngagementMath.clock(it) + " left" } ?: "Open") else "Ended", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("banner_poll_time"))
        }
        Text(p.title, style = MaterialTheme.typography.bodySmall, color = tv.frostline.core.ui.FrostColors.OnSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("banner_poll_title"))
        val total = p.totalVotes
        val top = p.choices.maxOfOrNull { it.votes } ?: 0
        p.choices.take(3).forEachIndexed { i, c ->
            OutcomeLine(colorOf(null, i), c.title, EngagementMath.percent(c.votes, total), EngagementMath.compact(c.votes), c.id == p.myChoiceId, p.ended && top > 0 && c.votes == top)
        }
        if (p.choices.size > 3) Text("+${p.choices.size - 3} more", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
    }
}

// ---------------------------------------------------------------- shared sheet pieces

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SheetFrame(tag: String, c: ChatController, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    FrostSheetSurface(
        modifier
            .onFocusChanged { c.overlayFocused = it.hasFocus }
            // Focus can only leave through Back or Close.
            .focusProperties { exit = { Stop } }
            .focusGroup()
            .testTag(tag),
        content = content,
    )
}

@Composable
private fun SheetHeader(title: String, closeTag: String, onClose: () -> Unit, points: PointsUi?, closeModifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            FrostSheetTitle(title)
            if (points != null) PointsBadge(points, Modifier.testTag("sheet_balance"))
        }
        FrostSecondaryButton("Close", onClose, closeModifier.focusProperties { up = Stop; left = Stop; right = Stop }.testTag(closeTag))
    }
}

@Composable
private fun SheetRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inert: Boolean = false,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    FrostListItem(
        onClick = { if (!inert) onClick() },
        minHeight = FrostTokens.SheetRowHeight,
        modifier = modifier.fillMaxWidth().focusProperties { left = Stop; right = Stop }.then(if (inert) Modifier.semantics { disabled() } else Modifier),
        selected = selected,
        headline = {
            if (inert) androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides FrostColors.Muted) { content() } else content()
        },
    )
}

@Composable
private fun Result(state: ActionState, tag: String) {
    when (state) {
        is ActionState.Done -> Text(state.message, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FrostColors.Lavender, modifier = Modifier.testTag("${tag}_success"))
        is ActionState.Failed -> Text(state.message, style = MaterialTheme.typography.bodyLarge, color = FrostColors.Live, modifier = Modifier.testTag("${tag}_error"))
        ActionState.Working -> Text("Working...", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("${tag}_working"))
        ActionState.Idle -> Unit
    }
}

// ---------------------------------------------------------------- rewards sheet

@Composable
internal fun RewardsSheet(
    ui: EngagementUi,
    act: EngagementActions,
    c: ChatController,
    modifier: Modifier = Modifier,
) {
    val pick = c.rewardPick
    val result = ui.redeem
    val finished = result is ActionState.Done || result is ActionState.Failed
    val now by rememberNowMs(true)
    val balance = ui.points?.balance ?: 0
    val initial = remember { FocusRequester() }
    val listFocusId = c.rewardLast?.takeIf { id -> ui.rewards.any { it.id == id } } ?: ui.rewards.firstOrNull()?.id
    val step = if (finished) 2 else if (pick != null) 1 else 0
    LaunchedEffect(step, ui.rewardsLoad, ui.rewards.isEmpty()) { initial.tryFocus() }

    SheetFrame("rewards_sheet", c, modifier) {
        SheetHeader(
            "Rewards", "rewards_close", { c.rewardsOpen = false }, ui.points,
            closeModifier = if (step == 0 && listFocusId == null) Modifier.focusRequester(initial) else Modifier,
        )
        when {
            finished -> {
                Result(result, "redeem")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrostSecondaryButton("Back to rewards", { act.resetRedeem(); c.rewardPick = null }, Modifier.focusRequester(initial).focusProperties { left = Stop }.testTag("redeem_back"))
                    FrostSecondaryButton("Close", { act.resetRedeem(); c.rewardPick = null; c.rewardsOpen = false }, Modifier.focusProperties { right = Stop; up = Stop }.testTag("redeem_done"))
                }
            }
            pick != null -> RedeemConfirm(pick, balance, result, act, c, initial)
            ui.rewardsLoad == RewardsLoad.Loading || ui.rewardsLoad == RewardsLoad.Idle ->
                Text("Loading rewards...", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("rewards_loading"))
            ui.rewardsLoad == RewardsLoad.Failed ->
                Text("Rewards are not available right now.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("rewards_failed"))
            ui.rewards.isEmpty() ->
                Text("This channel has no rewards you can redeem.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("rewards_empty"))
            else -> LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("rewards_list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(ui.rewards, key = { _, r -> r.id }) { i, r ->
                    val block = EngagementMath.rewardBlock(r, balance, now)
                    RewardRow(
                        r, block, ui.points,
                        Modifier.then(if (r.id == listFocusId) Modifier.focusRequester(initial) else Modifier).testTag("reward_$i"),
                    ) {
                        c.rewardLast = r.id
                        c.rewardText = ""
                        act.resetRedeem()
                        c.rewardPick = r
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardRow(r: CustomReward, block: String?, points: PointsUi?, modifier: Modifier, onClick: () -> Unit) {
    SheetRow(onClick, modifier, inert = block != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(FrostColors.Surface, RoundedCornerShape(6.dp))) {
                r.iconUrl?.takeIf { it.isNotEmpty() }?.let { EmoteImage(it, r.title, Modifier.fillMaxSize().padding(2.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (block != null) Text(block, style = MaterialTheme.typography.bodySmall, color = FrostColors.Live, maxLines = 1, modifier = Modifier.testTag("reward_reason"))
                else EngagementMath.rewardNote(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag("reward_note")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                points?.iconUrl?.takeIf { it.isNotEmpty() }?.let { EmoteImage(it, "points", Modifier.size(14.dp)) }
                Text(EngagementMath.grouped(r.cost), style = MaterialTheme.typography.bodySmall, color = tv.frostline.core.ui.FrostColors.OnSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("reward_cost"))
            }
        }
    }
}

@Composable
private fun RedeemConfirm(r: CustomReward, balance: Long, state: ActionState, act: EngagementActions, c: ChatController, initial: FocusRequester) {
    val working = state == ActionState.Working
    val needsText = r.requiresInput
    val canRedeem = !working && (!needsText || c.rewardText.isNotBlank())
    Column(Modifier.testTag("redeem_confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        if (r.prompt.isNotBlank()) Text(r.prompt, style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text("Cost ${EngagementMath.grouped(r.cost)}. You have ${EngagementMath.grouped(balance)}.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender)
        if (needsText) {
            TvTextField(
                value = c.rewardText,
                onValueChange = { c.rewardText = it.take(500) },
                placeholder = "Type your message",
                fieldModifier = Modifier
                    .onDpad(Key.DirectionLeft) { true }
                    .onDpad(Key.DirectionRight) { true }
                    .onDpad(Key.DirectionUp) { true }
                    .focusRequester(initial)
                    .testTag("redeem_input"),
            )
        }
        Result(state, "redeem")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostButton(
                if (working) "Redeeming..." else "Redeem",
                { act.redeem(r, if (needsText) c.rewardText else null) },
                Modifier.then(if (!needsText) Modifier.focusRequester(initial) else Modifier).focusProperties { left = Stop; if (needsText) up = FocusRequester.Default else up = Stop }.testTag("redeem_ok"),
                enabled = canRedeem,
            )
            FrostSecondaryButton("Cancel", { c.rewardPick = null }, Modifier.focusProperties { right = Stop; down = Stop }.testTag("redeem_cancel"), enabled = !working)
        }
    }
}

// ---------------------------------------------------------------- prediction sheet

@Composable
internal fun PredictionSheet(
    ui: EngagementUi,
    act: EngagementActions,
    c: ChatController,
    modifier: Modifier = Modifier,
) {
    val p = ui.prediction
    val balance = ui.points?.balance ?: 0
    val action = ui.predictionAction
    val finished = action is ActionState.Done || action is ActionState.Failed
    val initial = remember { FocusRequester() }
    val now by rememberNowMs(true)
    val step = if (finished) 2 else if (c.predConfirm) 1 else 0
    LaunchedEffect(step, p?.status) { initial.tryFocus() }

    SheetFrame("prediction_sheet", c, modifier) {
        SheetHeader("Prediction", "prediction_close", { c.closePrediction(act) }, ui.points, if (step == 0 && (p == null || !p.open)) Modifier.focusRequester(initial) else Modifier)
        if (p == null) {
            Text("This prediction is over.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("prediction_gone"))
            return@SheetFrame
        }
        Text(p.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface, modifier = Modifier.testTag("prediction_title"))
        Text(predictionStatusLine(p, p.endsAtMs?.let { it - now }), style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("prediction_status"))
        val total = p.totalPoints
        val outcome = p.outcomes.firstOrNull { it.id == c.predOutcome }
        when {
            finished -> {
                Result(action, "prediction")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrostSecondaryButton("Back", { act.resetPrediction(); c.predConfirm = false }, Modifier.focusRequester(initial).focusProperties { left = Stop; down = Stop }.testTag("prediction_back"))
                }
            }
            c.predConfirm && outcome != null -> {
                val working = action == ActionState.Working
                Text("Predict \"${outcome.title}\" with ${EngagementMath.grouped(c.predAmount)} points?", style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag("prediction_confirm_text"))
                Result(action, "prediction")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrostButton(if (working) "Placing..." else "Confirm", { act.predict(outcome.id, c.predAmount) }, Modifier.focusRequester(initial).focusProperties { left = Stop; up = Stop }.testTag("prediction_confirm"), enabled = !working && p.open)
                    FrostSecondaryButton("Back", { c.predConfirm = false }, Modifier.focusProperties { right = Stop; down = Stop }.testTag("prediction_confirm_back"), enabled = !working)
                }
            }
            else -> {
                p.outcomes.forEachIndexed { i, o ->
                    val locked = p.myOutcomeId != null && p.myOutcomeId != o.id
                    SheetRow(
                        { c.predOutcome = o.id },
                        Modifier.then(if (p.open && i == 0) Modifier.focusRequester(initial) else Modifier).testTag("prediction_outcome_$i"),
                        inert = !p.open || locked,
                        selected = c.predOutcome == o.id,
                    ) {
                        Column {
                            OutcomeLine(colorOf(o.color, i), o.title, EngagementMath.percent(o.totalPoints, total), EngagementMath.compact(o.totalPoints) + " pts", o.id == p.myOutcomeId, o.id == p.winningOutcomeId && p.status == PredictionStatus.Resolved, fontSp = 13)
                            Text("${EngagementMath.compact(o.totalUsers)} predictors", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
                        }
                    }
                }
                betResultText(p.result)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = FrostColors.Lavender, modifier = Modifier.testTag("prediction_result")) }
                if (p.myOutcomeId != null && p.result == null) {
                    Text("Your prediction: ${EngagementMath.grouped(p.myPoints)} points on ${p.outcomes.firstOrNull { it.id == p.myOutcomeId }?.title.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender, modifier = Modifier.testTag("prediction_mine"))
                }
                if (p.open) {
                    if (EngagementMath.maxAmount(balance) < EngagementMath.MIN_PREDICTION) {
                        Text("You need at least 10 points to predict.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("prediction_poor"))
                    } else {
                        AmountPicker(balance, c)
                        val ready = outcome != null && c.predAmount in EngagementMath.MIN_PREDICTION..EngagementMath.maxAmount(balance)
                        FrostButton(
                            if (outcome == null) "Pick an outcome" else "Predict ${EngagementMath.grouped(c.predAmount)}",
                            { c.predConfirm = true },
                            Modifier.focusProperties { left = Stop; right = Stop; down = Stop }.testTag("prediction_go"),
                            enabled = ready,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountPicker(balance: Long, c: ChatController) {
    // Keep whatever was typed inside the allowed range as the balance moves.
    LaunchedEffect(balance) { c.predAmount = EngagementMath.clampAmount(if (c.predAmount == 0L) 100 else c.predAmount, balance) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrostSecondaryButton("-", { c.predAmount = EngagementMath.decrease(c.predAmount, balance) }, Modifier.focusProperties { left = Stop }.testTag("prediction_dec"))
        Text(EngagementMath.grouped(c.predAmount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface, modifier = Modifier.width(96.dp).testTag("prediction_amount"))
        FrostSecondaryButton("+", { c.predAmount = EngagementMath.increase(c.predAmount, balance) }, Modifier.focusProperties { right = Stop }.testTag("prediction_inc"))
    }
    val presets = EngagementMath.presets(balance)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEachIndexed { i, (label, value) ->
            FrostSecondaryButton(
                label, { c.predAmount = value },
                Modifier.focusProperties { if (i == 0) left = Stop; if (i == presets.lastIndex) right = Stop }.testTag("prediction_preset_$label"),
            )
        }
    }
}

// ---------------------------------------------------------------- poll sheet

@Composable
internal fun PollSheet(
    ui: EngagementUi,
    act: EngagementActions,
    c: ChatController,
    modifier: Modifier = Modifier,
) {
    val p = ui.poll
    val balance = ui.points?.balance ?: 0
    val action = ui.pollAction
    val finished = action is ActionState.Done || action is ActionState.Failed
    val initial = remember { FocusRequester() }
    val now by rememberNowMs(true)
    val step = if (finished) 2 else if (c.pollChoice != null) 1 else 0
    LaunchedEffect(step, p?.status) { initial.tryFocus() }

    SheetFrame("poll_sheet", c, modifier) {
        SheetHeader("Poll", "poll_close", { c.closePoll(act) }, ui.points, if (step == 0 && (p == null || !p.open)) Modifier.focusRequester(initial) else Modifier)
        if (p == null) {
            Text("This poll is over.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("poll_gone"))
            return@SheetFrame
        }
        Text(p.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface, modifier = Modifier.testTag("poll_title"))
        Text(
            if (p.open) (p.endsAtMs?.let { EngagementMath.clock(it - now) + " left" } ?: "Open") else "Poll ended. Results:",
            style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("poll_status"),
        )
        val choice = p.choices.firstOrNull { it.id == c.pollChoice }
        val total = p.totalVotes
        val top = p.choices.maxOfOrNull { it.votes } ?: 0
        when {
            finished -> {
                Result(action, "poll")
                FrostSecondaryButton("Back", { act.resetPoll(); c.pollChoice = null }, Modifier.focusRequester(initial).focusProperties { left = Stop; right = Stop; down = Stop }.testTag("poll_back"))
            }
            choice != null && p.open -> {
                val working = action == ActionState.Working
                Text("Vote for \"${choice.title}\"?", style = MaterialTheme.typography.bodySmall, color = FrostColors.OnSurface, modifier = Modifier.testTag("poll_confirm_text"))
                Result(action, "poll")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrostButton(if (working) "Voting..." else "Vote", { act.vote(choice.id, false) }, Modifier.focusRequester(initial).focusProperties { left = Stop; up = Stop }.testTag("poll_vote"), enabled = !working)
                    val cost = p.pointsVoteCost
                    if (cost != null) {
                        FrostButton("Vote +${EngagementMath.grouped(cost.toLong())} pts", { act.vote(choice.id, true) }, Modifier.focusProperties { up = Stop }.testTag("poll_vote_points"), enabled = !working && cost <= balance)
                    }
                    FrostSecondaryButton("Back", { c.pollChoice = null }, Modifier.focusProperties { right = Stop; up = Stop }.testTag("poll_confirm_back"), enabled = !working)
                }
                if (cost(p) != null && cost(p)!! > balance) Text("Not enough points for an extra vote.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted)
            }
            else -> {
                p.choices.forEachIndexed { i, ch ->
                    SheetRow(
                        { c.pollChoice = ch.id },
                        Modifier.then(if (p.open && i == 0) Modifier.focusRequester(initial) else Modifier).testTag("poll_choice_$i"),
                        inert = !p.open,
                        selected = ch.id == p.myChoiceId,
                    ) {
                        OutcomeLine(colorOf(null, i), ch.title, EngagementMath.percent(ch.votes, total), EngagementMath.compact(ch.votes), ch.id == p.myChoiceId, p.ended && top > 0 && ch.votes == top, fontSp = 13)
                    }
                }
                if (p.open && p.pointsVoteCost != null) {
                    Text("Extra votes cost ${EngagementMath.grouped(p.pointsVoteCost.toLong())} channel points each.", style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.testTag("poll_extra_note"))
                }
            }
        }
    }
}

private fun cost(p: PollUi): Int? = p.pointsVoteCost
