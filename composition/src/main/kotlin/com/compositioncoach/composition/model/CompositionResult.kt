package com.compositioncoach.composition.model

/** One hypothetical nearby framing and the score the optimizer predicts for it. */
data class FramingCandidate(
    val label: String,
    val vector: ReframeVector,
    val predictedScore: Float,
)

/** Result of the simulated framing search (Phase 5). */
data class OptimizationResult(
    val currentScore: Float,
    val candidates: List<FramingCandidate>,
    val best: FramingCandidate?,
) {
    val improvement: Float get() = (best?.predictedScore ?: currentScore) - currentScore
}

/**
 * Raw (un-smoothed) evaluation of one frame.
 *
 * @param score 0..100 weighted score for this frame.
 * @param recommendations ranked, already trimmed to the few the UI should show (max ~3).
 * @param strengths / improvements short human sentences for the review screen.
 */
data class CompositionResult(
    val timestampNanos: Long,
    val score: Int,
    val rawScore: Float,
    val scene: SceneClassification,
    val metrics: List<CompositionMetric>,
    val recommendations: List<Recommendation>,
    val subjects: List<DetectedSubject>,
    val primarySubject: DetectedSubject?,
    val isShootReady: Boolean,
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val optimization: OptimizationResult? = null,
    val weights: ScoreWeights,
    val engineTimeMs: Long = 0L,
) {
    companion object {
        fun empty(timestampNanos: Long = 0L) = CompositionResult(
            timestampNanos = timestampNanos,
            score = 0, rawScore = 0f,
            scene = SceneClassification.UNKNOWN,
            metrics = emptyList(), recommendations = emptyList(),
            subjects = emptyList(), primarySubject = null,
            isShootReady = false,
            weights = ScoreWeights.forScene(SceneType.GENERAL),
        )
    }
}

/**
 * What the UI actually displays: the temporally smoothed score and the advice that has been stable long enough
 * to act on. [raw] is the latest un-smoothed result for the debug overlay and review screen.
 */
data class SmoothedComposition(
    val displayScore: Int,
    val activeRecommendations: List<Recommendation>,
    val isShootReady: Boolean,
    val scene: SceneClassification,
    val primarySubject: DetectedSubject?,
    val raw: CompositionResult,
) {
    val primaryRecommendation: Recommendation? get() = activeRecommendations.firstOrNull()

    companion object {
        val EMPTY = SmoothedComposition(0, emptyList(), false, SceneClassification.UNKNOWN, null, CompositionResult.empty())
    }
}
