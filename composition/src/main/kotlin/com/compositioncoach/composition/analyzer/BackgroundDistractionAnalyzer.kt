package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.engine.StatsHeuristics.meanEdgeDensity
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity

/**
 * Looks for two classic distracting-background problems using only the cheap [ImageStatistics] grids
 * (no extra detector needed):
 *
 *  1. **"Growing out of the head"**: a narrow column directly above the subject's head — from the top of
 *     the head up to the frame's top edge — that is much busier (edge density) or brighter than the rest
 *     of the background. This is the textbook "a lamppost/tree appears to sprout from their head" error.
 *  2. **General clutter**: the ring of background immediately around the head is noticeably busier than
 *     the background elsewhere, even without a single obvious "growing" object.
 *
 * Either case is fixed the same way: pan toward whichever side (left/right of the face) is *less* busy,
 * since that's the direction that pulls the background behind the head away from the distracting area.
 * [confidence] scales with how strong the contrast against the background is — a marginal difference
 * isn't worth interrupting the photographer for.
 */
class BackgroundDistractionAnalyzer : CompositionAnalyzer {
    override val name: String = "BackgroundDistractionAnalyzer"
    override val category: MetricCategory = MetricCategory.BACKGROUND_DISTRACTION

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val face = context.primarySubject?.face
        val stats = context.frame.stats
        if (face == null || stats == null) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }

        val headTop = face.estimatedHeadTop.coerceIn(0f, 1f)
        val background = stats.meanEdgeDensity().coerceAtLeast(MIN_BACKGROUND_EDGE)

        var columnRatio = 1f
        var brightnessContrast = 0f
        if (headTop > MIN_COLUMN_HEIGHT) {
            val column = NormalizedRect(face.bounds.left, 0f, face.bounds.right, headTop)
            columnRatio = stats.regionEdgeDensity(column) / background
            brightnessContrast = stats.regionLuminance(column) - stats.meanLuminance
        }

        val ring = face.bounds.let { NormalizedRect(it.left - RING_MARGIN, it.top - RING_MARGIN, it.right + RING_MARGIN, it.bottom + RING_MARGIN) }
        val ringRatio = stats.regionEdgeDensity(ring) / background

        val growingOutOfHead = columnRatio >= COLUMN_RATIO_THRESHOLD || brightnessContrast >= BRIGHTNESS_THRESHOLD
        val generalClutter = ringRatio >= RING_RATIO_THRESHOLD

        val worstRatio = maxOf(columnRatio, ringRatio)
        val score = (1f - ((worstRatio - 1f) / (COLUMN_RATIO_THRESHOLD)).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (!growingOutOfHead && !generalClutter) {
            null
        } else {
            val leftEdge = stats.regionEdgeDensity(NormalizedRect(0f, face.bounds.top, face.bounds.left, face.bounds.bottom))
            val rightEdge = stats.regionEdgeDensity(NormalizedRect(face.bounds.right, face.bounds.top, 1f, face.bounds.bottom))
            val moveLeft = leftEdge <= rightEdge
            val direction = if (moveLeft) Direction.LEFT else Direction.RIGHT
            val confidence = ((worstRatio - 1f) / 2f).coerceIn(0.3f, 0.95f)
            Recommendation(
                id = if (growingOutOfHead) "background.growingfromhead" else "background.clutter",
                category = category,
                priority = if (growingOutOfHead) Priority.HIGH else Priority.MEDIUM,
                confidence = confidence,
                severity = if (growingOutOfHead) Severity.MEDIUM else Severity.LOW,
                title = if (growingOutOfHead) "Object appears to be coming out of subject's head" else "Background is distracting",
                instruction = if (moveLeft) "Move slightly left to clear the background" else "Move slightly right to clear the background",
                reason = "The background right behind the subject is busier or brighter than the rest of the frame.",
                direction = direction,
                vector = ReframeVector(dx = if (moveLeft) -PAN_MAGNITUDE else PAN_MAGNITUDE),
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.7f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = recommendation?.title,
            recommendation = recommendation,
            geometry = listOfNotNull(recommendation?.let { OverlayGeometry.Region(ring, isProblem = true) }),
            strength = if (score >= 0.9f) "Clean background" else null,
        )
    }

    companion object {
        const val MIN_COLUMN_HEIGHT = 0.02f
        const val COLUMN_RATIO_THRESHOLD = 1.6f
        const val BRIGHTNESS_THRESHOLD = 0.22f
        const val RING_RATIO_THRESHOLD = 1.4f
        const val RING_MARGIN = 0.06f
        const val MIN_BACKGROUND_EDGE = 0.02f
        const val PAN_MAGNITUDE = 0.08f
    }
}
