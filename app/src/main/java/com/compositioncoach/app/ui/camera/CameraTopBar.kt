package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.SceneIntent

/**
 * Top row: a tiny "DEBUG" chip (debug mode only) at the left; at the right, a translucent shooting-mode
 * chip (shown only when [sceneIntent] isn't [SceneIntent.AUTO], tapping it opens Settings same as the gear)
 * next to the settings icon.
 */
@Composable
fun CameraTopBar(
    showDebugChip: Boolean,
    sceneIntent: SceneIntent,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (showDebugChip) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFC857))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(text = "DEBUG", color = Color.Black, fontSize = 10.sp)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sceneIntent != SceneIntent.AUTO) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(onClick = onSettingsClick)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(text = sceneIntent.label, color = Color.White, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onSettingsClick) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
    }
}

@Preview(name = "Debug chip + settings", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarPreview() {
    CompositionCoachTheme { CameraTopBar(showDebugChip = true, sceneIntent = SceneIntent.AUTO, onSettingsClick = {}) }
}

@Preview(name = "Shooting mode chip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarModeChipPreview() {
    CompositionCoachTheme { CameraTopBar(showDebugChip = false, sceneIntent = SceneIntent.PORTRAIT, onSettingsClick = {}) }
}
