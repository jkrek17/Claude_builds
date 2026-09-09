package com.survivor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.Expandable
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.FreshnessLine
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.StrikePips
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.engine.Evaluation
import com.survivor.engine.REGULAR_SEASON_WEEKS
import com.survivor.engine.Signal
import com.survivor.engine.StabilityReport
import com.survivor.engine.Team
import com.survivor.engine.TeamWeekEvaluation

@Composable
fun DashboardScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { OnboardingScreen(vm); return }
    val robust by vm.robustPlan.collectAsStateWithLifecycle()
    val robustPlanning by vm.robustPlanning.collectAsStateWithLifecycle()
    val bettingBoard by vm.bettingBoard.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item { StatusStrip(e) }
        item {
            if (e.eliminated) SectionCard("Entry eliminated") { Text("Two strikes recorded. The model still shows the best available picks in case your pool lets you keep playing.") }
            else HeroCard(e, vm, onNavigate)
        }
        if (!e.eliminated && e.recommended != null) item { RobustnessRow(e, robust, robustPlanning) }
        e.explanation?.let { ex ->
            item {
                Expandable("Why this pick", startExpanded = true) {
                    ExplainParagraph("Why it's safe", ex.whySafe)
                    ExplainParagraph("Why now", ex.whyNow)
                    ExplainParagraph("What we give up", ex.whatWeGiveUp)
                    ex.futureValueWarning?.let {
                        Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (ex.alternatives.isNotEmpty()) item {
                SectionCard {
                    Text("Alternatives", style = MaterialTheme.typography.titleMedium)
                    ex.alternatives.forEachIndexed { i, (alt, reason) ->
                        AlternativeRow(alt, reason)
                        if (i < ex.alternatives.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item { TopTenList(e, onNavigate) }
        item { SeasonOutlook(e, onNavigate) }
        val lineShopPicks = bettingBoard?.picks?.filter { it.signal == Signal.LINE_SHOP }.orEmpty()
        if (lineShopPicks.isNotEmpty()) item { BetsTeaserCard(lineShopPicks.size, lineShopPicks.maxOf { it.ev }, onNavigate) }
    }
}

/** Only shown when there's at least one line-shopping edge; never surfaces model-vs-market picks here -
 *  Home stays about the survivor recommendation, this is just a pointer to the separate Bets tab. */
@Composable
private fun BetsTeaserCard(count: Int, bestEv: Double, onNavigate: (String) -> Unit) {
    SectionCard {
        Text(
            "$count line-shopping edge${if (count == 1) "" else "s"} this week, best ${Fmt.evPct(bestEv)} EV",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { onNavigate(Routes.BETS) }, modifier = Modifier.fillMaxWidth()) { Text("Open Bets") }
    }
}

@Composable
private fun StatusStrip(e: Evaluation) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Week ${e.currentWeek}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StrikePips(e.strikesUsed)
            Text("${e.usedTeams.size} teams used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FreshnessLine(e)
    }
}

@Composable
private fun HeroCard(e: Evaluation, vm: AppViewModel, onNavigate: (String) -> Unit) {
    val rec = e.recommended
    if (rec == null) { SectionCard("No available team this week") { Text("Every team playing this week has already been used, or no lines are loaded.") }; return }
    val locked = e.currentPick != null
    SectionCard {
        Text(if (locked) "Locked pick · Week ${e.currentWeek}" else "Recommended · Week ${e.currentWeek}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamLogo(rec.team, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(rec.team.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TeamLogo(rec.opponent, 20.dp)
                    Text(
                        "${Fmt.matchup(rec.opponent.abbr, rec.situation.isHome, rec.situation.neutral)} · ${if (rec.situation.isHome) "Home" else "Away"}${if (rec.situation.divisional) " · Divisional" else ""}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.pct(rec.probability), style = MaterialTheme.typography.displaySmall, color = tierColor(rec.tier))
                TierBadge("Grade ${rec.grade}", rec.tier)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Spread", Fmt.spread(rec.estimate.teamSpread))
            Stat("Moneyline", Fmt.ml(rec.estimate.teamMoneyline))
            Stat("Source", rec.estimate.source.label, modifier = Modifier.width(140.dp))
        }
        rec.leverage?.let { lev ->
            Text(
                "Yahoo pick share: ${Fmt.pct(lev.pickShare)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        e.explanation?.headline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (rec.probability < e.settings.minimumAcceptableWinProbability) {
            Text("⚠ Below your ${Fmt.pct(e.settings.minimumAcceptableWinProbability)} minimum acceptable win probability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!locked) {
            Button(onClick = { vm.recordPick(e.currentWeek, rec.team) }, modifier = Modifier.fillMaxWidth()) { Text("Record ${rec.team.abbr} for Week ${e.currentWeek}") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Locked ✓", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { onNavigate(Routes.PICKS) }) { Text("Change") }
            }
        }
    }
}

@Composable
private fun RobustnessRow(e: Evaluation, robust: StabilityReport?, planning: Boolean) {
    val rec = e.recommended ?: return
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Decision robustness", style = MaterialTheme.typography.titleSmall)
            if (planning) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
        }
        if (robust == null) {
            Text("Checking how sensitive this pick is to line movement…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val share = robust.currentWeekShares[rec.team] ?: 0.0
            val topShare = robust.currentWeekShares.values.maxOrNull() ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Chosen in ${Fmt.pct(share)} of ${robust.scenarios} line scenarios", style = MaterialTheme.typography.bodyMedium)
                if (topShare < 0.6) AssistChip(onClick = {}, label = { Text("Toss-up") })
            }
            val robustPick = robust.robustPick
            if (robustPick != null && robustPick != rec.team) {
                Text(
                    "Across simulated line moves, ${robustPick.abbr} scores best on average - worth a look if you want the most robust pick rather than today's best number.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplainParagraph(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlternativeRow(alt: TeamWeekEvaluation, reason: String) {
    var expanded by remember(alt.team) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamLogo(alt.team, 32.dp)
            Column(Modifier.weight(1f)) {
                Text("#${alt.rank} ${alt.team.abbr}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(Fmt.matchup(alt.opponent.abbr, alt.situation.isHome, alt.situation.neutral), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                alt.leverage?.let { lev -> Text("Pick share ${Fmt.pct(lev.pickShare)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(Fmt.pct(alt.probability), style = MaterialTheme.typography.titleSmall)
            TierBadge(alt.grade, alt.tier)
        }
        Text(
            reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun TopTenList(e: Evaluation, onNavigate: (String) -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Top 10 this week", style = MaterialTheme.typography.titleMedium)
            Text("${e.rankings.size} available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.rankings.take(10).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${r.rank}", Modifier.width(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TeamLogo(r.team, 28.dp)
                Text(r.team.abbr, Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(Fmt.matchup(r.opponent.abbr, r.situation.isHome, r.situation.neutral), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.pct(r.probability), style = MaterialTheme.typography.bodyMedium)
                TierBadge(r.grade, r.tier)
            }
        }
        OutlinedButton(onClick = { onNavigate(Routes.RANKINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Full rankings") }
    }
}

@Composable
private fun SeasonOutlook(e: Evaluation, onNavigate: (String) -> Unit) {
    SectionCard("Season outlook") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(Fmt.pct(e.poolWinProbability), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("chance to win a ${e.settings.poolEntries}-entry pool", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Spacing.xs))
        }
        e.expectedPoolEndWeek?.let { end ->
            Text(
                if (end > REGULAR_SEASON_WEEKS) "The field is expected to still have other entries alive after Week $REGULAR_SEASON_WEEKS."
                else "The last other entry is expected to fall around Week ${Fmt.num(end)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("P(0 losses)", Fmt.pct1(e.seasonZeroLoss))
            Stat("P(≤1 loss)", Fmt.pct1(e.seasonAtMostOneLoss))
            Stat("Weeks alive (E)", Fmt.num(e.seasonExpectedWeeksAlive))
        }
        HorizontalDivider()
        Text("Optimized route from this pick", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val raw = e.routeRawProbabilities
        val routeScroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalScroll(routeScroll), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            e.route.steps.forEachIndexed { i, s ->
                val p = raw.getOrNull(i) ?: s.probability
                RouteChip(s.week, s.team, p, s.locked)
            }
        }
        OutlinedButton(onClick = { onNavigate(Routes.SEASON) }, modifier = Modifier.fillMaxWidth()) { Text("Open season planner") }
    }
}

@Composable
private fun RouteChip(week: Int, team: Team, probability: Double, locked: Boolean) {
    Column(
        Modifier
            .width(64.dp)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("W$week${if (locked) " 🔒" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TeamLogo(team, 32.dp)
        Text(team.abbr, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(Fmt.pct(probability), style = MaterialTheme.typography.labelSmall)
    }
}
