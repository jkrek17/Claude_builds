package com.compositioncoach.app.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.R
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.camera.GuidanceFormatter
import com.compositioncoach.app.ui.camera.drawReadinessArc
import com.compositioncoach.app.ui.camera.OutcomeStripLogic
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted

private const val SHIMMER_MS = 900

/**
 * The quiet "still working" treatment for card sections while `ReviewEntry.analysing`: a slow 0.45..0.8 alpha
 * breathe, nothing that moves. With animations off it simply sits at 0.6.
 */
@Composable
fun Modifier.analysingShimmer(active: Boolean, animationsEnabled: Boolean): Modifier {
    if (!active) return this
    if (!animationsEnabled) return this.alpha(0.6f)
    val transition = rememberInfiniteTransition(label = "analysing")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS), RepeatMode.Reverse),
        label = "analysingAlpha",
    )
    return this.alpha(alpha)
}

/** The shutter's readiness arc at review size with the number inside: the same drawing, so they never drift. */
@Composable
fun ScoreRing(score: Int, isShootReady: Boolean, size: Dp = 56.dp) {
    Box(
        modifier = Modifier.size(size).clearAndSetSemantics { contentDescription = GuidanceFormatter.scoreAnnouncement(score) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            drawReadinessArc(score = score, isShootReady = isShootReady, strokeWidthPx = 3.dp.toPx())
        }
        Text(
            text = score.toString(),
            color = OnScrim,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        )
    }
}

/** A sentence with a coloured dot: green for what worked, amber for the one thing to try. */
@Composable
fun DotLine(text: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(top = 4.dp)) {
        Text("•  ", color = dotColor, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = OnScrim.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "Details", collapsed: the outcome the shot was taken under, the capture diagnostics table ([DiagnosticsPanel])
 * and every applicable measure ([AfterActionLogic.metricLines]). For the curious and for Pro.
 */
@Composable
fun ReviewDetails(entry: ReviewEntry, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.card_details), color = OnScrimMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = OnScrimMuted)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 8.dp)) {
                DetailLine(stringResource(R.string.card_details_outcome), stringResource(OutcomeStripLogic.labelRes(entry.outcome)))
                DiagnosticsPanel(diagnostics = entry.diagnostics, initiallyExpanded = true)
                Text(
                    stringResource(R.string.card_details_metrics),
                    color = OnScrimMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                AfterActionLogic.metricLines(AfterActionLogic.resultOf(entry)).forEach { (label, value) ->
                    DetailLine(label, value.toString())
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = OnScrimMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = OnScrim, style = MaterialTheme.typography.bodySmall)
    }
}
