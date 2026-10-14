package com.compositioncoach.vision

import android.content.Context

/**
 * Entry point the app uses to construct the vision pipeline.
 *
 * [create] returns a fresh, unstarted [VisionPipeline]; the caller (the app's camera screen) is
 * responsible for calling [FrameAnalysisSource.start] / [FrameAnalysisSource.stop] from its
 * lifecycle and binding [FrameAnalysisSource.imageAnalyzer] to a CameraX `ImageAnalysis` use case.
 */
object VisionPipelineFactory {
    fun create(context: Context): FrameAnalysisSource = VisionPipeline(context.applicationContext)
}
