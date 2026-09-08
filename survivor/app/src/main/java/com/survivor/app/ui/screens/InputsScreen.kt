package com.survivor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.components.WeekSelector
import com.survivor.engine.Adjustment
import com.survivor.engine.GridCell
import com.survivor.engine.Safety
import com.survivor.engine.Team

@Composable
fun InputsScreen(vm: AppViewModel) {
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val e = eval ?: run { EmptyState("Download NFL data from the Dashboard first."); return }
    var week by remember(e.currentWeek) { mutableStateOf(e.currentWeek) }
    var editing by remember { mutableStateOf<GridCell?>(null) }
    var apiKey by remember(state.user.oddsApiKey) { mutableStateOf(state.user.oddsApiKey) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Data sources") {
            Text("Schedule, scores, DraftKings spreads/moneylines and FPI projections come from ESPN automatically. Optionally add a free key from the-odds-api.com to replace the single-book moneyline with a consensus across US books for the current week.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("The Odds API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setOddsApiKey(apiKey); vm.refreshOdds() }, enabled = apiKey != state.user.oddsApiKey || apiKey.isNotBlank()) { Text("Save key and refresh odds") }
            Text("Lines fetched ${Fmt.age(e.season.oddsFetchedAtEpochMs)} · FPI ${Fmt.age(e.season.fpiFetchedAtEpochMs)} · Consensus ${Fmt.age(e.season.consensusFetchedAtEpochMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Weekly inputs · manual adjustments") {
            Text("Everything here is optional. Manual override replaces all automated numbers. Injury, QB and weather adjustments are in points of spread (negative = worse for the team) and shift the market number. Pick share feeds the leverage model.", style = MaterialTheme.typography.bodySmall)
            WeekSelector(week, { week = it })
            val cells = e.grid.mapNotNull { (_, row) -> row[week - 1] }.sortedBy { it.team.abbr }
            cells.forEach { c ->
                val adj = state.user.adjustment(week, c.team)
                Row(Modifier.fillMaxWidth().clickable { editing = c }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${c.team.abbr} ${Fmt.matchup(c.opponent.abbr, c.isHome, c.neutral)}", fontWeight = FontWeight.SemiBold)
                        Text("${Fmt.spread(c.teamSpread)} · ${c.source.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (adj != null) Text(describe(adj), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    TierBadge(Fmt.pct(c.probability), Safety.probabilityTier(c.probability))
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }

    editing?.let { c ->
        AdjustmentDialog(c, state.user.adjustment(week, c.team), onDismiss = { editing = null }) { vm.setAdjustment(it); editing = null }
    }
}

private fun describe(a: Adjustment): String = buildList {
    a.overrideWinProbability?.let { add("override ${Fmt.pct(it)}") }
    if (a.injuryPoints != 0.0) add("injury ${Fmt.signed(a.injuryPoints)}")
    if (a.qbPoints != 0.0) add("QB ${Fmt.signed(a.qbPoints)}")
    if (a.weatherPoints != 0.0) add("weather ${Fmt.signed(a.weatherPoints)}")
    a.estimatedPickShare?.let { add("pick share ${Fmt.pct(it)}") }
    if (a.note.isNotBlank()) add(a.note)
}.joinToString(" · ")

@Composable
private fun AdjustmentDialog(cell: GridCell, existing: Adjustment?, onDismiss: () -> Unit, onSave: (Adjustment) -> Unit) {
    var override by remember { mutableStateOf(existing?.overrideWinProbability?.let { Fmt.num(it * 100) } ?: "") }
    var injury by remember { mutableStateOf(existing?.injuryPoints?.takeIf { it != 0.0 }?.let { Fmt.num(it) } ?: "") }
    var qb by remember { mutableStateOf(existing?.qbPoints?.takeIf { it != 0.0 }?.let { Fmt.num(it) } ?: "") }
    var weather by remember { mutableStateOf(existing?.weatherPoints?.takeIf { it != 0.0 }?.let { Fmt.num(it) } ?: "") }
    var share by remember { mutableStateOf(existing?.estimatedPickShare?.let { Fmt.num(it * 100) } ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${cell.team.abbr} ${Fmt.matchup(cell.opponent.abbr, cell.isHome, cell.neutral)} · Week ${cell.week}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model: ${Fmt.pct(cell.probability)} from ${cell.source.label}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(override, { override = it }, label = { Text("Manual override win % (blank = none)") }, keyboardOptions = numeric, singleLine = true)
                OutlinedTextField(injury, { injury = it }, label = { Text("Injury adjustment (points)") }, keyboardOptions = numeric, singleLine = true)
                OutlinedTextField(qb, { qb = it }, label = { Text("QB adjustment (points; any value flags QB risk)") }, keyboardOptions = numeric, singleLine = true)
                OutlinedTextField(weather, { weather = it }, label = { Text("Weather adjustment (points)") }, keyboardOptions = numeric, singleLine = true)
                OutlinedTextField(share, { share = it }, label = { Text("Estimated pool pick share %") }, keyboardOptions = numeric, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Adjustment(
                        week = cell.week, team = cell.team,
                        overrideWinProbability = override.toDoubleOrNull()?.let { (it / 100.0).coerceIn(0.01, 0.99) },
                        injuryPoints = injury.toDoubleOrNull() ?: 0.0, qbPoints = qb.toDoubleOrNull() ?: 0.0, weatherPoints = weather.toDoubleOrNull() ?: 0.0,
                        estimatedPickShare = share.toDoubleOrNull()?.let { (it / 100.0).coerceIn(0.0, 1.0) }, note = note.trim(),
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(Adjustment(cell.week, cell.team)) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Suppress("unused") private val keepTeamImport = Team.COUNT
