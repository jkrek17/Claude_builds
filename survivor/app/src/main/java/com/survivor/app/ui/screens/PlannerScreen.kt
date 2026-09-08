package com.survivor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.Spacing
import com.survivor.engine.PickResult
import com.survivor.engine.PlannerRow
import com.survivor.engine.PlannerStatus
import com.survivor.engine.Safety

@Composable
fun PlannerScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionCard("Optimized season path") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("P(0 losses)", Fmt.pct1(e.seasonZeroLoss))
                Stat("P(≤1 loss)", Fmt.pct1(e.seasonAtMostOneLoss))
                Stat("Survive", Fmt.pct1(e.seasonSurvival))
                Stat("Weeks planned", "${e.route.steps.size}")
            }
            Text(
                "Best route given today's lines and FPI, re-solved on every refresh, pick, or setting change. No team appears twice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            e.planner.forEachIndexed { i, r ->
                PlannerRowView(r)
                if (i < e.planner.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlannerRowView(r: PlannerRow) {
    val team = r.team
    val probability = r.probability
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("W${r.week}", Modifier.width(30.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (team != null) TeamLogo(team, 36.dp) else StatusDot()
        Column(Modifier.weight(1f)) {
            if (team != null) {
                Text("${team.abbr} ${r.opponent?.let { Fmt.matchup(it.abbr, r.isHome ?: true) } ?: ""}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        Fmt.spread(r.teamSpread).takeIf { it != "—" },
                        r.bestFuture?.let { "Best later: W${it.week} ${Fmt.pct(it.probability)}" },
                        r.opportunityCost?.let { "Cost ${Fmt.num(it)}%" },
                        r.source?.label,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(r.status.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (probability != null) Text(Fmt.pct(probability), style = MaterialTheme.typography.titleSmall)
        Column(horizontalAlignment = Alignment.End) {
            StatusChip(r.status)
            if (probability != null) TierBadge(r.grade ?: "-", Safety.probabilityTier(probability))
            r.result?.let { res ->
                Text(
                    when (res) { PickResult.WIN -> "Win"; PickResult.LOSS -> "Loss"; PickResult.TIE -> "Tie"; PickResult.PENDING -> "Pending" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (res == PickResult.LOSS || res == PickResult.TIE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot() {
    Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(Modifier.width(10.dp).padding(2.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)))
    }
}

@Composable
private fun StatusChip(status: PlannerStatus) {
    val (bg, fg) = when (status) {
        PlannerStatus.LOCKED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PlannerStatus.RECOMMENDED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        PlannerStatus.PROJECTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        PlannerStatus.MISSED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PlannerStatus.NO_DATA -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}
