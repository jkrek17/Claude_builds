package com.compositioncoach.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Saves captured photos to the shared Pictures collection and can delete them again (Retake).
 *
 * Overlays (score badge, guidance banner, grid, debug panel) are Compose layers drawn on top of the
 * [androidx.camera.view.PreviewView] surface; [ImageCapture] reads frames straight from the camera pipeline,
 * so nothing drawn by Compose is ever baked into the saved JPEG. No extra care is needed here for that.
 */
class CaptureRepository(private val context: Context) {

    /** Takes a photo with [imageCapture] and returns the MediaStore [Uri] it was saved to. */
    suspend fun capture(imageCapture: ImageCapture, isFrontCamera: Boolean): Uri {
        val name = "CC_${TIMESTAMP_FORMAT.format(System.currentTimeMillis())}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CompositionCoach")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore did not return a Uri for the new image")

        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageCapture.OutputFileOptions.Builder(resolver, uri, ContentValues()).apply {
                setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFrontCamera })
            }.build()
        } else {
            // API 26-28: MediaStore.Images.Media.EXTERNAL_CONTENT_URI writes only work via a file path;
            // write into the legacy public Pictures/CompositionCoach directory and scan it in afterwards.
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CompositionCoach")
            dir.mkdirs()
            val file = File(dir, name)
            ImageCapture.OutputFileOptions.Builder(file).apply {
                setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFrontCamera })
            }.build()
        }

        val result = suspendCancellableCoroutine<ImageCapture.OutputFileResults> { cont ->
            imageCapture.takePicture(
                outputOptions,
                ImmediateExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cont.resume(outputFileResults)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val savedUri = result.savedUri ?: uri
            val path = pathFromLegacyUri(savedUri)
            if (path != null) {
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg"), null)
            }
            savedUri
        }
    }

    /** Deletes a photo the user chose to retake. */
    suspend fun delete(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun pathFromLegacyUri(uri: Uri): String? = uri.path

    companion object {
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

/** [ImageCapture] requires an [java.util.concurrent.Executor]; the callback itself is what resumes the coroutine. */
private object ImmediateExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) = command.run()
}
