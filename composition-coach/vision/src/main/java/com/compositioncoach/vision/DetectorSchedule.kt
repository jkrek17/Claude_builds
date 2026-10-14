package com.compositioncoach.vision

/**
 * Explicit, tunable per-detector cadence for [VisionPipeline], separate from [SegmentationCadence]
 * (which owns its own subject-gated ladder for the more expensive segmenter). Pure Kotlin, no
 * Android/ML Kit types, so it is directly unit-testable.
 *
 * ## Baseline cadence (tier == [PerformanceTier.FULL])
 * On a Pixel, running face+pose alone already only sustains ~4.7 analyses/s; adding the object
 * detector without a cadence would make that worse. So, of the four detectors:
 *  - **Faces** run on every accepted frame, always — regardless of tier — since framing coaching is
 *    largely built around "is there a face and where is it".
 *  - **Pose** runs every 2nd accepted frame (`n % 2 == 0`); the previous [com.compositioncoach.composition.model.DetectedBody]
 *    list is reused on the frame in between (unchanged from the pre-existing behaviour).
 *  - **Objects** run every 2nd accepted frame too, but *offset* by one frame from pose
 *    (`n % 2 == 1`) so a pose-frame and an object-frame never coincide — this caps how many
 *    detectors ever run concurrently on a single frame at three (faces + one of pose/objects), not
 *    four, without reducing either detector's overall duty cycle. On the (up to 2) frames where
 *    objects don't run, [VisionPipeline] reuses the last object list unchanged rather than reporting
 *    empty — see [OBJECT_STALE_LIMIT_FRAMES] — since ML Kit's object detector reports stable tracking
 *    ids, unlike pose landmarks a slightly-stale box list is still meaningful, cheaper than a hole.
 *
 * ## Tier ladder (see [PerformanceTier] for the thermal/battery-saver mapping)
 *  - [PerformanceTier.FULL]: the baseline cadence above.
 *  - [PerformanceTier.REDUCED]: pose backed off to every 3rd frame; objects unaffected; segmentation
 *    reported as not-allowed via [segmentationAllowedByTier] (the actual on/off decision for
 *    segmentation still also depends on [VisionFeatureToggles.setSegmentationEnabled] and
 *    [SegmentationCadence] — this is one more multiplier on top of those, not a replacement).
 *  - [PerformanceTier.MINIMAL]: pose off entirely (stride 0 = never), objects off entirely,
 *    segmentation not-allowed. Faces are the only detector besides stats left running.
 *
 * All state is guarded by `synchronized(this)` since [VisionPipeline] confines detector work to a
 * single dispatcher but this class makes no assumption about that on its own.
 */
class DetectorSchedule {

    @Volatile private var tier: PerformanceTier = PerformanceTier.FULL
    private var acceptedFrameCount = 0

    /** Frames since the object detector actually ran; `Int.MAX_VALUE` before it has ever run. */
    private var framesSinceObjects = Int.MAX_VALUE

    /** This frame's plan, returned by [onAcceptedFrame]. */
    data class Decision(
        val runPose: Boolean,
        val runObjects: Boolean,
        /**
         * True when [runObjects] is false but a previous frame's object list is still fresh enough
         * ([OBJECT_STALE_LIMIT_FRAMES] accepted frames or newer) to reuse as-is; false means report
         * empty instead (either objects are disabled by this tier, or the cache has gone stale).
         */
        val reuseStaleObjects: Boolean,
    )

    /** Applies a new [PerformanceTier], effective from the next [onAcceptedFrame] call. */
    @Synchronized
    fun setTier(tier: PerformanceTier) {
        this.tier = tier
    }

    @Synchronized
    fun currentTier(): PerformanceTier = tier

    /** Whether segmentation is permitted to run at all under the current tier (see class doc). */
    @Synchronized
    fun segmentationAllowedByTier(): Boolean = tier == PerformanceTier.FULL

    /**
     * Call once per accepted frame, before deciding whether to invoke pose/objects this frame. The
     * caller is responsible for also honouring its own on/off toggles ([VisionFeatureToggles]) — this
     * only decides cadence for whichever detectors are otherwise enabled.
     */
    @Synchronized
    fun onAcceptedFrame(): Decision {
        val n = acceptedFrameCount++
        val poseStride = when (tier) {
            PerformanceTier.FULL -> 2
            PerformanceTier.REDUCED -> 3
            PerformanceTier.MINIMAL -> 0
        }
        val runPose = poseStride > 0 && n % poseStride == 0
        val objectsAllowedByTier = tier != PerformanceTier.MINIMAL
        val runObjects = objectsAllowedByTier && n % OBJECT_STRIDE == OBJECT_OFFSET
        if (runObjects) {
            framesSinceObjects = 0
        } else if (framesSinceObjects != Int.MAX_VALUE) {
            // Saturate at Int.MAX_VALUE rather than overflow after a very long stretch with objects
            // disabled — `Int.MAX_VALUE + 1` wraps around to a negative number, which would then read
            // as "very fresh" instead of "very stale" below.
            framesSinceObjects++
        }
        val reuseStaleObjects = !runObjects && objectsAllowedByTier && framesSinceObjects <= OBJECT_STALE_LIMIT_FRAMES
        return Decision(runPose = runPose, runObjects = runObjects, reuseStaleObjects = reuseStaleObjects)
    }

    /** Resets frame counting and object staleness tracking; call when the pipeline (re)starts. */
    @Synchronized
    fun reset() {
        acceptedFrameCount = 0
        framesSinceObjects = Int.MAX_VALUE
    }

    companion object {
        private const val OBJECT_STRIDE = 2
        private const val OBJECT_OFFSET = 1
        private const val OBJECT_STALE_LIMIT_FRAMES = 2
    }
}
