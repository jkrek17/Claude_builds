package com.compositioncoach.composition.engine

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.BackgroundDistractionAnalyzer
import com.compositioncoach.composition.analyzer.BalanceAnalyzer
import com.compositioncoach.composition.analyzer.CompositionAnalyzer
import com.compositioncoach.composition.analyzer.CroppingAnalyzer
import com.compositioncoach.composition.analyzer.EdgeTensionAnalyzer
import com.compositioncoach.composition.analyzer.HeadroomAnalyzer
import com.compositioncoach.composition.analyzer.HorizonAnalyzer
import com.compositioncoach.composition.analyzer.LeadingLinesAnalyzer
import com.compositioncoach.composition.analyzer.LookingRoomAnalyzer
import com.compositioncoach.composition.analyzer.NegativeSpaceAnalyzer
import com.compositioncoach.composition.analyzer.SceneSpecificAnalyzer
import com.compositioncoach.composition.analyzer.SubjectPlacementAnalyzer
import com.compositioncoach.composition.analyzer.SubjectSeparationAnalyzer
import com.compositioncoach.composition.analyzer.SymmetryAnalyzer
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.OptimizationResult
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.ScoreWeights
import com.compositioncoach.composition.model.Severity

/**
 * Stateless per-frame evaluation pipeline: classify the scene, resolve subjects, run every analyzer,
 * weight their metrics into a score, rank the resulting advice, then consult [CompositionOptimizer] to
 * sanity-check the top recommendation and estimate how much it would help.
 *
 * Robustness is a hard requirement here: this runs on a live camera preview, so a single misbehaving
 * analyzer (or an unexpected combination of detections) must never crash the pipeline. Each analyzer call
 * is individually guarded and simply skipped on failure, and the whole evaluation is wrapped once more as
 * a last resort, falling back to [CompositionResult.empty].
 */
class CompositionEngine(
    private val analyzers: List<CompositionAnalyzer>,
    private val sceneClassifier: (FrameAnalysis) -> SceneClassification = SceneClassifier::classify,
    private val subjectResolver: (FrameAnalysis) -> SubjectResolution = SubjectResolver::resolve,
    private val recommendationEngine: RecommendationEngine = RecommendationEngine(),
    private val optimizer: CompositionOptimizer = CompositionOptimizer(analyzers),
) {
    fun evaluate(frame: FrameAnalysis, level: GuidanceLevel = GuidanceLevel.BALANCED): CompositionResult {
        return runCatching { evaluateInternal(frame, level) }.getOrElse { CompositionResult.empty(frame.timestampNanos) }
    }

    private fun evaluateInternal(frame: FrameAnalysis, level: GuidanceLevel): CompositionResult {
        val startNanos = System.nanoTime()

        val scene = sceneClassifier(frame)
        val resolution = subjectResolver(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary, level)

        val metrics: List<CompositionMetric> = analyzers.mapNotNull { analyzer ->
            runCatching { analyzer.analyze(context) }.getOrNull()
        }

        val rawScore = ScoreAggregator.aggregate(metrics, scene)
        val ranked = recommendationEngine.rank(metrics, level)
        val optimization = runCatching { optimizer.optimize(frame, scene, rawScore, level) }.getOrNull()
        val finalRecommendations = applyOptimizerInsight(ranked, optimization)

        val isShootReady = rawScore >= SHOOT_READY_SCORE &&
            metrics.none { it.applicable && it.severity == Severity.HIGH }

        return CompositionResult(
            timestampNanos = frame.timestampNanos,
            score = Math.round(rawScore),
            rawScore = rawScore,
            scene = scene,
            metrics = metrics,
            recommendations = finalRecommendations,
            subjects = resolution.subjects,
            primarySubject = resolution.primary,
            isShootReady = isShootReady,
            strengths = recommendationEngine.strengths(metrics),
            improvements = recommendationEngine.improvements(metrics),
            optimization = optimization,
            weights = ScoreWeights.forScene(scene.type),
            engineTimeMs = (System.nanoTime() - startNanos) / 1_000_000,
        )
    }

    /**
     * Uses the optimizer's best predicted candidate two ways: (a) when it beats the current framing by a
     * material margin ([OVERRIDE_MARGIN_POINTS]) and a ranked recommendation already argues for that same
     * direction, that recommendation is promoted to the front (the analyzer-level advice is *validated*
     * by an independent simulation, not replaced by one the analyzers never produced); (b) every
     * recommendation missing an [Recommendation.expectedImprovement] gets one filled in from whichever
     * candidate shares its direction, when one exists.
     */
    private fun applyOptimizerInsight(ranked: List<Recommendation>, optimization: OptimizationResult?): List<Recommendation> {
        if (optimization == null) return ranked
        var result = ranked
        val best = optimization.best
        if (best != null && best.label != "current" && optimization.improvement >= OVERRIDE_MARGIN_POINTS) {
            val bestDirection = best.vector.primaryDirection()
            if (bestDirection != Direction.NONE) {
                val matchIndex = result.indexOfFirst { it.direction == bestDirection }
                if (matchIndex > 0) {
                    val mutable = result.toMutableList()
                    val promoted = mutable.removeAt(matchIndex)
                    mutable.add(0, promoted)
                    result = mutable
                }
            }
        }
        return result.map { rec ->
            if (rec.expectedImprovement != null) return@map rec
            val matching = optimization.candidates.firstOrNull { it.label != "current" && it.vector.primaryDirection() == rec.direction }
            val improvement = matching?.let { (it.predictedScore - optimization.currentScore).coerceAtLeast(0f) }
            if (improvement != null) rec.copy(expectedImprovement = improvement) else rec
        }
    }

    companion object {
        const val SHOOT_READY_SCORE = 88f
        const val OVERRIDE_MARGIN_POINTS = 3f

        /** Default production wiring: every analyzer, wired in the order they read most naturally. */
        fun default(): CompositionEngine = CompositionEngine(defaultAnalyzers())

        fun defaultAnalyzers(): List<CompositionAnalyzer> = listOf(
            HorizonAnalyzer(),
            SubjectPlacementAnalyzer(),
            HeadroomAnalyzer(),
            LookingRoomAnalyzer(),
            EdgeTensionAnalyzer(),
            CroppingAnalyzer(),
            BackgroundDistractionAnalyzer(),
            SubjectSeparationAnalyzer(),
            BalanceAnalyzer(),
            SymmetryAnalyzer(),
            NegativeSpaceAnalyzer(),
            LeadingLinesAnalyzer(),
            SceneSpecificAnalyzer(),
        )
    }
}
