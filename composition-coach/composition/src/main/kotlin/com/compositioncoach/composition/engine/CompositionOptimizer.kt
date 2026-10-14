package com.compositioncoach.composition.engine

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.CompositionAnalyzer
import com.compositioncoach.composition.geometry.FrameTransform
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.FramingCandidate
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.OptimizationResult
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent

/**
 * A cheap "what if?" search: simulates a handful of nearby framings (pan left/right, tilt up/down, zoom
 * in/out) and predicts each one's score, so the engine can tell the photographer which single move would
 * help most — and can sanity-check that the top recommendation actually points the right way.
 *
 * Candidates are small, deliberately conservative moves (±[PAN_TILT_STEP] ~7% pan/tilt, ±[ZOOM_STEP] ~10%
 * zoom) since this runs on every frame at ~10 Hz and must stay cheap: for each candidate it re-transforms
 * only the face/body geometry via [FrameTransform] (see its kdoc for the documented approximation that
 * [com.compositioncoach.composition.model.ImageStatistics] is left as-is), re-resolves subjects, re-runs
 * every analyzer, and re-aggregates a score — six lightweight simulations per frame, each just pure math
 * over a handful of boxes.
 *
 * Scene classification is *not* re-run per candidate: a 7-10% reframe essentially never changes whether a
 * frame reads as a portrait vs. a landscape, and re-classifying six times a frame for no behavioural
 * benefit would be wasted work. This is a second documented approximation, alongside [FrameTransform]'s.
 */
class CompositionOptimizer(
    private val analyzers: List<CompositionAnalyzer>,
) {
    fun optimize(
        frame: FrameAnalysis,
        scene: SceneClassification,
        currentScore: Float,
        level: GuidanceLevel,
        intent: SceneIntent = SceneIntent.AUTO,
    ): OptimizationResult {
        val candidates = mutableListOf(FramingCandidate("current", ReframeVector.ZERO, currentScore))
        for ((label, vector) in MOVES) {
            val predicted = predictScore(frame, scene, vector, level, intent)
            candidates += FramingCandidate(label, vector, predicted)
        }
        val best = candidates.maxByOrNull { it.predictedScore }
        return OptimizationResult(currentScore, candidates, best)
    }

    private fun predictScore(frame: FrameAnalysis, scene: SceneClassification, vector: ReframeVector, level: GuidanceLevel, intent: SceneIntent): Float {
        val transformed = FrameTransform.transform(frame, vector)
        val resolution = SubjectResolver.resolve(transformed, intent)
        val context = AnalysisContext(transformed, scene, resolution.subjects, resolution.primary, level, intent)
        val metrics: List<CompositionMetric> = analyzers.mapNotNull { analyzer ->
            runCatching { analyzer.analyze(context) }.getOrNull()
        }
        return ScoreAggregator.aggregate(metrics, scene)
    }

    companion object {
        const val PAN_TILT_STEP = 0.07f
        const val ZOOM_STEP = 0.10f

        private val MOVES: List<Pair<String, ReframeVector>> = listOf(
            "left" to ReframeVector(dx = -PAN_TILT_STEP),
            "right" to ReframeVector(dx = PAN_TILT_STEP),
            "down" to ReframeVector(dy = -PAN_TILT_STEP),
            "up" to ReframeVector(dy = PAN_TILT_STEP),
            "closer" to ReframeVector(zoom = ZOOM_STEP),
            "wider" to ReframeVector(zoom = -ZOOM_STEP),
        )
    }
}
