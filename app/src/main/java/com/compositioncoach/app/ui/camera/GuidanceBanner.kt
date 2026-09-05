package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

private const val MAX_LINES = 3

/**
 * The instruction readout below the score: the primary recommendation crossfades as it changes, an
 * optional COACH-mode reason sits under it, and up to two secondary recommendations follow in smaller
 * type. Never renders more lines than [activeRecommendations] actually has content for, and never more
 * than [MAX_LINES] total.
 */
@Composable
fun GuidanceBanner(
    activeRecommendations: List<Recommendation>,
    guidanceLevel: GuidanceLevel,
    modifier: Modifier = Modifier,
) {
    val primary = activeRecommendations.firstOrNull() ?: return
    val reason = GuidanceFormatter.reasonLine(primary, guidanceLevel)
    val budgetForSecondary = (MAX_LINES - 1 - (if (reason != null) 1 else 0)).coerceAtLeast(0)
    val secondary = activeRecommendations.drop(1).take(budgetForSecondary)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        AnimatedContent(
            targetState = primary,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "primaryInstruction",
        ) { rec ->
            Text(
                text = GuidanceFormatter.primaryLine(rec),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        reason?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        secondary.forEach { rec ->
            Text(
                text = GuidanceFormatter.secondaryLine(rec),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

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

@Preview(name = "Coach level (with reason)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerCoachPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(
                previewRecommendation("headroom", "Move slightly right", "Keeps eyes on the upper third", Direction.RIGHT),
                previewRecommendation("horizon", "Level the horizon", "The horizon is tilted 4°", Direction.ROTATE_COUNTER_CLOCKWISE),
            ),
            guidanceLevel = GuidanceLevel.COACH,
        )
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
