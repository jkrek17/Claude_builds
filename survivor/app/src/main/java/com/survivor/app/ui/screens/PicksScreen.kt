package com.survivor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.WeekSelector
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.engine.PickResult
import com.survivor.engine.Team

@Composable
fun PicksScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var week by remember(e.currentWeek) { mutableStateOf(e.currentWeek) }
    val existing = e.pickOutcomes.firstOrNull { it.pick.week == week }
    var selected by remember(week, existing) { mutableStateOf<Team?>(existing?.pick?.team) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Strikes", "${e.strikesUsed} / 2")
                Stat("Teams used", "${e.usedTeams.size}")
                Stat("Available", "${Team.COUNT - e.usedTeams.size}")
            }
            Text("Results and strikes fill in automatically from final scores after you refresh. A tie counts as a strike.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Record a pick") {
            WeekSelector(week, { week = it }) { w -> val p = e.pickOutcomes.firstOrNull { it.pick.week == w }; if (p != null) "Wk $w · ${p.pick.team.abbr}" else "Wk $w" }
            val cells = e.grid.mapNotNull { (_, row) -> row[week - 1] }.sortedByDescending { it.probability }
            val availableTeams = cells.filter { it.team !in e.usedTeams || existing?.pick?.team == it.team }
            if (availableTeams.isEmpty()) Text("No available teams play in Week $week.")
            availableTeams.forEach { c ->
                val isSelected = selected == c.team
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { selected = c.team }
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    RadioButton(selected = isSelected, onClick = { selected = c.team })
                    TeamLogo(c.team, 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.team.abbr, fontWeight = FontWeight.SemiBold)
                        Text(Fmt.matchup(c.opponent.abbr, c.isHome, c.neutral), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${Fmt.spread(c.teamSpread)} · ${Fmt.pct(c.probability)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = { selected?.let { vm.recordPick(week, it) } }, enabled = selected != null && selected != existing?.pick?.team) { Text(if (existing == null) "Record pick" else "Change pick") }
                if (existing != null) OutlinedButton(onClick = { vm.clearPick(week); selected = null }) { Text("Clear Week $week") }
            }
        }
        SectionCard("Used teams") {
            if (e.pickOutcomes.isEmpty()) Text("No picks recorded yet.")
            else {
                e.pickOutcomes.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TeamLogo(o.pick.team, 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${o.pick.team.abbr} · Week ${o.pick.week}", fontWeight = FontWeight.SemiBold)
                            Text(
                                o.game?.let { Fmt.matchup(it.opponentOf(o.pick.team).abbr, it.isHome(o.pick.team), it.neutralSite) } ?: "no game (bye?)",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ResultChip(o.result)
                    }
                    if (i < e.pickOutcomes.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ResultChip(result: PickResult) {
    val (label, color) = when (result) {
        PickResult.WIN -> "Win ✓" to tierColor(com.survivor.engine.Tier.STRONG)
        PickResult.LOSS -> "Loss - strike" to tierColor(com.survivor.engine.Tier.AVOID)
        PickResult.TIE -> "Tie - strike" to tierColor(com.survivor.engine.Tier.AVOID)
        PickResult.PENDING -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
}
