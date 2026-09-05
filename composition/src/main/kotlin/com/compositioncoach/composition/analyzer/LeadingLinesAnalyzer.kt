package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.geometry.RuleOfThirds
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import kotlin.math.hypot

/**
 * EXPERIMENTAL: rewards a frame where a strong detected line (a road, railing, shoreline...), extended
 * beyond its detected endpoints, passes close to the subject (or, with no subject, a thirds point) — the
 * classic "leading line draws the eye to the subject" composition.
 *
 * This is inherently a soft, exploratory signal (real leading-line judgement needs to reason about the
 * whole line's visual path, not just proximity of its infinite extension to one point) so it carries a
 * low weight in every scene preset and, deliberately, never produces a [Recommendation] — only a score
 * and, when a line lines up nicely, a positive [CompositionMetric.strength] string. Treat the score as a
 * loose hint, not a graded rule, until it has been validated against real photos.
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

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = (line.strength * 0.6f).coerceIn(0f, 0.6f), // low weight even at its most confident: experimental
            geometry = listOf(OverlayGeometry.Line(line.start, line.end, "leading line")),
            strength = if (score >= 0.75f) "A leading line draws the eye to the subject" else null,
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
    }
}
