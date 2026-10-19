package com.compositioncoach.app.di

import android.net.Uri
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.composition.model.CompositionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What was captured, waiting to be shown on the review screen. */
data class ReviewEntry(
    val photoUri: Uri,
    val result: CompositionResult,
    /** What the camera did for this capture; defaults to "unknown" so older call sites keep compiling. */
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    /**
     * Bumped every time the file behind [photoUri] is rewritten in place (auto-level finishing after the
     * review screen is already showing). The Uri itself does not change, so an image loader keyed only on the
     * Uri would keep showing the unlevelled bitmap from its cache: include this in the request's cache key
     * (e.g. Coil `memoryCacheKey("$photoUri#$revision")`) to pick up the new pixels.
     */
    val revision: Int = 0,
    /** True while auto-level is still running for this photo (the review screen shows "Levelling…"). */
    val processing: Boolean = false,
    /**
     * A private `file:` copy of the photo as captured, before auto-level rewrote [photoUri] (in the app's cache,
     * never the gallery; see `OriginalCopyStore`). Set only when auto-level actually ran; drives the review's
     * Compare chip. Deleted when this entry leaves the store ([staleOriginalOf]).
     */
    val originalPhotoUri: Uri? = null,
    // --- Wave 2 after-action contract (filled asynchronously after capture; null/empty until then) ---
    /** The captured photo analysed by the vision detectors (not the last preview frame). */
    val stillAnalysis: com.compositioncoach.composition.model.FrameAnalysis? = null,
    /** The engine's evaluation of [stillAnalysis] at COACH level with the user's adaptive weights. */
    val stillResult: com.compositioncoach.composition.model.CompositionResult? = null,
    /** Verdict, what worked, try next time: built from [stillResult] (falls back to the live result). */
    val report: com.compositioncoach.composition.engine.AfterActionReport? = null,
    /** Up to three better crops with predicted scores. */
    val crops: List<com.compositioncoach.composition.model.CropSuggestion> = emptyList(),
    /** The optimizer's best framing as a crop, for the press-and-hold "What if". */
    val whatIf: com.compositioncoach.composition.model.NormalizedRect? = null,
    /** True while still analysis / crops / post-processing are still being computed. */
    val analysing: Boolean = false,
    /** The outcome this capture was taken under. */
    val outcome: com.compositioncoach.app.outcome.Outcome = com.compositioncoach.app.outcome.Outcome.DEFAULT,
    /** Bokeh/background-separated copy when the outcome produced one (preview on review, save as copy). */
    val processedPreviewUri: Uri? = null,
)

/**
 * The original copy that just became unreachable when the store went from [previous] to [current] (Keep or
 * Retake cleared it, or a newer capture replaced it), or null if it is still in use or there was none.
 */
fun staleOriginalOf(previous: ReviewEntry?, current: ReviewEntry?): Uri? {
    val old = previous?.originalPhotoUri ?: return null
    return old.takeIf { it != current?.originalPhotoUri }
}

/**
 * Tiny in-memory hand-off between the camera and review screens. Navigation-compose does not carry rich
 * objects between destinations, so the just-captured [ReviewEntry] is parked here instead of round-tripping
 * it through a nav argument. Survives configuration changes (lives on [AppContainer]); does not survive
 * process death, which is fine because a killed process re-launches on the camera screen.
 */
class ReviewStore {
    private val _current = MutableStateFlow<ReviewEntry?>(null)
    val current: StateFlow<ReviewEntry?> = _current

    fun put(entry: ReviewEntry) {
        _current.value = entry
    }

    /**
     * Applies [transform] to the current entry only if it is still the photo at [photoUri] (the user may have
     * kept it and taken another, or retaken it, while background processing ran). Returns whether it applied.
     */
    fun updateIfCurrent(photoUri: Uri, transform: (ReviewEntry) -> ReviewEntry): Boolean {
        var applied = false
        _current.update { entry ->
            applied = false
            if (entry != null && entry.photoUri == photoUri) {
                applied = true
                transform(entry)
            } else {
                entry
            }
        }
        return applied
    }

    fun clear() {
        _current.value = null
    }
}
