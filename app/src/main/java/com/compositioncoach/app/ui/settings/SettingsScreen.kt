package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.description
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.settings.label
import com.compositioncoach.composition.model.GuidanceLevel

/** All coaching and privacy-relevant preferences, backed live by [SettingsViewModel]. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onGuidanceEnabledChange = viewModel::setGuidanceEnabled,
        onShowScoreChange = viewModel::setShowScore,
        onShowThirdsGridChange = viewModel::setShowThirdsGrid,
        onGuidanceLevelChange = viewModel::setGuidanceLevel,
        onPoseDetectionChange = viewModel::setPoseDetectionEnabled,
        onBatterySaverChange = viewModel::setBatterySaver,
        onDebugModeChange = viewModel::setDebugMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    settings: CoachSettings,
    onBack: () -> Unit,
    onGuidanceEnabledChange: (Boolean) -> Unit,
    onShowScoreChange: (Boolean) -> Unit,
    onShowThirdsGridChange: (Boolean) -> Unit,
    onGuidanceLevelChange: (GuidanceLevel) -> Unit,
    onPoseDetectionChange: (Boolean) -> Unit,
    onBatterySaverChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SwitchRow("Composition guidance", settings.guidanceEnabled, onGuidanceEnabledChange) }
            item { SwitchRow("Show score", settings.showScore, onShowScoreChange) }
            item { SwitchRow("Rule-of-thirds grid", settings.showThirdsGrid, onShowThirdsGridChange) }
            item { HorizontalDivider() }
            item {
                Text(
                    "Guidance level",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(GuidanceLevel.entries) { level ->
                GuidanceLevelRow(level, selected = settings.guidanceLevel == level, onClick = { onGuidanceLevelChange(level) })
            }
            item { HorizontalDivider() }
            item { SwitchRow("Body/pose detection", settings.poseDetectionEnabled, onPoseDetectionChange) }
            item { SwitchRow("Battery saver (slower analysis)", settings.batterySaver, onBatterySaverChange) }
            item { SwitchRow("Debug mode", settings.debugMode, onDebugModeChange) }
            item { HorizontalDivider() }
            item {
                Text(
                    text = "All analysis runs on your device. Camera frames never leave your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GuidanceLevelRow(level: GuidanceLevel, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(level.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                level.description(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@Preview(name = "Settings", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    CompositionCoachTheme {
        SettingsContent(
            settings = CoachSettings(guidanceLevel = GuidanceLevel.COACH, debugMode = true),
            onBack = {},
            onGuidanceEnabledChange = {},
            onShowScoreChange = {},
            onShowThirdsGridChange = {},
            onGuidanceLevelChange = {},
            onPoseDetectionChange = {},
            onBatterySaverChange = {},
            onDebugModeChange = {},
        )
    }
}
