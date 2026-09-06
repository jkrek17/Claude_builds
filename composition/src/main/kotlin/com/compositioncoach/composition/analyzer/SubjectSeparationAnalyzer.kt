package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectMask
import kotlin.math.abs

/**
 * Does the subject stand out from its background at all, tonally and texturally? A subject can be
 * perfectly *placed* and still disappear into a background that happens to match its brightness and
 * business — a dark shirt against a dark wall, a person made of the same "clutter frequency" as a busy
 * hedge behind them.
 *
 * **With a [SubjectMask]** (present only when a person segmenter ran): separation is measured against the
 * mask's own silhouette rather than a rectangular ring — mean luminance and edge density of subject cells
 * (probability >= [MaskHeuristics.SUBJECT_THRESHOLD]) versus a [RING_WIDTH_CELLS]-cell ring of background
 * cells traced around that shape (see [MaskHeuristics]). This is a meaningfully better signal than the
 * bounding-box approximation below — a person's silhouette is rarely a rectangle — so it runs at higher
 * confidence ([MASK_CONFIDENCE] vs [NO_MASK_CONFIDENCE]).
 *
 * **Without a mask** (or when the mask has no usable boundary): falls back to comparing the mean luminance
 * and edge density inside the subject's bounding box against a ring of background immediately around it
 * (see [NormalizedRect.inflate]).
 *
 * Either way: low separation on *both* signals means the subject truly blends in, and this then guesses
 * which fix is more relevant — if the tones are nearly identical, suggest lighting the subject differently;
 * if the background is about as busy as the subject itself, suggest a plainer patch of background — and
 * keeps the advice to one short sentence since this is a soft, secondary quality signal, not an urgent
 * framing fix.
 *
 * KNOWN APPROXIMATION (no-mask path only): the background ring's mean luminance/edge density is computed
 * by simply re-sampling the inflated rect, which still includes the subject's own cells. For a subject
 * that's a small fraction of the frame this barely moves the average.
 */
class SubjectSeparationAnalyzer : CompositionAnalyzer {
    override val name: String = "SubjectSeparationAnalyzer"
    override val category: MetricCategory = MetricCategory.SUBJECT_SEPARATION

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
        val stats = context.frame.stats
        if (subject == null || stats == null) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }
        val mask = context.frame.subjectMask
        return (mask?.let { analyzeWithMask(stats, it) }) ?: analyzeWithoutMask(subject, stats)
    }

    private fun analyzeWithMask(stats: ImageStatistics, mask: SubjectMask): CompositionMetric? {
        val subjectCells = MaskHeuristics.subjectCells(mask)
        val ring = MaskHeuristics.ringCells(mask, RING_WIDTH_CELLS)
        if (subjectCells.isEmpty() || ring.isEmpty()) return null

        val subjectLuminance = MaskHeuristics.meanLuminance(mask, stats, subjectCells)
        val ringLuminance = MaskHeuristics.meanLuminance(mask, stats, ring)
        val subjectEdge = MaskHeuristics.meanEdgeDensity(mask, stats, subjectCells)
        val ringEdge = MaskHeuristics.meanEdgeDensity(mask, stats, ring)

        val luminanceSeparation = (abs(subjectLuminance - ringLuminance) / LUMINANCE_SCALE).coerceIn(0f, 1f)
        val edgeSeparation = (abs(subjectEdge - ringEdge) / EDGE_SCALE).coerceIn(0f, 1f)
        val separation = maxOf(luminanceSeparation, edgeSeparation)

        val lowContrast = luminanceSeparation < LOW_SEPARATION_THRESHOLD
        val busyRing = ringEdge >= BUSY_RING_EDGE || edgeSeparation < LOW_SEPARATION_THRESHOLD
        val flagged = lowContrast && busyRing

        return metricFor(separation, MASK_CONFIDENCE, buildRecommendation(flagged, luminanceSeparation, edgeSeparation, MASK_CONFIDENCE))
    }

    private fun analyzeWithoutMask(subject: DetectedSubject, stats: ImageStatistics): CompositionMetric {
        val ring = subject.bounds.inflate(RING_MARGIN)
        val luminanceDiff = abs(stats.regionLuminance(subject.bounds) - stats.regionLuminance(ring))
        val edgeDiff = abs(stats.regionEdgeDensity(subject.bounds) - stats.regionEdgeDensity(ring))

        val luminanceSeparation = (luminanceDiff / LUMINANCE_SCALE).coerceIn(0f, 1f)
        val edgeSeparation = (edgeDiff / EDGE_SCALE).coerceIn(0f, 1f)
        val separation = maxOf(luminanceSeparation, edgeSeparation)
        val flagged = separation < LOW_SEPARATION_THRESHOLD

        return metricFor(separation, NO_MASK_CONFIDENCE, buildRecommendation(flagged, luminanceSeparation, edgeSeparation, NO_MASK_CONFIDENCE))
    }

    private fun buildRecommendation(flagged: Boolean, luminanceSeparation: Float, edgeSeparation: Float, confidence: Float): Recommendation? {
        if (!flagged) return null
        val suggestLighting = luminanceSeparation <= edgeSeparation
        return Recommendation(
            id = "separation.blends",
            category = category,
            priority = Priority.LOW,
            confidence = confidence,
            severity = Severity.LOW,
            title = "Subject blends into the background",
            instruction = if (suggestLighting) "Move so the light falls on your subject" else "Find a plainer background",
            reason = if (suggestLighting) {
                "The subject and its background are close to the same brightness."
            } else {
                "The background right behind the subject is about as busy as the subject itself."
            },
            direction = Direction.NONE,
        )
    }

    private fun metricFor(separation: Float, confidence: Float, recommendation: Recommendation?) = CompositionMetric(
        category = category,
        analyzerName = name,
        score = separation,
        confidence = confidence,
        severity = recommendation?.severity ?: Severity.NONE,
        issue = if (recommendation != null) "The subject barely stands out from what's behind it" else null,
        recommendation = recommendation,
        strength = if (separation >= 0.75f) "Subject stands out clearly from the background" else null,
    )

    companion object {
        const val RING_MARGIN = 0.08f
        const val RING_WIDTH_CELLS = 2
        const val LUMINANCE_SCALE = 0.25f
        const val EDGE_SCALE = 0.3f
        const val LOW_SEPARATION_THRESHOLD = 0.35f
        /** Raw ring edge density at/above this reads as "busy" outright, even if the diff from the subject is modest. */
        const val BUSY_RING_EDGE = 0.25f
        const val MASK_CONFIDENCE = 0.85f
        const val NO_MASK_CONFIDENCE = 0.5f
    }
}
