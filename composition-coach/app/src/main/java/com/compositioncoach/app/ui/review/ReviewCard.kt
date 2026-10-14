package com.compositioncoach.app.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.camera.GuidanceFormatter
import com.compositioncoach.app.ui.camera.drawReadinessArc
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType

/** The review card's score ring, per `docs/APP_UX.md`. */
private val SCORE_RING_SIZE = 56.dp

/**
 * The card under the photo: the shutter's readiness arc reused as a 56dp score ring with the number
 * inside, the mode chip beside it, then up to two strengths and up to two improvements as plain
 * sentences. This is the one screen where sentences belong — the live camera says everything in three
 * words, and the *why* lands here.
 */
@Composable
fun ReviewCard(result: CompositionResult, modifier: Modifier = Modifier) {
    val feedback = ReviewFormatter.trim(result.strengths, result.improvements)
    val subjectLine = ReviewFormatter.subjectLine(result.primarySubject)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = result.score, isShootReady = result.isShootReady)
            Spacer(Modifier.width(16.dp))
            Column {
                ModeChip(intent = result.intent, scene = result.scene)
                if (subjectLine != null) {
                    Text(
                        text = subjectLine,
                        color = OnScrimMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (feedback.strengths.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            feedback.strengths.forEach { BulletLine(it, Accent.Ready) }
        }
        if (feedback.improvements.isNotEmpty()) {
            Spacer(Modifier.height(if (feedback.strengths.isNotEmpty()) 4.dp else 12.dp))
            feedback.improvements.forEach { BulletLine(it, Accent.Warn) }
        }
    }
}

/** The same arc the shutter draws, at review size, with the score inside it. */
@Composable
private fun ScoreRing(score: Int, isShootReady: Boolean) {
    Box(
        modifier = Modifier
            .size(SCORE_RING_SIZE)
            .clearAndSetSemantics { contentDescription = GuidanceFormatter.scoreAnnouncement(score) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(SCORE_RING_SIZE)) {
            drawReadinessArc(score = score, isShootReady = isShootReady, strokeWidthPx = 3.dp.toPx())
        }
        Text(
            text = score.toString(),
            color = OnScrim,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        )
    }
}

/** The shooting mode the shot was coached under — the detected scene when the mode was Auto. */
@Composable
private fun ModeChip(intent: SceneIntent, scene: SceneClassification) {
    val label = if (intent == SceneIntent.AUTO) {
        "Auto · ${scene.type.name.lowercase().replaceFirstChar { it.uppercase() }}"
    } else {
        intent.label
    }
    Text(
        text = label,
        color = OnScrim,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Scrim)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun BulletLine(text: String, dotColor: Color) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text("•  ", color = dotColor, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = OnScrim.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Review card", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ReviewCardPreview() {
    CompositionCoachTheme {
        ReviewCard(
            result = CompositionResult.empty().copy(
                score = 86,
                scene = SceneClassification(SceneType.PORTRAIT, confidence = 0.9f),
                strengths = listOf("Level horizon", "Good subject separation"),
                improvements = listOf("Give a little more headroom", "Move the subject off-center"),
                intent = SceneIntent.PORTRAIT,
            ),
        )
    }
}

@Preview(name = "Review card, auto mode", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ReviewCardAutoPreview() {
    CompositionCoachTheme {
        ReviewCard(
            result = CompositionResult.empty().copy(
                score = 93,
                isShootReady = true,
                scene = SceneClassification(SceneType.LANDSCAPE, confidence = 0.8f),
                strengths = listOf("Level horizon", "Strong leading lines"),
                improvements = listOf("A touch more sky"),
            ),
        )
    }
}
