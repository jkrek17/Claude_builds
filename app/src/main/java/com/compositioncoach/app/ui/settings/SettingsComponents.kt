package com.compositioncoach.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.settings.label
import com.compositioncoach.app.ui.theme.OnSurface
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

/** Every settings row is this tall, with a 48dp minimum touch target inside it (`docs/APP_UX.md`). */
val SETTINGS_ROW_HEIGHT = 56.dp

/** The screen's single horizontal gutter, so labels, controls and the footer all line up. */
val SETTINGS_GUTTER = 20.dp

/**
 * A settings row: a label (plus an optional one-line subtitle, used only where the label isn't
 * self-explanatory) on the left, and [control] on the right. 56dp tall, so the whole screen reads as one
 * even rhythm rather than as a stack of differently-sized cards.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .padding(horizontal = SETTINGS_GUTTER),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) OnSurface else OnSurface.copy(alpha = 0.4f),
            )
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
            }
        }
        control()
    }
}

/** A [SettingsRow] whose control is a switch; the whole row toggles it. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        label = label,
        subtitle = subtitle,
        enabled = enabled,
        modifier = modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A section label above a control that needs the full width (the two segmented pickers). */
@Composable
fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = OnSurface,
        modifier = modifier.padding(start = SETTINGS_GUTTER, end = SETTINGS_GUTTER, top = 12.dp, bottom = 6.dp),
    )
}

/**
 * Shooting mode, as chips — the same DataStore value the camera's mode strip writes, kept here for
 * discoverability. Six modes don't fit a segmented row on a phone, so they scroll horizontally as chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneIntentSelector(selected: SceneIntent, onChange: (SceneIntent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SETTINGS_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SceneIntent.entries.forEach { intent ->
            FilterChip(
                selected = selected == intent,
                onClick = { onChange(intent) },
                label = { Text(intent.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Guidance level, as the three-way segmented control the spec asks for. */
@Composable
fun GuidanceLevelSelector(selected: GuidanceLevel, onChange: (GuidanceLevel) -> Unit, modifier: Modifier = Modifier) {
    val options = GuidanceLevel.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().padding(horizontal = SETTINGS_GUTTER)) {
        options.forEachIndexed { index, level ->
            SegmentedButton(
                selected = selected == level,
                onClick = { onChange(level) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(level.label()) },
            )
        }
    }
}

/** The "More" disclosure header: a 56dp row that expands the advanced settings below it. */
@Composable
fun MoreHeader(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    SettingsRow(
        label = "More",
        modifier = modifier.clickable(role = Role.Button, onClick = onToggle),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse more settings" else "Expand more settings",
            tint = OnSurface,
        )
    }
}

/** Wraps the collapsed "More" content so every caller animates it the same way. */
@Composable
fun MoreContent(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = expanded) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}
