package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.geometry.RuleOfThirds
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import kotlin.math.hypot

/**
 * Still low-weight and still somewhat exploratory (real leading-line judgement needs to reason about the
 * whole line's visual path, not just proximity of its infinite extension to one point), but no longer
 * silent: rewards a frame where a strong detected line (a road, railing, shoreline...), extended beyond
 * its detected endpoints, passes within [CONVERGENCE_DISTANCE] (~8% of frame) of the primary subject's
 * anchor — or, with no subject, the nearest thirds intersection to the frame's visual weight — the classic
 * "leading line draws the eye to the subject" composition.
 *
 * A line that clearly does *not* converge on the subject (further than [LEADS_AWAY_DISTANCE]) now produces
 * a gentle [Severity.LOW] recommendation, but only in [SceneType.LANDSCAPE] / [SceneType.ARCHITECTURE]: a
 * portrait or object shot has plenty of other things to fix first, and a stray line running the wrong way
 * is rarely the photographer's biggest problem there, whereas landscapes and architecture shots are
 * frequently *built around* a leading line on purpose.
 */
class LeadingLinesAnalyzer : CompositionAnalyzer {
    override val name: String = "LeadingLinesAnalyzer"
    override val category: MetricCategory = MetricCategory.LEADING_LINES

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val stats = context.frame.stats
        if (stats == null || stats.dominantLines.isEmpty()) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }

        val target = context.primarySubject?.anchorPoint
            ?: RuleOfThirds.nearestIntersection(stats.visualWeightCentroid())

        val best = stats.dominantLines.map { it to perpendicularDistanceToInfiniteLine(it, target) }
            .minByOrNull { it.second } ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val (line, distance) = best
        val score = (1f - (distance / MAX_MEANINGFUL_DISTANCE).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val confidence = (line.strength * 0.6f).coerceIn(0f, 0.6f)

        val convergesOnSubject = distance <= CONVERGENCE_DISTANCE
        val leadsAway = distance >= LEADS_AWAY_DISTANCE
        val applicableScene = context.scene.type == SceneType.LANDSCAPE || context.scene.type == SceneType.ARCHITECTURE

        val recommendation = if (leadsAway && applicableScene) {
            Recommendation(
                id = "leadinglines.away",
                category = category,
                priority = Priority.LOW,
                confidence = confidence.coerceAtLeast(0.3f),
                severity = Severity.LOW,
                title = "Leading lines pull away from the subject",
                instruction = "Move so the lines lead toward your subject",
                reason = "A strong line in the frame runs away from the subject instead of toward it.",
                direction = Direction.NONE,
            )
        } else {
            null
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = confidence,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "A strong line leads the eye away from the subject" else null,
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.Line(line.start, line.end, "leading line")),
            strength = if (convergesOnSubject) "Leading lines draw the eye to your subject" else null,
        )
    }

    /** Perpendicular distance from [p] to the *infinite* line through [line]'s two points (not the segment). */
    private fun perpendicularDistanceToInfiniteLine(line: DetectedLine, p: NormalizedPoint): Float {
        val dx = line.end.x - line.start.x
        val dy = line.end.y - line.start.y
        val lenSquared = dx * dx + dy * dy
        if (lenSquared < 1e-9f) return p.distanceTo(line.start)
        val t = ((p.x - line.start.x) * dx + (p.y - line.start.y) * dy) / lenSquared
        val projX = line.start.x + t * dx
        val projY = line.start.y + t * dy
        return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
    }

    companion object {
        const val MAX_MEANINGFUL_DISTANCE = 0.35f

        /** "Passes within 8% of the subject/thirds point" — close enough to read as leading the eye there. */
        const val CONVERGENCE_DISTANCE = 0.08f

        /** Beyond this, the line's extension is clearly nowhere near the subject. */
        const val LEADS_AWAY_DISTANCE = 0.2f
    }
}
