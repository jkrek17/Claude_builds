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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.data.RefreshStatus
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.PoolGuidance
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.theme.Spacing
import com.survivor.engine.Strategy

/**
 * First-run screen shown while no NFL data has been downloaded yet: a one-line value proposition, what
 * the app does, a quick "Your pool" setup, and the single primary action.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val settings = state.user.settings
    var entriesText by remember(settings.poolEntries) { mutableStateOf(settings.poolEntries.toString()) }
    val running = refresh as? RefreshStatus.Running

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Survivor Optimizer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Your NFL survivor-pool command center: who to pick, how safe it is, and what you give up by using them now.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            Bullet("Pulls the schedule, DraftKings lines and ESPN FPI projections automatically - nothing to type in.")
            Bullet("Ranks every available team by a Survivor Safety Score that weighs this week's risk against what you'd give up later.")
            Bullet("Optimizes your whole remaining season, accounting for your pool's size, not just this week's matchup.")
        }
        SectionCard("Your pool") {
            Text("How many entries are in your pool?", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PoolGuidance.presets.forEach { p ->
                    FilterChip(
                        selected = PoolGuidance.selectedPreset(settings.poolEntries) == p,
                        onClick = { entriesText = p.toString(); vm.updateSettings(settings.copy(poolEntries = p)) },
                        label = { Text(PoolGuidance.presetLabel(p)) },
                    )
                }
            }
            OutlinedTextField(
                value = entriesText,
                onValueChange = { entriesText = it; it.toIntOrNull()?.let { n -> vm.updateSettings(settings.copy(poolEntries = n.coerceIn(2, 100_000))) } },
                label = { Text("Exact number of entries") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(PoolGuidance.text(settings.poolEntries, null), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Strategy", style = MaterialTheme.typography.bodyMedium)
            Strategy.entries.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.strategy == s, onClick = { vm.updateSettings(settings.forStrategy(s)) })
                    Column {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                        Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(onClick = { vm.refreshNflData() }, enabled = running == null, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
            Text(if (running != null) "Downloading…" else "Download NFL data")
        }
        if (running != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = Spacing.sm))
                Text(running.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Fetches 18 weeks of schedule, spreads/moneylines and FPI projections from ESPN's public data (10-20 seconds on Wi-Fi). Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
