package com.compositioncoach.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.settings.description
import com.compositioncoach.app.settings.label
import com.compositioncoach.app.ui.theme.OnSurface
import com.compositioncoach.app.ui.theme.Surface
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

private const val SEGMENTED_ROW_MAX_OPTIONS = 4

/** A titled, one-line-summarized group card — the container every settings section lives in. */
@Composable
fun SettingsCard(title: String, summary: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        content()
    }
}

/** [SingleChoiceSegmentedButtonRow] when the options fit on one row without crowding, chips otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneIntentSelector(selected: SceneIntent, onChange: (SceneIntent) -> Unit) {
    val options = SceneIntent.entries
    if (options.size <= SEGMENTED_ROW_MAX_OPTIONS) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, intent ->
                SegmentedButton(
                    selected = selected == intent,
                    onClick = { onChange(intent) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(intent.label) },
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { intent ->
                FilterChip(selected = selected == intent, onClick = { onChange(intent) }, label = { Text(intent.label) })
            }
        }
    }
}

/** A settings switch row; [subtitle], when given, is a smaller descriptive line under [label]. At least
 * 48dp tall so the switch's tap target always meets the minimum, even for a one-line row. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun GuidanceLevelRow(level: GuidanceLevel, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(level.label(), style = MaterialTheme.typography.bodyLarge)
            Text(level.description(), style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
        }
    }
}
