package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.PickResult
import com.survivor.engine.PlannerStatus
import com.survivor.engine.Safety

@Composable
fun PlannerScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("Optimized season path") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("P(0 losses)", Fmt.pct1(e.seasonZeroLoss))
                Stat("P(≤1 loss)", Fmt.pct1(e.seasonAtMostOneLoss))
                Stat("Survive", Fmt.pct1(e.seasonSurvival))
                Stat("Weeks planned", "${e.route.steps.size}")
            }
            Text(
                "Best route given today's lines and FPI. Weeks ${e.currentWeek + 1}–18 use lookahead lines discounted ${Fmt.num(e.settings.futureDiscountPerWeek * 100)}% per week inside the optimizer. It is re-solved on every refresh, pick, or setting change. No team appears twice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rows = e.planner
        HTable(
            columns = listOf(
                Col("Wk", 34.dp), Col("Team", 48.dp), Col("Opp", 64.dp), Col("Venue", 48.dp), Col("Spread", 52.dp, TextAlign.End), Col("Win %", 52.dp, TextAlign.End), Col("Grade", 46.dp),
                Col("Best future spot", 110.dp), Col("Opp cost", 62.dp, TextAlign.End), Col("Status", 92.dp), Col("Result", 56.dp), Col("Strikes", 52.dp, TextAlign.End), Col("Source", 150.dp),
            ),
            rows = rows.map { r ->
                listOf(
                    "${r.week}", r.team?.abbr ?: "—", r.opponent?.abbr ?: "", r.isHome?.let { if (it) "Home" else "Away" } ?: "",
                    Fmt.spread(r.teamSpread), Fmt.pct(r.probability), r.grade ?: "",
                    r.bestFuture?.let { "W${it.week} ${Fmt.matchup(it.opponent.abbr, it.isHome)} ${Fmt.pct(it.probability)}" } ?: (if (r.team != null) "none" else ""),
                    r.opportunityCost?.let { "${Fmt.num(it)}%" } ?: "", r.status.label,
                    when (r.result) { PickResult.WIN -> "Win"; PickResult.LOSS -> "Loss"; PickResult.TIE -> "Tie"; PickResult.PENDING -> "Pending"; null -> "" },
                    "${r.strikesAfter}", r.source?.label ?: "",
                )
            },
            rowColor = { i ->
                val r = rows[i]
                when {
                    r.status == PlannerStatus.LOCKED && r.result == PickResult.LOSS -> MaterialTheme.colorScheme.errorContainer
                    r.status == PlannerStatus.LOCKED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    r.probability != null -> tierContainer(Safety.probabilityTier(r.probability!!)).copy(alpha = 0.55f)
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                }
            },
        )
    }
}
