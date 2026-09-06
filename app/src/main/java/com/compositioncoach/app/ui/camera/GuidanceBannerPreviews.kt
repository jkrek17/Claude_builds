package com.compositioncoach.app.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

/** [GuidanceBanner] previews, split from the component itself to keep that file compact — portrait states
 * plus the ROTATION_90 landscape state this task's field screenshot came from (see [RotatedChrome]).
 */
private fun previewRecommendation(id: String, instruction: String, reason: String, direction: Direction) = Recommendation(
    id = id,
    category = MetricCategory.SUBJECT_PLACEMENT,
    priority = Priority.HIGH,
    confidence = 0.9f,
    severity = Severity.MEDIUM,
    title = instruction,
    instruction = instruction,
    reason = reason,
    direction = direction,
)

private val FIELD_SCREENSHOT_RECOMMENDATIONS = listOf(
    previewRecommendation(
        "headroom",
        "Move slightly right",
        "The scene is strongly symmetric, so centring it reads much better than an off-centre crop.",
        Direction.RIGHT,
    ),
    previewRecommendation("background", "Find a plainer background", "", Direction.NONE),
)

@Preview(name = "Coach level (with reason)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerCoachPreview() {
    CompositionCoachTheme {
        GuidanceBanner(activeRecommendations = FIELD_SCREENSHOT_RECOMMENDATIONS, guidanceLevel = GuidanceLevel.COACH)
    }
}

@Preview(name = "Balanced level", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerBalancedPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(previewRecommendation("headroom", "Raise camera", "", Direction.UP)),
            guidanceLevel = GuidanceLevel.BALANCED,
        )
    }
}

@Preview(name = "Awaiting subject", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerAwaitingSubjectPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(
                Recommendation(
                    id = "intent.no_subject",
                    category = MetricCategory.SUBJECT_PLACEMENT,
                    priority = Priority.HIGH,
                    confidence = 1f,
                    severity = Severity.HIGH,
                    title = "Looking for a face",
                    instruction = "Move closer to your subject",
                ),
            ),
            guidanceLevel = GuidanceLevel.BALANCED,
            awaitingSubject = true,
        )
    }
}

/**
 * Landscape (`ROTATION_90`, phone's right edge up) preview: the same field-screenshot advice as the coach
 * preview above, wrapped in [RotatedChrome] exactly the way `CameraScreen` wraps the real score+banner
 * stack, capped to 60% of a representative preview height (see [RotatedChrome]/`chromeStackEdgeFor`'s
 * KDoc) — demonstrates the compact banner rotating 90 degrees in place without overflowing its slot, text
 * reading top-to-bottom with its own "up" toward the screen's right (Bug 1's fix).
 */
@Preview(name = "Landscape (ROTATION_90, right edge up)", showBackground = true, backgroundColor = 0xFF000000, widthDp = 400, heightDp = 400)
@Composable
private fun GuidanceBannerLandscapePreview() {
    CompositionCoachTheme {
        RotatedChrome(deviceRotationDegrees = 90) {
            GuidanceBanner(
                activeRecommendations = FIELD_SCREENSHOT_RECOMMENDATIONS,
                guidanceLevel = GuidanceLevel.COACH,
                maxWidthOverride = 240.dp,
            )
        }
    }
}
