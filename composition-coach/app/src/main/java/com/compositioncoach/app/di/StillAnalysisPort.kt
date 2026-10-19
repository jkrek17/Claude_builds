package com.compositioncoach.app.di

import android.graphics.Bitmap
import android.net.Uri
import com.compositioncoach.app.camera.BackgroundSeparator
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.OriginalCopyStore
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.vision.StillImageAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the after-action orchestration ([com.compositioncoach.app.ui.camera.ReviewFlowCoordinator]) needs from
 * the capture side to analyse the *captured* photo (`docs/WAVE2.md`, "Foundation") and to preview the
 * "Portrait" look. Bound in [AppContainer.stillAnalysisPort] to [CaptureStillAnalysisPort]; tests use fakes.
 *
 * Both are called off the main thread and may suspend; both may return null ("not available"), which the
 * review simply shows without that piece.
 */
interface StillAnalysisPort {
    /** Runs the vision detectors on the saved photo at [uri] (decoded upright). Null when unavailable. */
    suspend fun analyze(uri: Uri, isFrontCamera: Boolean): FrameAnalysis?

    /**
     * Builds a background-separated (blurred and darkened background) version of [uri] for the review's
     * "Portrait" preview, from [analysis]'s subject mask. It is a *private* file (app cache), never a gallery
     * photo: the look is never automatic. "Save as copy" exports it to the gallery
     * ([CaptureRepository.exportProcessed], `_portrait`). Null when the mask is not usable or on failure.
     */
    suspend fun separateBackground(uri: Uri, analysis: FrameAnalysis): Uri?
}

/**
 * The real still analysis: [CaptureRepository.decodeForAnalysis] (EXIF-aware, ~1024 px) through
 * [StillImageAnalyzer] (the saved selfie is already mirrored, so `alreadyMirrored = true`), and
 * [CaptureRepository.decodeFull] through [BackgroundSeparator] into a preview in [previews].
 */
class CaptureStillAnalysisPort(
    private val repository: CaptureRepository,
    private val previews: OriginalCopyStore,
    private val analyzer: StillImageAnalyzer = StillImageAnalyzer(),
) : StillAnalysisPort {

    override suspend fun analyze(uri: Uri, isFrontCamera: Boolean): FrameAnalysis? {
        val bitmap = repository.decodeForAnalysis(uri) ?: return null
        return try {
            analyzer.analyze(bitmap, isFrontCamera, alreadyMirrored = true)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun separateBackground(uri: Uri, analysis: FrameAnalysis): Uri? {
        val mask = analysis.subjectMask?.takeIf(BackgroundSeparator::isApplicable) ?: return null
        val full = repository.decodeFull(uri) ?: return null
        val separated: Bitmap = withContext(Dispatchers.Default) {
            try {
                // The front camera's subject sits closer and fills more of the frame: a lighter blur.
                val radiusScale = if (analysis.isFrontCamera) BackgroundSeparator.SELFIE_RADIUS_SCALE else 1f
                BackgroundSeparator.apply(full, mask, radiusScale = radiusScale)
            } finally {
                full.recycle()
            }
        }
        val file = withContext(Dispatchers.IO) {
            try {
                previews.savePreview(separated)
            } finally {
                separated.recycle()
            }
        }
        return file?.let(Uri::fromFile)
    }
}
