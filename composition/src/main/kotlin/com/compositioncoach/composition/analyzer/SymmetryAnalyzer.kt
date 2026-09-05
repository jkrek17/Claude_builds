package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs

/**
 * Rewards deliberate mirror symmetry (a doorway, a reflection, a face-on building facade) and, when the
 * scene is strongly symmetric, insists the composition actually be centred to cash in on that symmetry —
 * the opposite bias from [SubjectPlacementAnalyzer]'s usual "avoid dead centre" rule.
 *
 * Only applicable at all when [ImageStatistics.horizontalSymmetry] clears [LOW_SYMMETRY_THRESHOLD]: a
 * composition with weak or no symmetry should not be punished for "not being symmetric" — that's simply
 * not what it's going for, so this analyzer stays out of the way (`applicable = false`).
 *
 * Once a scene *is* strongly symmetric, being off-centre by more than [OFF_CENTER_THRESHOLD] (~4%)
 * undermines the whole point and is treated as a high-priority fix ("Strong symmetry — center the shot"),
 * stronger than the everyday centring nudge [SubjectPlacementAnalyzer] gives.
 */
class SymmetryAnalyzer : CompositionAnalyzer {
    override val name: String = "SymmetryAnalyzer"
    override val category: MetricCategory = MetricCategory.SYMMETRY

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val stats = context.frame.stats
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        if (stats.horizontalSymmetry < LOW_SYMMETRY_THRESHOLD) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }

        val referenceX = context.primarySubject?.anchorPoint?.x ?: stats.visualWeightCentroid().x
        val offCenter = abs(referenceX - 0.5f)
        val score = (stats.horizontalSymmetry * (1f - (offCenter / MAX_OFFSET).coerceIn(0f, 1f))).coerceIn(0f, 1f)
        val flagged = offCenter > OFF_CENTER_THRESHOLD

        val recommendation = if (!flagged) {
            null
        } else {
            val vector = ReframeVector(dx = referenceX - 0.5f)
            val direction = vector.primaryDirection()
            Recommendation(
                id = "symmetry.offcenter",
                category = category,
                priority = Priority.HIGH,
                confidence = stats.horizontalSymmetry,
                severity = Severity.MEDIUM,
                title = "Strong symmetry — center the shot",
                instruction = InstructionText.forDirection(direction),
                reason = "The scene is strongly symmetric, so centring it reads much better than an off-centre crop.",
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = stats.horizontalSymmetry,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (flagged) "Symmetric scene is off-centre" else null,
            recommendation = recommendation,
            strength = if (!flagged && stats.horizontalSymmetry >= 0.85f) "Strong, well-centred symmetry" else null,
        )
    }

    companion object {
        const val LOW_SYMMETRY_THRESHOLD = 0.6f
        const val OFF_CENTER_THRESHOLD = 0.04f
        const val MAX_OFFSET = 0.3f
    }
}
