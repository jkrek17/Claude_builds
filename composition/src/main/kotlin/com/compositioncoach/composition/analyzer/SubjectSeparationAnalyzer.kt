package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs

/**
 * Does the subject stand out from its background at all, tonally and texturally? A subject can be
 * perfectly *placed* and still disappear into a background that happens to match its brightness and
 * business — a dark shirt against a dark wall, a person made of the same "clutter frequency" as a busy
 * hedge behind them.
 *
 * Compares the mean luminance and edge density inside the subject's box against a ring of background
 * immediately around it (see [NormalizedRect.inflate]). Low separation on *both* signals means the
 * subject truly blends in; this analyzer then guesses which fix is more relevant — if the tones are
 * nearly identical, suggest lighting the subject differently; if edge density (texture/business) is
 * nearly identical, suggest a plainer patch of background — and keeps the advice to one short sentence
 * since this is a soft, secondary quality signal, not an urgent framing fix.
 *
 * KNOWN APPROXIMATION: the background ring's mean luminance/edge density is computed by simply
 * re-sampling the inflated rect, which still includes the subject's own cells. For a subject that's a
 * small fraction of the frame this barely moves the average; it avoids needing to subtract regions cell
 * by cell for a signal that's already a coarse, secondary one.
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

        val ring = subject.bounds.inflate(RING_MARGIN)
        val luminanceDiff = abs(stats.regionLuminance(subject.bounds) - stats.regionLuminance(ring))
        val edgeDiff = abs(stats.regionEdgeDensity(subject.bounds) - stats.regionEdgeDensity(ring))

        val luminanceSeparation = (luminanceDiff / LUMINANCE_SCALE).coerceIn(0f, 1f)
        val edgeSeparation = (edgeDiff / EDGE_SCALE).coerceIn(0f, 1f)
        val separation = maxOf(luminanceSeparation, edgeSeparation)

        val recommendation = if (separation >= LOW_SEPARATION_THRESHOLD) {
            null
        } else {
            val suggestLighting = luminanceSeparation <= edgeSeparation
            Recommendation(
                id = "separation.blends",
                category = category,
                priority = Priority.LOW,
                confidence = 0.55f,
                severity = Severity.LOW,
                title = "Subject blends into background",
                instruction = if (suggestLighting) "Let more light fall on the subject" else "Find a plainer background",
                reason = "The subject and background are similar in both tone and texture.",
                direction = Direction.NONE,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = separation,
            confidence = 0.5f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "Low subject/background separation" else null,
            recommendation = recommendation,
            strength = if (separation >= 0.75f) "Subject stands out from the background" else null,
        )
    }

    companion object {
        const val RING_MARGIN = 0.08f
        const val LUMINANCE_SCALE = 0.25f
        const val EDGE_SCALE = 0.3f
        const val LOW_SEPARATION_THRESHOLD = 0.35f
    }
}
