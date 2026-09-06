package com.compositioncoach.vision

/**
 * Feature toggles that are specific to [VisionPipeline]'s implementation rather than the generic
 * [FrameAnalysisSource] contract that `:app` owns. Kept as a separate, small interface so `:app` can
 * opt into these without widening [FrameAnalysisSource] itself:
 *
 * ```
 * (frameAnalysisSource as? VisionFeatureToggles)?.setSegmentationEnabled(false)
 * ```
 *
 * Both toggles default to `true` on a fresh [VisionPipeline].
 */
interface VisionFeatureToggles {
    /**
     * Enable/disable the prominent-object detector (populates [com.compositioncoach.composition.model.FrameAnalysis.objects]).
     * Disabling stops the detector running on the next frame; [com.compositioncoach.composition.model.FrameAnalysis.objects]
     * reports empty (objects are not reused/stale across frames the way pose bodies are).
     */
    fun setObjectDetectionEnabled(enabled: Boolean)

    /**
     * Enable/disable selfie segmentation (populates [com.compositioncoach.composition.model.FrameAnalysis.subjectMask]).
     * Disabling clears any cached mask immediately (mirrors [FrameAnalysisSource.setPoseDetectionEnabled]'s
     * "clears reuse" behaviour) rather than leaving a stale mask reported forever.
     */
    fun setSegmentationEnabled(enabled: Boolean)
}
