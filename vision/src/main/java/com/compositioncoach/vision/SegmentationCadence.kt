package com.compositioncoach.vision

/**
 * Decides, per accepted frame, whether selfie segmentation should run — and adapts how often it runs
 * under sustained load. Pure Kotlin, no Android/ML Kit types, so it is directly unit-testable.
 *
 * Three rules combine:
 *
 *  1. **Subject gating**: selfie segmentation is a *person* segmenter — running it on a frame with no
 *     face and no body (e.g. a still life: a pint, a plate, a product) is wasted work, since the
 *     result would be a mask of nothing. [onAcceptedFrame] is told whether the *previous* frame had a
 *     face or body and skips entirely when it did not (the true test — whether *this* frame has a
 *     subject — isn't known until face/pose detection for this frame finishes, which is exactly the
 *     work we're trying to avoid running unconditionally).
 *  2. **Base cadence**: when a subject was present, segmentation runs every [intervalFrames] accepted
 *     frames (starts at [normalIntervalFrames] = 3) and is skipped (reusing the previous mask) on the
 *     frames in between.
 *  3. **Thermal/perf ladder**: [recordLatency] feeds in each frame's *measured total pipeline latency*
 *     (independent of, and in addition to, [AdaptiveSampler]'s own frame-rate backoff — this reacts
 *     specifically to segmentation's cost rather than the whole pipeline's). [degradeStreakThreshold]
 *     (default 5) consecutive frames over [degradeLatencyMs] (default 180ms) push [intervalFrames]
 *     from [normalIntervalFrames] to [degradedIntervalFrames] (default 6) — segmentation runs half as
 *     often. Recovery is immediate rather than streak-gated: a single frame under [recoverLatencyMs]
 *     (default 120ms) restores [normalIntervalFrames]. This asymmetry is deliberate: getting *into*
 *     the degraded state should be resistant to one-off latency spikes (a GC pause, a slow frame while
 *     another detector warms up), but getting *out* of it should be prompt once the device has
 *     recovered, since segmentation is a real accuracy signal the engine is going without.
 *
 * [maskAgeFrames] tracks how many accepted frames old the currently-held mask is (0 = refreshed this
 * frame) — surfaced by [VisionPipeline] as `detectorTimings["mask_age"]` so the composition engine (or
 * a debug overlay) can see staleness, not just whether a mask exists.
 */
class SegmentationCadence(
    private val normalIntervalFrames: Int = 3,
    private val degradedIntervalFrames: Int = 6,
    private val degradeLatencyMs: Long = 180L,
    private val recoverLatencyMs: Long = 120L,
    private val degradeStreakThreshold: Int = 5,
) {
    /** Current cadence in accepted frames: [normalIntervalFrames] or [degradedIntervalFrames]. */
    var intervalFrames: Int = normalIntervalFrames
        private set

    private var slowStreak = 0
    private var framesSinceMask = 0

    /** How many accepted frames old the currently-held mask is; 0 right after a frame that ran segmentation. */
    val maskAgeFrames: Int get() = framesSinceMask

    /**
     * Feed the most recently completed frame's total analysis latency. Call once per processed frame,
     * independent of whether that frame ran segmentation — the ladder reacts to overall pipeline
     * health, of which segmentation is one contributor.
     */
    fun recordLatency(latencyMs: Long) {
        if (latencyMs > degradeLatencyMs) {
            slowStreak++
            if (slowStreak >= degradeStreakThreshold) intervalFrames = degradedIntervalFrames
        } else {
            slowStreak = 0
        }
        if (latencyMs < recoverLatencyMs) intervalFrames = normalIntervalFrames
    }

    /**
     * Call once per accepted frame, before deciding whether to invoke the segmenter this frame.
     * @param hadSubjectLastFrame whether the *previous* frame had any face or body detected.
     * @return true if segmentation should run this frame.
     */
    fun onAcceptedFrame(hadSubjectLastFrame: Boolean): Boolean {
        framesSinceMask++
        if (!hadSubjectLastFrame) return false
        val due = framesSinceMask >= intervalFrames
        if (due) framesSinceMask = 0
        return due
    }
}
