package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.ScoreWeights
import com.compositioncoach.composition.model.Severity

/**
 * Turns a frame's [CompositionMetric]s into the single 0..100 score shown to the photographer.
 *
 * The score is a confidence- and scene-weighted mean of every *applicable* metric's own 0..1 score:
 * each metric's [ScoreWeights] weight for the current [SceneClassification.type] is multiplied by the
 * metric's own [CompositionMetric.confidence] before being folded into the weighted average, so an
 * analyzer that isn't sure about its own reading (e.g. horizon estimated from a faint image line) has
 * less influence than one that is (a reliable device-attitude reading). Inapplicable metrics are simply
 * excluded from both the numerator and denominator, rather than counted as a zero — a landscape with no
 * headroom concept to speak of shouldn't be scored down for "failing" headroom.
 *
 * On top of the weighted mean, a small flat penalty is subtracted per applicable [Severity.HIGH] metric:
 * the weighted mean alone can hide a single glaring problem (e.g. a badly cropped foot) behind a dozen
 * mediocre-but-fine categories: a HIGH severity issue should visibly cap the score, not just nudge it.
 */
object ScoreAggregator {
    /** Score returned when no metric had anything applicable to say (e.g. a totally empty frame). */
    const val NEUTRAL_SCORE = 70f
    const val HIGH_SEVERITY_PENALTY = 3.5f
    const val MAX_HIGH_SEVERITY_PENALTY = 20f

    fun aggregate(metrics: List<CompositionMetric>, scene: SceneClassification): Float {
        val weights = ScoreWeights.forScene(scene.type)
        val applicable = metrics.filter { it.applicable }
        if (applicable.isEmpty()) return NEUTRAL_SCORE

        var weightedSum = 0f
        var weightTotal = 0f
        for (metric in applicable) {
            val weight = weights[metric.category] * metric.confidence.coerceIn(0f, 1f)
            if (weight <= 0f) continue
            weightedSum += weight * metric.score.coerceIn(0f, 1f)
            weightTotal += weight
        }
        if (weightTotal <= 0f) return NEUTRAL_SCORE

        val meanScore = (weightedSum / weightTotal) * 100f
        val highSeverityCount = applicable.count { it.severity == Severity.HIGH }
        val penalty = (highSeverityCount * HIGH_SEVERITY_PENALTY).coerceAtMost(MAX_HIGH_SEVERITY_PENALTY)
        return (meanScore - penalty).coerceIn(0f, 100f)
    }
}
