package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.engine.StatsHeuristics.strongHorizontalLine
import com.compositioncoach.composition.geometry.RuleOfThirds
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs

/**
 * Rules that only make sense for one particular scene; every other scene is not applicable here. This
 * analyzer owns [MetricCategory.SCENE_SPECIFIC], so any other scene- or intent-specific advice that needs
 * that category (there is only one analyzer per category, see the module README) lives here too rather
 * than in a second analyzer that would double up the category's weight.
 *
 *  - **[SceneType.LANDSCAPE]**: where does the horizon sit vertically? Near the upper or lower thirds
 *    line reads as a deliberate choice (sky-dominant or foreground-dominant); dead centre is the classic
 *    beginner mistake that visually splits the frame in half. The horizon's row is read from the
 *    strongest roughly-horizontal dominant line if one was detected, else from the row where mean row
 *    luminance changes the most (a sky-to-ground brightness step even without a clean detected line).
 *    With a declared [SceneIntent.LANDSCAPE], both of those signals are accepted at a lower strength
 *    ([INTENT_HORIZON_LINE_MIN_STRENGTH] / [INTENT_MIN_LUMINANCE_TRANSITION]): the photographer has
 *    already told us this is a landscape, so a fainter sky/ground step is still worth reading rather than
 *    silently giving up on horizon-placement advice.
 *  - **[SceneType.ARCHITECTURE]**: do the near-vertical dominant lines converge (some leaning one way,
 *    some the other) rather than running parallel? That's the classic "tilted up at a tall building"
 *    keystoning distortion — fixed by stepping back (less extreme up-tilt needed) or levelling the phone.
 *  - **Declared [SceneIntent.PORTRAIT]**: when the primary face is present but small
 *    (< [PORTRAIT_INTENT_SMALL_FACE_HEIGHT] of frame height), suggests moving closer so the portrait
 *    actually reads as one. Gated on the *declared* intent, not just [SceneType.PORTRAIT], so it never
 *    fires for an ordinary AUTO-detected portrait — AUTO behaviour is unchanged by this module.
 */
class SceneSpecificAnalyzer : CompositionAnalyzer {
    override val name: String = "SceneSpecificAnalyzer"
    override val category: MetricCategory = MetricCategory.SCENE_SPECIFIC

    override fun analyze(context: AnalysisContext): CompositionMetric {
        if (context.intent == SceneIntent.PORTRAIT) {
            return analyzePortraitTooSmall(context)
        }
        val stats = context.frame.stats ?: return notApplicable()
        return when (context.scene.type) {
            SceneType.LANDSCAPE -> analyzeLandscape(stats, relaxed = context.intent == SceneIntent.LANDSCAPE)
            SceneType.ARCHITECTURE -> analyzeArchitecture(stats)
            else -> notApplicable()
        }
    }

    private fun notApplicable() = CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

    private fun analyzePortraitTooSmall(context: AnalysisContext): CompositionMetric {
        val face = context.primarySubject?.face ?: return notApplicable()
        if (face.bounds.height >= PORTRAIT_INTENT_SMALL_FACE_HEIGHT) return notApplicable()

        val recommendation = Recommendation(
            id = "intent.portrait.too_small",
            category = category,
            priority = Priority.MEDIUM,
            confidence = 1f,
            severity = Severity.MEDIUM,
            title = "Move closer for a stronger portrait",
            instruction = "Move closer to your subject",
            reason = "Portrait mode wants the face to fill more of the frame.",
            direction = Direction.CLOSER,
            vector = ReframeVector(zoom = PORTRAIT_INTENT_CLOSER_ZOOM),
        )
        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = PORTRAIT_INTENT_TOO_SMALL_SCORE,
            confidence = 1f,
            severity = Severity.MEDIUM,
            issue = "Face is small for a portrait — move closer",
            recommendation = recommendation,
        )
    }

    private fun analyzeLandscape(stats: ImageStatistics, relaxed: Boolean): CompositionMetric {
        val horizonY = horizonRow(stats, relaxed) ?: return notApplicable()

        val nearestThird = RuleOfThirds.nearestHorizontalThird(horizonY)
        val distanceToThird = abs(horizonY - nearestThird)
        val score = (1f - (distanceToThird / MAX_THIRD_DISTANCE).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (distanceToThird <= THIRD_DEAD_ZONE) {
            null
        } else {
            val vector = ReframeVector(dy = horizonY - nearestThird)
            val direction = vector.primaryDirection()
            Recommendation(
                id = "scene.landscape.horizon",
                category = category,
                priority = Priority.MEDIUM,
                confidence = 0.65f,
                severity = if (distanceToThird > CENTER_DEAD_ZONE) Severity.MEDIUM else Severity.LOW,
                title = "Reposition the horizon",
                instruction = InstructionText.forDirection(direction),
                reason = "The horizon reads better near the upper or lower third than dead centre.",
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.6f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "Horizon placement could be improved" else null,
            recommendation = recommendation,
            strength = if (score >= 0.85f) "Well-placed horizon" else null,
        )
    }

    private fun analyzeArchitecture(stats: ImageStatistics): CompositionMetric {
        val verticals = stats.dominantLines.filter { it.isRoughlyVertical }
        val deviations = verticals.map { normalizedVerticalDeviation(it.angleDegrees) }
        val maxPositive = deviations.filter { it > 0f }.maxOrNull() ?: 0f
        val maxNegative = deviations.filter { it < 0f }.minOrNull() ?: 0f
        val converging = verticals.size >= 2 && maxPositive > CONVERGENCE_MIN_DEVIATION && maxNegative < -CONVERGENCE_MIN_DEVIATION

        val worstDeviation = maxOf(maxPositive, -maxNegative)
        val score = if (!converging) 1f else (1f - (worstDeviation / MAX_MEANINGFUL_DEVIATION).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (!converging) {
            null
        } else {
            Recommendation(
                id = "scene.architecture.converging",
                category = category,
                priority = Priority.MEDIUM,
                confidence = 0.6f,
                severity = Severity.MEDIUM,
                title = "Correct converging verticals",
                instruction = "Step back or straighten camera",
                reason = "Vertical lines are converging, a sign of an extreme up-tilt.",
                direction = Direction.NONE,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.55f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "Verticals are converging" else null,
            recommendation = recommendation,
            strength = if (!converging && verticals.isNotEmpty()) "Verticals are nicely parallel" else null,
        )
    }

    /**
     * Estimated vertical position (0..1) of the horizon: a detected horizontal line, or the strongest
     * luminance step. [relaxed] (declared LANDSCAPE intent) accepts a weaker signal on both fronts.
     */
    private fun horizonRow(stats: ImageStatistics, relaxed: Boolean): Float? {
        val lineStrength = if (relaxed) INTENT_HORIZON_LINE_MIN_STRENGTH else HORIZON_LINE_MIN_STRENGTH
        stats.strongHorizontalLine(lineStrength)?.let { return (it.start.y + it.end.y) / 2f }
        var bestRow = -1
        var bestDiff = 0f
        for (row in 1 until stats.gridHeight) {
            var prevSum = 0f
            var curSum = 0f
            for (col in 0 until stats.gridWidth) {
                prevSum += stats.luminanceAt(col, row - 1)
                curSum += stats.luminanceAt(col, row)
            }
            val diff = abs(curSum - prevSum) / stats.gridWidth
            if (diff > bestDiff) {
                bestDiff = diff
                bestRow = row
            }
        }
        val minTransition = if (relaxed) INTENT_MIN_LUMINANCE_TRANSITION else MIN_LUMINANCE_TRANSITION
        if (bestRow < 0 || bestDiff < minTransition) return null
        return bestRow.toFloat() / stats.gridHeight
    }

    /** Deviation (degrees) from perfectly vertical (90°), signed, folded into -90..90. */
    private fun normalizedVerticalDeviation(angleDegrees: Float): Float =
        (((angleDegrees % 180f) + 180f) % 180f) - 90f

    companion object {
        const val THIRD_DEAD_ZONE = 0.04f
        const val CENTER_DEAD_ZONE = 0.1f
        const val MAX_THIRD_DISTANCE = 0.25f
        const val HORIZON_LINE_MIN_STRENGTH = 0.4f
        const val MIN_LUMINANCE_TRANSITION = 0.06f
        /** Relaxed thresholds used once the photographer has declared LANDSCAPE mode (see class kdoc). */
        const val INTENT_HORIZON_LINE_MIN_STRENGTH = 0.3f
        const val INTENT_MIN_LUMINANCE_TRANSITION = 0.03f
        const val CONVERGENCE_MIN_DEVIATION = 4f
        const val MAX_MEANINGFUL_DEVIATION = 20f
        /** Below this fraction of frame height, a declared-PORTRAIT face reads as too small to be the portrait. */
        const val PORTRAIT_INTENT_SMALL_FACE_HEIGHT = 0.12f
        const val PORTRAIT_INTENT_TOO_SMALL_SCORE = 0.5f
        const val PORTRAIT_INTENT_CLOSER_ZOOM = 0.2f
    }
}
