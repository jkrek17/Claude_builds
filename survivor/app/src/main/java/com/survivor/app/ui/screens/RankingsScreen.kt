package com.survivor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.Col
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.HTable
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.components.WeekSelector
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.Evaluation
import com.survivor.engine.Safety
import com.survivor.engine.TeamWeekEvaluation

private val E = TextAlign.End

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingsScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var week by remember(e.currentWeek) { mutableStateOf(e.currentWeek) }
    var tableView by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TeamWeekEvaluation?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            WeekSelector(week, { week = it }) { w -> if (w == e.currentWeek) "Wk $w ●" else "Wk $w" }
        }
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = !tableView, onClick = { tableView = false }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) {
                Icon(Icons.Filled.ViewAgenda, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Cards")
            }
            SegmentedButton(selected = tableView, onClick = { tableView = true }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) {
                Icon(Icons.Filled.TableRows, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Table")
            }
        }
        if (week == e.currentWeek) {
            Text("Week $week · ${e.rankings.size} available teams, sorted by Survivor Safety Score. Tap for the full breakdown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tableView) CurrentWeekTable(e) { detail = it } else CurrentWeekCards(e) { detail = it }
        } else {
            Text("Week $week projections. Rankings with Safety Scores are computed for the current week; future weeks show win probabilities from lookahead lines blended with FPI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val cells = e.grid.mapNotNull { (_, row) -> row[week - 1] }.sortedByDescending { it.probability }
            if (tableView) {
                HTable(
                    columns = listOf(Col("#", 30.dp), Col("Team", 48.dp), Col("Opp", 70.dp), Col("Spread", 52.dp, E), Col("Win %", 52.dp, E), Col("Used", 44.dp), Col("Source", 170.dp)),
                    rows = cells.mapIndexed { i, c -> listOf("${i + 1}", c.team.abbr, Fmt.matchup(c.opponent.abbr, c.isHome, c.neutral), Fmt.spread(c.teamSpread), Fmt.pct(c.probability), if (c.team in e.usedTeams) "Yes" else "", c.source.label) },
                    rowColor = { i -> val c = cells[i]; if (c.team in e.usedTeams) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else tierContainer(Safety.probabilityTier(c.probability)).copy(alpha = 0.55f) },
                )
            } else {
                SectionCard {
                    cells.forEachIndexed { i, c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text("${i + 1}", Modifier.padding(end = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TeamLogo(c.team, 28.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.team.abbr, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(Fmt.matchup(c.opponent.abbr, c.isHome, c.neutral), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (c.team in e.usedTeams) Text("Used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else TierBadge(Fmt.pct(c.probability), Safety.probabilityTier(c.probability))
                        }
                        if (i < cells.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    detail?.let { r ->
        ModalBottomSheet(onDismissRequest = { detail = null }) { DetailSheet(r) }
    }
}

@Composable
private fun CurrentWeekCards(e: Evaluation, onSelect: (TeamWeekEvaluation) -> Unit) {
    val rows = e.rankings + e.unavailable
    SectionCard {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(r) }.padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(if (r.used) "—" else "${r.rank}", Modifier.padding(end = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TeamLogo(r.team, 32.dp)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.team.abbr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        if (r.used) Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.matchup(r.opponent.abbr, r.situation.isHome, r.situation.neutral), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!r.used) {
                        val pickShare = r.leverage?.let { " · pick % ${Fmt.pct(it.pickShare)}" } ?: ""
                        Text("${r.futureCostLabel} future cost · path loss ${Fmt.num(r.seasonPathLoss)}%$pickShare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.pct(r.probability), style = MaterialTheme.typography.titleSmall)
                    if (!r.used) {
                        Text("Safety ${Fmt.score(r.safetyScore)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TierBadge(r.grade, r.tier)
                    }
                }
            }
            if (i < rows.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun CurrentWeekTable(e: Evaluation, onSelect: (TeamWeekEvaluation) -> Unit) {
    val rows = e.rankings + e.unavailable
    HTable(
        columns = listOf(
            Col("#", 30.dp), Col("Team", 48.dp), Col("Opp", 64.dp), Col("H/A", 36.dp), Col("Div", 34.dp), Col("Spread", 52.dp, E), Col("ML", 52.dp, E),
            Col("No-vig %", 60.dp, E), Col("Model %", 58.dp, E), Col("FPI %", 52.dp, E), Col("Tm FPI", 52.dp, E), Col("Opp FPI", 56.dp, E),
            Col("Rest ±", 48.dp, E), Col("TZ", 30.dp, E), Col("Inj", 40.dp, E), Col("QB", 40.dp, E), Col("Wx", 40.dp, E),
            Col("Best future", 82.dp), Col("Fut >75%", 60.dp, E), Col("Opp cost", 62.dp, E), Col("Path loss", 62.dp, E), Col("Pick %", 54.dp, E), Col("Used", 40.dp), Col("Safety", 50.dp, E), Col("Grade", 44.dp), Col("Source", 150.dp), Col("Notes", 200.dp),
        ),
        rows = rows.map { r ->
            listOf(
                if (r.used) "—" else "${r.rank}", r.team.abbr, r.opponent.abbr, if (r.situation.neutral) "N" else if (r.situation.isHome) "H" else "A", if (r.situation.divisional) "Y" else "",
                Fmt.spread(r.estimate.teamSpread), Fmt.ml(r.estimate.teamMoneyline),
                Fmt.pct(r.estimate.marketProbability), Fmt.pct(r.probability), Fmt.pct(r.estimate.fpiProbability),
                Fmt.rating(e.season.ratings[r.team]?.fpi), Fmt.rating(e.season.ratings[r.opponent]?.fpi),
                Fmt.signed(r.situation.restAdvantageDays.toDouble()), "${r.situation.timeZonesCrossed}",
                r.adjustment?.injuryPoints?.let { Fmt.signed(it) } ?: "", r.adjustment?.qbPoints?.let { if (it != 0.0) Fmt.signed(it) else "" } ?: "", r.adjustment?.weatherPoints?.let { if (it != 0.0) Fmt.signed(it) else "" } ?: "",
                r.futureValue.best?.let { "W${it.week} ${Fmt.pct(it.probability)}" } ?: "none", "${r.futureValue.countAbove(0.75)}", if (r.used) "" else "${Fmt.num(r.opportunityCost)}%", if (r.used) "" else "${Fmt.num(r.seasonPathLoss)}%",
                r.leverage?.let { Fmt.pct(it.pickShare) } ?: "—",
                if (r.used) "Yes" else "", if (r.used) "" else Fmt.score(r.safetyScore), if (r.used) "" else r.grade, r.estimate.source.label, notes(r),
            )
        },
        rowColor = { i -> val r = rows[i]; if (r.used) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else tierContainer(r.tier).copy(alpha = 0.55f) },
        onRowClick = { i -> onSelect(rows[i]) },
    )
}

@Composable
private fun DetailSheet(r: TeamWeekEvaluation) {
    Column(Modifier.padding(Spacing.md).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamLogo(r.team, 44.dp)
            Column {
                Text(r.team.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(Fmt.matchup(r.opponent.abbr, r.situation.isHome, r.situation.neutral), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TierBadge("Win ${Fmt.pct(r.probability)}", r.tier)
            TierBadge("Safety ${Fmt.score(r.safetyScore)}", r.tier)
            TierBadge(r.grade, r.tier)
        }
        Text(r.estimate.source.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Safety Score breakdown", style = MaterialTheme.typography.titleMedium)
        val c = r.components
        KeyValue("Base (win % × 100)", Fmt.num(c.base))
        KeyValue("Road penalty", "−${Fmt.num(c.roadPenalty)}")
        KeyValue("Divisional penalty", "−${Fmt.num(c.divisionalPenalty)}")
        KeyValue("Rest disadvantage", "−${Fmt.num(c.restPenalty)}")
        KeyValue("Travel", "−${Fmt.num(c.travelPenalty)}")
        KeyValue("QB uncertainty", "−${Fmt.num(c.qbPenalty)}")
        KeyValue("Market confidence", Fmt.signed(c.marketAdjustment))
        KeyValue("Future opportunity cost (${Fmt.num(r.opportunityCost)}% × weight)", "−${Fmt.num(c.futureCost)}")
        KeyValue("Scarcity (${r.futureValue.premiumSpots} premium spots)", "−${Fmt.num(c.scarcityPenalty)}")
        KeyValue("Season path loss (${Fmt.num(r.seasonPathLoss)}% × weight)", "−${Fmt.num(c.pathPenalty)}")
        KeyValue("On optimizer's unconstrained path", if (r.onOptimalPath) "Yes" else "No")
        KeyValue("Below-minimum penalty", "−${Fmt.num(c.thresholdPenalty)}")
        KeyValue("Ownership leverage", Fmt.signed(c.leverageAdjustment))
        HorizontalDivider()
        KeyValue("Total", Fmt.num(c.total))
        HorizontalDivider()
        Text("Future value", style = MaterialTheme.typography.titleMedium)
        KeyValue("Best future", r.futureValue.best?.let { "W${it.week} ${Fmt.matchup(it.opponent.abbr, it.isHome)} ${Fmt.pct(it.probability)}" } ?: "none")
        KeyValue("Second best", r.futureValue.secondBest?.let { "W${it.week} ${Fmt.matchup(it.opponent.abbr, it.isHome)} ${Fmt.pct(it.probability)}" } ?: "none")
        KeyValue("Average future win %", Fmt.pct(r.futureValue.average))
        KeyValue("Future games >70 / >75 / >80%", "${r.futureValue.countAbove(0.70)} / ${r.futureValue.countAbove(0.75)} / ${r.futureValue.countAbove(0.80)}")
        r.leverage?.let { l ->
            HorizontalDivider(); Text("Pool leverage", style = MaterialTheme.typography.titleMedium)
            KeyValue("Estimated pick share", Fmt.pct(l.pickShare)); KeyValue("Expected pool equity", String.format(java.util.Locale.US, "%.3f", l.expectedEquity)); KeyValue("Leverage score", Fmt.signed(l.leverageScore))
        }
        HorizontalDivider()
        Text(notes(r).ifBlank { "No additional notes." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun notes(r: TeamWeekEvaluation): String {
    val n = mutableListOf<String>()
    if (r.situation.divisional) n += "divisional"
    if (r.situation.restAdvantageDays <= -3) n += "short rest"
    if (r.situation.restAdvantageDays >= 3) n += "rest edge"
    if (r.situation.timeZonesCrossed >= 2) n += "long travel"
    if (r.futureValue.premiumSpots >= 2) n += "${r.futureValue.premiumSpots} premium future spots"
    if (r.opportunityCost >= 12) n += "high future cost"
    r.adjustment?.note?.takeIf { it.isNotBlank() }?.let { n += it }
    return n.joinToString(", ")
}
