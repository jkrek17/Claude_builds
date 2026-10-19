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
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.ui.camera.EnhancementCycle
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnSurface

/**
 * What the Settings "Enhancement" row shows, given the persisted `capture_enhancement` and what the bound
 * lens offers. Pure, unit-tested in `EnhancementRowModelTest`.
 *
 * The row shows the *persisted* choice (the pill shows what is running). An explicit HDR/Night choice the
 * current lens lacks (a switch to the front camera) stays selected, greyed, with "Not available on this
 * camera", so the user sees it is kept for the lens that has it. AUTO is different: it is the default, not
 * necessarily a choice, and is documented as "AUTO when supported, else OFF", so on a lens that does not offer
 * it the row shows Off, which is what runs, instead of telling a first-run user about a mode they never picked.
 *
 * @param options the segments, in enum order; empty means "nothing beyond Off on this camera" (disabled row).
 * @param selected the segment to fill.
 * @param unavailableOnLens true when [selected] is the persisted choice but this lens cannot run it.
 */
data class EnhancementRowModel(
    val options: List<CaptureEnhancement>,
    val selected: CaptureEnhancement?,
    val unavailableOnLens: Boolean,
) {
    /** Whether a segment can be picked (an unavailable persisted choice is shown, not offered). */
    fun isSelectable(option: CaptureEnhancement): Boolean = !(unavailableOnLens && option == selected)

    companion object {
        fun of(persisted: CaptureEnhancement, available: Set<CaptureEnhancement>): EnhancementRowModel {
            val offered = EnhancementCycle.options(available)
            if (offered.isEmpty()) return EnhancementRowModel(emptyList(), null, unavailableOnLens = false)
            val shown = if (persisted == CaptureEnhancement.AUTO && persisted !in offered) CaptureEnhancement.OFF else persisted
            val unavailable = shown !in offered
            val options = if (unavailable) CaptureEnhancement.entries.filter { it in offered || it == shown } else offered
            return EnhancementRowModel(options, shown, unavailable)
        }
    }
}

/**
 * The capture rows at the top of "More", all persisted (`SettingsRepository`): Enhancement (a segmented
 * control over what the lens offers, see [EnhancementRowModel]), then Sharpest of burst, Auto-level horizon
 * and Auto shoot when ready. [settings] is the persisted state; [availableEnhancements] comes from the camera,
 * since only the bound lens knows what it offers.
 */
@Composable
fun CaptureSettingsRows(
    settings: CoachSettings,
    availableEnhancements: Set<CaptureEnhancement>,
    onEnhancementChange: (CaptureEnhancement) -> Unit,
    onBurstSharpestChange: (Boolean) -> Unit,
    onAutoLevelChange: (Boolean) -> Unit,
    onAutoShootChange: (Boolean) -> Unit,
    showEnhancement: Boolean = true,
) {
    // The enhancement selector is a Pro control (`docs/PRODUCT.md`, "Hidden under Pro").
    if (showEnhancement) {
        EnhancementSetting(EnhancementRowModel.of(settings.captureEnhancement, availableEnhancements), onEnhancementChange)
    }
    SwitchRow(
        label = stringResource(R.string.settings_burst_sharpest),
        subtitle = stringResource(R.string.settings_burst_sharpest_subtitle),
        checked = settings.burstSharpest,
        onCheckedChange = onBurstSharpestChange,
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_level),
        subtitle = stringResource(R.string.settings_auto_level_subtitle),
        checked = settings.autoLevel,
        onCheckedChange = onAutoLevelChange,
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_shoot),
        subtitle = stringResource(R.string.settings_auto_shoot_subtitle),
        checked = settings.autoShoot,
        onCheckedChange = onAutoShootChange,
    )
}

@Composable
private fun EnhancementSetting(model: EnhancementRowModel, onChange: (CaptureEnhancement) -> Unit) {
    val options = model.options
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
                    selected = model.selected == option,
                    onClick = { if (option != model.selected) onChange(option) },
                    enabled = model.isSelectable(option),
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            text = stringResource(
                if (model.unavailableOnLens) R.string.settings_enhancement_unavailable else R.string.settings_enhancement_subtitle,
            ),
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
                settings = CoachSettings(autoShoot = true),
                availableEnhancements = setOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT),
                onEnhancementChange = {},
                onBurstSharpestChange = {},
                onAutoLevelChange = {},
                onAutoShootChange = {},
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
                settings = CoachSettings(),
                availableEnhancements = emptySet(),
                onEnhancementChange = {},
                onBurstSharpestChange = {},
                onAutoLevelChange = {},
                onAutoShootChange = {},
            )
        }
    }
}

@Preview(name = "Capture settings, Night kept for another lens", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun CaptureSettingsRowsElsewherePreview() {
    CompositionCoachTheme {
        Column {
            CaptureSettingsRows(
                settings = CoachSettings(captureEnhancement = CaptureEnhancement.NIGHT),
                availableEnhancements = setOf(CaptureEnhancement.OFF, CaptureEnhancement.HDR),
                onEnhancementChange = {},
                onBurstSharpestChange = {},
                onAutoLevelChange = {},
                onAutoShootChange = {},
            )
        }
    }
}
