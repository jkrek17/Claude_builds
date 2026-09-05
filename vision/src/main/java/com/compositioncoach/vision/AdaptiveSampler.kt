package com.compositioncoach.vision

/**
 * Frame-rate throttle for the analysis pipeline.
 *
 * CameraX delivers frames on its own executor as fast as the camera produces them (bounded only by
 * `STRATEGY_KEEP_ONLY_LATEST`). Running face/pose detectors on every frame would burn battery and fall
 * behind, so [AdaptiveSampler] decides, per incoming frame, whether it is worth analysing:
 *
 *  - A frame is *accepted* when at least [currentIntervalMs] has elapsed since the last accepted frame.
 *    A rejected frame should be closed immediately by the caller without running any detector.
 *  - After each accepted frame finishes, the caller reports how long it took
 *    ([onFrameProcessed]) so the sampler can adapt: if processing is slower than the current interval
 *    (the pipeline can't keep up), the interval grows; if there is headroom, it shrinks back down.
 *
 * The interval is clamped to [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS] and starts at [DEFAULT_INTERVAL_MS].
 * All state is plain fields guarded by `synchronized` on `this` because CameraX may call the analyzer
 * from a single dedicated thread (typical) but callers must not assume that; this class is safe to call
 * from any thread.
 *
 * This class is pure Kotlin (no Android types) so it is directly unit-testable.
 */
class AdaptiveSampler(
    private var targetIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    /** Injectable clock (nanoTime-style, monotonic) so tests don't depend on wall time. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    @Volatile
    private var currentIntervalMs: Long = targetIntervalMs.coerceIn(minIntervalMs, maxIntervalMs)

    @Volatile
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE / 2

    @Volatile
    private var lastLatencyMs: Long = 0L

    /** The interval currently in force, after adaptation. */
    val intervalMs: Long get() = currentIntervalMs

    /** Latency of the most recently completed frame, as reported to [onFrameProcessed]. */
    val lastLatency: Long get() = lastLatencyMs

    /**
     * Call once per incoming frame, before doing any work. Returns true if this frame should be
     * analysed (and the caller must eventually call [onFrameProcessed]); returns false if the frame
     * should be dropped/closed immediately with no further work.
     */
    @Synchronized
    fun shouldAccept(nowMillis: Long = nowMs()): Boolean {
        val elapsed = nowMillis - lastAcceptedAtMs
        if (elapsed < currentIntervalMs) return false
        lastAcceptedAtMs = nowMillis
        return true
    }

    /**
     * Report how long an accepted frame took to process (detectors + stats, not including the
     * throttle check itself). Adapts [intervalMs] upward when the pipeline is falling behind
     * (latency exceeds the interval, i.e. we could not have sustained this rate) and back down,
     * one [STEP_MS] at a time, when there is comfortable headroom (latency well under the interval).
     */
    @Synchronized
    fun onFrameProcessed(latencyMs: Long) {
        lastLatencyMs = latencyMs
        currentIntervalMs = when {
            latencyMs > currentIntervalMs -> {
                // Falling behind: jump the interval up to cover the observed latency (plus margin),
                // so the next frame isn't accepted until we could plausibly have finished this one.
                (latencyMs + STEP_MS).coerceIn(minIntervalMs, maxIntervalMs)
            }
            latencyMs < currentIntervalMs - HEADROOM_MS -> {
                // Comfortable headroom: ease back down towards the target interval.
                (currentIntervalMs - STEP_MS).coerceAtLeast(targetIntervalMs).coerceIn(minIntervalMs, maxIntervalMs)
            }
            else -> currentIntervalMs
        }
    }

    /** Change the desired baseline interval (e.g. from a settings/perf policy). Re-clamped immediately. */
    @Synchronized
    fun setTargetIntervalMs(intervalMs: Long) {
        targetIntervalMs = intervalMs.coerceIn(minIntervalMs, maxIntervalMs)
        // Never silently drop below the new target; adaptation will push it back up if needed.
        if (currentIntervalMs < targetIntervalMs) currentIntervalMs = targetIntervalMs
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 120L
        const val MIN_INTERVAL_MS = 80L
        const val MAX_INTERVAL_MS = 500L
        private const val STEP_MS = 20L
        private const val HEADROOM_MS = 40L
    }
}
