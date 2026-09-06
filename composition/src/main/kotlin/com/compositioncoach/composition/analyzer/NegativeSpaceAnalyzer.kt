package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity

/**
 * Large areas of plain, empty space ("negative space") around a subject are a legitimate and often
 * deliberate compositional choice (minimalist portraits, a small figure in a big landscape), so this
 * analyzer is deliberately quiet: it scores a comfortable neutral value and *never* recommends filling
 * the space in.
 *
 * The one thing it does flag is a subject that has become so small relative to the frame
 * ([TINY_SUBJECT_AREA], under ~3% of the frame's area) that it reads as lost rather than deliberately
 * small — unless the scene is a [SceneType.LANDSCAPE], where a tiny figure against a big sky/vista is a
 * classic, intentional composition and should not be second-guessed. This check is keyed off
 * [SceneClassification.type][com.compositioncoach.composition.model.SceneClassification.type], so a
 * declared [com.compositioncoach.composition.model.SceneIntent.LANDSCAPE] gets the same protection for
 * free — the engine forces the scene type before any analyzer runs (see
 * [com.compositioncoach.composition.engine.CompositionEngine]), and this analyzer never needs to know
 * whether that came from detection or from the photographer.
 */
class NegativeSpaceAnalyzer : CompositionAnalyzer {
    override val name: String = "NegativeSpaceAnalyzer"
    override val category: MetricCategory = MetricCategory.NEGATIVE_SPACE

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
        if (subject == null) {
            return CompositionMetric(category, name, score = NEUTRAL_SCORE, confidence = 0.3f, applicable = true)
        }

        val tiny = subject.bounds.area < TINY_SUBJECT_AREA
        val protectedByScene = context.scene.type == SceneType.LANDSCAPE

        val recommendation = if (tiny && !protectedByScene) {
            Recommendation(
                id = "negativespace.subjecttoosmall",
                category = category,
                priority = Priority.MEDIUM,
                confidence = 0.6f,
                severity = Severity.MEDIUM,
                title = "Subject is too small",
                instruction = "Move closer",
                reason = "The subject takes up only a tiny fraction of the frame.",
                direction = Direction.CLOSER,
                vector = ReframeVector(zoom = CLOSER_MAGNITUDE),
            )
        } else {
            null
        }

        val score = if (recommendation != null) TINY_SUBJECT_SCORE else NEUTRAL_SCORE

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.5f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "Subject is very small in the frame" else null,
            recommendation = recommendation,
        )
    }

    companion object {
        const val TINY_SUBJECT_AREA = 0.03f
        const val NEUTRAL_SCORE = 0.75f
        const val TINY_SUBJECT_SCORE = 0.4f
        const val CLOSER_MAGNITUDE = 0.2f
    }
}
