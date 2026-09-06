package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.CompositionCoachTheme

/**
 * A single dismissible card shown once, on first launch after the camera permission is granted:
 * "Point at a subject. Follow the arrow. Shoot when it turns green." Dismissing it (or shooting at all —
 * see [CameraViewModel.onOnboardingDismissed]) persists `onboarding_seen` so it never shows again.
 */
@Composable
fun OnboardingCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_message),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_dismiss), color = Color.White)
            }
        }
    }
}

@Preview(name = "Onboarding card", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OnboardingCardPreview() {
    CompositionCoachTheme {
        OnboardingCard(onDismiss = {}, modifier = Modifier.padding(vertical = 24.dp).background(Color.Black))
    }
}
