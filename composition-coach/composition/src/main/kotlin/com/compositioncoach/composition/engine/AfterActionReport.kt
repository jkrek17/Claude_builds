package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FramingCandidate
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.OptimizationResult
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import kotlin.math.abs

/** The one-word verdict on the after-action card. */
enum class Verdict(val label: String) {
    KEEPER("Keeper"),
    GOOD("Good"),
    TRY_AGAIN("Try again"),
}

/**
 * The single "Try next time" line: one physical change, in past-conditional voice, with its predicted gain.
 *
 * @param sentence e.g. "A step to the left would have cleared the pole behind them (+9)"; at most
 *   [AfterActionReport.MAX_SENTENCE_LENGTH] characters, no jargon.
 * @param predictedGain points the change is predicted to add (≥ 1), also shown in the sentence.
 * @param direction the physical move ([Direction.NONE] for light advice that is not a camera move).
 * @param category what the change fixes; the app sends this back as `Feedback.NOT_HELPFUL(category)`.
 * @param recommendationId the engine recommendation this came from, when there is one.
 * @param fromOptimizer true when the line comes from the optimizer's simulated best move.
 */
data class TryNextTime(
    val sentence: String,
    val predictedGain: Int,
    val direction: Direction,
    val category: MetricCategory,
    val recommendationId: String? = null,
    val fromOptimizer: Boolean = false,
)

/**
 * The after-action card's content for one captured photo (see `docs/PRODUCT.md`, "After the shot").
 *
 * @param verdict KEEPER (score ≥ 80), GOOD (60..79), TRY_AGAIN (< 60); any applicable HIGH-severity metric
 *   caps the verdict at GOOD.
 * @param worked up to three plain sentences from the engine's strengths, best first.
 * @param tryNextTime the single change with the biggest predicted gain, or null when there is nothing to say.
 * @param lightNote a plain note about the light when the LIGHT metric found a problem
 *   ("Your subject is darker than the background. Turn so the light is behind you."); null when the light is
 *   fine (a good-light strength already appears in [worked]) or not measured.
 */
data class AfterActionReport(
    val verdict: Verdict,
    val worked: List<String>,
    val tryNextTime: TryNextTime?,
    val lightNote: String?,
) {
    companion object {
        const val KEEPER_SCORE = 80
        const val GOOD_SCORE = 60
        const val MAX_WORKED = 3
        const val MAX_SENTENCE_LENGTH = 90

        /** The optimizer's best move is used when it beats the photo by at least this many points. */
        const val MIN_OPTIMIZER_GAIN = 3f

        /**
         * Builds the report for [result] (normally the still-photo evaluation at `GuidanceLevel.COACH`).
         * [optimization] defaults to the result's own.
         */
        fun build(result: CompositionResult, optimization: OptimizationResult? = result.optimization): AfterActionReport {
            val hasHigh = result.metrics.any { it.applicable && it.severity == Severity.HIGH }
            return AfterActionReport(
                verdict = verdictFor(result.score, hasHigh),
                worked = result.strengths.map { plainStrength(it) }.distinct().take(MAX_WORKED),
                tryNextTime = if (result.awaitingSubject) null else tryNextTime(result, optimization),
                lightNote = lightNote(result.metrics.firstOrNull { it.category == MetricCategory.LIGHT && it.applicable }),
            )
        }

        fun verdictFor(score: Int, hasHighSeverity: Boolean): Verdict = when {
            score >= KEEPER_SCORE && !hasHighSeverity -> Verdict.KEEPER
            score >= GOOD_SCORE -> Verdict.GOOD
            else -> Verdict.TRY_AGAIN
        }

        /** Rewrites the few analyzer strengths that use jargon; everything else passes through. */
        fun plainStrength(strength: String): String = when (strength) {
            "Subject sits on a strong thirds point" -> "Subject sits off centre, where it reads well"
            "Good looking room" -> "Space in front of their face"
            "Strong, well-centred symmetry" -> "Nicely centred and symmetrical"
            "Intentional tight crop" -> "A bold, tight crop"
            "Verticals are nicely parallel" -> "Straight, upright lines"
            "Leading lines draw the eye to your subject" -> "Lines lead the eye to your subject"
            else -> strength
        }

        private fun lightNote(metric: CompositionMetric?): String? {
            val rec = metric?.recommendation ?: return null
            val issue = metric.issue ?: rec.reason ?: return null
            return "${issue.trimEnd('.')}. ${rec.instruction.trimEnd('.')}."
        }

        private fun tryNextTime(result: CompositionResult, optimization: OptimizationResult?): TryNextTime? {
            fromOptimizer(result, optimization)?.let { return it }
            val top = result.recommendations.firstOrNull { !it.id.startsWith("intent.") || it.id == "intent.portrait.too_small" }
                ?: return null
            val gain = Math.round(top.expectedImprovement?.takeIf { it > 0f } ?: estimatedGain(result, top))
                .coerceIn(1, maxOf(1, 100 - result.score))
            return TryNextTime(
                sentence = sentence(top.direction, outcomeFor(top.category, top.id, result), gain, top.id),
                predictedGain = gain,
                direction = top.direction,
                category = top.category,
                recommendationId = top.id,
            )
        }

        private fun fromOptimizer(result: CompositionResult, optimization: OptimizationResult?): TryNextTime? {
            val best: FramingCandidate = optimization?.best ?: return null
            if (best.label == FramingCandidate.CURRENT_LABEL) return null
            val gainPoints = best.predictedScore - optimization.currentScore
            if (gainPoints < MIN_OPTIMIZER_GAIN) return null
            val direction = best.vector.primaryDirection()
            if (direction == Direction.NONE) return null

            val allRecs = result.recommendations + result.metrics.mapNotNull { it.recommendation }
            val matching = allRecs.firstOrNull { it.direction == direction }
            val background = result.metrics.firstOrNull {
                it.applicable && it.category == MetricCategory.BACKGROUND_DISTRACTION && it.recommendation?.region != null
            }?.recommendation
            val (category, id) = when {
                matching != null -> matching.category to matching.id
                background != null && direction in HORIZONTAL -> background.category to background.id
                else -> weakestMovableCategory(result) to null
            }
            val gain = Math.round(gainPoints).coerceAtLeast(1)
            return TryNextTime(
                sentence = sentence(direction, outcomeFor(category, id, result), gain, id),
                predictedGain = gain,
                direction = direction,
                category = category,
                recommendationId = id,
                fromOptimizer = true,
            )
        }

        private val HORIZONTAL = setOf(Direction.LEFT, Direction.RIGHT)

        /** Categories a simulated move can change (the optimizer only moves face/body geometry). */
        private val MOVABLE = listOf(
            MetricCategory.SUBJECT_PLACEMENT, MetricCategory.HEADROOM, MetricCategory.LOOKING_ROOM,
            MetricCategory.EDGE_TENSION, MetricCategory.CROPPING,
        )

        /**
         * When no recommendation names the optimizer's direction, the move is credited to the movable category
         * with the largest weighted shortfall (`weight × confidence × (1 − score)`), falling back to placement.
         */
        private fun weakestMovableCategory(result: CompositionResult): MetricCategory =
            result.metrics.filter { it.applicable && it.category in MOVABLE && it.score < 1f }
                .maxByOrNull { result.weights[it.category] * it.confidence * (1f - it.score.coerceIn(0f, 1f)) }
                ?.category ?: MetricCategory.SUBJECT_PLACEMENT

        /**
         * Rough points gained by fixing [rec]'s category completely: its share of the applicable weight times
         * how far its metric is from ideal. Used only when the engine has no simulated estimate.
         */
        private fun estimatedGain(result: CompositionResult, rec: Recommendation): Float {
            val applicable = result.metrics.filter { it.applicable }
            val total = applicable.sumOf { (result.weights[it.category] * it.confidence).toDouble() }.toFloat()
            val metric = applicable.firstOrNull { it.category == rec.category } ?: return 1f
            if (total <= 0f) return 1f
            val share = result.weights[metric.category] * metric.confidence / total
            val penalty = if (metric.severity == Severity.HIGH) ScoreAggregator.HIGH_SEVERITY_PENALTY else 0f
            return share * (1f - metric.score.coerceIn(0f, 1f)) * 100f + penalty
        }

        /**
         * "<lead> would have <outcome> (+N)", shortened to fit [MAX_SENTENCE_LENGTH]: first the generic
         * outcome "helped", then the bare lead.
         */
        fun sentence(direction: Direction, outcome: String, gain: Int, recommendationId: String? = null): String {
            val lead = leadFor(direction, recommendationId)
            val full = "$lead would have $outcome (+$gain)"
            if (full.length <= MAX_SENTENCE_LENGTH) return full
            val short = "$lead would have helped (+$gain)"
            return if (short.length <= MAX_SENTENCE_LENGTH) short else short.take(MAX_SENTENCE_LENGTH)
        }

        /** The physical change, as the subject of a past-conditional sentence. */
        fun leadFor(direction: Direction, recommendationId: String? = null): String = when (direction) {
            Direction.LEFT -> "A step to the left"
            Direction.RIGHT -> "A step to the right"
            Direction.UP -> "Raising the camera a little"
            Direction.DOWN -> "Lowering the camera a little"
            Direction.CLOSER -> "Moving a little closer"
            Direction.BACK -> "A step back"
            Direction.ROTATE_CLOCKWISE -> "Turning the phone slightly clockwise"
            Direction.ROTATE_COUNTER_CLOCKWISE -> "Turning the phone slightly counter-clockwise"
            Direction.NONE -> when (recommendationId) {
                "light.backlit" -> "Turning so the light was behind you"
                "light.dark" -> "Moving toward the light"
                "light.blown" -> "Stepping into the shade"
                "light.split" -> "Turning them toward the light"
                "separation.blends" -> "A plainer background"
                else -> "A small change of angle"
            }
        }

        /** What the change would have achieved, by category (and recommendation id where it matters). */
        fun outcomeFor(category: MetricCategory, recommendationId: String?, result: CompositionResult): String {
            val who = pronoun(result)
            return when (category) {
                MetricCategory.SUBJECT_PLACEMENT ->
                    if (placementTargetIsCentre(result)) "centred $who" else "placed $who off centre, where it reads better"
                MetricCategory.HORIZON -> "levelled the horizon"
                MetricCategory.HEADROOM -> "fixed the headroom"
                MetricCategory.CROPPING -> when (recommendationId) {
                    "cropping.feet" -> "kept their feet in"
                    "cropping.headtop" -> "kept the top of their head in"
                    else -> "avoided cutting them at a joint"
                }
                MetricCategory.LOOKING_ROOM -> "given $who room to look into"
                MetricCategory.EDGE_TENSION ->
                    if (recommendationId == "edge.tension.group_cutoff") "brought everyone into the frame" else "eased $who away from the edge"
                MetricCategory.BACKGROUND_DISTRACTION ->
                    if (recommendationId == "background.pole") "cleared the pole behind $who" else "cleared the clutter behind $who"
                MetricCategory.SUBJECT_SEPARATION -> "made $who stand out"
                MetricCategory.BALANCE -> "balanced the frame"
                MetricCategory.SYMMETRY -> "centred the scene"
                MetricCategory.NEGATIVE_SPACE -> "made $who bigger in the frame"
                MetricCategory.LEADING_LINES -> "let the lines lead to $who"
                MetricCategory.SCENE_SPECIFIC -> when (recommendationId) {
                    "scene.landscape.horizon" -> "moved the horizon off centre"
                    "scene.architecture.converging" -> "kept the walls straight"
                    else -> "made $who bigger in the frame"
                }
                MetricCategory.LIGHT -> if (who == "them") "lit their face better" else "lit $who better"
            }
        }

        private fun pronoun(result: CompositionResult): String = when (result.primarySubject?.kind) {
            SubjectKind.FACE, SubjectKind.PERSON -> "them"
            SubjectKind.OBJECT, SubjectKind.SALIENT_REGION -> "it"
            null -> "the subject"
        }

        private fun placementTargetIsCentre(result: CompositionResult): Boolean {
            val target = result.metrics.firstOrNull { it.category == MetricCategory.SUBJECT_PLACEMENT }
                ?.geometry?.filterIsInstance<OverlayGeometry.TargetPoint>()?.firstOrNull()?.point
                ?: return false
            return abs(target.x - 0.5f) < CENTRE_TOLERANCE
        }

        private const val CENTRE_TOLERANCE = 0.05f
    }
}
