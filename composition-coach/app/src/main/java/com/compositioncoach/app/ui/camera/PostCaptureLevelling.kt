package com.compositioncoach.app.ui.camera

import android.net.Uri
import android.util.Log
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.di.stalePrivateCopiesOf
import com.compositioncoach.composition.model.DeviceOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture-pipeline step 6 (auto-level) and the lifetime of the pre-level original it leaves for the review's
 * Compare chip, run in [CameraViewModel]'s scope so neither depends on the review screen staying composed.
 *
 * * [run] levels after the review is already showing the photo (decode + rotate + re-encode of a 12 MP JPEG
 *   is ~0.5-1 s), then updates the review entry with the applied angle, `revision + 1` (so the image reloads)
 *   and the original copy. A failure just leaves the unlevelled photo in place.
 * * The original copy (and the "Portrait" preview) is deleted as soon as its entry leaves `ReviewStore` (Keep,
 *   Retake, or a newer capture), or straight away if the review ended while levelling ran; any left over from a
 *   killed process are purged when this is created.
 */
internal class PostCaptureLevelling(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val onPhotoRewritten: (Uri) -> Unit,
    private val onFinished: (CaptureDiagnostics) -> Unit,
) {
    init {
        scope.launch(Dispatchers.IO) {
            container.originalCopies.purge()
            var previous: ReviewEntry? = null
            container.reviewStore.current.collect { entry ->
                stalePrivateCopiesOf(previous, entry).forEach(::deleteCopy)
                previous = entry
            }
        }
    }

    fun run(uri: Uri, diagnostics: CaptureDiagnostics, orientation: DeviceOrientation) {
        scope.launch {
            val levelled = try {
                container.autoLevelProcessor.apply(uri, orientation.rollDegrees, orientation.isReliable)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Auto-level failed; keeping the photo as captured", t)
                null
            }
            val original = levelled?.original?.let(Uri::fromFile)
            val updated = diagnostics.copy(autoLevelAppliedDegrees = levelled?.appliedDegrees)
            val stillReviewing = container.reviewStore.updateIfCurrent(uri) {
                it.copy(
                    diagnostics = updated,
                    revision = if (levelled != null) it.revision + 1 else it.revision,
                    processing = false,
                    originalPhotoUri = original ?: it.originalPhotoUri,
                )
            }
            // Kept or retaken while levelling ran: nobody can compare any more.
            if (!stillReviewing && original != null) withContext(Dispatchers.IO) { deleteCopy(original) }
            if (levelled != null) onPhotoRewritten(uri)
            onFinished(updated)
        }
    }

    private fun deleteCopy(uri: Uri) {
        if (uri.scheme == "file") container.originalCopies.delete(uri.path)
    }

    private companion object {
        const val TAG = "AutoLevel"
    }
}
