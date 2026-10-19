package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.BuildConfig
import com.compositioncoach.app.R
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.ui.theme.OnSurface

/** The Wave 2 settings actions: experience, Pro, heads-up guides, "Your eye", reset, licences, feedback. */
data class ExperienceActions(
    val onExperienceChange: (ExperienceMode) -> Unit = {},
    val onProControlsChange: (Boolean) -> Unit = {},
    val onGuideByVibrationChange: (Boolean) -> Unit = {},
    val onGuideByVoiceChange: (Boolean) -> Unit = {},
    val onOpenYourEye: () -> Unit = {},
    val onResetCoaching: () -> Unit = {},
    val onOpenLicences: () -> Unit = {},
    val onSendFeedback: () -> Unit = {},
)

/** "Experience": Easy · Coach, with one line saying what the selected one does. First thing in Settings. */
@Composable
fun ExperienceSelector(selected: ExperienceMode, onChange: (ExperienceMode) -> Unit, modifier: Modifier = Modifier) {
    val options = ExperienceMode.entries
    Column(modifier.fillMaxWidth()) {
        SettingsSectionLabel(stringResource(R.string.settings_experience))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = SETTINGS_GUTTER)) {
            options.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(stringResource(if (mode == ExperienceMode.EASY) R.string.experience_easy else R.string.experience_coach)) },
                )
            }
        }
        Text(
            text = stringResource(
                if (selected == ExperienceMode.EASY) R.string.settings_experience_easy_subtitle else R.string.settings_experience_coach_subtitle,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = SETTINGS_GUTTER, vertical = 6.dp),
        )
    }
}

/** A row that opens another screen: label, optional subtitle, a chevron. */
@Composable
fun NavigationRow(label: String, onClick: () -> Unit, subtitle: String? = null) {
    SettingsRow(label = label, subtitle = subtitle, modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = OnSurface.copy(alpha = 0.6f))
    }
}

/** "Reset coaching preferences", behind the same "Start fresh?" confirmation as the "Your eye" screen. */
@Composable
fun ResetCoachingRow(onReset: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    NavigationRow(
        label = stringResource(R.string.settings_reset_coaching),
        subtitle = stringResource(R.string.settings_reset_coaching_subtitle),
        onClick = { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.your_eye_reset_confirm_title)) },
            text = { Text(stringResource(R.string.your_eye_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }) { Text(stringResource(R.string.your_eye_reset_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.your_eye_reset_confirm_no)) } },
        )
    }
}

/** About: the privacy line, Privacy, Open-source licences, Send feedback, and the version. */
@Composable
fun AboutSection(onOpenPrivacyPolicy: () -> Unit, actions: ExperienceActions) {
    SettingsSectionLabel(stringResource(R.string.settings_about))
    Text(
        text = stringResource(R.string.settings_privacy_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = SETTINGS_GUTTER, vertical = 4.dp),
    )
    NavigationRow(label = stringResource(R.string.settings_privacy), onClick = onOpenPrivacyPolicy)
    NavigationRow(label = stringResource(R.string.settings_licences), onClick = actions.onOpenLicences)
    NavigationRow(
        label = stringResource(R.string.settings_send_feedback),
        subtitle = stringResource(R.string.settings_send_feedback_subtitle),
        onClick = actions.onSendFeedback,
    )
    Text(
        text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = SETTINGS_GUTTER, vertical = 8.dp),
    )
    Spacer(Modifier.height(16.dp))
}
