package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity

/**
 * Overall visual balance: is there a large mass of visual weight (busy/high-contrast area) heavily
 * off to one side with nothing on the other side to counter it, and no subject to explain the asymmetry?
 *
 * Uses [ImageStatistics.visualWeightCentroid] as a cheap proxy for "where does the eye's attention mass
 * sit". A centroid pulled far from the frame centre is only a *problem* when it isn't simply explained by
 * the primary subject sitting off-centre on purpose (that's [SubjectPlacementAnalyzer]'s job to grade) —
 * so this analyzer only fires when the heavy mass is somewhere the subject *isn't*, e.g. a bright window
 * or a dense tree filling one side while the subject (or nothing at all, for a landscape) sits elsewhere.
 *
 * Deliberately soft: balance is a matter of taste more than a hard rule, so this never exceeds
 * [Severity.LOW] and carries a below-average weight in every scene preset.
 */
class BalanceAnalyzer : CompositionAnalyzer {
    override val name: String = "BalanceAnalyzer"
    override val category: MetricCategory = MetricCategory.BALANCE

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val stats = context.frame.stats
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val centroid = stats.visualWeightCentroid()
        val offset = centroid.distanceTo(NormalizedPoint.CENTER)
        val subject = context.primarySubject
        val explainedBySubject = subject != null && centroid.distanceTo(subject.center) < EXPLAINED_RADIUS

        val score = if (explainedBySubject) 1f else (1f - (offset / MAX_MEANINGFUL_OFFSET).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val flagged = !explainedBySubject && offset > OFFSET_THRESHOLD

        val recommendation = if (!flagged) {
            null
        } else {
            val vector = ReframeVector(dx = (centroid.x - 0.5f) * BALANCE_GAIN, dy = (0.5f - centroid.y) * BALANCE_GAIN)
            val direction = vector.primaryDirection()
            Recommendation(
                id = "balance.heavyside",
                category = category,
                priority = Priority.LOW,
                confidence = 0.5f,
                severity = Severity.LOW,
                title = "Balance the frame",
                instruction = InstructionText.forDirection(direction),
                reason = "One side of the frame carries a lot more visual weight than the other.",
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.55f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (flagged) "Unbalanced visual weight" else null,
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.TargetPoint(centroid, "visual weight")),
            strength = if (score >= 0.85f) "Well-balanced frame" else null,
        )
    }

    companion object {
        const val EXPLAINED_RADIUS = 0.22f
        const val OFFSET_THRESHOLD = 0.14f
        const val MAX_MEANINGFUL_OFFSET = 0.35f
        const val BALANCE_GAIN = 0.3f
    }
}
