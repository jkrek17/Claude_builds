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
    /** The shooting mode this frame was coached under. */
    val intent: SceneIntent = SceneIntent.AUTO,
    /**
     * True when the declared intent needs a subject that is not in frame yet (PORTRAIT/GROUP with no usable face,
     * OBJECT with no salient region). The score is not meaningful; [recommendations] then carries the single
     * "find your subject" instruction and the UI should show that instead of a number.
     */
    val awaitingSubject: Boolean = false,
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
 *
 * The `display*` geometry fields are the smoothed counterparts of the raw per-frame overlay geometry (see
 * [CompositionMetric.geometry] and [Recommendation.region]) — the preview should draw *these*, never
 * anything read straight off [raw], so the arrow/ring/highlight/level line hold as steady as the headline
 * advice and the score do. Every one is time-smoothed (EMA, ~400 ms time constant) the same way
 * [displayScore] is, held for a brief window (~600 ms) when its source momentarily disappears (a detector
 * blink), and clamped into the visible `0..1` frame; see `composition/README.md`'s Smoothing table.
 */
data class SmoothedComposition(
    val displayScore: Int,
    val activeRecommendations: List<Recommendation>,
    val isShootReady: Boolean,
    val scene: SceneClassification,
    val primarySubject: DetectedSubject?,
    val raw: CompositionResult,
    /** Mirrors [CompositionResult.awaitingSubject]; while true, [displayScore] is the last meaningful score, held. */
    val awaitingSubject: Boolean = false,
    /**
     * Smoothed anchor of the primary subject (eyes for a face, box centre otherwise — see
     * [DetectedSubject.anchorPoint]). Drive the framing **arrow**'s tip from this, not from
     * `raw.primarySubject?.anchorPoint`. Null when there is no primary subject (after the brief blink hold
     * expires); resets (snaps, does not blend) the instant the primary subject's id/kind changes.
     */
    val displayAnchor: NormalizedPoint? = null,
    /**
     * Smoothed target point the coach wants the subject moved to (the `SUBJECT_PLACEMENT` analyzer's
     * `TargetPoint`, i.e. the nearest thirds intersection or the centre). Drive the **target ring** from
     * this. Null whenever the current headline recommendation isn't placement/looking-room/edge-tension
     * advice, or the anchor already sits inside the placement dead zone — a ring floating on screen for
     * advice that isn't about repositioning the subject would be confusing.
     */
    val displayTarget: NormalizedPoint? = null,
    /**
     * Smoothed version of the current headline recommendation's [Recommendation.region]. Drive the
     * **region highlight** from this. Null when the headline carries no region; snaps (no blending) the
     * instant the headline recommendation itself changes, since blending between two unrelated regions
     * (e.g. a cropped foot box fading into a background-distraction column) would draw a meaningless
     * in-between rectangle.
     */
    val displayRegion: NormalizedRect? = null,
    /**
     * Smoothed horizon line — the EMA is taken on the measured angle, then the line is redrawn from it
     * (linearly interpolating the two endpoints directly would not track a rotation correctly). Drive the
     * **level line** from this. Null when no horizon signal is available this frame and none was held over
     * from a recent blink.
     */
    val displayHorizon: OverlayGeometry.Line? = null,
    /** Smoothed bounding box of the primary subject, for any UI element that wants the whole silhouette
     * (not just [displayAnchor]'s single point). Same reset-on-subject-change behaviour as [displayAnchor]. */
    val displaySubjectBounds: NormalizedRect? = null,
) {
    val primaryRecommendation: Recommendation? get() = activeRecommendations.firstOrNull()

    companion object {
        val EMPTY = SmoothedComposition(0, emptyList(), false, SceneClassification.UNKNOWN, null, CompositionResult.empty())
    }
}
