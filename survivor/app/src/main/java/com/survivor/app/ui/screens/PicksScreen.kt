package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.Col
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.HTable
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.WeekSelector
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.PickResult
import com.survivor.engine.Safety
import com.survivor.engine.Team

@Composable
fun PicksScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var week by remember(e.currentWeek) { mutableStateOf(e.currentWeek) }
    val existing = e.pickOutcomes.firstOrNull { it.pick.week == week }
    var selected by remember(week, existing) { mutableStateOf<Team?>(existing?.pick?.team) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == c.team, onClick = { selected = c.team })
                    Text("${c.team.abbr} ${Fmt.matchup(c.opponent.abbr, c.isHome, c.neutral)}", Modifier.weight(1f))
                    Text("${Fmt.spread(c.teamSpread)} · ${Fmt.pct(c.probability)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selected?.let { vm.recordPick(week, it) } }, enabled = selected != null && selected != existing?.pick?.team) { Text(if (existing == null) "Record pick" else "Change pick") }
                if (existing != null) OutlinedButton(onClick = { vm.clearPick(week); selected = null }) { Text("Clear Week $week") }
            }
        }
        SectionCard("Used teams") {
            if (e.pickOutcomes.isEmpty()) Text("No picks recorded yet.")
            else {
                val rows = e.pickOutcomes
                HTable(
                    columns = listOf(Col("Team", 56.dp), Col("Week", 44.dp, TextAlign.End), Col("Opponent", 80.dp), Col("Result", 62.dp), Col("Available?", 74.dp), Col("Strike", 52.dp)),
                    rows = rows.map { o ->
                        listOf(o.pick.team.abbr, "${o.pick.week}", o.game?.let { Fmt.matchup(it.opponentOf(o.pick.team).abbr, it.isHome(o.pick.team), it.neutralSite) } ?: "no game (bye?)",
                            when (o.result) { PickResult.WIN -> "Win"; PickResult.LOSS -> "Loss"; PickResult.TIE -> "Tie"; PickResult.PENDING -> "Pending" }, "No", if (o.isStrike) "Yes" else "")
                    },
                    rowColor = { i -> when (rows[i].result) { PickResult.WIN -> tierContainer(com.survivor.engine.Tier.STRONG).copy(alpha = 0.5f); PickResult.LOSS, PickResult.TIE -> tierContainer(com.survivor.engine.Tier.AVOID).copy(alpha = 0.5f); else -> androidx.compose.ui.graphics.Color.Unspecified } },
                )
            }
        }
    }
}
