package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.ui.camera.EnhancementCycle
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The three capture switches whose settings keys are not in `CoachSettings` yet. The integrator moves them
 * into `CoachSettings`/`SettingsRepository` under these exact keys and deletes this class:
 *
 * | field | key | default |
 * |---|---|---|
 * | [burstSharpest] | `burst_sharpest` (bool) | on |
 * | [autoLevel] | `auto_level` (bool) | off |
 * | [autoShoot] | `auto_shoot` (bool) | off |
 *
 * (`capture_enhancement` — the fourth key — is already reachable through `CameraUiState.enhancement` /
 * `CameraViewModel.onEnhancementSelected`, so the Enhancement row binds to those instead.)
 */
data class UiSettingsDraft(
    val burstSharpest: Boolean = true,
    val autoLevel: Boolean = false,
    val autoShoot: Boolean = false,
)

/**
 * Process-lifetime home for [UiSettingsDraft] until those keys are persisted: the Settings rows write it and
 * the camera screen reads [UiSettingsDraft.autoShoot] from it, so "Auto shoot when ready" already works
 * for the session. Not persisted; a cold start returns to the defaults.
 */
object UiSettingsDraftStore {
    private val _draft = MutableStateFlow(UiSettingsDraft())
    val draft: StateFlow<UiSettingsDraft> = _draft.asStateFlow()

    fun update(transform: (UiSettingsDraft) -> UiSettingsDraft) = _draft.update(transform)
}

/**
 * The capture rows at the top of "More": Enhancement (a segmented control over what the lens offers, or a
 * disabled row explaining it is not available), then Sharpest of burst, Auto-level horizon and Auto shoot
 * when ready.
 */
@Composable
fun CaptureSettingsRows(
    enhancement: CaptureEnhancement,
    availableEnhancements: Set<CaptureEnhancement>,
    onEnhancementChange: (CaptureEnhancement) -> Unit,
    draft: UiSettingsDraft,
    onDraftChange: (UiSettingsDraft) -> Unit,
) {
    EnhancementSetting(enhancement, availableEnhancements, onEnhancementChange)
    SwitchRow(
        label = stringResource(R.string.settings_burst_sharpest),
        subtitle = stringResource(R.string.settings_burst_sharpest_subtitle),
        checked = draft.burstSharpest,
        onCheckedChange = { onDraftChange(draft.copy(burstSharpest = it)) },
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_level),
        subtitle = stringResource(R.string.settings_auto_level_subtitle),
        checked = draft.autoLevel,
        onCheckedChange = { onDraftChange(draft.copy(autoLevel = it)) },
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_shoot),
        subtitle = stringResource(R.string.settings_auto_shoot_subtitle),
        checked = draft.autoShoot,
        onCheckedChange = { onDraftChange(draft.copy(autoShoot = it)) },
    )
}

@Composable
private fun EnhancementSetting(
    current: CaptureEnhancement,
    available: Set<CaptureEnhancement>,
    onChange: (CaptureEnhancement) -> Unit,
) {
    val options = EnhancementCycle.options(available)
    if (options.isEmpty()) {
        SettingsRow(
            label = stringResource(R.string.settings_enhancement),
            subtitle = stringResource(R.string.settings_enhancement_unavailable),
            enabled = false,
        ) {}
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionLabel(stringResource(R.string.settings_enhancement))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = SETTINGS_GUTTER)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = current == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_enhancement_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = SETTINGS_GUTTER, vertical = 6.dp),
        )
    }
}

@Preview(name = "Capture settings", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun CaptureSettingsRowsPreview() {
    CompositionCoachTheme {
        Column {
            CaptureSettingsRows(
                enhancement = CaptureEnhancement.AUTO,
                availableEnhancements = setOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT),
                onEnhancementChange = {},
                draft = UiSettingsDraft(autoShoot = true),
                onDraftChange = {},
            )
        }
    }
}

@Preview(name = "Capture settings, no enhancements", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun CaptureSettingsRowsUnavailablePreview() {
    CompositionCoachTheme {
        Column {
            CaptureSettingsRows(
                enhancement = CaptureEnhancement.OFF,
                availableEnhancements = emptySet(),
                onEnhancementChange = {},
                draft = UiSettingsDraft(),
                onDraftChange = {},
            )
        }
    }
}
