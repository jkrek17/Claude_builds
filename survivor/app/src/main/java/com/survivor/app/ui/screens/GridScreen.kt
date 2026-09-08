package com.survivor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.premiumContainer
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.GridCell
import com.survivor.engine.REGULAR_SEASON_WEEKS
import com.survivor.engine.Safety
import com.survivor.engine.Team
import com.survivor.engine.Tier

private val CELL_W = 84.dp
private val CELL_H = 34.dp
private val TEAM_W = 64.dp
private val SUMMARY_W = 60.dp

@Composable
fun GridScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var detail by remember { mutableStateOf<Team?>(null) }
    val hScroll = rememberScrollState()
    val teams = Team.entries.sortedWith(compareBy({ it.division.ordinal }, { it.abbr }))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Rows: teams · Columns: weeks. Cell = spread from the team's view and opponent. Dark green >82%, green 75–82%, yellow 68–75%, orange 60–68%, red <60%. ★ = your pick. Tap a team for its future value.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            // Frozen team column
            Column {
                HeaderCell("Team", TEAM_W)
                teams.forEach { t ->
                    Row(
                        Modifier.width(TEAM_W).height(CELL_H).background(if (t in e.usedTeams) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface).clickable { detail = t }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamLogo(t, 20.dp)
                        Text(
                            t.abbr, Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            color = if (t in e.usedTeams) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        )
                    }
                }
            }
            // Scrolling weeks + summary
            Column(Modifier.horizontalScroll(hScroll)) {
                Row {
                    (1..REGULAR_SEASON_WEEKS).forEach { w -> HeaderCell(if (w == e.currentWeek) "Wk $w ●" else "Wk $w", CELL_W) }
                    HeaderCell("Best", SUMMARY_W); HeaderCell("2nd", SUMMARY_W); HeaderCell(">75%", SUMMARY_W); HeaderCell(">80%", SUMMARY_W)
                }
                teams.forEach { t ->
                    val row = e.grid.getValue(t)
                    val fv = e.futureValues.getValue(t)
                    Row {
                        row.forEach { cell -> GridCellView(cell) }
                        SummaryCell(fv.best?.let { "W${it.week} ${Fmt.pct(it.probability)}" } ?: "—")
                        SummaryCell(fv.secondBest?.let { "W${it.week} ${Fmt.pct(it.probability)}" } ?: "—")
                        SummaryCell("${fv.countAbove(0.75)}")
                        SummaryCell("${fv.countAbove(0.80)}")
                    }
                }
            }
        }
    }

    detail?.let { t ->
        val fv = e.futureValues.getValue(t)
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeamLogo(t, 28.dp)
                    Text("${t.fullName}${if (t in e.usedTeams) " (used)" else ""}")
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyValue("FPI rating", Fmt.rating(e.season.ratings[t]?.fpi) + (e.season.ratings[t]?.rank?.let { " (#$it)" } ?: ""))
                    KeyValue("Best future", fv.best?.let { "W${it.week} ${Fmt.matchup(it.opponent.abbr, it.isHome)} ${Fmt.pct(it.probability)}" } ?: "none")
                    KeyValue("Second best", fv.secondBest?.let { "W${it.week} ${Fmt.matchup(it.opponent.abbr, it.isHome)} ${Fmt.pct(it.probability)}" } ?: "none")
                    KeyValue("Average future", Fmt.pct(fv.average))
                    KeyValue("Future >70% / >75% / >80%", "${fv.countAbove(0.70)} / ${fv.countAbove(0.75)} / ${fv.countAbove(0.80)}")
                    Text("Remaining schedule", style = MaterialTheme.typography.titleMedium)
                    fv.games.forEach { g -> Row(Modifier.padding(vertical = 1.dp)) { Text("W${g.week} ${Fmt.matchup(g.opponent.abbr, g.isHome)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TierBadge(Fmt.pct(g.probability), Safety.probabilityTier(g.probability)) } }
                }
            },
        )
    }
}

@Composable
private fun HeaderCell(text: String, w: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(w).height(CELL_H).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SummaryCell(text: String) {
    Box(Modifier.width(SUMMARY_W).height(CELL_H).padding(1.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GridCellView(cell: GridCell?) {
    val base = Modifier.width(CELL_W).height(CELL_H).padding(1.dp)
    if (cell == null) {
        Box(base.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { Text("BYE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val tier = Safety.probabilityTier(cell.probability)
    val bg = when {
        cell.isPast -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        tier == Tier.STRONG -> premiumContainer()
        else -> tierContainer(tier)
    }
    val text = buildString {
        if (cell.pickedHere) append("★ ")
        if (cell.isPast) append(when (cell.won) { true -> "W "; false -> "L "; null -> "" })
        append(Fmt.spread(cell.teamSpread)); append(' '); append(Fmt.matchup(cell.opponent.abbr, cell.isHome, cell.neutral))
    }
    Box(base.background(bg).let { if (cell.pickedHere) it.border(2.dp, MaterialTheme.colorScheme.primary) else it }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 10.sp, maxLines = 1, fontWeight = if (cell.pickedHere) FontWeight.Bold else FontWeight.Normal)
            Text(Fmt.pct(cell.probability), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
