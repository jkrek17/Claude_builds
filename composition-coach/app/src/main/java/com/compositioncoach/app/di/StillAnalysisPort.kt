package com.compositioncoach.app.di

import android.net.Uri
import com.compositioncoach.composition.model.FrameAnalysis

/**
 * What the after-action orchestration needs from the capture side to analyse the *captured* photo
 * (`docs/WAVE2.md`, "Foundation"). Defined here so the state layer compiles before the capture engineer's
 * classes land; the integrator binds the real implementation in [AppContainer.stillAnalysisPort].
 *
 * INTEGRATOR binds:
 *  * [analyze] -> `StillImageAnalyzer.analyze(captureRepository.decodeForAnalysis(uri), isFrontCamera)`
 *  * [separateBackground] -> decode full size, `BackgroundSeparator.apply(bitmap, analysis.subjectMask!!, strength)`,
 *    then `captureRepository.exportProcessed(uri, result, "_portrait")`
 *
 * Both are called off the main thread (from `Dispatchers.Default`) and may suspend; both may return null
 * ("not available"), which the review simply shows without that piece.
 */
interface StillAnalysisPort {
    /** Runs the vision detectors on the saved photo at [uri] (decoded upright). Null when unavailable. */
    suspend fun analyze(uri: Uri, isFrontCamera: Boolean): FrameAnalysis?

    /**
     * Writes a background-separated (blurred and darkened background) copy of [uri] for review, using
     * [analysis]'s subject mask (the caller checks it exists and covers 8-70 % of the frame). Never modifies
     * the original. Null when unavailable.
     */
    suspend fun separateBackground(uri: Uri, analysis: FrameAnalysis): Uri?

    /** Default until the capture engineer's classes are bound: nothing is available. */
    object Unavailable : StillAnalysisPort {
        override suspend fun analyze(uri: Uri, isFrontCamera: Boolean): FrameAnalysis? = null
        override suspend fun separateBackground(uri: Uri, analysis: FrameAnalysis): Uri? = null
    }
}
