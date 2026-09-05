package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.scoreColor

/**
 * The always-visible score readout: a large animated number, subtly colour-coded, with a distinct
 * shoot-ready state ("94 — SHOOT" + a gentle pulse). Hidden entirely when [showScore] is off; shows a small
 * "Looking for a subject…" hint instead of a number until [hasScene] is true.
 */
@Composable
fun ScoreBadge(
    score: Int,
    isShootReady: Boolean,
    hasScene: Boolean,
    showScore: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showScore) return

    val animatedScore by animateIntAsState(
        targetValue = score,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "score",
    )
    val tier = GuidanceFormatter.scoreTier(score)
    val targetColor = if (isShootReady) scoreColor(ScoreTier.EXCELLENT) else scoreColor(tier)
    val color by animateColorAsState(targetColor, label = "scoreColor")

    val pulse by animateFloatAsState(
        targetValue = if (isShootReady) 1.06f else 1f,
        animationSpec = if (isShootReady) {
            infiniteRepeatable(tween(700), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse)
        } else {
            tween(200)
        },
        label = "pulse",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .scale(pulse),
    ) {
        if (!hasScene) {
            Text(
                text = GuidanceFormatter.NO_SUBJECT_TEXT,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Text(
            text = if (isShootReady) GuidanceFormatter.shootReadyScoreText(animatedScore) else animatedScore.toString(),
            color = color,
            fontSize = if (isShootReady) 34.sp else 44.sp,
            fontWeight = FontWeight.Bold,
        )
        if (isShootReady) {
            Text(
                text = GuidanceFormatter.SHOOT_READY_SUBTITLE,
                color = color.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview(name = "Score states", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ScoreBadgePreview() {
    CompositionCoachTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScoreBadge(score = 42, isShootReady = false, hasScene = true, showScore = true)
            ScoreBadge(score = 78, isShootReady = false, hasScene = true, showScore = true)
            ScoreBadge(score = 94, isShootReady = true, hasScene = true, showScore = true)
            ScoreBadge(score = 0, isShootReady = false, hasScene = false, showScore = true)
        }
    }
}
