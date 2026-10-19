package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import kotlinx.coroutines.delay

/** How long "Point at a subject" lingers after a subject actually appears, before fading for good. */
private const val POINT_AT_SUBJECT_LINGER_MS = 3_000L

/**
 * "Point at a subject" shows until the engine classifies a scene, then lingers for three seconds so the
 * hand-off into real guidance doesn't read as the chip blinking out, and never comes back until the scene
 * is lost again.
 */
@Composable
internal fun rememberPointAtSubjectVisible(hasScene: Boolean): Boolean {
    var visible by remember { mutableStateOf(!hasScene) }
    LaunchedEffect(hasScene) {
        if (hasScene) {
            delay(POINT_AT_SUBJECT_LINGER_MS)
            visible = false
        } else {
            visible = true
        }
    }
    return visible
}

internal fun previewRecommendation(
    direction: Direction,
    category: MetricCategory = MetricCategory.SUBJECT_PLACEMENT,
    title: String = "Move slightly right",
    severity: Severity = Severity.MEDIUM,
    reason: String? = null,
) = Recommendation(
    id = "preview.${category.name}.${direction.name}",
    category = category,
    priority = Priority.HIGH,
    confidence = 0.9f,
    severity = severity,
    title = title,
    instruction = title,
    reason = reason,
    direction = direction,
)

private val PreviewComposition = SmoothedComposition.EMPTY.copy(
    displayScore = 74,
    activeRecommendations = listOf(
        previewRecommendation(Direction.RIGHT, reason = "Their face sits dead centre"),
        previewRecommendation(Direction.NONE, MetricCategory.HORIZON, "Level the horizon"),
    ),
    scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
)

@Preview(name = "Coaching layer, Easy (portrait, move right)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerEasyPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = PreviewComposition,
                guidanceLevel = GuidanceLevel.BALANCED,
                showScore = false,
                hasScene = true,
                deviceRotationDegrees = 0,
                animationsEnabled = false,
                showSecondary = false,
            )
        }
    }
}

@Preview(name = "Coaching layer, Coach (portrait, reason line)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerCoachPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = PreviewComposition,
                guidanceLevel = GuidanceLevel.COACH,
                showScore = true,
                hasScene = true,
                deviceRotationDegrees = 0,
                animationsEnabled = false,
                showReason = true,
            )
        }
    }
}

@Preview(name = "Coaching layer, Coach (rotation 90)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerRotatedPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = PreviewComposition,
                guidanceLevel = GuidanceLevel.COACH,
                showScore = true,
                hasScene = true,
                deviceRotationDegrees = 90,
                animationsEnabled = false,
                showReason = true,
            )
        }
    }
}

@Preview(name = "Coaching layer, hold instruction", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerHoldPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = PreviewComposition,
                guidanceLevel = GuidanceLevel.BALANCED,
                showScore = false,
                hasScene = true,
                deviceRotationDegrees = 0,
                animationsEnabled = false,
                overrideChip = LiveChip("Hold still for 4 seconds"),
            )
        }
    }
}
