package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.ScoreWeights
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * Everything the debug/developer setting exposes: per-metric scores, recommendation confidences,
 * timings, and the optimizer's candidate framings. Text-only (boxes/landmarks are [DebugGeometryOverlay]);
 * collapsible so it doesn't have to stay in the way while checking the live preview.
 */
@Composable
fun DebugOverlay(composition: SmoothedComposition, debugStats: DebugStats, modifier: Modifier = Modifier) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val raw = composition.raw

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .widthIn(max = 260.dp)
            .padding(10.dp),
    ) {
        Row(modifier = Modifier.clickable { collapsed = !collapsed }) {
            DebugText(if (collapsed) "DEBUG ▸" else "DEBUG ▾", bold = true, color = Color(0xFFFFC857))
        }
        if (collapsed) return@Column

        Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            DebugText("Scene: ${raw.scene.type} (${(raw.scene.confidence * 100).toInt()}%)  Intent: ${raw.intent.label}")
            DebugText("Score: raw=${"%.1f".format(raw.rawScore)} smoothed=${composition.displayScore}")
            DebugText("Engine: ${raw.engineTimeMs}ms  FPS: ${"%.1f".format(debugStats.fps)}")
            DebugText("Latency: ${debugStats.lastLatencyMs}ms  Interval: ${debugStats.samplingIntervalMs}ms")
            DebugText("Shoot ready: ${raw.isShootReady}")
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            DebugText("Metrics", bold = true)
            raw.metrics.forEach { m ->
                DebugText(
                    "${m.category}: score=${"%.2f".format(m.score)} conf=${"%.2f".format(m.confidence)} " +
                        "sev=${m.severity} applicable=${m.applicable}",
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            DebugText("Recommendations", bold = true)
            raw.recommendations.forEach { r ->
                DebugText("${r.id} pri=${r.priority} conf=${"%.2f".format(r.confidence)} dir=${r.direction}")
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            DebugText("Detector timings", bold = true)
            if (debugStats.detectorTimings.isEmpty()) {
                DebugText("(none)")
            } else {
                // "mask_age" is frames-since-refresh, not a wall-clock time like every other entry here
                // (see FrameAnalysis.detectorTimings' kdoc) — worth a distinct unit in the label so it
                // doesn't read as "40ms" when it means "40 frames old".
                debugStats.detectorTimings.forEach { (name, value) ->
                    DebugText(if (name == "mask_age") "  $name: $value frames" else "  $name: ${value}ms")
                }
            }
            DebugText("Subjects: ${raw.subjects.size}  Primary: ${raw.primarySubject?.kind ?: "none"}")

            raw.optimization?.let { opt ->
                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                DebugText("Optimizer: current=${"%.1f".format(opt.currentScore)} improvement=${"%.1f".format(opt.improvement)}", bold = true)
                opt.candidates.forEach { c ->
                    DebugText("  ${c.label}: ${"%.1f".format(c.predictedScore)}")
                }
            }
        }
    }
}

@Composable
private fun DebugText(text: String, bold: Boolean = false, color: Color = Color.White) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
    )
}

@Preview(name = "Debug panel", showBackground = true, backgroundColor = 0xFF202020)
@Composable
private fun DebugOverlayPreview() {
    val raw = CompositionResult(
        timestampNanos = 0L,
        score = 82,
        rawScore = 81.6f,
        scene = SceneClassification(SceneType.PORTRAIT, confidence = 0.87f, hasHorizon = true),
        metrics = listOf(
            CompositionMetric(MetricCategory.HORIZON, "HorizonAnalyzer", score = 0.95f, confidence = 0.9f, severity = Severity.NONE),
            CompositionMetric(MetricCategory.HEADROOM, "HeadroomAnalyzer", score = 0.6f, confidence = 0.8f, severity = Severity.MEDIUM),
        ),
        recommendations = emptyList(),
        subjects = emptyList(),
        primarySubject = null,
        isShootReady = false,
        weights = ScoreWeights.forScene(SceneType.PORTRAIT),
        engineTimeMs = 6L,
    )
    CompositionCoachTheme {
        DebugOverlay(
            composition = SmoothedComposition(82, emptyList(), false, raw.scene, null, raw),
            debugStats = DebugStats(
                fps = 8.7f,
                lastLatencyMs = 34L,
                samplingIntervalMs = 100L,
                engineTimeMs = 6L,
                detectorTimings = mapOf("face" to 4L, "pose" to 9L, "objects" to 11L, "segmentation" to 22L, "mask_age" to 2L),
            ),
        )
    }
}
