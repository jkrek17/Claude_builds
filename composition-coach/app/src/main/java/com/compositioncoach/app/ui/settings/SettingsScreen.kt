package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.subjectMaskSubtitle
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.GuidanceLevel

/** Live-backed settings; see [SettingsContent] for the layout. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    availableEnhancements: Set<CaptureEnhancement> = emptySet(),
    onEnhancementChange: (CaptureEnhancement) -> Unit = {},
    actions: ExperienceActions = ExperienceActions(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.uiPreferences.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        ui = ui,
        onBack = onBack,
        onOpenPrivacyPolicy = onOpenPrivacyPolicy,
        handlers = SettingsHandlers(
            onGuidanceLevelChange = viewModel::setGuidanceLevel,
            onShowScoreChange = viewModel::setShowScore,
            onShowThirdsGridChange = viewModel::setShowThirdsGrid,
            onGuidanceEnabledChange = viewModel::setGuidanceEnabled,
            onBatterySaverChange = viewModel::setBatterySaver,
            onDetectObjectsChange = viewModel::setDetectObjectsEnabled,
            onSubjectMaskChange = viewModel::setSubjectMaskEnabled,
            onPoseDetectionChange = viewModel::setPoseDetectionEnabled,
            onDebugModeChange = viewModel::setDebugMode,
        ),
        actions = actions.copy(
            onExperienceChange = viewModel::setExperienceMode,
            onProControlsChange = viewModel::setProControls,
            onGuideByVibrationChange = viewModel::setGuideByVibration,
            onGuideByVoiceChange = viewModel::setGuideByVoice,
        ),
        capture = {
            CaptureSettingsRows(
                settings = settings,
                availableEnhancements = availableEnhancements,
                onEnhancementChange = onEnhancementChange,
                onBurstSharpestChange = viewModel::setBurstSharpest,
                onAutoLevelChange = viewModel::setAutoLevel,
                onAutoShootChange = viewModel::setAutoShoot,
                showEnhancement = ui.proControls,
            )
        },
    )
}

/** The persisted-setting writers [SettingsContent] calls (all `SettingsRepository`, via [SettingsViewModel]). */
data class SettingsHandlers(
    val onGuidanceLevelChange: (GuidanceLevel) -> Unit = {},
    val onShowScoreChange: (Boolean) -> Unit = {},
    val onShowThirdsGridChange: (Boolean) -> Unit = {},
    val onGuidanceEnabledChange: (Boolean) -> Unit = {},
    val onBatterySaverChange: (Boolean) -> Unit = {},
    val onDetectObjectsChange: (Boolean) -> Unit = {},
    val onSubjectMaskChange: (Boolean) -> Unit = {},
    val onPoseDetectionChange: (Boolean) -> Unit = {},
    val onDebugModeChange: (Boolean) -> Unit = {},
)

/**
 * Settings, reorganised for Wave 2 (`docs/WAVE2.md` 0b, `docs/PRODUCT.md` "Hidden under Pro"):
 *  1. **Experience** (Easy · Coach); Coach adds "Show score".
 *  2. **Your eye**, then the **Pro controls** switch; with Pro on, Guidance level and Grid appear.
 *  3. **More** (collapsed): capture rows (Enhancement only under Pro), Guide by vibration / voice, Composition
 *     guidance, Battery saver, then under Pro the detection toggles and Developer mode; Reset coaching preferences.
 *  4. **About**: privacy line, Privacy, Open-source licences, Send feedback, version.
 * The shooting-mode chips are gone: the outcome strip on the camera is the control, and it sets the scene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: CoachSettings,
    ui: UiPreferences,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    handlers: SettingsHandlers = SettingsHandlers(),
    actions: ExperienceActions = ExperienceActions(),
    initiallyExpanded: Boolean = false,
    capture: @Composable () -> Unit = {},
) {
    var moreExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val pro = ui.proControls

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ExperienceSelector(selected = ui.experienceMode, onChange = actions.onExperienceChange)
            if (ui.experienceMode == ExperienceMode.COACH) {
                SwitchRow(label = stringResource(R.string.settings_show_score), checked = settings.showScore, onCheckedChange = handlers.onShowScoreChange)
            }
            NavigationRow(
                label = stringResource(R.string.your_eye_title),
                subtitle = stringResource(R.string.settings_your_eye_subtitle),
                onClick = actions.onOpenYourEye,
            )
            SwitchRow(
                label = stringResource(R.string.settings_pro_controls),
                subtitle = stringResource(R.string.settings_pro_controls_subtitle),
                checked = pro,
                onCheckedChange = actions.onProControlsChange,
            )
            if (pro) {
                SettingsSectionLabel(stringResource(R.string.settings_guidance))
                GuidanceLevelSelector(selected = settings.guidanceLevel, onChange = handlers.onGuidanceLevelChange)
                Spacer(Modifier.height(8.dp))
                SwitchRow(label = stringResource(R.string.settings_grid), checked = settings.showThirdsGrid, onCheckedChange = handlers.onShowThirdsGridChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MoreHeader(expanded = moreExpanded, onToggle = { moreExpanded = !moreExpanded })
            MoreContent(expanded = moreExpanded) {
                capture()
                SwitchRow(
                    label = stringResource(R.string.settings_guide_vibration),
                    subtitle = stringResource(R.string.settings_guide_vibration_subtitle),
                    checked = ui.guideByVibration,
                    onCheckedChange = actions.onGuideByVibrationChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_guide_voice),
                    subtitle = stringResource(R.string.settings_guide_voice_subtitle),
                    checked = ui.guideByVoice,
                    onCheckedChange = actions.onGuideByVoiceChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_composition_guidance),
                    subtitle = stringResource(R.string.settings_composition_guidance_subtitle),
                    checked = settings.guidanceEnabled,
                    onCheckedChange = handlers.onGuidanceEnabledChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_battery_saver),
                    subtitle = stringResource(R.string.settings_battery_saver_subtitle),
                    checked = settings.batterySaver,
                    onCheckedChange = handlers.onBatterySaverChange,
                )
                if (pro) ProDetectionRows(settings, handlers)
                ResetCoachingRow(onReset = actions.onResetCoaching)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            AboutSection(onOpenPrivacyPolicy = onOpenPrivacyPolicy, actions = actions)
        }
    }
}

/** Detection toggles and developer mode: Pro only. */
@Composable
private fun ProDetectionRows(settings: CoachSettings, handlers: SettingsHandlers) {
    SwitchRow(label = stringResource(R.string.settings_detect_objects), checked = settings.detectObjectsEnabled, onCheckedChange = handlers.onDetectObjectsChange)
    SwitchRow(
        label = stringResource(R.string.settings_subject_mask),
        subtitle = settings.subjectMaskSubtitle(),
        checked = settings.effectiveSubjectMaskEnabled,
        enabled = !settings.batterySaver,
        onCheckedChange = handlers.onSubjectMaskChange,
    )
    SwitchRow(label = stringResource(R.string.settings_body_detection), checked = settings.poseDetectionEnabled, onCheckedChange = handlers.onPoseDetectionChange)
    SwitchRow(label = stringResource(R.string.settings_developer_mode), checked = settings.debugMode, onCheckedChange = handlers.onDebugModeChange)
}

@Preview(name = "Settings, Easy", showBackground = true, heightDp = 900)
@Composable
private fun SettingsEasyPreview() {
    CompositionCoachTheme {
        SettingsContent(settings = CoachSettings(), ui = UiPreferences(), onBack = {}, onOpenPrivacyPolicy = {})
    }
}

@Preview(name = "Settings, Coach + Pro, More expanded", showBackground = true, heightDp = 1600)
@Composable
private fun SettingsProPreview() {
    CompositionCoachTheme {
        SettingsContent(
            settings = CoachSettings(guidanceLevel = GuidanceLevel.COACH, batterySaver = true),
            ui = UiPreferences(experienceMode = ExperienceMode.COACH, proControls = true, guideByVibration = true),
            onBack = {},
            onOpenPrivacyPolicy = {},
            initiallyExpanded = true,
            capture = {
                CaptureSettingsRows(
                    settings = CoachSettings(captureEnhancement = CaptureEnhancement.HDR),
                    availableEnhancements = setOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR),
                    onEnhancementChange = {},
                    onBurstSharpestChange = {},
                    onAutoLevelChange = {},
                    onAutoShootChange = {},
                )
            },
        )
    }
}
