package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrimMuted
import kotlinx.coroutines.delay

private const val LINGER_AFTER_SCENE_MS = 3_000L

/**
 * "Point at a subject" — a small centred line under the score badge, shown before the engine has
 * classified any scene at all. Once a scene appears it lingers for [LINGER_AFTER_SCENE_MS] (so the
 * transition into real guidance doesn't feel abrupt) and then fades out for good until the scene is lost
 * again. Never shown alongside [awaitingSubject]'s own headline in [GuidanceBanner], which already covers
 * that more specific "needs a subject" case.
 */
@Composable
fun EmptySceneHint(hasScene: Boolean, awaitingSubject: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(!hasScene) }
    LaunchedEffect(hasScene) {
        if (hasScene) {
            delay(LINGER_AFTER_SCENE_MS)
            visible = false
        } else {
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible && !awaitingSubject,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(400)),
        modifier = modifier,
    ) {
        Text(
            text = "Point at a subject",
            color = OnScrimMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Empty scene hint", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun EmptySceneHintPreview() {
    CompositionCoachTheme { EmptySceneHint(hasScene = false, awaitingSubject = false) }
}
