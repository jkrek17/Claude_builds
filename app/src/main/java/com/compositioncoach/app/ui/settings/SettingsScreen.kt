package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.BuildConfig
import com.compositioncoach.app.R
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.subjectMaskSubtitle
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

private const val SEGMENTED_ROW_MAX_OPTIONS = 4

/** All coaching and privacy-relevant preferences, backed live by [SettingsViewModel], grouped into cards. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenPrivacyPolicy: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenPrivacyPolicy = onOpenPrivacyPolicy,
        onGuidanceEnabledChange = viewModel::setGuidanceEnabled,
        onShowScoreChange = viewModel::setShowScore,
        onShowThirdsGridChange = viewModel::setShowThirdsGrid,
        onGuidanceLevelChange = viewModel::setGuidanceLevel,
        onPoseDetectionChange = viewModel::setPoseDetectionEnabled,
        onBatterySaverChange = viewModel::setBatterySaver,
        onDebugModeChange = viewModel::setDebugMode,
        onSceneIntentChange = viewModel::setSceneIntent,
        onDetectObjectsChange = viewModel::setDetectObjectsEnabled,
        onSubjectMaskChange = viewModel::setSubjectMaskEnabled,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    settings: CoachSettings,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onGuidanceEnabledChange: (Boolean) -> Unit,
    onShowScoreChange: (Boolean) -> Unit,
    onShowThirdsGridChange: (Boolean) -> Unit,
    onGuidanceLevelChange: (GuidanceLevel) -> Unit,
    onPoseDetectionChange: (Boolean) -> Unit,
    onBatterySaverChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onSceneIntentChange: (SceneIntent) -> Unit,
    onDetectObjectsChange: (Boolean) -> Unit,
    onSubjectMaskChange: (Boolean) -> Unit,
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard(title = "Shooting mode", summary = stringResource(R.string.shooting_mode_description)) {
                    SceneIntentSelector(selected = settings.sceneIntent, onChange = onSceneIntentChange)
                }
            }
            item {
                SettingsCard(title = "Guidance", summary = "Score, instructions, and how much detail they carry") {
                    SwitchRow("Composition guidance", settings.guidanceEnabled, onGuidanceEnabledChange)
                    SwitchRow("Show score", settings.showScore, onShowScoreChange)
                    Text(
                        "Guidance level",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    GuidanceLevel.entries.forEach { level ->
                        GuidanceLevelRow(level, selected = settings.guidanceLevel == level, onClick = { onGuidanceLevelChange(level) })
                    }
                }
            }
            item {
                SettingsCard(title = "Overlays", summary = "Extra guides drawn over the live preview") {
                    SwitchRow("Rule-of-thirds grid", settings.showThirdsGrid, onShowThirdsGridChange)
                }
            }
            item {
                SettingsCard(title = "Detection", summary = "What the on-device vision pipeline looks for") {
                    SwitchRow("Body/pose detection", settings.poseDetectionEnabled, onPoseDetectionChange)
                    SwitchRow(
                        label = "Detect objects",
                        subtitle = "Finds plates, drinks, products and other subjects",
                        checked = settings.detectObjectsEnabled,
                        onCheckedChange = onDetectObjectsChange,
                    )
                    SwitchRow(
                        label = "Subject mask",
                        subtitle = settings.subjectMaskSubtitle(),
                        checked = settings.effectiveSubjectMaskEnabled,
                        enabled = !settings.batterySaver,
                        onCheckedChange = onSubjectMaskChange,
                    )
                }
            }
            item {
                SettingsCard(title = "Performance", summary = "Trade coaching detail for battery life or debugging") {
                    SwitchRow("Battery saver (slower analysis)", settings.batterySaver, onBatterySaverChange)
                    SwitchRow("Debug mode", settings.debugMode, onDebugModeChange)
                }
            }
            item {
                SettingsCard(title = "About", summary = "${BuildConfig.VERSION_NAME} — on-device only, no data leaves your phone") {
                    AboutSection(onOpenPrivacyPolicy = onOpenPrivacyPolicy)
                }
            }
        }
    }
}

/** App name, version, the "on your device" privacy summary, and a link into the full policy. */
@Composable
private fun AboutSection(onOpenPrivacyPolicy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "All analysis runs on your device. Camera frames never leave your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Start,
        )
        TextButton(onClick = onOpenPrivacyPolicy, modifier = Modifier.padding(top = 4.dp, start = 0.dp)) {
            Text("Privacy policy")
        }
    }
}

@Preview(name = "Settings", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    CompositionCoachTheme {
        SettingsContent(
            settings = CoachSettings(guidanceLevel = GuidanceLevel.COACH, debugMode = true, sceneIntent = SceneIntent.PORTRAIT),
            onBack = {},
            onOpenPrivacyPolicy = {},
            onGuidanceEnabledChange = {},
            onShowScoreChange = {},
            onShowThirdsGridChange = {},
            onGuidanceLevelChange = {},
            onPoseDetectionChange = {},
            onBatterySaverChange = {},
            onDebugModeChange = {},
            onSceneIntentChange = {},
            onDetectObjectsChange = {},
            onSubjectMaskChange = {},
        )
    }
}
