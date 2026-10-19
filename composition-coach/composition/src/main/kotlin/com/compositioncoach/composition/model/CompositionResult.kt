package com.compositioncoach.composition.model

/** One hypothetical nearby framing and the score the optimizer predicts for it. */
data class FramingCandidate(
    val label: String,
    val vector: ReframeVector,
    val predictedScore: Float,
) {
    /**
     * This candidate expressed as a crop of the *current* photo, for the review screen's "What if" view and
     * as a [com.compositioncoach.composition.engine.CropSuggester] candidate. Null for the current framing
     * (label `"current"` or a zero vector) and for any move a crop cannot show (stepping back reveals content
     * that is not in the photo).
     *
     * **Approximation.** The optimizer simulates a real camera move ([com.compositioncoach.composition.geometry.FrameTransform]:
     * content shifts by `(-dx, +dy)`, then scales by `s = 1 + zoom` about the centre). A crop can only reproduce
     * that by moving a smaller window over the photo:
     *  - the window's centre is `(0.5 + dx, 0.5 - dy)`, so the subject shifts by the same amount the pan would
     *    shift it;
     *  - its size (the same fraction of both sides, so the frame's aspect is kept) is
     *    `min(1 / s, 1 - 2|dx|, 1 - 2|dy|)`: the zoom's own window, shrunk further until the shifted window fits
     *    inside the photo. A pure 7 % pan therefore shows as an 86 % crop, slightly tighter than the real move;
     *  - the result is clamped into the frame.
     * The candidate's [predictedScore] is the optimizer's prediction for the real move, not for this crop; use
     * `CropSuggester` when the crop itself must be scored.
     */
    fun asCrop(): NormalizedRect? {
        if (label == CURRENT_LABEL || vector == ReframeVector.ZERO) return null
        val scale = (1f + vector.zoom).coerceAtLeast(MIN_SCALE)
        val size = minOf(1f / scale, 1f - 2f * kotlin.math.abs(vector.dx), 1f - 2f * kotlin.math.abs(vector.dy))
        if (size >= 1f - NO_CHANGE_EPSILON || size < MIN_CROP_SIZE) return null
        val cx = (0.5f + vector.dx).coerceIn(size / 2f, 1f - size / 2f)
        val cy = (0.5f - vector.dy).coerceIn(size / 2f, 1f - size / 2f)
        return NormalizedRect.fromCenter(NormalizedPoint(cx, cy), size, size).clampToFrame()
    }

    companion object {
        /** Label the optimizer gives the unchanged framing. */
        const val CURRENT_LABEL = "current"
        private const val MIN_SCALE = 0.05f
        private const val NO_CHANGE_EPSILON = 0.005f

        /** Below this fraction of the frame a crop is too small to be a sensible "what if". */
        const val MIN_CROP_SIZE = 0.3f
    }
}

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
    /**
     * Group readiness ([com.compositioncoach.composition.engine.GroupReadiness]): filled when the scene is a
     * group portrait, either declared (`intent == GROUP_PORTRAIT`) or detected (`scene.type == GROUP_PORTRAIT`);
     * null otherwise. In a group, the shoot-at-peak shutter should wait for [GroupStatus.ready].
     */
    val groupStatus: GroupStatus? = null,
) {
    /** Shorthand for `groupStatus?.ready`: null when this is not a group scene. */
    val groupReady: Boolean? get() = groupStatus?.ready

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
