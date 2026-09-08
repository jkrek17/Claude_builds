package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.clickableRow

@Composable
fun MoreScreen(@Suppress("UNUSED_PARAMETER") vm: AppViewModel, onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Item(Icons.Filled.Edit, "Weekly Inputs", "Manual overrides, injury/QB/weather points, pick share, Odds API key") { onNavigate(Routes.INPUTS) }
        Item(Icons.Filled.Tune, "Model Settings", "Your pool, strategy and advanced model parameters") { onNavigate(Routes.SETTINGS) }
        Item(Icons.Filled.BarChart, "Simulation", "Monte Carlo route comparison and closed-loop policy comparison") { onNavigate(Routes.SIMULATION) }
        Item(Icons.Filled.MenuBook, "How to use", "Weekly workflow, data sources, changing assumptions") { onNavigate(Routes.GUIDE) }
        Item(Icons.Filled.Info, "About", "Version, season, data and automatic-refresh timestamps") { onNavigate(Routes.ABOUT) }
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickableRow(onClick),
    )
    HorizontalDivider(thickness = 0.5.dp)
}
