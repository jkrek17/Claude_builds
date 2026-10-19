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
