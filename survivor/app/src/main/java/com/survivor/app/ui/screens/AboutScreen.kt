package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.BuildConfig
import com.survivor.app.background.AutoRefreshPrefs
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.theme.Spacing

@Composable
fun AboutScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.season
    val context = LocalContext.current
    val autoRefreshPrefs = remember { AutoRefreshPrefs(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard {
            Text("Survivor Optimizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Data") {
            KeyValue("Season", "${s?.year ?: "—"}")
            KeyValue("Games loaded", "${s?.games?.size ?: 0}")
            KeyValue("Schedule fetched", Fmt.dateTime(s?.scheduleFetchedAtEpochMs))
            KeyValue("Lines fetched", Fmt.dateTime(s?.oddsFetchedAtEpochMs))
            KeyValue("FPI fetched", Fmt.dateTime(s?.fpiFetchedAtEpochMs))
            KeyValue("Consensus fetched", Fmt.dateTime(s?.consensusFetchedAtEpochMs))
            KeyValue("State saved", Fmt.dateTime(state.savedAtEpochMs.takeIf { it > 0 }))
            KeyValue("Last automatic refresh", Fmt.dateTime(autoRefreshPrefs.lastRunEpochMs.takeIf { it > 0 }))
        }
        Text(
            "Schedule, scores, DraftKings spreads/moneylines and FPI projections come from ESPN's public data. An optional key from the-odds-api.com adds a consensus moneyline across US books.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
