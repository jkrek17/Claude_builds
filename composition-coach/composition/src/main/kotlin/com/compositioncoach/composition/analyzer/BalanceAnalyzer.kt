package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.engine.StatsHeuristics.meanEdgeDensity
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectMask
import kotlin.math.abs

/**
 * Overall visual balance: is there a large mass of visual weight (busy/high-contrast area) heavily
 * off to one side with nothing on the other side to counter it, and no subject to explain the asymmetry?
 *
 * **With a [SubjectMask]** (present only when a person segmenter ran): the subject's own position comes
 * from the mask's bounding box rather than the (possibly face-only) subject box, and "visual weight" is
 * the edge-density mean of the *non-mask* cells on whichever side of the frame is empty of the subject
 * (see [MaskHeuristics.regionEdgeDensityExcludingMask]) — a subject sitting well to one side with nothing
 * of substance on the other reads as unbalanced, flagged at [Severity.LOW], *unless* the scene is
 * symmetric (subjectively fine at any offset) or the subject has [GazeDirection] looking room on that
 * empty side (a deliberate "looking room" composition is not the same problem as an accidental one).
 *
 * **Without a mask**: falls back to [ImageStatistics.visualWeightCentroid] as a cheap proxy for "where does
 * the eye's attention mass sit" — flagged only when that heavy mass isn't explained by the primary subject
 * sitting off-centre on purpose.
 *
 * Deliberately soft either way: balance is a matter of taste more than a hard rule, so this never exceeds
 * [Severity.LOW] and carries a below-average weight in every scene preset.
 */
class BalanceAnalyzer : CompositionAnalyzer {
    override val name: String = "BalanceAnalyzer"
    override val category: MetricCategory = MetricCategory.BALANCE

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val stats = context.frame.stats
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val mask = context.frame.subjectMask
        return (mask?.let { analyzeWithMask(context, stats, it) }) ?: analyzeWithoutMask(context, stats)
    }

    private fun analyzeWithMask(context: AnalysisContext, stats: ImageStatistics, mask: SubjectMask): CompositionMetric? {
        val subject = context.primarySubject ?: return null
        val maskBounds = mask.bounds() ?: return null

        val offset = maskBounds.center.x - 0.5f
        if (abs(offset) <= SUBJECT_OFFSET_THRESHOLD || context.scene.isSymmetricScene) {
            return CompositionMetric(category, name, score = 1f, confidence = MASK_CONFIDENCE, strength = "Well-balanced frame")
        }

        // The subject sits on this side; the *other* side is where a counterweight (or looking room) would live.
        val emptySideIsRight = offset < 0f
        val emptySideRect = if (emptySideIsRight) NormalizedRect(0.5f, 0f, 1f, 1f) else NormalizedRect(0f, 0f, 0.5f, 1f)
        val emptySideEdge = MaskHeuristics.regionEdgeDensityExcludingMask(mask, stats, emptySideRect)
        val background = stats.meanEdgeDensity().coerceAtLeast(MIN_BACKGROUND_EDGE)
        val emptySideRatio = emptySideEdge / background

        val gaze = subject.face?.gaze ?: GazeDirection.UNKNOWN
        val hasLookingRoomOnEmptySide = (emptySideIsRight && gaze == GazeDirection.RIGHT) || (!emptySideIsRight && gaze == GazeDirection.LEFT)

        val flagged = emptySideRatio < EMPTY_SIDE_RATIO_THRESHOLD && !hasLookingRoomOnEmptySide
        val score = if (flagged) (MIN_FLAGGED_SCORE + emptySideRatio * SCORE_SLOPE).coerceIn(0f, 0.85f) else 1f

        val recommendation = if (!flagged) {
            null
        } else {
            val vector = ReframeVector.toMoveSubject(NormalizedPoint(maskBounds.center.x, 0.5f), NormalizedPoint.CENTER)
            val direction = vector.primaryDirection()
            Recommendation(
                id = "balance.emptyside",
                category = category,
                priority = Priority.LOW,
                confidence = MASK_CONFIDENCE,
                severity = Severity.LOW,
                title = "Balance the frame",
                instruction = InstructionText.forDirection(direction),
                reason = "The subject sits to one side of the frame with nothing on the other side to balance it.",
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = MASK_CONFIDENCE,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (flagged) "One side of the frame is empty with nothing to balance the subject" else null,
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.Region(maskBounds)),
            strength = if (!flagged) "Well-balanced frame" else null,
        )
    }

    private fun analyzeWithoutMask(context: AnalysisContext, stats: ImageStatistics): CompositionMetric {
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
                confidence = NO_MASK_CONFIDENCE,
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
            confidence = NO_MASK_CONFIDENCE,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (flagged) "One side of the frame carries a lot more visual weight than the other" else null,
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
        const val NO_MASK_CONFIDENCE = 0.55f

        const val SUBJECT_OFFSET_THRESHOLD = 0.1f
        const val EMPTY_SIDE_RATIO_THRESHOLD = 0.6f
        const val MIN_BACKGROUND_EDGE = 0.02f
        const val MIN_FLAGGED_SCORE = 0.5f
        const val SCORE_SLOPE = 0.3f
        const val MASK_CONFIDENCE = 0.75f
    }
}
