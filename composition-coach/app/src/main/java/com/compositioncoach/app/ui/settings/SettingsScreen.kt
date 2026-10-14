package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.BuildConfig
import com.compositioncoach.app.R
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.subjectMaskSubtitle
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnSurface
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

/** Live-backed settings; see [SettingsContent] for the layout `docs/APP_UX.md` specifies. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenPrivacyPolicy: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenPrivacyPolicy = onOpenPrivacyPolicy,
        onSceneIntentChange = viewModel::setSceneIntent,
        onGuidanceLevelChange = viewModel::setGuidanceLevel,
        onShowScoreChange = viewModel::setShowScore,
        onShowThirdsGridChange = viewModel::setShowThirdsGrid,
        onGuidanceEnabledChange = viewModel::setGuidanceEnabled,
        onBatterySaverChange = viewModel::setBatterySaver,
        onDetectObjectsChange = viewModel::setDetectObjectsEnabled,
        onSubjectMaskChange = viewModel::setSubjectMaskEnabled,
        onPoseDetectionChange = viewModel::setPoseDetectionEnabled,
        onDebugModeChange = viewModel::setDebugMode,
    )
}

/**
 * One screen, four primary controls, everything else behind "More" — progressive disclosure, per
 * `docs/APP_UX.md`. Primary, in order: Shooting mode, Guidance, Show score, Grid. "More" holds the
 * settings people set once and forget, and the footer states the privacy position with links to the full
 * policy and the version.
 *
 * Two rows in "More" are not in the spec's table: "Composition guidance" and "Body detection". Both are
 * persisted settings with real behaviour behind them, and a persisted toggle with no way back to it is a
 * trap (a user who turned coaching off would have no way to turn it on again), so they live at the ends of
 * the disclosed section rather than being dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: CoachSettings,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onSceneIntentChange: (SceneIntent) -> Unit,
    onGuidanceLevelChange: (GuidanceLevel) -> Unit,
    onShowScoreChange: (Boolean) -> Unit,
    onShowThirdsGridChange: (Boolean) -> Unit,
    onGuidanceEnabledChange: (Boolean) -> Unit,
    onBatterySaverChange: (Boolean) -> Unit,
    onDetectObjectsChange: (Boolean) -> Unit,
    onSubjectMaskChange: (Boolean) -> Unit,
    onPoseDetectionChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_shooting_mode))
            SceneIntentSelector(selected = settings.sceneIntent, onChange = onSceneIntentChange)

            SettingsSectionLabel(stringResource(R.string.settings_guidance))
            GuidanceLevelSelector(selected = settings.guidanceLevel, onChange = onGuidanceLevelChange)

            Spacer(Modifier.height(8.dp))
            SwitchRow(
                label = stringResource(R.string.settings_show_score),
                checked = settings.showScore,
                onCheckedChange = onShowScoreChange,
            )
            SwitchRow(
                label = stringResource(R.string.settings_grid),
                checked = settings.showThirdsGrid,
                onCheckedChange = onShowThirdsGridChange,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MoreHeader(expanded = moreExpanded, onToggle = { moreExpanded = !moreExpanded })
            MoreContent(expanded = moreExpanded) {
                SwitchRow(
                    label = stringResource(R.string.settings_composition_guidance),
                    subtitle = stringResource(R.string.settings_composition_guidance_subtitle),
                    checked = settings.guidanceEnabled,
                    onCheckedChange = onGuidanceEnabledChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_battery_saver),
                    subtitle = stringResource(R.string.settings_battery_saver_subtitle),
                    checked = settings.batterySaver,
                    onCheckedChange = onBatterySaverChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_detect_objects),
                    checked = settings.detectObjectsEnabled,
                    onCheckedChange = onDetectObjectsChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_subject_mask),
                    subtitle = settings.subjectMaskSubtitle(),
                    checked = settings.effectiveSubjectMaskEnabled,
                    enabled = !settings.batterySaver,
                    onCheckedChange = onSubjectMaskChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_body_detection),
                    checked = settings.poseDetectionEnabled,
                    onCheckedChange = onPoseDetectionChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_developer_mode),
                    checked = settings.debugMode,
                    onCheckedChange = onDebugModeChange,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsFooter(onOpenPrivacyPolicy = onOpenPrivacyPolicy)
        }
    }
}

/** "All analysis runs on your device." plus the privacy link and the version. */
@Composable
private fun SettingsFooter(onOpenPrivacyPolicy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SETTINGS_GUTTER, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_privacy_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface.copy(alpha = 0.7f),
        )
        Row {
            TextButton(onClick = onOpenPrivacyPolicy, contentPadding = PaddingValues(0.dp)) {
                Text("Privacy")
            }
        }
        Text(
            text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Preview(name = "Settings", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    CompositionCoachTheme {
        SettingsContent(
            settings = CoachSettings(guidanceLevel = GuidanceLevel.COACH, sceneIntent = SceneIntent.PORTRAIT),
            onBack = {},
            onOpenPrivacyPolicy = {},
            onSceneIntentChange = {},
            onGuidanceLevelChange = {},
            onShowScoreChange = {},
            onShowThirdsGridChange = {},
            onGuidanceEnabledChange = {},
            onBatterySaverChange = {},
            onDetectObjectsChange = {},
            onSubjectMaskChange = {},
            onPoseDetectionChange = {},
            onDebugModeChange = {},
        )
    }
}

@Preview(name = "Settings, More expanded", showBackground = true)
@Composable
private fun SettingsScreenExpandedPreview() {
    CompositionCoachTheme {
        SettingsContent(
            settings = CoachSettings(batterySaver = true, debugMode = true),
            onBack = {},
            onOpenPrivacyPolicy = {},
            onSceneIntentChange = {},
            onGuidanceLevelChange = {},
            onShowScoreChange = {},
            onShowThirdsGridChange = {},
            onGuidanceEnabledChange = {},
            onBatterySaverChange = {},
            onDetectObjectsChange = {},
            onSubjectMaskChange = {},
            onPoseDetectionChange = {},
            onDebugModeChange = {},
            initiallyExpanded = true,
        )
    }
}
