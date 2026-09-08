package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

@Composable
fun SimulationScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val results by vm.simulation.collectAsStateWithLifecycle()
    val running by vm.simulating.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Monte Carlo season simulation") {
            Text("Simulates ${e.settings.monteCarloIterations} seasons from Week ${e.currentWeek} with ${e.strikesUsed} strike(s) used, along each strategy's route. Each week is an independent coin flip at that game's undiscounted win probability. Routes use available teams only.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.runMonteCarlo() }, enabled = !running) { Text(if (running) "Running…" else "Run simulation") }
            if (running) CircularProgressIndicator()
        }
        if (results.isNotEmpty()) {
            HTable(
                columns = listOf(Col("Strategy", 150.dp), Col("Survive season", 80.dp, TextAlign.End), Col("Zero-loss", 70.dp, TextAlign.End), Col("Reach W10", 70.dp, TextAlign.End), Col("Reach W14", 70.dp, TextAlign.End), Col("Reach W18", 70.dp, TextAlign.End), Col("Exp. strikes", 76.dp, TextAlign.End), Col("Exp. elim wk", 80.dp, TextAlign.End)),
                rows = results.map { r -> listOf(r.strategy, Fmt.pct1(r.surviveSeason), Fmt.pct1(r.zeroLossFinish), Fmt.pct1(r.reachWeek10), Fmt.pct1(r.reachWeek14), Fmt.pct1(r.reachWeek18), String.format(java.util.Locale.US, "%.2f", r.expectedStrikes), r.expectedEliminationWeek?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—") },
            )
            results.forEach { r ->
                SectionCard(r.strategy) {
                    Text(r.route.steps.joinToString(" · ") { "W${it.week} ${it.team.abbr} ${Fmt.pct(it.probability)}" }, style = MaterialTheme.typography.bodySmall)
                    Text("Analytic survival along this route: ${Fmt.pct1(r.route.survival)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Contrarian only differs from Future-value optimized when pick shares have been entered in Weekly Inputs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
