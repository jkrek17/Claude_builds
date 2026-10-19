package com.compositioncoach.app.ui.camera

import android.util.Log
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.di.ReviewStore
import com.compositioncoach.composition.model.CropSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The review's two "save as a copy" actions, run in [CameraViewModel]'s scope so a save survives the user
 * tapping Keep straight after. Both write a *new* gallery photo; the original is never touched.
 *
 *  * **Better crop** ([saveCrop]): `CaptureRepository.exportCrop` (full-size decode, crop, JPEG 100, EXIF
 *    capture facts copied, `<original>_crop.jpg`), then [onCropSaved] records the `CROP_CHOSEN` learning event.
 *  * **Portrait** ([savePortrait]): the review's background-separated preview (a private cache file) decoded and
 *    written with `CaptureRepository.exportProcessed(bitmap, "_portrait")`, the same path every processed photo
 *    (stacks included) takes.
 *
 * [onDone] reports whether the copy was written, on the scope's dispatcher (main for the view model).
 */
internal class ReviewCopies(
    private val scope: CoroutineScope,
    private val repository: CaptureRepository,
    private val reviewStore: ReviewStore,
    private val onCropSaved: (CropSuggestion) -> Unit,
) {

    fun saveCrop(crop: CropSuggestion, onDone: (Boolean) -> Unit) {
        val photo = reviewStore.current.value?.photoUri ?: return onDone(false)
        scope.launch {
            val saved = attempt("crop") { repository.exportCrop(photo, crop.rect) } != null
            if (saved) onCropSaved(crop)
            onDone(saved)
        }
    }

    fun savePortrait(onDone: (Boolean) -> Unit) {
        val preview = reviewStore.current.value?.processedPreviewUri ?: return onDone(false)
        scope.launch {
            val saved = attempt("portrait") {
                val bitmap = repository.decodeFull(preview) ?: error("Cannot decode the portrait preview")
                repository.exportProcessed(bitmap, PORTRAIT_SUFFIX)
            } != null
            onDone(saved)
        }
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w(TAG, "Saving the $what copy failed", t)
        null
    }

    private companion object {
        const val TAG = "ReviewCopies"
        const val PORTRAIT_SUFFIX = "_portrait"
    }
}
