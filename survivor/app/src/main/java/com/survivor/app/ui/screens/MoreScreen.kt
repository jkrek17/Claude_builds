package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.BuildConfig
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.clickableRow

@Composable
fun MoreScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Item("Record Weekly Pick / Used Teams", "Lock in this week's team; results and strikes fill in from scores") { onNavigate(Routes.PICKS) }
        Item("Weekly Inputs", "Optional overrides, injury/QB/weather points, pick share, Odds API key") { onNavigate(Routes.INPUTS) }
        Item("Model Settings", "Strategy, weights, penalties, current week") { onNavigate(Routes.SETTINGS) }
        Item("Monte Carlo", "Simulate the season and compare strategies") { onNavigate(Routes.SIMULATION) }
        Item("How to use", "Weekly workflow, data sources, changing assumptions") { onNavigate(Routes.GUIDE) }
        HorizontalDivider()
        val s = state.season
        Text(
            "Survivor Optimizer ${BuildConfig.VERSION_NAME}\nSeason ${s?.year ?: "—"} · ${s?.games?.size ?: 0} games loaded\nSchedule ${Fmt.dateTime(s?.scheduleFetchedAtEpochMs)} · Lines ${Fmt.dateTime(s?.oddsFetchedAtEpochMs)} · FPI ${Fmt.dateTime(s?.fpiFetchedAtEpochMs)}\nState saved ${Fmt.dateTime(state.savedAtEpochMs.takeIf { it > 0 })}",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Item(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, modifier = Modifier.clickableRow(onClick))
    HorizontalDivider(thickness = 0.5.dp)
}
