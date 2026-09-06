package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme

/**
 * The pinch-zoom readout ("1.0×") shown near the shutter for as long as [visible] is true — [CameraScreen]
 * flips that to false 1.2s after the last pinch gesture, and this fades out over the same
 * [androidx.compose.animation.fadeOut] every other transient chip on this screen uses.
 */
@Composable
fun ZoomChip(zoomRatio: Float, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text = ZoomChipFormatter.format(zoomRatio),
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Preview(name = "Zoom chip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomChipPreview() {
    CompositionCoachTheme { ZoomChip(zoomRatio = 2.5f, visible = true) }
}
