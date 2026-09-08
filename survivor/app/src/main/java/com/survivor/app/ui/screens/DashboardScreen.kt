package com.survivor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.data.RefreshStatus
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.Col
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.FreshnessLine
import com.survivor.app.ui.components.HTable
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.tierColor
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.Evaluation
import com.survivor.engine.TeamWeekEvaluation
import com.survivor.engine.Tier

@Composable
fun DashboardScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val evaluating by vm.evaluating.collectAsStateWithLifecycle()
    val e = eval
    if (e == null) {
        if (refresh is RefreshStatus.Running) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.padding(8.dp))
                Text((refresh as RefreshStatus.Running).message, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (evaluating) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else {
            EmptyState(
                "No NFL data yet. Download the schedule, DraftKings lines and ESPN FPI projections for every week to get started. Nothing needs to be typed in.",
                "Download NFL data",
            ) { vm.refreshNflData() }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HeaderCard(e) }
        item {
            if (e.eliminated) SectionCard("Entry eliminated") { Text("Two strikes recorded. The model still shows the best available picks in case your pool lets you keep playing.") }
            else RecommendationCard(e, vm, onNavigate)
        }
        e.explanation?.let { ex ->
            item {
                SectionCard("Why this pick") {
                    Text("Why it is safe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(ex.whySafe)
                    Text("Why use it now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(ex.whyNow)
                    Text("What we give up", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(ex.whatWeGiveUp)
                }
            }
            if (ex.alternatives.isNotEmpty()) item {
                SectionCard("Alternatives") {
                    ex.alternatives.forEachIndexed { i, (alt, reason) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("#${alt.rank} ${alt.team.abbr}", fontWeight = FontWeight.Bold)
                            Text(Fmt.matchup(alt.opponent.abbr, alt.situation.isHome, alt.situation.neutral), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            TierBadge("${Fmt.pct(alt.probability)} · ${alt.grade}", alt.tier)
                        }
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                        if (i < ex.alternatives.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item { RankedTable(e, onNavigate) }
        item { SeasonOutlook(e, onNavigate) }
    }
}

@Composable
private fun HeaderCard(e: Evaluation) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Week", "${e.currentWeek}")
            Stat("Strikes used", "${e.strikesUsed} / 2", color = if (e.strikesUsed >= 1) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            Stat("Losses left", "${e.strikesAllowed}")
            Stat("Season survival", Fmt.pct(e.seasonSurvival))
        }
        FreshnessLine(e)
        if (e.usedTeams.isNotEmpty()) {
            Text("Used: " + e.pickOutcomes.joinToString { "${it.pick.team.abbr} (W${it.pick.week}${when (it.result) { com.survivor.engine.PickResult.WIN -> " ✓"; com.survivor.engine.PickResult.LOSS -> " ✗"; com.survivor.engine.PickResult.TIE -> " tie"; else -> "" }})" },
                style = MaterialTheme.typography.bodySmall)
        } else Text("No teams used yet.", style = MaterialTheme.typography.bodySmall)
        Text("Strategy: ${e.settings.strategy.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecommendationCard(e: Evaluation, vm: AppViewModel, onNavigate: (String) -> Unit) {
    val rec = e.recommended
    if (rec == null) { SectionCard("No available team this week") { Text("Every team playing this week has already been used, or no lines are loaded.") }; return }
    val locked = e.currentPick != null
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (locked) "Locked pick · Week ${e.currentWeek}" else "Recommended · Week ${e.currentWeek}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("${rec.team.abbr}  ${rec.team.nickname}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${Fmt.matchup(rec.opponent.fullName, rec.situation.isHome, rec.situation.neutral)} · ${if (rec.situation.isHome) "Home" else "Away"}${if (rec.situation.divisional) " · Divisional" else ""}", style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.pct(rec.probability), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tierColor(rec.tier))
                TierBadge("Safety ${Fmt.score(rec.safetyScore)} · ${rec.grade}", rec.tier)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Spread", Fmt.spread(rec.estimate.teamSpread))
            Stat("Moneyline", Fmt.ml(rec.estimate.teamMoneyline))
            Stat("Future cost", "${rec.futureCostLabel} (${Fmt.num(rec.opportunityCost)}%)")
            Stat("Best later", rec.futureValue.best?.let { "W${it.week} ${Fmt.pct(it.probability)}" } ?: "—")
        }
        Text("Source: ${rec.estimate.source.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.explanation?.futureValueWarning?.let {
            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (rec.probability < e.settings.minimumAcceptableWinProbability) Text("⚠ Below your ${Fmt.pct(e.settings.minimumAcceptableWinProbability)} minimum acceptable win probability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!locked) Button(onClick = { vm.recordPick(e.currentWeek, rec.team) }) { Text("Record ${rec.team.abbr} as Week ${e.currentWeek} pick") }
            else OutlinedButton(onClick = { onNavigate(Routes.PICKS) }) { Text("Change pick") }
        }
    }
}

@Composable
private fun RankedTable(e: Evaluation, onNavigate: (String) -> Unit) {
    SectionCard("This week's ranked picks") {
        val rows = e.rankings.take(10)
        HTable(
            columns = listOf(Col("#", 32.dp), Col("Team", 52.dp), Col("Opp", 70.dp), Col("Win %", 52.dp, androidx.compose.ui.text.style.TextAlign.End), Col("Safety", 52.dp, androidx.compose.ui.text.style.TextAlign.End), Col("Future cost", 78.dp), Col("Grade", 48.dp)),
            rows = rows.map { r -> listOf("${r.rank}", r.team.abbr, Fmt.matchup(r.opponent.abbr, r.situation.isHome, r.situation.neutral), Fmt.pct(r.probability), Fmt.score(r.safetyScore), "${r.futureCostLabel}", r.grade) },
            rowColor = { i -> tierContainer(rows[i].tier).copy(alpha = 0.55f) },
        )
        OutlinedButton(onClick = { onNavigate(Routes.RANKINGS) }) { Text("Full rankings (${e.rankings.size} available teams)") }
    }
}

@Composable
private fun SeasonOutlook(e: Evaluation, onNavigate: (String) -> Unit) {
    SectionCard("Season outlook") {
        KeyValue("P(zero losses rest of season)", Fmt.pct1(e.seasonZeroLoss))
        KeyValue("P(one loss or fewer)", Fmt.pct1(e.seasonAtMostOneLoss))
        KeyValue("P(survive given ${e.strikesUsed} strike${if (e.strikesUsed == 1) "" else "s"})", Fmt.pct1(e.seasonSurvival))
        HorizontalDivider()
        Text("Optimized route from this pick (recalculated every refresh)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val alt = e.unconstrainedRoute.team(e.currentWeek)
        if (alt != null && e.recommended != null && alt != e.recommended!!.team) Text("Unconstrained optimum starts with ${alt.abbr} (${Fmt.pct1(e.unconstrainedRoute.survival)} vs ${Fmt.pct1(e.route.survival)} discounted survival).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val steps = e.route.steps
        val raw = e.routeRawProbabilities
        steps.forEachIndexed { i, s ->
            val g = e.season.gameFor(s.team, s.week)
            val p = raw.getOrNull(i) ?: s.probability
            Row(Modifier.fillMaxWidth().background(tierContainer(com.survivor.engine.Safety.probabilityTier(p)).copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text("W${s.week}", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text("${s.team.abbr} ${g?.let { Fmt.matchup(it.opponentOf(s.team).abbr, it.isHome(s.team), it.neutralSite) } ?: ""}${if (s.locked) " (locked)" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(Fmt.pct(p), style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = { onNavigate(Routes.PLANNER) }) { Text("Open planner") }
    }
}
