package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.Col
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.HTable
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.theme.Spacing
import java.util.Locale

@Composable
fun SimulationScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val results by vm.simulation.collectAsStateWithLifecycle()
    val running by vm.simulating.collectAsStateWithLifecycle()
    val comparison by vm.policyComparison.collectAsStateWithLifecycle()
    val comparing by vm.policyComparing.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var seasonsText by remember { mutableStateOf("500") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard("Route comparison (Monte Carlo)") {
            Text("Simulates ${e.settings.monteCarloIterations} seasons from Week ${e.currentWeek} with ${e.strikesUsed} strike(s) used, along each strategy's fixed route.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.runMonteCarlo() }, enabled = !running) { Text(if (running) "Running…" else "Run simulation") }
            if (running) CircularProgressIndicator()
        }
        if (results.isNotEmpty()) {
            HTable(
                columns = listOf(
                    Col("Strategy", 150.dp), Col("Survive", 68.dp, TextAlign.End), Col("Zero-loss", 66.dp, TextAlign.End), Col("Wk10", 56.dp, TextAlign.End), Col("Wk14", 56.dp, TextAlign.End), Col("Wk18", 56.dp, TextAlign.End),
                    Col("Weeks alive (E)", 90.dp, TextAlign.End), Col("Pool win %", 78.dp, TextAlign.End), Col("Exp. strikes", 76.dp, TextAlign.End),
                ),
                rows = results.map { r ->
                    listOf(
                        r.strategy, Fmt.pct1(r.surviveSeason), Fmt.pct1(r.zeroLossFinish), Fmt.pct1(r.reachWeek10), Fmt.pct1(r.reachWeek14), Fmt.pct1(r.reachWeek18),
                        String.format(Locale.US, "%.2f", r.expectedWeeksAlive), Fmt.pct1(r.poolWinProbability), String.format(Locale.US, "%.2f", r.expectedStrikes),
                    )
                },
            )
            Text("Contrarian only differs from Future-value optimized when pick shares have been entered in Weekly Inputs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Closed-loop policy comparison") {
            Text(
                "Unlike the route comparison above, this re-decides every week from freshly re-observed (noisy) lines - the way a real season actually plays out - for five policies: Greedy, Optimized, Pool-win, Zero-loss and a Threshold guard.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = seasonsText, onValueChange = { seasonsText = it },
                label = { Text("Seasons to simulate (200–2000)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.runPolicyComparison(seasonsText.toIntOrNull() ?: 500) }, enabled = !comparing) { Text(if (comparing) "Running…" else "Run policy comparison") }
            if (comparing) CircularProgressIndicator()
        }
        comparison?.let { cmp ->
            SectionCard {
                HTable(
                    columns = listOf(
                        Col("Policy", 190.dp), Col("Survive", 68.dp, TextAlign.End), Col("Zero-loss", 66.dp, TextAlign.End), Col("Wk10", 56.dp, TextAlign.End), Col("Wk14", 56.dp, TextAlign.End), Col("Wk18", 56.dp, TextAlign.End),
                        Col("Weeks alive (E)", 90.dp, TextAlign.End), Col("Exp. strikes", 76.dp, TextAlign.End),
                    ),
                    rows = cmp.results.map { r ->
                        listOf(
                            r.label, Fmt.pct1(r.surviveSeason), Fmt.pct1(r.zeroLossFinish), Fmt.pct1(r.reachWeek10), Fmt.pct1(r.reachWeek14), Fmt.pct1(r.reachWeek18),
                            String.format(Locale.US, "%.2f", r.expectedWeeksAlive), String.format(Locale.US, "%.2f", r.expectedStrikes),
                        )
                    },
                )
                HorizontalDivider()
                Text(policyReading(cmp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Two plain-language sentences: which policy wins for surviving the whole season vs. lasting the most
 *  weeks on average, and a note that pool size is what decides which of those actually matters. */
private fun policyReading(cmp: com.survivor.engine.PolicyComparison): String {
    if (cmp.results.isEmpty()) return ""
    val bestSurvive = cmp.results.maxBy { it.surviveSeason }
    val bestWeeks = cmp.results.maxBy { it.expectedWeeksAlive }
    val first = if (bestSurvive.label == bestWeeks.label) {
        "${bestSurvive.label} comes out on top both for finishing the season alive (${Fmt.pct1(bestSurvive.surviveSeason)}) and for weeks survived on average (${String.format(Locale.US, "%.1f", bestSurvive.expectedWeeksAlive)})."
    } else {
        "${bestSurvive.label} is best at finishing the whole season alive (${Fmt.pct1(bestSurvive.surviveSeason)}), while ${bestWeeks.label} lasts the most weeks on average (${String.format(Locale.US, "%.1f", bestWeeks.expectedWeeksAlive)})."
    }
    val second = "Which one actually matters for you depends on your pool's size: a small pool is usually decided long before Week 18, so surviving the most weeks matters more than surviving all of them, while a large pool often runs the full season, so finishing alive is what wins it."
    return "$first $second"
}
