package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** Top row: a tiny "DEBUG" chip (debug mode only) at the left, the settings icon at the right. */
@Composable
fun CameraTopBar(showDebugChip: Boolean, onSettingsClick: () -> Unit, modifier: Modifier = Modifier) {
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
        IconButton(onClick = onSettingsClick, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Preview(name = "Debug chip + settings", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarPreview() {
    CompositionCoachTheme { CameraTopBar(showDebugChip = true, onSettingsClick = {}) }
}
